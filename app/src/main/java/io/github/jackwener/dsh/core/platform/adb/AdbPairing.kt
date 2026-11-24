package io.github.jackwener.dsh.core.platform.adb

import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLSocket

/** Pairing failed; [kind] says what the user can do about it, [message] the details. */
class AdbPairingException(val kind: Kind, message: String, cause: Throwable? = null) : IOException(message, cause) {
    enum class Kind { WrongCode, NoService, Timeout, Unsupported, Other }
}

/**
 * The client half of adb's wireless pairing (packages/modules/adb pairing_connection + pairing_auth):
 * TLS 1.3 to the pairing port, SPAKE2 with password = the 6-digit code + 64 bytes exported from the
 * TLS session ("adb-label\0"), then each side sends its PeerInfo (type + "base64key user@host")
 * sealed with AES-128-GCM under HKDF-SHA256(spake key, "adb pairing_auth aes-128-gcm key").
 * On success adbd stores our key; later connections only need the TLS client certificate.
 */
class AdbPairing(
    private val key: AdbKey,
    /** RFC 5705 exporter of the TLS session (SSLSockets.exportKeyingMaterial on Android); injectable for tests. */
    private val exporter: (SSLSocket, String, Int) -> ByteArray,
) {
    /** Pairs with adbd's pairing service on [host]:[port] using [code]; throws [AdbPairingException]. */
    fun pair(host: String, port: Int, code: String, timeoutMs: Int = 15_000) {
        val raw = Socket()
        try {
            raw.connect(InetSocketAddress(host, port), timeoutMs)
            raw.soTimeout = timeoutMs
            val tls = key.sslContext().socketFactory.createSocket(raw, host, port, true) as SSLSocket
            tls.startHandshake()
            val exported = exporter(tls, EXPORT_LABEL, EXPORT_SIZE)
            run(DataInputStream(tls.inputStream), tls.outputStream, code.trim().toByteArray() + exported)
        } catch (e: AdbPairingException) {
            throw e
        } catch (e: java.net.ConnectException) {
            throw AdbPairingException(AdbPairingException.Kind.NoService, "nothing is listening on port $port: the pairing dialog is closed or the port is wrong", e)
        } catch (e: java.net.SocketTimeoutException) {
            throw AdbPairingException(AdbPairingException.Kind.Timeout, "the pairing service did not answer in time", e)
        } catch (e: IOException) {
            throw AdbPairingException(AdbPairingException.Kind.Other, "pairing failed: ${e.message ?: e.javaClass.simpleName}", e)
        } finally {
            runCatching { raw.close() }
        }
    }

    /** The exchange after the TLS handshake (separate so tests can drive it over any stream). */
    internal fun run(input: DataInputStream, output: OutputStream, password: ByteArray) {
        val spake = Spake2(Spake2.Role.Alice, CLIENT_NAME, SERVER_NAME)
        writePacket(output, TYPE_SPAKE2_MSG, spake.generateMessage(password))
        val theirMsg = readPacket(input, TYPE_SPAKE2_MSG)
        val cipher = PairingCipher(try { spake.processMessage(theirMsg) } catch (e: IllegalArgumentException) {
            throw AdbPairingException(AdbPairingException.Kind.Other, "the device sent an invalid pairing message", e)
        })
        writePacket(output, TYPE_PEER_INFO, cipher.encrypt(peerInfo(key.adbPublicKey)))
        // A wrong code shows up here: adbd cannot decrypt our PeerInfo and hangs up.
        val sealed = try { readPacket(input, TYPE_PEER_INFO) } catch (e: AdbPairingException) { throw e } catch (e: IOException) {
            throw AdbPairingException(AdbPairingException.Kind.WrongCode, WRONG_CODE, e)
        }
        val theirs = try { cipher.decrypt(sealed) } catch (e: AEADBadTagException) {
            throw AdbPairingException(AdbPairingException.Kind.WrongCode, WRONG_CODE, e)
        }
        if (theirs.size != PEER_INFO_SIZE) throw AdbPairingException(AdbPairingException.Kind.Other, "the device sent an unexpected pairing reply (${theirs.size} bytes)")
    }

    companion object {
        const val EXPORT_LABEL = "adb-label\u0000"
        const val EXPORT_SIZE = 64
        const val WRONG_CODE = "the device refused the pairing code (wrong or expired code)"
        val CLIENT_NAME = "adb pair client\u0000".toByteArray()
        val SERVER_NAME = "adb pair server\u0000".toByteArray()
        const val VERSION = 1
        const val TYPE_SPAKE2_MSG = 0
        const val TYPE_PEER_INFO = 1
        const val PEER_INFO_SIZE = 8192
        const val PEER_RSA_PUB_KEY = 0
        private const val MAX_PAYLOAD = PEER_INFO_SIZE * 2

        /** PeerInfo: 1-byte type + 8191 bytes of NUL-padded data. */
        fun peerInfo(adbPublicKey: String): ByteArray {
            val data = adbPublicKey.toByteArray()
            require(data.size < PEER_INFO_SIZE - 1) { "public key too long" }
            return ByteArray(PEER_INFO_SIZE).also { it[0] = PEER_RSA_PUB_KEY.toByte(); data.copyInto(it, 1) }
        }

        /** Header: version (1), type (1), payload size (4, big-endian). */
        fun writePacket(out: OutputStream, type: Int, payload: ByteArray) {
            out.write(ByteBuffer.allocate(6 + payload.size).order(ByteOrder.BIG_ENDIAN)
                .put(VERSION.toByte()).put(type.toByte()).putInt(payload.size).put(payload).array())
            out.flush()
        }

        fun readPacket(input: DataInputStream, expectType: Int): ByteArray {
            val version = input.readUnsignedByte()
            val type = input.readUnsignedByte()
            val size = input.readInt()
            if (version != VERSION) throw AdbPairingException(AdbPairingException.Kind.Other, "unsupported pairing protocol version $version")
            if (type != expectType) throw AdbPairingException(AdbPairingException.Kind.Other, "unexpected pairing packet (type $type, expected $expectType)")
            if (size <= 0 || size > MAX_PAYLOAD) throw AdbPairingException(AdbPairingException.Kind.Other, "bad pairing packet size $size")
            return ByteArray(size).also { input.readFully(it) }
        }

        /** HKDF-SHA256 (RFC 5869) with an empty salt. */
        fun hkdf(ikm: ByteArray, info: ByteArray, length: Int): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(ByteArray(32), "HmacSHA256"))
            val prk = mac.doFinal(ikm)
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            val out = java.io.ByteArrayOutputStream()
            var t = ByteArray(0)
            var i = 1
            while (out.size() < length) {
                mac.update(t); mac.update(info); mac.update(i++.toByte())
                t = mac.doFinal(); out.write(t)
            }
            return out.toByteArray().copyOf(length)
        }
    }
}

/** pairing_auth's Aes128Gcm: key from HKDF, 12-byte nonce = little-endian message counter (one per direction). */
class PairingCipher(spakeKey: ByteArray) {
    private val key = SecretKeySpec(AdbPairing.hkdf(spakeKey, "adb pairing_auth aes-128-gcm key".toByteArray(), 16), "AES")
    private var encSeq = 0L
    private var decSeq = 0L

    private fun nonce(seq: Long) = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putLong(seq).array()

    fun encrypt(plain: ByteArray): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
        init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce(encSeq++))); doFinal(plain)
    }

    fun decrypt(sealed: ByteArray): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
        init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce(decSeq++))); doFinal(sealed)
    }
}
