package io.github.jackwener.dsh.core.remote

import io.github.jackwener.dsh.core.provider.DEFAULT_DEEPSEEK_MODEL
import kotlinx.serialization.Serializable

enum class RemoteAuth { Password, Key }

/**
 * A computer the app drives over SSH (remote mode, #51): `ssh user@host -p port`, then the dsh installed
 * there in its SDK profile (RemoteLaunch). Secrets (password, private key, passphrase, extra environment)
 * live in SecretStore under [secretName], never here.
 */
@Serializable
data class RemoteHost(
    val id: String,
    val name: String,
    /** IPv4, IPv6 (with or without brackets) or a domain name. */
    val host: String,
    val port: Int = 22,
    val user: String,
    val auth: RemoteAuth = RemoteAuth.Password,
    /** Working directory of new conversations; "" = the remote home. `~/…` is expanded remotely. */
    val workdir: String = "",
    /** How to start dsh there: a command name, an absolute path, or e.g. `npx -y @deepseek-ai/dsh`. */
    val dshCommand: String = DEFAULT_COMMAND,
    /** Model route of the remote dsh (`initialize`): a provider configured on that computer. */
    val provider: String = DEFAULT_PROVIDER,
    val model: String = DEFAULT_DEEPSEEK_MODEL,
    /** Hand this phone's DeepSeek API key to the remote dsh (as DEEPSEEK_API_KEY, over the SSH channel). */
    val shareApiKey: Boolean = true,
    /** The trusted host key (OpenSSH public key line) and its SHA-256 fingerprint; null until the user confirmed one. */
    val hostKey: String? = null,
    val hostKeyFingerprint: String? = null,
) {
    /** `user@host:port` for display (IPv6 in brackets). */
    val address: String get() = "$user@${RemoteAddress.display(host)}" + if (port != 22) ":$port" else ""

    fun secretName(kind: Secret) = "remote-$id-${kind.slot}"

    enum class Secret(val slot: String) { Password("password"), Key("key"), Passphrase("passphrase"), Env("env") }

    companion object {
        const val DEFAULT_COMMAND = "dsh"
        const val DEFAULT_PROVIDER = "deepseek-official"
    }
}

object RemoteAddress {
    /** The host as typed, minus IPv6 brackets: what the socket connects to. */
    fun normalize(host: String): String = host.trim().removePrefix("[").removeSuffix("]")

    fun display(host: String): String = normalize(host).let { if (':' in it) "[$it]" else it }

    /** A plausible host: an IPv4/IPv6 literal or a DNS name (no spaces, no scheme, no path). */
    fun valid(host: String): Boolean {
        val h = normalize(host)
        if (h.isEmpty() || h.length > 253) return false
        if (':' in h) return h.all { it.isLetterOrDigit() || it == ':' || it == '.' || it == '%' }
        return h.all { it.isLetterOrDigit() || it == '-' || it == '.' || it == '_' } && !h.startsWith('.') && !h.startsWith('-')
    }
}

/** `KEY=value` lines of the extra environment; blank lines and `#` comments are skipped, invalid names refused. */
object RemoteEnv {
    private val NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")

    fun parse(text: String): Result<List<Pair<String, String>>> = runCatching {
        text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.map { line ->
            val eq = line.indexOf('=')
            require(eq > 0) { line }
            val name = line.substring(0, eq).trim().removePrefix("export ").trim()
            require(NAME.matches(name)) { line }
            name to line.substring(eq + 1).trim().let { v ->
                if (v.length >= 2 && (v.first() == '"' && v.last() == '"' || v.first() == '\'' && v.last() == '\'')) v.substring(1, v.length - 1) else v
            }
        }.toList()
    }
}
