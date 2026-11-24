package io.github.jackwener.dsh.core.platform.adb

import io.github.jackwener.dsh.core.platform.ShellResult
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLSocket

/** One adb transport message (adb.h `amessage` + payload). */
class AdbMessage(val command: Int, val arg0: Int, val arg1: Int, val data: ByteArray = EMPTY) {
    fun encode(): ByteArray = ByteBuffer.allocate(HEADER + data.size).order(ByteOrder.LITTLE_ENDIAN).apply {
        putInt(command); putInt(arg0); putInt(arg1); putInt(data.size)
        putInt(data.fold(0) { s, b -> s + (b.toInt() and 0xff) }) // checksum; adbd ignores it from A_VERSION 0x01000001 on
        putInt(command.inv())
        put(data)
    }.array()

    override fun toString() = "${name(command)}($arg0, $arg1, ${data.size}b)"

    companion object {
        const val HEADER = 24
        val EMPTY = ByteArray(0)
        const val CNXN = 0x4e584e43
        const val AUTH = 0x48545541
        const val OPEN = 0x4e45504f
        const val OKAY = 0x59414b4f
        const val CLSE = 0x45534c43
        const val WRTE = 0x45545257
        const val STLS = 0x534c5453
        const val VERSION = 0x01000001
        const val STLS_VERSION = 0x01000000
        const val MAX_PAYLOAD = 1024 * 1024
        const val AUTH_TOKEN = 1
        const val AUTH_SIGNATURE = 2
        const val AUTH_RSAPUBLICKEY = 3

        fun name(cmd: Int) = String(ByteArray(4) { (cmd ushr (8 * it)).toByte() }, Charsets.US_ASCII)

        /** Reads one message; throws [EOFException] when the peer closed, [IOException] on a corrupt header. */
        fun read(input: DataInputStream, maxData: Int = MAX_PAYLOAD): AdbMessage {
            val h = ByteArray(HEADER)
            input.readFully(h)
            val b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
            val cmd = b.int; val a0 = b.int; val a1 = b.int; val len = b.int; b.int; val magic = b.int
            if (magic != cmd.inv()) throw IOException("bad adb message header")
            if (len < 0 || len > maxData) throw IOException("adb message too large ($len bytes)")
            val data = if (len == 0) EMPTY else ByteArray(len).also { input.readFully(it) }
            return AdbMessage(cmd, a0, a1, data)
        }
    }
}

/** The connection was already closed, so the command was never sent (safe to retry on a new one). */
class AdbClosedException : IOException("adb connection closed")

/** adbd refused us: the key is not (or no longer) paired, or it wants the on-screen "allow" dialog. */
class AdbNotPairedException(message: String) : IOException(message)

/**
 * A client connection to adbd (the wireless-debugging TLS port): CNXN → STLS → TLS 1.3 with [AdbKey]
 * as the client certificate → CNXN, then `shell,v2,raw:` streams. A reader thread demultiplexes
 * streams; [onClosed] fires once when the transport ends. Non-TLS adbd (AUTH tokens) is handled too.
 */
class AdbConnection private constructor(private var socket: Socket, private val key: AdbKey) : Closeable {
    // Unbuffered until TLS is up: nothing past the STLS message may be read ahead of the handshake.
    private var input = DataInputStream(socket.getInputStream())
    private var output: OutputStream = socket.getOutputStream()
    private val writeLock = Any()
    private val streams = ConcurrentHashMap<Int, LinkedBlockingQueue<AdbMessage>>()
    private val nextId = AtomicInteger(1)
    @Volatile private var closed = false
    @Volatile var onClosed: ((Throwable?) -> Unit)? = null
    /** The device banner's features (shell_v2, cmd, …). */
    var features: Set<String> = emptySet(); private set
    var maxData = AdbMessage.MAX_PAYLOAD; private set
    val isOpen get() = !closed

    private fun send(m: AdbMessage) = synchronized(writeLock) { output.write(m.encode()); output.flush() }

