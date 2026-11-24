package io.github.jackwener.dsh.service

import android.app.assist.AssistStructure
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import io.github.jackwener.dsh.feature.quick.QuickActivity
import io.github.jackwener.dsh.feature.quick.ScreenContext
import io.github.jackwener.dsh.feature.voice.CloudSpeech
import io.github.jackwener.dsh.feature.voice.Recognizers
import io.github.jackwener.dsh.feature.voice.VoiceError
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel

/** Lets the user pick DSH as the system "digital assistant" (long-press power / gesture). */
class DshVoiceInteractionService : VoiceInteractionService()

class DshVoiceSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = DshVoiceSession(this)
}

/** Receives the current screen's structure from the system, then opens the quick panel with it. */
class DshVoiceSession(context: Context) : VoiceInteractionSession(context) {
    override fun onHandleAssist(state: AssistState) {
        val structure = state.assistStructure
        ScreenContext.fromAssist(structure?.let(::flatten), structure?.activityComponent?.packageName)
        startAssistantActivity(Intent(context, QuickActivity::class.java).putExtra(QuickActivity.EXTRA_FROM_ASSIST, true))
        finish()
    }

    private fun flatten(s: AssistStructure): String = buildString {
        fun walk(n: AssistStructure.ViewNode, depth: Int) {
            val t = n.text?.toString()?.trim()
            val d = n.contentDescription?.toString()?.trim()
            if (!t.isNullOrEmpty() || !d.isNullOrEmpty()) append("  ".repeat(minOf(depth, 10))).append(t ?: d).append('\n')
            for (i in 0 until n.childCount) walk(n.getChildAt(i), depth + 1)
        }
        for (i in 0 until s.windowNodeCount) walk(s.getWindowNodeAt(i).rootViewNode, 0)
    }.take(8000)

}

/**
 * The assistant's required recognitionService. Android makes it the system default recognizer while DSH is the
 * default assistant (#81), so Gboard voice typing and every other app's SpeechRecognizer land here: it forwards
 * to the device's real recognizer ([Recognizers.best]); a phone with none transcribes through the provider voice
 * input uses ([CloudSpeech]), else reports a client error. DSH's own voice input never comes here.
 */
class DshRecognitionService : android.speech.RecognitionService() {
    private val scope = MainScope()
    private var proxy: SpeechRecognizer? = null
    private var cloud: CloudSpeech.Session? = null

    override fun onStartListening(intent: Intent?, listener: Callback?) {
        listener ?: return
        release()
        val target = Recognizers.best(this)
        if (target != null) forward(target, intent, listener) else transcribe(listener)
    }

    override fun onStopListening(listener: Callback?) {
        proxy?.stopListening()
        cloud?.stop()
    }

    override fun onCancel(listener: Callback?) = release()

    override fun onDestroy() {
        release()
        scope.cancel()
        super.onDestroy()
    }

    private fun release() {
        proxy?.let { r -> proxy = null; runCatching { r.cancel(); r.destroy() } }
        cloud?.let { c -> cloud = null; c.cancel() }
    }

    private fun forward(target: ComponentName, intent: Intent?, cb: Callback) {
        val r = runCatching { SpeechRecognizer.createSpeechRecognizer(this, target) }.getOrNull()
            ?: return send { cb.error(SpeechRecognizer.ERROR_CLIENT) }
        proxy = r
        r.setRecognitionListener(object : RecognitionListener {
            private val current get() = proxy === r
            override fun onReadyForSpeech(params: Bundle?) { if (current) send { cb.readyForSpeech(params ?: Bundle()) } }
            override fun onBeginningOfSpeech() { if (current) send { cb.beginningOfSpeech() } }
            override fun onRmsChanged(rmsdB: Float) { if (current) send { cb.rmsChanged(rmsdB) } }
            override fun onBufferReceived(buffer: ByteArray?) { if (current && buffer != null) send { cb.bufferReceived(buffer) } }
            override fun onEndOfSpeech() { if (current) send { cb.endOfSpeech() } }
            override fun onPartialResults(partialResults: Bundle?) { if (current) send { cb.partialResults(partialResults ?: Bundle()) } }
            override fun onResults(results: Bundle?) { if (current) send { cb.results(results ?: Bundle()) } }
            override fun onError(error: Int) { if (current) send { cb.error(error) } }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        runCatching { r.startListening(intent ?: Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)) }.onFailure { send { cb.error(SpeechRecognizer.ERROR_CLIENT) } }
    }

    private fun transcribe(cb: Callback) {
        lateinit var session: CloudSpeech.Session
        session = CloudSpeech.of(this).session(scope, object : CloudSpeech.Listener {
            private val current get() = cloud === session
            override fun onReady() { if (current) send { cb.readyForSpeech(Bundle()) } }
            override fun onLevel(rmsDb: Float) { if (current) send { cb.rmsChanged(rmsDb) } }
            override fun onTranscribing() { if (current) send { cb.endOfSpeech() } }
            override fun onResult(text: String) {
                if (current) send { cb.results(Bundle().apply { putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text)) }) }
            }
            override fun onError(error: VoiceError) { if (current) send { cb.error(codeOf(error)) } }
        })
        cloud = session
        session.start()
    }

    /** The client may have gone away meanwhile (RemoteException). */
    private inline fun send(block: () -> Unit) { runCatching(block) }

    private fun codeOf(e: VoiceError) = when (e) {
        VoiceError.Permission -> SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
        VoiceError.NoMatch -> SpeechRecognizer.ERROR_NO_MATCH
        VoiceError.Network -> SpeechRecognizer.ERROR_NETWORK
        VoiceError.Busy -> SpeechRecognizer.ERROR_RECOGNIZER_BUSY
        VoiceError.Audio -> SpeechRecognizer.ERROR_AUDIO
        VoiceError.Provider -> SpeechRecognizer.ERROR_SERVER
        VoiceError.Language -> SpeechRecognizer.ERROR_CLIENT
        VoiceError.NoRecognizer, VoiceError.Other -> SpeechRecognizer.ERROR_CLIENT
    }
}
