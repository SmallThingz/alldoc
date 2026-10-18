package app.smallthingz.alldoc.theme

import java.io.DataInputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.zip.GZIPInputStream
import org.junit.Assert.*
import org.junit.Test

class ScanPaperMaskTest {
    @Test fun nativeScanSelectsContinuousPaperAndAntialiasedInkWithoutSelectingItsPhoto() {
        val fixture = nativeScan()
        val original = fixture.pixels.copyOf()
        var rows = 0
        var buffer: IntArray? = null
        val mask = requireNotNull(ScanPaperMask.detect(fixture.width, fixture.height, { y, row ->
            if (buffer == null) buffer = row else assertSame(buffer, row)
            assertEquals(rows++, y)
            fixture.pixels.copyInto(row, 0, y * fixture.width, (y + 1) * fixture.width)
        }))
        assertEquals(fixture.height, rows)
        assertEquals(fixture.width, mask.width)
        assertEquals(fixture.height, mask.height)
        // Independent scanline-component oracle over the exact native RGB fixture.
        val digest = MessageDigest.getInstance("SHA-256")
        val flags = ByteArray(fixture.width)
        for (y in 0 until fixture.height) {
            for (x in flags.indices) flags[x] = if (mask.contains(x, y)) 1 else 0
            digest.update(flags)
        }
        assertEquals("b06a778a92dcd0837d35a717d3cbe1e5e027b0d4539833834f099b58f0b576ce",
            digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
        // The authored photograph clips the next line. Its visible neutral glyph
        // tops must still be selected, even where they meet chromatic image pixels.
        var clippedInk = 0
        for (y in 710 until 720) for (x in 64 until 500) {
            val pixel = fixture.pixels[y * fixture.width + x]
            val r = pixel ushr 16 and 255
            val g = pixel ushr 8 and 255
            val b = pixel and 255
            val lightness = (54 * r + 183 * g + 19 * b) ushr 8
            if (maxOf(r, g, b) - minOf(r, g, b) <= 40 && lightness < 160) {
                clippedInk++
                assertTrue("visible clipped ink at $x,$y", mask.contains(x, y))
            }
        }
        assertTrue(clippedInk > 100)
        // The photograph is one authored continuous image, not a blank test plate.
        for (y in 720 until 1200) for (x in 54 until 1026)
            assertFalse("photo at $x,$y", mask.contains(x, y))
        val theme = PixelTheme(ThemeSettings(ReadingMode.ADAPTIVE))
        val palette = DocumentColors.palette(ThemeSettings(ReadingMode.ADAPTIVE))
        var neutralInk = 0
        var nearWhitePaper = 0
        val paperLightness = (54 * (palette.background ushr 16 and 255) +
            183 * (palette.background ushr 8 and 255) + 19 * (palette.background and 255)) ushr 8
        for (y in 1202 until 1216) for (x in 54 until 600) {
            assertTrue("body immediately below photo at $x,$y", mask.contains(x, y))
            val pixel = fixture.pixels[y * fixture.width + x]
            val mapped = theme.color(pixel)
            val r = pixel ushr 16 and 255
            val g = pixel ushr 8 and 255
            val b = pixel and 255
            val chroma = maxOf(r, g, b) - minOf(r, g, b)
            val lightness = (54 * r + 183 * g + 19 * b) ushr 8
            val mappedLightness = (54 * (mapped ushr 16 and 255) +
                183 * (mapped ushr 8 and 255) + 19 * (mapped and 255)) ushr 8
            // Native JPEG paper and antialiasing are textured, not literal RGB255
            // or perfectly equal-channel gray. Use their actual neutral colors.
            if (chroma <= 6 && lightness >= 245) {
                nearWhitePaper++
                assertTrue("paper becomes a dark surface", mappedLightness < 48)
            }
            if (chroma <= 40 && lightness in 1..159) {
                neutralInk++
                assertTrue("antialiased ink remains brighter than paper", mappedLightness > paperLightness)
            }
        }
        assertTrue(nearWhitePaper > 100)
        assertTrue(neutralInk > 10)
        assertArrayEquals(original, fixture.pixels)
        assertFalse(mask.contains(-1, 0))
        assertFalse(mask.contains(fixture.width, 0))
        // Final mask has no borrowed mutable row storage.
        requireNotNull(buffer).fill(0)
        assertTrue(mask.contains(1000, 704))
    }

    @Test fun clippedNeutralInkNeedsThreePaperSidesAndKeepsNeutralPhotoDetails() {
        val width = 128
        val height = 128
        val pixels = IntArray(width * height) { -1 }
        fun fill(left: Int, top: Int, right: Int, bottom: Int, color: Int) {
            for (y in top until bottom) for (x in left until right) pixels[y * width + x] = color
        }
        fill(32, 64, 96, 96, 0xff286fa0.toInt())
        fill(48, 58, 52, 64, 0xff323232.toInt()) // Ink touches the photo along its bottom.
        fill(47, 60, 48, 64, 0xffaaaaaa.toInt()) // Its antialiased edge also touches color.
        fill(32, 72, 36, 76, 0xff5a5a5a.toInt()) // Neutral photo edge: paper on one side only.
        fill(64, 72, 69, 78, -1) // Enclosed pale image lettering is not page paper.
        fill(70, 80, 75, 86, 0xff787878.toInt()) // Enclosed neutral photo detail.
        val original = pixels.copyOf()
        val mask = requireNotNull(ScanPaperMask.detect(width, height, { y, row ->
            pixels.copyInto(row, 0, y * width, (y + 1) * width)
        }))
        for (y in 0 until height) for (x in 0 until width) {
            val photo = x in 32 until 96 && y in 64 until 96
            assertEquals("paper, clipped ink or image at $x,$y", !photo, mask.contains(x, y))
        }
        assertArrayEquals(original, pixels)
    }

    @Test fun lowPaperImageFootprintAndPartialAlphaKeepTheirExistingTreatment() {
        val width = 176
        val height = 112
        var reads = 0
        assertNull(ScanPaperMask.detect(width, height, { y, row ->
            reads++
            for (x in row.indices) row[x] = when {
                x >= 170 && y in 10..25 -> -1 // A small light toolbar pocket is not page paper.
                x >= 150 -> 0xff242424.toInt()
                else -> 0xff406d80.toInt() or ((x + y) and 15)
            }
        }))
        assertTrue("impossible edge confidence stops early", reads in 1 until height)
        assertNull(ScanPaperMask.detect(64, 64, { y, row ->
            row.fill(-1)
            if (y == 20) row[20] = 0x7fffffff
        }))
    }

    @Test fun invalidSizesAndFloodCapacityFailClosedWithoutUnboundedStorage() {
        for ((width, height) in listOf(0 to 64, 64 to 0, Int.MAX_VALUE to Int.MAX_VALUE,
            4000 to 4000, 500000 to 16)) {
            assertNull(ScanPaperMask.detect(width, height, { _, _ -> fail("size must reject before reading") }))
        }
        // One ordinary opaque comb needs 8,193 pending spans. Exceeding the fixed
        // stack returns no override; it does not partially publish a paper mask.
        val width = 16_386
        val height = 16
        var reads = 0
        assertNull(ScanPaperMask.detect(width, height, { y, row ->
            reads++
            for (x in row.indices) row[x] = if (y == 0 || y == height - 1 || x % 2 == 0) -1 else 0xff000000.toInt()
        }))
        assertEquals(height, reads)
    }

    @Test fun neutralTextAndBlankPaperBeforeDistantColorKeepTheirCompleteMasks() {
        val width = 512
        val height = 512
        val blank = requireNotNull(ScanPaperMask.detect(width, height, { _, row -> row.fill(-1) }))
        for (y in 0 until height) for (x in 0 until width)
            if (!blank.contains(x, y)) fail("blank paper at $x,$y")
        val mask = requireNotNull(ScanPaperMask.detect(width, height, { y, row ->
            for (x in row.indices) row[x] = when {
                y in 448 until 480 && x in 32 until 480 -> 0xff286fa0.toInt()
                y in 16 until 256 && x in 16 until 480 &&
                    (y - 16) % 16 < 7 && (x - 16) % 16 < 5 ->
                    if ((x - 16) % 16 == 0 || (y - 16) % 16 == 0) 0xff888888.toInt() else 0xff202020.toInt()
                else -> -1
            }
        }))
        // A colored footer must neither change earlier neutral text selection nor
        // be mistaken for ink. The exact native mask oracle remains separate.
        for (y in 0 until height) for (x in 0 until width) {
            val photo = y in 448 until 480 && x in 32 until 480
            if (mask.contains(x, y) == photo) fail("text/paper or colored footer at $x,$y")
        }
    }

    @Test fun cancellationPropagatesDuringRowsAndComponentTraversal() {
        val width = 128
        val height = 256
        for (afterRows in listOf(4, height)) {
            var rows = 0
            var postRowChecks = 0
            val cancellation = CancellationException("owned test cancellation")
            val error = runCatching {
                ScanPaperMask.detect(width, height, { _, row -> rows++; row.fill(-1) }, {
                    if (rows >= afterRows && (afterRows < height || ++postRowChecks >= 3)) throw cancellation
                })
            }.exceptionOrNull()
            assertSame(cancellation, error)
            assertEquals(afterRows, rows)
        }
        val failure = IOException("owned row failure")
        assertSame(failure, runCatching {
            ScanPaperMask.detect(width, height, { _, _ -> throw failure })
        }.exceptionOrNull())
    }

    @Test fun packedRowsTranslateRunsWithoutChangingNeighboringPixels() {
        val width = 197
        val height = 128
        val mask = requireNotNull(ScanPaperMask.detect(width, height, { y, row ->
            for (x in row.indices) row[x] = if (x in 61 until 139 && y in 32 until 96) 0xff286fa0.toInt() else -1
        }))
        for (retain in listOf(false, true)) for (offset in listOf(1, 61, 64, 123)) for (y in 0 until height) {
            val bits = LongArray(8) { if (retain) -1L else 0L }
            markBits(bits, 0, 1); markBits(bits, 511, 512)
            mask.markRow(y, bits, offset, retain)
            for (bit in 0 until 512) {
                val expected = if (bit in offset until offset + width) mask.contains(bit - offset, y)
                    else retain || bit == 0 || bit == 511
                assertEquals("row $y at offset $offset, bit $bit", expected,
                    bits[bit ushr 6] and (1L shl (bit and 63)) != 0L)
            }
        }
        val cleared = LongArray(4) { -1L }
        markBits(cleared, 61, 193, false)
        for (bit in 0 until 256) assertEquals(bit !in 61 until 193,
            cleared[bit ushr 6] and (1L shl (bit and 63)) != 0L)
        assertTrue(mask.storageBytes < ScanPaperMask.auxiliaryBytes(width, height))
        assertTrue(ScanPaperMask.auxiliaryBytes(500000, 16) > ScanPaperMask.MAX_AUXILIARY_BYTES)
    }

    private data class Fixture(val width: Int, val height: Int, val pixels: IntArray)
    private fun nativeScan(): Fixture = DataInputStream(GZIPInputStream(requireNotNull(
        javaClass.getResourceAsStream("/scan-paper/native1080.rgb.gz")))).use { input ->
        val width = input.readInt()
        val height = input.readInt()
        assertEquals(1080, width)
        assertEquals(1440, height)
        val pixels = IntArray(width * height) {
            -0x1000000 or (input.readUnsignedByte() shl 16) or
                (input.readUnsignedByte() shl 8) or input.readUnsignedByte()
        }
        assertEquals(-1, input.read())
        Fixture(width, height, pixels)
    }
}
