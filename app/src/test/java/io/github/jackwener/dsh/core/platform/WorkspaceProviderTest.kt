package io.github.jackwener.dsh.core.platform

import android.app.Application
import android.content.pm.ProviderInfo
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files

/** #76: the agent's working directory as the "DSH" documents root, and its id ↔ path mapping. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
class WorkspaceProviderTest {
    private lateinit var provider: WorkspaceProvider
    private lateinit var home: File

    @Before fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        home = Workspace.home(app).apply { deleteRecursively(); mkdirs() }
        val info = ProviderInfo().apply { authority = Workspace.authority(app); exported = true; grantUriPermissions = true; readPermission = "android.permission.MANAGE_DOCUMENTS"; writePermission = readPermission }
        provider = Robolectric.buildContentProvider(WorkspaceProvider::class.java).create(info).get()
    }

    private fun names(parent: String): List<String> = provider.queryChildDocuments(parent, null, null as String?).use { c ->
        buildList { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow(Document.COLUMN_DISPLAY_NAME))) }.sorted()
    }

    @Test fun idsMapInsideHomeOnly() {
        assertEquals(home, Workspace.fileOf(home, "home"))
        assertEquals(File(home, "a/b.txt"), Workspace.fileOf(home, "home/a/b.txt"))
        assertNull(Workspace.fileOf(home, "home/../dsh-home"))
        assertNull(Workspace.fileOf(home, "other/x"))
        assertNull(Workspace.fileOf(home, "homework"))
        assertEquals("home", Workspace.docIdOf(home, home))
        assertEquals("home/a/b.txt", Workspace.docIdOf(home, File(home, "a/b.txt")))
        assertNull(Workspace.docIdOf(home, home.parentFile!!))
        // A symlink inside home that leads out of it resolves to nothing.
        val outside = Files.createTempDirectory("outside").toFile()
        Files.createSymbolicLink(File(home, "escape").toPath(), outside.toPath())
        assertNull(Workspace.fileOf(home, "home/escape"))
        assertFalse("not listed either", "escape" in names("home"))
    }

    @Test fun displayPaths() {
        assertEquals("~", Workspace.displayPath("/data/files/home", "/data/files/home"))
        assertEquals("~/blog/posts", Workspace.displayPath("/data/files/home/", "/data/files/home/blog/posts/"))
        assertEquals("/sdcard/Download", Workspace.displayPath("/data/files/home", "/sdcard/Download"))
        assertEquals("/data/files/homework", Workspace.displayPath("/data/files/home", "/data/files/homework"))
    }

    @Test fun oneWritableSearchableRoot() {
        provider.queryRoots(null).use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals("home", c.getString(c.getColumnIndexOrThrow(Root.COLUMN_DOCUMENT_ID)))
            val flags = c.getInt(c.getColumnIndexOrThrow(Root.COLUMN_FLAGS))
            assertTrue(flags and Root.FLAG_SUPPORTS_CREATE != 0)
            assertTrue("needed for folder pickers", flags and Root.FLAG_SUPPORTS_IS_CHILD != 0)
            assertTrue(flags and Root.FLAG_SUPPORTS_SEARCH != 0)
        }
    }

    @Test fun createWriteReadRenameMoveCopyDelete() {
        val dir = provider.createDocument("home", Document.MIME_TYPE_DIR, "blog")
        assertEquals("home/blog", dir)
        val doc = provider.createDocument(dir, "text/plain", "post")
        assertEquals("home/blog/post.txt", doc)
        assertEquals("home/blog/post (1).txt", provider.createDocument(dir, "text/plain", "post.txt"))
        provider.openDocument(doc, "w", null).use { pfd -> java.io.FileOutputStream(pfd.fileDescriptor).write("hello".toByteArray()) }
        assertEquals("hello", File(home, "blog/post.txt").readText())
        provider.openDocument(doc, "r", null).use { pfd -> assertEquals("hello", java.io.FileInputStream(pfd.fileDescriptor).readBytes().decodeToString()) }
        assertEquals("text/plain", provider.getDocumentType(doc))
        assertTrue(provider.getDocumentType(provider.createDocument("home", "application/octet-stream", "notes.md")).startsWith("text/"))
        assertTrue(provider.isChildDocument("home", doc))
        assertFalse(provider.isChildDocument("home/blo", doc))

        val renamed = provider.renameDocument(doc, "hello.txt")
        assertEquals("home/blog/hello.txt", renamed)
        val moved = provider.moveDocument(renamed, dir, "home")
        assertEquals("home/hello.txt", moved)
        assertEquals(listOf("blog", "hello.txt", "notes.md"), names("home"))
        val copied = provider.copyDocument(dir, "home")
        assertEquals("home/blog (1)", copied)
        provider.querySearchDocuments("home", "HELLO", null).use { assertEquals(1, it.count) }

        provider.deleteDocument(dir)
        assertFalse(File(home, "blog").exists())
        assertTrue(runCatching { provider.queryDocument(dir, null) }.exceptionOrNull() is FileNotFoundException)
        assertTrue(runCatching { provider.deleteDocument("home") }.isFailure)
        assertTrue(runCatching { provider.moveDocument(copied, "home", copied) }.isFailure)
    }

    @Test fun pickedUrisOfThisProviderMapToFiles() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        File(home, "proj").mkdirs()
        val tree = DocumentsContract.buildTreeDocumentUri(Workspace.authority(app), "home/proj")
        assertEquals(File(home, "proj"), Workspace.fileOfUri(app, tree))
        val doc = DocumentsContract.buildDocumentUri(Workspace.authority(app), "home/proj")
        assertEquals(File(home, "proj"), Workspace.fileOfUri(app, doc))
        assertNull(Workspace.fileOfUri(app, DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", "primary:Download")))
    }
}
