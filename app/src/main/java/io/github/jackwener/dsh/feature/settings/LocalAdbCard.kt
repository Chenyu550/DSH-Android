package io.github.jackwener.dsh.feature.settings

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.EntryPointAccessors
import io.github.jackwener.dsh.R
import io.github.jackwener.dsh.core.designsystem.BtnSize
import io.github.jackwener.dsh.core.designsystem.BtnVariant
import io.github.jackwener.dsh.core.designsystem.DButton
import io.github.jackwener.dsh.core.designsystem.DInput
import io.github.jackwener.dsh.core.designsystem.DotKind
import io.github.jackwener.dsh.core.designsystem.Dsh
import io.github.jackwener.dsh.core.designsystem.DshType
import io.github.jackwener.dsh.core.designsystem.SettingsCard
import io.github.jackwener.dsh.core.designsystem.StateDot
import io.github.jackwener.dsh.core.designsystem.Tag
import io.github.jackwener.dsh.core.designsystem.Tone
import io.github.jackwener.dsh.core.platform.adb.AdbPairingNotifier
import io.github.jackwener.dsh.core.platform.adb.LocalAdb
import io.github.jackwener.dsh.core.platform.adb.LocalAdbEntryPoint
import io.github.jackwener.dsh.core.platform.adb.LocalAdbState
import kotlinx.coroutines.launch

fun localAdbOf(context: Context): LocalAdb =
    EntryPointAccessors.fromApplication(context.applicationContext, LocalAdbEntryPoint::class.java).localAdb()

/** Short status for the card's tag and 自检. */
fun localAdbStateLabel(context: Context, s: LocalAdbState): String = when (s) {
    LocalAdbState.Unsupported -> context.getString(R.string.adb_state_unsupported)
    LocalAdbState.NotPaired -> context.getString(R.string.adb_state_not_paired)
    LocalAdbState.Off -> context.getString(R.string.adb_state_off)
    LocalAdbState.Connecting -> context.getString(R.string.adb_state_connecting)
    is LocalAdbState.Ready -> context.getString(R.string.adb_state_ready, (s.uid ?: 2000).toString())
    is LocalAdbState.Unreachable -> context.getString(when (s.issue) {
        LocalAdbState.Issue.WirelessDebuggingOff -> R.string.adb_state_wd_off
        LocalAdbState.Issue.PairingRemoved -> R.string.adb_state_pairing_removed
        LocalAdbState.Issue.Failed -> R.string.adb_state_failed
    })
}

/** Developer options, scrolled to 无线调试 where the Settings app supports the highlight key. */
fun wirelessDebuggingIntent(): Intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
    .putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/**
 * 直接 ADB 授权 (#71) on 设置 → 手机权限, under the Shizuku card: pair once with wireless debugging's
 * code (in DSH's notification while the system dialog stays open, or typed here with the port),
 * then "shell ready (uid 2000)" with 断开; a manual connect port when mDNS finds nothing.
 */
