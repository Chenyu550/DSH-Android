package io.github.jackwener.dsh.feature.conversation

import io.github.jackwener.dsh.core.session.Block
import io.github.jackwener.dsh.core.session.ToolState
import io.github.jackwener.dsh.core.session.Turn
import io.github.jackwener.dsh.core.session.TurnStatus
import io.github.jackwener.dsh.core.session.Usage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #76: the running status line names the step, and "/" commands never eat the draft. */
class ChatItemsTest {
    private fun tool(id: String, state: ToolState) = Block.Tool("k$id", id, "bash", "{}", null, state, null, null, null, 0, null)
    private fun turn(vararg blocks: Block, status: TurnStatus = TurnStatus.Running) =
        Turn("t", 1, null, blocks.toList(), 0, null, status, null, Usage())

    @Test fun `status follows the turn's steps`() {
        assertEquals(RunPhase.Thinking, RunPhase.of(null))
        assertEquals(RunPhase.Thinking, RunPhase.of(turn(Block.Reasoning("r", "…"))))
        val running = tool("b", ToolState.Running)
        assertEquals(RunPhase.Tool(running), RunPhase.of(turn(tool("a", ToolState.Done), running)))
        // Between tool calls the model works out the next step: said, with the count so far.
        assertEquals(RunPhase.NextStep(2), RunPhase.of(turn(tool("a", ToolState.Done), tool("b", ToolState.Error))))
        // A finished turn is not "running" anything.
        assertEquals(RunPhase.Thinking, RunPhase.of(turn(running, status = TurnStatus.Completed)))
    }

    @Test fun `slash query is a leading slash plus one word`() {
        assertEquals("", SlashCommands.query("/"))
        assertEquals("mo", SlashCommands.query("/mo"))
        assertNull(SlashCommands.query("/model please"))
        assertNull(SlashCommands.query("/sdcard/Download"))
        assertNull(SlashCommands.query("hi /model"))
        assertNull(SlashCommands.query(""))
    }

    @Test fun `slash matches the English id or the shown label`() {
        assertTrue(SlashCommands.matches("", "model", "模型"))
        assertTrue(SlashCommands.matches("Mo", "model", "模型"))
        assertTrue(SlashCommands.matches("模型", "model", "模型"))
        assertFalse(SlashCommands.matches("xyz", "model", "模型"))
    }
}
