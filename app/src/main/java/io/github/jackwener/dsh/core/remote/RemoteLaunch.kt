package io.github.jackwener.dsh.core.remote

import java.io.IOException
import java.io.InputStream

/**
 * How the remote dsh is started over one SSH exec channel (#51, docs/ENGINE-CONTRACT.md §5).
 *
 * The exec command is a fixed `sh -c '…'` script: it has no quote or backslash pair that fish, zsh,
 * bash and sh would read differently, and it carries no value. Everything variable (working
 * directory, dsh command, the API key, the user's environment) goes over the channel's stdin as
 * `NAME=value` lines up to an empty line, so no secret ever shows in the remote `ps`. Then the script:
 * 1. enters the working directory (`~` expanded; default: the remote home);
 * 2. finds the dsh command, falling back to the login shell's PATH (nvm, Homebrew, ~/.local/bin are often
 *    only there); an absolute command path puts its own directory first, so the Node next to it is used;
 * 3. takes over: a dsh this host entry started earlier that is still alive (a connection that died
 *    without the server noticing) is stopped, so the new process can open its sessions;
 * 4. prints `DSH-REMOTE-OK <cwd>` and execs `<dsh> --profile sdk`: dsh's SDK JSON-RPC on stdin/stdout.
 * A failure before that prints `DSH-REMOTE-ERR <kind> <detail>` and exits.
 */
object RemoteLaunch {
    const val OK = "DSH-REMOTE-OK "
    const val ERR = "DSH-REMOTE-ERR "

    private val SCRIPT = """
        while IFS= read -r l && [ -n "${'$'}l" ]; do export "${'$'}l"; done
        w=${'$'}DSH_REMOTE_WORKDIR
        case ${'$'}w in "~") w=${'$'}HOME;; "~/"*) w=${'$'}HOME/${'$'}{w#??};; esac
        if [ -n "${'$'}w" ]; then cd "${'$'}w" 2>/dev/null || { echo "DSH-REMOTE-ERR workdir ${'$'}w"; exit 3; }; else cd "${'$'}HOME" 2>/dev/null; fi
        c=${'$'}{DSH_REMOTE_CMD:-dsh}
        case ${'$'}c in "~/"*) c=${'$'}HOME/${'$'}{c#??};; esac
        if [ -f "${'$'}c" ]; then set -- "${'$'}c"; else set -f; set -- ${'$'}c; set +f; fi
        case ${'$'}1 in */*) PATH=${'$'}{1%/*}:${'$'}PATH;; esac
        if ! command -v "${'$'}1" >/dev/null 2>&1; then
          p=${'$'}("${'$'}{SHELL:-/bin/sh}" -lc "echo; echo \"DSHPATH=\${'$'}PATH\"" </dev/null 2>/dev/null | sed -n "s/^DSHPATH=//p" | tail -n 1)
          [ -n "${'$'}p" ] && PATH=${'$'}p:${'$'}PATH
          command -v "${'$'}1" >/dev/null 2>&1 || { echo "DSH-REMOTE-ERR nodsh ${'$'}1"; exit 127; }
        fi
        export PATH
        pf=${'$'}HOME/.cache/dsh-android/${'$'}DSH_REMOTE_ID.pid
        mkdir -p "${'$'}{pf%/*}" 2>/dev/null
        o=${'$'}(cat "${'$'}pf" 2>/dev/null)
        if [ -n "${'$'}o" ] && kill -0 "${'$'}o" 2>/dev/null && ps -p "${'$'}o" -o args= 2>/dev/null | grep -q -e "--profile sdk"; then
          kill "${'$'}o" 2>/dev/null; i=0
          while kill -0 "${'$'}o" 2>/dev/null && [ ${'$'}i -lt 50 ]; do sleep 0.1 2>/dev/null || sleep 1; i=${'$'}((i+1)); done
          kill -9 "${'$'}o" 2>/dev/null
        fi
        echo ${'$'}${'$'} > "${'$'}pf" 2>/dev/null
        unset DSH_REMOTE_WORKDIR DSH_REMOTE_CMD DSH_REMOTE_ID
        echo "DSH-REMOTE-OK ${'$'}PWD"
        exec "${'$'}@" --profile sdk
    """.trimIndent()

    /** The exec request's command line. */
    val command: String = "sh -c '$SCRIPT'"

    init { check('\'' !in SCRIPT && "\\\\" !in SCRIPT) { "the launch script must stay quote-safe in every login shell" } }

    /**
     * The stdin preamble: `NAME=value` lines and the terminating empty line. [instance] names the
     * takeover pid file (one per host entry of this install). Values cannot contain a line break.
     */
    fun preamble(instance: String, workdir: String, command: String, env: List<Pair<String, String>>): String {
        val all = listOf("DSH_REMOTE_ID" to instance, "DSH_REMOTE_WORKDIR" to workdir.trim(), "DSH_REMOTE_CMD" to command.trim()) + env
        all.forEach { (k, v) -> require('\n' !in v && '\r' !in v) { "$k: line breaks are not supported" } }
        return all.joinToString("") { (k, v) -> "$k=$v\n" } + "\n"
    }

    sealed interface Handshake {
        /** dsh is starting in [cwd]; the stream is dsh's JSON-RPC from here on. */
        data class Ready(val cwd: String) : Handshake
        /** [kind]: `workdir` (cannot enter [detail]) or `nodsh` (command [detail] not found). */
        data class Failed(val kind: String, val detail: String) : Handshake
    }

    /** One stdout line before dsh: a handshake, or null for anything else (a login shell's banner). */
    fun parse(line: String): Handshake? = when {
        line.startsWith(OK) -> Handshake.Ready(line.removePrefix(OK))
        line.startsWith(ERR) -> line.removePrefix(ERR).let { Handshake.Failed(it.substringBefore(' '), it.substringAfter(' ', "")) }
        else -> null
    }

    /**
     * Reads stdout up to the handshake one byte at a time (the bytes after it are dsh's and must stay in
     * [input]). Null when the stream ended first; at most [maxLines] other lines are skipped.
     */
    fun awaitHandshake(input: InputStream, maxLines: Int = 200): Handshake? {
        val line = java.io.ByteArrayOutputStream()
        var lines = 0
        while (true) {
            val b = try { input.read() } catch (e: IOException) { return null }
            if (b < 0) return null
            if (b == '\n'.code) {
                parse(line.toString(Charsets.UTF_8).trimEnd('\r'))?.let { return it }
                if (++lines > maxLines) return null
                line.reset()
            } else if (line.size() < 8192) line.write(b)
        }
    }
}
