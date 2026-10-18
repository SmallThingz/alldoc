package app.smallthingz.alldoc.ui

import android.app.Application
import android.content.Context
import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.database.Cursor
import android.os.ParcelFileDescriptor
import androidx.test.filters.SdkSuppress
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.smallthingz.alldoc.sources.DocumentSources
import app.smallthingz.alldoc.state.DocumentStore
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderFailureTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync { action() }
    private fun await(ready: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            var complete = false
            main { complete = ready() }
            if (complete) return
            SystemClock.sleep(25)
        }
        fail("Reader worker did not settle")
    }

    @Test fun unavailableReadingDatabaseDoesNotDiscardAReadableDocument() = unavailableStorage("documents.db")
    @Test fun unavailableCopyIndexDoesNotDiscardAReadableDocument() = unavailableStorage("imports.sqlite")

    private fun unavailableStorage(databaseName: String) {
        val context = ScopedContext(instrumentation.targetContext)
        val blocked = context.getDatabasePath(databaseName).apply { mkdirs() }
        val input = File(context.root, "readable.md").apply { writeText("Readable despite unavailable $databaseName") }
        val documentStore = DocumentStore(context)
        val sources = DocumentSources(context)
        val application = object : Application() { init { attachBaseContext(context) } }
        val owner = ViewModelStore()
        lateinit var reader: ReaderModel
        var hash: String? = null
        try {
            main {
                reader = ReaderModel(application, documentStore, sources)
                owner.put("reader", reader)
                reader.open(Uri.fromFile(input))
            }
            await { !reader.loading && reader.info != null && reader.run { loadText(); text != null } && !reader.textLoading && !reader.storageBusy }
            main {
                assertNull(reader.error)
                assertNotNull(reader.documentHash)
                hash = reader.documentHash
                val warning = reader.notice.orEmpty()
                assertTrue("Storage failure must remain visible; notice=$warning",
                    warning.contains("unavailable", ignoreCase = true) ||
                        warning.contains("could not be saved", ignoreCase = true))
                reader.loadText()
            }
            await { reader.run { loadText(); text != null } }
            assertNull("Unavailable storage must not publish a cold-resume target",
                context.getSharedPreferences("reader", 0).getString("activeDocument", null))
            if (databaseName == "imports.sqlite") {
                val metadata = requireNotNull(documentStore.get(requireNotNull(hash))).metadata
                assertNull("Unknown copy ownership must not publish an original source", metadata.sourceUri)
                assertNull("An unavailable copy index cannot publish a retained reference", metadata.importUri)
            }
            main {
                assertTrue(reader.text!!.contains("Readable despite unavailable"))
                assertNull(reader.error)
                reader.showReader(false)
            }
            assertTrue("Storage failure must preserve the blocked test path", blocked.isDirectory)
        } finally {
            closeModel(owner, reader)
            documentStore.close()
            sources.close()
            context.clear()
        }
    }

    @Test fun unavailableCopyIndexCannotPromoteARetainedCopyToAnOriginal() {
        val context = ScopedContext(instrumentation.targetContext)
        val documentStore = DocumentStore(context)
        val sources = DocumentSources(context)
        val application = object : Application() { init { attachBaseContext(context) } }
        val owner = ViewModelStore()
        var reader: ReaderModel? = null
        fun active() = requireNotNull(reader)
        val index = context.getDatabasePath("imports.sqlite")
        val backup = File(context.root, "imports.saved.sqlite")
        try {
            // Seed only scoped private payloads, then close the seed helper before blocking SQLite.
            DocumentSources(context).use { it.status(app.smallthingz.alldoc.state.CopySettings()) }
            fun retained(body: String, original: File? = null): Pair<String, File> {
                val bytes = body.toByteArray()
                val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()
                val token = UUID.randomUUID().toString()
                val file = File(context.filesDir, "imports/Retained-$token.md").apply {
                    parentFile!!.mkdirs(); writeBytes(bytes)
                }
                SQLiteDatabase.openDatabase(index.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                    db.execSQL("INSERT INTO copies(hash,name,mime,bytes,uri,target,token,pending,owner,touched) VALUES(?,?,?,?,?,?,?,?,?,?)", arrayOf<Any?>(hash, "Retained.md",
                        "text/markdown", file.length(), Uri.fromFile(file).toString(), "private", token, 0,
                        android.os.Process.myPid(), System.currentTimeMillis()))
                }
                documentStore.open(hash, app.smallthingz.alldoc.state.DocumentMetadata("Retained.md", "text/markdown",
                    file.length(), original?.let { Uri.fromFile(it).toString() }, Uri.fromFile(file).toString()))
                return hash to file
            }
            val body = "Retained-only ownership fixture " + UUID.randomUUID()
            val (hash, copy) = retained(body)
            val original = File(context.root, "Original.md").apply { writeText("Original alias " + UUID.randomUUID()) }
            val (aliasHash, aliasCopy) = retained(original.readText(), original)
            assertTrue(index.renameTo(backup)); assertTrue(index.mkdir())
            main {
                reader = ReaderModel(application, documentStore, sources)
                owner.put("reader", active())
                active().reopenDocument(hash)
            }
            await { active().documentHash == hash && active().run { loadText(); text != null } && !active().textLoading && !active().loading && !active().storageBusy }
            main { assertNull(active().error); active().loadText() }
            await { active().run { loadText(); text != null } }
            main { assertTrue(active().text!!.contains(body)); assertNotNull(active().notice) }
            assertNull("Unknown ownership must not publish the private copy as an original",
                documentStore.get(hash)!!.metadata.sourceUri)
            assertEquals(Uri.fromFile(copy).toString(), documentStore.get(hash)!!.metadata.importUri)
            main { active().reopenDocument(aliasHash) }
            await { active().documentHash == aliasHash && active().run { loadText(); text != null } && !active().textLoading && !active().storageBusy }
            assertEquals("A genuine previously saved original must survive the null merge",
                Uri.fromFile(original).toString(), documentStore.get(aliasHash)!!.metadata.sourceUri)
            main { active().reopenDocument(hash) }
            await { active().documentHash == hash && active().run { loadText(); text != null } && !active().textLoading && !active().storageBusy }

            // The same lazy manager recovers; prove this copy is resume-eligible before clearing it.
            assertTrue(index.delete()); assertTrue(backup.renameTo(index))
            main { active().showReader(false); active().showReader(true); active().refreshStorageUsage() }
            await { !active().storageBusy && active().copyUsageCount == 2 }
            val stateWorker = ReaderModel::class.java.getDeclaredField("StateWorker").apply { isAccessible = true }
            (stateWorker.get(null) as ExecutorService).submit {}.get(15, TimeUnit.SECONDS)
            assertEquals(hash, context.getSharedPreferences("reader", 0).getString("activeDocument", null))
            main { active().clearRetainedCopies() }
            await { !active().storageBusy && active().copyUsageCount == 0 }
            assertFalse(copy.exists()); assertFalse(aliasCopy.exists()); assertTrue(original.exists())
            assertNull(documentStore.get(hash)!!.metadata.sourceUri)
            assertNull(documentStore.get(hash)!!.metadata.importUri)
            assertNull(context.getSharedPreferences("reader", 0).getString("activeDocument", null))
            assertEquals(Uri.fromFile(original).toString(), documentStore.get(aliasHash)!!.metadata.sourceUri)
            assertNull(documentStore.get(aliasHash)!!.metadata.importUri)

            closeModel(owner, active()); reader = null
            main {
                reader = ReaderModel(application, documentStore, sources)
                owner.put("reader", active())
                active().restoreLastDocument()
            }
            await { !active().restoringLastDocument && !active().storageBusy }
            main { assertNull(active().info); assertNull(active().error); active().reopenDocument(aliasHash) }
            await { active().documentHash == aliasHash && active().run { loadText(); text != null } && !active().textLoading && !active().storageBusy }
            main { assertEquals(Uri.fromFile(original), active().source); assertNull(active().error) }
            assertEquals(0, sources.status(app.smallthingz.alldoc.state.CopySettings()).count)
        } finally {
            reader?.let { closeModel(owner, it) }
            documentStore.close(); sources.close(); context.clear()
        }
    }

    @Test fun unavailableCopyIndexUsesSavedFormatOnlyWhenRetainedBytesStillMatch() {
        val context = ScopedContext(instrumentation.targetContext)
        val documentStore = DocumentStore(context)
        val sources = DocumentSources(context)
        val application = object : Application() { init { attachBaseContext(context) } }
        val owner = ViewModelStore()
        var reader: ReaderModel? = null
        fun active() = requireNotNull(reader)
        val index = context.getDatabasePath("imports.sqlite")
        val backup = File(context.root, "imports.saved.sqlite")
        try {
            DocumentSources(context).use { it.status(app.smallthingz.alldoc.state.CopySettings()) }
            val token = UUID.randomUUID().toString()
            val file = File(context.filesDir, "imports/Report-$token").apply { parentFile!!.mkdirs() }
            val document = android.graphics.pdf.PdfDocument()
            try {
                val page = document.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(8, 4, 1).create())
                page.canvas.drawColor(android.graphics.Color.BLUE)
                document.finishPage(page)
                file.outputStream().use(document::writeTo)
            } finally { document.close() }
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toHexString()
            val uri = Uri.fromFile(file)
            SQLiteDatabase.openDatabase(index.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL("INSERT INTO copies(hash,name,mime,bytes,uri,target,token,pending,owner,touched) VALUES(?,?,?,?,?,?,?,?,?,?)", arrayOf<Any?>(hash, "Saved report",
                    "application/pdf", file.length(), uri.toString(), "private", token, 0,
                    android.os.Process.myPid(), System.currentTimeMillis()))
            }
            documentStore.open(hash, app.smallthingz.alldoc.state.DocumentMetadata("Saved report", "application/pdf",
                file.length(), importUri = uri.toString()))
            assertTrue(index.renameTo(backup)); assertTrue(index.mkdir())
            main {
                reader = ReaderModel(application, documentStore, sources)
                active().setRenderWidth(720)
                owner.put("reader", active())
                active().reopenDocument(hash)
            }
            await { active().documentHash == hash && active().bitmap != null && !active().loading && !active().storageBusy }
            main {
                assertNull(active().error)
                assertEquals("pdf", active().info!!.format)
                assertEquals("Saved report", active().currentTitle)
                assertEquals(android.graphics.Color.BLUE, active().bitmap!!.getPixel(0, 0))
            }
            assertNull("Format recovery does not establish external ownership", documentStore.get(hash)!!.metadata.sourceUri)

            val changed = "Changed retained bytes " + UUID.randomUUID()
            file.writeText("<!doctype html><html><body>$changed</body></html>")
            val changedHash = java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toHexString()
            main { active().reopenDocument(hash) }
            await { active().documentHash == changedHash && active().run { loadText(); text != null } && !active().textLoading && !active().loading && !active().storageBusy }
            main {
                assertNull(active().error)
                assertEquals("html", active().info!!.format)
                assertEquals("A stale title must be rejected with the stale MIME", file.name, active().currentTitle)
                active().loadText()
            }
            await { active().run { loadText(); text != null } }
            main { assertTrue(active().text!!.contains(changed)) }
            assertNull(documentStore.get(changedHash)!!.metadata.sourceUri)
            assertEquals("text/html", documentStore.get(changedHash)!!.metadata.mimeType)
        } finally {
            reader?.let { closeModel(owner, it) }
            documentStore.close(); sources.close(); context.clear()
        }
    }

    @Test fun providerCancellationTerminatesOpeningAndTheNextDocumentStillOpens() {
        val context = ScopedContext(instrumentation.targetContext)
        val documentStore = DocumentStore(context)
        documentStore.saveCopySettings(app.smallthingz.alldoc.state.CopySettings(enabled = false))
        val sources = DocumentSources(context)
        val application = object : Application() { init { attachBaseContext(context) } }
        val owner = ViewModelStore()
        lateinit var reader: ReaderModel
        try {
            main {
                reader = ReaderModel(application, documentStore, sources)
                owner.put("reader", reader)
                reader.open(Uri.parse("content://app.smallthingz.alldoc.test.reader-fixtures/metadata-cancel.md"))
            }
            await { !reader.loading }
            main {
                assertFalse(reader.openingCancelled)
                assertFalse(reader.error.isNullOrBlank())
                assertNull(reader.info)
                assertNull(reader.bitmap)
            }
            val input = File(context.root, "after-cancellation.md").apply { writeText("Provider cancellation recovered") }
            main { reader.open(Uri.fromFile(input)) }
            await { !reader.loading && reader.run { loadText(); text != null } && !reader.textLoading && !reader.storageBusy }
            main { assertNull(reader.error); assertEquals(input.name, reader.currentTitle) }
        } finally {
            closeModel(owner, reader)
            documentStore.close()
            sources.close()
            context.clear()
        }
    }

    @Test fun explicitNavigationDefeatsAQueuedColdResume() {
        val context = ScopedContext(instrumentation.targetContext)
        val preferences = context.getSharedPreferences("resume", 0)
        val input = File(context.root, "eligible-resume.md").apply { writeText("Eligible queued resume") }
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(input.readBytes()).toHexString()
        assertTrue(preferences.edit().putString("activeDocument", hash).commit())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val application = object : Application() {
            init { attachBaseContext(context) }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                return preferences
            }
        }
        val documentStore = DocumentStore(context)
        documentStore.open(hash, app.smallthingz.alldoc.state.DocumentMetadata(input.name, "text/markdown", input.length(),
            Uri.fromFile(input).toString()))
        val sources = DocumentSources(context)
        val owner = ViewModelStore()
        lateinit var reader: ReaderModel
        try {
            main { reader = ReaderModel(application, documentStore, sources); owner.put("reader", reader) }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            main {
                reader.restoreLastDocument()
                assertTrue(reader.restoringLastDocument)
                reader.cancelAutomaticResume()
            }
            release.countDown()
            await { !reader.restoringLastDocument && !reader.storageBusy }
            main {
                assertEquals(0L, reader.documentId)
                assertFalse(reader.readerVisible)
                assertFalse(reader.loading)
                assertNull(reader.error)
                assertNull(reader.info)
            }
            assertEquals("Cancellation must win while the saved document remains eligible", hash,
                preferences.getString("activeDocument", null))
        } finally {
            release.countDown()
            closeModel(owner, reader)
            documentStore.close()
            sources.close()
            context.clear()
        }
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    fun reliableExportFailureReplacesPriorSuccessAfterFlushedPayload() {
        val context = ScopedContext(instrumentation.targetContext)
        val documentStore = DocumentStore(context)
        val sources = DocumentSources(context)
        val application = object : Application() { init { attachBaseContext(context) } }
        val owner = ViewModelStore()
        val input = File(context.root, "export.md").apply { writeText("Reliable export fixture.") }
        val ordinary = File(context.root, "export.txt")
        var reader: ReaderModel? = null
        var failed: ReportedDestinationFailure? = null
        try {
            main {
                reader = ReaderModel(application, documentStore, sources).also {
                    owner.put("reader", it)
                    it.open(Uri.fromFile(input))
                }
            }
            val current = requireNotNull(reader)
            await { !current.loading && current.info != null && !current.storageBusy }
            main {
                assertNotNull(current.prepareExport(ReaderModel.ExportKind.TEXT))
                current.export(Uri.fromFile(ordinary))
            }
            await { !current.exporting }
            main { assertNull(current.error); assertEquals("Export saved.", current.notice) }
            val expected = ordinary.readBytes()
            assertTrue(String(expected, Charsets.UTF_8).contains("Reliable export fixture."))
            main {
                assertNotNull(current.prepareExport(ReaderModel.ExportKind.TEXT))
                current.export(null)
                assertEquals("Cancelling the picker preserves the previous notice", "Export saved.", current.notice)
            }
            val destination = ReportedDestinationFailure { it.contentEquals(expected) }.also { failed = it }
            val uri = Uri.parse("content://alldoc.export.failure/result")
            var opens = 0
            // wrap(ContentProvider) calls the local ContentInterface directly: no Binder parcel
            // round trip can erase this descriptor's deterministic acknowledgement hook.
            context.testResolver = ContentResolver.wrap(object : ContentProvider() {
                override fun onCreate() = true
                override fun openFile(request: Uri, mode: String): ParcelFileDescriptor {
                    check(request == uri && mode == "wt")
                    opens++
                    return destination.output
                }
                override fun getType(uri: Uri) = "text/plain"
                override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                    args: Array<out String>?, sort: String?): Cursor? = null
                override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Unexpected insert")
                override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = error("Unexpected update")
                override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = error("Unexpected delete")
            })
            main {
                assertEquals("Export saved.", current.notice)
                assertNotNull(current.prepareExport(ReaderModel.ExportKind.TEXT))
                current.export(uri)
                assertNull("A new export clears the previous success immediately", current.notice)
            }
            await { !current.exporting }
            main {
                assertNull("Failed export must not retain or publish success", current.notice)
                assertTrue(current.error.orEmpty().contains(ReportedDestinationFailure.MESSAGE))
            }
            assertEquals(1, opens)
            assertEquals(1, destination.checks)
            assertTrue("The provider consumed the complete flushed payload before reporting failure", destination.completePayload)
            assertFalse(destination.output.fileDescriptor.valid())
        } finally {
            context.testResolver = null
            reader?.let { closeModel(owner, it) }
            failed?.close()
            documentStore.close(); sources.close(); context.clear()
        }
    }

    private fun closeModel(owner: ViewModelStore, reader: ReaderModel) {
        var job: Job? = null
        main { job = reader.viewModelScope.coroutineContext[Job]; owner.clear() }
        runBlocking { withTimeout(15_000) { job?.join() } }
        // Persisted snapshots and native session closes outlive the cancelled UI scope.
        for (name in listOf("StateWorker", "CloseWorker")) {
            val field = ReaderModel::class.java.getDeclaredField(name).apply { isAccessible = true }
            (field.get(null) as ExecutorService).submit {}.get(15, TimeUnit.SECONDS)
        }
    }

    private class ScopedContext(base: Context) : ContextWrapper(base) {
        val root = File(base.cacheDir, "reader-failure-${UUID.randomUUID()}").apply { mkdirs() }
        @Volatile var testResolver: ContentResolver? = null
        override fun getContentResolver(): ContentResolver = testResolver ?: super.getContentResolver()
        override fun getApplicationContext(): Context = this
        override fun getDatabasePath(name: String): File = File(root, name)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
            SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, handler: DatabaseErrorHandler?): SQLiteDatabase =
            SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory, handler)
        override fun getCacheDir(): File = baseContext.cacheDir
        override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences("${root.name}-$name", mode)
        fun clear() {
            super.deleteSharedPreferences("${root.name}-resume")
            super.deleteSharedPreferences("${root.name}-reader")
            root.deleteRecursively()
        }
    }
}
