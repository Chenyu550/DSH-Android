package io.github.jackwener.dsh.core.session

import io.github.jackwener.dsh.core.data.Effort
import io.github.jackwener.dsh.core.data.Settings
import io.github.jackwener.dsh.core.engine.EngineRoute
import io.github.jackwener.dsh.core.provider.ReasoningLevel
import io.github.jackwener.dsh.core.provider.ReasoningLevels
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Automatic conversation titles (#89). dsh publishes a fallback title from the first message; the
 * engine row `android-session-title` (plugin-guard session-title.js) then asks a model for a short
 * title once the first reply is done, off the conversation's own requests. The app's part, in its
 * settings layer: 自动生成对话标题 (off: the row is disabled, no model call), 标题模型 (the
 * conversation's own, or a configured one) and 推理等级 ([LOWEST], [CONVERSATION] or one level of the
 * configured model). A user rename always wins (SessionStore.renameByUser).
 */
object SessionTitles {
    const val ROW = "android-session-title"
    /** 推理等级: the route's lowest level (off where it can be). The default. */
    const val LOWEST = "lowest"
    /** 推理等级: the conversation's own level (only while following its model). */
    const val CONVERSATION = "conversation"

    /** The title model the user picked, while it is still available (else the conversation's own). */
    fun pinned(st: Settings): SessionModel? {
        val provider = st.titleProvider ?: return null
        val model = st.titleModel?.takeIf { it.isNotEmpty() } ?: return null
        return SessionModel(provider, model).takeIf { SessionModels.available(it, st) }
    }

    /** The levels 推理等级 offers besides [LOWEST]: the pinned model's, or [CONVERSATION] while following. */
    fun levels(st: Settings): List<ReasoningLevel> {
        val pinned = pinned(st) ?: return listOf(ReasoningLevel(CONVERSATION, CONVERSATION))
        if (pinned.isDeepSeek) return Effort.entries.map { ReasoningLevel(it.id, it.label) }
        val p = st.providers.firstOrNull { it.id == pinned.provider } ?: return emptyList()
        return ReasoningLevels.of(p, pinned.model, st.reasoningCatalog)?.levels.orEmpty()
    }

    /** The stored 推理等级 while [levels] still offers it, else [LOWEST]. */
    fun effort(st: Settings): String = st.titleEffort?.takeIf { e -> levels(st).any { it.id == e } } ?: LOWEST

    /** The settings layer's row: disabled, or the title model's route (none: the conversation's) and level. */
    fun patchRow(st: Settings, route: EngineRoute?): String {
        if (!st.autoTitle) return "- id: $ROW\n  disabled: true\n"
        val config = buildJsonObject {
            if (route != null) { put("provider", route.provider); put("model", route.model) }
            put("reasoning", effort(st))
        }
        return "- id: $ROW\n  disabled: false\n  config: $config\n"
    }
}
