package app.smallthingz.alldoc.render

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.smallthingz.alldoc.engine.SheetGeometry
import app.smallthingz.alldoc.engine.SheetViewport
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class WorkbookWireTest {
    private fun sheet(index: Int = 3) = JSONObject().put("index", index).put("name", "Sheet $index")
        .put("rowCount", 4).put("columnCount", 2).put("usedRows", 3).put("usedColumns", 2)
        .put("width", 100.0).put("height", 30.0).put("showGridlines", true).put("showHeadings", true)
        .put("rightToLeft", false).put("freezeRows", 0).put("freezeColumns", 0)
    private fun header(kind: Int, json: Int, png: Int = 0) = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
        .put("ZSG1".toByteArray()).putInt(kind).putInt(1).putInt(json).putInt(png).array()
    private fun rejects(action: () -> Unit) {
        try { action(); fail("Invalid worksheet receipt accepted") } catch (_: Exception) { }
    }

    @Test fun visibleSheetIndicesAndHiddenRowGeometryRemainExact() {
        val workbook = WorkbookWire.workbook(JSONObject().put("version", 1).put("activeSheet", 7)
            .put("sheets", JSONArray().put(sheet()).put(sheet(7))).put("warnings", JSONArray()))
        assertEquals(listOf(3, 7), workbook.sheets.map { it.index })
        assertEquals(7, workbook.activeSheet)
        val json = sheet().put("rowEdges", JSONArray(listOf(0.0, 0.0, 12.0, 12.0, 30.0)))
            .put("columnEdges", JSONArray(listOf(0.0, 40.0, 100.0)))
        val geometry = SheetGeometry(WorkbookWire.sheet(json), WorkbookWire.edges(json, "rowEdges", 4, 30.0),
            WorkbookWire.edges(json, "columnEdges", 2, 100.0))
        assertEquals(1, geometry.rowAt(0.0))
        assertEquals(1, geometry.rowAt(11.999))
        assertEquals(3, geometry.rowAt(12.0))
        assertEquals(3, geometry.rowAt(100.0))
        assertEquals(1, geometry.columnAt(40.0))
        assertEquals(12.0, geometry.rowEdge(3), 0.0)
        rejects { WorkbookWire.edges(json.put("rowEdges", JSONArray(listOf(0, 4, 2, 12, 30))), "rowEdges", 4, 30.0) }
    }

    @Test fun framesAreBoundedBeforeAllocationAndRejectTruncation() {
        val json = "{}".toByteArray()
        val frame = WorkbookWire.read(ByteArrayInputStream(header(2, 2) + json))
        assertEquals(2, frame.kind); assertEquals(1, frame.id); assertEquals(0, frame.png.size)
        rejects { WorkbookWire.read(ByteArrayInputStream(header(1, 512 * 1024 + 1))) }
        rejects { WorkbookWire.read(ByteArrayInputStream(header(3, 2, DocRenderCore.OUTPUT_LIMIT + 1))) }
        rejects { WorkbookWire.read(ByteArrayInputStream(header(2, 2, 1) + json + byteArrayOf(0))) }
        rejects { WorkbookWire.read(ByteArrayInputStream(header(3, 2, 4) + json)) }
        rejects { WorkbookWire.read(ByteArrayInputStream(header(1, 2) + byteArrayOf(0xc0.toByte(), 0x80.toByte()))) }
        rejects { WorkbookWire.read(ByteArrayInputStream(header(2, 2).copyOf(19))) }
    }

    @Test fun coordinateEchoAllowsOnlyRoundingAndViewportBoundsIncludeDownsampling() {
        assertTrue(WorkbookWire.sameCoordinate(Math.nextUp(1e9), 1e9))
        assertFalse(WorkbookWire.sameCoordinate(1e9 + .01, 1e9))
        assertFalse(WorkbookWire.sameCoordinate(Double.NaN, 1.0))
        SheetViewport(7, 1e9, 0.0, .0625, 2800, 2800).checked()
        rejects { SheetViewport(7, 0.0, 0.0, .01, 100, 100).checked() }
        rejects { SheetViewport(7, 0.0, 0.0, 1.0, 2801, 100).checked() }
        rejects { SheetViewport(7, Double.POSITIVE_INFINITY, 0.0, 1.0, 100, 100).checked() }
    }
}
