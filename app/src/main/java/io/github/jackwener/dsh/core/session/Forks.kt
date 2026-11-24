package io.github.jackwener.dsh.core.session

import io.github.jackwener.dsh.core.data.SessionEntity
import kotlinx.serialization.json.JsonObject

/**
 * How a conversation was made from another (#2; `sessions.forkKind`). All three are dsh's boundary
 * fork (`android/session/fork`, ENGINE-CONTRACT §2): a new session seeded with a prefix of the
 * source's log, the source left as it is. dsh has no session tree, so the new one is a conversation
 * of its own that remembers its source. [Edit] and [Retry] cut before a message and send it again
 * (changed or as it was): they stand in for the source in the session list ([Forks.listed]), like a
 * new version of the same conversation. [Fork] keeps everything up to the end of a turn and starts a
 * second conversation next to the first.
 */
enum class ForkKind(val id: String) {
    Edit("edit"), Retry("retry"), Fork("fork");

    val replacesSource: Boolean get() = this != Fork

    companion object {
        fun of(id: String?): ForkKind? = entries.firstOrNull { it.id == id }
    }
}

object Forks {
    /** Events after which a human message no longer opens its turn (plugin-guard session-fork.js `CONTENT`). */
    private val CONTENT = setOf("assistant/message", "assistant/attempt", "tool/call", "tool/result", "turn/end", "app/error", "app/stopped")

    private fun LoggedEvent.isHumanPrompt() = type == "user/message" && data.o("source")?.s("kind") == "user" && !isReplacement()

    /**
     * How much of the app's log ([events], in log order) a fork keeps: the index the copy ends before.
     * The engine cuts its own log by the same rule (session-fork.js `forkBoundary`): before the turn
     * [beforeMessageId] opened and the inbox entry that queued it, or before the message itself when it
     * was steered into a running turn;
     * null [beforeMessageId] keeps everything. Null when the message is not in the log.
     */
    fun cut(events: List<LoggedEvent>, beforeMessageId: String?): Int? {
        if (beforeMessageId == null) return events.size
        val at = events.indexOfFirst { it.isHumanPrompt() && it.data.s("id") == beforeMessageId }
        if (at < 0) return null
        var cut = at
        for (i in at - 1 downTo 0) {
            val e = events[i]
            if (e.type == "turn/start") { cut = i; continue }
            if (e.type == "agent/inbox/spliced" && e.data.a("inserted")?.any { (it as? JsonObject)?.s("id") == beforeMessageId } == true) return i
            if (e.type in CONTENT || e.isHumanPrompt()) break
        }
        return cut
    }

    /**
     * 从这里分叉 on [turn]: the fork keeps the turn to its end, i.e. it cuts before the next turn's message
     * that reached the engine; null (the whole log) when none follows.
     */
    fun nextMessageId(turns: List<Turn>, turn: Turn): String? {
        val i = turns.indexOfFirst { it.key == turn.key }
        if (i < 0) return null
        return turns.drop(i + 1).firstNotNullOfOrNull { it.user?.messageId }
    }

    /**
     * The session list: a conversation an edit or retry stands in for is left out while that one is
     * there (not archived or deleted); it stays reachable from it ([Lineage]).
     */
    fun listed(rows: List<SessionEntity>): List<SessionEntity> {
        val replaced = rows.filter { !it.archived && ForkKind.of(it.forkKind)?.replacesSource == true }.mapNotNullTo(HashSet()) { it.forkOf }
        return if (replaced.isEmpty()) rows else rows.filter { it.id !in replaced }
    }
}

/**
 * Where the shown conversation stands among its forks (#2), for the line above its messages: made from
 * [source] by [kind]; [newer] is its latest edit / retry (it was replaced). Null fields: none.
 */
data class Lineage(
    val kind: ForkKind? = null,
    val source: SessionEntity? = null,
    val newer: SessionEntity? = null,
) {
    val isEmpty: Boolean get() = (kind == null || source == null) && newer == null
}
