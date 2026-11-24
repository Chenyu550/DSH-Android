package io.github.jackwener.dsh.core.platform.adb

import android.content.Context
import android.content.pm.PackageManager
import android.net.ssl.SSLSockets
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jackwener.dsh.core.platform.ShellResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLSocket

/** Where DSH's own wireless-debugging ADB channel (#71) stands. */
sealed interface LocalAdbState {
    /** Android 10: no wireless debugging (pairing needs Android 11+). */
    data object Unsupported : LocalAdbState
    data object NotPaired : LocalAdbState
    /** Paired, switched off by the user (断开): never connects until turned on again. */
    data object Off : LocalAdbState
    data object Connecting : LocalAdbState
    /** Works: the last connection reached adbd as uid [uid]; [connected] while a connection is open (it closes when idle). */
    data class Ready(val connected: Boolean, val uid: Int?) : LocalAdbState
    /** Paired and on, but the last attempt failed. */
    data class Unreachable(val issue: Issue, val detail: String?) : LocalAdbState

    enum class Issue {
        /** No adbd service found / connection refused: wireless debugging is off (or Wi-Fi is). */
        WirelessDebuggingOff,
        /** adbd rejected the key: the pairing was removed on the phone. */
        PairingRemoved,
        Failed,
    }

    companion object {
        /** The state after a failed connect: what the user can do about [e]. */
        fun failed(e: Throwable?): LocalAdbState = when (e) {
            is AdbNotPairedException -> Unreachable(Issue.PairingRemoved, e.message)
            is NoPortException, is java.net.ConnectException -> Unreachable(Issue.WirelessDebuggingOff, e.message)
            else -> Unreachable(Issue.Failed, e?.message ?: e?.javaClass?.simpleName)
        }
    }

    val ready get() = this is Ready
    /** Set up and switched on (it may still fail to connect right now). */
    val pairedOn get() = this is Ready || this is Unreachable || this == Connecting
    /** Model-facing reason. */
    val reason: String get() = when (this) {
        Unsupported -> "DSH's own ADB channel needs Android 11+"
        NotPaired -> "DSH's own ADB channel (wireless debugging) is not paired"
        Off -> "DSH's own ADB channel is switched off"
        Connecting -> "DSH's own ADB channel is connecting"
        is Ready -> "DSH's own ADB channel is ready"
        is Unreachable -> when (issue) {
            Issue.WirelessDebuggingOff -> "wireless debugging is off (DSH's ADB channel cannot connect)"
            Issue.PairingRemoved -> "the phone no longer trusts DSH's ADB key (pair again)"
            Issue.Failed -> "DSH's ADB channel failed" + (detail?.let { ": $it" } ?: "")
        }
    }
}

/** No wireless-debugging service answered (mDNS found nothing, or the known ports are closed). */
class NoPortException(message: String) : IOException(message)

/** The pairing flow the settings card and the notification drive; null = not pairing. */
data class AdbPairingFlow(
    /** The pairing service's port from mDNS (the dialog is open), or null while waiting for it. */
    val port: Int? = null,
    val busy: Boolean = false,
    val error: AdbPairingException.Kind? = null,
    val errorDetail: String? = null,
)

/**
 * A Shizuku-free shell user (uid 2000) through the phone's own adbd (#71): pair once with the
 * wireless-debugging code (SPAKE2 over TLS, [AdbPairing]), then connect to 127.0.0.1 on the TLS
 * port ([AdbConnection], port from mDNS or typed by the user) with DSH's key, which lives only in
 * app-private storage. Connections open on demand and close after a short idle time, so the
 * system's "wireless debugging connected" state does not linger. Shizuku stays first wherever it
 * is ready ([io.github.jackwener.dsh.core.platform.DeviceShell]).
 */