    private fun handshake(timeoutMs: Int) {
        try { handshakeSteps(timeoutMs) } catch (e: IOException) {
            // A failure once TLS started (alert, reset, EOF; in TLS 1.3 the client certificate is
            // judged after our side finished) means adbd does not know this key.
            if (e is AdbNotPairedException || !tlsStarted) throw e
            throw AdbNotPairedException("adbd rejected DSH's key (not paired, or the pairing was removed): ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private var tlsStarted = false

    private fun handshakeSteps(timeoutMs: Int) {
        socket.soTimeout = timeoutMs
        send(AdbMessage(AdbMessage.CNXN, AdbMessage.VERSION, AdbMessage.MAX_PAYLOAD, "host::features=shell_v2,cmd".toByteArray()))
        var signed = false
        while (true) {
            val m = AdbMessage.read(input)
            when (m.command) {
                AdbMessage.STLS -> {
                    send(AdbMessage(AdbMessage.STLS, AdbMessage.STLS_VERSION, 0))
                    val tls = key.sslContext().socketFactory.createSocket(socket, socket.inetAddress.hostAddress, socket.port, true) as SSLSocket
                    tlsStarted = true
                    tls.startHandshake()
                    socket = tls
                    input = DataInputStream(tls.inputStream.buffered())
                    output = tls.outputStream
                }
                AdbMessage.AUTH -> {
                    if (m.arg0 != AdbMessage.AUTH_TOKEN) continue
                    // Plain adbd (adb tcpip): one signature try; a second token means the key is unknown.
                    if (signed) throw AdbNotPairedException("adbd does not know DSH's key")
                    send(AdbMessage(AdbMessage.AUTH, AdbMessage.AUTH_SIGNATURE, 0, key.signToken(m.data)))
                    signed = true
                }
                AdbMessage.CNXN -> {
                    maxData = m.arg1.coerceIn(4096, AdbMessage.MAX_PAYLOAD)
                    features = parseFeatures(String(m.data, Charsets.UTF_8))
                    socket.soTimeout = 0
                    return
                }
                else -> {}
            }
        }
    }

    private fun startReader() = Thread({
        var error: Throwable? = null
        try {
            while (!closed) {
                val m = AdbMessage.read(input, maxData.coerceAtLeast(AdbMessage.MAX_PAYLOAD))
                when (m.command) {
                    AdbMessage.OKAY, AdbMessage.WRTE, AdbMessage.CLSE -> {
                        val q = streams[m.arg1]
                        if (q != null) q.put(m)
                        // A stream we gave up on (timed out before adbd answered): tell adbd to end it.
                        else if (m.command != AdbMessage.CLSE) runCatching { send(AdbMessage(AdbMessage.CLSE, 0, m.arg0)) }
                    }
                    else -> {}
                }
            }
        } catch (t: Throwable) { error = t }
        shutdown(if (closed) null else error)
    }, "adb-reader").apply { isDaemon = true; start() }

    /**
     * Runs [command] through `shell,v2,raw:` (separate stdout/stderr and the exit code, no PTY) and
     * returns the exit code with stdout+stderr in arrival order, cut at [outputCap] characters. After
     * [timeoutMs] the stream is closed (adbd ends the process) and the code is 124, like `timeout`.
     */
    fun exec(command: String, timeoutMs: Long, outputCap: Int = OUTPUT_CAP): ShellResult {
        if (closed) throw AdbClosedException()
        val v2 = SHELL_V2 in features
        val local = nextId.getAndIncrement()
        val queue = LinkedBlockingQueue<AdbMessage>()
        streams[local] = queue
        try {
            if (closed) throw AdbClosedException()
            send(AdbMessage(AdbMessage.OPEN, local, 0, ((if (v2) "shell,v2,raw:" else "shell:") + command + "\u0000").toByteArray()))
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            fun next(): AdbMessage? {
                val left = deadline - System.nanoTime()
                if (left <= 0) return null
                val m = queue.poll(left, TimeUnit.NANOSECONDS) ?: return null
                if (m === LOST) throw IOException("adb connection lost")
                return m
            }
            val out = java.io.ByteArrayOutputStream()
            // Bytes, decoded once at the end: a UTF-8 character may span two WRTE packets.
            fun result(code: Int) = ShellResult(code, out.toString(Charsets.UTF_8.name()).take(outputCap), VIA)
            fun append(b: ByteArray) { if (out.size() < outputCap * 4) out.write(b, 0, minOf(b.size, outputCap * 4 - out.size())) }
            val first = next() ?: return result(TIMED_OUT)
            if (first.command == AdbMessage.CLSE) throw IOException("adbd refused the shell service")
            val remote = first.arg0
            // No stdin: close it so commands that read it end instead of waiting for the timeout.
            if (v2) send(AdbMessage(AdbMessage.WRTE, local, remote, ShellPacket.encode(ShellPacket.CLOSE_STDIN, AdbMessage.EMPTY)))
            val decoder = ShellPacket.Decoder()
            var exit: Int? = null
            while (true) {
                val m = next() ?: run {
                    runCatching { send(AdbMessage(AdbMessage.CLSE, local, remote)) }
                    return result(TIMED_OUT)
                }
                when (m.command) {
                    AdbMessage.WRTE -> {
                        send(AdbMessage(AdbMessage.OKAY, local, remote))
                        if (!v2) append(m.data)
                        else decoder.feed(m.data) { id, data ->
                            when (id) {
                                ShellPacket.STDOUT, ShellPacket.STDERR -> append(data)
                                ShellPacket.EXIT -> exit = if (data.isNotEmpty()) data[0].toInt() and 0xff else 0
                            }
                        }
                    }
                    AdbMessage.CLSE -> return result(exit ?: if (v2) -1 else 0)
                }
            }
        } finally {
            streams.remove(local)
        }
    }

    private fun shutdown(error: Throwable?) {
        val first = synchronized(this) { val was = closed; closed = true; !was }
        runCatching { socket.close() }
        streams.values.forEach { it.offer(LOST) }
        if (first) onClosed?.invoke(error)
    }

    override fun close() { if (!closed) shutdown(null) }

    companion object {
        const val SHELL_V2 = "shell_v2"
        const val OUTPUT_CAP = 64_000
        const val TIMED_OUT = 124
        const val VIA = "ADB"
        private val LOST = AdbMessage(0, 0, 0)

        /** Connects to adbd on [host]:[port] and completes the handshake within [timeoutMs]. */
        fun open(host: String, port: Int, key: AdbKey, timeoutMs: Int = 10_000): AdbConnection {
            val s = Socket()
            try {
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(host, port), timeoutMs)
                return AdbConnection(s, key).apply { handshake(timeoutMs); startReader() }
            } catch (e: Throwable) {
                runCatching { s.close() }
                throw e
            }
        }

        /** "device::ro.product.name=…;features=shell_v2,cmd,…" → its feature names. */
        fun parseFeatures(banner: String): Set<String> = banner.substringAfter("::", "").split(';')
            .firstOrNull { it.startsWith("features=") }?.removePrefix("features=")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
            ?: emptySet()
    }
}

/** adb's shell protocol v2 packets inside WRTE payloads: 1-byte id, 4-byte little-endian length, data. */
object ShellPacket {
    const val STDIN = 0
    const val STDOUT = 1
    const val STDERR = 2
    const val EXIT = 3
    const val CLOSE_STDIN = 4

    fun encode(id: Int, data: ByteArray): ByteArray =
        ByteBuffer.allocate(5 + data.size).order(ByteOrder.LITTLE_ENDIAN).put(id.toByte()).putInt(data.size).put(data).array()

    /** Reassembles packets that WRTE boundaries may split anywhere. */
    class Decoder {
        private var buf = ByteArray(0)
        fun feed(chunk: ByteArray, onPacket: (Int, ByteArray) -> Unit) {
            buf += chunk
            var at = 0
            while (buf.size - at >= 5) {
                val len = ByteBuffer.wrap(buf, at + 1, 4).order(ByteOrder.LITTLE_ENDIAN).int
                if (len < 0 || buf.size - at - 5 < len) break
                onPacket(buf[at].toInt() and 0xff, buf.copyOfRange(at + 5, at + 5 + len))
                at += 5 + len
            }
            buf = buf.copyOfRange(at, buf.size)
        }
    }
}
