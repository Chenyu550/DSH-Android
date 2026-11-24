package io.github.jackwener.dsh.core.session

import io.github.jackwener.dsh.core.engine.JsonRpcException
import io.github.jackwener.dsh.core.session.ReasoningTranslator.Failure
import io.github.jackwener.dsh.core.session.ReasoningTranslator.State
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class ReasoningTranslatorTest {
    private val zh = Locale.forLanguageTag("zh-CN")
    private val en = Locale.ENGLISH
    private val ru = Locale.forLanguageTag("ru")

    @Test fun sameScriptAsTheUiHidesTheAction() {
        val chinese = "用户想读取 config.json，我先调用 read 工具，然后用 grep 搜索 TODO。"
        val english = "The user wants me to read the file first. Then I'll call grep."
        val russian = "Пользователь хочет, чтобы я сначала прочитал файл."
        assertFalse("Chinese reasoning with identifiers, zh UI", ReasoningTranslator.needsTranslation(chinese, zh))
        assertTrue(ReasoningTranslator.needsTranslation(english, zh))
        assertTrue(ReasoningTranslator.needsTranslation(chinese, en))
        assertFalse(ReasoningTranslator.needsTranslation(english, en))
        assertTrue(ReasoningTranslator.needsTranslation(english, ru))
        assertFalse(ReasoningTranslator.needsTranslation(russian, ru))
        assertTrue(ReasoningTranslator.needsTranslation(russian, en))
        // English with a Chinese term stays English.
        assertTrue(ReasoningTranslator.needsTranslation("The setting is called 自动化 in the app; I should open it and check the rules.", zh))
        // Another Latin-script UI: the script cannot tell, so it is offered.
        assertTrue(ReasoningTranslator.needsTranslation(english, Locale.FRENCH))
        // Nothing to translate.
        assertFalse(ReasoningTranslator.needsTranslation("123 + 456 = ?", en))
    }

    @Test fun targetTags() {
        assertEquals("zh-Hans", ReasoningTranslator.targetTag(Locale.forLanguageTag("zh-Hans-CN")))
        assertEquals("zh-Hans", ReasoningTranslator.targetTag(zh))
        assertEquals("zh-Hant", ReasoningTranslator.targetTag(Locale.forLanguageTag("zh-TW")))
        assertEquals("zh-Hant", ReasoningTranslator.targetTag(Locale.forLanguageTag("zh-Hant-HK")))
        assertEquals("ru", ReasoningTranslator.targetTag(Locale.forLanguageTag("ru-RU")))
        assertEquals("en", ReasoningTranslator.targetTag(Locale.US))
    }

    @Test fun answersAndErrorCodes() {
        assertEquals(State.Done("你好"), ReasoningTranslator.parse(buildJsonObject { put("text", "你好"); put("provider", "bing") }))
        val err = { code: String -> buildJsonObject { putJsonObject("error") { put("code", code); put("message", "m") } } }
        assertEquals(State.Failed(Failure.Unreachable, "m"), ReasoningTranslator.parse(err("TRANSLATION_TIMEOUT")))
        assertEquals(Failure.Unreachable, (ReasoningTranslator.parse(err("TRANSLATION_HTTP_ERROR")) as State.Failed).reason)
        assertEquals(Failure.BadResponse, (ReasoningTranslator.parse(err("TRANSLATION_INVALID_RESPONSE")) as State.Failed).reason)
        assertEquals(Failure.Other, (ReasoningTranslator.parse(err("TRANSLATION_TEXT_LIMIT")) as State.Failed).reason)
    }

    @Test fun oneCallPerTextAndLanguageFailuresRetry() = runBlocking {
        val calls = mutableListOf<Pair<String, String>>()
        val gate = CompletableDeferred<Unit>()
        var fail = true
        val tr = ReasoningTranslator({ text, lang ->
            calls += text to lang
            gate.await()
            if (fail) throw JsonRpcException(-32601, "unknown DeepSeek Harness SDK runtime method: android/reasoning/translate")
            buildJsonObject { put("text", "[$lang] $text") } as JsonElement
        }, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val key = ReasoningTranslator.Key("think", "ru")
        tr.request("think", "ru")
        tr.request("think", "ru")
        assertEquals(State.Loading, tr.states.value[key])
        gate.complete(Unit)
        val failed = withTimeout(5_000) { tr.states.first { it[key] is State.Failed } }[key] as State.Failed
        assertEquals(Failure.Engine, failed.reason)
        assertEquals(1, calls.size)
        fail = false
        tr.request("think", "ru")
        assertEquals(State.Done("[ru] think"), withTimeout(5_000) { tr.states.first { it[key] is State.Done } }[key])
        tr.request("think", "ru")
        tr.request("think", "zh-Hans")
        withTimeout(5_000) { tr.states.first { it[ReasoningTranslator.Key("think", "zh-Hans")] is State.Done } }
        assertEquals(listOf("think" to "ru", "think" to "ru", "think" to "zh-Hans"), calls)
    }
}
