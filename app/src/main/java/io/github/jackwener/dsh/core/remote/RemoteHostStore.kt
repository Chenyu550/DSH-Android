package io.github.jackwener.dsh.core.remote

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jackwener.dsh.core.data.SecretStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val Context.remoteStore by preferencesDataStore("remote_hosts")
private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
private val hostList = ListSerializer(RemoteHost.serializer())

/** The secrets of one host entry, as edited (null = unchanged when saving). */
data class RemoteSecrets(val password: String? = null, val privateKey: String? = null, val passphrase: String? = null, val env: String? = null)

/**
 * Remote hosts (#51) and the workspace the home screen shows: null = this phone (本机), else a host id.
 * Host entries live in their own DataStore; passwords, keys, passphrases and the extra environment in
 * SecretStore (Keystore-encrypted), never in settings, logs or diagnostics.
 */
@Singleton
class RemoteHostStore @Inject constructor(@ApplicationContext private val context: Context, private val secrets: SecretStore) {
    private object K {
        val hosts = stringPreferencesKey("hosts_json")
        val workspace = stringPreferencesKey("workspace")
    }

    val hosts: Flow<List<RemoteHost>> = context.remoteStore.data.map { decode(it[K.hosts]) }
    /** The active workspace's host id (null = this phone); a deleted host reads as the phone. */
    val workspace: Flow<String?> = context.remoteStore.data.map { p -> p[K.workspace]?.takeIf { id -> decode(p[K.hosts]).any { it.id == id } } }

    suspend fun all(): List<RemoteHost> = hosts.first()
    suspend fun get(id: String): RemoteHost? = all().firstOrNull { it.id == id }

    fun newId(): String = UUID.randomUUID().toString().take(8)

    /** Adds or replaces [host]; non-null [s] fields replace the stored secrets ("" clears one). */
    suspend fun save(host: RemoteHost, s: RemoteSecrets) {
        fun put(kind: RemoteHost.Secret, v: String?) {
            if (v == null) return
            if (v.isEmpty()) secrets.clear(host.secretName(kind)) else secrets.write(host.secretName(kind), v)
        }
        put(RemoteHost.Secret.Password, s.password)
        put(RemoteHost.Secret.Key, s.privateKey)
        put(RemoteHost.Secret.Passphrase, s.passphrase)
        put(RemoteHost.Secret.Env, s.env)
        context.remoteStore.edit {
            val cur = decode(it[K.hosts])
            it[K.hosts] = json.encodeToString(hostList, if (cur.any { c -> c.id == host.id }) cur.map { c -> if (c.id == host.id) host else c } else cur + host)
        }
    }

    /** The user confirmed the host key the server presented (first connection, or a change they accepted). */
    suspend fun trustHostKey(id: String, keyLine: String, fingerprint: String) = context.remoteStore.edit {
        it[K.hosts] = json.encodeToString(hostList, decode(it[K.hosts]).map { c -> if (c.id == id) c.copy(hostKey = keyLine, hostKeyFingerprint = fingerprint) else c })
    }

    suspend fun delete(id: String) {
        RemoteHost.Secret.entries.forEach { secrets.clear("remote-$id-${it.slot}") }
        context.remoteStore.edit {
            it[K.hosts] = json.encodeToString(hostList, decode(it[K.hosts]).filter { c -> c.id != id })
            if (it[K.workspace] == id) it.remove(K.workspace)
        }
    }

    suspend fun setWorkspace(id: String?) = context.remoteStore.edit { if (id == null) it.remove(K.workspace) else it[K.workspace] = id }

    fun secret(host: RemoteHost, kind: RemoteHost.Secret): String? = secrets.read(host.secretName(kind))

    /** The SSH target of [host] with its stored credential. */
    fun target(host: RemoteHost): SshTarget = SshTarget(
        host.host, host.port, host.user,
        password = if (host.auth == RemoteAuth.Password) secret(host, RemoteHost.Secret.Password) else null,
        privateKey = if (host.auth == RemoteAuth.Key) secret(host, RemoteHost.Secret.Key) else null,
        passphrase = if (host.auth == RemoteAuth.Key) secret(host, RemoteHost.Secret.Passphrase) else null,
        trustedHostKey = host.hostKey,
    )

    /** The remote dsh's environment: this phone's DeepSeek API key (when shared), then the user's variables. */
    fun launch(host: RemoteHost): RemoteRuntime.Launch {
        val apiKey = if (host.shareApiKey) secrets.read() else null
        val user = RemoteEnv.parse(secret(host, RemoteHost.Secret.Env).orEmpty()).getOrDefault(emptyList())
        val env = listOfNotNull(apiKey?.let { "DEEPSEEK_API_KEY" to it }) + user
        return RemoteRuntime.Launch(host.id, host.workdir, host.dshCommand.ifBlank { RemoteHost.DEFAULT_COMMAND },
            host.provider.ifBlank { RemoteHost.DEFAULT_PROVIDER }, host.model.ifBlank { io.github.jackwener.dsh.core.provider.DEFAULT_DEEPSEEK_MODEL }, env)
    }

    private fun decode(raw: String?): List<RemoteHost> = raw?.let { runCatching { json.decodeFromString(hostList, it) }.getOrNull() } ?: emptyList()
}
