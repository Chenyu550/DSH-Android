package io.github.jackwener.dsh.core.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jackwener.dsh.core.engine.EngineCallCancelled
import io.github.jackwener.dsh.core.engine.JsonRpcPeer
import io.github.jackwener.dsh.core.engine.RestartPolicy
import io.github.jackwener.dsh.core.engine.SdkEngine
import io.github.jackwener.dsh.core.session.SessionStore
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

sealed interface RemoteState {
    data object Idle : RemoteState
    data object Connecting : RemoteState
    data class Connected(val cwd: String) : RemoteState
    /** The last connection attempt failed, or the link dropped ([RemoteException.Kind.Disconnected]). */
    data class Failed(val error: RemoteException) : RemoteState
}

/**
 * One computer's dsh as an [SdkEngine] (#51): connects on demand (the first prompt of one of its
 * conversations), keeps the SSH link alive while it is used and closes it after [IDLE_MS] without a run.
 *
 * Losing the link (keepalive timeout, the server going away, the phone switching networks) is an engine
 * loss like an `:engine` crash: SessionStore resumes the interrupted conversations on a new connection
 * and continues them once (the remote dsh ends with its stdin, so the turn there is over). A change of
 * the phone's default network (Wi-Fi ↔ mobile data) reconnects right away instead of waiting for the
 * keepalive to time out on a dead socket.
 */
class RemoteEngine internal constructor(
    val hostId: String,
    private val context: Context,
    private val hosts: RemoteHostStore,
    private val sessions: Lazy<SessionStore>,
    private val scope: CoroutineScope,
    private val onStateChanged: () -> Unit,
) : SdkEngine {
    override val androidExtensions get() = false

    private val mutex = Mutex()
    private val _state = MutableStateFlow<RemoteState>(RemoteState.Idle)
    val state: StateFlow<RemoteState> = _state.asStateFlow()
    @Volatile private var runtime: RemoteRuntime? = null
    @Volatile override var generation: Int = 0; private set
    @Volatile override var retiredGeneration: Int = 0; private set
    @Volatile private var expectedExit = -1
    private val restarts = RestartPolicy()
    private var idleJob: Job? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var network: Network? = null
    @Volatile private var networkChanged = false

    private fun setState(s: RemoteState) { _state.value = s; onStateChanged() }

    override fun sdkIfReady(): JsonRpcPeer? = runtime?.takeIf { it.isOpen }?.peer

    /** A ready channel, connecting first when needed. Throws [RemoteException]. */
    suspend fun sdk(): JsonRpcPeer = mutex.withLock { sdkIfReady() ?: connectLocked() }

    override fun markSessionOpened() {}

    private suspend fun connectLocked(): JsonRpcPeer {
        val host = hosts.get(hostId) ?: throw RemoteException(RemoteException.Kind.HostMissing, "host $hostId was removed")
        setState(RemoteState.Connecting)
        val gen = generation + 1
        try {
            awaitNetwork()
            val t0 = System.currentTimeMillis()
            val rt = RemoteRuntime.start(hosts.target(host), hosts.launch(host), scope,
                onRequest = { m, p -> sessions.get().onServerRequest(m, p) },
                onNotification = { m, p -> sessions.get().onNotification(m, p, gen, this) },
                onClosed = { onGone(gen) })
            runtime = rt
            generation = gen
            Log.i(TAG, "host $hostId gen $gen connected in ${System.currentTimeMillis() - t0} ms (cwd ${rt.cwd})")
            setState(RemoteState.Connected(rt.cwd))
            watchNetwork()
            RemoteService.start(context)
            scope.launch { delay(RestartPolicy.STABLE_MS); if (generation == gen && runtime === rt) restarts.recordHealthy() }
            return rt.peer
        } catch (e: RemoteException) {
            Log.w(TAG, "host $hostId: connect failed (${e.kind}): ${e.message}")
            setState(RemoteState.Failed(e))
            throw e
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) { setState(RemoteState.Idle); throw e }
            val r = RemoteException(RemoteException.Kind.Other, e.message ?: e.javaClass.simpleName, cause = e)
            setState(RemoteState.Failed(r))
            throw r
        }
    }

    /** No network at all: wait a little for one (the phone is switching), then give up as offline. */
    private suspend fun awaitNetwork() {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        if (cm.activeNetwork != null) return
        withTimeoutOrNull(NETWORK_WAIT_MS) { while (cm.activeNetwork == null) delay(500) }
            ?: throw RemoteException(RemoteException.Kind.Offline, "no network")
    }

    /** Reconnects when the default network changes under a live link (its socket is bound to the old one). */
    private fun watchNetwork() {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        unwatchNetwork()
        network = cm.activeNetwork
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: Network) {
                val old = network
                network = n
                val rt = runtime ?: return
                if (old != null && n != old) {
                    Log.i(TAG, "host $hostId: default network changed; reconnecting")
                    networkChanged = true
                    scope.launch(Dispatchers.IO) { rt.close() }
                }
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(cb); netCallback = cb }.onFailure { Log.w(TAG, "network callback", it) }
    }

    private fun unwatchNetwork() {
        val cb = netCallback ?: return
        netCallback = null
        runCatching { context.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) }
    }

    /** The channel of [gen] closed. Unexpected: an engine loss (SessionStore resumes and continues its runs). */
    private fun onGone(gen: Int) {
        val retryInMs: Long?
        synchronized(this) {
            if (gen != generation || gen == expectedExit || runtime == null) return
            val tail = runtime?.stderrTail().orEmpty()
            Log.w(TAG, "host $hostId gen $gen: link lost${if (tail.isNotEmpty()) " (stderr: ${tail.takeLast(300)})" else ""}")
            teardown(gen)
            retryInMs = if (networkChanged) NETWORK_SWITCH_RETRY_MS else restarts.recordFailure()
            networkChanged = false
        }
        // Nothing ran: the next message simply connects again. Otherwise the resumed runs reconnect (RemoteService stays).
        setState(if (sessions.get().hasRunning(hostId)) RemoteState.Failed(RemoteException(RemoteException.Kind.Disconnected, "connection lost")) else RemoteState.Idle)
        scope.launch {
            sessions.get().onEngineLost(gen, retryInMs, this@RemoteEngine)
            // No run continues (breaker open, already continued once): nothing will reconnect by itself.
            if (!sessions.get().hasRunning(hostId) && (_state.value as? RemoteState.Failed)?.error?.kind == RemoteException.Kind.Disconnected) setState(RemoteState.Idle)
        }
    }

    private fun teardown(gen: Int) {
        retiredGeneration = maxOf(retiredGeneration, gen)
        val rt = runtime
        runtime = null
        rt?.peer?.cancelRequests(EngineCallCancelled("remote generation $gen ended"))
        unwatchNetwork()
        if (rt != null) scope.launch(Dispatchers.IO) { rt.close() }
        if (_state.value is RemoteState.Connected) setState(RemoteState.Idle)
    }

    override fun cancelIdleShutdown() { idleJob?.cancel() }

    override fun scheduleIdleShutdown() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(IDLE_MS)
            if (sessions.get().hasRunning(hostId)) return@launch
            Log.i(TAG, "host $hostId idle: disconnecting")
            stop()
        }
    }

    /** Ends the remote dsh (`shutdown`, then the link). Its running turns end; SessionStore marks them stopped. */
    override suspend fun stop() = mutex.withLock {
        val rt = runtime ?: run { if (_state.value !is RemoteState.Failed) setState(RemoteState.Idle); return@withLock }
        expectedExit = generation
        runCatching { withTimeout(3_000) { rt.peer.request("shutdown") } }
        teardown(generation)
        setState(RemoteState.Idle)
    }

    /** Disconnects and forgets the last error (the host entry changed, or it was deleted). */
    suspend fun reset() { stop(); setState(RemoteState.Idle) }

    companion object {
        private const val TAG = "RemoteEngine"
        /** A remote dsh costs nothing on the phone but a socket; it stays a while for the next message. */
        const val IDLE_MS = 10 * 60_000L
        const val NETWORK_WAIT_MS = 20_000L
        const val NETWORK_SWITCH_RETRY_MS = 1_000L
    }
}