@Composable
fun LocalAdbCard(modifier: Modifier = Modifier) {
    val c = Dsh.colors
    val context = LocalContext.current
    val adb = remember { localAdbOf(context) }
    if (!adb.supported) return
    val s by adb.state.collectAsStateWithLifecycle()
    val pairing by adb.pairing.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var pairPort by remember { mutableStateOf("") }
    var manual by remember { mutableStateOf(false) }
    var connectPort by remember { mutableStateOf("") }
    fun openDevOptions() { runCatching { context.startActivity(wirelessDebuggingIntent()) } }

    SettingsCard(modifier) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StateDot(when (s) {
                    is LocalAdbState.Ready -> DotKind.Done
                    is LocalAdbState.Unreachable -> DotKind.Warn
                    LocalAdbState.Connecting -> DotKind.Info
                    else -> DotKind.Idle
                })
                Text(stringResource(R.string.adb_card_title), Modifier.weight(1f), style = DshType.xs13m, color = c.labelTertiary)
                Tag(localAdbStateLabel(context, s), when (s) { is LocalAdbState.Ready -> Tone.Success; is LocalAdbState.Unreachable -> Tone.Warn; else -> Tone.Neutral })
            }
            val body = when (s) {
                is LocalAdbState.Ready -> R.string.adb_body_ready
                LocalAdbState.Off -> R.string.adb_body_off
                is LocalAdbState.Unreachable -> when ((s as LocalAdbState.Unreachable).issue) {
                    LocalAdbState.Issue.WirelessDebuggingOff -> R.string.adb_body_wd_off
                    LocalAdbState.Issue.PairingRemoved -> R.string.adb_body_pairing_removed
                    LocalAdbState.Issue.Failed -> R.string.adb_body_failed
                }
                else -> R.string.adb_body_intro
            }
            Text(stringResource(body), style = DshType.xs13, color = c.labelSecondary)
            (s as? LocalAdbState.Unreachable)?.detail?.takeIf { (s as LocalAdbState.Unreachable).issue == LocalAdbState.Issue.Failed }
                ?.let { Text(it.take(200), style = DshType.xxs12, color = c.labelTertiary) }
            val p = pairing
            if (s == LocalAdbState.NotPaired || p != null) Steps()
            if (p != null) PairingForm(
                code = code, onCode = { code = it.filter(Char::isDigit).take(6) },
                port = pairPort.ifEmpty { p.port?.toString().orEmpty() }, onPort = { pairPort = it.filter(Char::isDigit).take(5) },
                found = p.port, busy = p.busy,
                error = p.error?.let { AdbPairingNotifier.failureText(context, it) },
            )
            if (manual && p == null && (s is LocalAdbState.Unreachable || s == LocalAdbState.Off)) {
                Text(stringResource(R.string.adb_connect_port_hint), style = DshType.xxs12, color = c.labelTertiary)
                DInput(connectPort, { connectPort = it.filter(Char::isDigit).take(5) }, placeholder = stringResource(R.string.adb_port_label), keyboardType = KeyboardType.Number)
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            val p = pairing
            when {
                p != null -> {
                    DButton(stringResource(R.string.adb_pair_cancel), { adb.cancelPairing(); code = ""; pairPort = "" }, variant = BtnVariant.Outline, size = BtnSize.Sm)
                    DButton(stringResource(R.string.shz_dev_options), ::openDevOptions, variant = BtnVariant.Outline, size = BtnSize.Sm)
                    val port = (pairPort.ifEmpty { p.port?.toString().orEmpty() }).toIntOrNull()
                    DButton(stringResource(R.string.adb_pair), { scope.launch { if (adb.pair(code, port)) { code = ""; pairPort = "" } } },
                        enabled = !p.busy && code.length == 6 && port != null, size = BtnSize.Sm)
                }
                s == LocalAdbState.NotPaired -> DButton(stringResource(R.string.adb_pair_start), { adb.startPairing(); openDevOptions() }, size = BtnSize.Sm)
                s is LocalAdbState.Ready -> DButton(stringResource(R.string.adb_disconnect), adb::disconnect, variant = BtnVariant.Outline, size = BtnSize.Sm)
                s == LocalAdbState.Connecting -> {}
                else -> { // Off / Unreachable
                    if (s is LocalAdbState.Unreachable && (s as LocalAdbState.Unreachable).issue == LocalAdbState.Issue.PairingRemoved)
                        DButton(stringResource(R.string.adb_repair), { adb.startPairing(); openDevOptions() }, size = BtnSize.Sm)
                    else {
                        if (!manual) DButton(stringResource(R.string.adb_manual_port), { manual = true }, variant = BtnVariant.Outline, size = BtnSize.Sm)
                        DButton(stringResource(R.string.shz_dev_options), ::openDevOptions, variant = BtnVariant.Outline, size = BtnSize.Sm)
                        DButton(stringResource(R.string.adb_connect), {
                            scope.launch { if (adb.connect(manualPort = connectPort.toIntOrNull(), userInitiated = true) != null) manual = false }
                        }, size = BtnSize.Sm)
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.Steps() {
    val c = Dsh.colors
    listOf(R.string.adb_step1, R.string.adb_step2, R.string.adb_step3).forEachIndexed { i, step ->
        Row(verticalAlignment = Alignment.Top) {
            Text("${i + 1}", Modifier.width(16.dp), style = DshType.xs13m, color = c.labelTertiary)
            Text(stringResource(step), style = DshType.xs13, color = c.labelSecondary)
        }
    }
}

@Composable
private fun ColumnScope.PairingForm(code: String, onCode: (String) -> Unit, port: String, onPort: (String) -> Unit, found: Int?, busy: Boolean, error: String?) {
    val c = Dsh.colors
    Text(if (found != null) stringResource(R.string.adb_pair_found, found) else stringResource(R.string.adb_pair_waiting),
        style = DshType.xxs12, color = c.labelTertiary)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(0.6f)) { DInput(code, onCode, placeholder = stringResource(R.string.adb_pair_code_hint), keyboardType = KeyboardType.Number) }
        Box(Modifier.weight(0.4f)) { DInput(port, onPort, placeholder = stringResource(R.string.adb_port_label), keyboardType = KeyboardType.Number) }
    }
    if (busy) Text(stringResource(R.string.adb_pair_busy), style = DshType.xs13, color = c.labelSecondary)
    error?.let { Text(stringResource(R.string.adb_pair_failed, it), style = DshType.xs13, color = c.error) }
}
