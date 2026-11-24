package io.github.jackwener.dsh.core.platform.adb

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

/**
 * #71: the client against a fake wireless-debugging adbd on localhost: plain CNXN, STLS upgrade to
 * TLS 1.3 with the paired key as the client certificate, then `shell,v2,raw:` streams.
 */
class AdbConnectionTest {
    private val serverKey = AdbKey.generate("adbd@phone")
    private val client = AdbKey.generate("DSH@test")
    private val server = ServerSocket(0, 5, InetAddress.getLoopbackAddress())
    private val seen = LinkedBlockingQueue<AdbMessage>()

    @After fun close() { server.close() }

    /** adbd's TLS side: only [paired] client keys pass the handshake (adbd_tls_verify_cert). */
    private fun serverTls(paired: java.security.PublicKey): SSLContext {
        val ks = KeyStore.getInstance("PKCS12").apply { load(null); setKeyEntry("k", serverKey.privateKey, CharArray(0), arrayOf(serverKey.certificate)) }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, CharArray(0)) }
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String?) {
                if (chain[0].publicKey != paired) throw CertificateException("unknown key")
            }
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        return SSLContext.getInstance("TLSv1.3").apply { init(kmf.keyManagers, arrayOf(tm), null) }
    }

    /** Accepts one client and runs [shell] for each OPEN (gets the command, returns packets to send). */
    private fun fakeAdbd(paired: java.security.PublicKey, shell: (String, (AdbMessage) -> Unit, Int, Int) -> Unit) = thread(isDaemon = true) {
        runCatching {
            val raw: Socket = server.accept()
            val pin = DataInputStream(raw.getInputStream())
            val cnxn = AdbMessage.read(pin); seen.put(cnxn)
            raw.getOutputStream().write(AdbMessage(AdbMessage.STLS, AdbMessage.STLS_VERSION, 0).encode())
            seen.put(AdbMessage.read(pin))
            val tls = serverTls(paired).socketFactory.createSocket(raw, null, raw.port, true) as SSLSocket
            tls.useClientMode = false; tls.needClientAuth = true
            tls.startHandshake()
            val input = DataInputStream(tls.inputStream); val out: OutputStream = tls.outputStream
            val send = { m: AdbMessage -> synchronized(out) { out.write(m.encode()); out.flush() } }
            send(AdbMessage(AdbMessage.CNXN, AdbMessage.VERSION, 256 * 1024, "device::ro.product.name=x;features=shell_v2,cmd".toByteArray()))
            var nextId = 100
            while (true) {
                val m = AdbMessage.read(input); seen.put(m)
                if (m.command == AdbMessage.OPEN) {
                    val id = nextId++
                    send(AdbMessage(AdbMessage.OKAY, id, m.arg0))
                    thread(isDaemon = true) { shell(String(m.data).trimEnd('\u0000'), send, id, m.arg0) }
                }
            }
        }
    }

    @Test fun shellV2ReturnsExitCodeAndOutputOverTls() {
        fakeAdbd(client.publicKey) { cmd, send, id, peer ->
            assertEquals("shell,v2,raw:id -u", cmd)
            // stdout split across two WRTEs, then stderr, then the exit packet, then CLSE.
            val all = ShellPacket.encode(ShellPacket.STDOUT, "2000\n".toByteArray()) + ShellPacket.encode(ShellPacket.STDERR, "warn\n".toByteArray()) +
                ShellPacket.encode(ShellPacket.EXIT, byteArrayOf(0))
            send(AdbMessage(AdbMessage.WRTE, id, peer, all.copyOfRange(0, 7)))
            send(AdbMessage(AdbMessage.WRTE, id, peer, all.copyOfRange(7, all.size)))
            send(AdbMessage(AdbMessage.CLSE, id, peer))
        }
        AdbConnection.open("127.0.0.1", server.localPort, client).use { c ->
            assertEquals(setOf("shell_v2", "cmd"), c.features)
            val r = c.exec("id -u", 5_000)
            assertEquals(0, r.code)
            assertEquals("2000\nwarn\n", r.output)
            assertEquals("ADB", r.via)
        }
        val cnxn = seen.poll(1, TimeUnit.SECONDS)!!
        assertEquals(AdbMessage.CNXN, cnxn.command)
        assertTrue(String(cnxn.data).startsWith("host::"))
        assertEquals(AdbMessage.STLS, seen.poll(1, TimeUnit.SECONDS)!!.command)
    }

    @Test fun aTimedOutCommandIsClosedAndReports124() {
        fakeAdbd(client.publicKey) { _, send, id, peer ->
            send(AdbMessage(AdbMessage.WRTE, id, peer, ShellPacket.encode(ShellPacket.STDOUT, "partial".toByteArray())))
        }
        AdbConnection.open("127.0.0.1", server.localPort, client).use { c ->
            val r = c.exec("logcat", 600)
            assertEquals(124, r.code)
            assertEquals("partial", r.output)
            // The client closed its end so adbd stops the process.
            val deadline = System.currentTimeMillis() + 2_000
            while (System.currentTimeMillis() < deadline) {
                val m = seen.poll(200, TimeUnit.MILLISECONDS) ?: continue
                if (m.command == AdbMessage.CLSE) return
            }
            fail("no CLSE sent")
        }
    }

    @Test fun anUnpairedKeyFailsTheTlsHandshakeAsNotPaired() {
        fakeAdbd(AdbKey.generate("someone@else").publicKey) { _, _, _, _ -> }
        try {
            AdbConnection.open("127.0.0.1", server.localPort, client, timeoutMs = 5_000).close()
            fail("connected with an unpaired key")
        } catch (e: AdbNotPairedException) {
            assertTrue(e.message!!, "not paired" in e.message!!)
        }
    }

    @Test fun theConnectionReportsItsEnd() {
        fakeAdbd(client.publicKey) { _, _, _, _ -> }
        val c = AdbConnection.open("127.0.0.1", server.localPort, client)
        val ended = LinkedBlockingQueue<String>()
        c.onClosed = { ended.put("closed") }
        server.close()
        c.close()
        assertEquals("closed", ended.poll(2, TimeUnit.SECONDS))
        assertTrue(!c.isOpen)
    }
}
