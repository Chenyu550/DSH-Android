package io.github.jackwener.dsh.core.platform.adb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.DataInputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import javax.crypto.Cipher
import kotlin.concurrent.thread

/** #71: the adb key format, the pairing exchange and the message/shell-packet framing (no device needed). */
class AdbProtocolTest {
    private val key by lazy { AdbKey.generate("DSH@test") }

    @Test fun androidPubkeyHasWordCountMontgomeryValuesAndExponent() {
        val raw = AdbKey.encodeAndroidPubkey(key.publicKey)
        assertEquals(524, raw.size)
        val b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(64, b.int) // words, not bits (#71: the classic mistake that leaves adbd at AUTH TOKEN)
        val n0inv = b.int.toLong() and 0xffffffffL
        val n = key.publicKey.modulus
        // n0inv · n[0] ≡ -1 (mod 2^32)
        assertEquals(BigInteger.ONE.shiftLeft(32) - BigInteger.ONE, (BigInteger.valueOf(n0inv) * n).mod(BigInteger.ONE.shiftLeft(32)))
        fun le(len: Int) = BigInteger(1, ByteArray(len).also { b.get(it) }.reversedArray())
        assertEquals(n, le(256))
        assertEquals(BigInteger.ONE.shiftLeft(4096).mod(n), le(256))
        assertEquals(65537, b.int)
        assertTrue(key.adbPublicKey.endsWith(" DSH@test"))
        assertArrayEquals(raw, Base64.getDecoder().decode(key.adbPublicKey.substringBefore(' ')))
    }

    @Test fun certificateIsSelfSignedWithTheAdbKey() {
        val cert = key.certificate
        cert.checkValidity()
        cert.verify(key.publicKey)
        assertEquals(key.publicKey, cert.publicKey)
    }

    @Test fun tokenSignatureIsPkcs1OverSha1DigestInfo() {
        val token = ByteArray(20) { it.toByte() }
        val sig = key.signToken(token)
        val em = Cipher.getInstance("RSA/ECB/NoPadding").run { init(Cipher.DECRYPT_MODE, key.publicKey); doFinal(sig) }
        val digestInfo = byteArrayOf(0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14) + token
        assertArrayEquals(digestInfo, em.copyOfRange(em.size - digestInfo.size, em.size))
        assertEquals(1, em[em.size - 256 + 1].toInt()) // 00 01 FF… 00 DigestInfo (leading 00 dropped by BigInteger)
    }

    @Test fun keyIsStoredOnceAndReloaded() {
        val dir = kotlin.io.path.createTempDirectory("adbkey").toFile()
        val f = java.io.File(dir, "adb/adbkey")
        val a = AdbKey.loadOrCreate(f, "x@y")
        val b = AdbKey.loadOrCreate(f, "x@y")
        assertEquals(a.publicKey, b.publicKey)
        dir.deleteRecursively()
    }

    @Test fun hkdfMatchesRfc5869TestCase3() {
        val okm = AdbPairing.hkdf(ByteArray(22) { 0x0b }, ByteArray(0), 42)
        assertArrayEquals(Spake2.hex("8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8"), okm)
    }

    /** adbd's side of the exchange (pairing_connection.cpp as the server, Bob). */
    private fun fakePairingServer(input: DataInputStream, output: java.io.OutputStream, password: ByteArray): ByteArray? {
        val spake = Spake2(Spake2.Role.Bob, AdbPairing.SERVER_NAME, AdbPairing.CLIENT_NAME)
        val theirMsg = AdbPairing.readPacket(input, AdbPairing.TYPE_SPAKE2_MSG)
        AdbPairing.writePacket(output, AdbPairing.TYPE_SPAKE2_MSG, spake.generateMessage(password))
        val cipher = PairingCipher(spake.processMessage(theirMsg))
        val peer = runCatching { cipher.decrypt(AdbPairing.readPacket(input, AdbPairing.TYPE_PEER_INFO)) }.getOrNull()
        if (peer == null) { output.close(); return null } // wrong code: adbd hangs up
        AdbPairing.writePacket(output, AdbPairing.TYPE_PEER_INFO, cipher.encrypt(AdbPairing.peerInfo("SERVERKEY adbd@phone")))
        return peer
    }

    private fun pairOverPipes(clientCode: String, serverCode: String): Pair<Throwable?, ByteArray?> {
        val exported = ByteArray(64) { (it * 3).toByte() }
        val c2s = PipedOutputStream(); val s2c = PipedOutputStream()
        val sIn = DataInputStream(PipedInputStream(c2s, 1 shl 16)); val cIn = DataInputStream(PipedInputStream(s2c, 1 shl 16))
        var got: ByteArray? = null
        val server = thread { got = runCatching { fakePairingServer(sIn, s2c, serverCode.toByteArray() + exported) }.getOrNull() }
        val err = runCatching { AdbPairing(key) { _, _, _ -> exported }.run(cIn, c2s, clientCode.toByteArray() + exported) }.exceptionOrNull()
        server.join(10_000)
        return err to got
    }

    @Test fun pairingSendsOurKeyEncryptedAndAcceptsTheDevicesReply() {
        val (err, peer) = pairOverPipes("482913", "482913")
        assertEquals(null, err)
        assertEquals(8192, peer!!.size)
        assertEquals(0, peer[0].toInt())
        val end = (1 until peer.size).first { peer[it] == 0.toByte() }
        assertEquals(key.adbPublicKey, String(peer, 1, end - 1))
    }

    @Test fun aWrongCodeIsReportedAsSuch() {
        val (err, peer) = pairOverPipes("000000", "482913")
        assertEquals(null, peer)
        assertTrue(err.toString(), err is AdbPairingException && err.kind == AdbPairingException.Kind.WrongCode)
    }

    @Test fun messagesRoundTripAndRejectBadMagic() {
        val m = AdbMessage(AdbMessage.OPEN, 7, 0, "shell,v2,raw:id\u0000".toByteArray())
        val bytes = m.encode()
        assertEquals("OPEN", AdbMessage.name(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).int))
        val back = AdbMessage.read(DataInputStream(bytes.inputStream()))
        assertEquals(AdbMessage.OPEN, back.command); assertEquals(7, back.arg0); assertArrayEquals(m.data, back.data)
        bytes[20] = (bytes[20] + 1).toByte()
        try { AdbMessage.read(DataInputStream(bytes.inputStream())); fail() } catch (e: java.io.IOException) { assertTrue(e.message!!, "header" in e.message!!) }
    }

    @Test fun shellPacketsAreReassembledAcrossWriteBoundaries() {
        val stream = ShellPacket.encode(ShellPacket.STDOUT, "héllo ".toByteArray()) + ShellPacket.encode(ShellPacket.STDERR, "err".toByteArray()) +
            ShellPacket.encode(ShellPacket.EXIT, byteArrayOf(3))
        val got = mutableListOf<Pair<Int, String>>()
        val d = ShellPacket.Decoder()
        stream.toList().chunked(4).forEach { c -> d.feed(c.toByteArray()) { id, data -> got += id to if (id == ShellPacket.EXIT) "${data[0]}" else String(data) } }
        assertEquals(listOf(1 to "héllo ", 2 to "err", 3 to "3"), got)
    }

    @Test fun bannerFeatures() {
        assertEquals(setOf("shell_v2", "cmd", "stat_v2"), AdbConnection.parseFeatures("device::ro.product.name=x;ro.product.model=y;features=shell_v2,cmd,stat_v2"))
        assertEquals(emptySet<String>(), AdbConnection.parseFeatures("device::"))
    }
}
