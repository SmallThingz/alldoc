package app.smallthingz.alldoc.engine

import android.graphics.Bitmap
import kotlin.math.floor

/** Reader geometry is independent of the workbook's print pages and print settings. */
data class SheetInfo(val index: Int, val name: String, val rowCount: Int, val columnCount: Int,
    val usedRows: Int, val usedColumns: Int, val width: Double, val height: Double,
    val showGridlines: Boolean = true, val showHeadings: Boolean = true, val rightToLeft: Boolean = false,
    val freezeRows: Int = 0, val freezeColumns: Int = 0)
data class WorkbookInfo(val sheets: List<SheetInfo>, val activeSheet: Int, val warnings: List<String> = emptyList())

class SheetGeometry(val sheet: SheetInfo, private val rowEdges: DoubleArray? = null,
    private val columnEdges: DoubleArray? = null, val rowHeight: Double = 28.0, val columnWidth: Double = 120.0) {
    fun rowEdge(index: Int): Double = rowEdges?.get(index.coerceIn(0, sheet.rowCount)) ?: index.coerceIn(0, sheet.rowCount) * rowHeight
    fun columnEdge(index: Int): Double = columnEdges?.get(index.coerceIn(0, sheet.columnCount)) ?: index.coerceIn(0, sheet.columnCount) * columnWidth
    fun rowAt(position: Double): Int = at(position, rowEdges, rowHeight, sheet.rowCount)
    fun columnAt(position: Double): Int = at(position, columnEdges, columnWidth, sheet.columnCount)
    private fun at(position: Double, edges: DoubleArray?, step: Double, count: Int): Int {
        if (!position.isFinite()) return 0
        if (edges == null) return floor(position / step).toInt().coerceIn(0, count - 1)
        var low = 0; var high = edges.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (edges[middle] <= position) low = middle + 1 else high = middle
        }
        return (low - 1).coerceIn(0, count - 1)
    }
}
data class SheetViewport(val sheet: Int, val x: Double, val y: Double, val scale: Double, val width: Int, val height: Int) {
    fun checked(): SheetViewport = apply {
        require(sheet in 0..1023 && x.isFinite() && y.isFinite() && x in 0.0..1e9 && y in 0.0..1e9 &&
            scale.isFinite() && scale in MIN_SCALE..MAX_SCALE && width in 1..MAX_EDGE && height in 1..MAX_EDGE &&
            width.toLong() * height <= MAX_PIXELS) { "Invalid worksheet viewport" }
    }
    companion object {
        const val MIN_SCALE = .0625
        const val MAX_SCALE = 256.0
        const val MAX_EDGE = 4096
        const val MAX_PIXELS = 8_000_000L
    }
}
/** Published pixels are immutable and may remain in Compose while a replacement is rendered. */
data class SheetFrame(val bitmap: Bitmap, val viewport: SheetViewport)
