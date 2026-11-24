package io.github.jackwener.dsh.core.session

import io.github.jackwener.dsh.core.data.Settings
import io.github.jackwener.dsh.core.engine.EngineRoute
import io.github.jackwener.dsh.core.provider.ModelReasoning
import io.github.jackwener.dsh.core.provider.ProviderConfig
import io.github.jackwener.dsh.core.provider.ReasoningLevel
import io.github.jackwener.dsh.core.provider.ReasoningLevels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** #89: the settings layer's android-session-title row, the pinned title model and its level. */
class SessionTitlesTest {
    private val kimi = ProviderConfig(id = "kimi", preset = "kimi", name = "Kimi", baseUrl = "https://api.moonshot.cn/v1", models = listOf("kimi-k3"), enabled = true)
    private val catalog = mapOf(ReasoningLevels.key("kimi", "kimi-k3") to ModelReasoning(listOf(ReasoningLevel("low", "Low"), ReasoningLevel("high", "High"))))

    @Test fun `off disables the row, on follows the conversation or carries the pinned route at the lowest level by default`() {
        assertEquals("- id: android-session-title\n  disabled: true\n", SessionTitles.patchRow(Settings(autoTitle = false), EngineRoute("x", "y", "")))
        assertEquals("- id: android-session-title\n  disabled: false\n  config: {\"reasoning\":\"lowest\"}\n", SessionTitles.patchRow(Settings(), null))
        assertEquals("- id: android-session-title\n  disabled: false\n  config: {\"provider\":\"deepseek-official\",\"model\":\"deepseek-flash\",\"reasoning\":\"off\"}\n",
            SessionTitles.patchRow(Settings(titleProvider = "", titleModel = "deepseek-flash", titleEffort = "off"), EngineRoute("deepseek-official", "deepseek-flash", "high")))
    }

    @Test fun `a pinned model that is gone falls back to the conversation's`() {
        assertNull(SessionTitles.pinned(Settings()))
        assertEquals(SessionModel("", "deepseek-flash"), SessionTitles.pinned(Settings(titleProvider = "", titleModel = "deepseek-flash")))
        assertNull(SessionTitles.pinned(Settings(titleProvider = "gone", titleModel = "m")))
        assertEquals(SessionModel("kimi", "kimi-k3"), SessionTitles.pinned(Settings(providers = listOf(kimi), titleProvider = "kimi", titleModel = "kimi-k3")))
    }

    @Test fun `levels follow the title model, and a level it no longer offers is the lowest`() {
        assertEquals(listOf(SessionTitles.CONVERSATION), SessionTitles.levels(Settings()).map { it.id })
        assertEquals(SessionTitles.CONVERSATION, SessionTitles.effort(Settings(titleEffort = SessionTitles.CONVERSATION)))
        assertEquals(SessionTitles.LOWEST, SessionTitles.effort(Settings(titleEffort = "high")))
        val ds = Settings(titleProvider = "", titleModel = "deepseek-flash", titleEffort = "low")
        assertEquals(listOf("off", "low", "high", "max"), SessionTitles.levels(ds).map { it.id })
        assertEquals("low", SessionTitles.effort(ds))
        assertEquals(SessionTitles.LOWEST, SessionTitles.effort(ds.copy(titleEffort = SessionTitles.CONVERSATION)))
        val k = Settings(providers = listOf(kimi), reasoningCatalog = catalog, titleProvider = "kimi", titleModel = "kimi-k3", titleEffort = "high")
        assertEquals(listOf("low", "high"), SessionTitles.levels(k).map { it.id })
        assertEquals("high", SessionTitles.effort(k))
        assertEquals(SessionTitles.LOWEST, SessionTitles.effort(k.copy(titleEffort = "max")))
        assertEquals(emptyList<ReasoningLevel>(), SessionTitles.levels(k.copy(reasoningCatalog = emptyMap())))
    }
}
