package io.github.jackwener.dsh.core.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class RemoteLaunchTest {
    @Test fun `the exec command carries no value and no quote a login shell could read differently`() {
        val cmd = RemoteLaunch.command
        assertTrue(cmd.startsWith("sh -c '") && cmd.endsWith("'"))
        val script = cmd.removePrefix("sh -c '").removeSuffix("'")
        assertFalse('\'' in script)
        assertFalse("\\\\" in script)
        assertTrue(script.contains("exec \"\$@\" --profile sdk"))
    }

    @Test fun `preamble is NAME=value lines ending with an empty line, launcher variables first`() {
        val p = RemoteLaunch.preamble("h1", " ~/proj ", "dsh", listOf("DEEPSEEK_API_KEY" to "sk-1", "FOO" to "a b"))
        assertEquals("DSH_REMOTE_ID=h1\nDSH_REMOTE_WORKDIR=~/proj\nDSH_REMOTE_CMD=dsh\nDEEPSEEK_API_KEY=sk-1\nFOO=a b\n\n", p)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a value with a line break is refused, it would end the preamble`() {
        RemoteLaunch.preamble("h1", "", "dsh", listOf("X" to "a\nb"))
    }

    @Test fun `handshake skips a login banner and keeps the bytes after it`() {
        val bytes = "Welcome to Ubuntu\nlast login…\nDSH-REMOTE-OK /home/me/项目\n{\"jsonrpc\":\"2.0\"}\n".toByteArray()
        val input = ByteArrayInputStream(bytes)
        assertEquals(RemoteLaunch.Handshake.Ready("/home/me/项目"), RemoteLaunch.awaitHandshake(input))
        assertEquals("{\"jsonrpc\":\"2.0\"}\n", String(input.readBytes()))
    }

    @Test fun `failures and end of stream`() {
        assertEquals(RemoteLaunch.Handshake.Failed("nodsh", "dsh"), RemoteLaunch.awaitHandshake(ByteArrayInputStream("DSH-REMOTE-ERR nodsh dsh\n".toByteArray())))
        assertEquals(RemoteLaunch.Handshake.Failed("workdir", "/x y"), RemoteLaunch.parse("DSH-REMOTE-ERR workdir /x y"))
        assertNull(RemoteLaunch.awaitHandshake(ByteArrayInputStream("bash: dsh: command not found\n".toByteArray())))
    }

    @Test fun `environment text`() {
        val env = RemoteEnv.parse("# proxy\nexport HTTPS_PROXY=http://127.0.0.1:7890\n\nNAME = \"a b\"\nEMPTY=\n").getOrThrow()
        assertEquals(listOf("HTTPS_PROXY" to "http://127.0.0.1:7890", "NAME" to "a b", "EMPTY" to ""), env)
        assertTrue(RemoteEnv.parse("1BAD=x").isFailure)
        assertTrue(RemoteEnv.parse("no equals").isFailure)
    }

    @Test fun `addresses`() {
        assertTrue(RemoteAddress.valid("192.168.1.10"))
        assertTrue(RemoteAddress.valid("home.example.com"))
        assertTrue(RemoteAddress.valid("[fe80::1%wlan0]"))
        assertTrue(RemoteAddress.valid("2001:db8::1"))
        assertFalse(RemoteAddress.valid("ssh://host"))
        assertFalse(RemoteAddress.valid("a b"))
        assertFalse(RemoteAddress.valid(""))
        assertEquals("2001:db8::1", RemoteAddress.normalize("[2001:db8::1]"))
        assertEquals("[2001:db8::1]", RemoteAddress.display("2001:db8::1"))
        assertEquals("me@[2001:db8::1]:2222", RemoteHost("i", "n", "2001:db8::1", 2222, "me").address)
        assertEquals("me@nas", RemoteHost("i", "n", "nas", 22, "me").address)
    }

    @Test fun `SshTarget never prints its secrets`() {
        val t = SshTarget("h", 22, "me", password = "hunter2", privateKey = "KEY", passphrase = "pp")
        assertFalse(t.toString().contains("hunter2") || t.toString().contains("KEY") || t.toString().contains("pp"))
        val l = RemoteRuntime.Launch("i", "", "dsh", "p", "m", listOf("DEEPSEEK_API_KEY" to "sk-secret"))
        assertFalse(l.toString().contains("sk-secret"))
    }
}
