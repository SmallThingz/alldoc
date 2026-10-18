package app.smallthingz.alldoc.ui

import android.graphics.Bitmap
import androidx.compose.ui.unit.IntSize
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.CancellationSignal
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.smallthingz.alldoc.engine.DocumentInfo
import app.smallthingz.alldoc.engine.DocumentSession
import app.smallthingz.alldoc.engine.SheetGeometry
import app.smallthingz.alldoc.engine.SheetInfo
import app.smallthingz.alldoc.engine.SheetViewport
import app.smallthingz.alldoc.engine.WorkbookInfo
import app.smallthingz.alldoc.sources.PreparedDocument
import app.smallthingz.alldoc.state.DocumentMetadata
import app.smallthingz.alldoc.state.DocumentState
import app.smallthingz.alldoc.state.DocumentViewMode
import app.smallthingz.alldoc.state.CopySettings
import app.smallthingz.alldoc.state.SheetPosition
import app.smallthingz.alldoc.theme.ReadingMode
import app.smallthingz.alldoc.theme.ThemeSettings
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real model, cancellation and hash-bound state with a controllable worker session; no shared-device touches. */
@RunWith(AndroidJUnit4::class)
class ReaderWorksheetModelTest {
    private fun fixture() = ReaderPersistenceTest().Fixture().also { it.launch(width = 0) }
    private fun ReaderPersistenceTest.Fixture.file(name: String = "workbook.xlsx") =
        File(context.root, name).apply { writeText("Worksheet fixture " + UUID.randomUUID()) }

    private fun ReaderPersistenceTest.Fixture.open(session: FakeWorkbook, file: File, awaitSheet: Boolean = true) {
        val uri = Uri.fromFile(file)
        val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toHexString()
        val opener: suspend (CopySettings?, CancellationSignal) -> PreparedDocument = { _, signal ->
            signal.throwIfCanceled()
            PreparedDocument(hash, file.length(), file.name, session.info.mimeType, session, uri, null)
        }
        act { reader ->
            // Exercise the normal publication/restore path without depending on a native parser in scheduler tests.
            val method = ReaderModel::class.java.declaredMethods.single { it.name == "openDocument" }
            method.isAccessible = true
            method.invoke(reader, uri, null, opener)
        }
        await { assertNull(it.sheetsError); !it.loading && it.info != null && (!awaitSheet || it.sheetMode && it.sheetGeometry != null) }
    }

