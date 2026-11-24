package io.github.jackwener.dsh.core.remote

import org.apache.sshd.client.ClientBuilder
import org.apache.sshd.client.SshClient
import org.apache.sshd.client.channel.ChannelExec
import org.apache.sshd.client.config.hosts.HostConfigEntryResolver
import org.apache.sshd.client.keyverifier.ServerKeyVerifier
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.common.BaseBuilder
import org.apache.sshd.common.NamedFactory
import org.apache.sshd.common.NamedResource
import org.apache.sshd.common.kex.BuiltinDHFactories
import org.apache.sshd.common.config.keys.FilePasswordProvider
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.apache.sshd.common.digest.BuiltinDigests
import org.apache.sshd.common.keyprovider.KeyIdentityProvider
import org.apache.sshd.common.util.security.SecurityUtils
import org.apache.sshd.core.CoreModuleProperties
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException
import java.security.KeyPair
import java.security.PublicKey
import java.time.Duration

/** What one SSH connection needs: the address, the credential, and the host key the user trusts (null = none yet). */
data class SshTarget(
    val host: String,
    val port: Int,
    val user: String,
    val password: String? = null,
    /** OpenSSH or PEM private key text. */
    val privateKey: String? = null,
    val passphrase: String? = null,
    /** OpenSSH public key line of the trusted host key. */
    val trustedHostKey: String? = null,
) {
    override fun toString() = "SshTarget($user@$host:$port)" // never the secrets
}

