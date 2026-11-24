package io.github.jackwener.dsh.core.platform

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jackwener.dsh.core.capability.PermissionMissingException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/** What a finished Termux command returned (Termux's plugin result bundle). */
data class TermuxResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    /** Termux cuts stdout+stderr to fit a binder transaction; the lengths before that, when it did. */
    val stdoutOriginalLength: Int? = null,
    val stderrOriginalLength: Int? = null,
)

/** Why a command could not run in Termux (model-facing [message]; [code] is the tool error code). */
class TermuxException(val code: String, message: String) : Exception(message)

/** Missing com.termux.permission.RUN_COMMAND; the capability layer shows the permission card for it. */
class TermuxPermissionException : PermissionMissingException(TERMUX_WHAT)

/** PendingPermission.what for Termux's RUN_COMMAND (SessionStore.permissionIdFor maps it to the catalog item). */
const val TERMUX_WHAT = "Termux RUN_COMMAND permission"

/**
 * Commands in the user's own Termux (#68) through its RunCommandService (the documented RUN_COMMAND
 * intent): DSH holds the runtime permission, Termux runs the command as a background task in its own
 * environment (pkg, ~/storage, …) and sends the result back through a PendingIntent to
 * [TermuxResultReceiver]. Nothing stays running on DSH's side; the built-in bash is unaffected.
 */
@Singleton
class TermuxBridge @Inject constructor(@ApplicationContext private val context: Context) {
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<Bundle>>()
    private val ids = AtomicInteger((System.currentTimeMillis() % 100_000).toInt())

    val installed: Boolean get() = runCatching { context.packageManager.getPackageInfo(Termux.PACKAGE, 0); true }.getOrDefault(false)
    val permissionGranted: Boolean get() = context.checkSelfPermission(Termux.PERMISSION) == PackageManager.PERMISSION_GRANTED
    fun versionName(): String? = runCatching { context.packageManager.getPackageInfo(Termux.PACKAGE, 0).versionName }.getOrNull()

    /**
     * Runs [command] with Termux's bash in [cwd] (Termux home when null), stopped after [timeoutS]
     * by coreutils `timeout` (exit 124). Throws [TermuxPermissionException] or [TermuxException].
     */
    suspend fun run(command: String, cwd: String?, timeoutS: Int): TermuxResult {
        if (!installed) throw TermuxException("termux_unavailable", Termux.NOT_INSTALLED)
        if (!permissionGranted) throw TermuxPermissionException()
        val id = ids.incrementAndGet()
        val done = CompletableDeferred<Bundle>()
        pending[id] = done
        try {
            val reply = PendingIntent.getBroadcast(context, id,
                Intent(context, TermuxResultReceiver::class.java).setAction(TermuxResultReceiver.ACTION).putExtra(TermuxResultReceiver.EXTRA_ID, id),
                // Mutable: Termux fills in the result bundle. Explicit component, so this is allowed on 12+.
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_MUTABLE)
            val intent = Termux.runIntent(command, cwd, timeoutS).putExtra(Termux.EXTRA_PENDING_INTENT, reply)
            // RunCommandService calls startForeground itself (Termux targets API 28, so no FGS-start limits apply to it).
            val started = try { context.startForegroundService(intent) } catch (e: SecurityException) {
                // The permission was revoked between the check and the call, or Termux's manifest changed.
                if (!permissionGranted) throw TermuxPermissionException()
                throw TermuxException("termux_unavailable", "Termux refused the request: ${e.message}")
            } catch (e: IllegalStateException) {
                throw TermuxException("termux_unavailable", "${Termux.NOT_REACHABLE} (${e.message})")
            }
            if (started == null) throw TermuxException("termux_unavailable", Termux.NOT_REACHABLE)
            // Termux has no timeout of its own: allow the command's, plus time for Termux to start up.
            val bundle = withTimeoutOrNull(timeoutS * 1000L + REPLY_GRACE_MS) { done.await() }
                ?: throw TermuxException("termux_no_reply", Termux.noReply(timeoutS))
            return Termux.parseResult(bundle)
        } finally {
            pending.remove(id)
        }
    }

    internal fun deliver(id: Int, bundle: Bundle) { pending[id]?.complete(bundle) }

    companion object {
        private const val REPLY_GRACE_MS = 20_000L
    }
}

/** Receives Termux's result PendingIntent (not exported; only Termux holds the PendingIntent). */
class TermuxResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val id = intent.getIntExtra(EXTRA_ID, -1)
        val bundle = intent.getBundleExtra(Termux.EXTRA_RESULT_BUNDLE) ?: Bundle()
        dagger.hilt.android.EntryPointAccessors.fromApplication(context.applicationContext, TermuxEntryPoint::class.java).termux().deliver(id, bundle)
    }

    companion object {
        const val ACTION = "io.github.jackwener.dsh.TERMUX_RESULT"
        const val EXTRA_ID = "dsh_termux_id"
    }
}

@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface TermuxEntryPoint { fun termux(): TermuxBridge }

