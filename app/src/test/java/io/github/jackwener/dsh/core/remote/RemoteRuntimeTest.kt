package io.github.jackwener.dsh.core.remote

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * Remote mode end to end on the JVM (#51): MINA's SSH client against an in-process SSH server that runs
 * exec requests like OpenSSH does (`/bin/sh -c <command>` as the user), the real launch script, and a fake
 * `dsh` (a POSIX sh script speaking the SDK JSON-RPC subset the app uses). Covers host key TOFU, password
 * and key authentication, the stdin preamble (cwd, environment), the JSON-RPC handshake, notifications,
 * takeover of a stale remote process, and the classified failures.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RemoteRuntimeTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: SshServer
    private lateinit var home: File
    private lateinit var bin: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var acceptedKey: java.security.PublicKey? = null

    @Before fun setUp() {
        assumeTrue(File("/bin/sh").exists())
        home = tmp.newFolder("home")
        File(home, "proj dir").mkdirs()
        bin = tmp.newFolder("bin")
        File(bin, "dsh").apply { writeText(FAKE_DSH); setExecutable(true) }
        server = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"
            port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider(tmp.root.toPath().resolve("hostkey.ser"))
            passwordAuthenticator = org.apache.sshd.server.auth.password.PasswordAuthenticator { u, p, _ -> u == "me" && p == "secret pw" }
            publickeyAuthenticator = org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator { u, k, _ ->
                u == "me" && acceptedKey?.let { org.apache.sshd.common.config.keys.KeyUtils.compareKeys(it, k) } == true
            }
            commandFactory = org.apache.sshd.server.command.CommandFactory { _, cmd -> ShCommand(cmd, home, bin) }
        }
        server.start()
    }

    @After fun tearDown() {
        scope.cancel()
        if (::server.isInitialized) server.stop(true)
    }

    private fun hostKeyLine(): String = runBlocking {
        try { start(SshTarget("127.0.0.1", server.port, "me", password = "secret pw")); error("expected an unknown host key") }
        catch (e: RemoteException) { assertEquals(RemoteException.Kind.HostKeyUnknown, e.kind); e.presentedKey!! }
    }

    private val notifications = Collections.synchronizedList(mutableListOf<Pair<String, JsonObject>>())
    private val closed = CompletableDeferred<Unit>()

    private suspend fun start(target: SshTarget, launch: RemoteRuntime.Launch = launch()): RemoteRuntime =
        withTimeout(60_000) {
            RemoteRuntime.start(target, launch, scope,
                onRequest = { m, _ -> if (m == "approval/request") buildJsonObject { put("outcome", "allowed-once") } else error(m) },
                onNotification = { m, p -> notifications += m to p },
                onClosed = { closed.complete(Unit) })
        }

    private fun launch(workdir: String = "~/proj dir", command: String = "dsh", env: List<Pair<String, String>> = listOf("DEEPSEEK_API_KEY" to "sk-test", "FOO" to "a b=c")) =
        RemoteRuntime.Launch("host1", workdir, command, "deepseek-official", "deepseek-flash", env)

    @Test fun unknownHostKeyIsReportedWithItsFingerprintAndNothingRuns() = runBlocking {
        val e = try { start(SshTarget("127.0.0.1", server.port, "me", password = "secret pw")); null } catch (e: RemoteException) { e }
        assertEquals(RemoteException.Kind.HostKeyUnknown, e!!.kind)
        assertTrue(e.presentedFingerprint!!.startsWith("SHA256:"))
        assertEquals(e.presentedFingerprint, HostKeys.fingerprint(HostKeys.parse(e.presentedKey!!)!!))
        assertTrue(File(home, ".cache/dsh-android").listFiles().isNullOrEmpty())
    }

    @Test fun passwordLoginRunsDshInTheWorkdirWithTheEnvironment() = runBlocking {
        val rt = start(SshTarget("127.0.0.1", server.port, "me", password = "secret pw", trustedHostKey = hostKeyLine()))
        assertEquals(File(home, "proj dir").canonicalPath, File(rt.cwd).canonicalPath)
        val probe = rt.peer.request("test/env").jsonObject
        assertEquals("sk-test", probe["key"]!!.jsonPrimitive.content)
        assertEquals("a b=c", probe["foo"]!!.jsonPrimitive.content)
        // The launcher's own variables are not passed on.
        assertEquals("", probe["cmd"]!!.jsonPrimitive.content)
        assertEquals(rt.cwd, probe["init_cwd"]!!.jsonPrimitive.content)
        // A prompt: status running, an event, status idle, in wire order.
        rt.peer.request("session/prompt", buildJsonObject { put("sessionId", "s1") })
        withTimeout(10_000) { while (notifications.size < 3) kotlinx.coroutines.delay(20) }
        assertEquals(listOf("session.status", "session.event", "session.status"), notifications.map { it.first })
        assertEquals(JsonPrimitive("idle"), notifications[2].second["status"])
        rt.peer.request("shutdown")
        withTimeout(10_000) { closed.await() }
        rt.close()
    }

    @Test fun changedHostKeyIsRefused() = runBlocking {
        val other = SimpleGeneratorHostKeyProvider(tmp.root.toPath().resolve("other.ser")).loadKeys(null).first().public
        val e = try { start(SshTarget("127.0.0.1", server.port, "me", password = "secret pw", trustedHostKey = HostKeys.line(other))); null } catch (e: RemoteException) { e }
        assertEquals(RemoteException.Kind.HostKeyChanged, e!!.kind)
        assertNotNull(e.presentedFingerprint)
    }

    @Test fun wrongPasswordIsAnAuthFailure() = runBlocking {
        val e = try { start(SshTarget("127.0.0.1", server.port, "me", password = "nope", trustedHostKey = hostKeyLine())); null } catch (e: RemoteException) { e }
        assertEquals(RemoteException.Kind.Auth, e!!.kind)
    }

    @Test fun ed25519KeyWithPassphrase() = runBlocking {
        val key = sshKeygen("ed25519", "pass phrase") ?: return@runBlocking
        acceptedKey = HostKeys.parse(File(key.path + ".pub").readText().trim())
        val trusted = hostKeyLine()
        val rt = start(SshTarget("127.0.0.1", server.port, "me", privateKey = key.readText(), passphrase = "pass phrase", trustedHostKey = trusted))
        assertTrue(rt.isOpen)
        rt.close()
        val e = try { start(SshTarget("127.0.0.1", server.port, "me", privateKey = key.readText(), passphrase = "wrong", trustedHostKey = trusted)); null } catch (e: RemoteException) { e }
        assertEquals(RemoteException.Kind.KeyPassphrase, e!!.kind)
    }

    @Test fun rsaKeyWithoutPassphrase() = runBlocking {
        val key = sshKeygen("rsa", "") ?: return@runBlocking
        acceptedKey = HostKeys.parse(File(key.path + ".pub").readText().trim())
        start(SshTarget("127.0.0.1", server.port, "me", privateKey = key.readText(), trustedHostKey = hostKeyLine())).close()
    }

    @Test fun missingWorkdirAndMissingDshAreClassified() = runBlocking {
        val target = SshTarget("127.0.0.1", server.port, "me", password = "secret pw", trustedHostKey = hostKeyLine())
        val w = try { start(target, launch(workdir = "/nonexistent/dir")); null } catch (e: RemoteException) { e }
        assertEquals(RemoteException.Kind.Workdir, w!!.kind)
        val d = try { start(target, launch(command = "dsh-not-installed")); null } catch (e: RemoteException) { e }
        assertEquals(RemoteException.Kind.NoDsh, d!!.kind)
        assertEquals("dsh-not-installed", d.message)
    }

    @Test fun absoluteCommandPathWithSpacesWorks() = runBlocking {
        val dir = File(tmp.root, "my tools").apply { mkdirs() }
        File(dir, "dsh").apply { writeText(FAKE_DSH); setExecutable(true) }
        start(SshTarget("127.0.0.1", server.port, "me", password = "secret pw", trustedHostKey = hostKeyLine()), launch(command = File(dir, "dsh").path)).close()
    }

    @Test fun aDshThatDiesWhileBootingReportsItsStderr() = runBlocking {
        File(bin, "broken").apply { writeText("#!/bin/sh\necho 'Error: Node.js 18 is too old' >&2\nexit 1\n"); setExecutable(true) }
        val target = SshTarget("127.0.0.1", server.port, "me", password = "secret pw", trustedHostKey = hostKeyLine())
        val e = try { start(target, launch(command = "broken")); null } catch (e: RemoteException) { e }
        assertEquals(RemoteException.Kind.DshExited, e!!.kind)
        assertTrue(e.message, e.message!!.contains("too old"))
    }

    @Test fun aNewConnectionTakesOverTheStaleProcessOfTheSameHost() = runBlocking {
        val target = SshTarget("127.0.0.1", server.port, "me", password = "secret pw", trustedHostKey = hostKeyLine())
        val first = start(target)
        val firstPid = File(home, ".cache/dsh-android/host1.pid").readText().trim()
        // The first link stays open (the server never noticed it died); the second must not wait for it.
        val second = RemoteRuntime.start(target, launch(), scope, { _, _ -> JsonObject(emptyMap()) }, { _, _ -> }, {})
        withTimeout(15_000) { closed.await() }
        assertTrue(File(home, ".cache/dsh-android/host1.pid").readText().trim() != firstPid)
        assertTrue(second.isOpen)
        first.close(); second.close()
    }

    private fun sshKeygen(type: String, passphrase: String): File? {
        val keygen = listOf("/usr/bin/ssh-keygen", "/bin/ssh-keygen").firstOrNull { File(it).canExecute() }
        assumeTrue("ssh-keygen not available", keygen != null)
        val f = File(tmp.root, "id_$type")
        val p = ProcessBuilder(keygen, "-q", "-t", type, "-N", passphrase, "-f", f.path).redirectErrorStream(true).start()
        p.waitFor(30, TimeUnit.SECONDS)
        return f.takeIf { it.exists() }
    }

    /** An exec request the way OpenSSH's sshd runs it: the user's shell with `-c`, HOME set, the channel's streams. */
    private class ShCommand(private val command: String, private val home: File, private val bin: File) : Command {
        private lateinit var input: InputStream
        private lateinit var out: OutputStream
        private lateinit var err: OutputStream
        private lateinit var exit: ExitCallback
        private var process: Process? = null
        override fun setInputStream(`in`: InputStream) { input = `in` }
        override fun setOutputStream(o: OutputStream) { out = o }
        override fun setErrorStream(e: OutputStream) { err = e }
        override fun setExitCallback(callback: ExitCallback) { exit = callback }
        override fun start(channel: ChannelSession, env: Environment) {
            val pb = ProcessBuilder("/bin/sh", "-c", command)
            pb.environment().apply { clear(); put("HOME", home.path); put("PATH", "${bin.path}:/usr/bin:/bin"); put("SHELL", "/bin/sh") }
            val p = pb.start().also { process = it }
            pump(input, p.outputStream, close = true)
            pump(p.inputStream, out)
            pump(p.errorStream, err)
            Thread { val code = p.waitFor(); Thread.sleep(200); exit.onExit(code) }.apply { isDaemon = true }.start()
        }
        override fun destroy(channel: ChannelSession) { process?.destroy() }

        private fun pump(from: InputStream, to: OutputStream, close: Boolean = false) = Thread {
            val buf = ByteArray(8192)
            try {
                while (true) { val n = from.read(buf); if (n < 0) break; to.write(buf, 0, n); to.flush() }
            } catch (_: Exception) {
            } finally { if (close) runCatching { to.close() } }
        }.apply { isDaemon = true }.start()
    }

    companion object {
        /** The SDK subset the app uses, in POSIX sh: initialize, session/prompt (status + event), shutdown; test/env reports what it got. */
        val FAKE_DSH = """
            #!/bin/sh
            [ "${'$'}1 ${'$'}2" = "--profile sdk" ] || { echo "bad args: ${'$'}*" >&2; exit 2; }
            cwd=""
            while IFS= read -r line; do
              id=${'$'}(printf '%s' "${'$'}line" | sed -n 's/.*"id":\([0-9][0-9]*\).*/\1/p')
              case ${'$'}line in
                *'"method":"initialize"'*)
                  cwd=${'$'}(printf '%s' "${'$'}line" | sed -n 's/.*"cwd":"\([^"]*\)".*/\1/p')
                  printf '{"jsonrpc":"2.0","id":%s,"result":{"serverInfo":{"name":"fake","version":"0"}}}\n' "${'$'}id" ;;
                *'"method":"test/env"'*)
                  printf '{"jsonrpc":"2.0","id":%s,"result":{"key":"%s","foo":"%s","cmd":"%s","init_cwd":"%s"}}\n' "${'$'}id" "${'$'}DEEPSEEK_API_KEY" "${'$'}FOO" "${'$'}DSH_REMOTE_CMD" "${'$'}cwd" ;;
                *'"method":"session/prompt"'*)
                  printf '{"jsonrpc":"2.0","method":"session.status","params":{"sessionId":"s1","status":"running"}}\n'
                  printf '{"jsonrpc":"2.0","id":%s,"result":{"messageId":"m1"}}\n' "${'$'}id"
                  printf '{"jsonrpc":"2.0","method":"session.event","params":{"sessionId":"s1","event":{"seq":0,"type":"user/message","time":1,"data":{}}}}\n'
                  printf '{"jsonrpc":"2.0","method":"session.status","params":{"sessionId":"s1","status":"idle"}}\n' ;;
                *'"method":"shutdown"'*)
                  printf '{"jsonrpc":"2.0","id":%s,"result":{}}\n' "${'$'}id"; exit 0 ;;
                *)
                  [ -n "${'$'}id" ] && printf '{"jsonrpc":"2.0","id":%s,"error":{"code":-32601,"message":"unknown DeepSeek Harness SDK runtime method"}}\n' "${'$'}id" ;;
              esac
            done
        """.trimIndent() + "\n"
    }
}
