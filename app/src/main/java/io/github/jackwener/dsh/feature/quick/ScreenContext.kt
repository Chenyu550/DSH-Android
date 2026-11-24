package io.github.jackwener.dsh.feature.quick

import android.graphics.Bitmap
import io.github.jackwener.dsh.service.DshAccessibilityService
import io.github.jackwener.dsh.service.FloatingBallService

/** "What's on screen right now", captured at the moment the user summons the quick panel. */
object ScreenContext {
    @Volatile var packageName: String? = null; private set
    @Volatile var text: String? = null; private set
    /** Screenshot taken by a floating-ball double tap; handed to the panel's composer once. */
    @Volatile private var shot: Bitmap? = null

    fun attachShot(bmp: Bitmap?) { shot = bmp }
    fun takeShot(): Bitmap? = shot.also { shot = null }

    fun fromAssist(text: String?, packageName: String?) { this.text = text; this.packageName = packageName }

    /** Floating ball / tile path: read via accessibility, before our panel covers the screen. */
    fun captureFromAccessibility() {
        val svc = DshAccessibilityService.instance ?: return run { text = null; packageName = null }
        runCatching { svc.snapshot() }.getOrNull()?.let {
            if (it.packageName != null && !it.packageName.startsWith("io.github.jackwener.dsh")) { text = it.text.take(8000); packageName = it.packageName }
        }
    }

    /**
     * 发送当前屏幕 (floating-ball double tap, app shortcut): the screen's text and a screenshot without our
     * overlays, for the panel opened next with [QuickActivity.EXTRA_SCREEN]. False without the accessibility
     * service. Android 10 has no accessibility screenshot, and secure pages refuse one: the text still goes along.
     */
    suspend fun captureScreen(): Boolean {
        val a11y = DshAccessibilityService.instance ?: return false
        FloatingBallService.withoutOverlays {
            captureFromAccessibility()
            attachShot(runCatching { a11y.capture().bitmap }.getOrNull())
        }
        return true
    }

    fun clear() { text = null; packageName = null; shot = null }
}
