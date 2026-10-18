package app.smallthingz.alldoc.sources

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.MatrixCursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.CancellationSignal
import android.provider.DocumentsContract
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.smallthingz.alldoc.state.DocumentMetadata
import app.smallthingz.alldoc.state.DocumentState
import app.smallthingz.alldoc.state.LibraryCatalog
import app.smallthingz.alldoc.state.LibraryFile
import app.smallthingz.alldoc.state.StoredDocument
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecentDocumentsTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun saved(source: String? = null, imported: String? = null) = StoredDocument("a".repeat(64),
        DocumentMetadata("History.pdf", "application/pdf", 8, source, imported), DocumentState(page = 7), 1)

    @Test fun onlyConfirmedMissingReferencesHideHistoryAndLookupRemainsBounded() {
        val missing = setOf("deleted", "expired")
        fun visible(row: StoredDocument, retained: String? = null, aliases: List<String> = emptyList()) =
            RecentDocuments.keep(row, { retained }, { aliases }, missing::contains)
        assertFalse(visible(saved("deleted", "expired")))
        assertFalse(visible(saved()))
        assertTrue(visible(saved("offline-provider")))
        assertTrue(visible(saved("denied-provider")))
        assertTrue(visible(saved("deleted"), "retained"))
        assertTrue(visible(saved("deleted"), aliases = listOf("alternate")))
        assertTrue(visible(saved("deleted"), aliases = List(17) { "deleted" }))
        assertFalse(visible(saved("deleted"), aliases = List(16) { "deleted" }))
        assertTrue(RecentDocuments.keep(saved("valid"), { error("Unneeded retained lookup") },
            { error("Unneeded alias lookup") }, missing::contains))
    }

    @Test fun localProviderMetadataOnlyConfirmsEmptyOrExplicitDirectoryRows() {
        val id = DocumentsContract.Document.COLUMN_DOCUMENT_ID
        val mime = DocumentsContract.Document.COLUMN_MIME_TYPE
        MatrixCursor(arrayOf(id, mime)).use { cursor ->
            assertTrue(RecentDocuments.missing(cursor))
            cursor.addRow(arrayOf("primary:Documents/old.pdf", DocumentsContract.Document.MIME_TYPE_DIR))
            assertTrue("A replacement directory still has a valid path-based document ID", RecentDocuments.missing(cursor))
        }
        for (type in arrayOf(null, "application/pdf", "application/octet-stream")) {
            MatrixCursor(arrayOf(id, mime)).use { cursor ->
                cursor.addRow(arrayOf("primary:Documents/old.pdf", type))
                assertFalse(RecentDocuments.missing(cursor))
            }
        }
        MatrixCursor(arrayOf(id)).use { cursor ->
            cursor.addRow(arrayOf("primary:Documents/old.pdf"))
            assertFalse("An omitted MIME column is unknown, not a missing document", RecentDocuments.missing(cursor))
        }
    }

    @Test fun localDeletionKeepsAlternateCopyAndUnknownProvidersWithoutOpeningPayloads() {
        val root = File(context.cacheDir, "recent-check-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val scoped = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getDatabasePath(name: String): File = File(root, name)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
                SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, handler: DatabaseErrorHandler?): SQLiteDatabase =
                SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory, handler)
        }
        val original = File(root, "original.pdf").apply { writeText("Original") }
        val copy = File(root, "copy.pdf").apply { writeText("Copy") }
        val row = saved(Uri.fromFile(original).toString())
        fun visible(value: StoredDocument = row, retained: Uri? = null, signal: CancellationSignal = CancellationSignal()) =
            RecentDocuments.visible(scoped, listOf(value), signal) { retained }
        try {
            assertEquals(listOf(row), visible())
            assertTrue(original.delete())
            assertTrue(original.mkdir())
            assertEquals("A directory cannot replace an opened document", emptyList<StoredDocument>(), visible())
            assertEquals(listOf(row), visible(retained = Uri.fromFile(copy)))
            assertTrue(original.delete())
            Os.mkfifo(original.path, 0x180)
            assertEquals("A FIFO cannot replace an opened document", emptyList<StoredDocument>(), visible())
            assertEquals(listOf(row), visible(retained = Uri.fromFile(copy)))
            assertTrue(original.delete())
            Os.symlink(copy.path, original.path)
            assertEquals("A symlink to a regular document remains usable", listOf(row), visible())
            assertTrue(original.delete())
            assertEquals(emptyList<StoredDocument>(), visible())
            assertEquals(listOf(row), visible(retained = Uri.fromFile(copy)))
            LibraryCatalog(scoped).use { catalog ->
                val discovered = catalog.discover(LibraryFile(Uri.fromFile(copy).toString(), copy.name,
                    "application/pdf", copy.length(), 0))!!
                catalog.resolve(discovered, row.hash)
            }
            assertEquals(listOf(row), visible())
            assertTrue(copy.delete())
            assertEquals(emptyList<StoredDocument>(), visible())
            val parent = File(root, "replaced-folder").apply { check(mkdirs()) }
            val child = File(parent, "removed.pdf").apply { writeText("Removed") }
            val nested = saved(Uri.fromFile(child).toString())
            assertTrue(child.delete()); assertTrue(parent.delete()); parent.writeText("Now a file")
            assertEquals("ENOTDIR proves a vanished descendant too", emptyList<StoredDocument>(), visible(nested))
            val unknown = saved("content://offline.example/document/1")
            assertEquals(listOf(unknown), visible(unknown))
            val unavailableVolume = saved("file:///unmounted-alldoc-volume/deleted.pdf")
            assertEquals(listOf(unavailableVolume), visible(unavailableVolume))
            assertEquals(listOf(row), visible(signal = CancellationSignal().apply { cancel() }))
        } finally { root.deleteRecursively() }
    }
}
