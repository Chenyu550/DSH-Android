package io.github.jackwener.dsh.core.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/** #66: the user-supplied Node option channel. */
class EngineNodeOptionsTest {
    @Test fun `a user option reaches the merged NODE_OPTIONS and the argv copy`() {
        val merged = EngineRuntimeEnv.mergeNodeOptions(
            "--max-old-space-size=768",
            EngineRuntimeEnv.RUNTIME_NODE_OPTIONS + EngineRuntimeEnv.parseExtraNodeOptions("  --jitless  "),
        )
        assertEquals("--max-old-space-size=768 --network-family-autoselection-attempt-timeout=1500 --jitless", merged)
    }

    @Test fun `no input adds nothing and keeps the app's own options`() {
        assertEquals(emptyList<String>(), EngineRuntimeEnv.parseExtraNodeOptions(null))
        assertEquals(emptyList<String>(), EngineRuntimeEnv.parseExtraNodeOptions("   \n\t "))
        assertEquals(
            "--max-old-space-size=768 --network-family-autoselection-attempt-timeout=1500",
            EngineRuntimeEnv.mergeNodeOptions("--max-old-space-size=768", EngineRuntimeEnv.RUNTIME_NODE_OPTIONS + EngineRuntimeEnv.parseExtraNodeOptions("")),
        )
    }

    @Test fun `code-loading flags and stray values are refused`() {
        assertEquals(
            listOf("--jitless", "--stack-trace-limit=64"),
            EngineRuntimeEnv.parseExtraNodeOptions(
                "--require=/tmp/x.cjs -r /tmp/y.cjs --eval=1 -e 1 --print=1 -p 1 --import=data:1 --expose-internals --jitless --stack-trace-limit=64",
            ),
        )
    }

    @Test fun `a user value for an app flag replaces it, duplicates collapse`() {
        assertEquals(
            "--max-old-space-size=1536",
            EngineRuntimeEnv.mergeNodeOptions("--max-old-space-size=768", EngineRuntimeEnv.parseExtraNodeOptions("--max-old-space-size=1536")),
        )
        assertEquals(listOf("--jitless"), EngineRuntimeEnv.parseExtraNodeOptions("--jitless --jitless"))
    }

    @Test fun `malformed, debugger and exit-at-once flags are named for the settings field`() {
        assertEquals(listOf("--inspect=0.0.0.0:9229", "--version", "jitless", "--x y"),
            EngineRuntimeEnv.rejectedNodeOptions("--jitless --inspect=0.0.0.0:9229 --version jitless --x\u00a0y").map { it.replace('\u00a0', ' ') })
        assertEquals(emptyList<String>(), EngineRuntimeEnv.rejectedNodeOptions("--max-old-space-size=1024 --stack-trace-limit=64"))
    }

    @Test fun `compatibility mode puts --jitless before the user's options`() {
        val s = io.github.jackwener.dsh.core.data.Settings()
        assertEquals("", s.engineExtraNodeOptions)
        assertEquals("--jitless", s.copy(engineJitlessAutoAt = 1).engineExtraNodeOptions)
        assertEquals("--stack-trace-limit=64", s.copy(engineJitlessAutoAt = 1, engineJitless = false, engineNodeOptions = "--stack-trace-limit=64").engineExtraNodeOptions)
        assertEquals("--jitless --stack-trace-limit=64", s.copy(engineJitless = true, engineNodeOptions = "--stack-trace-limit=64").engineExtraNodeOptions)
    }

    @Test fun `native crash signals, not kills, count toward compatibility mode`() {
        val signaled = android.app.ApplicationExitInfo.REASON_SIGNALED
        assertEquals(listOf(true, true, true, false, false), listOf(11, 7, 4, 9, 15).map { EngineDiagnostics.isNativeCrash(signaled, it) })
        assertEquals(true, EngineDiagnostics.isNativeCrash(android.app.ApplicationExitInfo.REASON_CRASH_NATIVE, 0))
    }
}
