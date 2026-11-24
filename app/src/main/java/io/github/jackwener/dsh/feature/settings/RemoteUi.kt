package io.github.jackwener.dsh.feature.settings

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jackwener.dsh.R
import io.github.jackwener.dsh.core.designsystem.BtnSize
import io.github.jackwener.dsh.core.designsystem.BtnVariant
import io.github.jackwener.dsh.core.designsystem.DButton
import io.github.jackwener.dsh.core.designsystem.DDialog
import io.github.jackwener.dsh.core.designsystem.DIcon
import io.github.jackwener.dsh.core.designsystem.DInput
import io.github.jackwener.dsh.core.designsystem.DSwitch
import io.github.jackwener.dsh.core.designsystem.DotKind
import io.github.jackwener.dsh.core.designsystem.Dsh
import io.github.jackwener.dsh.core.designsystem.DshType
import io.github.jackwener.dsh.core.designsystem.RiskConfirmDialog
import io.github.jackwener.dsh.core.designsystem.Segmented
import io.github.jackwener.dsh.core.designsystem.SettingsCard
import io.github.jackwener.dsh.core.designsystem.SettingsGroupTitle
import io.github.jackwener.dsh.core.designsystem.SettingsRow
import io.github.jackwener.dsh.core.designsystem.Spinner
import io.github.jackwener.dsh.core.designsystem.StateDot
import io.github.jackwener.dsh.core.provider.DEFAULT_DEEPSEEK_MODEL
import io.github.jackwener.dsh.core.remote.RemoteAddress
import io.github.jackwener.dsh.core.remote.RemoteAuth
import io.github.jackwener.dsh.core.remote.RemoteEngines
import io.github.jackwener.dsh.core.remote.RemoteEnv
import io.github.jackwener.dsh.core.remote.RemoteErrors
import io.github.jackwener.dsh.core.remote.RemoteException
import io.github.jackwener.dsh.core.remote.RemoteHost
import io.github.jackwener.dsh.core.remote.RemoteHostStore
import io.github.jackwener.dsh.core.remote.RemoteSecrets
import io.github.jackwener.dsh.core.remote.RemoteState
import io.github.jackwener.dsh.core.remote.SshTarget
import io.github.jackwener.dsh.core.session.SessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** 测试连接 in the host editor. */
sealed interface RemoteTest {
    data object Running : RemoteTest
    data class Ok(val cwd: String) : RemoteTest
    data class Failed(val message: String, val kind: RemoteException.Kind) : RemoteTest
    /** The server presented a host key the entry does not trust yet ([previous] null) or a different one. */
    data class Trust(val keyLine: String, val fingerprint: String, val previous: String?) : RemoteTest
}

/** The editor's fields; secrets left empty keep the stored ones. */
data class RemoteDraft(
    val name: String, val host: String, val port: String, val user: String, val auth: RemoteAuth,
    val password: String, val privateKey: String, val passphrase: String,
    val workdir: String, val command: String, val provider: String, val model: String, val shareApiKey: Boolean, val env: String,
    val hostKey: String?, val hostKeyFingerprint: String?,
)

