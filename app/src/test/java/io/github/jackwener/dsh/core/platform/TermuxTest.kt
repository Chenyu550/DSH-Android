package io.github.jackwener.dsh.core.platform

import android.app.Activity
import android.app.Application
import android.os.Bundle
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #68: the RUN_COMMAND intent DSH sends to the user's Termux and how its result bundle is read. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
class TermuxTest {
    @Test fun runIntentTargetsRunCommandServiceAsABackgroundTaskWithATimeout() {
        val i = Termux.runIntent("pkg list-installed | head", "~/proj", 30)
        assertEquals("com.termux.RUN_COMMAND", i.action)
        assertEquals("com.termux", i.component?.packageName)
        assertEquals("com.termux.app.RunCommandService", i.component?.className)
        assertEquals("/data/data/com.termux/files/usr/bin/timeout", i.getStringExtra("com.termux.RUN_COMMAND_PATH"))
        assertArrayEquals(arrayOf("-k", "5", "30s", "/data/data/com.termux/files/usr/bin/bash", "-c", "pkg list-installed | head"),
            i.getStringArrayExtra("com.termux.RUN_COMMAND_ARGUMENTS"))
        assertEquals("/data/data/com.termux/files/home/proj", i.getStringExtra("com.termux.RUN_COMMAND_WORKDIR"))
        // Background (app shell) runner: the only one whose stdout/stderr Termux returns.
        assertTrue(i.getBooleanExtra("com.termux.RUN_COMMAND_BACKGROUND", false))
    }

    @Test fun workdirExpandsHomeAndKeepsAbsolutePaths() {
        assertEquals(Termux.HOME, Termux.workdir(null))
        assertEquals(Termux.HOME, Termux.workdir("  "))
        assertEquals(Termux.HOME, Termux.workdir("~"))
        assertEquals("${Termux.HOME}/a/b", Termux.workdir("~/a/b"))
        assertEquals("${Termux.HOME}/rel", Termux.workdir("rel"))
        assertEquals("/sdcard/Download", Termux.workdir("/sdcard/Download"))
    }

    @Test fun resultBundleCarriesExitCodeStdoutStderrAndCutLengths() {
        val b = Bundle().apply {
            putString("stdout", "hello\n"); putString("stdout_original_length", "6")
            putString("stderr", "warn"); putString("stderr_original_length", "90000")
            putInt("exitCode", 3); putInt("err", Activity.RESULT_OK); putString("errmsg", "")
        }
        val r = Termux.parseResult(b)
        assertEquals(TermuxResult(3, "hello\n", "warn", 6, 90000), r)
        val text = Termux.format(r, 30)
        assertTrue(text, text.startsWith("exit 3\nhello\n--- stderr ---\nwarn\n"))
        assertTrue(text, "--- stderr ---\nwarn" in text)
        assertTrue(text, "stderr cut by Termux: 4 of 90000" in text)
        assertFalse(text, "stdout cut" in text)
        assertTrue(Termux.format(TermuxResult(124, "", ""), 7).startsWith("exit 124 (stopped after 7s"))
        assertEquals("exit 0\n(no output)", Termux.format(TermuxResult(0, "", ""), 7))
    }

    @Test fun termuxReportedErrorsBecomeClearToolErrors() {
        fun err(msg: String) = Bundle().apply { putInt("err", Activity.RESULT_FIRST_USER + 1); putString("errmsg", msg) }
        try { Termux.parseResult(err("RUN_COMMAND requires `allow-external-apps` property to be set to `true` in `~/.termux/termux.properties` file.")); fail() }
        catch (e: TermuxException) { assertEquals("termux_not_allowed", e.code); assertTrue(e.message!!, "allow-external-apps = true" in e.message!!) }
        try { Termux.parseResult(err("No such file: /data/data/com.termux/files/usr/bin/timeout")); fail() }
        catch (e: TermuxException) { assertEquals("termux_failed", e.code); assertTrue(e.message!!, "No such file" in e.message!!) }
        try { Termux.parseResult(err("")); fail() }
        catch (e: TermuxException) { assertTrue(e.message!!, "error 2" in e.message!!) }
    }
}
