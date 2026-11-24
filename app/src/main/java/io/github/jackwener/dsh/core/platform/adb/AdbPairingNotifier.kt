package io.github.jackwener.dsh.core.platform.adb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.github.jackwener.dsh.R
import io.github.jackwener.dsh.core.platform.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The pairing notification (#71), the way Shizuku does it: Android closes the "pair with code"
 * dialog as soon as Settings leaves the screen, so the code is typed into this notification's
 * reply field while the dialog stays open. The port comes from mDNS.
 */
object AdbPairingNotifier {
    private const val ID = 20
    const val KEY_CODE = "adb_pairing_code"

    private fun base(context: Context, text: String) = NotificationCompat.Builder(context, Notifications.CHANNEL_ATTENTION)
        .setSmallIcon(R.drawable.ic_stat_dsh)
        .setContentTitle(context.getString(R.string.adb_pair_notif_title))
        .setContentText(text)
        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        .setContentIntent(Notifications.openSection(context, "Phone"))
        .setOnlyAlertOnce(true)

    private fun cancelAction(context: Context) = NotificationCompat.Action(0, context.getString(R.string.adb_pair_cancel),
        PendingIntent.getBroadcast(context, 1, Intent(context, AdbPairingReceiver::class.java).setAction(AdbPairingReceiver.ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))

    private fun post(context: Context, b: NotificationCompat.Builder) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        @Suppress("MissingPermission")
        runCatching { NotificationManagerCompat.from(context).notify(ID, b.build()) }
    }

    fun showWaiting(context: Context) = post(context, base(context, context.getString(R.string.adb_pair_notif_waiting))
        .setOngoing(true).addAction(cancelAction(context)))

    fun showCodeEntry(context: Context, port: Int, retryText: String? = null) {
        val input = RemoteInput.Builder(KEY_CODE).setLabel(context.getString(R.string.adb_pair_code_hint)).build()
        // Mutable: the system adds the typed code. Explicit component, so allowed on Android 12+.
        val reply = PendingIntent.getBroadcast(context, 2,
            Intent(context, AdbPairingReceiver::class.java).setAction(AdbPairingReceiver.ACTION_CODE).putExtra(AdbPairingReceiver.EXTRA_PORT, port),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val action = NotificationCompat.Action.Builder(0, context.getString(R.string.adb_pair_enter_code), reply).addRemoteInput(input).build()
        post(context, base(context, retryText ?: context.getString(R.string.adb_pair_notif_found, port))
            .setOngoing(true).addAction(action).addAction(cancelAction(context)))
    }

    fun showBusy(context: Context) = post(context, base(context, context.getString(R.string.adb_pair_busy)).setOngoing(true))

    fun showFailed(context: Context, kind: AdbPairingException.Kind, port: Int?) {
        val text = context.getString(R.string.adb_pair_failed, failureText(context, kind))
        if (port != null && kind != AdbPairingException.Kind.Unsupported) showCodeEntry(context, port, text)
        else post(context, base(context, text).setAutoCancel(true))
    }

    fun showDone(context: Context, connected: Boolean) = post(context, base(context,
        context.getString(if (connected) R.string.adb_pair_notif_done else R.string.adb_pair_notif_done_offline))
        .setAutoCancel(true).setTimeoutAfter(15_000))

    fun cancel(context: Context) = NotificationManagerCompat.from(context).cancel(ID)

    fun failureText(context: Context, kind: AdbPairingException.Kind): String = context.getString(when (kind) {
        AdbPairingException.Kind.WrongCode -> R.string.adb_pair_err_code
        AdbPairingException.Kind.NoService -> R.string.adb_pair_err_service
        AdbPairingException.Kind.Timeout -> R.string.adb_pair_err_timeout
        AdbPairingException.Kind.Unsupported -> R.string.adb_pair_err_unsupported
        AdbPairingException.Kind.Other -> R.string.adb_pair_err_other
    })
}

/** The notification's code reply and 取消 (not exported). */
class AdbPairingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val adb = EntryPointAccessors.fromApplication(context.applicationContext, LocalAdbEntryPoint::class.java).localAdb()
        when (intent.action) {
            ACTION_CANCEL -> adb.cancelPairing()
            ACTION_CODE -> {
                val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(AdbPairingNotifier.KEY_CODE)?.toString().orEmpty()
                val port = intent.getIntExtra(EXTRA_PORT, 0).takeIf { it > 0 }
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try { adb.pair(code, port) } finally { pending.finish() }
                }
            }
        }
    }

    companion object {
        const val ACTION_CODE = "io.github.jackwener.dsh.ADB_PAIR_CODE"
        const val ACTION_CANCEL = "io.github.jackwener.dsh.ADB_PAIR_CANCEL"
        const val EXTRA_PORT = "port"
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface LocalAdbEntryPoint { fun localAdb(): LocalAdb }
