package io.github.jackwener.dsh.feature.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.jackwener.dsh.BuildConfig
import io.github.jackwener.dsh.core.data.SecretStore
import io.github.jackwener.dsh.core.data.SettingsRepository
import io.github.jackwener.dsh.core.provider.SpeechToText
import io.github.jackwener.dsh.core.provider.appRequestHeaders
import io.github.jackwener.dsh.core.provider.appUserAgent
import io.github.jackwener.dsh.core.provider.headerRowsOf
import io.github.jackwener.dsh.core.provider.resolveHeaderRows
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Voice input without a system recognizer (#81): records the microphone, finds the end of speech itself
 * ([Endpointer]) and sends the audio to a configured provider's transcription endpoint ([SpeechToText]).
 * No partial results: the strip says 正在识别 while the request runs.
 */
@Singleton
class CloudSpeech @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val secrets: SecretStore,
) {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps { fun cloudSpeech(): CloudSpeech }

    /** The route voice input would transcribe with, or null when no configured provider offers one. */
    suspend fun route(): SpeechToText.Route? = SpeechToText.pick(settings.current().providers)

    interface Listener {
        fun onReady()
        /** Input level in RecognitionListener.onRmsChanged's rough -2..10 dB scale. */
        fun onLevel(rmsDb: Float)
        /** Recording ended; the transcription request is running. */
        fun onTranscribing()
        fun onResult(text: String)
        fun onError(error: VoiceError)
    }

    /** One utterance: [stop] ends recording and transcribes what was said, [cancel] drops it. Callbacks come on the main thread. */
    inner class Session internal constructor(private val scope: CoroutineScope, private val listener: Listener) {
        private var job: Job? = null
        @Volatile private var stopRequested = false

        fun start() {
            job = scope.launch(Dispatchers.Main) {
                val route = route() ?: return@launch listener.onError(VoiceError.NoRecognizer)
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return@launch listener.onError(VoiceError.Permission)
                val pcm = record() ?: return@launch
                listener.onTranscribing()
                val text = runCatching { withContext(Dispatchers.IO) { transcribe(route, pcm) } }
                if (!isActive) return@launch
                text.fold({ if (it.isBlank()) listener.onError(VoiceError.NoMatch) else listener.onResult(it) },
                    { listener.onError(errorOf(it)) })
            }
        }

        fun stop() { stopRequested = true }

        fun cancel() { job?.cancel(); job = null }

        /** Records until the speaker stops (or [stop]); null when it ended in an error already reported, or nothing was said. */
        @SuppressLint("MissingPermission")
        private suspend fun record(): ByteArray? {
            val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = runCatching { AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, FRAME * 4)) }
                .getOrNull()?.takeIf { it.state == AudioRecord.STATE_INITIALIZED }
                ?: run { listener.onError(VoiceError.Audio); return null }
            val ep = Endpointer()
            val out = ByteArrayOutputStream()
            var ended: Endpointer.Decision = Endpointer.Decision.Continue
            try {
                rec.startRecording()
                if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) { listener.onError(VoiceError.Audio); return null }
                listener.onReady()
                val buf = ShortArray(FRAME)
                while (ended == Endpointer.Decision.Continue && !stopRequested) {
                    val n = withContext(Dispatchers.IO) { rec.read(buf, 0, FRAME) }
                    if (!currentCoroutineContext().isActive) return null
                    if (n <= 0) { listener.onError(VoiceError.Audio); return null }
                    val bytes = ByteArray(n * 2)
                    for (i in 0 until n) { bytes[2 * i] = (buf[i].toInt() and 0xff).toByte(); bytes[2 * i + 1] = (buf[i].toInt() shr 8).toByte() }
                    out.write(bytes)
                    ended = ep.feed(db(buf, n))
                    listener.onLevel(ep.level)
                }
            } finally {
                runCatching { rec.stop() }
                rec.release()
            }
            if (!ep.heard || ended == Endpointer.Decision.NoSpeech) { listener.onError(VoiceError.NoMatch); return null }
            return out.toByteArray()
        }
    }

    fun session(scope: CoroutineScope, listener: Listener) = Session(scope, listener)

    private suspend fun transcribe(route: SpeechToText.Route, pcm: ByteArray): String {
        val p = route.provider
        val key = if (p.hasKey) secrets.read(p.secretName) else null
        val headers = appRequestHeaders(resolveHeaderRows(headerRowsOf(p), p) { secrets.read(it) }, p.sessionHeader, appUserAgent(BuildConfig.VERSION_NAME))
        return SpeechToText.transcribe(p.baseUrl, key, route.model, SpeechToText.wav(pcm, RATE), headers)
    }

    private fun errorOf(t: Throwable): VoiceError {
        Log.w(TAG, "transcription failed", t)
        return when (val f = (t as? SpeechToText.TranscribeException)?.failure) {
            is SpeechToText.Failure.Network -> VoiceError.Network
            is SpeechToText.Failure.Quota -> VoiceError.Busy
            is SpeechToText.Failure.Rejected -> VoiceError.Provider
            is SpeechToText.Failure.Server -> if (f.status == 400 || f.status == 404 || f.status == 422) VoiceError.Provider else VoiceError.Other
            null -> VoiceError.Other
        }
    }

    companion object {
        private const val TAG = "CloudSpeech"
        const val RATE = 16_000
        /** 100 ms of samples. */
        const val FRAME = RATE / 10

        fun of(context: Context): CloudSpeech = EntryPointAccessors.fromApplication(context.applicationContext, Deps::class.java).cloudSpeech()

        /** Frame loudness in dBFS (-90 for silence). */
        fun db(buf: ShortArray, n: Int): Float {
            if (n <= 0) return -90f
            var sum = 0.0
            for (i in 0 until n) sum += buf[i].toDouble() * buf[i]
            val rms = sqrt(sum / n) / 32768.0
            return if (rms <= 0.0) -90f else (20 * log10(rms)).toFloat().coerceAtLeast(-90f)
        }
    }
}

/**
 * End-of-utterance detection on 100 ms frames of loudness (dBFS): an adaptive noise floor, speech once a
 * frame is clearly above it, the end after [silenceMs] of quiet following speech. Nothing said within
 * [noSpeechMs] gives up; [maxMs] caps one utterance. Pure, unit-tested.
 */
class Endpointer(
    private val frameMs: Int = 100,
    private val silenceMs: Int = 1_200,
    private val noSpeechMs: Int = 8_000,
    private val maxMs: Int = 60_000,
) {
    enum class Decision { Continue, End, NoSpeech }

    private var floor: Float? = null
    private var elapsed = 0
    private var quiet = 0
    var heard = false
        private set
    /** The last frame's level in RecognitionListener's -2..10 scale (for the mic halo). */
    var level = -2f
        private set

    fun feed(db: Float): Decision {
        elapsed += frameMs
        val f = floor?.let { if (db < it) db else it + 0.02f * (db - it) } ?: db
        floor = f
        val above = db - f
        level = (above / 3f - 2f).coerceIn(-2f, 10f)
        if (above > SPEECH_DB && db > MIN_SPEECH_DBFS) { heard = true; quiet = 0 } else if (heard && above < QUIET_DB) quiet += frameMs
        return when {
            heard && quiet >= silenceMs -> Decision.End
            elapsed >= maxMs -> if (heard) Decision.End else Decision.NoSpeech
            !heard && elapsed >= noSpeechMs -> Decision.NoSpeech
            else -> Decision.Continue
        }
    }

    private companion object {
        const val SPEECH_DB = 12f
        const val QUIET_DB = 6f
        const val MIN_SPEECH_DBFS = -60f
    }
}