    @Test fun busyRenderKeepsOnlyTheNewestViewportAndRetainsPublishedPixels() = fixture().use { fixture ->
        val session = FakeWorkbook()
        fixture.open(session, fixture.file())
        val first = SheetViewport(3, 0.0, 0.0, 1.0, 64, 48)
        val latest = first.copy(x = 300.0)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        session.before = { request, signal ->
            if (request == first) { entered.countDown(); awaitRelease(release, signal) }
        }
        try {
            fixture.act { assertEquals(3, it.sheetPosition.sheet); it.requestSheetViewport(first) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            fixture.act { reader ->
                listOf(100.0, 200.0, 300.0).forEach { reader.requestSheetViewport(first.copy(x = it)) }
            }
            release.countDown()
            fixture.await { assertNull(it.sheetsError); it.sheetFrame?.viewport == latest && !it.sheetsLoading }
            assertEquals(listOf(first, latest), session.requests.toList())
            assertEquals(1, session.maximumActive.get())
            assertTrue("Published earlier frames must remain drawable during transitions", session.images.none { it.isRecycled })
            fixture.act { reader -> repeat(5) { reader.requestSheetViewport(latest) }; assertFalse(reader.sheetsLoading) }
            assertEquals(2, session.requests.size)
        } finally { release.countDown() }
    }

    @Test fun returningToPublishedViewportDropsQueuedAndLateWork() {
        for (fail in listOf(false, true)) fixture().use { fixture ->
            val session = FakeWorkbook()
            fixture.open(session, fixture.file())
            val first = SheetViewport(3, 0.0, 0.0, 1.0, 64, 48)
            val busy = first.copy(x = 300.0)
            val queued = first.copy(x = 600.0)
            fixture.act { it.requestSheetViewport(first) }
            fixture.await { it.sheetFrame?.viewport == first && !it.sheetsLoading }
            lateinit var retained: Bitmap
            fixture.act { retained = requireNotNull(it.sheetFrame).bitmap }
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            session.before = { request, signal ->
                if (request == busy) {
                    entered.countDown(); awaitRelease(release, signal)
                    if (fail) throw IOException("Viewport no longer requested")
                }
            }
            try {
                fixture.act { it.requestSheetViewport(busy) }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                fixture.act {
                    it.requestSheetViewport(queued)
                    it.requestSheetViewport(first)
                    assertSame(retained, it.sheetFrame?.bitmap)
                }
                release.countDown()
                fixture.await { assertNull(it.sheetsError); !it.sheetsLoading }
                fixture.act {
                    assertEquals(first, it.sheetFrame?.viewport)
                    assertSame("Returning to cached pixels must not rerender them", retained, it.sheetFrame?.bitmap)
                }
                assertEquals(listOf(first, busy), session.requests.toList())
                assertEquals(1, session.maximumActive.get())
                assertFalse(retained.isRecycled)
                assertTrue(session.images.filter { it !== retained }.all { it.isRecycled })
            } finally { release.countDown() }
        }
    }

    @Test fun failureFromThePreviousSheetCannotPoisonTheNewSelection() = fixture().use { fixture ->
        val session = FakeWorkbook()
        fixture.open(session, fixture.file())
        val first = SheetViewport(3, 0.0, 0.0, 1.0, 64, 48)
        val next = first.copy(sheet = 1)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        session.before = { request, signal ->
            if (request.sheet == 3) {
                entered.countDown(); awaitRelease(release, signal)
                throw IOException("Old sheet failed after selection changed")
            }
        }
        try {
            fixture.act { it.requestSheetViewport(first) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            fixture.act { it.selectSheet(1); it.requestSheetViewport(next); assertNull(it.sheetFrame) }
            release.countDown()
            fixture.await { assertNull(it.sheetsError); it.sheetFrame?.viewport == next && !it.sheetsLoading }
            fixture.act { assertEquals(1, it.sheetGeometry!!.sheet.index); assertEquals(1, it.sheetPosition.sheet) }
            assertEquals(listOf(first, next), session.requests.toList())
            assertEquals(1, session.maximumActive.get())
        } finally { release.countDown() }
    }

    @Test fun backgroundCancellationDropsLatePixelsAndPausesOffTheMainThread() = fixture().use { fixture ->
        val session = FakeWorkbook()
        fixture.open(session, fixture.file())
        val request = SheetViewport(3, 100.0, 200.0, 1.0, 64, 48)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        session.before = { _, signal ->
            entered.countDown()
            while (!release.await(10, TimeUnit.MILLISECONDS)) {
                if (signal.isCanceled) { cancelled.countDown(); break }
            }
            // A native call can finish just after cancellation. The model must discard its result.
        }
        try {
            fixture.act { it.requestSheetViewport(request) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            fixture.act { it.setAppVisible(false) }
            assertTrue(cancelled.await(5, TimeUnit.SECONDS))
            fixture.await { session.images.size == 1 && session.images.single().isRecycled && session.pauses.get() > 0 }
            fixture.act { assertNull(it.sheetFrame); assertFalse(it.sheetsLoading); assertNull(it.sheetsError) }
            assertFalse("Session shutdown must not block the UI thread", session.pausedOnMain.get())
            session.before = { _, _ -> }
            fixture.act { it.setAppVisible(true) }
            fixture.await { assertNull(it.sheetsError); it.sheetFrame?.viewport == request && !it.sheetsLoading }
            assertEquals(2, session.requests.size)
        } finally { release.countDown() }
    }

    @Test fun sheetPanAndZoomFollowTheHashAcrossAliasesAndModelRecreation() = fixture().use { fixture ->
        val file = fixture.file()
        val alias = File(fixture.context.root, "renamed.xlsx").also { file.copyTo(it) }
        val position = SheetPosition(3, 1_234_567.125, 29_000_000.125, 2.25f)
        fixture.open(FakeWorkbook(), file)
        var hash = ""
        var identity = 0L
        fixture.act {
            hash = requireNotNull(it.documentHash); identity = it.documentId
            it.updateSheetPosition(position); it.flushDocumentState()
        }
        fixture.stateBarrier()
        assertEquals(position, fixture.store.get(hash)!!.state.sheetPosition)
        fixture.open(FakeWorkbook(), alias)
        fixture.act {
            assertEquals(hash, it.documentHash); assertEquals(position, it.sheetPosition)
            it.updateSheetPosition(SheetPosition(1), identity)
            it.selectSheet(1, identity)
            assertEquals(position, it.sheetPosition)
        }
        fixture.launch(width = 0)
        fixture.open(FakeWorkbook(), alias)
        fixture.act { assertEquals(position, it.sheetPosition); assertEquals(hash, it.documentHash) }
    }

    @Test fun textAndBackgroundOpenReleaseResidentWorkbooksWithoutStartingGridWork() {
        for (savedText in listOf(true, false)) fixture().use { fixture ->
            val session = FakeWorkbook()
            val file = fixture.file()
            if (savedText) {
                val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toHexString()
                fixture.store.open(hash, DocumentMetadata(file.name, session.info.mimeType, file.length(), Uri.fromFile(file).toString()),
                    DocumentState(mode = DocumentViewMode.TEXT))
            } else fixture.act { it.setAppVisible(false) }
            fixture.open(session, file, awaitSheet = false)
            fixture.await { session.pauses.get() > 0 && (!savedText || it.text != null) }
            fixture.act { assertNull(it.sheetFrame); assertFalse(it.sheetsLoading) }
            assertFalse(session.pausedOnMain.get())
            assertEquals(0, session.workbookCalls.get())
            assertTrue(session.requests.isEmpty())
        }
    }

    @Test fun imageExportCapturesTheCurrentSheetPoseBeforeDestinationSelection() = fixture().use { fixture ->
        val session = FakeWorkbook()
        fixture.open(session, fixture.file())
        val request = worksheetRequest(SheetPosition(3), 320.0, 240.0, 1.5)
        fixture.act {
            it.requestSheetViewport(request)
            assertNull("An unknown visible extent must not export padded render bounds", it.prepareExport(ReaderModel.ExportKind.IMAGE))
            it.updateSheetViewport(IntSize(320, 240), 1.5f)
        }
        fixture.await { it.sheetFrame?.viewport == request && !it.sheetsLoading }
        val pose = SheetPosition(3, 500.125, 1000.25, 1.75f)
        val destination = File(fixture.context.root, "visible-sheet.png")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        session.before = { viewport, signal ->
            if (viewport.x == pose.x && viewport.y == pose.y) { entered.countDown(); awaitRelease(release, signal) }
        }
        try {
            fixture.act {
                assertEquals(0, it.info!!.pageCount)
                it.updateSheetPosition(pose)
                it.updateSheetViewport(IntSize(3840, 2160), 1.375f) // Rotation/resize precedes a fresh raster.
                assertEquals(request, it.sheetFrame?.viewport)
                assertTrue(requireNotNull(it.prepareExport(ReaderModel.ExportKind.IMAGE)).endsWith("-sheet-4.png"))
                it.updateSheetViewport(IntSize(480, 640), 2f) // Destination picker cannot change the snapshot.
                it.selectSheet(1) // Destination selection can finish after the reader navigated elsewhere.
                it.export(Uri.fromFile(destination))
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val pausedBeforeCompletion = session.pauses.get()
            fixture.act { it.setAppVisible(false) }
            release.countDown()
            fixture.await { !it.exporting && it.notice == "Export saved." && session.pauses.get() > pausedBeforeCompletion }
            assertFalse("Export must release its restarted worker off the UI thread", session.pausedOnMain.get())
            val exported = session.requests.last()
            assertEquals(pose.sheet, exported.sheet)
            assertEquals(pose.x, exported.x, 0.0)
            assertEquals(pose.y, exported.y, 0.0)
            assertEquals(2800, exported.width); assertEquals(1575, exported.height)
            val visibleScale = pose.zoom * 1.375
            assertEquals(3840 / visibleScale, exported.width / exported.scale, .000001)
            assertEquals(2160 / visibleScale, exported.height / exported.scale, .000001)
            val bitmap = requireNotNull(BitmapFactory.decodeFile(destination.path))
            try {
                assertEquals(2800, bitmap.width); assertEquals(1575, bitmap.height)
                assertEquals(Color.MAGENTA, bitmap.getPixel(bitmap.width / 2, bitmap.height / 2))
            } finally { bitmap.recycle() }
        } finally { release.countDown() }
    }

    private class FakeWorkbook : DocumentSession {
        override val info = DocumentInfo("Workbook", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "xlsx", 0, true, "STRUCTURED", supportsSheets = true)
        private val sheets = listOf(1, 3).map { index ->
            SheetInfo(index, "Sheet $index", 1_048_576, 16_384, 10, 4, 1_966_080.0, 29_360_128.0)
        }
        private val geometry = sheets.associate { it.index to SheetGeometry(it) }
        val requests = CopyOnWriteArrayList<SheetViewport>()
        val images = CopyOnWriteArrayList<Bitmap>()
        val workbookCalls = AtomicInteger()
        val maximumActive = AtomicInteger()
        val pauses = AtomicInteger()
        val pausedOnMain = AtomicBoolean()
        private val active = AtomicInteger()
        @Volatile var before: (SheetViewport, CancellationSignal) -> Unit = { _, _ -> }
        override fun workbook(signal: CancellationSignal): WorkbookInfo {
            signal.throwIfCanceled(); workbookCalls.incrementAndGet(); return WorkbookInfo(sheets, 3)
        }
        override fun sheetGeometry(index: Int, signal: CancellationSignal): SheetGeometry {
            signal.throwIfCanceled(); return requireNotNull(geometry[index])
        }
        override fun renderSheet(viewport: SheetViewport, settings: ThemeSettings, signal: CancellationSignal): Bitmap {
            signal.throwIfCanceled()
            // Idle cover work has its own tests; it deliberately renders in original colors.
            if (settings.mode == ReadingMode.ORIGINAL) return Bitmap.createBitmap(viewport.width, viewport.height, Bitmap.Config.ARGB_8888)
                .also { it.eraseColor(Color.MAGENTA) }
            val count = active.incrementAndGet()
            maximumActive.updateAndGet { maxOf(it, count) }
            requests += viewport
            try {
                before(viewport, signal)
                return Bitmap.createBitmap(viewport.width, viewport.height, Bitmap.Config.ARGB_8888).also {
                    it.eraseColor(Color.MAGENTA); images += it
                }
            } finally { active.decrementAndGet() }
        }
        override fun pauseSheets() { pausedOnMain.set(Looper.myLooper() == Looper.getMainLooper()); pauses.incrementAndGet() }
        override fun text(signal: CancellationSignal) = "Cell values"
        override fun json(signal: CancellationSignal) = "{}"
        override fun renderPage(index: Int, width: Int, settings: ThemeSettings, signal: CancellationSignal): Bitmap =
            error("Normal worksheet reading must not paginate")
        override fun close() = Unit
    }

    private fun awaitRelease(release: CountDownLatch, signal: CancellationSignal) {
        while (!release.await(10, TimeUnit.MILLISECONDS)) signal.throwIfCanceled()
    }
}
