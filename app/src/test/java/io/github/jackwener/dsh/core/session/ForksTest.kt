package io.github.jackwener.dsh.core.session

import io.github.jackwener.dsh.core.data.SessionEntity
import io.github.jackwener.dsh.core.engine.RpcJson
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Edit / retry / fork (#2): the app's cut of its log (the engine's rule, js/test/session-fork.test.mjs), the session list. */
class ForksTest {
    private fun ev(seq: Long, type: String, json: String = "{}") = LoggedEvent(seq, type, seq * 1000, RpcJson.parseToJsonElement(json) as JsonObject)
    private fun user(seq: Long, id: String, text: String, kind: String = "user") =
        ev(seq, "user/message", """{"id":"$id","role":"user","source":{"kind":"$kind"},"content":[{"type":"text","text":"$text"},{"type":"image","attachment":{"attachmentId":"att-$id"}}]}""")
    private fun reply(seq: Long, text: String) = ev(seq, "assistant/message", """{"message":{"content":[{"type":"text","text":"$text"}]}}""")

    /** Same shape as the engine test's log: two turns, a message steered into the second. App records interleave. */
    private fun splice(seq: Long, id: String) = ev(seq, "agent/inbox/spliced", """{"target":"next-turn","start":0,"inserted":[{"id":"$id","role":"user"}]}""")

    private val log = listOf(
        splice(20, "m1"),
        ev(0, "turn/start", """{"turn":1}"""),
        ev(1, "step/start"),
        user(3, "m1", "hi"),
        reply(4, "hello"),
        ev(5, "step/end"),
        ev(6, "turn/end", """{"turn":1,"reason":{"kind":"completed"}}"""),
        ev(7, "session/title", """{"title":"Hi"}"""),
        ev(-5, "app/title", """{"title":"Mine"}"""),
        splice(21, "m2"),
        ev(8, "turn/start", """{"turn":2}"""),
        ev(9, "step/start"),
        user(10, "ctx", "runtime context", kind = "runtime-context"),
        user(11, "m2", "open the app"),
        ev(12, "tool/call", """{"callId":"c1","name":"app_open","arguments":"{}"}"""),
        ev(13, "tool/result", """{"message":{"toolCallId":"c1","content":[]}}"""),
        ev(14, "step/end"),
        ev(15, "step/start"),
        user(16, "m3", "and then the other one"),
        reply(17, "done"),
        ev(18, "step/end"),
        ev(19, "turn/end", """{"turn":2,"reason":{"kind":"completed"}}"""),
    )

    @Test fun `the cut is before the turn a message opened, before a steered message, or the whole log`() {
        assertEquals(0, Forks.cut(log, "m1"))
        // The title records between the turns stay; the inbox entry that queued m2 and turn 2's injected context go with it.
        assertEquals(listOf(7L, -5L), log.subList(0, Forks.cut(log, "m2")!!).takeLast(2).map { it.seq })
        assertEquals(9, Forks.cut(log, "m2"))
        assertEquals(18, Forks.cut(log, "m3"))
        assertEquals(log.size, Forks.cut(log, null))
        assertNull("only human messages anchor a cut", Forks.cut(log, "ctx"))
        assertNull(Forks.cut(log, "nope"))
    }

    @Test fun `a prompt that failed in the app ends the turn before the next one`() {
        val events = listOf(user(0, "m1", "hi"), ev(-9, "app/error", """{"kind":"engine","message":"x"}"""), user(1, "m2", "again"))
        assertEquals(2, Forks.cut(events, "m2"))
    }

    @Test fun `user messages carry their id and attachments for 编辑 and 重试`() {
        val conv = Projector.conversation(log, running = false)
        val u = conv.turns.first().user!!
        assertEquals("m1", u.messageId)
        assertEquals(1, u.attachments.size)
        assertEquals("image", u.attachments.single().s("type"))
        assertEquals("m3", conv.turns.last().user?.messageId)
    }

    @Test fun `从这里分叉 keeps the turn to its end`() {
        val turns = Projector.conversation(log, running = false).turns
        assertEquals(listOf("m1", "m2", "m3"), turns.map { it.user?.messageId })
        assertEquals("m2", Forks.nextMessageId(turns, turns[0]))
        assertEquals("m3", Forks.nextMessageId(turns, turns[1]))
        assertNull(Forks.nextMessageId(turns, turns[2]))
        // A failed prompt that never reached the engine is skipped: the engine's log has nothing of it.
        val failed = turns + Turn("t-1", null, UserMsg(-1, 0, "lost", 0, 0), emptyList(), 0, 0, TurnStatus.Failed, null, Usage())
        assertNull(Forks.nextMessageId(failed, failed[2]))
    }

    @Test fun `an edit or retry stands in for its source in the list, a fork sits next to it`() {
        fun row(id: String, forkOf: String? = null, kind: ForkKind? = null, archived: Boolean = false) =
            SessionEntity(id, id, 0, 0, archived = archived, forkOf = forkOf, forkKind = kind?.id)
        val rows = listOf(row("a"), row("b", "a", ForkKind.Edit), row("c", "b", ForkKind.Retry), row("d", "c", ForkKind.Fork), row("e"))
        assertEquals(listOf("c", "d", "e"), Forks.listed(rows).map { it.id })
        // Archiving (or deleting) the newer version brings the older one back.
        assertEquals(listOf("b", "d", "e"), Forks.listed(rows.map { if (it.id == "c") it.copy(archived = true) else it }).filter { !it.archived }.map { it.id })
        assertEquals(listOf("a", "e"), Forks.listed(listOf(row("a"), row("e"))).map { it.id })
        assertNull(ForkKind.of(null))
        assertEquals(ForkKind.Retry, ForkKind.of("retry"))
    }
}
