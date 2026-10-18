package app.smallthingz.alldoc.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.CancellationSignal
import app.smallthingz.alldoc.core.Block
import app.smallthingz.alldoc.core.Document
import app.smallthingz.alldoc.core.takeScalarSafe
import app.smallthingz.alldoc.theme.DocumentColors
import app.smallthingz.alldoc.theme.PaperTexture
import app.smallthingz.alldoc.theme.ReadingMode
import app.smallthingz.alldoc.theme.ThemeSettings
import java.util.TreeMap

/** Saved values retain sparse coordinates. No formulas, macros or links execute. */
internal class SemanticSpreadsheet(document: Document, signal: CancellationSignal) {
    private class Sheet(val geometry: SheetGeometry, val rows: TreeMap<Int, MutableList<Block>>)
    private val sheets = document.sections.withIndex().filter { it.value.kind == "sheet" }.map { (index, section) ->
        signal.throwIfCanceled()
        require(index in 0..1023) { "Too many worksheets" }
        val rowCount = if (document.format == "XLS") 65_536 else 1_048_576
        val columnCount = if (document.format == "XLS") 256 else 16_384
        val rows = TreeMap<Int, MutableList<Block>>()
        var usedRows = 0; var usedColumns = 0
        for ((offset, cell) in section.blocks.withIndex()) {
            if (offset % 256 == 0) signal.throwIfCanceled()
            if (cell.kind != "cell") continue
            require(cell.row in 0 until rowCount && cell.column in 0 until columnCount) { "Invalid worksheet cell" }
            rows.getOrPut(cell.row) { ArrayList() }.add(cell)
            usedRows = maxOf(usedRows, cell.row + 1); usedColumns = maxOf(usedColumns, cell.column + 1)
        }
        for (cells in rows.values) {
            signal.throwIfCanceled()
            cells.sortBy { it.column }
        }
        signal.throwIfCanceled()
        Sheet(SheetGeometry(SheetInfo(index, section.title, rowCount, columnCount, usedRows, usedColumns,
            columnCount * 120.0, rowCount * 28.0)), rows)
    }
    val info = WorkbookInfo(sheets.map { it.geometry.sheet }, sheets.firstOrNull()?.geometry?.sheet?.index ?: 0,
        (document.warnings + "Cell values only; styles, merged cells and drawing objects are not preserved." +
            if (document.truncated) listOf("Document excerpt: remaining content omitted.") else emptyList()).distinct())

    fun geometry(index: Int): SheetGeometry = sheets.first { it.geometry.sheet.index == index }.geometry

    fun render(viewport: SheetViewport, settings: ThemeSettings, signal: CancellationSignal): Bitmap {
        viewport.checked(); signal.throwIfCanceled()
        val sheet = sheets.first { it.geometry.sheet.index == viewport.sheet }
        val geometry = sheet.geometry
        val invert = settings.mode == ReadingMode.INVERT
        val colors = DocumentColors.palette(if (invert) settings.copy(mode = ReadingMode.ORIGINAL) else settings)
        val image = Bitmap.createBitmap(viewport.width, viewport.height, Bitmap.Config.ARGB_8888)
        try {
            image.eraseColor(colors.background)
            val canvas = Canvas(image)
            PaperTexture.background(canvas, image.width, image.height, settings)
            canvas.scale(viewport.scale.toFloat(), viewport.scale.toFloat())
            val right = minOf(geometry.sheet.width, viewport.x + viewport.width / viewport.scale)
            val bottom = minOf(geometry.sheet.height, viewport.y + viewport.height / viewport.scale)
            if (viewport.x < right && viewport.y < bottom) {
                val firstRow = geometry.rowAt(viewport.y); val lastRow = geometry.rowAt(bottom)
                val firstColumn = geometry.columnAt(viewport.x); val lastColumn = geometry.columnAt(right)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = colors.foreground; alpha = 32; strokeWidth = (1 / viewport.scale).toFloat()
                }
                // Subtract in Double first: large sheet coordinates cannot retain pixel precision in Float.
                for (row in firstRow..minOf(geometry.sheet.rowCount, lastRow + 1)) {
                    signal.throwIfCanceled()
                    val y = (geometry.rowEdge(row) - viewport.y).toFloat()
                    canvas.drawLine(0f, y, (right - viewport.x).toFloat(), y, paint)
                }
                for (column in firstColumn..minOf(geometry.sheet.columnCount, lastColumn + 1)) {
                    signal.throwIfCanceled()
                    val x = (geometry.columnEdge(column) - viewport.x).toFloat()
                    canvas.drawLine(x, 0f, x, (bottom - viewport.y).toFloat(), paint)
                }
                paint.alpha = 255; paint.textSize = 14f; paint.typeface = Typeface.DEFAULT
                for ((row, cells) in sheet.rows.subMap(firstRow, true, lastRow, true)) {
                    signal.throwIfCanceled()
                    var offset = lowerColumn(cells, firstColumn)
                    while (offset < cells.size && cells[offset].column <= lastColumn) {
                        if (offset % 128 == 0) signal.throwIfCanceled()
                        val cell = cells[offset++]
                        val x = (geometry.columnEdge(cell.column) - viewport.x).toFloat()
                        val y = (geometry.rowEdge(row) - viewport.y).toFloat()
                        val saved = canvas.save()
                        canvas.clipRect(x + 4, y, x + geometry.columnWidth.toFloat() - 4, y + geometry.rowHeight.toFloat())
                        canvas.drawText(cell.text.takeScalarSafe(256).replace('\n', ' ').replace('\r', ' '), x + 4, y + 19, paint)
                        canvas.restoreToCount(saved)
                    }
                }
            }
            signal.throwIfCanceled()
        } catch (failure: Throwable) { image.recycle(); throw failure }
        return if (invert) themed(image, settings, signal) else image
    }

    private fun lowerColumn(cells: List<Block>, column: Int): Int {
        var low = 0; var high = cells.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (cells[middle].column < column) low = middle + 1 else high = middle
        }
        return low
    }
}
