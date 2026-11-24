package io.github.jackwener.dsh.core.session

import io.github.jackwener.dsh.core.engine.EngineManager
import io.github.jackwener.dsh.core.engine.JsonRpcException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.lang.Character.UnicodeScript
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-demand translation of reasoning text into the UI language (#15): the engine's
 * `android/reasoning/translate` (plugin-guard translate.js over dsh's translator service; anonymous
 * Bing, else Google; no key, no model call). Results are kept per text and language for the
 * process, so a reasoning row translated once stays translated when the chat is recomposed,
 * reopened or shown in the quick panel; failures stay until retried.
 */
@Singleton
class ReasoningTranslator internal constructor(
    private val call: suspend (text: String, targetLanguage: String) -> JsonElement,
    private val scope: CoroutineScope,
) {
    @Inject constructor(engine: EngineManager) : this(
        call = { text, target ->
            // control(): no credentials needed, and a running conversation's engine is never restarted for it.
            engine.control().request(METHOD, buildJsonObject { put("text", text); put("targetLanguage", target) })
        },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )

    data class Key(val text: String, val language: String)

    sealed interface State {
        data object Loading : State
        data class Done(val text: String) : State
        data class Failed(val reason: Failure, val detail: String = "") : State
    }

    enum class Failure { Unreachable, BadResponse, Engine, Other }

    private val _states = MutableStateFlow<Map<Key, State>>(emptyMap())
    val states: StateFlow<Map<Key, State>> = _states

    /** Translates [text] into [language] (a [targetTag]), unless it is done or under way. */
    fun request(text: String, language: String) {
        val key = Key(text, language)
        var start = false
        _states.update { m ->
            val cur = m[key]
            if (cur is State.Done || cur is State.Loading) m
            else { start = true; bounded(m + (key to State.Loading)) }
        }
        if (!start) return
        scope.launch {
            val result = try {
                withTimeout(TIMEOUT_MS) { parse(call(text, language)) }
            } catch (e: JsonRpcException) {
                State.Failed(if (e.code == -32601 || e.message?.contains("unknown DeepSeek Harness SDK runtime method") == true) Failure.Engine else Failure.Other, e.message ?: "")
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                State.Failed(Failure.Unreachable)
            } catch (e: CancellationException) {
                _states.update { it - key }
                throw e
            } catch (e: Exception) {
                // The engine could not start or went away mid-call.
                State.Failed(Failure.Engine, e.message ?: "")
            }
            _states.update { bounded(it + (key to result)) }
        }
    }

    companion object {
        const val METHOD = "android/reasoning/translate"
        private const val TIMEOUT_MS = 120_000L
        /** Kept translations (and failures); the oldest go first. */
        private const val MAX_ENTRIES = 200

        private fun bounded(m: Map<Key, State>): Map<Key, State> =
            if (m.size <= MAX_ENTRIES) m else m.entries.drop(m.size - MAX_ENTRIES).associate { it.key to it.value }

        /** `{ text }` or `{ error: { code, message } }` (TranslationErrorCode). */
        internal fun parse(res: JsonElement): State {
            val o = res as? JsonObject ?: return State.Failed(Failure.Other)
            o["text"]?.jsonPrimitive?.contentOrNull?.let { return State.Done(it) }
            val err = o["error"] as? JsonObject ?: return State.Failed(Failure.Other)
            val code = err["code"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val message = err["message"]?.jsonPrimitive?.contentOrNull.orEmpty()
            return State.Failed(failureOf(code), message)
        }

        internal fun failureOf(code: String): Failure = when (code) {
            "TRANSLATION_REQUEST_FAILED", "TRANSLATION_TIMEOUT", "TRANSLATION_HTTP_ERROR" -> Failure.Unreachable
            "TRANSLATION_INVALID_RESPONSE", "TRANSLATION_RESPONSE_LIMIT" -> Failure.BadResponse
            else -> Failure.Other
        }

        /**
         * The language tag sent for [locale]: its language, Chinese as zh-Hans / zh-Hant (the
         * translation endpoints know those, not zh-Hans-CN).
         */
        fun targetTag(locale: Locale): String = when (locale.language) {
            "zh" -> if (locale.script == "Hant" || (locale.script.isEmpty() && locale.country in setOf("TW", "HK", "MO"))) "zh-Hant" else "zh-Hans"
            "" -> "en"
            else -> locale.language
        }

        /**
         * Whether [text] is worth translating for a reader of [locale]: false when it is already
         * written in the UI language's script (DeepSeek's often-Chinese reasoning for a Chinese UI,
         * Cyrillic for Russian, Latin for English). Decided by the dominant script; a Han, kana or
         * Hangul character weighs as much as a short word, so identifiers and code in Chinese
         * reasoning do not tip it. Other Latin-script languages always offer it (script alone cannot
         * tell French from English).
         */
        fun needsTranslation(text: String, locale: Locale): Boolean {
            val dominant = dominantScript(text) ?: return false
            val ui = when (locale.language) {
                "zh" -> UnicodeScript.HAN
                "ja" -> return dominant != UnicodeScript.HAN && dominant != UnicodeScript.HIRAGANA && dominant != UnicodeScript.KATAKANA
                "ko" -> UnicodeScript.HANGUL
                "ru", "uk", "be", "bg", "sr", "mk", "kk" -> UnicodeScript.CYRILLIC
                "en" -> UnicodeScript.LATIN
                else -> return true
            }
            return dominant != ui
        }

        internal fun dominantScript(text: String): UnicodeScript? {
            val weights = HashMap<UnicodeScript, Int>()
            var i = 0
            while (i < text.length) {
                val cp = text.codePointAt(i)
                i += Character.charCount(cp)
                if (!Character.isLetter(cp)) continue
                val script = UnicodeScript.of(cp)
                val w = when (script) {
                    UnicodeScript.HAN, UnicodeScript.HIRAGANA, UnicodeScript.KATAKANA, UnicodeScript.HANGUL -> 3
                    UnicodeScript.COMMON, UnicodeScript.INHERITED, UnicodeScript.UNKNOWN -> continue
                    else -> 1
                }
                weights[script] = (weights[script] ?: 0) + w
            }
            return weights.maxByOrNull { it.value }?.key
        }
    }
}
