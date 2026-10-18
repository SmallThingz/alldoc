package app.smallthingz.alldoc.oacp

import android.content.Intent
import android.graphics.Bitmap
import android.os.CancellationSignal
import android.os.OperationCanceledException
import app.smallthingz.alldoc.engine.DocumentInfo
import app.smallthingz.alldoc.engine.DocumentSession
import app.smallthingz.alldoc.engine.SheetInfo
import app.smallthingz.alldoc.engine.WorkbookInfo
import app.smallthingz.alldoc.theme.ImageTreatment
import app.smallthingz.alldoc.theme.ReadingMode
import app.smallthingz.alldoc.theme.ThemeSettings
import org.junit.Assert.*
import org.junit.Test
import org.oacp.android.Oacp

class CapabilitySheetTest {
    @Test fun fractionalViewportUsesActiveOriginalSheetIdWithoutPrintMetadata() {
        val request = parse("x" to .125, "y" to "0.625", "scale" to 1.25,
            "width" to 96, "height" to 80, "theme" to "system", "imageTreatment" to "auto")
        val session = Workbook()
        val resolved = requireNotNull(request.sheet).resolve(session, request.width, CancellationSignal(), false)
        assertEquals("render_sheet", request.capability)
        assertEquals("png", request.format)
        assertEquals(7, resolved.info.index)
        assertEquals(.125, resolved.viewport.x, 0.0)
        assertEquals(.625, resolved.viewport.y, 0.0)
        assertEquals(1.25, resolved.viewport.scale, 0.0)
        assertEquals(96, resolved.viewport.width)
        assertEquals(80, resolved.viewport.height)
        assertEquals(ReadingMode.ADAPTIVE_LIGHT, resolved.settings.mode)
        assertEquals(ImageTreatment.AUTO, resolved.settings.imageTreatment)
        assertEquals(1, session.workbookReads)
        assertEquals(0, session.closes)
        assertEquals("worksheet", resolved.metadata()["view"])
        assertEquals(false, resolved.metadata()["paginationKnown"])
        assertFalse(resolved.metadata().containsKey("pageCount"))
        assertEquals(listOf("Fixture warning"), resolved.warnings)
    }

    @Test fun explicitSheetIsAnOriginalIndexAndDefaultsStayBounded() {
        val request = parse("sheet" to 3, "imageTreatment" to "keep")
        val resolved = requireNotNull(request.sheet).resolve(Workbook(), request.width, CancellationSignal(), true)
        assertEquals(3, resolved.info.index)
        assertEquals(1200, resolved.viewport.width)
        assertEquals(800, resolved.viewport.height)
        assertEquals(ReadingMode.ORIGINAL, resolved.settings.mode)
        assertEquals(ImageTreatment.KEEP, resolved.settings.imageTreatment)
        val failure = assertThrows(CapabilityFailure::class.java) {
            requireNotNull(parse("sheet" to 1).sheet).resolve(Workbook(), 1200, CancellationSignal(), false)
        }
        assertEquals(Oacp.ERROR_INVALID_PARAMETERS, failure.code)
    }

    @Test fun invalidViewportAndThemeInputsFailBeforeOpening() {
        for (entry in listOf("x" to Double.NaN, "y" to Double.POSITIVE_INFINITY, "x" to -1.0,
            "x" to 1000000001.0, "scale" to .01, "scale" to 33.0, "width" to 2049,
            "height" to 63, "sheet" to 1024, "sheet" to 1.5, "theme" to "unknown", "imageTreatment" to "unknown")) {
            assertEquals(Oacp.ERROR_INVALID_PARAMETERS,
                assertThrows(CapabilityFailure::class.java) { parse(entry) }.code)
        }
    }

    @Test fun everyAdvertisedImageTreatmentHasAStableWireName() {
        for ((wire, expected) in listOf("auto" to ImageTreatment.AUTO, "keep" to ImageTreatment.KEEP, "all" to ImageTreatment.RECOLOR)) {
            val resolved = requireNotNull(parse("imageTreatment" to wire).sheet).resolve(Workbook(), 1200, CancellationSignal(), false)
            assertEquals(expected, resolved.settings.imageTreatment)
            assertEquals(wire, resolved.metadata()["imageTreatment"])
        }
    }

    @Test fun unavailableAndCancelledRequestsDoNotResolvePrintPages() {
        val request = requireNotNull(parse().sheet)
        assertEquals(Oacp.ERROR_UNSUPPORTED_STATE, assertThrows(CapabilityFailure::class.java) {
            request.resolve(Workbook(false), 1200, CancellationSignal(), false)
        }.code)
        val session = Workbook()
        assertThrows(OperationCanceledException::class.java) {
            request.resolve(session, 1200, CancellationSignal().apply { cancel() }, false)
        }
        assertEquals(0, session.workbookReads)
    }

    @Test fun legacyRenderPageKeepsItsOriginalContract() {
        val request = CapabilityRequest.parse(Intent("$PACKAGE.oacp.ACTION_RENDER_PAGE")
            .putExtra(Oacp.EXTRA_REQUEST_ID, "legacy-page").putExtra("page", 2), PACKAGE)
        assertEquals("render_page", request.capability)
        assertEquals(2, request.page)
        assertEquals("png", request.format)
        assertNull(request.sheet)
    }

    private fun parse(vararg values: Pair<String, Any>): CapabilityRequest {
        val intent = Intent("$PACKAGE.oacp.ACTION_RENDER_SHEET").putExtra(Oacp.EXTRA_REQUEST_ID, "worksheet-test")
        for ((key, value) in values) when (value) {
            is Int -> intent.putExtra(key, value)
            is Double -> intent.putExtra(key, value)
            is String -> intent.putExtra(key, value)
            else -> error("Unsupported test input")
        }
        return CapabilityRequest.parse(intent, PACKAGE)
    }

    private class Workbook(supported: Boolean = true) : DocumentSession {
        override val info = DocumentInfo("Workbook", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "XLSX", 0, true, "VISUAL_BEST_EFFORT", supportsSheets = supported)
        var workbookReads = 0
        var closes = 0
        override fun workbook(signal: CancellationSignal): WorkbookInfo {
            signal.throwIfCanceled(); workbookReads++
            return WorkbookInfo(listOf(3, 7).map { SheetInfo(it, "Sheet $it", 1000, 64, 40, 6, 7680.0, 28000.0) },
                7, listOf("Fixture warning"))
        }
        override fun pageCount(signal: CancellationSignal): Int = error("Print pagination must not run")
        override fun printInfo(signal: CancellationSignal): DocumentInfo = error("Print metadata must not run")
        override fun text(signal: CancellationSignal): String = error("Text export must not run")
        override fun json(signal: CancellationSignal): String = error("JSON export must not run")
        override fun renderPage(index: Int, width: Int, settings: ThemeSettings, signal: CancellationSignal): Bitmap =
            error("Page rendering must not run")
        override fun close() { closes++ }
    }
    private companion object { const val PACKAGE = "app.smallthingz.alldoc" }
}
