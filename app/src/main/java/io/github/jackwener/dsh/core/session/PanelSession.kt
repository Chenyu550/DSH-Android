package io.github.jackwener.dsh.core.session

import android.content.Context

/**
 * The quick panel's recent conversation (floating ball, QS tile, assistant gesture): a summon within
 * [RECENT_MS] of its last use, or while it runs, shows it again instead of a new one. The conversation
 * the app was showing when the user left it counts the same way (#83): minimizing a chat and tapping
 * the ball continues that chat, not a new one.
 *
 * Deleting the conversation forgets it here ([forget], from SessionStore.delete). Before #57 the panel
 * reopened a deleted conversation as an empty one under the same id: the engine still had that session
 * (its log is not deleted with the app's rows), so the next message either continued the deleted
 * conversation's context, or, after an engine restart, failed with dsh's `session "…" already exists`,
 * and kept failing on every summon.
 */
object PanelSession {
    const val RECENT_MS = 10 * 60_000L
    private const val PREFS = "quick_panel"
    private const val KEY_LAST = "last_session"
    private const val KEY_AT = "last_at"
    private const val KEY_APP = "app_session"
    private const val KEY_APP_AT = "app_at"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The conversation last used in the panel (a run started there reopens in the panel, not the app). */
    fun last(context: Context): String? = prefs(context).getString(KEY_LAST, null)

    private fun lastAt(context: Context): Long = prefs(context).getLong(KEY_AT, 0)

    /** [sessionId] is about to get a message in the panel. */
    fun remember(context: Context, sessionId: String) {
        prefs(context).edit().putString(KEY_LAST, sessionId).putLong(KEY_AT, System.currentTimeMillis()).apply()
    }

    /** The panel went away while showing [sessionId]: its "recent" window starts now. */
    fun touch(context: Context, sessionId: String) {
        if (last(context) == sessionId) prefs(context).edit().putLong(KEY_AT, System.currentTimeMillis()).apply()
    }

    /** The app left the screen showing [sessionId]; null: a new, empty conversation (nothing to continue). */
    fun leftApp(context: Context, sessionId: String?) {
        val e = prefs(context).edit()
        if (sessionId == null) e.remove(KEY_APP).remove(KEY_APP_AT) else e.putString(KEY_APP, sessionId).putLong(KEY_APP_AT, System.currentTimeMillis())
        e.apply()
    }

    /** [sessionId] was deleted: never reopen it. */
    fun forget(context: Context, sessionId: String) {
        val p = prefs(context)
        if (p.getString(KEY_LAST, null) == sessionId) p.edit().remove(KEY_LAST).remove(KEY_AT).apply()
        if (p.getString(KEY_APP, null) == sessionId) p.edit().remove(KEY_APP).remove(KEY_APP_AT).apply()
    }

    /** The conversation a plain summon reopens, or null for a new one ([busy]: whether that one runs or waits for the user). */
    fun reopen(context: Context, now: Long, busy: (String) -> Boolean): String? {
        val p = prefs(context)
        return reopenable(listOf(last(context) to lastAt(context), p.getString(KEY_APP, null) to p.getLong(KEY_APP_AT, 0)), now, busy)
    }

    /** [reopen]'s rule, pure: of the panel's and the app's last conversations (id to when it was left), the latest one still running or recent. */
    fun reopenable(candidates: List<Pair<String?, Long>>, now: Long, busy: (String) -> Boolean): String? =
        candidates.mapNotNull { (id, at) -> id?.let { it to at } }.sortedByDescending { it.second }
            .firstOrNull { (id, at) -> busy(id) || now - at < RECENT_MS }?.first
}
