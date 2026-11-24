package io.github.jackwener.dsh.core.platform

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File

/**
 * The agent's working directory, one for the whole app (#76): `<filesDir>/home`, the engine's HOME
 * and every conversation's starting directory. App-private storage, so it keeps working without
 * storage permissions and with Node's hard links and locks, and is reachable from outside through
 * [WorkspaceProvider] (the system Files app's "DSH" root, any app's file picker) without copies.
 * A conversation can work in a subfolder of it (dsh's per-session working directory).
 *
 * Document ids are [ROOT_ID] and `home/<relative path>`; nothing outside home resolves, also not
 * through a symlink inside it.
 */
object Workspace {
    const val ROOT_ID = "home"

    fun home(context: Context): File = File(context.filesDir, "home")

    fun authority(context: Context): String = "${context.packageName}.workspace"

    /** The file of [docId] under [home], or null when it names nothing inside home. */
    fun fileOf(home: File, docId: String): File? {
        if (docId != ROOT_ID && !docId.startsWith("$ROOT_ID/")) return null
        val rel = docId.removePrefix(ROOT_ID).trimStart('/')
        if (rel.split('/').any { it == ".." }) return null
        val file = if (rel.isEmpty()) home else File(home, rel)
        return file.takeIf { inside(home, it) }
    }

    /** The document id of [file] (a path under [home]), or null outside it. */
    fun docIdOf(home: File, file: File): String? {
        if (!inside(home, file)) return null
        val base = home.absoluteFile.normalize().path
        val path = file.absoluteFile.normalize().path
        return if (path == base) ROOT_ID else "$ROOT_ID/" + path.removePrefix("$base/")
    }

    /** [file] is [home] or below it, also after resolving symlinks. */
    fun inside(home: File, file: File): Boolean {
        val base = runCatching { home.canonicalPath }.getOrNull() ?: return false
        val path = runCatching { file.canonicalPath }.getOrNull() ?: return false
        return path == base || path.startsWith("$base/")
    }

    /** [path] as the user sees it: `~` for home, `~/a/b` below it, else as is. */
    fun displayPath(home: String, path: String): String {
        val h = home.trimEnd('/')
        return when {
            path.trimEnd('/') == h -> "~"
            path.startsWith("$h/") -> "~/" + path.removePrefix("$h/").trimEnd('/')
            else -> path
        }
    }

    fun rootUri(context: Context): Uri = DocumentsContract.buildRootUri(authority(context), ROOT_ID)

    fun rootDocumentUri(context: Context): Uri = DocumentsContract.buildDocumentUri(authority(context), ROOT_ID)

    /** The file behind a document or tree [uri] of this provider (a picked file or folder), else null. */
    fun fileOfUri(context: Context, uri: Uri): File? {
        if (uri.authority != authority(context)) return null
        val docId = runCatching {
            if (DocumentsContract.isDocumentUri(context, uri)) DocumentsContract.getDocumentId(uri) else DocumentsContract.getTreeDocumentId(uri)
        }.getOrNull() ?: return null
        return fileOf(home(context), docId)
    }

    /**
     * Opens the workspace in the system Files app (DocumentsUI shows a root on ACTION_VIEW of its
     * root URI); false when no app takes it.
     */
    fun openInFiles(context: Context): Boolean {
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(rootUri(context), DocumentsContract.Root.MIME_TYPE_ITEM)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return try { context.startActivity(view); true } catch (_: ActivityNotFoundException) { false } catch (_: SecurityException) { false }
    }
}