@HiltViewModel
class RemoteViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: RemoteHostStore,
    private val remotes: RemoteEngines,
    private val sessions: SessionStore,
) : ViewModel() {
    val hosts: StateFlow<List<RemoteHost>> = store.hosts.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val workspace: StateFlow<String?> = store.workspace.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val states: StateFlow<Map<String, RemoteState>> = remotes.states

    fun newId() = store.newId()
    fun setWorkspace(id: String?) = viewModelScope.launch { store.setWorkspace(id) }
    fun storedEnv(host: RemoteHost): String = store.secret(host, RemoteHost.Secret.Env).orEmpty()
    fun hasSecret(host: RemoteHost, kind: RemoteHost.Secret) = store.secret(host, kind) != null

    /** Why [d] cannot be saved or tested (a string resource), or null. */
    fun invalid(d: RemoteDraft, existing: RemoteHost?): Int? = when {
        !RemoteAddress.valid(d.host) -> R.string.remote_invalid_host
        d.port.toIntOrNull()?.takeIf { it in 1..65535 } == null -> R.string.remote_invalid_port
        d.user.isBlank() || d.user.any { it.isWhitespace() } -> R.string.remote_invalid_user
        d.auth == RemoteAuth.Password && d.password.isEmpty() && existing?.let { hasSecret(it, RemoteHost.Secret.Password) } != true -> R.string.remote_need_password
        d.auth == RemoteAuth.Key && d.privateKey.isBlank() && existing?.let { hasSecret(it, RemoteHost.Secret.Key) } != true -> R.string.remote_need_key
        RemoteEnv.parse(d.env).isFailure -> R.string.remote_invalid_env
        else -> null
    }

    private fun host(id: String, d: RemoteDraft) = RemoteHost(
        id = id, name = d.name.trim().ifBlank { RemoteAddress.normalize(d.host) }, host = d.host.trim(), port = d.port.toInt(), user = d.user.trim(),
        auth = d.auth, workdir = d.workdir.trim(), dshCommand = d.command.trim().ifBlank { RemoteHost.DEFAULT_COMMAND },
        provider = d.provider.trim().ifBlank { RemoteHost.DEFAULT_PROVIDER }, model = d.model.trim().ifBlank { DEFAULT_DEEPSEEK_MODEL },
        shareApiKey = d.shareApiKey, hostKey = d.hostKey, hostKeyFingerprint = d.hostKeyFingerprint,
    )

    /** Connect, start dsh and `initialize` with the draft (stored secrets fill empty fields); nothing is saved. */
    suspend fun test(id: String, d: RemoteDraft, existing: RemoteHost?): RemoteTest {
        val h = host(id, d)
        val stored = existing ?: h
        fun pick(v: String, kind: RemoteHost.Secret) = v.ifEmpty { store.secret(stored, kind) }
        val target = SshTarget(h.host, h.port, h.user,
            password = if (h.auth == RemoteAuth.Password) pick(d.password, RemoteHost.Secret.Password) else null,
            privateKey = if (h.auth == RemoteAuth.Key) pick(d.privateKey, RemoteHost.Secret.Key) else null,
            passphrase = if (h.auth == RemoteAuth.Key) pick(d.passphrase, RemoteHost.Secret.Passphrase) else null,
            trustedHostKey = h.hostKey)
        val launch = store.launch(h).copy(env = store.launch(h).env.filter { it.first == "DEEPSEEK_API_KEY" } + RemoteEnv.parse(d.env).getOrDefault(emptyList()))
        return try {
            RemoteTest.Ok(remotes.test(target, launch))
        } catch (e: RemoteException) {
            when (e.kind) {
                RemoteException.Kind.HostKeyUnknown, RemoteException.Kind.HostKeyChanged ->
                    RemoteTest.Trust(e.presentedKey!!, e.presentedFingerprint!!, if (e.kind == RemoteException.Kind.HostKeyChanged) h.hostKeyFingerprint else null)
                else -> RemoteTest.Failed(RemoteErrors.message(context, e), e.kind)
            }
        }
    }

    fun save(id: String, d: RemoteDraft, existing: RemoteHost?, onDone: () -> Unit) = viewModelScope.launch {
        val h = host(id, d)
        store.save(h, RemoteSecrets(
            password = if (d.auth == RemoteAuth.Password) d.password.ifEmpty { null } else "",
            privateKey = if (d.auth == RemoteAuth.Key) d.privateKey.ifBlank { null } else "",
            passphrase = if (d.auth == RemoteAuth.Key) d.passphrase.ifEmpty { if (d.privateKey.isNotBlank()) "" else null } else "",
            env = d.env.trim(),
        ))
        // A live link runs on the old values: the next message connects with the new ones.
        if (existing != null && existing != h) remotes.reset(id)
        onDone()
    }

    /** Removes the entry, its secrets and its conversations on this phone (the computer keeps its dsh sessions). */
    fun delete(id: String, onDone: () -> Unit) = viewModelScope.launch {
        remotes.reset(id)
        sessions.deleteHostConversations(id)
        store.delete(id)
        onDone()
    }

    /** A private key file picked with the system picker (≤ 64 KB of text). */
    suspend fun readKey(uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching { context.contentResolver.openInputStream(uri)?.use { String(it.readNBytes(65536)) } }.getOrNull()
    }
}

