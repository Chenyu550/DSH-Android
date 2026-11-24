package io.github.jackwener.dsh.core.session

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

/**
 * #57: the quick panel (floating ball, QS tile, assistant) reopened its last conversation by id even after
 * it was deleted; the engine still had that session, so every message from the panel failed with
 * `session "…" already exists`. A deleted conversation is forgotten; and dsh's refusal is recognized so
 * SessionStore can resume such a session instead of failing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@Suppress("DEPRECATION") @SQLiteMode(SQLiteMode.Mode.LEGACY)
class PanelSessionTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val idle: (String) -> Boolean = { false }

    @Test fun `reopens the recent or busy panel conversation`() {
        val now = 1_000_000_000L
        fun one(id: String?, at: Long) = listOf(id to at)
        assertEquals("s", PanelSession.reopenable(one("s", now - 60_000), now, idle))
        assertNull(PanelSession.reopenable(one("s", now - PanelSession.RECENT_MS), now, idle))
        assertEquals("s", PanelSession.reopenable(one("s", 0), now) { it == "s" })
        assertNull(PanelSession.reopenable(one(null, now), now) { true })
    }

    @Test fun `the conversation left in the app is continued by the next summon (#83)`() {
        val now = 1_000_000_000L
        // Minimized in the app a minute ago, the panel used earlier: the app's one.
        assertEquals("app", PanelSession.reopenable(listOf("panel" to now - 300_000, "app" to now - 60_000), now, idle))
        // The panel used after leaving the app: the panel's one.
        assertEquals("panel", PanelSession.reopenable(listOf("panel" to now - 30_000, "app" to now - 60_000), now, idle))
        // Both long ago: a new conversation, unless one still runs.
        assertNull(PanelSession.reopenable(listOf("panel" to 0L, "app" to 0L), now, idle))
        assertEquals("panel", PanelSession.reopenable(listOf("panel" to 0L, "app" to 1L), now) { it == "panel" })

        PanelSession.leftApp(app, "b")
        assertEquals("b", PanelSession.reopen(app, System.currentTimeMillis(), idle))
        // It is not the panel's own run (a run started in the app reopens in the app, #39).
        assertNull(PanelSession.last(app))
        PanelSession.leftApp(app, null)
        assertNull(PanelSession.reopen(app, System.currentTimeMillis(), idle))
        PanelSession.leftApp(app, "b")
        PanelSession.forget(app, "b")
        assertNull(PanelSession.reopen(app, System.currentTimeMillis(), idle))
    }

    @Test fun `a deleted panel conversation is never reopened`() {
        PanelSession.remember(app, "a")
        assertEquals("a", PanelSession.reopen(app, System.currentTimeMillis(), idle))
        PanelSession.forget(app, "other")
        assertEquals("a", PanelSession.last(app))
        PanelSession.forget(app, "a")
        assertNull(PanelSession.last(app))
        assertNull(PanelSession.reopen(app, System.currentTimeMillis()) { true })
        // Leaving the panel later does not bring it back.
        PanelSession.touch(app, "a")
        assertNull(PanelSession.reopen(app, System.currentTimeMillis(), idle))
    }

    @Test fun `dsh's duplicate-create refusal is recognized`() {
        assertTrue(SessionOpen.alreadyExists("""session "3b37232f-6889-425f-a61d-1260a991f7f7" already exists"""))
        assertFalse(SessionOpen.alreadyExists("""subagent "x" already exists"""))
        assertFalse(SessionOpen.alreadyExists("""SDK session is already open or opening: x"""))
        assertFalse(SessionOpen.alreadyExists("""session "x" not found"""))
        assertFalse(SessionOpen.alreadyExists(null))
    }
}