@Singleton
class LocalAdb @Inject constructor(@ApplicationContext private val context: Context) {
    private val prefs = context.getSharedPreferences("local_adb", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectLock = Mutex()
    @Volatile private var conn: AdbConnection? = null
    @Volatile private var lastFailureAt = 0L
    private var idleJob: Job? = null
    private var pairingMdns: AdbMdns? = null
    private var pairingTimeout: Job? = null

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<LocalAdbState> = _state.asStateFlow()
    private val _pairing = MutableStateFlow<AdbPairingFlow?>(null)
    val pairing: StateFlow<AdbPairingFlow?> = _pairing.asStateFlow()

    val supported: Boolean get() = Build.VERSION.SDK_INT >= 30
    private val paired get() = prefs.getBoolean(KEY_PAIRED, false)
    private val enabled get() = prefs.getBoolean(KEY_ENABLED, true)

    init {
        // Back from Settings (wireless debugging turned on?): try again without a tap.
        scope.launch(Dispatchers.Main) {
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    if (_state.value is LocalAdbState.Unreachable) scope.launch { connect(userInitiated = true) }
                }
            })
        }
    }

    private fun initialState(): LocalAdbState = when {
        Build.VERSION.SDK_INT < 30 -> LocalAdbState.Unsupported
        !prefs.getBoolean(KEY_PAIRED, false) -> LocalAdbState.NotPaired
        !prefs.getBoolean(KEY_ENABLED, true) -> LocalAdbState.Off
        else -> LocalAdbState.Ready(connected = false, uid = prefs.getInt(KEY_UID, -1).takeIf { it >= 0 })
    }

    private val key: AdbKey by lazy {
        AdbKey.loadOrCreate(File(context.noBackupFilesDir, "adb/adbkey"), "DSH@" + (Build.MODEL ?: "android").replace(' ', '_'))
    }

    // ---- shell ------------------------------------------------------------------------------

    /**
     * True when a command can run now, connecting if needed. A failed attempt is not repeated for
     * [RETRY_AFTER_MS] (tools fall back instead of each waiting for a timeout); [force] skips that.
     */
    suspend fun ensureConnected(force: Boolean = false): Boolean {
        if (!supported || !paired || !enabled) return false
        if (conn?.isOpen == true) return true
        if (!force && _state.value is LocalAdbState.Unreachable && System.currentTimeMillis() - lastFailureAt < RETRY_AFTER_MS) return false
        return connect(userInitiated = force) != null
    }

    /** Runs [command] as the shell user; throws [IOException] when the channel is not usable. */
    suspend fun exec(command: String, timeoutMs: Long): ShellResult {
        repeat(2) { attempt ->
            if (!ensureConnected(force = attempt > 0)) throw IOException(_state.value.reason)
            val c = conn ?: return@repeat
            touch()
            try {
                return withContext(Dispatchers.IO) { c.exec(command, timeoutMs) }
            } catch (_: AdbClosedException) {
                // Closed (idle timeout, adbd restart) before the command went out: once more on a new connection.
            } finally { touch() }
        }
        throw IOException(_state.value.reason)
    }

    /** Closes an idle connection after [IDLE_MS]; the next command reconnects (about a second). */
    private fun touch() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(IDLE_MS)
            connectLock.withLock {
                conn?.let { it.onClosed = null; it.close() }
                conn = null
                _state.update { if (it is LocalAdbState.Ready) it.copy(connected = false) else it }
            }
        }
    }

    // ---- connect / disconnect ---------------------------------------------------------------

    /** Connects (port: [manualPort], else the last one, else mDNS); null when it failed (see [state]). */
    suspend fun connect(manualPort: Int? = null, userInitiated: Boolean = false): AdbConnection? = connectLock.withLock {
        conn?.takeIf { it.isOpen }?.let { return it }
        if (!supported || !paired) return null
        if (userInitiated) prefs.edit().putBoolean(KEY_ENABLED, true).apply()
        if (!enabled) return null
        _state.value = LocalAdbState.Connecting
        val result = runCatching { withContext(Dispatchers.IO) { open(manualPort) } }
        val c = result.getOrNull()
        if (c == null) {
            val e = result.exceptionOrNull()
            Log.i(TAG, "connect failed: $e")
            lastFailureAt = System.currentTimeMillis()
            _state.value = LocalAdbState.failed(e)
            return null
        }
        c.onClosed = { err -> onLost(c, err) }
        conn = c
        val uid = runCatching { c.exec("id -u", 10_000).output.trim().toIntOrNull() }.getOrNull()
        if (uid != null) prefs.edit().putInt(KEY_UID, uid).apply()
        _state.value = LocalAdbState.Ready(connected = true, uid = uid)
        selfGrantSecureSettings(c)
        touch()
        return c
    }

    private fun onLost(c: AdbConnection, err: Throwable?) {
        if (conn !== c) return
        conn = null
        Log.i(TAG, "connection lost: $err")
        // Wireless debugging turned off, Wi-Fi changed, …: find out now so the card tells the truth.
        scope.launch { delay(1_000); connect() }
    }

    /** 断开: closes the connection and stays off until the user connects again. */
    fun disconnect() {
        prefs.edit().putBoolean(KEY_ENABLED, false).apply()
        scope.launch {
            connectLock.withLock {
                conn?.let { it.onClosed = null; it.close() }
                conn = null
                idleJob?.cancel()
                _state.value = if (paired) LocalAdbState.Off else LocalAdbState.NotPaired
            }
        }
    }

    private suspend fun open(manualPort: Int?): AdbConnection {
        val candidates = buildList {
            manualPort?.let { add(it) }
            prefs.getInt(KEY_PORT, 0).takeIf { it > 0 && it != manualPort }?.let { add(it) }
        }
        var last: Exception? = null
        for (port in candidates) {
            try { return openOn(port) } catch (e: AdbNotPairedException) { throw e } catch (e: IOException) { last = e }
        }
        if (manualPort != null) throw last ?: NoPortException("port $manualPort did not answer")
        // After a reboot wireless debugging is usually off; with WRITE_SECURE_SETTINGS (granted to
        // ourselves over this channel before) turn it back on, as Shizuku's start-on-boot does.
        val port = findConnectPort(MDNS_WAIT_MS)
            ?: (if (turnOnWirelessDebugging()) findConnectPort(MDNS_WAIT_AFTER_ENABLE_MS) else null)
            ?: throw NoPortException("no wireless-debugging service found (is it on, with Wi-Fi connected?)")
        return openOn(port)
    }

    private fun openOn(port: Int): AdbConnection =
        AdbConnection.open(LOCALHOST, port, key).also { prefs.edit().putInt(KEY_PORT, port).apply() }

    private suspend fun findConnectPort(waitMs: Long): Int? = withContext(Dispatchers.Main) {
        val found = MutableStateFlow<Int?>(null)
        val mdns = AdbMdns(context, AdbMdns.TLS_CONNECT) { p -> if (p != null) found.value = p }.start()
        try { withTimeoutOrNull(waitMs) { found.first { it != null } } } finally { mdns.stop() }
    }

    private fun canWriteSecureSettings() = context.checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    private fun turnOnWirelessDebugging(): Boolean {
        if (!canWriteSecureSettings()) return false
        val on = runCatching { Settings.Global.getInt(context.contentResolver, ADB_WIFI_ENABLED, 0) == 1 }.getOrDefault(false)
        if (on) return false
        return runCatching { Settings.Global.putInt(context.contentResolver, ADB_WIFI_ENABLED, 1) }.getOrDefault(false)
    }

    /** One-time `pm grant` of WRITE_SECURE_SETTINGS (a development permission the shell user may grant). */
    private fun selfGrantSecureSettings(c: AdbConnection) {
        if (canWriteSecureSettings()) return
        runCatching { c.exec("pm grant ${context.packageName} $WRITE_SECURE_SETTINGS", 10_000) }
    }

    // ---- pairing ----------------------------------------------------------------------------

    /** Starts listening for the pairing dialog's service (mDNS) and shows the code-entry notification. */
    fun startPairing() {
        if (!supported) return
        stopPairingDiscovery()
        _pairing.value = AdbPairingFlow()
        pairingMdns = AdbMdns(context, AdbMdns.TLS_PAIRING) { port ->
            _pairing.update { it?.copy(port = port ?: it.port) }
            if (port != null) AdbPairingNotifier.showCodeEntry(context, port)
        }.also { m -> scope.launch(Dispatchers.Main) { m.start() } }
        AdbPairingNotifier.showWaiting(context)
        pairingTimeout?.cancel()
        pairingTimeout = scope.launch { delay(PAIRING_WINDOW_MS); if (_pairing.value?.busy == false) cancelPairing() }
    }

    fun cancelPairing() {
        stopPairingDiscovery()
        pairingTimeout?.cancel()
        _pairing.value = null
        AdbPairingNotifier.cancel(context)
    }

    private fun stopPairingDiscovery() {
        val m = pairingMdns ?: return
        pairingMdns = null
        scope.launch(Dispatchers.Main) { m.stop() }
    }

    /**
     * Pairs with [code] on [port] (else the port mDNS found), then connects. Returns true on success;
     * failures land in [pairing] (and the notification) so the user can retry.
     */
    suspend fun pair(code: String, port: Int? = null): Boolean {
        val digits = code.filter { it.isDigit() }
        val p = port ?: _pairing.value?.port
        if (_pairing.value == null) _pairing.value = AdbPairingFlow(port = p)
        if (p == null) {
            fail(AdbPairingException.Kind.NoService, "pairing port unknown")
            return false
        }
        _pairing.update { it?.copy(busy = true, error = null, errorDetail = null) }
        AdbPairingNotifier.showBusy(context)
        val err = runCatching {
            withContext(Dispatchers.IO) { AdbPairing(key, ::exportKeyingMaterial).pair(LOCALHOST, p, digits) }
        }.exceptionOrNull()
        if (err != null) {
            val e = err as? AdbPairingException ?: AdbPairingException(AdbPairingException.Kind.Other, err.message ?: err.javaClass.simpleName, err)
            Log.i(TAG, "pairing failed: ${e.kind} ${e.message}")
            fail(e.kind, e.message)
            return false
        }
        prefs.edit().putBoolean(KEY_PAIRED, true).putBoolean(KEY_ENABLED, true).remove(KEY_PORT).apply()
        stopPairingDiscovery()
        pairingTimeout?.cancel()
        _pairing.value = null
        val c = connect(userInitiated = true)
        AdbPairingNotifier.showDone(context, connected = c != null)
        return true
    }

    private fun fail(kind: AdbPairingException.Kind, detail: String?) {
        _pairing.update { (it ?: AdbPairingFlow()).copy(busy = false, error = kind, errorDetail = detail) }
        AdbPairingNotifier.showFailed(context, kind, _pairing.value?.port)
    }

    /** RFC 5705 exporter: public on Android 12+, Conscrypt's own method on Android 11. */
    private fun exportKeyingMaterial(socket: SSLSocket, label: String, length: Int): ByteArray =
        if (Build.VERSION.SDK_INT >= 31) SSLSockets.exportKeyingMaterial(socket, label, null, length)
            ?: throw AdbPairingException(AdbPairingException.Kind.Unsupported, "TLS key export returned nothing")
        else try {
            Class.forName("com.android.org.conscrypt.Conscrypt")
                .getMethod("exportKeyingMaterial", SSLSocket::class.java, String::class.java, ByteArray::class.java, Int::class.javaPrimitiveType)
                .invoke(null, socket, label, null, length) as ByteArray
        } catch (e: ReflectiveOperationException) {
            throw AdbPairingException(AdbPairingException.Kind.Unsupported, "this Android 11 build does not expose TLS key export: ${e.message}", e)
        }

    /** One line for 诊断信息. */
    fun describe(): String = "${_state.value.reason}" + (if (paired) "; last port ${prefs.getInt(KEY_PORT, 0).takeIf { it > 0 } ?: "-"}" else "") +
        "; secure settings ${if (canWriteSecureSettings()) "granted" else "not granted"}"

    companion object {
        private const val TAG = "LocalAdb"
        const val LOCALHOST = "127.0.0.1"
        private const val KEY_PAIRED = "paired"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_PORT = "port"
        private const val KEY_UID = "uid"
        private const val WRITE_SECURE_SETTINGS = "android.permission.WRITE_SECURE_SETTINGS"
        private const val ADB_WIFI_ENABLED = "adb_wifi_enabled"
        private const val RETRY_AFTER_MS = 20_000L
        private const val IDLE_MS = 3 * 60_000L
        private const val MDNS_WAIT_MS = 4_000L
        private const val MDNS_WAIT_AFTER_ENABLE_MS = 8_000L
        private const val PAIRING_WINDOW_MS = 10 * 60_000L
    }
}