/** 设置 → 远程主机: the workspace, the hosts, adding one. */
@Composable
fun RemoteSection(vm: RemoteViewModel, onHost: (String?) -> Unit) {
    val c = Dsh.colors
    val hosts by vm.hosts.collectAsStateWithLifecycle()
    val workspace by vm.workspace.collectAsStateWithLifecycle()
    val states by vm.states.collectAsStateWithLifecycle()
    Text(stringResource(R.string.remote_intro), Modifier.padding(start = 4.dp, top = 8.dp, end = 4.dp), style = DshType.xs13, color = c.labelTertiary)
    SettingsGroupTitle(stringResource(R.string.remote_workspace))
    SettingsCard {
        SettingsRow(stringResource(R.string.remote_this_phone), stringResource(R.string.remote_this_phone_desc), divider = false, onClick = { vm.setWorkspace(null) },
            trailing = { if (workspace == null) DIcon(R.drawable.dsw_check_outline, size = 16.dp, tint = c.labelPrimary) })
        hosts.forEach { h ->
            SettingsRow(h.name, h.address, onClick = { vm.setWorkspace(h.id) },
                trailing = { if (workspace == h.id) DIcon(R.drawable.dsw_check_outline, size = 16.dp, tint = c.labelPrimary) })
        }
    }
    SettingsGroupTitle(stringResource(R.string.remote_hosts))
    SettingsCard {
        hosts.forEachIndexed { i, h ->
            val st = states[h.id] ?: RemoteState.Idle
            SettingsRow(h.name, listOf(h.address, remoteStateText(st)).filter { it.isNotEmpty() }.joinToString(" · "), divider = i > 0, onClick = { onHost(h.id) },
                leading = { RemoteStateDot(st) },
                trailing = { DIcon(R.drawable.dsw_chevron_right_outline, size = 14.dp, tint = c.labelTertiary) })
        }
        SettingsRow(stringResource(R.string.remote_add), null, divider = hosts.isNotEmpty(), onClick = { onHost(null) },
            leading = { DIcon(R.drawable.dsw_plus_outline, size = 16.dp, tint = c.labelSecondary) })
    }
    Text(stringResource(R.string.remote_scope_note), Modifier.padding(start = 4.dp, top = 8.dp, end = 4.dp), style = DshType.xxs12, color = c.labelTertiary)
}

@Composable
fun RemoteStateDot(st: RemoteState) = when (st) {
    is RemoteState.Connecting -> Spinner(10.dp)
    else -> StateDot(when (st) { is RemoteState.Connected -> DotKind.Done; is RemoteState.Failed -> DotKind.Error; else -> DotKind.Idle })
}

@Composable
fun remoteStateText(st: RemoteState): String = when (st) {
    is RemoteState.Connected -> stringResource(R.string.remote_state_connected)
    is RemoteState.Connecting -> stringResource(R.string.remote_state_connecting)
    is RemoteState.Failed -> stringResource(if (st.error.kind == RemoteException.Kind.Disconnected) R.string.remote_state_lost else R.string.remote_state_failed)
    RemoteState.Idle -> ""
}

