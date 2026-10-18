package app.smallthingz.alldoc.ui

import androidx.compose.ui.unit.IntSize
import app.smallthingz.alldoc.engine.SheetGeometry
import app.smallthingz.alldoc.engine.SheetInfo
import app.smallthingz.alldoc.state.SheetPosition
import org.junit.Assert.*
import org.junit.Test

class ReaderWorksheetMathTest {
    @Test fun zoomKeepsTheCellPointUnderTheFingersAndPanUsesDocumentUnits() {
        val before = SheetPosition(sheet = 2, x = 1000.0, y = 2000.0, zoom = 1f)
        val after = worksheetTransform(before, 10000.0, 10000.0, 600.0, 900.0, 2.0,
            100.0, 300.0, 0.0, 0.0, 2f)
        assertEquals(2, after.sheet)
        assertEquals(before.x + 100 / 2.0, after.x + 100 / 4.0, .0001)
        assertEquals(before.y + 300 / 2.0, after.y + 300 / 4.0, .0001)
        val panned = worksheetTransform(before, 10000.0, 10000.0, 600.0, 900.0, 2.0,
            0.0, 0.0, 40.0, -60.0, 1f)
        assertEquals(980.0, panned.x, .0001)
        assertEquals(2030.0, panned.y, .0001)
    }

    @Test fun boundsAndQuarterScaleDoNotExposeNegativePositions() {
        val after = worksheetTransform(SheetPosition(), 100.0, 200.0, 600.0, 900.0, 2.0,
            300.0, 450.0, 10000.0, -10000.0, .01f)
        assertEquals(.25f, after.zoom, 0f)
        assertEquals(0.0, after.x, .0001)
        assertEquals(0.0, after.y, .0001)
        assertEquals(1f, worksheetTransform(SheetPosition(), 1000.0, 2000.0, 600.0, 900.0, 2.0,
            0.0, 0.0, 0.0, 0.0, Float.NaN).zoom, 0f)
    }

    @Test fun quantizedRequestsCoverVisibleCellsAtPhoneAndLargeDisplaySizes() {
        for (width in listOf(360.0, 1080.0, 3840.0, 7680.0)) {
            for (zoom in listOf(.25f, 1f, 8f)) {
                val position = SheetPosition(1, 12345.0, 34567.0, zoom)
                val height = width * .6
                val request = worksheetRequest(position, width, height, 1.0)
                val visibleScale = worksheetScale(zoom, 1.0)
                assertTrue(request.width in 1..2800 && request.height in 1..2800)
                assertTrue(request.width.toLong() * request.height <= 8L * 1024 * 1024)
                assertTrue(request.scale in .0625..32.0)
                assertTrue(request.x <= position.x && request.y <= position.y)
                assertTrue(request.x + request.width / request.scale >= position.x + width / visibleScale)
                assertTrue(request.y + request.height / request.scale >= position.y + height / visibleScale)
            }
        }
        assertEquals(worksheetRequest(SheetPosition(x = 100.0), 800.0, 600.0, 1.0),
            worksheetRequest(SheetPosition(x = 101.0), 800.0, 600.0, 1.0))
    }

    @Test fun visibleExportsExcludeOverscanAndKeepDocumentExtentWhenDownsampled() {
        val pose = SheetPosition(3, 500.125, 1000.25, 1.75f)
        for (size in listOf(IntSize(320, 240), IntSize(1080, 1920), IntSize(3840, 2160))) {
            val density = 1.375
            val visible = requireNotNull(worksheetVisibleViewport(pose, size, density))
            val request = worksheetRequest(pose, size.width.toDouble(), size.height.toDouble(), density)
            assertEquals(pose.x, visible.x, 0.0); assertEquals(pose.y, visible.y, 0.0)
            assertTrue(visible.width <= 2800 && visible.height <= 2800)
            val scale = worksheetScale(pose.zoom, density)
            // Raster rounding can include less than one output pixel, never the render padding.
            assertEquals(size.width / scale, visible.width / visible.scale, 1.0 / visible.scale)
            assertEquals(size.height / scale, visible.height / visible.scale, 1.0 / visible.scale)
            assertTrue(request.width / request.scale > visible.width / visible.scale)
            assertTrue(request.height / request.scale > visible.height / visible.scale)
        }
        val rotated = requireNotNull(worksheetVisibleViewport(pose, IntSize(2160, 3840), 1.375))
        assertEquals(1575, rotated.width); assertEquals(2800, rotated.height)
        assertNull(worksheetVisibleViewport(pose, IntSize.Zero, 1.375))
        assertNull(worksheetVisibleViewport(pose, IntSize(1080, 0), 1.375))
        assertNull(worksheetVisibleViewport(pose, IntSize(1080, 1920), Double.NaN))
    }

    @Test fun farWorksheetCoordinatesKeepSubpixelPanPrecision() {
        val before = SheetPosition(3, 1_900_000.125, 29_000_000.125, 8f)
        val after = worksheetTransform(before, 1_966_080.0, 29_360_128.0, 600.0, 900.0, 4.0,
            0.0, 0.0, 1.0, -1.0, 1f)
        assertEquals(before.x - 1.0 / 32.0, after.x, .0000001)
        assertEquals(before.y + 1.0 / 32.0, after.y, .0000001)
    }

    @Test fun headersContinueAfterLongHiddenRunsAndSkipUnreadableBands() {
        val edges = DoubleArray(1005) { when { it == 0 -> 0.0; it <= 1001 -> 28.0; else -> (it - 1000) * 28.0 } }
        val geometry = SheetGeometry(SheetInfo(0, "Hidden rows", 1004, 2, 1004, 2, 240.0, 112.0), rowEdges = edges)
        val next = worksheetHeaderNext(0, geometry.rowEdge(0), geometry.rowEdge(1), 16.0, geometry::rowAt)
        assertEquals(1001, next)
        assertEquals(1002, worksheetHeaderNext(next, geometry.rowEdge(next), geometry.rowEdge(next + 1), 16.0, geometry::rowAt))
        assertEquals(40, worksheetHeaderNext(0, 0.0, .25, 10.0) { (it / .25).toInt() })
    }

    @Test fun coordinateLabelsAndScrollThumbsKeepTheirBoundaries() {
        assertEquals(listOf("A", "Z", "AA", "ZZ", "AAA", "XFD"),
            listOf(0, 25, 26, 701, 702, 16383).map(::worksheetColumn))
        assertNull(worksheetThumb(0.0, 100.0, 100.0, 600.0))
        val first = requireNotNull(worksheetThumb(-100.0, 100.0, 100000.0, 600.0))
        val last = requireNotNull(worksheetThumb(200000.0, 100.0, 100000.0, 600.0))
        assertEquals(0.0, first.first, .0001)
        assertEquals(24.0, first.second, .0001)
        assertEquals(600.0, last.first + last.second, .0001)
    }
}
