package app.smallthingz.alldoc.theme

import org.junit.Assert.*
import org.junit.Test

class BackdropRegionsTest {
    @Test fun closedPanelsKeepTheirEdgesAndLetteringAcrossStreamingBands() {
        val width = 257
        val height = 193
        val paper = 0xff101010.toInt()
        for (top in intArrayOf(0, 1, 31, 63, 121)) {
            val pixels = IntArray(width * height) { index ->
                val x = index % width; val y = index / width - top
                when {
                    x >= 192 || y !in 0 until 72 -> -1
                    y in 12 until 60 && (x in 40 until 46 || x in 80 until 86) ||
                        y in 34 until 40 && x in 40 until 86 -> -1
                    y in 12 until 60 && (x == 39 || x == 86) -> 0xff9a9a9a.toInt()
                    else -> paper
                }
            }
            for (band in intArrayOf(1, 17, 64)) {
                val regions = scan(pixels, width, band)
                assertFalse("top=$top band=$band", regions.isEmpty)
                assertFalse(regions.transparent)
                for (first in 0 until height step 64) {
                    val rows = minOf(64, height - first)
                    val all = LongArray((width * rows + 63) / 64 + 1) { -1L }
                    val neutral = all.copyOf()
                    regions.masks(first, rows, all, neutral)
                    assertArrayEquals("A neutral panel has both roles", all, neutral)
                    for (index in 0 until all.size * 64) {
                        val expected = index < width * rows && index % width < 192 &&
                            first + index / width in top until top + 72
                        val actual = all[index ushr 6] and (1L shl (index and 63)) != 0L
                        if (expected != actual) fail("top=$top band=$band first=$first pixel=$index")
                    }
                }
            }
        }
    }

    @Test fun foregroundStrokesAndGrayscaleDetailAreNotBackgroundPlates() {
        val dark = 0xff101010.toInt()
        for (kind in listOf("HE", "I", "underline", "framed image", "dark framed image", "row gradient", "column gradient")) {
            val width = if (kind == "HE") 720 else 320
            val height = if (kind == "HE") 160 else 120
            val pixels = IntArray(width * height) { -1 }
            fun fill(left: Int, right: Int, top: Int, bottom: Int, color: Int = dark) {
                for (y in top until bottom) pixels.fill(color, y * width + left, y * width + right)
            }
            when (kind) {
                "HE" -> {
                    fill(32, 58, 16, 144); fill(138, 164, 16, 144); fill(32, 164, 67, 93)
                    fill(204, 230, 16, 144)
                    for (top in intArrayOf(16, 67, 118)) fill(204, 336, top, top + 26)
                }
                "I" -> fill(142, 166, 28, 88)
                "underline" -> fill(70, 230, 72, 84)
                "framed image", "dark framed image" -> {
                    fill(40, 280, 20, 100)
                    for (y in 37 until 83) for (x in 80 until 240) {
                        val gray = if (kind == "dark framed image") 20 + (x * 11 + y * 7) % 25
                            else 45 + (x * 11 + y * 7) % 166
                        pixels[y * width + x] = 0xff000000.toInt() or (gray * 0x010101)
                    }
                }
                else -> for (index in pixels.indices) {
                    val position = if (kind == "row gradient") index / width else index % width
                    val gray = 45 + position % 166
                    pixels[index] = 0xff000000.toInt() or (gray * 0x010101)
                }
            }
            for (band in intArrayOf(17, 64)) {
                val regions = scan(pixels, width, band)
                assertTrue("$kind band=$band must retain its foreground/image role", regions.isEmpty)
                val all = LongArray((pixels.size + 63) / 64) { -1L }
                val neutral = all.copyOf()
                regions.masks(0, height, all, neutral)
                assertTrue("$kind must clear reused masks", all.all { it == 0L } && neutral.all { it == 0L })
            }
        }
    }

    @Test fun transparencyDiscardsEarlierCandidatesAndCancellationStopsScanning() {
        val width = 128
        val pixels = IntArray(width * 65) { if (it / width < 32) 0xff101010.toInt() else -1 }
        pixels[width * 64 + width - 1] = 0x70101010
        val regions = scan(pixels, width, 64)
        assertTrue(regions.transparent)
        assertTrue(regions.isEmpty)
        val cancelled = IllegalStateException("cancelled")
        var rows = 0
        try {
            BackdropRegions(width).scan(pixels, 65) { if (++rows == 3) throw cancelled }
            fail("Scan must honor cancellation between rows")
        } catch (error: IllegalStateException) {
            assertSame(cancelled, error)
            assertEquals(3, rows)
        }
    }

    private fun scan(pixels: IntArray, width: Int, band: Int): BackdropRegions {
        val original = pixels.copyOf()
        val regions = BackdropRegions(width)
        val buffer = IntArray(width * band) { 0x01020304 }
        val height = pixels.size / width
        for (top in 0 until height step band) {
            val rows = minOf(band, height - top)
            pixels.copyInto(buffer, 0, top * width, (top + rows) * width)
            val unchanged = buffer.copyOf()
            regions.scan(buffer, rows) {}
            assertArrayEquals("Streaming recognition must not mutate source rows", unchanged, buffer)
            if (regions.transparent) break
        }
        regions.finish()
        assertArrayEquals(original, pixels)
        return regions
    }
}
