package app.smallthingz.alldoc.ui

import android.graphics.Bitmap
import android.os.CancellationSignal
import android.os.OperationCanceledException
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.smallthingz.alldoc.engine.DocumentInfo
import app.smallthingz.alldoc.engine.DocumentSession
import app.smallthingz.alldoc.engine.SheetGeometry
import app.smallthingz.alldoc.engine.SheetInfo
import app.smallthingz.alldoc.engine.SheetViewport
import app.smallthingz.alldoc.engine.WorkbookInfo
import app.smallthingz.alldoc.theme.RasterRegion
import app.smallthingz.alldoc.theme.ReadingMode
import app.smallthingz.alldoc.theme.ThemeSettings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SheetPreviewTest {
    private class Workbook : DocumentSession {
        override val info = DocumentInfo("Sparse.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "XLSX", 0, true, "LAYOUT", supportsSheets = true)
        val sheet = SheetInfo(4, "Saved active sheet", 1_048_576, 16_384, 1, 1, 16_384 * 120.0, 1_048_576 * 28.0)
        val regions = listOf(RasterRegion(.1f, .2f, .3f, .4f))
        val requests = ArrayList<SheetViewport>()
        var last: Bitmap? = null
        var cancel = false
        var badSize = false
        var metadataFailure = false
        var closes = 0
        override fun workbook(signal: CancellationSignal) = WorkbookInfo(listOf(sheet.copy(index = 2), sheet), 4)
        override fun sheetGeometry(index: Int, signal: CancellationSignal): SheetGeometry {
            assertEquals(4, index); return SheetGeometry(sheet)
        }
        override fun renderSheet(viewport: SheetViewport, settings: ThemeSettings, signal: CancellationSignal): Bitmap {
            assertEquals(ReadingMode.ORIGINAL, settings.mode)
            requests += viewport
            return Bitmap.createBitmap(viewport.width + if (badSize) 1 else 0, viewport.height, Bitmap.Config.ARGB_8888).also {
                last = it
                if (cancel) signal.cancel()
            }
        }
        override fun sheetRasterRegions(viewport: SheetViewport, signal: CancellationSignal): List<RasterRegion>? {
            check(!metadataFailure) { "No matching worksheet mask" }
            assertEquals(requests.last(), viewport)
            return regions
        }
        override fun pageCount(signal: CancellationSignal): Int = error("Print layout must stay lazy")
        override fun pageAspect(index: Int, signal: CancellationSignal): Float = error("Print layout must stay lazy")
        override fun renderPage(index: Int, width: Int, settings: ThemeSettings, signal: CancellationSignal): Bitmap =
            error("Print layout must stay lazy")
        override fun text(signal: CancellationSignal) = ""
        override fun json(signal: CancellationSignal) = "{}"
        override fun close() { closes++ }
    }

    @Test fun workbookCoverUsesActiveGridAndItsExactMasksWithoutPrintLayout() {
        val session = Workbook()
        val cover = DocumentPreviews.nativeSheet(session, CancellationSignal(), 240, 320)
        try {
            assertEquals(4, session.requests.single().sheet)
            assertEquals(240, cover.image.width); assertEquals(320, cover.image.height)
            assertEquals(session.regions, cover.regions)
            assertFalse(cover.image.isRecycled)
            assertEquals(0, session.closes)
        } finally { cover.image.recycle(); session.close() }
    }

    @Test fun currentViewportKeepsFarDoubleOriginsAndBoundsTheCoverScale() {
        val session = Workbook()
        val viewport = SheetViewport(4, 16_000 * 120.0 + .125, 1_000_000 * 28.0 + .125, 8.0, 900, 1200)
        val cover = DocumentPreviews.nativeSheet(session, CancellationSignal(), viewport = viewport)
        try {
            val request = session.requests.single()
            assertEquals(viewport.x, request.x, 0.0); assertEquals(viewport.y, request.y, 0.0)
            assertEquals(1.28, request.scale, .000001)
            assertEquals(144, request.width); assertEquals(192, request.height)
        } finally { cover.image.recycle() }
        val tiny = DocumentPreviews.nativeSheet(session, CancellationSignal(), viewport = viewport.copy(scale = .0625))
        try {
            val request = session.requests.last()
            assertEquals(.0625, request.scale, 0.0)
            assertEquals(viewport.width, request.width); assertEquals(viewport.height, request.height)
            assertEquals(viewport.width / .0625, request.width / request.scale, 0.0)
            assertEquals(viewport.height / .0625, request.height / request.scale, 0.0)
            assertEquals(144, tiny.image.width); assertEquals(192, tiny.image.height)
            assertTrue(requireNotNull(session.last).isRecycled)
            assertEquals(session.regions, tiny.regions)
        } finally { tiny.image.recycle() }
        val wide = DocumentPreviews.nativeSheet(session, CancellationSignal(),
            viewport = viewport.copy(width = 2800, height = 100, scale = .0625))
        try {
            assertEquals(2800, session.requests.last().width); assertEquals(100, session.requests.last().height)
            assertEquals(144, wide.image.width); assertEquals(5, wide.image.height)
        } finally { wide.image.recycle(); session.close() }
    }

    @Test fun failedOrCancelledGridCoversRecycleOnlyTheirUnpublishedBitmap() {
        for (failure in 0..2) {
            val session = Workbook().apply {
                cancel = failure == 0; badSize = failure == 1; metadataFailure = failure == 2
            }
            try {
                if (session.cancel) assertThrows(OperationCanceledException::class.java) {
                    DocumentPreviews.nativeSheet(session, CancellationSignal())
                } else assertThrows(IllegalStateException::class.java) {
                    DocumentPreviews.nativeSheet(session, CancellationSignal())
                }
                assertTrue(requireNotNull(session.last).isRecycled)
                assertEquals(0, session.closes)
            } finally { session.close() }
        }
        val session = Workbook()
        assertThrows(OperationCanceledException::class.java) {
            DocumentPreviews.nativeSheet(session, CancellationSignal().apply { cancel() })
        }
        assertTrue(session.requests.isEmpty())
        session.close()
    }
}
