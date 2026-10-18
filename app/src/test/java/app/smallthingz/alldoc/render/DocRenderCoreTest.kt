package app.smallthingz.alldoc.render

import java.io.IOException
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class DocRenderCoreTest {
    private val state = DocRenderCore.ViewState(0, 1, "private-snapshot")

    private fun receipt(format: Int, width: Int = 1, data: ByteArray = byteArrayOf(), note: String = ""): ByteArray {
        val text = note.toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(32 + text.size + data.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(byteArrayOf(90, 70, 82, 49))
            for (value in intArrayOf(format, width, 1, 0, 1, text.size, data.size)) putInt(value)
            put(text)
            put(data)
        }.array()
    }

    @Test fun cancellationIsNotReportedAsCorruption() {
        assertThrows(InterruptedIOException::class.java) {
            DocRenderCore.decode(receipt(0, note = "CANCELLED"), state)
        }
    }

    @Test fun rejectsTruncatedAndOversizedReceipts() {
        val wire = receipt(0, note = "Reader rejected this document")
        assertThrows(IOException::class.java) { DocRenderCore.decode(wire.copyOf(wire.size - 1), state) }
        ByteBuffer.wrap(wire).order(ByteOrder.LITTLE_ENDIAN).putInt(24, Int.MAX_VALUE)
        assertThrows(IOException::class.java) { DocRenderCore.decode(wire, state) }
    }

    @Test fun rejectsMismatchBetweenImageAndPageDimensions() {
        val png = ByteBuffer.allocate(24).order(ByteOrder.BIG_ENDIAN).apply {
            put(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
            putInt(16, 2)
            putInt(20, 1)
        }.array()
        assertThrows(IOException::class.java) { DocRenderCore.decode(receipt(1, data = png), state) }
    }

    @Test fun cachedPayloadIsSharedButCallerBuffersStayIsolated() {
        val png = ByteBuffer.allocate(24).order(ByteOrder.BIG_ENDIAN).apply {
            put(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
            putInt(16, 1)
            putInt(20, 1)
        }.array()
        val wire = receipt(1, data = png)
        val page = DocRenderCore.decode(wire, state)
        val cached = page.cachedBytes()
        assertSame(cached, page.cachedBytes())
        val copy = page.copyBytes()
        assertNotSame(cached, copy)
        wire.fill(0)
        copy.fill(0)
        assertArrayEquals(png, cached)
        assertArrayEquals(png, page.copyBytes())
    }

    @Test fun acceptsTheLastOf1024PagesAndRejectsOverflowOrWrongPage() {
        val png = ByteBuffer.allocate(24).order(ByteOrder.BIG_ENDIAN).apply {
            put(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
            putInt(16, 1)
            putInt(20, 1)
        }.array()
        val wire = receipt(1, data = png)
        val header = ByteBuffer.wrap(wire).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(16, 1023)
        header.putInt(20, 1024)
        val last = DocRenderCore.ViewState(1023, 1, "private-snapshot")
        val page = DocRenderCore.decode(wire, last)
        assertEquals(1023, page.page)
        assertEquals(1024, page.pageCount)
        assertThrows(IOException::class.java) { DocRenderCore.decode(wire, state) }
        for (count in listOf(0, 1023, 1025, Int.MAX_VALUE)) {
            header.putInt(20, count)
            assertThrows(IOException::class.java) { DocRenderCore.decode(wire, last) }
        }
    }

    @Test fun detailReceiptsKeepTheirSideAndPixelBounds() {
        val png = ByteBuffer.allocate(24).order(ByteOrder.BIG_ENDIAN).apply {
            put(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
            putInt(16, 2800)
            putInt(20, 2800)
        }.array()
        val wire = receipt(1, width = 2800, data = png)
        ByteBuffer.wrap(wire).order(ByteOrder.LITTLE_ENDIAN).putInt(12, 2800)
        val page = DocRenderCore.decode(wire, state)
        assertEquals(2800, page.width)
        assertEquals(2800, page.height)
        ByteBuffer.wrap(wire).order(ByteOrder.LITTLE_ENDIAN).putInt(8, 2801)
        assertThrows(IOException::class.java) { DocRenderCore.decode(wire, state) }
    }

    @Test fun unknownFilenameDoesNotClaimAVisualEngine() {
        assertEquals(0, DocRenderCore.kind("document.pages"))
        assertEquals(0, DocRenderCore.kind("legacy.ppt"))
        assertEquals(0, DocRenderCore.kind("photo.unknown"))
    }
    @Test fun rasterMetadataDistinguishesUnknownEmptyAndCompleteWithoutChangingPixels() {
        val png = ByteBuffer.allocate(24).order(ByteOrder.BIG_ENDIAN).apply {
            put(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)); putInt(16, 1); putInt(20, 1)
        }.array()
        val original = receipt(1, data = png)
        fun withRegions(values: List<FloatArray>?): ByteArray = ByteBuffer.allocate(original.size + 4 + (values?.size ?: 0) * 16)
            .order(ByteOrder.LITTLE_ENDIAN).apply {
                put(original, 0, 32); put(3, '2'.code.toByte()); putInt(values?.size ?: -1)
                values?.forEach { r -> r.forEach { putFloat(it) } }; put(original, 32, original.size - 32)
            }.array()
        assertEquals(null, DocRenderCore.decode(original, state).rasterRegions)
        assertEquals(null, DocRenderCore.decode(withRegions(null), state).rasterRegions)
        assertEquals(emptyList<Any>(), DocRenderCore.decode(withRegions(emptyList()), state).rasterRegions)
        val page = DocRenderCore.decode(withRegions(listOf(floatArrayOf(.1f, .2f, .7f, .9f))), state)
        val region = requireNotNull(page.rasterRegions).single()
        assertEquals(.1f, region.left)
        assertEquals(.9f, region.bottom)
        assertArrayEquals(png, page.copyBytes())
        for (r in listOf(floatArrayOf(Float.NaN, 0f, 1f, 1f), floatArrayOf(0f, 0f, 2f, 1f), floatArrayOf(.5f, 0f, .4f, 1f))) {
            assertEquals(null, DocRenderCore.decode(withRegions(listOf(r)), state).rasterRegions)
        }
        assertThrows(IOException::class.java) { DocRenderCore.decode(withRegions(List(65) { floatArrayOf(0f, 0f, 1f, 1f) }), state) }
    }

}
