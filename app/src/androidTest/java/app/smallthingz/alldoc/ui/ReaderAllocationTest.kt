package app.smallthingz.alldoc.ui

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.net.Uri
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.smallthingz.alldoc.engine.DocumentInfo
import app.smallthingz.alldoc.engine.DocumentNotes
import app.smallthingz.alldoc.engine.DocumentNote
import app.smallthingz.alldoc.engine.DocumentOutlineEntry
import app.smallthingz.alldoc.engine.DocumentSession
import app.smallthingz.alldoc.sources.DocumentSources
import app.smallthingz.alldoc.state.DocumentStore
import app.smallthingz.alldoc.theme.ThemeSettings
import java.io.Closeable
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderAllocationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync { action() }

    @Test fun pageTextAndContentsAllocationFailuresAllowRetryWithoutClosingTheDocument() {
        for (operation in listOf("page", "text", "contents")) {
            val closes = AtomicInteger()
            Fixture(closes).use { fixture ->
                val attempts = AtomicInteger()
                fixture.session.before = { kind ->
                    if (kind == operation && attempts.getAndIncrement() == 0)
                        throw OutOfMemoryError("Controlled $operation allocation failure")
                }
                fun start() = main {
                    when (operation) {
                        "page" -> fixture.reader.setRenderWidth(if (attempts.get() == 0) 720 else 721)
                        "text" -> if (!fixture.reader.textMode) fixture.reader.setTextMode(true) else fixture.reader.loadText()
                        else -> fixture.reader.loadContents()
                    }
                }
                start()
                fixture.await {
                    val failed = if (operation == "contents")
                        it.contentsError == "Not enough memory to load contents." && it.error == null
                    else it.error == "Not enough memory."
                    failed && !it.loading && !it.pageLoading && !it.textLoading && !it.contentsLoading
                }
                main {
                    assertNotNull(fixture.reader.info)
                    assertEquals(fixture.hash, fixture.reader.documentHash)
                    assertEquals(0, closes.get())
                    if (operation == "contents") assertNull(fixture.reader.error)
                    else fixture.reader.dismissError()
                }
                start()
                fixture.await { model ->
                    model.error == null && when (operation) {
                        "page" -> model.bitmap != null && !model.pageLoading
                        "text" -> model.text == "Recovered text" && !model.textLoading
                        else -> model.outline.size == 1 && !model.contentsLoading && model.contentsError == null
                    }
                }
                main { fixture.reader.bitmap?.let { assertFalse("Published page must remain valid", it.isRecycled) } }
                assertEquals(2, attempts.get())
                assertEquals(0, closes.get())
            }
            assertEquals("The session closes exactly once after its model is cleared", 1, closes.get())
        }
    }

    @Test fun staleWorkerFailuresCannotSettleAReplacementTaskOrDocument() {
        for (failure in listOf("allocation", "android", "io")) for (replaceDocument in listOf(false, true)) {
            val closes = AtomicInteger()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            Fixture(closes).use { fixture ->
                try {
                    val attempts = AtomicInteger()
                    var lateImage: Bitmap? = null
                    fun pause() { entered.countDown(); check(release.await(15, TimeUnit.SECONDS)) }
                    if (failure == "android") fixture.session.afterRender = { image ->
                        if (attempts.getAndIncrement() == 0) { lateImage = image; pause() }
                    } else fixture.session.before = { operation ->
                        if (operation == "page" && attempts.getAndIncrement() == 0) {
                            pause()
                            if (failure == "allocation") throw OutOfMemoryError("Controlled stale render allocation failure")
                            throw java.io.IOException("Controlled stale render failure")
                        }
                    }
                    lateinit var oldJob: Job
                    main {
                        fixture.reader.setRenderWidth(720)
                        val field = ReaderModel::class.java.getDeclaredField("rendering").apply { isAccessible = true }
                        val task = requireNotNull(field.get(fixture.reader))
                        oldJob = task.javaClass.getDeclaredField("job").apply { isAccessible = true }.get(task) as Job
                    }
                    assertTrue(entered.await(5, TimeUnit.SECONDS))
                    main { fixture.reader.setRenderWidth(721) }
                    main {
                        assertTrue("Superseding must cancel the old job", oldJob.isCancelled)
                        assertTrue("The replacement waits without blocking UI", fixture.reader.pageLoading)
                        assertNull(fixture.reader.bitmap)
                        assertNull(fixture.reader.error)
                    }
                    assertEquals("Blocked native rendering must remain serialized", 1, attempts.get())
                    if (replaceDocument) {
                        val next = File(fixture.context.root, "next.html").apply { writeText("<html><body>New document</body></html>") }
                        main { fixture.reader.open(Uri.fromFile(next)) }
                        fixture.await { it.currentTitle == "next.html" && it.info != null && !it.loading }
                        main {
                            assertFalse("Replacement text documents open as pages", fixture.reader.textMode)
                            assertTrue("The new page waits for the serialized old worker", fixture.reader.pageLoading)
                            assertNull(fixture.reader.bitmap)
                            assertNull(fixture.reader.error)
                        }
                    }
                    assertEquals("The session remains leased while its old worker is blocked", 0, closes.get())
                    release.countDown()
                    runBlocking { withTimeout(15_000) { oldJob.join() } }
                    lateImage?.let { assertTrue("Canceled theme work must recycle its unpublished bitmap", it.isRecycled) }
                    if (replaceDocument) fixture.await {
                        closes.get() == 1 && it.bitmap != null && !it.pageLoading
                    }
                    else {
                        fixture.await { it.bitmap != null && !it.pageLoading }
                        assertEquals(0, closes.get())
                    }
                    main {
                        assertNull(fixture.reader.error)
                        assertFalse(fixture.reader.loading)
                        assertFalse(fixture.reader.pageLoading)
                        if (replaceDocument) {
                            assertEquals("next.html", fixture.reader.currentTitle)
                            assertFalse(fixture.reader.textMode)
                            assertFalse("The replacement document page remains valid", requireNotNull(fixture.reader.bitmap).isRecycled)
                        }
                        else {
                            assertEquals(fixture.hash, fixture.reader.documentHash)
                            assertFalse("The replacement page remains valid", requireNotNull(fixture.reader.bitmap).isRecycled)
                        }
                    }
                } finally { release.countDown() }
            }
            assertEquals("Every session closes exactly once", 1, closes.get())
        }
    }

    @Test fun liveWorkerFailuresRemainVisibleAndRetryable() {
        for (failure in listOf(android.os.OperationCanceledException("Controlled active renderer failure"),
            java.io.IOException("Controlled active renderer failure"))) {
            val closes = AtomicInteger()
            Fixture(closes).use { fixture ->
                val attempts = AtomicInteger()
                fixture.session.before = { operation ->
                    if (operation == "page" && attempts.getAndIncrement() == 0)
                        throw failure
                }
                main { fixture.reader.setRenderWidth(720) }
                fixture.await { it.error == "Controlled active renderer failure" && !it.pageLoading && 0 in it.failedPages }
                main {
                    assertEquals(fixture.hash, fixture.reader.documentHash)
                    assertEquals(0, closes.get())
                    fixture.reader.dismissError()
                    fixture.reader.retryPage(0)
                }
                fixture.await { it.bitmap != null && !it.pageLoading && it.error == null }
                assertEquals(2, attempts.get())
            }
            assertEquals(1, closes.get())
        }
    }

    @Test fun visibleWindowRenderingHasABoundedCacheAndDoesNotRepeatUnchangedRequests() {
        val closes = AtomicInteger()
        Fixture(closes, pages = 12).use { fixture ->
            fixture.session.renderSize = 1024 to 4096 // 16 MiB inputs force downsampling to the shared byte budget.
            main {
                fixture.reader.setAppearance(ThemeSettings(app.smallthingz.alldoc.theme.ReadingMode.ORIGINAL))
                fixture.reader.requestVisiblePages(0..10)
                fixture.reader.setRenderWidth(720)
            }
            val task = ReaderModel::class.java.getDeclaredField("rendering").apply { isAccessible = true }.get(fixture.reader)
            val job = task.javaClass.getDeclaredField("job").apply { isAccessible = true }
            fun settled() = fixture.await { reader ->
                (job.get(task) as? Job)?.isActive != true && !reader.pageLoading && reader.bitmap != null
            }
            settled()
            val calls = fixture.session.pages.toList()
            assertTrue("Only the visible window and adjacent pages should render", calls.size in 11..13)
            assertTrue("Every requested visible page must render", calls.containsAll((0..10).toList()))
            assertEquals("A page is rendered at most once in a stable window", calls.size, calls.distinct().size)
            lateinit var previousPage: Bitmap
            main {
                val images = fixture.reader.pageImages.values.map { it.bitmap }
                assertTrue("Every requested visible page must remain ready", fixture.reader.pageImages.keys.containsAll((0..10).toList()))
                assertTrue("Published image memory must stay within 48 MiB", images.sumOf { it.allocationByteCount.toLong() } <= 48L * 1024 * 1024)
                previousPage = requireNotNull(fixture.reader.bitmap)
                repeat(30) { fixture.reader.requestVisiblePages(0..10) }
            }
            settled()
            assertEquals("Repeated viewport updates must not invoke native rendering", calls, fixture.session.pages.toList())
            main { fixture.reader.goToPage(8) }
            fixture.await { it.page == 8 && it.bitmap != null && !it.pageLoading }
            settled()
            main {
                assertFalse("Previously published pages must not be recycled while a view can retain them", previousPage.isRecycled)
                assertTrue("The current page must be retained", 8 in fixture.reader.pageImages)
                assertTrue("Distant pages must leave the visible window cache", 0 !in fixture.reader.pageImages)
                assertTrue(fixture.reader.pageImages.values.sumOf { it.bitmap.allocationByteCount.toLong() } <= 48L * 1024 * 1024)
                assertNull(fixture.reader.error)
            }
        }
        assertEquals(1, closes.get())
    }

    @Test fun revisitingPagesKeepsVisibleAspectRatiosWhenTheCacheIsFull() {
        val closes = AtomicInteger()
        Fixture(closes, pages = 500).use { fixture ->
            val remember = ReaderModel::class.java.getDeclaredMethod("rememberAspect", Int::class.javaPrimitiveType,
                Float::class.javaPrimitiveType).apply { isAccessible = true }
            main {
                val reader = fixture.reader
                fun aspect(index: Int) { remember.invoke(reader, index, .5625f) }
                (listOf(100, 101, 99) + (200..452)).forEach(::aspect)
                assertEquals(256, reader.pageAspects.size)
                val unchanged = reader.pageAspects
                aspect(101)
                assertSame("Equal ratios must not allocate or publish another map", unchanged, reader.pageAspects)
                reader.goToPage(101)
                reader.updatePagePosition(100, .375f)
                reader.requestVisiblePages(100..102)
                val pose = reader.viewport
                listOf(101, 100, 102, 103, 99).forEach(::aspect)
                assertEquals(256, reader.pageAspects.size)
                for (index in 99..103) {
                    assertEquals("Visible and adjacent page $index must keep its real ratio", .5625f,
                        requireNotNull(reader.pageAspects[index]), 0f)
                }
                assertEquals(101, reader.page)
                assertEquals(100, reader.anchorPage)
                assertEquals(.375f, reader.pageFraction, 0f)
                assertEquals(pose, reader.viewport)

                // A full render window still leaves an unprotected victim for an unrelated insertion.
                reader.updateCurrentPage(200)
                reader.requestVisiblePages(200..453)
                (199..454).forEach(::aspect)
                assertEquals((199..454).toSet(), reader.pageAspects.keys)
                aspect(499)
                assertEquals("The cache stays bounded even with 256 protected pages",
                    (199..454).toSet(), reader.pageAspects.keys)
                assertTrue("This cache test must not start native page rendering", fixture.session.pages.isEmpty())
            }
        }
        assertEquals(1, closes.get())
    }

    @Test fun resizingKeepsPublishedPagesUntilTheCurrentUpgradeIsReady() {
        val closes = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        Fixture(closes).use { fixture ->
            try {
                main {
                    fixture.reader.setAppearance(ThemeSettings(app.smallthingz.alldoc.theme.ReadingMode.ORIGINAL))
                    fixture.reader.setRenderWidth(1024)
                }
                fixture.await { it.bitmap != null && !it.pageLoading }
                lateinit var original: Bitmap
                main {
                    original = requireNotNull(fixture.reader.bitmap)
                    fixture.reader.setRenderWidth(720)
                    assertSame("Shrinking must reuse the published page", original, fixture.reader.bitmap)
                    assertFalse(fixture.reader.pageLoading)
                }
                assertEquals(listOf(1024), fixture.session.widths.toList())

                val upgrades = AtomicInteger()
                var stale: Bitmap? = null
                fixture.session.afterRender = { image ->
                    if (upgrades.getAndIncrement() == 0) {
                        stale = image
                        entered.countDown()
                        check(release.await(15, TimeUnit.SECONDS))
                    }
                }
                main { fixture.reader.setRenderWidth(1600) }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                main {
                    assertSame("Keep the usable page during an upgrade", original, fixture.reader.bitmap)
                    fixture.reader.setRenderWidth(1400)
                    assertSame("Superseding an upgrade must not blank the page", original, fixture.reader.bitmap)
                    assertFalse(fixture.reader.pageLoading)
                }
                release.countDown()
                fixture.await { it.bitmap != null && it.bitmap !== original && !it.pageLoading }
                main {
                    assertEquals(listOf(1024, 1600, 1400), fixture.session.widths.toList())
                    assertEquals(1400, requireNotNull(fixture.reader.pageImages[0]).renderWidth)
                    assertTrue("Stale unpublished work must be recycled", requireNotNull(stale).isRecycled)
                    assertFalse("Previously published pages remain valid for Compose", original.isRecycled)
                    assertFalse(requireNotNull(fixture.reader.bitmap).isRecycled)
                    assertNull(fixture.reader.error)
                }
            } finally { release.countDown() }
        }
        assertEquals(1, closes.get())
    }

    @Test fun cancelledTextWorkersCannotSettleAReenteredSameDocument() {
        for (exit in listOf("home", "background", "pages", "normal-view", "presentation-view")) {
            val closes = AtomicInteger()
            val oldEntered = CountDownLatch(1)
            val oldRelease = CountDownLatch(1)
            val nextEntered = CountDownLatch(1)
            val nextRelease = CountDownLatch(1)
            Fixture(closes).use { fixture ->
                try {
                    val attempts = AtomicInteger()
                    fixture.session.before = { operation -> if (operation == "text") {
                        if (attempts.getAndIncrement() == 0) {
                            oldEntered.countDown(); check(oldRelease.await(15, TimeUnit.SECONDS))
                            throw java.io.IOException("Late cancelled extraction must not become a reader error")
                        } else {
                            nextEntered.countDown(); check(nextRelease.await(15, TimeUnit.SECONDS))
                        }
                    } }
                    lateinit var oldJob: Job
                    main {
                        fixture.reader.setTextMode(true)
                        val field = ReaderModel::class.java.getDeclaredField("extracting").apply { isAccessible = true }
                        val task = requireNotNull(field.get(fixture.reader))
                        oldJob = task.javaClass.getDeclaredField("job").apply { isAccessible = true }.get(task) as Job
                    }
                    assertTrue(oldEntered.await(5, TimeUnit.SECONDS))
                    main {
                        when (exit) {
                            "home" -> fixture.reader.showReader(false)
                            "background" -> fixture.reader.setAppVisible(false)
                            "normal-view" -> fixture.reader.choosePageView(false)
                            "presentation-view" -> fixture.reader.choosePageView(true)
                            else -> fixture.reader.setTextMode(false)
                        }
                        assertTrue("Leaving text demand cancels its job", oldJob.isCancelled)
                        assertFalse(fixture.reader.textLoading)
                        assertNull(fixture.reader.text)
                        assertNull(fixture.reader.error)
                        if (exit == "home" || exit == "background") {
                            fixture.reader.loadText()
                            assertFalse("Hidden/background explicit extraction must remain idle", fixture.reader.textLoading)
                        }
                    }
                    assertEquals("Hidden/background extraction must not restart", 1, attempts.get())
                    main {
                        when (exit) {
                            "home" -> fixture.reader.showReader(true)
                            "background" -> fixture.reader.setAppVisible(true)
                            else -> fixture.reader.setTextMode(true)
                        }
                        assertTrue("Reentering the same document starts replacement extraction", fixture.reader.textLoading)
                    }
                    oldRelease.countDown()
                    runBlocking { withTimeout(15_000) { oldJob.join() } }
                    assertTrue(nextEntered.await(5, TimeUnit.SECONDS))
                    main {
                        assertTrue("Old finally must not settle the replacement's loading state", fixture.reader.textLoading)
                        assertNull(fixture.reader.text)
                        assertNull(fixture.reader.error)
                        assertEquals(fixture.hash, fixture.reader.documentHash)
                    }
                    assertEquals("An outstanding session remains leased", 0, closes.get())
                    nextRelease.countDown()
                    fixture.await { it.text == "Recovered text" && !it.textLoading }
                    main { assertNull(fixture.reader.error); assertTrue(fixture.reader.readerVisible) }
                    assertEquals(2, attempts.get())
                } finally { oldRelease.countDown(); nextRelease.countDown() }
            }
            assertEquals("All extraction leases eventually release", 1, closes.get())
        }
    }

    @Test fun lateMetadataFailuresStayInlineAfterClosingOrLeavingTheReader() {
        for (kind in listOf("notes", "contents"))
            for (exit in listOf("visible", "pane", "home", "background")) for (allocation in listOf(false, true)) {
                val closes = AtomicInteger()
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1)
                Fixture(closes).use { fixture ->
                    try {
                        val attempts = AtomicInteger()
                        fixture.session.before = { operation ->
                            if (operation == kind && attempts.getAndIncrement() == 0) {
                                entered.countDown()
                                check(release.await(15, TimeUnit.SECONDS))
                                if (allocation) throw OutOfMemoryError("Controlled $kind allocation failure")
                                throw java.io.IOException("Controlled $kind failure")
                            }
                        }
                        fun start() = main {
                            if (kind == "notes") {
                                fixture.reader.setNotesVisible(false)
                                fixture.reader.setNotesVisible(true)
                            } else fixture.reader.loadContents()
                        }
                        main { if (kind == "notes") assertTrue(requireNotNull(fixture.reader.info).supportsNotes) }
                        start()
                        assertTrue(entered.await(5, TimeUnit.SECONDS))
                        lateinit var pending: Job
                        main {
                            val field = ReaderModel::class.java.getDeclaredField(if (kind == "notes") "annotating" else "outlining")
                                .apply { isAccessible = true }
                            val task = requireNotNull(field.get(fixture.reader))
                            pending = task.javaClass.getDeclaredField("job").apply { isAccessible = true }.get(task) as Job
                            when (exit) {
                                "pane" -> if (kind == "notes") fixture.reader.setNotesVisible(false)
                                "home" -> fixture.reader.showReader(false)
                                "background" -> fixture.reader.setAppVisible(false)
                            }
                            // Contents sheet visibility has no model flag; closing it leaves this worker running.
                            assertNull(fixture.reader.error)
                        }
                        val paused = exit == "home" || exit == "background"
                        if (paused) assertTrue("Leaving the reader must cancel optional metadata work", pending.isCancelled)
                        release.countDown()
                        runBlocking { withTimeout(15_000) { pending.join() } }
                        val warningText = if (allocation) "Not enough memory to load $kind." else "Controlled $kind failure"
                        main {
                            if (kind == "notes") {
                                assertFalse(fixture.reader.notesLoading)
                                if (paused) assertTrue("Cancelled metadata must not publish a late warning", fixture.reader.notes.warnings.isEmpty())
                                else assertTrue(fixture.reader.notes.warnings.any { it.contains(warningText) })
                            } else {
                                assertFalse(fixture.reader.contentsLoading)
                                if (paused) assertNull("Cancelled metadata must not publish a late error", fixture.reader.contentsError)
                                else assertTrue(fixture.reader.contentsError?.contains(warningText) == true)
                            }
                        }
                        main {
                            assertNull("Late optional metadata errors must not open a modal on the new destination", fixture.reader.error)
                            assertEquals(fixture.hash, fixture.reader.documentHash)
                            assertEquals(exit != "home", fixture.reader.readerVisible)
                            if (kind == "notes" && exit == "pane") assertFalse(fixture.reader.notesVisible)
                        }
                        assertEquals(1, attempts.get())
                        main {
                            if (exit == "home") fixture.reader.showReader(true)
                            if (exit == "background") fixture.reader.setAppVisible(true)
                        }
                        start()
                        fixture.await {
                            if (kind == "notes") !it.notesLoading && it.notes.entries.any { note -> note.text == "Recovered notes" }
                            else !it.contentsLoading && it.outline.any { entry -> entry.title == "Chapter" }
                        }
                        main {
                            if (kind == "notes") assertTrue(fixture.reader.notes.warnings.isEmpty())
                            else assertNull(fixture.reader.contentsError)
                            assertNull(fixture.reader.error)
                            assertTrue(fixture.reader.readerVisible)
                        }
                        assertEquals(2, attempts.get())
                    } finally { release.countDown() }
                }
                assertEquals("Metadata workers release their session lease", 1, closes.get())
            }
    }

    private class FakeSession(override val info: DocumentInfo, private val closes: AtomicInteger) : DocumentSession {
        @Volatile var before: (String) -> Unit = {}
        @Volatile var afterRender: (Bitmap) -> Unit = {}
        var renderSize = 24 to 32
        val pages = CopyOnWriteArrayList<Int>()
        val widths = CopyOnWriteArrayList<Int>()
        override fun renderPage(index: Int, width: Int, settings: ThemeSettings, signal: CancellationSignal): Bitmap {
            pages += index
            widths += width
            before("page")
            signal.throwIfCanceled()
            val image = Bitmap.createBitmap(renderSize.first, renderSize.second, Bitmap.Config.ARGB_8888)
            try { afterRender(image); return image } catch (failure: Throwable) { image.recycle(); throw failure }
        }
        override fun text(signal: CancellationSignal): String { before("text"); signal.throwIfCanceled(); return "Recovered text" }
        override fun notes(signal: CancellationSignal): DocumentNotes {
            before("notes"); signal.throwIfCanceled()
            return DocumentNotes(listOf(DocumentNote("Recovered notes")))
        }
        override fun json(signal: CancellationSignal) = "{}"
        override fun outline(signal: CancellationSignal): List<DocumentOutlineEntry> {
            before("contents"); signal.throwIfCanceled(); return listOf(DocumentOutlineEntry("Chapter", 0))
        }
        override fun close() { closes.incrementAndGet() }
    }

    private inner class Fixture(closes: AtomicInteger, pages: Int = 1) : Closeable {
        val context = ScopedContext(instrumentation.targetContext)
        private val store = DocumentStore(context)
        private val sources = DocumentSources(context)
        private val application = object : Application() { init { attachBaseContext(context) } }
        private val owner = ViewModelStore()
        lateinit var reader: ReaderModel
        lateinit var session: FakeSession
        lateinit var hash: String
        init {
            val file = File(context.root, "memory.pdf").also { writeReaderPdf(it, pages = pages) }
            main { reader = ReaderModel(application, store, sources); owner.put("reader", reader); reader.open(Uri.fromFile(file)) }
            await { it.info != null && !it.loading }
            main {
                hash = requireNotNull(reader.documentHash)
                val info = requireNotNull(reader.info)
                assertTrue(info.textAvailable)
                session = FakeSession(info, closes)
                // This fixed-size, failure-injecting fake exercises viewport workers only.
                // Native cover sizing and ownership are covered by DocumentPreviewsTest and LibraryPreviewsTest.
                ReaderModel::class.java.getDeclaredField("previewSaved").apply { isAccessible = true }
                    .setBoolean(reader, true)
                // Replace only the worker session; exercise the real model's normal public commands.
                val field = ReaderModel::class.java.getDeclaredField("current").apply { isAccessible = true }
                val previous = requireNotNull(field.get(reader))
                previous.javaClass.getDeclaredMethod("retire").apply { isAccessible = true }.invoke(previous)
                val constructor = previous.javaClass.getDeclaredConstructor(DocumentSession::class.java).apply { isAccessible = true }
                field.set(reader, constructor.newInstance(session))
            }
        }
        fun await(ready: (ReaderModel) -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 15_000
            while (SystemClock.uptimeMillis() < deadline) {
                var result = false
                main { result = ready(reader) }
                if (result) return
                SystemClock.sleep(25)
            }
            fail("Allocation recovery did not settle")
        }
        override fun close() {
            var job: Job? = null
            main { job = reader.viewModelScope.coroutineContext[Job]; owner.clear() }
            runBlocking { withTimeout(15_000) { job?.join() } }
            for (name in listOf("StateWorker", "CloseWorker")) {
                val field = ReaderModel::class.java.getDeclaredField(name).apply { isAccessible = true }
                (field.get(null) as ExecutorService).submit {}.get(15, TimeUnit.SECONDS)
            }
            store.close(); sources.close(); context.clear()
        }
    }

    private class ScopedContext(base: Context) : ContextWrapper(base) {
        val root = File(base.cacheDir, "reader-allocation-${UUID.randomUUID()}").apply { mkdirs() }
        override fun getApplicationContext(): Context = this
        override fun getDatabasePath(name: String): File = File(root, name)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
            SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, handler: DatabaseErrorHandler?): SQLiteDatabase =
            SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory, handler)
        override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
        override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences(root.name + "-" + name, mode)
        fun clear() { super.deleteSharedPreferences(root.name + "-reader"); root.deleteRecursively() }
    }
}
