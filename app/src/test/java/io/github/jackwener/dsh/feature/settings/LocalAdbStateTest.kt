package io.github.jackwener.dsh.feature.settings

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.github.jackwener.dsh.core.platform.adb.AdbMdns
import io.github.jackwener.dsh.core.platform.adb.AdbNotPairedException
import io.github.jackwener.dsh.core.platform.adb.AdbPairingException
import io.github.jackwener.dsh.core.platform.adb.AdbPairingNotifier
import io.github.jackwener.dsh.core.platform.adb.LocalAdbState
import io.github.jackwener.dsh.core.platform.adb.NoPortException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetAddress

/** #71: what the ADB channel's card and the model are told for each state and failure. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
class LocalAdbStateTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test fun connectFailuresMapToWhatTheUserCanDo() {
        assertEquals(LocalAdbState.Issue.PairingRemoved, (LocalAdbState.failed(AdbNotPairedException("rejected")) as LocalAdbState.Unreachable).issue)
        assertEquals(LocalAdbState.Issue.WirelessDebuggingOff, (LocalAdbState.failed(NoPortException("none")) as LocalAdbState.Unreachable).issue)
        assertEquals(LocalAdbState.Issue.WirelessDebuggingOff, (LocalAdbState.failed(java.net.ConnectException("refused")) as LocalAdbState.Unreachable).issue)
        assertEquals(LocalAdbState.Issue.Failed, (LocalAdbState.failed(java.io.IOException("x")) as LocalAdbState.Unreachable).issue)
    }

    @Test fun onlyAWorkingChannelIsReadyAndOnlySetUpOnesCount() {
        assertTrue(LocalAdbState.Ready(connected = false, uid = 2000).ready)
        assertTrue(LocalAdbState.failed(NoPortException("none")).pairedOn)
        assertFalse(LocalAdbState.NotPaired.pairedOn)
        assertFalse(LocalAdbState.Off.pairedOn)
        assertFalse(LocalAdbState.Unsupported.ready)
    }

    @Test fun labelsAreDistinctAndTheReadyOneNamesTheUid() {
        val states = listOf(LocalAdbState.Unsupported, LocalAdbState.NotPaired, LocalAdbState.Off, LocalAdbState.Connecting,
            LocalAdbState.Ready(true, 2000), LocalAdbState.failed(NoPortException("")), LocalAdbState.failed(AdbNotPairedException("")),
            LocalAdbState.failed(java.io.IOException("")))
        val labels = states.map { localAdbStateLabel(app, it) }
        assertEquals(labels.size, labels.toSet().size)
        assertTrue(localAdbStateLabel(app, LocalAdbState.Ready(false, null)).contains("2000"))
        val reasons = states.map { it.reason }
        assertEquals(reasons.size, reasons.toSet().size)
        val failures = AdbPairingException.Kind.entries.map { AdbPairingNotifier.failureText(app, it) }
        assertEquals(failures.size, failures.toSet().size)
    }

    @Test fun onlyThisPhonesOwnAddressesCountAsLocal() {
        assertTrue(AdbMdns.isLocal(InetAddress.getLoopbackAddress()))
        assertFalse(AdbMdns.isLocal(InetAddress.getByName("203.0.113.7")))
    }
}