/** A remote-mode failure, classified for the user (RemoteErrors renders it). */
class RemoteException(
    val kind: Kind,
    message: String,
    /** The host key the server presented (HostKeyUnknown / HostKeyChanged): OpenSSH line and SHA-256 fingerprint. */
    val presentedKey: String? = null,
    val presentedFingerprint: String? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {
    enum class Kind {
        Offline, Dns, Timeout, Refused, Unreachable, HostKeyUnknown, HostKeyChanged, Auth, KeyInvalid, KeyPassphrase,
        Workdir, NoDsh, DshExited, Initialize, Disconnected, HostMissing, Other,
    }
}

/** Host key helpers: the trusted key is stored as an OpenSSH public key line, shown as its SHA-256 fingerprint. */
object HostKeys {
    fun fingerprint(key: PublicKey): String = KeyUtils.getFingerPrint(BuiltinDigests.sha256, key)
    fun line(key: PublicKey): String = PublicKeyEntry.toString(key)
    fun parse(line: String): PublicKey? = runCatching { PublicKeyEntry.parsePublicKeyEntry(line).resolvePublicKey(null, emptyMap(), null) }.getOrNull()
    fun matches(trusted: String, key: PublicKey): Boolean = parse(trusted)?.let { KeyUtils.compareKeys(it, key) } == true
}

/**
 * Key exchanges this device can actually run. MINA offers curve25519 / curve448 (and the post-quantum hybrids built
 * on X25519) whenever a provider lists XDH, but on Android the key pair and the agreement can come from different
 * providers that reject each other's keys ("Unsupported key type"), and before Android 13 there is no XDH at all.
 * So each Montgomery curve is tried once for real; the ECDH (nistp*) and DH group exchanges, which Android has
 * always had, stay either way.
 */
object SshAlgorithms {
    val kex: List<BuiltinDHFactories> by lazy {
        val montgomery = mapOf("25519" to works(BuiltinDHFactories.curve25519), "448" to works(BuiltinDHFactories.curve448))
        BaseBuilder.DEFAULT_KEX_PREFERENCE.filter { f ->
            f.isSupported && montgomery.none { (curve, ok) -> !ok && (curve in f.getName() || "sntrup" in f.getName()) }
        }
    }

    private fun works(f: BuiltinDHFactories): Boolean = f.isSupported && runCatching {
        val a = f.create()
        val b = f.create()
        val ea = a.e
        val eb = b.e
        a.setF(eb); b.setF(ea)
        a.k.contentEquals(b.k)
    }.getOrDefault(false)
}

/**
 * The SSH side of remote mode (#51): Apache MINA SSHD. One client per connection (its own host key check,
 * no ~/.ssh lookups), keepalive through `keepalive@openssh.com` requests, the exec channel's streams for
 * dsh's stdio. Blocking: call from Dispatchers.IO.
 */
class SshConnection private constructor(
    private val client: SshClient,
    private val session: ClientSession,
    private val channel: ChannelExec,
    /** The remote process's stdin / stdout. */
    val stdin: OutputStream,
    val stdout: InputStream,
    private val stderr: StderrTail,
) : Closeable {
    val isOpen: Boolean get() = session.isOpen && channel.isOpen

    /** The remote process's last stderr output (dsh boot errors), at most [StderrTail.MAX] chars. */
    fun stderrTail(): String = stderr.text()

    /** Exit status once the remote command ended (null while it runs or when the server sent none). */
    val exitStatus: Int? get() = channel.exitStatus

    override fun close() {
        runCatching { channel.close(true) }
        runCatching { session.close(true) }
        runCatching { client.stop() }
    }

    class StderrTail(input: InputStream) {
        private val buf = StringBuilder()
        init {
            Thread({
                runCatching {
                    input.bufferedReader().forEachLine { l -> synchronized(buf) { buf.append(l).append('\n'); if (buf.length > MAX) buf.delete(0, buf.length - MAX) } }
                }
            }, "ssh-stderr").apply { isDaemon = true }.start()
        }
        fun text(): String = synchronized(buf) { buf.toString().trim() }
        companion object { const val MAX = 4000 }
    }

    companion object {
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(15)
        val AUTH_TIMEOUT: Duration = Duration.ofSeconds(30)
        /** keepalive@openssh.com every 15 s; 3 unanswered (≈45 s) close the session, which the engine reports as lost. */
        val HEARTBEAT: Duration = Duration.ofSeconds(15)
        const val HEARTBEAT_MISSES = 3

        /**
         * Connects, checks the host key against [SshTarget.trustedHostKey] (unknown or different: a
         * [RemoteException] carrying the presented key, nothing is sent), authenticates and starts [command].
         */
        fun open(target: SshTarget, command: String): SshConnection {
            var presented: PublicKey? = null
            val client = SshClient.setUpDefaultClient().apply {
                keyExchangeFactories = NamedFactory.setUpTransformedFactories(false, SshAlgorithms.kex, ClientBuilder.DH2KEX)
                hostConfigEntryResolver = HostConfigEntryResolver.EMPTY
                keyIdentityProvider = KeyIdentityProvider.EMPTY_KEYS_PROVIDER
                serverKeyVerifier = ServerKeyVerifier { _, _, key ->
                    presented = key
                    target.trustedHostKey != null && HostKeys.matches(target.trustedHostKey, key)
                }
                CoreModuleProperties.HEARTBEAT_INTERVAL.set(this, HEARTBEAT)
                CoreModuleProperties.HEARTBEAT_REPLY_WAIT.set(this, HEARTBEAT)
                CoreModuleProperties.HEARTBEAT_NO_REPLY_MAX.set(this, HEARTBEAT_MISSES)
                // The heartbeat keeps the link busy; an idle dsh must not be closed by MINA's own idle timer.
                CoreModuleProperties.IDLE_TIMEOUT.set(this, Duration.ZERO)
                CoreModuleProperties.NIO2_READ_TIMEOUT.set(this, Duration.ZERO)
            }
            client.start()
            var session: ClientSession? = null
            try {
                val identities = target.privateKey?.let { loadKey(it, target.passphrase) }
                session = try {
                    client.connect(target.user, RemoteAddress.normalize(target.host), target.port).verify(CONNECT_TIMEOUT).session
                } catch (e: Exception) { throw classifyConnect(e) }
                target.password?.let(session::addPasswordIdentity)
                identities?.forEach(session::addPublicKeyIdentity)
                try {
                    session.auth().verify(AUTH_TIMEOUT)
                } catch (e: Exception) {
                    presented?.let { key -> hostKeyProblem(target, key)?.let { throw it } }
                    throw RemoteException(RemoteException.Kind.Auth, e.message ?: "authentication failed", cause = e)
                }
                val channel = session.createExecChannel(command)
                channel.open().verify(CONNECT_TIMEOUT)
                return SshConnection(client, session, channel, channel.invertedIn, channel.invertedOut, StderrTail(channel.invertedErr))
            } catch (e: Exception) {
                runCatching { session?.close(true) }
                runCatching { client.stop() }
                // A refused host key surfaces as a key-exchange error from connect/auth: report the key instead.
                val key = presented
                if (e !is RemoteException && key != null) hostKeyProblem(target, key)?.let { throw it }
                if (e is RemoteException) throw e
                throw RemoteException(RemoteException.Kind.Other, e.message ?: e.javaClass.simpleName, cause = e)
            }
        }

        private fun hostKeyProblem(target: SshTarget, key: PublicKey): RemoteException? = when {
            target.trustedHostKey == null ->
                RemoteException(RemoteException.Kind.HostKeyUnknown, "unknown host key", HostKeys.line(key), HostKeys.fingerprint(key))
            !HostKeys.matches(target.trustedHostKey, key) ->
                RemoteException(RemoteException.Kind.HostKeyChanged, "host key changed", HostKeys.line(key), HostKeys.fingerprint(key))
            else -> null
        }

        /** Parses an OpenSSH / PEM private key; a missing or wrong passphrase is [RemoteException.Kind.KeyPassphrase]. */
        fun loadKey(text: String, passphrase: String?): List<KeyPair> {
            val provider = FilePasswordProvider { _, _, _ -> passphrase?.takeIf { it.isNotEmpty() } }
            val keys = try {
                SecurityUtils.loadKeyPairIdentities(null, NamedResource.ofName("key"), ByteArrayInputStream(text.trim().toByteArray() + '\n'.code.toByte()), provider)?.toList()
            } catch (e: Exception) {
                val msg = (e.message ?: "").lowercase()
                val pass = passphrase.isNullOrEmpty() && ("password" in msg || "passphrase" in msg || "encrypt" in msg) ||
                    !passphrase.isNullOrEmpty() && ("decrypt" in msg || "password" in msg || "passphrase" in msg || "padding" in msg || "check" in msg)
                throw RemoteException(if (pass) RemoteException.Kind.KeyPassphrase else RemoteException.Kind.KeyInvalid, e.message ?: "invalid key", cause = e)
            }
            if (keys.isNullOrEmpty()) throw RemoteException(RemoteException.Kind.KeyInvalid, "no private key found")
            return keys
        }

        private fun classifyConnect(e: Throwable): RemoteException {
            val chain = generateSequence(e) { it.cause }.toList()
            val kind = when {
                chain.any { it is UnknownHostException || it is UnresolvedAddressException } -> RemoteException.Kind.Dns
                chain.any { it is ConnectException && (it.message ?: "").contains("refused", true) } -> RemoteException.Kind.Refused
                chain.any { it is NoRouteToHostException || (it.message ?: "").contains("unreachable", true) } -> RemoteException.Kind.Unreachable
                chain.any { it is SocketTimeoutException || (it.message ?: "").contains("timeout", true) || (it.message ?: "").contains("timed out", true) } -> RemoteException.Kind.Timeout
                chain.any { it is ConnectException } -> RemoteException.Kind.Refused
                else -> RemoteException.Kind.Other
            }
            return RemoteException(kind, chain.last().message ?: e.message ?: e.javaClass.simpleName, cause = e)
        }
    }
}