/** The remote engines, one per host entry, created on first use. */
@Singleton
class RemoteEngines @Inject constructor(
    @ApplicationContext private val context: Context,
    private val hosts: RemoteHostStore,
    private val sessions: Lazy<SessionStore>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> Log.e("RemoteEngines", "remote task crashed", e) })
    private val engines = ConcurrentHashMap<String, RemoteEngine>()
    private val _states = MutableStateFlow<Map<String, RemoteState>>(emptyMap())
    /** Connection state per host id (the drawer's and the top bar's status). */
    val states: StateFlow<Map<String, RemoteState>> = _states.asStateFlow()

    init {
        // MINA looks for ~/.ssh files and OS specifics; on Android, home is the app's own directory.
        org.apache.sshd.common.util.OsUtils.setAndroid(true)
        // Only the net.i2p Ed25519 provider: Android's stripped platform "BC" must not pass for BouncyCastle, and there is no SunJCE.
        System.setProperty("org.apache.sshd.security.registrars", "org.apache.sshd.common.util.security.eddsa.EdDSASecurityProviderRegistrar")
        org.apache.sshd.common.util.io.PathUtils.setUserHomeFolderResolver { context.filesDir.toPath() }
    }

    fun engine(hostId: String): RemoteEngine = engines.getOrPut(hostId) {
        RemoteEngine(hostId, context, hosts, sessions, scope) { _states.update { m -> m + (hostId to (engines[hostId]?.state?.value ?: RemoteState.Idle)) } }
    }

    fun existing(hostId: String): RemoteEngine? = engines[hostId]


    /** The entry changed (address, credential, directory, model) or was deleted: the next prompt connects anew. */
    suspend fun reset(hostId: String) { engines[hostId]?.reset() }

    /**
     * 测试连接 with the editor's (maybe unsaved) values: connect, start dsh, `initialize`, shut down.
     * Returns the remote working directory. Host key problems come back as [RemoteException]s carrying the key.
     */
    suspend fun test(target: SshTarget, launch: RemoteRuntime.Launch): String = withContext(Dispatchers.IO) {
        val rt = RemoteRuntime.start(target, launch.copy(instance = launch.instance + "-test"), scope, { m, _ -> throw io.github.jackwener.dsh.core.engine.JsonRpcException(-32601, m) }, { _, _ -> }, {})
        try {
            runCatching { withTimeout(3_000) { rt.peer.request("shutdown") } }
            rt.cwd
        } finally { rt.close() }
    }
}
