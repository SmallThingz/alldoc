package app.smallthingz.alldoc.ui

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.smallthingz.alldoc.sources.DocumentSources
import app.smallthingz.alldoc.state.CopySettings
import app.smallthingz.alldoc.state.DocumentMetadata
import app.smallthingz.alldoc.state.DocumentStore
import app.smallthingz.alldoc.state.LibraryCatalog
import app.smallthingz.alldoc.state.TextPosition
import app.smallthingz.alldoc.state.ViewportState
import app.smallthingz.alldoc.theme.ReadingMode
import app.smallthingz.alldoc.theme.ThemeSettings
import java.io.Closeable
import java.io.File
import app.smallthingz.alldoc.sources.TransientDocument
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderPersistenceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync { action() }

    internal inner class Fixture : Closeable {
        val context = ScopedContext(instrumentation.targetContext)
        val store = DocumentStore(context)
        val sources = DocumentSources(context)
        val application = object : Application() { init { attachBaseContext(context) } }
        private var owner: ViewModelStore? = null
        private var current: ReaderModel? = null
        val reader get() = checkNotNull(current)
        fun launch(resume: Boolean = false, width: Int = 720, beforeCreate: () -> Unit = {}, onCreate: (ReaderModel) -> Unit = {}) {
            stop()
            beforeCreate()
            main {
                current = ReaderModel(application, store, sources)
                reader.setRenderWidth(width)
                owner = ViewModelStore().also { it.put("reader", reader) }
                onCreate(reader)
                if (resume) reader.restoreLastDocument()
            }
        }
        fun act(action: (ReaderModel) -> Unit) = main { action(reader) }
        fun await(allowError: Boolean = false, ready: (ReaderModel) -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 20_000
            while (SystemClock.uptimeMillis() < deadline) {
                var complete = false
                act { if (!allowError) assertNull(it.error); complete = ready(it) }
                if (complete) return
                SystemClock.sleep(25)
            }
            fail("Persisted reader state did not settle")
        }
        fun stateBarrier() {
            val field = ReaderModel::class.java.getDeclaredField("StateWorker").apply { isAccessible = true }
            (field.get(null) as ExecutorService).submit {}.get(15, TimeUnit.SECONDS)
        }
        fun activeHash(): String? {
            stateBarrier()
            return context.getSharedPreferences("reader", 0).getString("activeDocument", null)
        }
        fun retained(text: String, original: File? = null, publishState: Boolean = true): Pair<String, Uri> {
            sources.status(store.copySettings())
            val bytes = text.toByteArray()
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()
            val token = UUID.randomUUID().toString()
            val directory = File(context.filesDir, "imports").apply { mkdirs() }
            val file = File(directory, "Retained-$token.html").apply { writeBytes(bytes) }
            val uri = Uri.fromFile(file)
            SQLiteDatabase.openDatabase(context.getDatabasePath("imports.sqlite").path, null, SQLiteDatabase.OPEN_READWRITE).use { database ->
                database.execSQL("INSERT INTO copies(hash,name,mime,bytes,uri,target,token,pending,owner,touched) VALUES(?,?,?,?,?,?,?,?,?,?)",
                    arrayOf<Any?>(hash, "Retained.html", "text/html", bytes.size, uri.toString(), "private", token, 0,
                        android.os.Process.myPid(), System.currentTimeMillis()))
            }
            if (publishState) store.open(hash, DocumentMetadata("Retained.html", "text/html", bytes.size.toLong(),
                original?.let { Uri.fromFile(it).toString() }, uri.toString()))
            return hash to uri
        }
        private fun stop() {
            var job: Job? = null
            main {
                job = current?.viewModelScope?.coroutineContext?.get(Job)
                owner?.clear()
                owner = null
                current = null
            }
            runBlocking { withTimeout(15_000) { job?.join() } }
            // State snapshots and native closes deliberately outlive the cancelled UI scope.
            for (name in listOf("StateWorker", "CloseWorker")) {
                val field = ReaderModel::class.java.getDeclaredField(name).apply { isAccessible = true }
                (field.get(null) as ExecutorService).submit {}.get(15, TimeUnit.SECONDS)
            }
        }
        override fun close() {
            stop()
            store.close()
            sources.close()
            context.clear()
        }
    }

    @Test fun savedTextViewRestoresWithoutDiscardingItsTextAnchor() = Fixture().use { fixture ->
        val file = File(fixture.context.root, "legacy.html").apply { writeText("<html><body>Legacy text " + UUID.randomUUID() + "</body></html>") }
        val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toHexString()
        val anchor = TextPosition(7, .25f)
        fixture.store.open(hash, DocumentMetadata(file.name, "text/html", file.length(), Uri.fromFile(file).toString()),
            app.smallthingz.alldoc.state.DocumentState(mode = app.smallthingz.alldoc.state.DocumentViewMode.TEXT,
                textPosition = anchor, horizontalPages = true, notesVisible = true))
        fixture.launch()
        fixture.act { it.open(Uri.fromFile(file)) }
        fixture.await { it.documentHash == hash && it.text != null && !it.loading && !it.textLoading }
        fixture.act {
            assertTrue(it.textMode); assertTrue(it.horizontalPages); assertFalse("Unsupported text notes remain hidden", it.notesVisible)
            assertEquals(anchor, it.textPosition)
        }
    }

    @Test fun pageDirectionNotesAndFractionFollowContentAcrossAliasesAndProcessRecreation() = Fixture().use { fixture ->
        val first = File(fixture.context.root, "first.pdf").also { writeReaderPdf(it, pages = 8) }
        val alias = File(fixture.context.root, "alias.pdf").also { first.copyTo(it) }
        val other = File(fixture.context.root, "other.html").apply { writeText("<html><body>Separate content " + UUID.randomUUID() + "</body></html>") }
        fixture.launch()
        fixture.act { it.open(Uri.fromFile(first)) }
        fixture.await { it.info != null && it.bitmap != null && !it.loading && !it.pageLoading }
        var hash = ""
        var previousIdentity = 0L
        fixture.act {
            assertFalse(it.textMode); assertFalse(it.horizontalPages); assertFalse(it.notesVisible)
            hash = requireNotNull(it.documentHash); previousIdentity = it.documentId
            it.updatePagePosition(3, .375f)
            it.setHorizontalPages(true)
            it.setNotesVisible(true)
            it.flushDocumentState()
        }
        fixture.stateBarrier()
        assertTrue(fixture.store.get(hash)!!.state.notesVisible)
        fixture.act { it.open(Uri.fromFile(other)) }
        fixture.await { it.currentTitle == other.name && it.info != null && it.bitmap != null && !it.loading }
        fixture.act {
            assertFalse("New text documents also start as pages", it.textMode)
            assertFalse(it.horizontalPages); assertFalse(it.notesVisible)
            it.setNotesVisible(true, previousIdentity)
            it.setHorizontalPages(true, previousIdentity)
            assertFalse("An old pane callback cannot change the next document", it.notesVisible)
            assertFalse(it.horizontalPages)
            it.open(Uri.fromFile(alias))
        }
        fixture.await { it.documentHash == hash && it.currentTitle == alias.name && !it.loading && it.bitmap != null }
        fixture.act {
            assertFalse(it.textMode); assertTrue(it.horizontalPages); assertTrue(it.notesVisible)
            assertEquals(3, it.page); assertEquals(.375f, it.pageFraction, 0f)
        }
        fixture.launch(resume = true)
        fixture.await { it.documentHash == hash && !it.loading && it.bitmap != null }
        fixture.act {
            assertFalse(it.textMode)
            assertTrue(it.horizontalPages); assertTrue(it.notesVisible)
            assertEquals(3, it.page); assertEquals(.375f, it.pageFraction, 0f)
            it.setHorizontalPages(false); it.setNotesVisible(false); it.flushDocumentState()
        }
        fixture.launch(resume = true)
        fixture.await { it.documentHash == hash && !it.loading && it.bitmap != null }
        fixture.act {
            assertFalse(it.horizontalPages); assertFalse(it.notesVisible)
            assertEquals(3, it.page); assertEquals(.375f, it.pageFraction, 0f)
        }
    }

    @Test fun recentPreviewsStayBoundedAndIgnoreUnrelatedPublications() = Fixture().use { fixture ->
        val hashes = (1..30).map { it.toString(16).padStart(64, '0') }
        val allowed = hashes.takeLast(6).toSet()
        val folder = File(fixture.context.cacheDir, "previews-v5").apply { check(mkdirs()) }
        val source = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        try {
            hashes.forEachIndexed { index, hash ->
                source.eraseColor(Color.rgb(index * 7 % 256, index * 11 % 256, index * 17 % 256))
                File(folder, "$hash.png").outputStream().use { assertTrue(source.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                val metadata = when (index) {
                    0 -> DocumentMetadata("Recent.PDF", null, 1)
                    1 -> DocumentMetadata("No extension", " \tAPPLICATION/PDF ; charset=utf-8", 1)
                    2 -> DocumentMetadata("Preview.\u212Aey", "application/octet-stream", 1)
                    3 -> DocumentMetadata("Misnamed.png", "application/pdf", 1)
                    4 -> DocumentMetadata("Historical.PDF", "text/plain", 1)
                    else -> DocumentMetadata("Recent-$index.pdf", "application/pdf", 1)
                }
                fixture.store.open(hash, metadata)
            }
        } finally { source.recycle() }
        // More than one candidate batch: removed images with misleading document names.
        val removed = (101..240).map { it.toString(16).padStart(64, '0') }
        removed.forEachIndexed { index, hash ->
            fixture.store.open(hash, DocumentMetadata("Removed-$index.pdf", "image/png", 1))
        }
        SQLiteDatabase.openDatabase(fixture.context.getDatabasePath("documents.db").path, null, SQLiteDatabase.OPEN_READWRITE).use { database ->
            // Legacy timestamps may tie: the hash anchor must neither skip nor repeat rows.
            database.execSQL("UPDATE documents SET opened=(SELECT MAX(opened) FROM documents) WHERE mime='image/png'")
        }
        val preserved = fixture.store.get(removed.first())
        assertEquals(50, fixture.store.recent().size)
        assertTrue(fixture.store.recent().all { it.metadata.mimeType == "image/png" })
        assertEquals(hashes.takeLast(6).reversed(), fixture.store.recentDocuments(6).map { it.hash })
        fixture.launch(width = 0, onCreate = { it.setAppearance(ThemeSettings(ReadingMode.ORIGINAL)) })
        fixture.await { it.recentDocuments.map { row -> row.hash }.toSet() == hashes.toSet() }
        assertEquals((hashes.size + removed.size).toLong(), fixture.store.count())
        assertEquals(preserved, fixture.store.get(removed.first()))
        fixture.act { reader -> hashes.forEach(reader::requestRecentPreview) }
        fixture.await { it.recentPreviews.keys == allowed }
        var published = emptyMap<String, Bitmap>()
        fixture.act { reader ->
            published = reader.recentPreviews
            hashes.forEachIndexed { index, hash ->
                if (hash in allowed) {
                    val image = checkNotNull(published[hash])
                    assertFalse(image.isRecycled)
                    assertEquals(Color.rgb(index * 7 % 256, index * 11 % 256, index * 17 % 256), image.getPixel(0, 0))
                }
            }
        }
        val lateHash = "f".repeat(64)
        fixture.act { reader ->
            // A completed old-document save can arrive after that hash leaves Home's allowed set.
            val field = ReaderModel::class.java.getDeclaredField("previews\$delegate").apply { isAccessible = true }
            ((field.get(reader) as Lazy<*>).value as DocumentPreviews).saveText(lateHash, "Late former document")
        }
        val drained = java.util.concurrent.CountDownLatch(1)
        DocumentPreviews({ File(fixture.context.root, "preview-barrier") }) { _, image, _ ->
            image.recycle(); drained.countDown()
        }.use { barrier ->
            barrier.saveText("e".repeat(64), "Worker barrier")
            assertTrue("The late preview worker must finish", drained.await(5, TimeUnit.SECONDS))
        }
        assertTrue("The late result must pass through the actual cache worker", File(folder, "$lateHash.png").isFile)
        fixture.act { reader ->
            assertEquals("An unrelated result must not evict Home previews", allowed, reader.recentPreviews.keys)
            published.forEach { (hash, image) ->
                assertSame(image, reader.recentPreviews[hash])
                assertFalse("Published thumbnails remain usable", image.isRecycled)
            }
        }
    }

    @Test fun pagesWaitForARealViewportWidthBeforeRendering() = Fixture().use { fixture ->
        val file = File(fixture.context.root, "measured.pdf").also { writeReaderPdf(it) }
        fixture.launch(width = 0)
        fixture.act { it.open(Uri.fromFile(file)) }
        fixture.await { it.info != null && !it.loading }
        fixture.act {
            assertFalse("Opening must not render an assumed page width", it.pageLoading)
            assertNull(it.bitmap)
            it.setRenderWidth(0)
            assertFalse(it.pageLoading)
            assertNull(it.bitmap)
            it.setRenderWidth(1190)
        }
        fixture.await { !it.pageLoading && it.bitmap?.width == 1190 }
        fixture.act {
            val image = it.bitmap
            it.setRenderWidth(0)
            assertSame("Transient zero-size layout must not restart rendering", image, it.bitmap)
            assertFalse(it.pageLoading)
        }
    }

    @Test fun equivalentViewportWidthsKeepTheRenderedPage() = Fixture().use { fixture ->
        fun rendering(reader: ReaderModel): Boolean {
            val field = ReaderModel::class.java.getDeclaredField("rendering").apply { isAccessible = true }
            val task = requireNotNull(field.get(reader))
            val job = task.javaClass.getDeclaredField("job").apply { isAccessible = true }.get(task) as? Job
            return job?.isActive == true
        }
        val file = File(fixture.context.root, "viewport.pdf").also { writeReaderPdf(it) }
        fixture.launch()
        fixture.act { it.open(Uri.fromFile(file)) }
        fixture.await { !it.loading && !it.pageLoading && it.bitmap != null }
        var originalPage: android.graphics.Bitmap? = null
        fixture.act { reader ->
            originalPage = checkNotNull(reader.bitmap)
            assertEquals(720, originalPage!!.width)
            for (width in listOf(0, 320, 600, 640, 719, 720)) {
                reader.setRenderWidth(width)
                assertFalse("Equivalent output sizes must not restart rendering", reader.pageLoading)
                assertSame("Narrow layout changes must reuse the published page", originalPage, reader.bitmap)
            }
            reader.setRenderWidth(960)
            assertSame("The usable page stays visible while its upgrade renders", originalPage, reader.bitmap)
            assertFalse("An upgrade must not show a missing-page loader", reader.pageLoading)
            assertTrue("A materially larger effective width must start an upgrade", rendering(reader))
        }
        fixture.await { !rendering(it) && !it.pageLoading && it.bitmap?.width == 960 }
        var largerPage: android.graphics.Bitmap? = null
        fixture.act { reader ->
            assertNotSame(originalPage, reader.bitmap)
            largerPage = reader.bitmap
            reader.setRenderWidth(960)
            assertFalse(reader.pageLoading)
            assertSame(largerPage, reader.bitmap)
            reader.setRenderWidth(640)
            assertSame("Shrinking keeps the sharper published page", largerPage, reader.bitmap)
            assertFalse(reader.pageLoading)
            assertFalse("Shrinking must not schedule a redundant downsample", rendering(reader))
        }
        fixture.act { reader ->
            reader.setRenderWidth(600)
            assertFalse(reader.pageLoading)
            assertSame(largerPage, reader.bitmap)
            assertFalse(rendering(reader))
            assertFalse("The old page remains valid while Compose can retain it", requireNotNull(originalPage).isRecycled)
            assertFalse(requireNotNull(largerPage).isRecycled)
        }
    }

    @Test fun renamedSourceRestoresAllStateAndOldCallbacksCannotChangeTheNewHash() = Fixture().use { fixture ->
        val first = File(fixture.context.root, "first.html")
        val renamed = File(fixture.context.root, "renamed.html")
        val other = File(fixture.context.root, "other.html")
        val token = UUID.randomUUID().toString()
        val contents = "<html><body><pre>" + (0 until 320).joinToString("\n") { "$token line $it" } + "</pre></body></html>"
        other.writeText("<html><body>Different document $token</body></html>")
        val appearance = ThemeSettings(ReadingMode.SEPIA, false)
        val viewport = ViewportState(2.5f, .25f, .75f, 3)
        val position = TextPosition(2500, .35f)
        fixture.launch()
        fixture.act { reader ->
            reader.open(Uri.fromFile(first))
            val before = reader.settings
            reader.setAppearance(ThemeSettings(ReadingMode.INVERT), reader.documentId)
            assertTrue(reader.loading)
            assertEquals("Appearance cannot change before saved state is restored", before, reader.settings)
        }
        fixture.await(allowError = true) { !it.loading && it.error != null }
        lateinit var oldRetry: () -> Unit
        lateinit var retryMessage: String
        lateinit var oldCancel: () -> Unit
        fixture.act { reader ->
            val identity = reader.documentId
            retryMessage = requireNotNull(reader.error)
            oldRetry = { reader.retryOpening(identity, retryMessage) }
            assertNull(reader.info)
            reader.showError("Replacement opening error")
            oldRetry()
            assertEquals("An old Retry must preserve a different error in the same document", identity, reader.documentId)
            assertFalse(reader.loading)
            assertEquals("Replacement opening error", reader.error)
            reader.showError(retryMessage)
        }
        first.writeText(contents)
        first.copyTo(renamed)
        fixture.act { reader ->
            oldRetry()
            assertTrue("The current error's Retry must reopen its source", reader.loading)
            val identity = reader.documentId
            oldCancel = { reader.cancelLoading(identity) }
        }
        fixture.await { it.currentTitle == "first.html" && !it.loading && !it.pageLoading && it.bitmap != null }
        fixture.act { assertFalse(it.textMode); it.setTextMode(true) }
        fixture.await { it.text != null && !it.textLoading }
        fixture.act { reader ->
            val identity = reader.documentId
            oldCancel()
            assertEquals(identity, reader.documentId)
            assertTrue("A queued Cancel after readiness must not navigate Home", reader.readerVisible)
            assertFalse(reader.openingCancelled)
        }
        var hash: String? = null
        var oldIdentity = 0L
        lateinit var oldClose: () -> Boolean
        lateinit var oldErrorDismiss: () -> Unit
        fixture.act {
            assertTrue(it.info!!.reflowable)
            assertTrue("Model resolves semantic document pages on its worker", it.info!!.pageCount > 1)
            assertTrue(it.textMode)
            assertNull(it.bitmap)
            hash = it.documentHash
            oldIdentity = it.documentId
            val reader = it
            val capturedIdentity = it.documentId
            oldClose = { reader.showReader(false, capturedIdentity) }
            oldErrorDismiss = { reader.dismissError(capturedIdentity) }
            it.goToPage(1)
            it.updateViewport(viewport)
            it.updateTextPosition(position)
            it.setTextMode(true)
            it.setAppearance(appearance)
            it.flushDocumentState()
            it.open(Uri.fromFile(other))
            // Same UI action: no recomposition has disposed the previous surface yet.
            val openingIdentity = it.documentId
            assertTrue(it.loading)
            oldCancel()
            it.showError(retryMessage)
            oldRetry()
            it.dismissError()
            assertEquals("Old controls must not cancel or restart the new open", openingIdentity, it.documentId)
            assertEquals(Uri.fromFile(other), it.source)
            assertFalse(it.openingCancelled)
            assertFalse(oldClose())
            assertTrue(it.readerVisible)
            assertTrue(it.loading)
            it.showError("New opening error")
            oldErrorDismiss()
            oldRetry()
            assertEquals(openingIdentity, it.documentId)
            assertTrue(it.loading)
            assertEquals("New opening error", it.error)
            it.dismissError()
        }
        fixture.await { it.currentTitle == "other.html" && !it.loading }
        fixture.act {
            // A completed old transition must not mutate the new document before recomposition.
            val currentHash = it.documentHash
            assertFalse(oldClose())
            assertTrue(it.readerVisible)
            assertEquals(currentHash, it.documentHash)
            val displayedMessage = "Current document error"
            val currentIdentity = it.documentId
            val dismissDisplayedError = { it.dismissError(currentIdentity, displayedMessage) }
            it.showError(displayedMessage)
            oldErrorDismiss()
            assertEquals(displayedMessage, it.error)
            it.showError("Replacement document error")
            dismissDisplayedError()
            assertEquals("A closing dialog must preserve a different error in the same document",
                "Replacement document error", it.error)
            it.dismissError()
            assertNull(it.error)
            val state = it.viewport
            val mode = it.textMode
            val theme = it.settings
            it.updateViewport(ViewportState(7f), oldIdentity)
            it.updateTextPosition(TextPosition(9999), oldIdentity)
            it.setTextMode(!mode, oldIdentity)
            it.setAppearance(ThemeSettings(ReadingMode.INVERT), oldIdentity)
            it.goToPage(1, oldIdentity)
            assertEquals(state, it.viewport)
            assertEquals(TextPosition(), it.textPosition)
            assertEquals(mode, it.textMode)
            assertEquals(theme, it.settings)
            assertEquals(0, it.page)
            it.open(Uri.fromFile(renamed))
        }
        fixture.await { it.currentTitle == "renamed.html" && !it.loading && !it.pageLoading && it.bitmap != null }
        fun checkState(reader: ReaderModel) {
            assertEquals(hash, reader.documentHash)
            assertEquals("renamed.html", reader.currentTitle)
            assertEquals("The independent document-page anchor survives text utility use", 1, reader.page)
            assertNotNull(reader.bitmap)
            assertEquals(viewport, reader.viewport)
            assertEquals(position, reader.textPosition)
            assertFalse("Every reopen starts with document pages", reader.textMode)
            assertEquals(appearance, reader.settings)
            reader.setTextMode(true)
            assertTrue(reader.textMode)
            assertNull(reader.bitmap)
            assertEquals("Entering the text utility preserves the saved character and line fraction", position, reader.textPosition)
        }
        fixture.act(::checkState)
        fixture.await { it.text != null && !it.textLoading }
        val previous = fixture.reader
        fixture.launch(resume = true)
        fixture.await { it.documentHash == hash && !it.loading && !it.pageLoading && it.bitmap != null }
        fixture.act { assertNotSame(previous, it); checkState(it) }
        fixture.await { it.text != null && !it.textLoading }
        fixture.await { !it.storageBusy }
        fixture.act { it.clearRetainedCopies() }
        fixture.await { !it.storageBusy && it.notice == "Retained copies cleared. Reading positions were kept." }
        fixture.act { reader ->
            val displayed = requireNotNull(reader.notice)
            val dismissDisplayedNotice = { reader.dismissNotice(displayed) }
            reader.open(Uri.fromFile(renamed))
            reader.cancelLoading()
            val replacement = requireNotNull(reader.notice)
            assertNotEquals(displayed, replacement)
            dismissDisplayedNotice()
            assertEquals("A completed snackbar must preserve a newer notice", replacement, reader.notice)
            reader.dismissNotice(replacement)
            assertNull(reader.notice)
        }
    }

    @Test fun systemAppearanceFollowsTheDeviceWithoutReplacingHashBoundChoicesOrRasterizingText() = Fixture().use { fixture ->
        val first = File(fixture.context.root, "system.html").apply { writeText("<html><body>System appearance " + UUID.randomUUID() + "</body></html>") }
        val other = File(fixture.context.root, "chosen.html").apply { writeText("<html><body>Explicit appearance " + UUID.randomUUID() + "</body></html>") }
        val fresh = File(fixture.context.root, "fresh.html").apply { writeText("<html><body>New document " + UUID.randomUUID() + "</body></html>") }
        fun enterTextUtility(title: String) {
            fixture.await { it.currentTitle == title && !it.loading && !it.pageLoading && it.bitmap != null }
            fixture.act {
                assertFalse("Opening a document starts with pages", it.textMode)
                it.setTextMode(true)
                assertTrue(it.textMode)
            }
            fixture.await { it.text != null && !it.textLoading }
        }
        fixture.launch()
        fixture.act { it.open(Uri.fromFile(first)) }
        enterTextUtility(first.name)
        var firstHash = ""
        fixture.act {
            firstHash = requireNotNull(it.documentHash)
            assertEquals(ReadingMode.SYSTEM, it.settings.mode)
            it.setSystemDark(false)
            assertEquals(ReadingMode.ADAPTIVE_LIGHT, it.effectiveSettings.mode)
            it.updateTextPosition(TextPosition(8, .25f))
            it.setSystemDark(true)
            assertEquals(ReadingMode.ADAPTIVE, it.effectiveSettings.mode)
            assertEquals(ReadingMode.SYSTEM, it.settings.mode)
            assertEquals(TextPosition(8, .25f), it.textPosition)
            assertNull(it.bitmap)
            assertFalse(it.pageLoading)
            it.flushDocumentState()
        }
        fixture.stateBarrier()
        assertEquals(ReadingMode.SYSTEM, fixture.store.get(firstHash)!!.state.appearance.mode)
        fixture.act { it.open(Uri.fromFile(other)) }
        enterTextUtility(other.name)
        fixture.act { it.setAppearance(ThemeSettings(ReadingMode.SEPIA)); it.open(Uri.fromFile(first)) }
        enterTextUtility(first.name)
        fixture.act {
            assertEquals("One document’s Sepia choice must not affect another hash", ReadingMode.SYSTEM, it.settings.mode)
            assertEquals(TextPosition(8, .25f), it.textPosition)
            it.open(Uri.fromFile(other))
        }
        enterTextUtility(other.name)
        fixture.act {
            it.setSystemDark(false)
            assertEquals(ReadingMode.SEPIA, it.effectiveSettings.mode)
            assertNull(it.bitmap)
            it.open(Uri.fromFile(fresh))
        }
        enterTextUtility(fresh.name)
        fixture.act {
            assertEquals("A per-document choice must not become the default for new hashes", ReadingMode.SYSTEM, it.settings.mode)
            assertEquals(ReadingMode.ADAPTIVE_LIGHT, it.effectiveSettings.mode)
            assertNull(it.bitmap)
        }
    }

    @Test fun coldLaunchResumesTheLastDocumentButExplicitHomeStaysHome() = Fixture().use { fixture ->
        val file = File(fixture.context.root, "resume.html").apply { writeText("<html><body>Recoverable fixture " + UUID.randomUUID() + "</body></html>") }
        var hash: String? = null
        val viewport = ViewportState(2f, .3f, .6f, 1)
        fixture.store.saveCopySettings(CopySettings(enabled = false, maxCount = 7))
        fixture.launch { reader ->
            // Startup cannot publish on main until this explicit same-value choice finishes.
            assertEquals(CopySettings(), reader.copySettings)
            reader.updateCopySettings(CopySettings())
        }
        fixture.stateBarrier()
        fixture.act { assertEquals("Startup must preserve the latest storage choice", CopySettings(), it.copySettings) }
        assertEquals("The explicit defaults must also be durable", CopySettings(), fixture.store.copySettings())
        fixture.act { it.open(Uri.fromFile(file)) }
        fixture.await { it.currentTitle == file.name && it.info != null && !it.loading && it.info!!.pageCount > 0 }
        fixture.act { hash = it.documentHash; it.updateViewport(viewport); it.flushDocumentState() }
        fixture.launch(resume = true)
        fixture.await { it.documentHash == hash && it.info != null && !it.loading && it.info!!.pageCount > 0 }
        fixture.act { assertTrue(it.readerVisible); assertEquals(viewport, it.viewport) }
        fixture.launch { reader ->
            reader.restoreLastDocument()
            reader.cancelAutomaticResume() // A deliberate Home tab click wins before startup publishes.
        }
        fixture.await { !it.restoringLastDocument && it.recentDocuments.any { saved -> saved.hash == hash } }
        fixture.act {
            assertFalse("A tab choice must prevent automatic navigation", it.readerVisible)
            assertNull(it.info); assertFalse(it.loading)
            it.reopenDocument(requireNotNull(hash))
        }
        fixture.await { it.documentHash == hash && it.info != null && !it.loading && it.info!!.pageCount > 0 }
        fixture.act { assertTrue(it.readerVisible); assertEquals(viewport, it.viewport); it.showReader(false) }
        fixture.launch(resume = true)
        fixture.await { !it.restoringLastDocument && it.recentDocuments.any { saved -> saved.hash == hash } }
        fixture.act {
            assertFalse(it.readerVisible)
            assertNull(it.info)
            assertFalse(it.loading)
            it.reopenDocument(requireNotNull(hash))
        }
        fixture.await { it.documentHash == hash && it.info != null && !it.loading && it.info!!.pageCount > 0 }
        fixture.act { assertTrue(it.readerVisible); assertEquals(viewport, it.viewport) }
    }

    @Test fun libraryReopenValidatesCurrentBytesEvenForTheRetainedHash() = Fixture().use { fixture ->
        val body = "<html><body><pre>Library alias " + UUID.randomUUID() + "\n" + "Reading position\n".repeat(100) + "</pre></body></html>"
        val first = File(fixture.context.root, "a.html").apply { writeText(body) }
        val second = File(fixture.context.root, "b.html").also { first.copyTo(it) }
        val pose = ViewportState(3f, .3f, .7f, 1)
        val position = TextPosition(90, .4f)
        fixture.launch(width = 0)
        fixture.act { it.open(Uri.fromFile(second)) }
        fixture.await { it.currentTitle == second.name && it.info != null && !it.loading && it.info!!.pageCount > 0 }
        var hash = ""
        fixture.act {
            hash = requireNotNull(it.documentHash)
            it.updateViewport(pose); it.updateTextPosition(position); it.flushDocumentState()
            it.open(Uri.fromFile(first))
        }
        fixture.await { it.currentTitle == first.name && it.info != null && !it.loading && it.info!!.pageCount > 0 }
        fixture.stateBarrier()
        val row = LibraryCatalog(fixture.context).use { requireNotNull(it.location(Uri.fromFile(second).toString())) }
        assertEquals(hash, row.hash)
        var identity = 0L
        fixture.act {
            identity = it.documentId
            it.showReader(false); it.openLibraryDocument(row)
        }
        fixture.await { it.currentTitle == second.name && it.info != null && !it.loading && it.info!!.pageCount > 0 }
        fixture.act {
            assertTrue("A Library alias is a fresh open, even with the same hash", it.documentId > identity)
            assertEquals(Uri.fromFile(second), it.source); assertEquals(hash, it.documentHash)
            assertEquals(pose, it.viewport); assertEquals(position, it.textPosition)
            identity = it.documentId
            it.showReader(false)
        }
        fixture.stateBarrier()
        val saved = requireNotNull(fixture.store.get(hash)).state
        second.writeText(body.replaceFirst("Library", "Changed"))
        assertEquals("The replacement keeps the same byte count", first.length(), second.length())
        val changedHash = MessageDigest.getInstance("SHA-256").digest(second.readBytes()).toHexString()
        assertNotEquals(hash, changedHash)
        fixture.act {
            it.showReader(true)
            assertEquals("Explicit Resume keeps the retained snapshot", identity, it.documentId)
            assertEquals(hash, it.documentHash); assertEquals(pose, it.viewport)
            it.showReader(false); it.openLibraryDocument(row)
        }
        fixture.await { it.documentHash == changedHash && it.info != null && !it.loading && it.info!!.pageCount > 0 }
        fixture.act {
            assertTrue(it.documentId > identity); assertEquals(Uri.fromFile(second), it.source)
            assertEquals(ViewportState(), it.viewport); assertEquals(TextPosition(), it.textPosition)
        }
        fixture.stateBarrier()
        assertEquals("Changed bytes cannot overwrite the previous hash state", saved, fixture.store.get(hash)!!.state)
        LibraryCatalog(fixture.context).use {
            assertEquals(changedHash, it.location(Uri.fromFile(second).toString())?.hash)
            assertEquals(hash, it.location(Uri.fromFile(first).toString())?.hash)
        }
        assertEquals("Local aliases must not create retained copies", 0, fixture.sources.status(fixture.store.copySettings()).count)
    }

    @Test fun recentReopenUsesOnlyAnIndexedAliasWhoseCurrentBytesMatch() {
        for (changed in listOf(false, true)) Fixture().use { fixture ->
            val body = "<html><body><pre>Alias recovery " + UUID.randomUUID() + "\n" + "Reading position\n".repeat(100) + "</pre></body></html>"
            val first = File(fixture.context.root, "a.html").apply { writeText(body) }
            val second = File(fixture.context.root, "b.html").also { first.copyTo(it) }
            val pose = ViewportState(3f, .3f, .7f, 1)
            val position = TextPosition(90, .4f)
            fixture.launch(width = 0)
            fixture.act { it.open(Uri.fromFile(first)) }
            fixture.await { it.currentTitle == first.name && it.info != null && !it.loading && it.info!!.pageCount > 0 }
            var hash = ""
            fixture.act {
                hash = requireNotNull(it.documentHash)
                it.updateViewport(pose); it.updateTextPosition(position)
                it.flushDocumentState(); it.open(Uri.fromFile(second))
            }
            fixture.await { it.currentTitle == second.name && it.info != null && !it.loading && it.info!!.pageCount > 0 }
            fixture.act { assertEquals(pose, it.viewport); assertEquals(position, it.textPosition) }
            fixture.stateBarrier()
            val saved = requireNotNull(fixture.store.get(hash))
            assertEquals(Uri.fromFile(second).toString(), saved.metadata.sourceUri)
            LibraryCatalog(fixture.context).use { catalog ->
                assertEquals(setOf(Uri.fromFile(first).toString(), Uri.fromFile(second).toString()), catalog.locationsForHash(hash).toSet())
            }
            assertTrue(second.delete())
            if (changed) {
                first.writeText(body.replaceFirst("Alias", "Other"))
                assertEquals("Same-size stale aliases still require full hash verification", saved.metadata.sizeBytes, first.length())
            }
            fixture.launch(width = 0)
            fixture.act { it.reopenDocument(hash) }
            if (changed) {
                fixture.await(allowError = true) { !it.loading && it.error != null }
                fixture.act { assertNull(it.documentHash); assertNull(it.info) }
                assertEquals("Rejected aliases cannot overwrite the requested hash state", saved.state, fixture.store.get(hash)!!.state)
            } else {
                fixture.await { it.documentHash == hash && it.info != null && !it.loading && it.info!!.pageCount > 0 }
                fixture.act {
                    assertEquals(Uri.fromFile(first), it.source)
                    assertEquals(pose, it.viewport); assertEquals(position, it.textPosition)
                }
                fixture.stateBarrier()
                assertEquals(Uri.fromFile(first).toString(), fixture.store.get(hash)!!.metadata.sourceUri)
            }
            assertEquals("Recovering an original alias must not retain a duplicate", 0, fixture.sources.status(fixture.store.copySettings()).count)
        }
    }

    @Test fun coldResumeRequiresARecoverableReferenceAndPreservesAnOlderAlias() = Fixture().use { fixture ->
        fixture.store.saveCopySettings(CopySettings(enabled = false))
        val text = "<html><body>Unretained shared document " + UUID.randomUUID() + "</body></html>"
        fixture.launch()
        val shared = TransientDocument(text, "Shared.html")
        try {
        fixture.act { it.open(shared.uri) }
        fixture.await { it.info != null && !it.loading && it.info!!.pageCount > 0 && !it.storageBusy }
        var hash = ""
        fixture.act { hash = requireNotNull(it.documentHash); it.showReader(false); it.showReader(true) }
        assertNull(fixture.activeHash())
        val stored = requireNotNull(fixture.store.get(hash))
        assertNull(stored.metadata.sourceUri)
        assertNull(stored.metadata.importUri)
        fixture.launch(resume = true)
        fixture.await { !it.restoringLastDocument && !it.storageBusy }
        fixture.act { assertFalse(it.readerVisible); assertNull(it.info); assertNull(it.error) }

        val alias = File(fixture.context.root, "recoverable-alias.html").apply { writeText(text) }
        fixture.act { it.open(Uri.fromFile(alias)) }
        fixture.await { it.documentHash == hash && it.info != null && !it.loading && it.info!!.pageCount > 0 && !it.storageBusy }
        val viewport = ViewportState(2f, .25f, .75f, 2)
        fixture.act { it.updateViewport(viewport); it.flushDocumentState(); it.open(shared.uri) }
        fixture.await { it.currentTitle == "Shared.html" && it.info != null && !it.loading && it.info!!.pageCount > 0 && !it.storageBusy }
        fixture.act { assertEquals(viewport, it.viewport) }
        assertEquals(Uri.fromFile(alias).toString(), fixture.store.get(hash)!!.metadata.sourceUri)
        assertEquals(hash, fixture.activeHash())
        fixture.launch(resume = true)
        fixture.await { it.documentHash == hash && it.info != null && !it.loading && it.info!!.pageCount > 0 }
        fixture.act { assertEquals(viewport, it.viewport); assertTrue(it.readerVisible) }
        } finally { shared.close() }
    }

    @Test fun failedReferenceCleanupKeepsItsWarningAndDoesNotResumeADeletedCopy() = Fixture().use { fixture ->
        val retainedBody = "Retained-only fixture " + UUID.randomUUID()
        val retainedText = "<html><body>$retainedBody</body></html>"
        val (hash, imported) = fixture.retained(retainedText)
        val original = File(fixture.context.root, "original.html").apply { writeText("<html><body>Original remains " + UUID.randomUUID() + "</body></html>") }
        val (aliasHash, aliasImport) = fixture.retained(original.readText(), original)
        fixture.launch()
        fixture.act { it.reopenDocument(hash) }
        fixture.await { it.documentHash == hash && it.bitmap != null && !it.pageLoading && !it.storageBusy }
        assertEquals(hash, fixture.activeHash())
        LibraryCatalog(fixture.context).use { catalog ->
            assertEquals("Opening a proven retained copy must publish its hash-bound Library entry",
                hash, requireNotNull(catalog.location(imported.toString())).hash)
        }
        val databasePath = fixture.context.getDatabasePath("documents.db").path
        SQLiteDatabase.openDatabase(databasePath, null, SQLiteDatabase.OPEN_READWRITE).use { database ->
            database.execSQL("CREATE TRIGGER block_import_clear BEFORE UPDATE OF import_uri ON documents " +
                "WHEN OLD.import_uri IS NOT NULL AND NEW.import_uri IS NULL BEGIN SELECT RAISE(ABORT,'fixture import clear blocked'); END")
        }
        fixture.act { it.clearRetainedCopies() }
        fixture.await { !it.storageBusy && it.copyUsageCount == 0 }
        fixture.act {
            assertEquals("Saved copies could not be refreshed.", it.storageWarning)
            assertNotNull(it.info)
            assertNotNull(it.bitmap)
            assertFalse(it.textMode)
            it.showReader(false)
            it.showReader(true)
            it.loadText()
        }
        fixture.await { it.text != null }
        fixture.act { assertEquals(retainedBody, it.text) }
        assertNull(fixture.activeHash())
        assertEquals(imported.toString(), fixture.store.get(hash)!!.metadata.importUri)
        assertEquals(aliasImport.toString(), fixture.store.get(aliasHash)!!.metadata.importUri)
        assertEquals(2, fixture.sources.pendingEvictions().size)
        assertFalse(File(requireNotNull(imported.path)).exists())
        assertFalse(File(requireNotNull(aliasImport.path)).exists())

        // A surviving original reference stays eligible despite its copy's delayed invalidation.
        fixture.act { it.reopenDocument(aliasHash) }
        fixture.await { it.documentHash == aliasHash && it.info != null && !it.loading && it.info!!.pageCount > 0 && !it.storageBusy }
        assertEquals(aliasHash, fixture.activeHash())
        SQLiteDatabase.openDatabase(databasePath, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("DROP TRIGGER block_import_clear")
        }
        fixture.act { reader ->
            val field = ReaderModel::class.java.getDeclaredField("library").apply { isAccessible = true }
            (field.get(reader) as ReaderLibrary).close()
            reader.refreshStorageUsage()
        }
        fixture.await { !it.storageBusy }
        fixture.act { assertEquals("Saved copies could not be refreshed.", it.storageWarning) }
        assertEquals("Closed library cleanup must leave durable receipts for retry", 2, fixture.sources.pendingEvictions().size)
        fixture.launch()
        fixture.await { !it.storageBusy && it.storageWarning == null && it.recentDocuments.any { saved -> saved.hash == aliasHash } }
        assertTrue(fixture.sources.pendingEvictions().isEmpty())
        LibraryCatalog(fixture.context).use { catalog ->
            assertNull("A removed copy must not remain as a Library card", catalog.location(imported.toString()))
            assertNull(catalog.location(aliasImport.toString()))
        }
        assertNull(fixture.store.get(hash)!!.metadata.importUri)
        assertNull(fixture.store.get(aliasHash)!!.metadata.importUri)
        assertEquals(aliasHash, fixture.activeHash())

        // Simulate death before an obsolete active marker could be cleared.
        assertTrue(fixture.context.getSharedPreferences("reader", 0).edit().putString("activeDocument", hash).commit())
        fixture.launch(resume = true)
        fixture.await { !it.restoringLastDocument && !it.storageBusy }
        fixture.act { assertFalse(it.readerVisible); assertNull(it.info); assertNull(it.error) }
        assertNull(fixture.activeHash())

        // Copy publication can precede the matching metadata update after a process restart.
        val (replacementHash, replacement) = fixture.retained(retainedText, publishState = false)
        assertEquals(hash, replacementHash)
        assertNull(fixture.store.get(hash)!!.metadata.importUri)
        assertTrue(fixture.context.getSharedPreferences("reader", 0).edit().putString("activeDocument", hash).commit())
        fixture.launch(resume = true)
        fixture.await { it.documentHash == hash && it.info != null && !it.loading && it.info!!.pageCount > 0 }
        fixture.act { assertEquals(replacement, it.source); assertTrue(it.readerVisible) }
        assertEquals(replacement.toString(), fixture.store.get(hash)!!.metadata.importUri)
        assertEquals(hash, fixture.activeHash())
    }

    @Test fun reopenedPasswordChallengeSurvivesMissingFallbackAndRetriesTheSameDocument() {
        assumeTrue(Build.VERSION.SDK_INT >= 35)
        Fixture().use { fixture ->
            val file = File(fixture.context.root, "locked.pdf")
            instrumentation.context.assets.open("encrypted.pdf").use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toHexString()
            fixture.store.open(hash, DocumentMetadata("locked.pdf", "application/pdf", file.length(),
                Uri.fromFile(File(fixture.context.root, "missing.pdf")).toString(), Uri.fromFile(file).toString()))
            fixture.launch()
            fixture.act { it.reopenDocument(hash) }
            fixture.await(allowError = true) { !it.loading && it.error.orEmpty().contains("password") }
            var challenge = 0L
            fixture.act {
                assertNull(it.source)
                challenge = it.documentId
                val displayed = requireNotNull(it.error)
                val retryDisplayedPassword = { it.retryWithPassword("incorrect", challenge, displayed) }
                it.showError("No readable document was provided.")
                retryDisplayedPassword()
                assertEquals("A stale password button must preserve the current request", challenge, it.documentId)
                assertFalse(it.loading)
                assertEquals("No readable document was provided.", it.error)
                it.showError(displayed)
                retryDisplayedPassword()
                assertTrue("The displayed challenge still accepts a password retry", it.loading)
            }
            fixture.await(allowError = true) { !it.loading && it.error.orEmpty().contains("password") }
            fixture.act {
                val current = it.documentId
                it.retryWithPassword("alldoc-test", challenge)
                assertEquals("A stale dialog must not reopen a later request", current, it.documentId)
                it.retryWithPassword("alldoc-test", current)
            }
            fixture.await { it.documentHash == hash && it.bitmap != null }
            fixture.launch(resume = true)
            fixture.await(allowError = true) { !it.loading && it.error.orEmpty().contains("password") }
            fixture.act {
                val identity = it.documentId
                it.showReader(false)
                it.retryWithPassword("alldoc-test", identity)
                assertEquals("Home cancels the pending password retry", identity, it.documentId)
                it.reopenDocument(hash)
            }
            fixture.await(allowError = true) { !it.loading && it.error.orEmpty().contains("password") }
            fixture.act { it.retryWithPassword("alldoc-test") }
            fixture.await { it.documentHash == hash && it.bitmap != null }
        }
    }

    internal class ScopedContext(base: Context) : ContextWrapper(base) {
        val root = File(base.cacheDir, "reader-state-" + UUID.randomUUID()).apply { mkdirs() }
        override fun getApplicationContext(): Context = this
        override fun getDatabasePath(name: String): File = File(root, name)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
            SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, handler: DatabaseErrorHandler?): SQLiteDatabase =
            SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory, handler)
        override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
        override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
        override fun getSharedPreferences(name: String, mode: Int) =
            super.getSharedPreferences(root.name + "-" + name, mode)
        fun clear() {
            super.deleteSharedPreferences(root.name + "-reader")
            super.deleteSharedPreferences(root.name + "-app-appearance")
            root.deleteRecursively()
        }
    }
}
