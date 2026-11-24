package io.github.jackwener.dsh.core.platform

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import io.github.jackwener.dsh.R
import java.io.File
import java.io.FileNotFoundException
import java.net.URLConnection

/**
 * The agent's working directory ([Workspace]) as a storage root (#76): "DSH" in the system Files
 * app and in every app's file / folder picker. Users browse, open, edit, add and remove the agent's
 * files in place, also while the app is not running a task; other apps get the documents the user
 * picks (no storage permission on either side). Only the system holds MANAGE_DOCUMENTS, so other
 * apps reach nothing the user did not pick.
 */
class WorkspaceProvider : DocumentsProvider() {
    private val home: File get() = Workspace.home(context!!).apply { mkdirs() }
    private val authority: String get() = Workspace.authority(context!!)
    private val main by lazy { Handler(Looper.getMainLooper()) }

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val c = MatrixCursor(projection ?: ROOT_COLUMNS)
        val ctx = context!!
        c.newRow().apply {
            add(Root.COLUMN_ROOT_ID, Workspace.ROOT_ID)
            add(Root.COLUMN_DOCUMENT_ID, Workspace.ROOT_ID)
            add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD or Root.FLAG_SUPPORTS_SEARCH or Root.FLAG_LOCAL_ONLY)
            add(Root.COLUMN_TITLE, ctx.getString(R.string.app_name))
            add(Root.COLUMN_SUMMARY, ctx.getString(R.string.workspace_root_summary))
            add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
            add(Root.COLUMN_MIME_TYPES, "*/*")
            add(Root.COLUMN_AVAILABLE_BYTES, home.usableSpace)
        }
        return c
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: DOCUMENT_COLUMNS).apply { addFile(this, documentId, fileOf(documentId)) }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        val dir = fileOf(parentDocumentId)
        val c = MatrixCursor(projection ?: DOCUMENT_COLUMNS)
        dir.listFiles()?.forEach { f -> Workspace.docIdOf(home, f)?.let { addFile(c, it, f) } }
        c.setNotificationUri(context!!.contentResolver, DocumentsContract.buildChildDocumentsUri(authority, parentDocumentId))
        return c
    }

    override fun querySearchDocuments(rootId: String, query: String, projection: Array<out String>?): Cursor {
        val c = MatrixCursor(projection ?: DOCUMENT_COLUMNS)
        home.walkTopDown().onEnter { Workspace.inside(home, it) }.drop(1)
            .filter { it.name.contains(query, ignoreCase = true) }.take(MAX_SEARCH_RESULTS)
            .forEach { f -> Workspace.docIdOf(home, f)?.let { addFile(c, it, f) } }
        return c
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        documentId.startsWith("$parentDocumentId/") && Workspace.fileOf(home, documentId) != null

    override fun getDocumentType(documentId: String): String = mimeOf(fileOf(documentId))

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val file = fileOf(documentId)
        val pfdMode = ParcelFileDescriptor.parseMode(mode)
        if (!mode.contains('w')) return ParcelFileDescriptor.open(file, pfdMode)
        // A written file: its folder's listing (size, date) changes once it is closed.
        return ParcelFileDescriptor.open(file, pfdMode, main) { notifyParentOf(documentId) }
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val dir = fileOf(parentDocumentId)
        if (!dir.isDirectory) throw FileNotFoundException("not a folder: $parentDocumentId")
        val isDir = mimeType == Document.MIME_TYPE_DIR
        val target = uniqueChild(dir, withExtension(sanitize(displayName), mimeType, isDir))
        val ok = if (isDir) target.mkdir() else target.createNewFile()
        if (!ok) throw FileNotFoundException("could not create $displayName")
        notifyChildren(parentDocumentId)
        return Workspace.docIdOf(home, target) ?: throw FileNotFoundException(displayName)
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val file = fileOf(documentId)
        if (documentId == Workspace.ROOT_ID) throw UnsupportedOperationException("the root cannot be renamed")
        val target = uniqueChild(file.parentFile!!, sanitize(displayName))
        if (!file.renameTo(target)) throw FileNotFoundException("could not rename $documentId")
        notifyParentOf(documentId)
        return Workspace.docIdOf(home, target)!!
    }

    override fun deleteDocument(documentId: String) {
        val file = fileOf(documentId)
        if (documentId == Workspace.ROOT_ID) throw UnsupportedOperationException("the root cannot be deleted")
        if (!file.deleteRecursively()) throw FileNotFoundException("could not delete $documentId")
        revokeDocumentPermission(documentId)
        notifyParentOf(documentId)
    }

    override fun removeDocument(documentId: String, parentDocumentId: String) = deleteDocument(documentId)

    override fun moveDocument(sourceDocumentId: String, sourceParentDocumentId: String, targetParentDocumentId: String): String {
        val file = fileOf(sourceDocumentId)
        val dir = fileOf(targetParentDocumentId)
        if (sourceDocumentId == Workspace.ROOT_ID || targetParentDocumentId.startsWith("$sourceDocumentId/")) throw IllegalArgumentException("cannot move a folder into itself")
        val target = uniqueChild(dir, file.name)
        if (!file.renameTo(target)) throw FileNotFoundException("could not move $sourceDocumentId")
        notifyChildren(sourceParentDocumentId); notifyChildren(targetParentDocumentId)
        return Workspace.docIdOf(home, target)!!
    }

    override fun copyDocument(sourceDocumentId: String, targetParentDocumentId: String): String {
        val file = fileOf(sourceDocumentId)
        val dir = fileOf(targetParentDocumentId)
        if (targetParentDocumentId == sourceDocumentId || targetParentDocumentId.startsWith("$sourceDocumentId/")) throw IllegalArgumentException("cannot copy a folder into itself")
        val target = uniqueChild(dir, file.name)
        if (!file.copyRecursively(target)) throw FileNotFoundException("could not copy $sourceDocumentId")
        notifyChildren(targetParentDocumentId)
        return Workspace.docIdOf(home, target)!!
    }

    private fun fileOf(docId: String): File =
        Workspace.fileOf(home, docId)?.takeIf { it.exists() } ?: throw FileNotFoundException("no document $docId")

    private fun addFile(c: MatrixCursor, docId: String, f: File) {
        val dir = f.isDirectory
        var flags = Document.FLAG_SUPPORTS_COPY
        if (f.canWrite()) {
            flags = flags or (if (dir) Document.FLAG_DIR_SUPPORTS_CREATE else Document.FLAG_SUPPORTS_WRITE)
            if (docId != Workspace.ROOT_ID) flags = flags or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME or
                Document.FLAG_SUPPORTS_MOVE or Document.FLAG_SUPPORTS_REMOVE
        }
        c.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, docId)
            add(Document.COLUMN_DISPLAY_NAME, if (docId == Workspace.ROOT_ID) context!!.getString(R.string.app_name) else f.name)
            add(Document.COLUMN_MIME_TYPE, mimeOf(f))
            add(Document.COLUMN_SIZE, if (dir) null else f.length())
            add(Document.COLUMN_LAST_MODIFIED, f.lastModified())
            add(Document.COLUMN_FLAGS, flags)
        }
    }

    private fun notifyChildren(parentId: String) =
        context!!.contentResolver.notifyChange(DocumentsContract.buildChildDocumentsUri(authority, parentId), null)

    private fun notifyParentOf(docId: String) = notifyChildren(docId.substringBeforeLast('/', Workspace.ROOT_ID))

    companion object {
        private const val MAX_SEARCH_RESULTS = 200
        private val ROOT_COLUMNS = arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_FLAGS, Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY, Root.COLUMN_ICON, Root.COLUMN_MIME_TYPES, Root.COLUMN_AVAILABLE_BYTES)
        private val DOCUMENT_COLUMNS = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS)

        fun mimeOf(f: File): String {
            if (f.isDirectory) return Document.MIME_TYPE_DIR
            val ext = f.name.substringAfterLast('.', "").lowercase()
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                ?: URLConnection.guessContentTypeFromName(f.name)
                ?: if (ext in TEXT_EXTENSIONS) "text/plain" else "application/octet-stream"
        }

        /** Source and config files an editor should open as text (no registered MIME type on Android). */
        private val TEXT_EXTENSIONS = setOf("md", "markdown", "txt", "log", "json", "jsonl", "yaml", "yml", "toml", "ini", "conf", "csv", "tsv",
            "py", "js", "mjs", "cjs", "ts", "tsx", "jsx", "kt", "kts", "java", "c", "h", "cpp", "hpp", "rs", "go", "rb", "php", "sh", "bash",
            "zsh", "sql", "xml", "html", "htm", "css", "scss", "svg", "gradle", "properties", "env", "gitignore", "lock")

        /** A file name: no path separators, not empty. */
        internal fun sanitize(name: String): String = name.replace('/', '_').replace('\u0000', '_').trim().ifEmpty { "untitled" }

        /** Adds the MIME type's extension to a new file when the name has none (a picker's "Save as" sends the type). */
        internal fun withExtension(name: String, mimeType: String, isDir: Boolean): String {
            if (isDir || name.contains('.')) return name
            val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType) ?: return name
            return "$name.$ext"
        }

        /** [name] in [dir], or `name (1).ext`, `name (2).ext`, … when taken. */
        internal fun uniqueChild(dir: File, name: String): File {
            var f = File(dir, name)
            var n = 1
            val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
            while (f.exists()) { f = File(dir, name.substring(0, dot) + " ($n)" + name.substring(dot)); n++ }
            return f
        }
    }
}
