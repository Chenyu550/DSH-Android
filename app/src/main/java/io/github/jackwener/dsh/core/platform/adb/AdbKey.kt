package io.github.jackwener.dsh.core.platform.adb

import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Principal
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAKeyGenParameterSpec
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.TrustManager
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

/**
 * DSH's own adb key (RSA-2048, like `~/.android/adbkey`): what adbd learns at pairing and checks on
 * every TLS connection. Kept in app-private storage only; [name] is the "user@host" comment adbd
 * shows under 已配对的设备.
 */
class AdbKey(val privateKey: RSAPrivateCrtKey, val name: String) {
    val publicKey: RSAPublicKey = KeyFactory.getInstance("RSA")
        .generatePublic(RSAPublicKeySpec(privateKey.modulus, privateKey.publicExponent)) as RSAPublicKey

    /** Self-signed X.509 for the TLS client side; adbd only compares its public key with the paired ones. */
    val certificate: X509Certificate by lazy { selfSignedCertificate(publicKey, privateKey) }

    /** `base64(android_pubkey) user@host`: the PeerInfo of pairing and the AUTH RSAPUBLICKEY payload. */
    val adbPublicKey: String get() = Base64.getEncoder().encodeToString(encodeAndroidPubkey(publicKey)) + " " + name

    /** AUTH SIGNATURE for a 20-byte token: RSA PKCS#1 v1.5 over SHA-1's DigestInfo with the token as the digest (adbd RSA_verify(NID_sha1)). */
    fun signToken(token: ByteArray): ByteArray = Signature.getInstance("NONEwithRSA").run {
        initSign(privateKey); update(SHA1_DIGEST_INFO); update(token); sign()
    }

    /** TLS 1.3 with this key as the client certificate; adbd's own certificate is not checked (it is self-signed per device). */
    fun sslContext(): SSLContext = SSLContext.getInstance("TLSv1.3").apply {
        init(arrayOf<KeyManager>(keyManager()), arrayOf<TrustManager>(TrustAll), null)
    }

    private fun keyManager() = object : X509ExtendedKeyManager() {
        // adbd asks for a client certificate with its paired keys as "CA" names; ours is always the answer.
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
        override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = ALIAS
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
        override fun getCertificateChain(alias: String?) = arrayOf(certificate)
        override fun getPrivateKey(alias: String?): PrivateKey = privateKey
    }

    private object TrustAll : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    companion object {
        private const val ALIAS = "adbkey"
        private val SHA1_DIGEST_INFO = byteArrayOf(0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14)
        private const val WORDS = 64 // 2048-bit modulus in 32-bit words

        fun generate(name: String): AdbKey {
            val gen = KeyPairGenerator.getInstance("RSA").apply { initialize(RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4)) }
            return AdbKey(gen.generateKeyPair().private as RSAPrivateCrtKey, name)
        }

        /** The key in [file] (PKCS#8), created on first use. */
        fun loadOrCreate(file: File, name: String): AdbKey {
            if (file.isFile) runCatching {
                val k = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(file.readBytes())) as RSAPrivateCrtKey
                return AdbKey(k, name)
            }
            return generate(name).also { k ->
                file.parentFile?.mkdirs()
                val tmp = File(file.path + ".tmp")
                tmp.writeBytes(k.privateKey.encoded)
                if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
            }
        }

        /**
         * android_pubkey_encode (libcrypto_utils): little-endian {len = 64 words, n0inv = -1/n mod 2^32,
         * n[256], rr = 2^4096 mod n [256], e}. The first field is the size in words, not bits: get it
         * wrong and adbd never accepts the key.
         */
        fun encodeAndroidPubkey(k: RSAPublicKey): ByteArray {
            val n = k.modulus
            require(n.bitLength() <= WORDS * 32) { "only RSA-2048 keys" }
            val r32 = BigInteger.ONE.shiftLeft(32)
            val n0inv = (r32 - n.mod(r32).modInverse(r32)).toLong()
            val rr = BigInteger.ONE.shiftLeft(WORDS * 32 * 2).mod(n)
            val out = ByteArray(4 + 4 + WORDS * 4 * 2 + 4)
            putLe32(out, 0, WORDS.toLong())
            putLe32(out, 4, n0inv)
            putLe(out, 8, n, WORDS * 4)
            putLe(out, 8 + WORDS * 4, rr, WORDS * 4)
            putLe32(out, 8 + WORDS * 8, k.publicExponent.toLong())
            return out
        }

        private fun putLe32(b: ByteArray, at: Int, v: Long) { for (i in 0 until 4) b[at + i] = (v ushr (8 * i)).toByte() }
        private fun putLe(b: ByteArray, at: Int, v: BigInteger, len: Int) {
            val be = v.toByteArray()
            for (i in 0 until len) b[at + i] = if (i < be.size) be[be.size - 1 - i] else 0
        }

        // ---- a minimal self-signed X.509 v3 certificate (DER), sha256WithRSAEncryption ----------

        internal fun selfSignedCertificate(pub: RSAPublicKey, priv: PrivateKey): X509Certificate {
            val sigAlg = seq(oid(byteArrayOf(0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x01, 0x0b)), byteArrayOf(0x05, 0x00))
            val name = seq(set(seq(oid(byteArrayOf(0x55, 0x04, 0x03)), tlv(0x0c, "DSH adb".toByteArray()))))
            val now = System.currentTimeMillis()
            val validity = seq(time(now - 24 * 3600_000L), time(now + 20L * 365 * 24 * 3600_000L))
            val tbs = seq(
                tlv(0xa0, tlv(0x02, byteArrayOf(2))), // v3
                tlv(0x02, byteArrayOf(1)),            // serial
                sigAlg, name, validity, name,
                pub.encoded,                          // SubjectPublicKeyInfo
            )
            val sig = Signature.getInstance("SHA256withRSA").run { initSign(priv); update(tbs); sign() }
            val der = seq(tbs, sigAlg, tlv(0x03, byteArrayOf(0) + sig))
            return CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate
        }

        private fun time(ms: Long): ByteArray {
            // UTCTime, as RFC 5280 wants for dates before 2050 (strict DER parsers such as BoringSSL's).
            val f = java.text.SimpleDateFormat("yyMMddHHmmss'Z'", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            return tlv(0x17, f.format(java.util.Date(ms)).toByteArray())
        }
        private fun oid(body: ByteArray) = tlv(0x06, body)
        private fun seq(vararg parts: ByteArray) = tlv(0x30, *parts)
        private fun set(vararg parts: ByteArray) = tlv(0x31, *parts)
        private fun tlv(tag: Int, vararg parts: ByteArray): ByteArray {
            val body = ByteArrayOutputStream().apply { parts.forEach { write(it) } }.toByteArray()
            val out = ByteArrayOutputStream()
            out.write(tag)
            val n = body.size
            when {
                n < 0x80 -> out.write(n)
                n < 0x100 -> { out.write(0x81); out.write(n) }
                n < 0x10000 -> { out.write(0x82); out.write(n shr 8); out.write(n and 0xff) }
                else -> { out.write(0x83); out.write(n shr 16); out.write((n shr 8) and 0xff); out.write(n and 0xff) }
            }
            out.write(body)
            return out.toByteArray()
        }
    }
}
