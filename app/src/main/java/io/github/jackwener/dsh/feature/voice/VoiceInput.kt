package io.github.jackwener.dsh.feature.voice

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.jackwener.dsh.feature.conversation.ComposerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Speech → composer text: the system recognizer streams partial results into the draft, and the
 * final text is sent after a short countdown ([VoiceMachine]) unless the user edits or cancels.
 *
 * Recognizer choice: the on-device one (API 31+) when the system has it, else a recognition
 * service other than DSH's own ([Recognizers]), else a configured provider's transcription
 * ([CloudSpeech]: phones without Google usually have no recognizer at all, #81), else the
 * RECOGNIZE_SPEECH activity of some app, else the keyboard.
 */
@Stable
class VoiceInput internal constructor(private val context: Context, private val composer: ComposerState, private val scope: CoroutineScope) {
    private val machine = VoiceMachine()
    var state: VoiceState by mutableStateOf(VoiceState.Idle)
        private set
    val active get() = state is VoiceState.Listening || state is VoiceState.Countdown

    internal var onSend: () -> Unit = {}
    internal var requestPermission: () -> Unit = {}
    internal var launchActivity: (Intent) -> Boolean = { false }

    private var recognizer: SpeechRecognizer? = null
    private var cloudSession: CloudSpeech.Session? = null
    private val cloud by lazy { CloudSpeech.of(context) }
    /** The start() still deciding between transcription and a recognizer activity; a newer start or cancel replaces it. */
    private var starting: Any? = null
    private var onDevice = false
    private var draft = ""
    private var timer: Job? = null

    fun toggle() = if (state is VoiceState.Listening) stop() else start()

    fun start() {
        cancel()
        draft = composer.text
        val service = Recognizers.best(context)
        val canOnDevice = Build.VERSION.SDK_INT >= 31 && runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)
        if (canOnDevice || service != null) {
            if (!hasMic()) { requestPermission(); return }
            dispatch(VoiceEvent.Start)
            listen(onDevice = canOnDevice, service)
            return
        }
        dispatch(VoiceEvent.Start)
        val attempt = Any().also { starting = it }
        scope.launch {
            val transcribes = runCatching { cloud.route() != null }.getOrDefault(false)
            if (starting !== attempt || state !is VoiceState.Listening) return@launch
            when {
                transcribes && !hasMic() -> { dispatch(VoiceEvent.Cancel); requestPermission() }
                transcribes -> listenCloud()
                // A recognizer activity needs no microphone permission of ours.
                !launchActivity(recognizeIntent()) -> dispatch(VoiceEvent.Error(VoiceError.NoRecognizer))
            }
        }
    }

    private fun hasMic() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Stops taking audio: the recognizer then delivers the final result (nothing heard yet: just cancels). */
    fun stop() {
        val s = state as? VoiceState.Listening ?: return
        cloudSession?.let { if (!s.transcribing) it.stop(); return }
        val r = recognizer
        if (r != null && s.text.isNotEmpty()) r.stopListening() else cancel()
    }

    /** Keeps whatever is in the composer and sends nothing. */
    fun cancel() { starting = null; if (state != VoiceState.Idle) dispatch(VoiceEvent.Cancel) }

    /** The user typed in the composer while we were writing to it. */
    fun onUserEdit() = cancel()

    internal fun onPermission(granted: Boolean) {
        if (granted) start() else { dispatch(VoiceEvent.Start); dispatch(VoiceEvent.Error(VoiceError.Permission)) }
    }

    internal fun onActivityResult(resultCode: Int, data: Intent?) {
        val text = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
        // The recognizer activity covering the panel stopped it (and so cancelled): listen again to take the result.
        if (resultCode == Activity.RESULT_OK && text.isNotBlank()) { dispatch(VoiceEvent.Start); dispatch(VoiceEvent.Final(text)) } else cancel()
    }

    internal fun release() { cancel(); destroyRecognizer() }

    private fun listen(onDevice: Boolean, service: ComponentName?) {
        destroyRecognizer()
        this.onDevice = onDevice
        val r = runCatching {
            if (onDevice && Build.VERSION.SDK_INT >= 31) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            else SpeechRecognizer.createSpeechRecognizer(context, service)
        }.getOrNull() ?: return dispatch(VoiceEvent.Error(VoiceError.NoRecognizer))
        recognizer = r
        r.setRecognitionListener(Listener(r, service))
        runCatching { r.startListening(recognizeIntent()) }.onFailure { dispatch(VoiceEvent.Error(VoiceError.Other)) }
    }

    private fun listenCloud() {
        destroyRecognizer()
        lateinit var session: CloudSpeech.Session
        session = cloud.session(scope, object : CloudSpeech.Listener {
            private val current get() = cloudSession === session
            override fun onReady() { if (current) dispatch(VoiceEvent.Ready) }
            override fun onLevel(rmsDb: Float) { if (current) dispatch(VoiceEvent.Level(rmsDb)) }
            override fun onTranscribing() { if (current) dispatch(VoiceEvent.Transcribing) }
            override fun onResult(text: String) { if (current) dispatch(VoiceEvent.Final(text)) }
            override fun onError(error: VoiceError) { if (current) dispatch(VoiceEvent.Error(error)) }
        })
        cloudSession = session
        session.start()
    }

    private inner class Listener(private val r: SpeechRecognizer, private val service: ComponentName?) : RecognitionListener {
        private val current get() = recognizer === r
        override fun onReadyForSpeech(params: Bundle?) { if (current) dispatch(VoiceEvent.Ready) }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) { if (current) dispatch(VoiceEvent.Level(rmsdB)) }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onPartialResults(partialResults: Bundle?) { if (current) dispatch(VoiceEvent.Partial(best(partialResults))) }
        override fun onResults(results: Bundle?) { if (current) dispatch(VoiceEvent.Final(best(results))) }
        override fun onError(error: Int) {
            if (!current) return
            val heard = (state as? VoiceState.Listening)?.text.orEmpty()
            // The on-device model may lack the language (or fail outright): retry once with the service recognizer.
            if (onDevice && heard.isEmpty() && service != null && error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) return listen(onDevice = false, service)
            dispatch(VoiceEvent.Error(VoiceMachine.errorOf(error)))
        }
    }

    private fun best(b: Bundle?) = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()

    private fun dispatch(e: VoiceEvent) {
        val effect = machine.on(e)
        state = machine.state
        when (val s = state) {
            is VoiceState.Listening -> if (s.text.isNotEmpty()) composer.text = VoiceMachine.join(draft, s.text)
            is VoiceState.Countdown -> if (e !is VoiceEvent.Tick) { composer.text = VoiceMachine.join(draft, s.text); runTimer() }
            is VoiceState.Failed -> if (e !is VoiceEvent.Tick) runTimer()
            VoiceState.Idle -> {}
        }
        when (effect) {
            is VoiceEffect.Send -> onSend()
            VoiceEffect.Release -> destroyRecognizer()
            null -> {}
        }
        if (!active && state !is VoiceState.Failed) { timer?.cancel(); timer = null }
    }

    /** Countdown ticks, or clearing an error after a few seconds. */
    private fun runTimer() {
        timer?.cancel()
        timer = scope.launch {
            if (state is VoiceState.Failed) { delay(ERROR_SHOWN_MS); if (state is VoiceState.Failed) { machine.on(VoiceEvent.Cancel); state = machine.state }; return@launch }
            var last = SystemClock.uptimeMillis()
            while (state is VoiceState.Countdown) {
                delay(TICK_MS)
                val now = SystemClock.uptimeMillis()
                dispatch(VoiceEvent.Tick(now - last))
                last = now
            }
        }
    }

    private fun destroyRecognizer() {
        cloudSession?.let { cloudSession = null; it.cancel() }
        val r = recognizer ?: return
        recognizer = null
        runCatching { r.cancel(); r.destroy() }
    }

    private fun recognizeIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)

    companion object {
        private const val TICK_MS = 50L
        private const val ERROR_SHOWN_MS = 4000L
    }
}

/** A [VoiceInput] writing into [composer]; [onSend] sends the composer as the Send button would. */
@Composable
fun rememberVoiceInput(composer: ComposerState, onSend: () -> Unit): VoiceInput {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val voice = remember(composer) { VoiceInput(context, composer, scope) }
    val send by rememberUpdatedState(onSend)
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { voice.onPermission(it) }
    val activity = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { voice.onActivityResult(it.resultCode, it.data) }
    voice.onSend = { send() }
    voice.requestPermission = { permission.launch(Manifest.permission.RECORD_AUDIO) }
    voice.launchActivity = { i -> i.resolveActivity(context.packageManager) != null && runCatching { activity.launch(i) }.isSuccess }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(voice, lifecycle) {
        // Leaving the screen stops listening and any pending auto-send; the text stays in the draft.
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) voice.cancel() }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs); voice.release() }
    }
    return voice
}