/** Termux's RUN_COMMAND contract (termux-app TermuxConstants) and the pure parts of the bridge. */
object Termux {
    const val PACKAGE = "com.termux"
    const val PERMISSION = "com.termux.permission.RUN_COMMAND"
    const val SERVICE = "com.termux.app.RunCommandService"
    const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
    const val EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
    const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
    const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
    const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
    const val EXTRA_COMMAND_LABEL = "com.termux.RUN_COMMAND_COMMAND_LABEL"
    const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"
    const val EXTRA_RESULT_BUNDLE = "result"
    const val RESULT_STDOUT = "stdout"
    const val RESULT_STDOUT_ORIGINAL_LENGTH = "stdout_original_length"
    const val RESULT_STDERR = "stderr"
    const val RESULT_STDERR_ORIGINAL_LENGTH = "stderr_original_length"
    const val RESULT_EXIT_CODE = "exitCode"
    const val RESULT_ERR = "err"
    const val RESULT_ERRMSG = "errmsg"

    const val PREFIX = "/data/data/com.termux/files/usr"
    const val HOME = "/data/data/com.termux/files/home"
    const val TIMED_OUT = 124

    const val NOT_INSTALLED = "Termux is not installed (the app com.termux). termux_run only works with the user's own Termux; " +
        "use the bash tool instead, or tell the user to install Termux (F-Droid or GitHub build) if they want its packages."
    const val NOT_REACHABLE = "Termux's RunCommandService could not be started; Termux may be too old (needs 0.95+) or disabled. Ask the user to open Termux once and update it."
    const val ALLOW_EXTERNAL_APPS = "Termux refused the command because `allow-external-apps` is not enabled. The user must run this once in Termux: " +
        "mkdir -p ~/.termux && echo 'allow-external-apps = true' >> ~/.termux/termux.properties && termux-reload-settings"

    fun noReply(timeoutS: Int) = "Termux did not send a result within ${timeoutS}s plus startup time. Termux may have been stopped by the system " +
        "(battery restrictions) or still be installing its environment; ask the user to open Termux once, then retry."

    /** The RUN_COMMAND intent: `timeout -k 5 <t> bash -c <command>` as a background task (no terminal session). */
    fun runIntent(command: String, cwd: String?, timeoutS: Int): Intent = Intent(ACTION_RUN_COMMAND)
        .setComponent(ComponentName(PACKAGE, SERVICE))
        .putExtra(EXTRA_COMMAND_PATH, "$PREFIX/bin/timeout")
        .putExtra(EXTRA_ARGUMENTS, arguments(command, timeoutS))
        .putExtra(EXTRA_WORKDIR, workdir(cwd))
        .putExtra(EXTRA_BACKGROUND, true)
        .putExtra(EXTRA_COMMAND_LABEL, "DSH: " + command.lineSequence().first().take(60))

    /** [cwd] with "~" as Termux's home and relative paths under it (plugin-guard judges the same directory). */
    fun workdir(cwd: String?): String {
        val c = cwd?.trim()?.takeIf { it.isNotEmpty() } ?: return HOME
        val expanded = if (c == "~" || c.startsWith("~/")) HOME + c.substring(1) else c
        return if (expanded.startsWith("/")) expanded else "$HOME/$expanded"
    }

    fun arguments(command: String, timeoutS: Int): Array<String> = arrayOf("-k", "5", "${timeoutS}s", "$PREFIX/bin/bash", "-c", command)

    /** Turns Termux's result bundle into a [TermuxResult], or throws for an error Termux reported itself. */
    fun parseResult(b: Bundle): TermuxResult {
        val err = b.getInt(RESULT_ERR, Activity.RESULT_OK)
        val errmsg = b.getString(RESULT_ERRMSG)?.trim().orEmpty()
        if (err != Activity.RESULT_OK) {
            if ("allow-external-apps" in errmsg) throw TermuxException("termux_not_allowed", ALLOW_EXTERNAL_APPS)
            throw TermuxException("termux_failed", "Termux could not run the command: ${errmsg.ifEmpty { "error $err" }.take(600)}")
        }
        fun len(k: String) = b.getString(k)?.toIntOrNull() ?: b.getInt(k, -1).takeIf { it >= 0 }
        return TermuxResult(b.getInt(RESULT_EXIT_CODE, -1), b.getString(RESULT_STDOUT).orEmpty(), b.getString(RESULT_STDERR).orEmpty(),
            len(RESULT_STDOUT_ORIGINAL_LENGTH), len(RESULT_STDERR_ORIGINAL_LENGTH))
    }

    /** Model-facing text: exit code, stdout, stderr, and whether Termux cut either. */
    fun format(r: TermuxResult, timeoutS: Int): String = buildString {
        append("exit ").append(r.exitCode)
        if (r.exitCode == TIMED_OUT) append(" (stopped after ${timeoutS}s, or the command itself exited 124)")
        val out = r.stdout.trimEnd()
        val err = r.stderr.trimEnd()
        if (out.isEmpty() && err.isEmpty()) append("\n(no output)")
        if (out.isNotEmpty()) append("\n").append(out)
        cut(r.stdout, r.stdoutOriginalLength)?.let { append("\n…(stdout cut by Termux: $it)") }
        if (err.isNotEmpty()) append("\n--- stderr ---\n").append(err)
        cut(r.stderr, r.stderrOriginalLength)?.let { append("\n…(stderr cut by Termux: $it)") }
    }

    private fun cut(s: String, original: Int?): String? =
        if (original != null && original > s.length) "${s.length} of $original characters; narrow the command or write to a file and read parts" else null
}
