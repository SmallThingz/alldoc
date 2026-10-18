package app.smallthingz.alldoc.engine

import android.graphics.Bitmap
import android.os.CancellationSignal
import android.os.OperationCanceledException
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.smallthingz.alldoc.core.Block
import app.smallthingz.alldoc.core.Document
import app.smallthingz.alldoc.core.Section
import app.smallthingz.alldoc.theme.DocumentColors
import app.smallthingz.alldoc.theme.ReadingMode
import app.smallthingz.alldoc.theme.ThemeSettings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SemanticSpreadsheetTest {
    private val original = ThemeSettings(ReadingMode.ORIGINAL)
    private fun document(vararg cells: Block, format: String = "ODS") =
        Document(format, sections = listOf(Section("Sheet", "sheet", cells.toList())))
    private fun cell(row: Int, column: Int, text: String = "Saved value") = Block("cell", text, row, column)
    private fun pixels(image: Bitmap) = IntArray(image.width * image.height).also {
        image.getPixels(it, 0, image.width, 0, 0, image.width, image.height)
    }

    @Test fun sparseSheetKeepsFarCoordinatesAndBlankSpaceWithoutInventingFormatting() {
        val source = Document("ODS", warnings = listOf("Cached formula values"), sections = listOf(
            Section("Metadata"), Section("Far", "sheet", listOf(cell(1_048_575, 16_383))),
            Section("Empty", "sheet")))
        val workbook = SemanticSpreadsheet(source, CancellationSignal())
        assertEquals(listOf(1, 2), workbook.info.sheets.map { it.index })
        val far = workbook.geometry(1)
        assertEquals(1_048_576, far.sheet.rowCount); assertEquals(16_384, far.sheet.columnCount)
        assertEquals(1_048_576, far.sheet.usedRows); assertEquals(16_384, far.sheet.usedColumns)
        assertEquals(1_048_575, far.rowAt(far.rowEdge(1_048_575)))
        assertEquals(16_383, far.columnAt(far.columnEdge(16_383)))
        val empty = workbook.geometry(2).sheet
        assertEquals(0, empty.usedRows); assertEquals(0, empty.usedColumns)
        assertEquals(far.sheet.rowCount, empty.rowCount)
        assertTrue(workbook.info.warnings.contains("Cached formula values"))
        assertTrue(workbook.info.warnings.any { "merged cells" in it && "not preserved" in it })
        val legacy = SemanticSpreadsheet(document(cell(65_535, 255), format = "XLS"), CancellationSignal()).geometry(0)
        assertEquals(65_536, legacy.sheet.rowCount); assertEquals(256, legacy.sheet.columnCount)
    }

    @Test fun farCellPixelsRemainIdenticalToOriginAtHighZoomAndFractionalOffsets() {
        val signal = CancellationSignal()
        val near = SemanticSpreadsheet(document(cell(0, 0)), signal)
        val far = SemanticSpreadsheet(document(cell(1_048_575, 16_383)), signal)
        for (scale in listOf(1.0, 8.0, 32.0)) for (mode in listOf(ReadingMode.ORIGINAL, ReadingMode.ADAPTIVE)) {
            val width = minOf(2400, (110 * scale).toInt())
            val height = (25 * scale).toInt()
            val viewport = SheetViewport(0, .125, .125, scale, width, height)
            val settings = ThemeSettings(mode)
            val expected = near.render(viewport, settings, signal)
            val actual = far.render(viewport.copy(x = 16_383 * 120.0 + .125, y = 1_048_575 * 28.0 + .125), settings, signal)
            try { assertArrayEquals("large coordinates at $scale, $mode", pixels(expected), pixels(actual)) }
            finally { expected.recycle(); actual.recycle() }
        }
    }

    @Test fun visibleColumnLookupMatchesSparseResultWithoutMovingOrMergingCells() {
        val signal = CancellationSignal()
        val target = 12_345
        val denseRow = (16_383 downTo 0).map { cell(9, it, if (it == target) "Visible" else "Other") }
        val full = SemanticSpreadsheet(Document("ODS", sections = listOf(Section("Sheet", "sheet", denseRow))), signal)
        val sparse = SemanticSpreadsheet(document(cell(9, target, "Visible")), signal)
        val viewport = SheetViewport(0, target * 120.0, 9 * 28.0, 4.0, 480, 112)
        val expected = sparse.render(viewport, original, signal)
        val actual = full.render(viewport, original, signal)
        try { assertArrayEquals(pixels(expected), pixels(actual)) }
        finally { expected.recycle(); actual.recycle() }
        val blank = sparse.render(viewport.copy(x = (target + 10) * 120.0, y = 50 * 28.0), original, signal)
        try {
            assertEquals(DocumentColors.palette(original).background, blank.getPixel(40, 40))
        } finally { blank.recycle() }
    }

    @Test fun invalidCoordinatesAndCancellationCannotAllocateAWholeGrid() {
        val cancelled = CancellationSignal().apply { cancel() }
        try { SemanticSpreadsheet(document(cell(0, 0)), cancelled); fail("Cancelled construction") }
        catch (_: OperationCanceledException) { }
        for (invalid in listOf(cell(Int.MAX_VALUE, 0), cell(0, 16_384), cell(-1, 0))) {
            try { SemanticSpreadsheet(document(invalid), CancellationSignal()); fail("Invalid coordinates") }
            catch (_: IllegalArgumentException) { }
        }
        val tooMany = Document("ODS", sections = List(1024) { Section("Metadata") } + Section("Beyond contract", "sheet"))
        try { SemanticSpreadsheet(tooMany, CancellationSignal()); fail("Unrenderable sheet ID") }
        catch (_: IllegalArgumentException) { }
        val sheet = SemanticSpreadsheet(document(cell(0, 0)), CancellationSignal())
        try { sheet.render(SheetViewport(0, 0.0, 0.0, 1.0, 120, 28), original, cancelled); fail("Cancelled rendering") }
        catch (_: OperationCanceledException) { }
    }

    @Test fun truncatedSemanticGridCarriesTheExcerptWarningInEveryMetadataView() {
        val excerpt = "Document excerpt: remaining content omitted."
        for (format in listOf("XLS", "XLSX", "ODS")) for (existing in listOf(emptyList(), listOf(excerpt))) {
            val source = document(cell(0, 0), format = format).copy(truncated = true, warnings = existing)
            val signal = CancellationSignal()
            TextSession("Excerpt.$format", "application/octet-stream", source, signal).use { session ->
                assertEquals(1, session.info.warnings.count { it == excerpt })
                assertEquals(1, session.textInfo.warnings.count { it == excerpt })
                assertEquals(1, session.printInfo(signal).warnings.count { it == excerpt })
                assertEquals(1, requireNotNull(session.workbook(signal)).warnings.count { it == excerpt })
                assertTrue(session.text(signal).startsWith("[$excerpt]"))
            }
        }
        val complete = SemanticSpreadsheet(document(cell(0, 0)), CancellationSignal())
        assertFalse(complete.info.warnings.contains(excerpt))
    }

    @Test fun textSessionKeepsPrintAndExportsSeparateFromTheWorksheetViewport() {
        val source = document(cell(4, 3, "Cached formula result"), format = "XLS")
        val signal = CancellationSignal()
        TextSession("Legacy.xls", "application/vnd.ms-excel", source, signal).use { session ->
            assertTrue(session.info.supportsSheets)
            assertEquals(5, requireNotNull(session.workbook(signal)).sheets.single().usedRows)
            assertEquals(source.toPlainText(), session.text(signal))
            assertEquals(source.toJson(), session.json(signal))
            assertTrue(session.pageCount(signal) > 0)
            val grid = session.renderSheet(SheetViewport(0, 3 * 120.0, 4 * 28.0, 1.0, 120, 28), original, signal)
            val print = session.renderPrintPage(0, 320, signal)
            try { assertEquals(120, grid.width); assertEquals(28, grid.height); assertEquals(320, print.width); assertTrue(print.height > print.width) }
            finally { grid.recycle(); print.recycle() }
        }
    }
}