/** Add ([hostId] null) or edit one host; 测试连接 confirms the host key (TOFU) before anything runs there. */
@Composable
fun RemoteHostPage(vm: RemoteViewModel, hostId: String?, onDone: () -> Unit) {
    val c = Dsh.colors
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val hosts by vm.hosts.collectAsStateWithLifecycle()
    val existing = hostId?.let { id -> hosts.firstOrNull { it.id == id } }
    if (hostId != null && existing == null) {
        // Not loaded yet, or deleted: wait for the list; a deleted host closes the page.
        LaunchedEffect(hosts) { if (hosts.isNotEmpty()) onDone() }
        return
    }
    val id = rememberSaveable { hostId ?: vm.newId() }
    var name by rememberSaveable { mutableStateOf(existing?.name.orEmpty()) }
    var host by rememberSaveable { mutableStateOf(existing?.host.orEmpty()) }
    var port by rememberSaveable { mutableStateOf((existing?.port ?: 22).toString()) }
    var user by rememberSaveable { mutableStateOf(existing?.user.orEmpty()) }
    var auth by rememberSaveable { mutableStateOf(existing?.auth ?: RemoteAuth.Password) }
    var password by rememberSaveable { mutableStateOf("") }
    var privateKey by rememberSaveable { mutableStateOf("") }
    var passphrase by rememberSaveable { mutableStateOf("") }
    var workdir by rememberSaveable { mutableStateOf(existing?.workdir.orEmpty()) }
    var command by rememberSaveable { mutableStateOf(existing?.dshCommand ?: RemoteHost.DEFAULT_COMMAND) }
    var provider by rememberSaveable { mutableStateOf(existing?.provider ?: RemoteHost.DEFAULT_PROVIDER) }
    var model by rememberSaveable { mutableStateOf(existing?.model ?: DEFAULT_DEEPSEEK_MODEL) }
    var share by rememberSaveable { mutableStateOf(existing?.shareApiKey ?: true) }
    var env by rememberSaveable { mutableStateOf(existing?.let(vm::storedEnv).orEmpty()) }
    var hostKey by rememberSaveable { mutableStateOf(existing?.hostKey) }
    var fingerprint by rememberSaveable { mutableStateOf(existing?.hostKeyFingerprint) }
    var test by remember { mutableStateOf<RemoteTest?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    // A different address is a different server: its key starts untrusted (no false "changed" alarm).
    val resetKey = { h: String, p: String ->
        val same = existing != null && RemoteAddress.normalize(h) == RemoteAddress.normalize(existing.host) && p == existing.port.toString()
        hostKey = existing?.hostKey?.takeIf { same }; fingerprint = existing?.hostKeyFingerprint?.takeIf { same }
    }
    val draft = { RemoteDraft(name, host, port, user, auth, password, privateKey, passphrase, workdir, command, provider, model, share, env, hostKey, fingerprint) }
    val problem = vm.invalid(draft(), existing)
    val runTest = { scope.launch { test = RemoteTest.Running; test = vm.test(id, draft(), existing) } }
    val pickKey = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { scope.launch { vm.readKey(it)?.let { k -> privateKey = k; test = null } } }
    }
    val storedNote = stringResource(R.string.remote_secret_stored)

    Spacer(Modifier.height(8.dp))
    SettingsCard {
        SettingsRow(stringResource(R.string.remote_name), null, divider = false, stacked = true) { DInput(name, { name = it.take(40) }, placeholder = stringResource(R.string.remote_name_hint)) }
        SettingsRow(stringResource(R.string.remote_host), stringResource(R.string.remote_host_desc), stacked = true) {
            DInput(host, { host = it.trim(); resetKey(host, port); test = null }, placeholder = "192.168.1.10", keyboardType = KeyboardType.Uri)
        }
        SettingsRow(stringResource(R.string.remote_port), null, stacked = true) { DInput(port, { port = it.filter(Char::isDigit).take(5); resetKey(host, port); test = null }, keyboardType = KeyboardType.Number) }
        SettingsRow(stringResource(R.string.remote_user), null, stacked = true) { DInput(user, { user = it.trim(); test = null }, placeholder = "me") }
        SettingsRow(stringResource(R.string.remote_auth), null, stacked = true) {
            Segmented(listOf(stringResource(R.string.remote_auth_password), stringResource(R.string.remote_auth_key)), auth.ordinal, { auth = RemoteAuth.entries[it]; test = null })
        }
        if (auth == RemoteAuth.Password) {
            SettingsRow(stringResource(R.string.remote_password), null, stacked = true) {
                DInput(password, { password = it; test = null }, secret = true,
                    placeholder = if (existing != null && vm.hasSecret(existing, RemoteHost.Secret.Password)) storedNote else "")
            }
        } else {
            SettingsRow(stringResource(R.string.remote_key), stringResource(R.string.remote_key_desc), stacked = true) {
                DInput(privateKey, { privateKey = it; test = null }, singleLine = false, minHeight = 72.dp,
                    placeholder = if (existing != null && vm.hasSecret(existing, RemoteHost.Secret.Key)) storedNote else "-----BEGIN OPENSSH PRIVATE KEY-----")
                DButton(stringResource(R.string.remote_key_file), { pickKey.launch(arrayOf("*/*")) }, size = BtnSize.Sm, variant = BtnVariant.Outline)
            }
            SettingsRow(stringResource(R.string.remote_passphrase), stringResource(R.string.remote_passphrase_desc), stacked = true) {
                DInput(passphrase, { passphrase = it; test = null }, secret = true,
                    placeholder = if (existing != null && vm.hasSecret(existing, RemoteHost.Secret.Passphrase)) storedNote else "")
            }
        }
    }
    SettingsGroupTitle(stringResource(R.string.remote_dsh_group))
    SettingsCard {
        SettingsRow(stringResource(R.string.remote_workdir), stringResource(R.string.remote_workdir_desc), divider = false, stacked = true) {
            DInput(workdir, { workdir = it; test = null }, placeholder = "~/projects")
        }
        SettingsRow(stringResource(R.string.remote_command), stringResource(R.string.remote_command_desc), stacked = true) {
            DInput(command, { command = it; test = null }, placeholder = RemoteHost.DEFAULT_COMMAND)
        }
        SettingsRow(stringResource(R.string.remote_model), stringResource(R.string.remote_model_desc), stacked = true) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DInput(provider, { provider = it.trim(); test = null }, Modifier.weight(1f), placeholder = RemoteHost.DEFAULT_PROVIDER)
                DInput(model, { model = it.trim(); test = null }, Modifier.weight(1f), placeholder = DEFAULT_DEEPSEEK_MODEL)
            }
        }
        SettingsRow(stringResource(R.string.remote_share_key), stringResource(R.string.remote_share_key_desc), trailing = { DSwitch(share, { share = it; test = null }) })
        SettingsRow(stringResource(R.string.remote_env), stringResource(R.string.remote_env_desc), stacked = true) {
            DInput(env, { env = it; test = null }, singleLine = false, minHeight = 72.dp, placeholder = "HTTPS_PROXY=http://127.0.0.1:7890")
        }
    }
    SettingsGroupTitle(stringResource(R.string.remote_hostkey))
    SettingsCard {
        SettingsRow(fingerprint ?: stringResource(R.string.remote_hostkey_none), stringResource(if (fingerprint != null) R.string.remote_hostkey_desc else R.string.remote_hostkey_none_desc), divider = false)
    }
    Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        DButton(stringResource(R.string.remote_test), { runTest() }, variant = BtnVariant.Outline, enabled = problem == null && test != RemoteTest.Running)
        DButton(stringResource(R.string.save), { vm.save(id, draft(), existing, onDone) }, enabled = problem == null)
        if (existing != null) DButton(stringResource(R.string.delete), { confirmDelete = true }, variant = BtnVariant.Danger)
    }
    problem?.let { Text(stringResource(it), Modifier.padding(start = 4.dp, top = 8.dp), style = DshType.xs13, color = c.labelTertiary) }
    when (val t = test) {
        RemoteTest.Running -> Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Spinner(14.dp); Text(stringResource(R.string.remote_testing), style = DshType.xs13, color = c.labelSecondary)
        }
        is RemoteTest.Ok -> Text(stringResource(R.string.remote_test_ok, t.cwd), Modifier.padding(start = 4.dp, top = 10.dp), style = DshType.xs13, color = c.success)
        is RemoteTest.Failed -> Text(t.message, Modifier.padding(start = 4.dp, top = 10.dp), style = DshType.xs13, color = c.error)
        is RemoteTest.Trust -> {
            val address = "${user.ifBlank { "?" }}@${RemoteAddress.display(host)}" + if (port != "22") ":$port" else ""
            val trust = { hostKey = t.keyLine; fingerprint = t.fingerprint; runTest() }
            if (t.previous == null) DDialog({ test = null }, stringResource(R.string.remote_trust_title), buttons = {
                DButton(stringResource(R.string.cancel), { test = null }, variant = BtnVariant.Outline)
                DButton(stringResource(R.string.remote_trust), { trust() })
            }) {
                Text(stringResource(R.string.remote_trust_body, address), style = DshType.s14, color = c.labelSecondary)
                Text(t.fingerprint, style = DshType.mono11, color = c.labelPrimary)
                Text(stringResource(R.string.remote_trust_check), style = DshType.xs13, color = c.labelTertiary)
            } else RiskConfirmDialog(
                title = stringResource(R.string.remote_changed_title),
                body = stringResource(R.string.remote_changed_body, address, t.previous, t.fingerprint),
                check = stringResource(R.string.remote_changed_check),
                confirm = stringResource(R.string.remote_changed_trust),
                cancel = stringResource(R.string.cancel),
                onConfirm = { trust() }, onDismiss = { test = null })
        }
        null -> {}
    }
    if (confirmDelete) DDialog({ confirmDelete = false }, stringResource(R.string.remote_delete_title), buttons = {
        DButton(stringResource(R.string.cancel), { confirmDelete = false }, variant = BtnVariant.Outline)
        DButton(stringResource(R.string.delete), { confirmDelete = false; vm.delete(id, onDone) }, variant = BtnVariant.Danger)
    }) { Text(stringResource(R.string.remote_delete_body), style = DshType.s14, color = c.labelSecondary) }
}
