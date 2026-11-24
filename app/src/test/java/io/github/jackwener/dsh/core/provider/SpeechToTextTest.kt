package io.github.jackwener.dsh.core.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SpeechToTextTest {
    private fun p(id: String, preset: String, models: List<String> = emptyList(), key: Boolean = true, url: String = "https://x.test/v1", enabled: Boolean = true) =
        ProviderConfig(id, preset, id, url, models, enabled = enabled, hasKey = key)

    @Test fun picksAProviderThatTranscribes() {
        // #81's reporter: OpenRouter only, no transcription.
        assertNull(SpeechToText.pick(listOf(p("openrouter", "openrouter", listOf("deepseek/deepseek-v4.1-flash")))))
        val sf = p("siliconflow", "siliconflow", listOf("deepseek-ai/DeepSeek-V4"))
        assertEquals(SpeechToText.Route(sf, "FunAudioLLM/SenseVoiceSmall"), SpeechToText.pick(listOf(p("openai", "openai"), sf)))
        assertEquals("gpt-4o-mini-transcribe", SpeechToText.pick(listOf(p("openai", "openai")))?.model)
        // A model list naming a speech model wins (a custom gateway serving whisper).
        val custom = p("custom", "custom", listOf("llama-4", "whisper-large-v3"))
        assertEquals(SpeechToText.Route(custom, "whisper-large-v3"), SpeechToText.pick(listOf(sf, custom)))
        assertEquals("whisper-large-v3-turbo", SpeechToText.pick(listOf(p("groq", "custom", url = "https://api.groq.com/openai/v1")))?.model)
        // Disabled, or no key where one is needed: not used.
        assertNull(SpeechToText.pick(listOf(p("siliconflow", "siliconflow", key = false), p("openai", "openai", enabled = false))))
    }

    @Test fun recognisesSpeechModelIds() {
        listOf("whisper-1", "FunAudioLLM/SenseVoiceSmall", "gpt-4o-mini-transcribe", "TeleAI/TeleSpeechASR", "paraformer-v2", "glm-asr", "qwen3-asr-flash")
            .forEach { assertTrue(it, SpeechToText.looksSpeechModel(it)) }
        listOf("gpt-5", "deepseek-chat", "fast-model", "glm-5.3", "kimi-k3").forEach { assertTrue(it, !SpeechToText.looksSpeechModel(it)) }
    }

    @Test fun wavHeader() {
        val pcm = ByteArray(3200) { 1 }
        val w = SpeechToText.wav(pcm, 16_000)
        assertEquals(44 + 3200, w.size)
        assertEquals("RIFF", String(w, 0, 4))
        assertEquals("WAVE", String(w, 8, 4))
        val b = ByteBuffer.wrap(w).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(36 + 3200, b.getInt(4))
        assertEquals(16_000, b.getInt(24))
        assertEquals(32_000, b.getInt(28))
        assertEquals("data", String(w, 36, 4))
        assertEquals(3200, b.getInt(40))
    }

    @Test fun multipartBody() {
        val body = String(SpeechToText.multipart("B", "whisper-1", "AUDIO".toByteArray()))
        assertTrue(body.startsWith("--B\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\nwhisper-1\r\n"))
        assertTrue("name=\"file\"; filename=\"speech.wav\"\r\nContent-Type: audio/wav\r\n\r\nAUDIO\r\n--B--\r\n" in body)
    }

    @Test fun answersAndErrors() {
        assertEquals("你好世界", SpeechToText.parseText("""{"text":" 你好世界 "}"""))
        assertEquals("plain", SpeechToText.parseText("plain\n"))
        assertTrue(SpeechToText.classify(401, """{"error":{"message":"Invalid API key"}}""") is SpeechToText.Failure.Rejected)
        assertTrue(SpeechToText.classify(429, "{}") is SpeechToText.Failure.Quota)
        assertEquals(SpeechToText.Failure.Server(404, "Not Found"), SpeechToText.classify(404, """{"message":"Not Found"}"""))
    }
}
