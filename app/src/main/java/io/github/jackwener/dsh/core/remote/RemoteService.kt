package io.github.jackwener.dsh.core.remote

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import io.github.jackwener.dsh.R
import io.github.jackwener.dsh.core.platform.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Keeps the app process (and with it the SSH links) alive while a remote host is connected or reconnecting
 * (#51): the remote dsh runs on the computer, but its conversation's client is this process, which Android
 * would otherwise freeze or kill in the background. Stops itself when no host needs it any more.
 */
@AndroidEntryPoint
class RemoteService : Service() {
    @Inject lateinit var remotes: RemoteEngines
    @Inject lateinit var hosts: RemoteHostStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        runCatching { startForeground(Notifications.ID_REMOTE, notification(""), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) }
            .onFailure { Log.w(TAG, "startForeground refused", it) }
        scope.launch {
            combine(remotes.states, hosts.hosts) { states, all ->
                all.filter { states[it.id]?.let(::keepsService) == true }.map { it.name }
            }.collect { names ->
                if (names.isEmpty()) { stopSelf(); return@collect }
                runCatching { getSystemService(android.app.NotificationManager::class.java).notify(Notifications.ID_REMOTE, notification(names.joinToString("、"))) }
            }
        }
    }

    private fun notification(names: String) = NotificationCompat.Builder(this, Notifications.CHANNEL_ENGINE)
        .setSmallIcon(R.drawable.ic_stat_dsh)
        .setContentTitle(getString(R.string.remote_connected_notif, names))
        .setOngoing(true)
        .setSilent(true)
        .setContentIntent(Notifications.openRunning(this))
        .build()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "RemoteService"

        /** Connected, connecting, or reconnecting after a lost link: a reconnect in the background could not start the service again. */
        fun keepsService(st: RemoteState) = st is RemoteState.Connected || st is RemoteState.Connecting ||
            st is RemoteState.Failed && st.error.kind == RemoteException.Kind.Disconnected

        /** From a connect (a user's send, normally in the foreground); a refused background start only costs robustness. */
        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, RemoteService::class.java)) }
                .onFailure { Log.w(TAG, "could not start the remote service", it) }
        }
    }
}
