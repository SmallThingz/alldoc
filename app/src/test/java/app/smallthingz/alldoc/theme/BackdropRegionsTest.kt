package app.smallthingz.alldoc.theme

import org.junit.Assert.*
import org.junit.Test

class BackdropRegionsTest {
    @Test fun knownDarkPaperAdmitsOneColoredInkFamilyWithoutBroadeningUnknownImages() {
        val width = 256
        val height = 128
        val paper = 0xff000000.toInt()
        val ink = 0xff3366cc.toInt()
        val pixels = IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            when {
                y !in 32 until 72 || x !in 48 until 56 -> paper
                x == 48 -> DocumentColors.blend(paper, ink, 64)
                x == 55 -> DocumentColors.blend(paper, ink, 128)
                else -> ink
            }
        }
        for (band in intArrayOf(1, 17, 64)) {
            assertTrue("Unknown image policy stays conservative", scan(pixels, width, band).isEmpty)
            val regions = scan(pixels, width, band, allowNeutralColoredInk = true)
            assertFalse("Complete vector hints retain dark paper", regions.isEmpty)
            val all = LongArray((pixels.size + 63) / 64)
            val neutral = all.copyOf()
            assertTrue(regions.masks(0, height, all, neutral))
            assertArrayEquals("Colored lettering does not change the neutral paper role", all, neutral)
            assertTrue(all.all { it == -1L })
            regions.prepareInk()
            val output = pixels.copyOf()
            for (y in 0 until height) regions.adaptInk(output, y * width, y, null)
            assertEquals(paper, output[0])
            assertTrue(DocumentColors.contrast(output[40 * width + 50], paper) >= 4.5)
            for (index in pixels.indices) assertTrue("AA contrast at $index",
                DocumentColors.contrast(output[index], paper) + 1e-12 >= DocumentColors.contrast(pixels[index], paper))
            val protected = pixels.copyOf()
            val images = LongArray(all.size) { -1L }
            for (y in 0 until height) regions.adaptInk(protected, y * width, y, images)
            assertArrayEquals("Known images stay byte exact", pixels, protected)
        }
    }

    @Test fun knownDarkPaperKeepsMixedInkWithoutGuessingAnAntialiasFamily() {
        val width = 256
        val height = 128
        val paper = 0xff000000.toInt()
        val pixels = IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            val ink = if (x < 128) 0xff3366cc.toInt() else 0xffcc3333.toInt()
            when {
                y !in 32 until 72 || x % 128 !in 48 until 56 -> paper
                x % 128 == 48 -> DocumentColors.blend(paper, ink, 64)
                x % 128 == 55 -> DocumentColors.blend(paper, ink, 128)
                else -> ink
            }
        }
        for (band in intArrayOf(1, 17, 64)) {
            assertTrue("Unknown image admission is unchanged", scan(pixels, width, band).isEmpty)
            val regions = scan(pixels, width, band, allowNeutralColoredInk = true)
            assertFalse("Mixed vector lettering must not discard its dark paper", regions.isEmpty)
            val all = LongArray((pixels.size + 63) / 64)
            val neutral = all.copyOf()
            assertTrue(regions.masks(0, height, all, neutral))
            assertTrue(all.all { it == -1L })
            assertArrayEquals(all, neutral)
            regions.prepareInk()
            val output = pixels.copyOf()
            for (y in 0 until height) regions.adaptInk(output, y * width, y, null)
            assertArrayEquals("Unknown mixed AA families retain every original color and contrast", pixels, output)
        }
    }

    @Test fun chromaticPanelCorrectionsDoNotWeakenActualAntialiasPixels() {
        val width = 320
        val height = 160
        for ((ink, paper) in arrayOf(0xff1045d1.toInt() to 0xffd80623.toInt(),
            0xff00b2b9.toInt() to 0xffd4010c.toInt())) {
            val pixels = IntArray(width * height) { index ->
                val x = index % width; val y = index / width
                when {
                    x !in 8 until 312 || y !in 8 until 152 -> -1
                    y in 64 until 76 && x in 32 until 288 -> DocumentColors.blend(paper, ink, x - 32)
                    else -> paper
                }
            }
            for (band in intArrayOf(1, 17, 64)) {
                val regions = scan(pixels, width, band)
                assertFalse(regions.isEmpty)
                regions.prepareInk()
                val output = pixels.copyOf()
                for (y in 0 until height) regions.adaptInk(output, y * width, y, null)
                var changed = 0
                for (index in pixels.indices) {
                    if (output[index] != pixels[index]) {
                        changed++
                        assertTrue(DocumentColors.contrast(output[index], paper) + 1e-12 >=
                            DocumentColors.contrast(pixels[index], paper))
                    }
                }
                assertTrue("Identified low-contrast lettering must actually improve", changed > 100)
                assertEquals(paper, output[40 * width + 100])
                assertEquals(-1, output[0])
            }
        }
    }


    @Test fun coloredLetteringKeepsPanelAndCoverageAcrossBandsButNeverTouchesKnownImages() {
        val width = 240
        val height = 100
        val paper = 0xff18a303.toInt()
        val ink = 0xff235d75.toInt()
        val ramp = requireNotNull(DocumentColors.inkRamp(ink, paper))
        val pixels = IntArray(width * height) { index ->
            val x = index % width; val y = index / width
            when {
                x !in 16 until 224 || y !in 12 until 88 -> -1
                y in 28 until 64 && x in 60 until 68 ->
                    DocumentColors.blend(paper, ink, if (x == 60 || x == 67) 100 else 255)
                y in 28 until 64 && x in 112 until 118 -> -1
                else -> paper
            }
        }
        for (band in intArrayOf(1, 17, 64)) {
            val regions = scan(pixels, width, band)
            assertFalse(regions.isEmpty)
            regions.prepareInk()
            val output = pixels.copyOf()
            for (y in 0 until height) regions.adaptInk(output, y * width, y, null)
            for (index in output.indices) {
                val coverage = DocumentColors.inkCoverage(pixels[index], paper, ink)
                val expected = if (coverage > 0) ramp[coverage] else pixels[index]
                assertEquals("band=$band pixel=$index", expected, output[index])
            }
            assertTrue(DocumentColors.contrast(output[40 * width + 64], paper) >= 4.5)
            val protected = LongArray((pixels.size + 63) / 64) { -1L }
            val unchanged = pixels.copyOf()
            for (y in 0 until height) regions.adaptInk(unchanged, y * width, y, protected)
            assertArrayEquals(pixels, unchanged)
            val partial = pixels.copyOf()
            partial[40 * width + 64] = (partial[40 * width + 64] and 0x00ffffff) or 0xf0000000.toInt()
            val translucent = partial[40 * width + 64]
            regions.adaptInk(partial, 40 * width, 40, null)
            assertEquals("Unknown alpha compositing cannot establish opaque contrast", translucent, partial[40 * width + 64])
        }
        // A framed multicolor image is not a single colored lettering/antialias family.
        val photo = pixels.copyOf()
        for (y in 28 until 64) for (x in 60 until 68)
            photo[y * width + x] = if ((x + y) % 2 == 0) 0xff112288.toInt() else 0xffaa2211.toInt()
        assertTrue(scan(photo, width, 17).isEmpty)
    }

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

    @Test fun coloredPlateAdmissionUsesPageScaleInsteadOfDetailPixelCount() {
        for (scale in 1..3) {
            val width = 720 * scale
            val height = 192 * scale
            val pixels = IntArray(width * height) { index ->
                val x = index % width / scale
                val y = index / width / scale
                when {
                    // Too narrow or too short to be a plate at every detail tier.
                    x in 32 until 68 && y in 16 until 80 -> 0xff18a303.toInt()
                    x in 128 until 288 && y in 32 until 50 -> 0xff712580.toInt()
                    x in 320 until 680 && y in 16 until 176 ->
                        if (x in 400 until 408 && y in 48 until 128) -1 else 0xff18a303.toInt()
                    else -> -1
                }
            }
            for (band in intArrayOf(17, 64)) {
                val regions = scan(pixels, width, band)
                assertFalse("The large plate remains recognized at scale=$scale band=$band", regions.isEmpty)
                assertFalse(regions.transparent)
                for (top in 0 until height step 64) {
                    val rows = minOf(64, height - top)
                    val all = LongArray((width * rows + 63) / 64 + 1) { -1L }
                    val neutral = all.copyOf()
                    val muted = all.copyOf()
                    regions.masks(top, rows, all, neutral, muted)
                    for (index in 0 until all.size * 64) {
                        val x = index % width / scale
                        val y = (top + index / width) / scale
                        val expected = index < width * rows && x in 320 until 680 && y in 16 until 176
                        val actual = all[index ushr 6] and (1L shl (index and 63)) != 0L
                        if (expected != actual) fail("Scaled preservation mask: scale=$scale band=$band top=$top pixel=$index")
                    }
                    assertTrue("The colored plate is not neutral or muted",
                        neutral.all { it == 0L } && muted.all { it == 0L })
                }
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

    private fun scan(pixels: IntArray, width: Int, band: Int, allowNeutralColoredInk: Boolean = false): BackdropRegions {
        val original = pixels.copyOf()
        val regions = BackdropRegions(width, allowNeutralColoredInk)
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
