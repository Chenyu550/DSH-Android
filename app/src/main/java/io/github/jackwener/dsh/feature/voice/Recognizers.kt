package io.github.jackwener.dsh.feature.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.speech.RecognitionService

/**
 * The device's real speech recognizers. DSH's own RecognitionService (DshRecognitionService) is the system
 * default whenever DSH is the default assistant (Android takes the assistant's recognitionService), so it is
 * never one of them: it forwards to [best] instead.
 */
object Recognizers {
    private val PREFERRED = listOf("com.google.android.googlequicksearchbox", "com.google.android.tts", "com.google.android.as")

    /** Recognition services of other apps. (SpeechRecognizer looks them up with flags 0: no CATEGORY_DEFAULT involved.) */
    fun others(context: Context): List<ComponentName> =
        runCatching { context.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0) }.getOrDefault(emptyList())
            .map { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }.filter { it.packageName != context.packageName }

    /** The system default recognition service unless it is DSH's own, else a known good one, else any; null when there is none. */
    fun best(context: Context): ComponentName? {
        val all = others(context)
        if (all.isEmpty()) return null
        val default = runCatching { Settings.Secure.getString(context.contentResolver, "voice_recognition_service") }.getOrNull()
            ?.let(ComponentName::unflattenFromString)
        return pick(all, default)
    }

    internal fun pick(all: List<ComponentName>, default: ComponentName?): ComponentName? =
        all.firstOrNull { it == default } ?: PREFERRED.firstNotNullOfOrNull { p -> all.firstOrNull { it.packageName == p } } ?: all.firstOrNull()
}
