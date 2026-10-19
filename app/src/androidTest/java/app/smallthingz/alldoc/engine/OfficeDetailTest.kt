package app.smallthingz.alldoc.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.CancellationSignal
import android.os.OperationCanceledException
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.smallthingz.alldoc.core.Document
import app.smallthingz.alldoc.render.DocRenderCore
import app.smallthingz.alldoc.theme.RasterRegion
import app.smallthingz.alldoc.theme.ReadingMode
import app.smallthingz.alldoc.theme.ThemeSettings
import java.io.File
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OfficeDetailTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val original = ThemeSettings(ReadingMode.ORIGINAL)

    @Test fun officeDetailRerendersNativelyAndReusesItsBoundedCache() {
        DocRenderCore.configure(context)
        for (name in listOf("sample.docx", "sample.pptx", "sample.xlsx")) {
            val file = File.createTempFile("office-detail-", ".fixture", context.cacheDir)
            try {
                instrumentation.context.assets.open("office/$name").use { input -> file.outputStream().use { input.copyTo(it) } }
                OfficeSession(file, name, "application/octet-stream", Document(name.substringAfterLast('.'), warnings = listOf("Text and structure preview; page layout, drawings and embedded objects are omitted.", "Retained semantic warning")), CancellationSignal()).use { session ->
                    assertEquals("VISUAL_BEST_EFFORT", session.info.fidelity)
                    assertFalse(session.info.warnings.any { it.startsWith("Text and structure preview;") })
                    assertTrue(session.info.warnings.contains("Retained semantic warning"))
                    val cache = OfficeSession::class.java.getDeclaredField("pages").apply { isAccessible = true }.get(session) as Map<*, *>
                    if (name.endsWith(".xlsx")) {
                        assertTrue(session.info.supportsSheets)
                        assertEquals("Opening a workbook must not paginate its print area", 0, session.info.pageCount)
                        assertTrue(cache.isEmpty())
                        assertFalse(requireNotNull(session.workbook(CancellationSignal())).sheets.isEmpty())
                        assertThrows(OperationCanceledException::class.java) {
                            session.printInfo(CancellationSignal().apply { cancel() })
                        }
                        assertEquals(0, session.info.pageCount)
                    }
                    val count = session.printInfo(CancellationSignal()).pageCount
                    assertTrue(count > 0)
                    val initial = requireNotNull(cache[0])
                    assertEquals(1400, initial.javaClass.getDeclaredField("side").apply { isAccessible = true }.getInt(initial))
                    val firstPage = initial.javaClass.getDeclaredField("page").apply { isAccessible = true }.get(initial) as DocRenderCore.RenderedPage
                    val baseWidth = minOf(1080, firstPage.width)
                    // A request covered by actual cached pixels must not launch another native read.
                    val parked = File(file.parentFile, file.name + ".parked")
                    assertFalse(parked.exists())
                    assertTrue(file.renameTo(parked))
                    val base = try { session.renderPage(0, baseWidth, original, CancellationSignal()) }
                        finally { assertTrue("Restore the owned fixture", parked.renameTo(file)) }
                    try {
                        assertSame("Covered widths reuse the native base receipt", initial, cache[0])
                        assertEquals(baseWidth, base.width)
                        val viewport = session.renderPage(0, 1080, original, CancellationSignal())
                        try {
                            assertEquals("Portrait pages need enough pixels at fit width", 1080, viewport.width)
                            if (firstPage.width < 1080) assertNotSame("A short portrait base must refine", initial, cache[0])
                            else assertSame("Landscape fit pages keep their warm base", initial, cache[0])
                        } finally { viewport.recycle() }
                        val detail = session.renderPage(0, 2160, original, CancellationSignal())
                        try {
                            assertNotSame("2160 detail must replace the native base receipt", initial, cache[0])
                            val upgraded = requireNotNull(cache[0])
                            assertEquals(DocRenderCore.SIDE_LIMIT, upgraded.javaClass.getDeclaredField("side").apply { isAccessible = true }.getInt(upgraded))
                            assertTrue("$name detail remained at the base resolution", detail.width > 1400)
                            assertTrue(detail.width > base.width && detail.height > base.height)
                            assertTrue(detail.width.toLong() * detail.height <= 8_000_000)
                            val enlarged = Bitmap.createScaledBitmap(base, detail.width, detail.height, true)
                            try { assertFalse("$name detail merely enlarged the base bitmap", enlarged.sameAs(detail)) }
                            finally { enlarged.recycle() }
                        } finally { detail.recycle() }
                    } finally { base.recycle() }
                    val cached = requireNotNull(cache[0])
                    val repeated = session.renderPage(0, 2160, original, CancellationSignal())
                    repeated.recycle()
                    assertSame("Same detail must reuse encoded output", cached, cache[0])
                    val smaller = session.renderPage(0, 320, original, CancellationSignal())
                    smaller.recycle()
                    assertSame("Smaller requests must reuse the detail tier", cached, cache[0])
                    assertTrue(cache.size <= 3)
                    assertTrue(cache.values.sumOf { entry ->
                        (entry!!.javaClass.getDeclaredField("page").apply { isAccessible = true }.get(entry) as DocRenderCore.RenderedPage).encodedBytes()
                    } <= 4 * 1024 * 1024)
                    assertEquals(count, session.info.pageCount)
                    assertThrows(OperationCanceledException::class.java) {
                        session.renderPage(0, 2400, original, CancellationSignal().apply { cancel() })
                    }
                    assertSame("Cancelled refinement must retain the usable cache", cached, cache[0])
                }
            } finally { file.delete() }
        }
    }

    @Test fun sparseWorkbookOpensWithoutLayingOutItsLargePrintArea() {
        DocRenderCore.configure(context)
        val file = File.createTempFile("office-sparse-", ".fixture", context.cacheDir)
        try {
            java.util.zip.ZipInputStream(instrumentation.context.assets.open("office/sample.xlsx")).use { input ->
                java.util.zip.ZipOutputStream(file.outputStream()).use { output ->
                    while (true) {
                        val entry = input.nextEntry ?: break
                        output.putNextEntry(java.util.zip.ZipEntry(entry.name))
                        if (entry.name == "xl/worksheets/sheet1.xml") {
                            output.write(("<worksheet xmlns='http://schemas.openxmlformats.org/spreadsheetml/2006/main'>" +
                                "<dimension ref='A1:AZ12000'/><sheetData><row r='1'><c r='A1' t='inlineStr'><is><t>Start</t></is></c></row>" +
                                "<row r='12000'><c r='AZ12000'><v>42</v></c></row></sheetData></worksheet>").toByteArray())
                        } else input.copyTo(output)
                        output.closeEntry(); input.closeEntry()
                    }
                }
            }
            OfficeSession(file, "sparse.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                Document("XLSX"), CancellationSignal()).use { session ->
                assertEquals(0, session.info.pageCount)
                val workbook = requireNotNull(session.workbook(CancellationSignal()))
                val first = workbook.sheets.first()
                val geometry = requireNotNull(session.sheetGeometry(first.index, CancellationSignal()))
                assertTrue(geometry.sheet.rowCount >= 12000)
                assertTrue(geometry.sheet.columnCount >= 52)
                val viewport = SheetViewport(first.index, 0.0, 0.0, 1.0, 720, 720)
                val image = session.renderSheet(viewport, original, CancellationSignal())
                try { assertEquals(720, image.width); assertEquals(720, image.height) }
                finally { image.recycle() }
                assertEquals("Grid rendering must not initialize print pages", 0, session.info.pageCount)
                val cache = OfficeSession::class.java.getDeclaredField("pages").apply { isAccessible = true }.get(session) as Map<*, *>
                assertTrue(cache.isEmpty())
            }
        } finally { file.delete() }
    }

    @Test fun officeRegionsRasterizeTheRequestedContentWithoutReplacingFullPageState() {
        DocRenderCore.configure(context)
        for (name in listOf("sample.docx", "sample.pptx", "sample.xlsx")) {
            val file = File.createTempFile("office-region-", ".fixture", context.cacheDir)
            try {
                instrumentation.context.assets.open("office/$name").use { input -> file.outputStream().use { input.copyTo(it) } }
                OfficeSession(file, name, "application/octet-stream", Document(name.substringAfterLast('.')), CancellationSignal()).use { session ->
                    val pages = session.printInfo(CancellationSignal()).pageCount
                    val aspect = session.pageAspect(0, CancellationSignal())
                    val cache = OfficeSession::class.java.getDeclaredField("pages").apply { isAccessible = true }.get(session) as Map<*, *>
                    val full = requireNotNull(cache[0])
                    val fullHints = session.rasterRegions(0, CancellationSignal())
                    val region = PageRegion(.05f, .05f, .25f, .25f, 800, (800 * aspect).roundToInt())
                    val detail = requireNotNull(session.renderRegion(0, region, original, CancellationSignal()))
                    try {
                        assertEquals(region.width, detail.width)
                        assertEquals(region.height, detail.height)
                        assertTrue(detail.allocationByteCount <= PageRegion.MAX_PIXELS * 4)
                        val other = requireNotNull(session.renderRegion(0,
                            region.copy(left = .55f, top = .55f, right = .75f, bottom = .75f), original, CancellationSignal()))
                        try { assertFalse("$name must render the selected region, not a cached full page", detail.sameAs(other)) }
                        finally { other.recycle() }
                    } finally { detail.recycle() }
                    assertEquals(pages, session.pageCount(CancellationSignal()))
                    assertEquals(aspect, session.pageAspect(0, CancellationSignal()))
                    assertEquals(fullHints, session.rasterRegions(0, CancellationSignal()))
                    assertSame("Viewport output cannot replace the full-page cache", full, cache[0])
                    assertThrows(OperationCanceledException::class.java) {
                        session.renderRegion(0, region, original, CancellationSignal().apply { cancel() })
                    }
                    assertSame(full, cache[0])
                    if (name.endsWith(".pptx")) {
                        val settings = ThemeSettings(ReadingMode.ADAPTIVE)
                        session.renderPage(0, 320, settings, CancellationSignal()).recycle()
                        val contextField = OfficeSession::class.java.getDeclaredField("themeContext").apply { isAccessible = true }
                        val policy = requireNotNull(contextField.get(session))
                        val source = session.renderPage(0, 320, original, CancellationSignal())
                        val parked = File(file.parentFile, file.name + ".parked")
                        assertFalse(parked.exists())
                        assertTrue(file.renameTo(parked))
                        try {
                            session.themePage(0, source, settings, CancellationSignal()).recycle()
                        } finally { assertTrue("Restore the owned fixture", parked.renameTo(file)) }
                        assertSame("The same page/settings must reuse its full-source classification", policy, contextField.get(session))
                        assertSame("Theme probes cannot replace the full-page receipt", full, cache[0])
                        assertEquals(fullHints, session.rasterRegions(0, CancellationSignal()))
                        val cancelled = session.renderPage(0, 320, original, CancellationSignal())
                        assertThrows(OperationCanceledException::class.java) {
                            session.themePage(0, cancelled, settings, CancellationSignal().apply { cancel() })
                        }
                        assertTrue("Cancelled theming releases its adopted bitmap", cancelled.isRecycled)
                        assertSame(policy, contextField.get(session))
                        val retained = requireNotNull(session.pageThemeContext(0, settings, CancellationSignal()))
                        session.renderPage(1, 320, settings, CancellationSignal()).recycle()
                        val nextPolicy = requireNotNull(session.pageThemeContext(1, settings, CancellationSignal()))
                        assertNotSame(retained, nextPolicy)
                        assertNull("The accessor must not recreate an evicted page classification",
                            session.pageThemeContext(0, settings, CancellationSignal()))
                        requireNotNull(session.renderRegionWithContext(0, region, settings, CancellationSignal(), retained)).recycle()
                        assertSame("Detail must use its retained base classification without preparing another probe",
                            nextPolicy, session.pageThemeContext(1, settings, CancellationSignal()))
                    }
                }
            } finally { file.delete() }
        }
    }

    @Test fun noteMetadataTracksLazyPrintResolutionWithoutLosingItsCapabilities() {
        var calls = 0
        val delegate = object : DocumentSession {
            override var info = DocumentInfo("Workbook", "application/octet-stream", "XLSX", 0, true,
                "VISUAL_BEST_EFFORT", supportsSheets = true)
            override fun pageCount(signal: CancellationSignal): Int {
                signal.throwIfCanceled(); calls++
                info = info.copy(pageCount = 7, warnings = listOf("Print layout warning"))
                return 7
            }
            override fun text(signal: CancellationSignal) = ""
            override fun json(signal: CancellationSignal) = "{}"
            override fun renderPage(index: Int, width: Int, settings: ThemeSettings, signal: CancellationSignal): Bitmap = error("Not needed")
            override fun close() = Unit
        }
        withDocumentNotes(delegate, File(context.cacheDir, "metadata-only.xlsx")).use { session ->
            assertEquals(0, session.info.pageCount)
            assertEquals(0, calls)
            val print = session.printInfo(CancellationSignal())
            assertEquals(7, print.pageCount)
            assertTrue(print.supportsNotes && print.supportsSheets)
            assertEquals(listOf("Print layout warning"), print.warnings)
            assertEquals(print, session.info)
            assertEquals(1, calls)
        }
    }

    @Test fun embeddedDocumentPreviewUsesAvailableSourceDetailWithoutUpscaling() {
        val file = File.createTempFile("embedded-preview-", ".jpg", context.cacheDir)
        try {
            val source = Bitmap.createBitmap(2400, 1600, Bitmap.Config.ARGB_8888)
            try {
                source.eraseColor(Color.WHITE)
                val canvas = Canvas(source)
                val ink = Paint().apply { color = Color.BLACK; strokeWidth = 2f }
                for (x in 0 until source.width step 9) canvas.drawLine(x.toFloat(), 0f, x.toFloat(), source.height.toFloat(), ink)
                file.outputStream().use { assertTrue(source.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
            } finally { source.recycle() }
            ImageSession(file, "Saved Keynote preview", "image/jpeg", CancellationSignal()).use { session ->
                assertNull("No decode solely to discover hints", session.rasterRegions(0, CancellationSignal()))
                for ((requested, expected) in listOf(1000 to 1000, 2000 to 2000, 4096 to 2400)) {
                    val image = session.renderPage(0, requested, original, CancellationSignal())
                    try {
                        assertEquals(expected, image.width)
                        assertFalse(image.hasAlpha())
                        assertEquals(listOf(RasterRegion(0f, 0f, 1f, 1f)), session.rasterRegions(0, CancellationSignal()))
                        assertTrue(image.width.toLong() * image.height <= 8_000_000)
                    } finally { image.recycle() }
                }
                val parked = File(file.parentFile, file.name + ".parked")
                assertFalse(parked.exists())
                assertTrue(file.renameTo(parked))
                try {
                    assertEquals("Hints reuse decoded opacity without reopening the preview",
                        listOf(RasterRegion(0f, 0f, 1f, 1f)), session.rasterRegions(0, CancellationSignal()))
                    assertThrows(OperationCanceledException::class.java) {
                        session.rasterRegions(0, CancellationSignal().apply { cancel() })
                    }
                } finally { assertTrue("Restore the owned fixture", parked.renameTo(file)) }
                assertThrows(OperationCanceledException::class.java) {
                    session.renderPage(0, 2000, original, CancellationSignal().apply { cancel() })
                }
            }
        } finally { file.delete() }
    }

    @Test fun transparentEmbeddedPreviewDoesNotClaimAnOpaqueRasterFootprint() {
        val file = File.createTempFile("embedded-alpha-", ".png", context.cacheDir)
        try {
            val source = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888)
            try {
                source.eraseColor(Color.TRANSPARENT)
                Canvas(source).drawRect(4f, 4f, 28f, 20f, Paint().apply { color = Color.WHITE })
                file.outputStream().use { assertTrue(source.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { source.recycle() }
            ImageSession(file, "Saved transparent preview", "image/png", CancellationSignal()).use { session ->
                assertNull(session.rasterRegions(0, CancellationSignal()))
                val image = session.renderPage(0, 32, original, CancellationSignal())
                try {
                    assertTrue(image.hasAlpha())
                    assertEquals(0, Color.alpha(image.getPixel(0, 0)))
                    assertEquals(Color.WHITE, image.getPixel(16, 12))
                    assertNull("Opacity is uncertain, so AUTO must retain its unknown-input path",
                        session.rasterRegions(0, CancellationSignal()))
                } finally { image.recycle() }
            }
        } finally { file.delete() }
    }
}
