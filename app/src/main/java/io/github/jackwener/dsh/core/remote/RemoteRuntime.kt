package io.github.jackwener.dsh.core.remote

import io.github.jackwener.dsh.core.engine.JsonRpcException
import io.github.jackwener.dsh.core.engine.JsonRpcPeer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.Closeable
import java.util.Timer
import kotlin.concurrent.schedule

/**
 * One running remote dsh: the SSH exec channel and the same [JsonRpcPeer] the embedded engine uses, over
 * the channel's stdin/stdout, initialized (`initialize`, ENGINE-CONTRACT §2) on the host's model route in
 * the directory the launch script entered. Plain JVM (no Android), so the whole path is unit-tested
 * against a real SSH server.
 */
class RemoteRuntime private constructor(
    private val connection: SshConnection,
    val peer: JsonRpcPeer,
    /** The remote working directory new sessions start in (absolute). */
    val cwd: String,
) : Closeable {
    val isOpen: Boolean get() = connection.isOpen

    /** stderr of the remote dsh (its boot errors), for a failure's detail. */
    fun stderrTail(): String = connection.stderrTail()

    override fun close() = connection.close()

    /** What the launch needs besides the SSH target. */
    data class Launch(
        /** Names the takeover pid file on the remote (one per host entry of this install). */
        val instance: String,
        val workdir: String,
        val command: String,
        val provider: String,
        val model: String,
        /** Environment for the remote dsh (the shared API key, the user's variables); sent over the channel. */
        val env: List<Pair<String, String>>,
    ) {
        override fun toString() = "Launch($instance, $workdir, $command, $provider/$model, env ${env.map { it.first }})" // names only
    }

    companion object {
        const val HANDSHAKE_TIMEOUT_MS = 60_000L
        const val INITIALIZE_TIMEOUT_MS = 90_000L

        /**
         * Connects, starts dsh and initializes it. Failures are [RemoteException]s (connection, host key,
         * authentication, working directory, dsh missing or exiting, `initialize` refused); nothing is left open.
         */
        suspend fun start(
            target: SshTarget,
            launch: Launch,
            scope: CoroutineScope,
            onRequest: suspend (String, JsonObject) -> JsonElement,
            onNotification: suspend (String, JsonObject) -> Unit,
            onClosed: () -> Unit,
        ): RemoteRuntime {
            val connection = withContext(Dispatchers.IO) { SshConnection.open(target, RemoteLaunch.command) }
            try {
                val handshake = withContext(Dispatchers.IO) {
                    connection.stdin.write(RemoteLaunch.preamble(launch.instance, launch.workdir, launch.command, launch.env).toByteArray())
                    connection.stdin.flush()
                    // A blocking read: a watchdog closes the channel if the script never answers.
                    val watchdog = Timer("remote-handshake", true).apply { schedule(HANDSHAKE_TIMEOUT_MS) { connection.close() } }
                    try { RemoteLaunch.awaitHandshake(connection.stdout) } finally { watchdog.cancel() }
                }
                val cwd = when (handshake) {
                    is RemoteLaunch.Handshake.Ready -> handshake.cwd
                    is RemoteLaunch.Handshake.Failed -> throw when (handshake.kind) {
                        "workdir" -> RemoteException(RemoteException.Kind.Workdir, handshake.detail)
                        else -> RemoteException(RemoteException.Kind.NoDsh, handshake.detail)
                    }
                    null -> throw exited(connection, "the remote command ended before starting dsh")
                }
                val peer = JsonRpcPeer("remote", connection.stdout, connection.stdin, scope, onRequest, onNotification, onClosed)
                peer.start()
                try {
                    withTimeout(INITIALIZE_TIMEOUT_MS) {
                        peer.request("initialize", buildJsonObject {
                            put("cwd", cwd)
                            put("provider", launch.provider)
                            put("model", launch.model)
                            put("approvalRequests", true)
                            putJsonObject("capabilities") { put("userQuestions", true) }
                        })
                    }
                } catch (e: JsonRpcException) {
                    // A closed channel means dsh died while booting (old Node, broken install): its stderr says why.
                    if (!connection.isOpen || e.code == -32000) throw exited(connection, e.message ?: "dsh exited")
                    throw RemoteException(RemoteException.Kind.Initialize, e.message ?: "initialize failed", cause = e)
                }
                return RemoteRuntime(connection, peer, cwd)
            } catch (e: Throwable) {
                connection.close()
                if (e is RemoteException || e is kotlinx.coroutines.CancellationException) throw e
                throw RemoteException(RemoteException.Kind.Other, e.message ?: e.javaClass.simpleName, cause = e)
            }
        }

        private suspend fun exited(connection: SshConnection, fallback: String): RemoteException {
            // The exit status arrives with the channel's close; give it a moment.
            repeat(20) { if (connection.exitStatus == null) kotlinx.coroutines.delay(50) }
            val status = connection.exitStatus
            val tail = connection.stderrTail()
            val kind = if (status == 127 || tail.contains("command not found") || tail.contains("No such file or directory") && tail.contains("node"))
                RemoteException.Kind.NoDsh else RemoteException.Kind.DshExited
            return RemoteException(kind, listOfNotNull(status?.let { "exit $it" }, tail.takeLast(1500).ifBlank { null } ?: fallback).joinToString(": "))
        }
    }
}
