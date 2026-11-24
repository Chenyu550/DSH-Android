package io.github.jackwener.dsh.feature.quick

import android.app.Activity
import android.content.Intent
import android.content.pm.ShortcutManager
import android.os.Bundle
import android.widget.Toast
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import io.github.jackwener.dsh.MainActivity
import io.github.jackwener.dsh.R
import io.github.jackwener.dsh.core.platform.ActivityStarts
import kotlinx.coroutines.launch

/**
 * App shortcuts (res/xml/shortcuts.xml, #82): 新会话, 语音任务, 发送当前屏幕 — on the launcher icon's
 * long-press and wherever the system offers an app's shortcuts (e.g. vivo's Quick action button → 打开应用服务).
 *
 * A no-display trampoline in a task of its own: the system starts a static shortcut's activity with
 * NEW_TASK | CLEAR_TASK, which aimed at MainActivity / QuickActivity would wipe their tasks. It forwards to
 * the floating ball's entry points instead. Having no window, it leaves the user's screen and its focus
 * untouched, so 发送当前屏幕 captures the app the user was in.
 */
class ShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = applicationContext
        val action = intent?.action
        when (action) {
            ACTION_NEW -> ActivityStarts.start(app, Intent(app, MainActivity::class.java).putExtra(MainActivity.EXTRA_NEW_SESSION, true))
            ACTION_VOICE -> ActivityStarts.start(app, Intent(app, QuickActivity::class.java).putExtra(QuickActivity.EXTRA_VOICE, true))
            ACTION_SCREEN -> ProcessLifecycleOwner.get().lifecycleScope.launch {
                if (ScreenContext.captureScreen()) ActivityStarts.start(app, Intent(app, QuickActivity::class.java).putExtra(QuickActivity.EXTRA_SCREEN, true))
                else {
                    Toast.makeText(app, R.string.ball_screen_needs_a11y, Toast.LENGTH_SHORT).show()
                    ActivityStarts.start(app, Intent(app, QuickActivity::class.java))
                }
            }
        }
        // Launchers rank an app's shortcuts by use.
        SHORTCUT_IDS[action]?.let { id -> runCatching { getSystemService(ShortcutManager::class.java)?.reportShortcutUsed(id) } }
        // Theme.NoDisplay: finish before onResume.
        finish()
    }

    companion object {
        const val ACTION_NEW = "io.github.jackwener.dsh.shortcut.NEW_SESSION"
        const val ACTION_VOICE = "io.github.jackwener.dsh.shortcut.VOICE"
        const val ACTION_SCREEN = "io.github.jackwener.dsh.shortcut.SEND_SCREEN"

        /** Action → android:shortcutId in res/xml/shortcuts.xml. */
        val SHORTCUT_IDS = mapOf(ACTION_NEW to "new_session", ACTION_VOICE to "voice_task", ACTION_SCREEN to "send_screen")
    }
}
