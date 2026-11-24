package io.github.jackwener.dsh.core.remote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import io.github.jackwener.dsh.core.engine.JsonRpcException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Opt-in: a real OpenSSH server and the real dsh (#51). Runs when DSH_REMOTE_E2E_PORT is set, e.g. with an
 * unprivileged `sshd -f <config> -p 22622` and `npm i @deepseek-ai/dsh`:
 *
 *   DSH_REMOTE_E2E_PORT=22622 DSH_REMOTE_E2E_USER=$USER DSH_REMOTE_E2E_KEY=~/k/id_ed25519 \
 *   DSH_REMOTE_E2E_DSH=/path/node_modules/.bin/dsh DSH_REMOTE_E2E_HOME=/tmp/dsh-home ./gradlew testDebugUnitTest --tests '*RemoteRealDshTest*'
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RemoteRealDshTest {
    private fun env(name: String) = System.getenv("DSH_REMOTE_E2E_$name")

    @Test fun realSshdRunsRealDshSdk() = runBlocking {
        val port = env("PORT")?.toIntOrNull()
        assumeTrue("DSH_REMOTE_E2E_PORT not set", port != null)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val host = env("HOST") ?: "127.0.0.1"
            val key = File(env("KEY")!!).readText()
            val launch = RemoteRuntime.Launch("e2e", env("WORKDIR") ?: "~", env("DSH") ?: "dsh", "deepseek-official", "deepseek-flash",
                listOfNotNull(env("HOME")?.let { "DSH_HOME" to it }))
            suspend fun start(trusted: String?) = withTimeout(120_000) {
                RemoteRuntime.start(SshTarget(host, port!!, env("USER")!!, privateKey = key, passphrase = env("PASSPHRASE"), trustedHostKey = trusted),
                    launch, scope, { _, _ -> JsonObject(emptyMap()) }, { _, _ -> }, {})
            }
            val unknown = try { start(null); null } catch (e: RemoteException) { e }
            assertEquals(RemoteException.Kind.HostKeyUnknown, unknown!!.kind)
            println("host key ${unknown.presentedFingerprint}")
            val t0 = System.currentTimeMillis()
            val rt = start(unknown.presentedKey)
            println("connected + dsh initialized in ${System.currentTimeMillis() - t0} ms, cwd ${rt.cwd}")
            val wd = rt.peer.request("session/working-directory/get", buildJsonObject { put("sessionId", "e2e-" + System.currentTimeMillis()) }).jsonObject
            assertEquals(rt.cwd, wd["cwd"]!!.jsonPrimitive.content)
            // The embedded engine's Android extensions do not exist there: the app must not depend on them remotely.
            val ext = try { rt.peer.request("android/session/mode", buildJsonObject { }); null } catch (e: JsonRpcException) { e }
            assertTrue(ext!!.message!!.contains("unknown DeepSeek Harness SDK runtime method"))
            rt.peer.request("shutdown")
            rt.close()
        } finally { scope.cancel() }
    }
}
