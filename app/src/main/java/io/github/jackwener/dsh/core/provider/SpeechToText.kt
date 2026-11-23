package io.github.jackwener.dsh.core.provider

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Speech-to-text through a configured provider's OpenAI-compatible `POST {base}/audio/transcriptions` (#81):
 * the voice fallback on phones without any speech recognizer (most vivo / OPPO / Xiaomi / Honor builds ship
 * no Google and no RecognitionService). SiliconFlow serves FunAudioLLM/SenseVoiceSmall free of charge and
 * it is strong on Chinese, so it is preferred; OpenAI and Groq serve their Whisper-family models; any other
 * route counts once its model list has a speech-to-text model (SenseVoice, Whisper, *-transcribe, ASR…).
 */
object SpeechToText {
    /** A provider route and the model that transcribes on it. */
    data class Route(val provider: ProviderConfig, val model: String)

    /** Known transcription models by preset (checked against the vendors' docs, 2026-11). */
    private val PRESET_MODELS = linkedMapOf(
        "siliconflow" to "FunAudioLLM/SenseVoiceSmall",
        "openai" to "gpt-4o-mini-transcribe",
    )
    private const val GROQ_MODEL = "whisper-large-v3-turbo"

    private val STT_ID = Regex("""whisper|sensevoice|transcribe|paraformer|telespeech|(^|[-_/])(asr|stt)($|[-_/.\d])""", RegexOption.IGNORE_CASE)

    fun looksSpeechModel(id: String) = STT_ID.containsMatchIn(id)

    /** The transcription model [p] offers: one in its model list, else its preset's (or host's) known one; null when none is known. */
    fun modelOf(p: ProviderConfig): String? =
        p.models.firstOrNull(::looksSpeechModel) ?: PRESET_MODELS[p.preset]
            ?: GROQ_MODEL.takeIf { runCatching { URL(p.baseUrl).host.endsWith("groq.com") }.getOrDefault(false) }

    /**
     * The route voice input transcribes with: among enabled providers that have a key (or need none), one whose
     * own model list names a speech model first, then by preset preference (SiliconFlow, OpenAI), else any known.
     */
    fun pick(providers: List<ProviderConfig>): Route? {
        val usable = providers.filter { it.enabled && (it.hasKey || presetOf(it.preset)?.keyOptional == true) && it.baseUrl.isNotBlank() }
        usable.firstOrNull { p -> p.models.any(::looksSpeechModel) }?.let { p -> return Route(p, modelOf(p)!!) }
        PRESET_MODELS.keys.forEach { preset -> usable.firstOrNull { it.preset == preset }?.let { return Route(it, modelOf(it)!!) } }
        return usable.firstNotNullOfOrNull { p -> modelOf(p)?.let { Route(p, it) } }
    }

    sealed interface Failure {
        /** 401 / 403: key, scope or the model is not enabled for the account. */
        data class Rejected(val status: Int, val message: String) : Failure
        /** 402 / 429: balance or rate limit. */
        data class Quota(val message: String) : Failure
        data class Network(val message: String) : Failure
        data class Server(val status: Int, val message: String) : Failure
    }

    class TranscribeException(val failure: Failure) : IOException(failure.toString())

    /** Mono 16-bit PCM → a WAV file. */
    fun wav(pcm: ByteArray, sampleRate: Int): ByteArray {
        val b = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(pcm.size).put(pcm)
        return b.array()
    }

    /** The multipart body: `model`, `response_format=json` and the audio as `file`. */
    fun multipart(boundary: String, model: String, audio: ByteArray, fileName: String = "speech.wav"): ByteArray {
        val out = ByteArrayOutputStream()
        fun text(s: String) = out.write(s.toByteArray(Charsets.UTF_8))
        fun field(name: String, value: String) = text("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n")
        field("model", model)
        field("response_format", "json")
        text("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$fileName\"\r\nContent-Type: audio/wav\r\n\r\n")
        out.write(audio)
        text("\r\n--$boundary--\r\n")
        return out.toByteArray()
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** The transcript in an answer: `{"text": …}` (OpenAI, SiliconFlow, Groq), or a plain-text body. */
    fun parseText(body: String): String {
        val o = runCatching { json.parseToJsonElement(body.trim()) as? JsonObject }.getOrNull() ?: return body.trim()
        return (o["text"] as? JsonPrimitive)?.content?.trim().orEmpty()
    }

    fun classify(status: Int, body: String): Failure {
        val msg = VerifyErrors.serverMessage(body).ifEmpty { "HTTP $status" }
        return when (status) {
            401, 403 -> Failure.Rejected(status, msg)
            402, 429 -> Failure.Quota(msg)
            else -> Failure.Server(status, msg)
        }
    }

    /** Sends [wav] for transcription; throws [TranscribeException]. */
    fun transcribe(baseUrl: String, key: String?, model: String, wav: ByteArray, headers: Map<String, String> = emptyMap(),
        connectTimeoutMs: Int = 10_000, readTimeoutMs: Int = 60_000): String {
        val boundary = "dsh" + freshSessionId()
        val body = multipart(boundary, model, wav)
        val c = try { URL(normalizeBaseUrl(baseUrl) + "/audio/transcriptions").openConnection() as HttpURLConnection }
            catch (t: Throwable) { throw TranscribeException(Failure.Network(t.message ?: t.javaClass.simpleName)) }
        try {
            c.requestMethod = "POST"
            c.connectTimeout = connectTimeoutMs
            c.readTimeout = readTimeoutMs
            c.doOutput = true
            c.setFixedLengthStreamingMode(body.size)
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            c.setRequestProperty("content-type", "multipart/form-data; boundary=$boundary")
            c.setRequestProperty("accept", "application/json")
            if (!key.isNullOrBlank()) c.setRequestProperty("authorization", "Bearer ${key.trim()}")
            c.outputStream.use { it.write(body) }
            val status = c.responseCode
            val text = (if (status in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw TranscribeException(classify(status, text))
            return parseText(text)
        } catch (e: TranscribeException) {
            throw e
        } catch (t: IOException) {
            throw TranscribeException(Failure.Network(t.message ?: t.javaClass.simpleName))
        } finally {
            c.disconnect()
        }
    }
}
