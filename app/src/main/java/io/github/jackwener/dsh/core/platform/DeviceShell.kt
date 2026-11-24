package io.github.jackwener.dsh.core.platform

import io.github.jackwener.dsh.core.platform.adb.LocalAdb
import io.github.jackwener.dsh.core.platform.adb.LocalAdbState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shell-user (uid 2000) commands from either channel (#71): Shizuku when it is ready, otherwise
 * DSH's own wireless-debugging ADB connection ([LocalAdb]). Only command lines go through here;
 * what needs Shizuku's binder user service (agent screen, ShellFiles for Android/data) stays on
 * [ShizukuShell]. A phone with Shizuku behaves exactly as before.
 */
@Singleton
class DeviceShell @Inject constructor(val shizuku: ShizukuShell, val adb: LocalAdb) {
    val access: ShellAccess get() = shizuku.access

    /** Ready on either channel (no connection attempt; for waiting on the user). */
    val readyFlow: Flow<Boolean> = combine(access.state, adb.state) { s, a -> s.ready || a.ready }

    /** The channel that can run a command now ("Shizuku" / "ADB"), connecting the ADB one on demand; null when neither can. */
    suspend fun channel(): String? = when {
        shizuku.available -> "Shizuku"
        adb.ensureConnected() -> "ADB"
        else -> null
    }

    suspend fun available(): Boolean = channel() != null

    /** Runs [cmd]; throws [ShellUnavailableException] when no channel is ready (the capability layer shows the setup card). */
    suspend fun exec(cmd: String, timeoutMs: Long = 30_000): ShellResult = when (channel()) {
        "Shizuku" -> shizuku.exec(cmd, timeoutMs)
        "ADB" -> adb.exec(cmd, timeoutMs)
        else -> throw ShellUnavailableException(access.state.value)
    }

    /** Model-facing reason no channel is ready (both, when the ADB one was ever set up). */
    fun reason(): String {
        val a = adb.state.value
        val s = access.state.value.reason
        return if (a == LocalAdbState.NotPaired || a == LocalAdbState.Unsupported) s else "$s, and ${a.reason}"
    }
}
