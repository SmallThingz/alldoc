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

    @Test fun unknownFilenameDoesNotClaimAVisualEngine() {
        assertEquals(0, DocRenderCore.kind("document.pages"))
        assertEquals(0, DocRenderCore.kind("legacy.ppt"))
        assertEquals(0, DocRenderCore.kind("photo.unknown"))
    }
}
