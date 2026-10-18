package app.smallthingz.alldoc.theme

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.smallthingz.alldoc.engine.DocumentEngine
import app.smallthingz.alldoc.ui.DocumentPreviews
import java.io.DataInputStream
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BitmapThemeTest {
    @Test fun knownVectorWhiteLabelsKeepContrastWithoutChangingPhotosOrImagePolicies() {
        val width = 192
        val height = 83
        val paper = 0xff86d07d.toInt() // Actual Layer 10 fill in the pinned layered PPTX.
        val source = IntArray(width * height) { paper }
        for (top in listOf(3, 61)) for (y in top until top + 8) for (left in listOf(62, 126)) {
            source[y * width + left - 1] = DocumentColors.blend(paper, -1, 64)
            val coverage = if ((y - top) % 2 == 0) 224 else 255
            for (x in left until left + 4) source[y * width + x] = DocumentColors.blend(paper, -1, coverage)
            source[y * width + left + 4] = DocumentColors.blend(paper, -1, 128)
        }
        val bitmap = Bitmap.createBitmap(source, width, height, Bitmap.Config.ARGB_8888)
        val hints = listOf(RasterRegion(.5f, 0f, 1f, 1f))
        val settings = ThemeSettings(ReadingMode.ADAPTIVE)
        val theme = PixelTheme(settings)
        val mappedPaper = theme.color(paper)
        val ramp = requireNotNull(DocumentColors.inkRamp(theme.color(-1), mappedPaper))
        val actual = IntArray(source.size)
        try {
            val output = BitmapTheme.apply(bitmap, settings, CancellationSignal(), hints)
            try {
                output.getPixels(actual, 0, width, 0, 0, width, height)
                var cores = 0
                var edges = 0
                for (y in 0 until height) for (x in 0 until width) {
                    val index = y * width + x
                    if (x < width / 2 && source[index] != paper) {
                        val coverage = DocumentColors.inkCoverage(source[index], paper, -1)
                        assertTrue(coverage > 0)
                        assertEquals("Consistent whole glyph at $x,$y", ramp[coverage], actual[index])
                    }
                    when {
                        x >= width / 2 -> assertEquals("Image pixel at $x,$y", source[index], actual[index])
                        source[index] == paper -> assertEquals("Fill at $x,$y", mappedPaper, actual[index])
                        source[index] == -1 -> {
                            assertTrue("White core at $x,$y", DocumentColors.contrast(actual[index], mappedPaper) >= 4.5)
                            cores++
                        }
                        else -> {
                            assertTrue("Antialiased ink at $x,$y", DocumentColors.contrast(actual[index], mappedPaper) + 1e-6 >=
                                DocumentColors.contrast(theme.color(source[index]), mappedPaper))
                            edges++
                        }
                    }
                }
                assertEquals(32, cores)
                assertEquals(64, edges)
                assertTrue("The old independent mapping is a failing contrast control",
                    DocumentColors.contrast(theme.color(-1), mappedPaper) < 4.5)
            } finally { output.recycle() }
            for (policy in listOf(ImageTreatment.KEEP, ImageTreatment.DIM)) {
                val explicit = settings.copy(imageTreatment = policy)
                val baseline = BitmapTheme.apply(bitmap, explicit, CancellationSignal())
                try {
                    val hinted = BitmapTheme.apply(bitmap, explicit, CancellationSignal(), hints)
                    try {
                        val expected = IntArray(source.size)
                        baseline.getPixels(expected, 0, width, 0, 0, width, height)
                        hinted.getPixels(actual, 0, width, 0, 0, width, height)
                        assertArrayEquals("$policy retains its existing mapping", expected, actual)
                    } finally { hinted.recycle() }
                } finally { baseline.recycle() }
            }
            bitmap.getPixels(actual, 0, width, 0, 0, width, height)
            assertArrayEquals("The original bitmap is unchanged", source, actual)
        } finally { bitmap.recycle() }
    }

    @Test fun translucentPanelsStayDarkBesideThinAndOccludedWhiteInk() {
        val width = 128
        val settings = ThemeSettings(ReadingMode.ADAPTIVE)
        val theme = PixelTheme(settings)
        for (paper in listOf(0xff86d07d.toInt(), 0xff9da0c8.toInt())) for (padding in listOf(0, 63)) {
            val height = padding + 96
            val source = IntArray(width * height) { paper }
            val panel = DocumentColors.blend(paper, -1, 167)
            // A tall alpha-white panel terminates at an unrelated opaque vector line.
            for (y in 0 until height) source[y * width + 57] = 0xff000000.toInt()
            for (y in 16 until 80) for (x in 40 until 57) source[(padding + y) * width + x] = panel
            // A short, entirely AA glyph remains eligible at an occluding edge, even without a core.
            for (y in 4 until 12) {
                for (x in 40 until 46) source[(padding + y) * width + x] = DocumentColors.blend(paper, -1, 128)
                source[(padding + y) * width + 46] = 0xff000000.toInt()
            }
            // Tall one/two-pixel AA stems cross a processing band; their height is not a veto.
            for (y in 24 until 72) for (left in listOf(70, 82)) {
                val thickness = if (left == 70) 1 else 2
                for (x in left until left + thickness)
                    source[(padding + y) * width + x] = DocumentColors.blend(paper, -1, 96)
                source[(padding + y) * width + left + thickness] = 0xff000000.toInt()
            }
            for (y in 0 until height) for (x in 96 until width)
                source[y * width + x] = -0x1000000 or ((x * 17 and 255) shl 16) or ((y * 13 and 255) shl 8) or (x * y and 255)
            val bitmap = Bitmap.createBitmap(source, width, height, Bitmap.Config.ARGB_8888)
            try {
                val output = BitmapTheme.apply(bitmap, settings, CancellationSignal(), listOf(RasterRegion(.75f, 0f, 1f, 1f)))
                try {
                    val actual = IntArray(source.size)
                    output.getPixels(actual, 0, width, 0, 0, width, height)
                    val ramp = requireNotNull(DocumentColors.inkRamp(theme.color(-1), theme.color(paper)))
                    var panels = 0
                    var ink = 0
                    for (y in 0 until height) for (x in 0 until width) {
                        val index = y * width + x
                        val coverage = DocumentColors.inkCoverage(source[index], paper, -1)
                        val expected = when {
                            x >= 96 -> source[index]
                            source[index] == panel -> { panels++; theme.color(panel) }
                            coverage > 0 -> { ink++; ramp[coverage] }
                            else -> theme.color(source[index])
                        }
                        assertEquals("Panel/ink/image at $x,$y padding $padding", expected, actual[index])
                    }
                    assertEquals(17 * 64, panels)
                    assertEquals(6 * 8 + 3 * 48, ink)
                    bitmap.getPixels(actual, 0, width, 0, 0, width, height)
                    assertArrayEquals(source, actual)
                } finally { output.recycle() }
            } finally { bitmap.recycle() }
        }
    }

    @Test fun actualLayeredPdfKeepsTranslucentRowPaperDarkAndPhotoOriginal() {
        // Exact ORIGINAL frame from the failed PDF0 qualification; preserve full-page context.
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().context.assets
            .open("theme/pdf-layered-page.png").use { input ->
                BitmapFactory.decodeStream(input, null, BitmapFactory.Options().apply { inScaled = false })
            })
        try {
            assertEquals(2160, bitmap.width)
            assertEquals(2880, bitmap.height)
            val generation = bitmap.generationId
            val output = BitmapTheme.apply(bitmap, ThemeSettings(ReadingMode.ADAPTIVE), CancellationSignal(),
                listOf(RasterRegion(.6588542f, .890625f, .8411458f, .96875f)))
            try {
                val source = IntArray(346)
                val actual = IntArray(346)
                var paper = 0
                // All eighteen authored text-free strips: x41..44pt, inset one point vertically.
                for (row in 0 until 18) {
                    val bottom = 120 + row * 39
                    for (y in (960 - bottom - 22) * 3 until (960 - bottom - 1) * 3) {
                        bitmap.getPixels(source, 0, 9, 123, y, 9, 1)
                        output.getPixels(actual, 0, 9, 123, y, 9, 1)
                        for (x in 0 until 9) {
                            val color = source[x]
                            assertTrue(minOf(color ushr 16 and 255, color ushr 8 and 255, color and 255) >= 165)
                            assertTrue("Dark row paper at ${123 + x},$y", DocumentColors.luminance(actual[x]) < .10)
                            paper++
                        }
                    }
                }
                assertEquals(18 * 63 * 9, paper)
                // The unchanged strict interior from the heavy PDF photograph oracle.
                var photo = 0
                for (y in 2592 until 2765) {
                    bitmap.getPixels(source, 0, 346, 1447, y, 346, 1)
                    output.getPixels(actual, 0, 346, 1447, y, 346, 1)
                    for (x in source.indices) {
                        assertEquals("Original photo at ${1447 + x},$y", source[x], actual[x])
                        photo++
                    }
                }
                assertEquals(59_858, photo)
                assertEquals(generation, bitmap.generationId)
                assertFalse(bitmap.isRecycled)
            } finally { output.recycle() }
        } finally { bitmap.recycle() }
    }

    @Test fun manyColoredLabelsKeepContrastAfterRampEvictionAndRevisit() {
        val width = 128
        val stripe = 24
        val papers = (0 until 96).map { index ->
            -0x1000000 or ((134 + index % 16) shl 16) or (208 shl 8) or (125 + index / 16)
        }.let { it + it.first() } // Revisit a qualifying fill after more than 64 distinct fills.
        val height = stripe * papers.size
        val source = IntArray(width * height) { papers[it / width / stripe] }
        for (band in papers.indices) for (row in 8 until 16) {
            val offset = (band * stripe + row) * width
            val paper = papers[band]
            source[offset + 31] = DocumentColors.blend(paper, -1, 64)
            for (x in 32 until 36) source[offset + x] = DocumentColors.blend(paper, -1, if (row % 2 == 0) 224 else 255)
            source[offset + 36] = DocumentColors.blend(paper, -1, 128)
        }
        val settings = ThemeSettings(ReadingMode.ADAPTIVE)
        val theme = PixelTheme(settings)
        val bitmap = Bitmap.createBitmap(source, width, height, Bitmap.Config.ARGB_8888)
        try {
            val output = BitmapTheme.apply(bitmap, settings, CancellationSignal(), emptyList())
            try {
                val actual = IntArray(source.size)
                output.getPixels(actual, 0, width, 0, 0, width, height)
                var cores = 0
                var edges = 0
                for ((band, paper) in papers.withIndex()) {
                    val mappedPaper = theme.color(paper)
                    val ramp = requireNotNull(DocumentColors.inkRamp(theme.color(-1), mappedPaper))
                    for (row in 0 until stripe) for (x in 0 until width) {
                        val index = (band * stripe + row) * width + x
                        val coverage = DocumentColors.inkCoverage(source[index], paper, -1)
                        if (coverage > 0) {
                            assertEquals("Whole glyph in color $band at $x,$row", ramp[coverage], actual[index])
                            if (coverage == 255) {
                                assertTrue(DocumentColors.contrast(actual[index], mappedPaper) >= 4.5)
                                cores++
                            } else edges++
                        } else assertEquals("Fill in color $band at $x,$row", mappedPaper, actual[index])
                    }
                }
                assertEquals(96, papers.toSet().size)
                assertEquals(97 * 16, cores)
                assertEquals(97 * 32, edges)
                bitmap.getPixels(actual, 0, width, 0, 0, width, height)
                assertArrayEquals("The original palette is unchanged", source, actual)
            } finally { output.recycle() }
        } finally { bitmap.recycle() }
    }

    @Test fun quantizedShapeEdgesDoNotReclassifyAdjacentFillAsWhiteInk() {
        val width = 128
        val paper = 0xff86d07d.toInt()
        val settings = ThemeSettings(ReadingMode.ADAPTIVE)
        val theme = PixelTheme(settings)
        val ramp = requireNotNull(DocumentColors.inkRamp(theme.color(-1), theme.color(paper)))
        for (padding in listOf(0, 42, 63)) {
            val height = padding + 48
            val source = IntArray(width * height) { paper }
            // The native slide's two near-color edge runs; neither is the surrounding fill.
            for (y in 0 until height) source[y * width + 8] = 0xff000000.toInt()
            for (y in listOf(23, 25)) {
                for (x in 91 until 100) source[(padding + y) * width + x] = 0xff85cf7d.toInt()
                for (x in 100 until width) source[(padding + y) * width + x] = 0xff85ce7c.toInt()
            }
            // Thin and AA-only rows still have genuine surrounding paper, without a core requirement.
            for ((x, amount) in listOf(32, 64, 96, 64, 32).withIndex())
                source[(padding + 7) * width + 40 + x] = DocumentColors.blend(paper, -1, amount)
            for (x in 40..44) source[(padding + 8) * width + x] = -1
            for (y in 9..13) source[(padding + y) * width + 42] = DocumentColors.blend(paper, -1, 128)
            val bitmap = Bitmap.createBitmap(source, width, height, Bitmap.Config.ARGB_8888)
            try {
                val output = BitmapTheme.apply(bitmap, settings, CancellationSignal(), emptyList())
                try {
                    val actual = IntArray(source.size)
                    output.getPixels(actual, 0, width, 0, 0, width, height)
                    var cores = 0
                    var edges = 0
                    for (index in source.indices) {
                        val coverage = DocumentColors.inkCoverage(source[index], paper, -1)
                        val expected = if (coverage > 0) {
                            if (coverage == 255) cores++ else edges++
                            ramp[coverage]
                        } else theme.color(source[index])
                        assertEquals("Exact fill or whole glyph at ${index % width},${index / width}", expected, actual[index])
                    }
                    assertEquals(5, cores)
                    assertEquals(10, edges)
                    bitmap.getPixels(actual, 0, width, 0, 0, width, height)
                    assertArrayEquals("The source fill and edge colors are unchanged", source, actual)
                } finally { output.recycle() }
            } finally { bitmap.recycle() }
        }
    }

    @Test fun actualFullSlideKeepsLateWhiteLabelAndPhotoAfterEarlierColors() {
        // Exact native ORIGINAL page 0; a crop loses the earlier fills that exhausted the memo.
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().context.assets
            .open("theme/pptx-layered-page.png").use { input ->
                BitmapFactory.decodeStream(input, null, BitmapFactory.Options().apply { inScaled = false })
            })
        try {
            assertEquals(2160, bitmap.width)
            assertEquals(1215, bitmap.height)
            val generation = bitmap.generationId
            val settings = ThemeSettings(ReadingMode.ADAPTIVE)
            val theme = PixelTheme(settings)
            val bounds = listOf(RasterRegion(.5550139f, .50635785f, .89252234f, .849006f))
            val output = BitmapTheme.apply(bitmap, settings, CancellationSignal(), bounds)
            try {
                val paper = 0xff86d07d.toInt()
                val mappedPaper = theme.color(paper)
                val ramp = requireNotNull(DocumentColors.inkRamp(theme.color(-1), mappedPaper))
                val before = IntArray(670)
                val after = IntArray(670)
                var cores = 0
                var edges = 0
                var fills = 0
                for (y in 610 until 660) {
                    bitmap.getPixels(before, 0, 160, 920, y, 160, 1)
                    output.getPixels(after, 0, 160, 920, y, 160, 1)
                    for (x in 0 until 160) {
                        val coverage = DocumentColors.inkCoverage(before[x], paper, -1)
                        if (coverage > 0) {
                            assertEquals("Whole late Layer 10 glyph at ${x + 920},$y", ramp[coverage], after[x])
                            if (coverage == 255) {
                                assertTrue(DocumentColors.contrast(after[x], mappedPaper) >= 4.5)
                                cores++
                            } else edges++
                        } else if (before[x] == paper) {
                            assertEquals("Layer 10 fill at ${x + 920},$y", mappedPaper, after[x])
                            fills++
                        }
                    }
                }
                assertEquals(204, cores)
                assertEquals(822, edges)
                assertEquals(6788, fills)
                var colorful = 0
                val photoColors = HashSet<Int>()
                // Same strict interior as the heavy PPTX oracle: [.57,.53,.88,.82].
                for (y in 643 until 997) {
                    bitmap.getPixels(before, 0, 670, 1231, y, 670, 1)
                    output.getPixels(after, 0, 670, 1231, y, 670, 1)
                    for (x in before.indices) {
                        assertEquals("Photo pixel at ${x + 1231},$y", before[x], after[x])
                        val color = before[x]
                        val r = color ushr 16 and 255; val g = color ushr 8 and 255; val b = color and 255
                        if (maxOf(r, g, b) - minOf(r, g, b) > 40) colorful++
                        if (photoColors.size < 64) photoColors.add(color)
                    }
                }
                assertTrue(colorful > 100)
                assertTrue(photoColors.size >= 12)
                assertEquals("The original full slide is unchanged", generation, bitmap.generationId)
                assertFalse(bitmap.isRecycled)
            } finally { output.recycle() }
        } finally { bitmap.recycle() }
    }

    @Test fun actualWorksheetHeaderKeepsWhitePaperBetweenColoredGlyphs() {
        // Exact first 66 rows of the pinned 1080-wide dense worksheet ORIGINAL capture.
        val width = 1080
        val rows = 66
        val rgb = ByteArray(width * rows * 3)
        // Android packages the source .rgb.gz fixture as a decoded .rgb asset.
        InstrumentationRegistry.getInstrumentation().context.assets.open("theme/xlsx-header.rgb").use { asset ->
            DataInputStream(asset).use { input ->
                input.readFully(rgb)
                assertEquals("No trailing fixture data", -1, input.read())
            }
        }
        val header = IntArray(width * rows) { index ->
            val at = index * 3
            -0x1000000 or ((rgb[at].toInt() and 255) shl 16) or
                ((rgb[at + 1].toInt() and 255) shl 8) or (rgb[at + 2].toInt() and 255)
        }
        val settings = ThemeSettings(ReadingMode.ADAPTIVE)
        val theme = PixelTheme(settings)
        // The observed row 22 false bracket lies on/beside a processing-band boundary.
        for (padding in listOf(0, 42, 63)) {
            val height = rows + padding
            val source = IntArray(width * height) { -1 }
            header.copyInto(source, padding * width)
            val bitmap = Bitmap.createBitmap(source, width, height, Bitmap.Config.ARGB_8888)
            try {
                val output = BitmapTheme.apply(bitmap, settings, CancellationSignal(), emptyList())
                try {
                    val actual = IntArray(source.size)
                    output.getPixels(actual, 0, width, 0, 0, width, height)
                    var paper = 0
                    var ink = 0
                    for (index in source.indices) {
                        assertEquals("Header pixel $index with padding $padding", theme.color(source[index]), actual[index])
                        if (source[index] == -1) paper++ else ink++
                    }
                    assertTrue("Whole white header paper was exercised", paper > 60000)
                    assertTrue("Actual colored glyphs were exercised", ink > 1000)
                    bitmap.getPixels(actual, 0, width, 0, 0, width, height)
                    assertArrayEquals("The original header is unchanged", source, actual)
                } finally { output.recycle() }
            } finally { bitmap.recycle() }
        }
    }

    @Test fun actualChartOverlapKeepsVisibleWhiteGlyphsCoherent() {
        // Exact ORIGINAL pixels [510,385,840,670] from the layered PPTX page 0 at width 2160.
        val width = 330
        val rows = 285
        val rgb = ByteArray(width * rows * 3)
        InstrumentationRegistry.getInstrumentation().context.assets.open("theme/pptx-chart-overlap.rgb").use { asset ->
            DataInputStream(asset).use { input ->
                input.readFully(rgb)
                assertEquals("No trailing fixture data", -1, input.read())
            }
        }
        val crop = IntArray(width * rows) { index ->
            val at = index * 3
            -0x1000000 or ((rgb[at].toInt() and 255) shl 16) or
                ((rgb[at + 1].toInt() and 255) shl 8) or (rgb[at + 2].toInt() and 255)
        }
        val settings = ThemeSettings(ReadingMode.ADAPTIVE)
        val theme = PixelTheme(settings)
        val paper = 0xff52cc7d.toInt() // Layer 38, exposed on both sides of chart bars.
        val mappedPaper = theme.color(paper)
        val ramp = requireNotNull(DocumentColors.inkRamp(theme.color(-1), mappedPaper))
        val paper9 = 0xff7bbf78.toInt()
        val ramp9 = requireNotNull(DocumentColors.inkRamp(theme.color(-1), theme.color(paper9)))
        for (padding in listOf(1, 63)) {
            val height = rows + padding
            val source = IntArray(width * height) { -1 }
            crop.copyInto(source, padding * width)
            val bitmap = Bitmap.createBitmap(source, width, height, Bitmap.Config.ARGB_8888)
            try {
                val output = BitmapTheme.apply(bitmap, settings, CancellationSignal(), emptyList())
                try {
                    val actual = IntArray(source.size)
                    output.getPixels(actual, 0, width, 0, 0, width, height)
                    var cores = 0
                    var edges = 0
                    var fills = 0
                    for (y in 167 until 247) for (x in 28 until 128) {
                        val index = (y + padding) * width + x
                        val coverage = DocumentColors.inkCoverage(source[index], paper, -1)
                        if (coverage > 0) {
                            assertEquals("Whole Layer 38 glyph $x,$y padding $padding", ramp[coverage], actual[index])
                            if (coverage == 255) cores++ else edges++
                        } else if (source[index] == paper) {
                            assertEquals("Layer 38 fill $x,$y", mappedPaper, actual[index])
                            fills++
                        }
                    }
                    assertEquals(157, cores)
                    assertEquals(680, edges)
                    assertTrue(fills > 1000)
                    var cores9 = 0
                    // Layer 9 also has authored soft shadows. Check every actual white core;
                    // do not reinterpret its nonwhite shadow pixels as a flat-paper AA family.
                    for (y in 41 until 86) for (x in 143 until 271) {
                        val index = (y + padding) * width + x
                        if (source[index] == -1) {
                            assertEquals("Layer 9 white core $x,$y", ramp9[255], actual[index])
                            cores9++
                        }
                    }
                    assertEquals(223, cores9)
                    // These four solid, closed chart bars are preserved chromatic plates.
                    // Pin their exact source geometry and every output fill pixel; they do not
                    // follow the ordinary PixelTheme mapping used by the exposed label fills.
                    val chartBars = arrayOf(
                        intArrayOf(68, 0, 93, 283, 0xff4f81bd.toInt()),
                        intArrayOf(238, 55, 263, 283, 0xff4f81bd.toInt()),
                        intArrayOf(94, 0, 120, 283, 0xffc0504d.toInt()),
                        intArrayOf(265, 82, 290, 283, 0xffc0504d.toInt()),
                    )
                    for (bar in chartBars) for (y in bar[1] until bar[3]) for (x in bar[0] until bar[2]) {
                        val index = (y + padding) * width + x
                        assertEquals("Authored chart fill $x,$y", bar[4], source[index])
                        assertEquals("Preserved chart fill $x,$y padding $padding", bar[4], actual[index])
                    }
                    assertEquals("Exact blue chart population", 12_775, source.count { it == 0xff4f81bd.toInt() })
                    assertEquals("Exact red chart population", 12_383, source.count { it == 0xffc0504d.toInt() })
                    bitmap.getPixels(actual, 0, width, 0, 0, width, height)
                    assertArrayEquals("The original overlap crop is unchanged", source, actual)
                } finally { output.recycle() }
            } finally { bitmap.recycle() }
        }
    }

    @Test fun chartOverlapCorrectionRejectsImageAndTransparentEdges() {
        val width = 96
        val height = 40
        val paper = 0xff86d07d.toInt()
        val bar = 0xffaabbdd.toInt()
        val settings = ThemeSettings(ReadingMode.ADAPTIVE)
        val theme = PixelTheme(settings)
        val ramp = requireNotNull(DocumentColors.inkRamp(theme.color(-1), theme.color(paper)))
        for (edge in 0..2) {
            val source = IntArray(width * height) { index -> if (index % width < 46) paper else bar }
            for (y in 14 until 22) {
                for (x in 40 until 46) source[y * width + x] = DocumentColors.blend(paper, -1, if (y % 2 == 0) 128 else 255)
                if (edge == 2) source[y * width + 46] = 0xfeaabbdd.toInt()
            }
            val hints = if (edge == 1) listOf(RasterRegion(46f / width, 0f, 1f, 1f)) else emptyList()
            val bitmap = Bitmap.createBitmap(source, width, height, Bitmap.Config.ARGB_8888)
            try {
                val output = BitmapTheme.apply(bitmap, settings, CancellationSignal(), hints)
                try {
                    val actual = IntArray(source.size)
                    output.getPixels(actual, 0, width, 0, 0, width, height)
                    for (y in 14 until 22) for (x in 40 until 46) {
                        val index = y * width + x
                        val amount = DocumentColors.inkCoverage(source[index], paper, -1)
                        assertEquals("Terminator $edge at $x,$y", if (edge == 0) ramp[amount] else theme.color(source[index]), actual[index])
                    }
                    if (edge == 1) for (y in 0 until height) for (x in 46 until width)
                        assertEquals("Protected image $x,$y", source[y * width + x], actual[y * width + x])
                } finally { output.recycle() }
            } finally { bitmap.recycle() }
        }
    }

    @Test fun nativeSlideshowColoredTitleRetainsReadableContrastWithImagePlacementHints() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("title-contrast-", ".ppsx", context.cacheDir)
        val ink = 0xff235d75.toInt()
        val paper = 0xff18a303.toInt()
        val corrected = DocumentColors.contrastingInk(ink, paper)
        try {
            context.assets.open("examples/office/office-guide.ppsx").use { input ->
                file.outputStream().use(input::copyTo)
            }
            val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            DocumentEngine.open(context, descriptor, file.name,
                "application/vnd.openxmlformats-officedocument.presentationml.slideshow", CancellationSignal()).use { session ->
                val original = session.renderPage(0, 1080, ThemeSettings(ReadingMode.ORIGINAL), CancellationSignal())
                try {
                    val regions = session.rasterRegions(0, CancellationSignal())
                    assertNotNull("Native placement hints must exercise the hinted theme path", regions)
                    val output = BitmapTheme.apply(original, ThemeSettings(ReadingMode.ADAPTIVE), CancellationSignal(), regions)
                    try {
                        val sourceRow = IntArray(original.width)
                        val outputRow = IntArray(original.width)
                        val edgeLevels = BooleanArray(256)
                        var tested = 0
                        var plate = 0
                        var edges = 0
                        for (y in 0 until original.height) {
                            original.getPixels(sourceRow, 0, original.width, 0, y, original.width, 1)
                            output.getPixels(outputRow, 0, original.width, 0, y, original.width, 1)
                            for (x in sourceRow.indices) {
                                val source = sourceRow[x]
                                val actual = outputRow[x]
                                when (source) {
                                    paper -> { assertEquals("Title plate at $x,$y", paper, actual); plate++ }
                                    ink -> { assertEquals("Title at $x,$y", corrected, actual); tested++ }
                                    else -> {
                                        val coverage = DocumentColors.inkCoverage(source, paper, ink)
                                        if (coverage in 1..254) {
                                            // Partial glyph coverage needs its original contrast, not a full-ink 4.5 ratio.
                                            assertTrue("Title edge contrast at $x,$y (coverage=$coverage)",
                                                DocumentColors.contrast(actual, paper) + 1e-6 >= DocumentColors.contrast(source, paper))
                                            if (coverage in 16..239) { edges++; edgeLevels[coverage] = true }
                                        }
                                    }
                                }
                            }
                        }
                        assertTrue("Actual authored blue title was rendered", tested > 100)
                        assertTrue("Actual green title plate was rendered", plate > 1000)
                        assertTrue("Actual partial title edges were exercised", edges > 20 && edgeLevels.count { it } >= 4)
                        assertTrue(DocumentColors.contrast(corrected, paper) >= 4.5)
                    } finally { output.recycle() }
                } finally { original.recycle() }
            }
        } finally { file.delete() }
    }

    @Test fun nativeOfficeCoversAdaptPaperWithoutChangingColoredHeadersOrSourcePixels() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = ThemeSettings(ReadingMode.ADAPTIVE)
        val theme = PixelTheme(settings)
        for ((extension, mime) in listOf("pdf" to "application/pdf",
            "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation")) {
            val file = File.createTempFile("cover-theme-", ".$extension", context.cacheDir)
            try {
                context.assets.open("examples/office/office-guide.$extension").use { input ->
                    file.outputStream().use(input::copyTo)
                }
                val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                DocumentEngine.open(context, descriptor, file.name, mime, CancellationSignal()).use { session ->
                    val cases = listOf(0 to 144, 0 to 240) + if (extension == "pdf") listOf(1 to 1080) else emptyList()
                    for ((page, width) in cases) {
                        val original = session.renderPage(page, width, ThemeSettings(ReadingMode.ORIGINAL), CancellationSignal())
                        try {
                            val height = original.height
                            val pixels = IntArray(width * height)
                            original.getPixels(pixels, 0, width, 0, 0, width, height)
                            val output = BitmapTheme.apply(original, settings, CancellationSignal())
                            try {
                                val actual = IntArray(pixels.size)
                                output.getPixels(actual, 0, width, 0, 0, width, height)
                                var paper = 0
                                var ink = 0
                                var colored = 0
                                var lightInk = 0
                                for (index in pixels.indices) {
                                    val value = pixels[index]
                                    val x = index % width
                                    val y = index / width
                                    val r = value ushr 16 and 255
                                    val g = value ushr 8 and 255
                                    val b = value and 255
                                    val chroma = maxOf(r, g, b) - minOf(r, g, b)
                                    val body = if (extension == "pdf") y * 100 >= height * 29 else
                                        x * 100 < width * 4 || x * 100 > width * 94 ||
                                            y * 100 > height * 73 || y * 100 in height * 33..height * 37
                                    val plate = if (extension == "pdf") y * 100 < height * 26 else
                                        x * 100 in width * 7..width * 90 &&
                                            (y * 100 in height * 14..height * 29 || y * 100 in height * 41..height * 66)
                                    if (extension == "pdf" && plate)
                                        assertEquals("PDF page $page/$width header at $x,$y", value, actual[index])
                                    if (plate && minOf(r, g, b) >= 160) {
                                        assertEquals("$extension/$width light lettering at $x,$y", value, actual[index])
                                        lightInk++
                                    } else if (chroma > 40) {
                                        val titleCoverage = if (extension == "pptx") DocumentColors.inkCoverage(value,
                                            0xff18a303.toInt(), 0xff235d75.toInt()) else -1
                                        if (titleCoverage > 0) assertTrue("$extension/$width title contrast at $x,$y",
                                            DocumentColors.contrast(actual[index], 0xff18a303.toInt()) + 0.02 >=
                                                DocumentColors.contrast(value, 0xff18a303.toInt()))
                                        else assertEquals("$extension/$width chromatic mark at $x,$y", value, actual[index])
                                        colored++
                                    } else if (body && chroma <= 40) {
                                        assertEquals("$extension/$width paper/ink at $x,$y", theme.color(value), actual[index])
                                        if (r == 255) paper++ else if (r < 210) ink++
                                    }
                                }
                                assertTrue("$extension/$width must exercise paper and colored regions", paper > width && colored > width)
                                if (extension == "pdf") assertTrue("Native PDF contains body ink", ink > 0)
                                assertTrue("$extension/$width contains light lettering", lightInk > 0)
                            } finally { output.recycle() }
                            val unchanged = IntArray(pixels.size)
                            original.getPixels(unchanged, 0, width, 0, 0, width, height)
                            assertArrayEquals("The original cover remains reusable", pixels, unchanged)
                            assertFalse(original.isRecycled)
                        } finally { original.recycle() }
                    }
                }
            } finally { file.delete() }
        }
    }

    @Test fun nativeNeutralPanelsAndInkSurviveDirectAndBoundedCoverSizes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val settings = ThemeSettings(ReadingMode.ADAPTIVE)
        val theme = PixelTheme(settings)
        fun checkCover(source: Bitmap, label: String) {
            val width = source.width
            val height = source.height
            val pixels = IntArray(width * height)
            source.getPixels(pixels, 0, width, 0, 0, width, height)
            val output = BitmapTheme.apply(source, settings, CancellationSignal())
            try {
                assertNotSame(source, output)
                assertEquals(width, output.width); assertEquals(height, output.height)
                val actual = IntArray(pixels.size)
                output.getPixels(actual, 0, width, 0, 0, width, height)
                var darkFill = 0
                var peakHeaderInk = 0
                var headerAA = 0
                var paper = 0
                var bodyInk = 0
                var bodyAA = 0
                // Rectangles use the fixture's 600 x 840 point geometry. Only the two-pixel
                // boundary between different authored fills is excluded; all glyph AA remains.
                for (y in 2 until height - 2) for (x in 2 until width - 2) {
                    val index = y * width + x
                    val value = pixels[index]
                    val header = y < height / 10 - 2
                    val darkHeader = header && x < width / 2 - 2
                    val blueHeader = header && x > width / 2 + 2
                    val body = y * 100 in height * 11..height * 36
                    val middle = y > height * 314 / 840 + 2 && y < height * 504 / 840 - 2
                    val darkMiddle = middle && x < width * 126 / 600 - 2
                    val whiteMiddle = middle && x > width * 474 / 600 + 2
                    val darkFooter = y > height * 740 / 840 + 2 && x > width / 2 + 2
                    if (darkHeader || blueHeader || darkMiddle || darkFooter) {
                        assertEquals("$label authored panel/ink at $x,$y", value, actual[index])
                    } else if (body || whiteMiddle) {
                        assertEquals("$label paper/body ink at $x,$y", theme.color(value), actual[index])
                    }
                    val r = value ushr 16 and 255
                    val g = value ushr 8 and 255
                    val b = value and 255
                    if (darkHeader) {
                        if (value == 0xff090b11.toInt()) darkFill++
                        // A fitted glyph can have antialiasing without a near-white core.
                        peakHeaderInk = maxOf(peakHeaderInk, minOf(r, g, b))
                        if (minOf(r, g, b) > 20 && maxOf(r, g, b) < 245) headerAA++
                    }
                    if (body) {
                        if (value == -1) paper++
                        if (maxOf(r, g, b) < 128) bodyInk++
                        if (maxOf(r, g, b) in 64..244) bodyAA++
                    }
                }
                assertTrue("$label contains flat dark header (pixels=$darkFill)", darkFill > width)
                assertTrue("$label exercises native header antialiasing (pixels=$headerAA, peak=$peakHeaderInk)", headerAA > 0)
                assertTrue("$label contains white paper and dark body lettering", paper > width && bodyInk > 0)
                assertTrue("$label exercises native body antialiasing", bodyAA > 0)
                source.getPixels(actual, 0, width, 0, 0, width, height)
                assertArrayEquals("$label leaves its input reusable", pixels, actual)
                assertFalse(source.isRecycled)
            } catch (failure: AssertionError) {
                val stem = "alldoc-check-cover-${label.replace(' ', '-')}"
                fun save(image: Bitmap, suffix: String) {
                    File(context.cacheDir, "$stem-$suffix.png").outputStream().use {
                        check(image.compress(Bitmap.CompressFormat.PNG, 100, it))
                    }
                }
                // Only this authored fixture: preserve its exact pre-transform pixels, even if mutated.
                runCatching {
                    val snapshot = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
                    try { save(snapshot, "source") } finally { snapshot.recycle() }
                }
                runCatching { save(output, "output") }
                throw failure // Diagnostic allocation or I/O must never replace the original assertion.
            } finally { if (output !== source) output.recycle() }
        }
        val file = File.createTempFile("contrast-cover-", ".pdf", context.cacheDir)
        try {
            instrumentation.context.assets.open("theme/contrast-tour.pdf").use { input ->
                file.outputStream().use(input::copyTo)
            }
            val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            DocumentEngine.open(context, descriptor, file.name, "application/pdf", CancellationSignal()).use { session ->
                for ((width, height) in listOf(137 to 191, 144 to 201, 229 to 320, 240 to 336)) {
                    val original = session.renderPage(0, width, ThemeSettings(ReadingMode.ORIGINAL), CancellationSignal())
                    try {
                        assertEquals("Native width", width, original.width)
                        assertEquals("Native height at width $width", height, original.height)
                        val generation = original.generationId
                        checkCover(original, "native $width x $height")
                        assertFalse(original.isRecycled)
                        assertEquals("Rendering a cover must not mutate its native source", generation, original.generationId)
                    } finally { original.recycle() }
                }
                for (bounds in listOf(intArrayOf(144, 192, 137, 191), intArrayOf(240, 320, 228, 319))) {
                    val original = DocumentPreviews.nativePage(session, 0, CancellationSignal(), bounds[0], bounds[1])
                    try {
                        assertEquals("Bounded native width", bounds[2], original.width)
                        assertEquals("Bounded native height", bounds[3], original.height)
                        val generation = original.generationId
                        val cached = DocumentPreviews.page(original, -1, bounds[0], bounds[1])
                        try {
                            assertNotSame("The cache owns its bitmap", original, cached)
                            assertEquals(original.width, cached.width)
                            assertEquals(original.height, cached.height)
                            assertTrue("Cache copying must not resample native antialiasing", original.sameAs(cached))
                            checkCover(cached, "bounded ${bounds[0]} x ${bounds[1]}")
                        } finally { cached.recycle() }
                        assertFalse(original.isRecycled)
                        assertEquals("Cache/theme work must leave its native source reusable", generation, original.generationId)
                    } finally { original.recycle() }
                }
            }
        } finally { file.delete() }
    }

    @Test fun authoredWhiteInkOnColoredPlatesSurvivesSamplingPhasesAndPartialBandEdges() {
        val width = 145
        val height = 133
        fun blend(paper: Int, coverage: Int): Int {
            var color = 0xff000000.toInt()
            for (shift in intArrayOf(16, 8, 0)) {
                val channel = paper ushr shift and 255
                color = color or ((channel + (255 - channel) * coverage / 255) shl shift)
            }
            return color
        }
        for (paper in intArrayOf(0xff157e9e.toInt(), 0xffe96951.toInt())) for (phase in 0 until 16) {
            val dx = phase % 4
            val dy = phase / 4
            val sourcePixels = IntArray(width * height) { index ->
                val x = index % width
                val y = index / width
                if (x < 8 || y < 4) -1 else {
                    // A C-shaped white mark near surrounding white paper, a stem across x64,
                    // and another stem across the final partial-width/height tiles.
                    val cornerC = x in 16 + dx..20 + dx && y in 12 + dy..32 + dy ||
                        x in 16 + dx..34 + dx && (y in 12 + dy..16 + dy || y in 28 + dy..32 + dy)
                    val crossingStem = x in 62 + dx..65 + dx && y in 40 + dy..92 + dy
                    val partialStem = x in 130 + dx..133 + dx && y in 112 + dy until height
                    if (cornerC || crossingStem || partialStem) -1
                    else if (x == 61 + dx && y in 40 + dy..92 + dy ||
                        x == 129 + dx && y in 112 + dy until height) blend(paper, 128 + (y % 8) * 16)
                    else paper
                }
            }
            val source = Bitmap.createBitmap(sourcePixels, width, height, Bitmap.Config.ARGB_8888)
            try {
                for (mode in listOf(ReadingMode.ADAPTIVE, ReadingMode.ADAPTIVE_LIGHT,
                    ReadingMode.SYSTEM, ReadingMode.SEPIA, ReadingMode.HIGH_CONTRAST)) {
                    val settings = ThemeSettings(mode)
                    val output = BitmapTheme.apply(source, settings, CancellationSignal())
                    try {
                        val actual = IntArray(sourcePixels.size)
                        output.getPixels(actual, 0, width, 0, 0, width, height)
                        val mappedPaper = DocumentColors.palette(settings).background
                        for (index in actual.indices) {
                            val x = index % width
                            val y = index / width
                            val expected = if (x < 8 || y < 4) mappedPaper else sourcePixels[index]
                            assertEquals("$mode phase=$phase x=$x y=$y: authored ink/AA stays on its colored backdrop; exterior paper adapts",
                                expected, actual[index])
                        }
                        assertEquals(width, output.width); assertEquals(height, output.height)
                    } finally { output.recycle() }
                }
                val unchanged = IntArray(sourcePixels.size)
                source.getPixels(unchanged, 0, width, 0, 0, width, height)
                assertArrayEquals("The original raster must remain reusable", sourcePixels, unchanged)
                assertFalse(source.isRecycled)
            } finally { source.recycle() }
        }
    }

    @Test fun paperBetweenRecognizedPlatesStaysPaperAcrossSamplingAndBandEdges() {
        val width = 192
        val height = 69
        val blue = 0xff157e9e.toInt()
        val settings = ThemeSettings(ReadingMode.ADAPTIVE)
        val paper = DocumentColors.palette(settings).background
        for (right in intArrayOf(blue, 0xffe96951.toInt())) for (gutter in 63..66) {
            val pixels = IntArray(width * height) { index -> when {
                index % width < gutter -> blue
                index % width == gutter -> -1
                else -> right
            } }
            val expected = pixels.copyOf()
            for (y in 0 until height) expected[y * width + gutter] = paper
            val source = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
            try {
                val output = BitmapTheme.apply(source, settings, CancellationSignal())
                try {
                    assertNotSame(source, output)
                    val actual = IntArray(pixels.size)
                    output.getPixels(actual, 0, width, 0, 0, width, height)
                    assertArrayEquals("gutter=$gutter sameColor=${right == blue}: both plates and every paper pixel",
                        expected, actual)
                    source.getPixels(actual, 0, width, 0, 0, width, height)
                    assertArrayEquals("Original remains reusable", pixels, actual)
                    assertFalse(source.isRecycled)
                } finally { if (output !== source) output.recycle() }
            } finally { source.recycle() }
        }
    }

    @Test fun whiteInkAcrossRectangleEdgesDoesNotPreserveExteriorPaperOrBridgeDifferentColors() {
        val width = 192
        val height = 69
        val blue = 0xff157e9e.toInt()
        val coral = 0xffe96951.toInt()
        for (phase in 0 until 16) {
            val dx = phase % 4
            val dy = phase / 4
            val pixels = IntArray(width * height) { i ->
                val x = i % width; val y = i / width
                when {
                    x !in 32 + dx..145 + dx -> -1
                    x in 124 + dx..131 + dx && y in 20 + dy..43 + dy -> -1
                    x == 123 + dx && y in 20 + dy..43 + dy -> 0xffe7f3f6.toInt()
                    x in 130 + dx..133 + dx && y >= 64 -> -1
                    else -> blue
                }
            }
            val source = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
            val settings = ThemeSettings(ReadingMode.ADAPTIVE)
            val output = BitmapTheme.apply(source, settings, CancellationSignal())
            try {
                val actual = IntArray(pixels.size)
                output.getPixels(actual, 0, width, 0, 0, width, height)
                for (i in pixels.indices) {
                    val x = i % width
                    val expected = if (x in 32 + dx..145 + dx) pixels[i] else DocumentColors.palette(settings).background
                    assertEquals("phase=$phase x=$x y=${i / width}", expected, actual[i])
                }
                val original = IntArray(pixels.size)
                source.getPixels(original, 0, width, 0, 0, width, height)
                assertArrayEquals(pixels, original)
            } finally { output.recycle(); source.recycle() }
        }
        val separate = IntArray(width * 64) { i -> when (i % width) {
            in 32..79 -> blue; in 96..145 -> coral; else -> -1
        } }
        val source = Bitmap.createBitmap(separate, width, 64, Bitmap.Config.ARGB_8888)
        val settings = ThemeSettings(ReadingMode.ADAPTIVE)
        val output = BitmapTheme.apply(source, settings, CancellationSignal())
        try {
            for (y in 0 until 64) for (x in 80..95)
                assertEquals("Paper between different objects x=$x y=$y", DocumentColors.palette(settings).background, output.getPixel(x, y))
        } finally { output.recycle(); source.recycle() }
    }


    @Test fun mixedPageRetainsPhotoAndDarkTilesWhileRecoloringPaperAndInk() {
        val width = 192
        val pixels = IntArray(width * 64) { index ->
            val x = index % width
            val y = index / width
            when {
                x >= 128 -> 0xff121212.toInt()
                x >= 64 -> {
                    val gray = 55 + (x + y) % 130
                    0xff000000.toInt() or (gray shl 16) or (gray shl 8) or gray
                }
                x in 10..40 && y in 20..24 -> 0xff000000.toInt()
                else -> -1
            }
        }
        val source = Bitmap.createBitmap(pixels, width, 64, Bitmap.Config.ARGB_8888)
        try {
            val output = BitmapTheme.apply(source, ThemeSettings(ReadingMode.ADAPTIVE), CancellationSignal())
            try {
                assertNotSame(source, output)
                val palette = DocumentColors.palette(ThemeSettings(ReadingMode.ADAPTIVE))
                assertEquals(palette.background, output.getPixel(2, 2))
                assertEquals(palette.foreground, output.getPixel(20, 22))
                for (x in 64 until width) assertEquals(source.getPixel(x, 20), output.getPixel(x, 20))
                val after = IntArray(pixels.size)
                source.getPixels(after, 0, width, 0, 0, width, 64)
                assertArrayEquals(pixels, after)
                assertFalse(source.isRecycled)
            } finally { output.recycle() }
        } finally { source.recycle() }
    }

    @Test fun allModesHandlePartialEdgeTilesAndHaveExplicitOwnership() {
        val pixels = IntArray(65 * 67) { -1 }.also { it[20 * 65 + 20] = 0xff000000.toInt() }
        val source = Bitmap.createBitmap(pixels, 65, 67, Bitmap.Config.ARGB_8888)
        try {
            for (mode in ReadingMode.entries) {
                val settings = ThemeSettings(mode, preserveImages = false)
                val output = BitmapTheme.apply(source, settings, CancellationSignal())
                try {
                    if (mode == ReadingMode.ORIGINAL) assertSame(source, output) else assertNotSame(source, output)
                    val expected = PixelTheme(settings)
                    assertEquals(expected.color(-1), output.getPixel(64, 66))
                    assertEquals(expected.color(0xff000000.toInt()), output.getPixel(20, 20))
                    assertEquals(65, output.width)
                    assertEquals(67, output.height)
                } finally { if (output !== source) output.recycle() }
            }
            assertEquals(-1, source.getPixel(64, 66))
            assertFalse(source.isRecycled)
        } finally { source.recycle() }
    }

    @Test fun explicitPreservationKeepsScansWhileAutomaticAndRecolorAdaptMutedPaper() {
        val pixels = IntArray(64 * 64) { if (it % 7 == 0) 0xff202020.toInt() else 0xffAAAAAA.toInt() }
        val source = Bitmap.createBitmap(pixels, 64, 64, Bitmap.Config.ARGB_8888)
        try {
            for (settings in listOf(ThemeSettings(ReadingMode.ADAPTIVE, preserveImages = true),
                ThemeSettings(ReadingMode.ADAPTIVE), ThemeSettings(ReadingMode.ADAPTIVE, preserveImages = false))) {
                val output = BitmapTheme.apply(source, settings, CancellationSignal())
                try {
                    val paper = output.getPixel(2, 2)
                    val ink = output.getPixel(0, 0)
                    if (settings.imageTreatment == ImageTreatment.KEEP) {
                        assertEquals(source.getPixel(2, 2), paper)
                        assertEquals(source.getPixel(0, 0), ink)
                    } else for (shift in intArrayOf(16, 8, 0)) {
                        assertTrue("${settings.imageTreatment} darkens muted paper", (paper ushr shift and 255) < 170)
                        assertTrue("${settings.imageTreatment} keeps ink brighter than paper", (ink ushr shift and 255) > (paper ushr shift and 255))
                    }
                } finally { output.recycle() }
            }
            assertFalse(source.isRecycled)
            assertEquals(0xffAAAAAA.toInt(), source.getPixel(2, 2))
            assertEquals(0xff202020.toInt(), source.getPixel(0, 0))
        } finally { source.recycle() }
    }

    @Test fun adaptiveLightNormalizesDarkPaperAndPreservesAdjacentPhotos() {
        val pixels = IntArray(128 * 64) { index ->
            val x = index % 128
            when {
                x < 64 -> if (x in 8..40 && index / 128 in 20..24) -1 else 0xff000000.toInt()
                else -> {
                    val gray = 55 + index % 130
                    0xff000000.toInt() or (gray shl 16) or (gray shl 8) or gray
                }
            }
        }
        val source = Bitmap.createBitmap(pixels, 128, 64, Bitmap.Config.ARGB_8888)
        try {
            val settings = ThemeSettings(ReadingMode.ADAPTIVE_LIGHT)
            val output = BitmapTheme.apply(source, settings, CancellationSignal())
            try {
                val palette = DocumentColors.palette(settings)
                assertEquals(palette.background, output.getPixel(2, 2))
                assertEquals(palette.foreground, output.getPixel(20, 22))
                for (x in 64 until 128) assertEquals(source.getPixel(x, 20), output.getPixel(x, 20))
                assertEquals(0xff000000.toInt(), source.getPixel(2, 2))
                assertEquals(-1, source.getPixel(20, 22))
            } finally { output.recycle() }
        } finally { source.recycle() }
    }

    @Test fun transparentGlyphsKeepConsistentInkAcrossOpaqueInteriorTiles() {
        for (ink in intArrayOf(0xff000000.toInt(), -1, 0x80000000.toInt(), 0x80ffffff.toInt())) {
            val pixels = IntArray(256 * 192) { index ->
                if (index % 256 in 48..207 && index / 256 in 32..159) ink else 0
            }
            val source = Bitmap.createBitmap(pixels, 256, 192, Bitmap.Config.ARGB_8888)
            try {
                for (mode in ReadingMode.entries) for (preserve in listOf(false, true)) {
                    val settings = ThemeSettings(mode, preserve)
                    val output = BitmapTheme.apply(source, settings, CancellationSignal())
                    try {
                        val expected = when (mode) {
                            ReadingMode.ORIGINAL -> ink
                            ReadingMode.INVERT -> ink xor 0x00ffffff
                            else -> (ink and -0x1000000) or (DocumentColors.palette(settings).foreground and 0x00ffffff)
                        }
                        for ((label, pixel) in listOf("interior" to output.getPixel(96, 96), "edge" to output.getPixel(48, 32))) {
                            assertEquals("$mode/$preserve $label alpha", ink ushr 24, pixel ushr 24)
                            if (ink ushr 24 == 255 || mode == ReadingMode.ORIGINAL || mode == ReadingMode.INVERT)
                                assertEquals("$mode/$preserve $label", expected, pixel)
                            else for (shift in intArrayOf(16, 8, 0)) assertTrue("$mode/$preserve $label channel",
                                kotlin.math.abs((expected ushr shift and 255) - (pixel ushr shift and 255)) <= 1)
                        }
                        assertEquals(0, output.getPixel(0, 0) ushr 24)
                    } finally { if (output !== source) output.recycle() }
                }
                val unchanged = IntArray(pixels.size)
                source.getPixels(unchanged, 0, 256, 0, 0, 256, 192)
                assertArrayEquals(pixels, unchanged)
            } finally { source.recycle() }
        }
    }

    @Test fun flatColoredDiagramKeepsItsColorWithoutPaperTileHalos() {
        val width = 512
        val height = 192
        val blue = 0xff285ca0.toInt()
        val pixels = IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            when {
                x >= 384 -> when {
                    x % 64 < 8 -> blue
                    y % 64 < 8 -> -1
                    else -> {
                        val gray = 55 + index % 130
                        0xff000000.toInt() or (gray shl 16) or (gray shl 8) or gray
                    }
                }
                x >= 256 -> 0xff000000.toInt() or ((60 + x % 80) shl 16) or ((70 + y % 100) shl 8) or (80 + (x * 7 + y * 11) % 120)
                x in 45..209 && y in 56..131 -> blue
                x in 4..115 && y in 8..15 -> {
                    val gray = 16 + (x % 64 / 4) * 13
                    0xff000000.toInt() or (gray shl 16) or (gray shl 8) or gray
                }
                else -> -1
            }
        }
        val source = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        try {
            for (mode in listOf(ReadingMode.ADAPTIVE, ReadingMode.ADAPTIVE_LIGHT, ReadingMode.SEPIA, ReadingMode.HIGH_CONTRAST, ReadingMode.SYSTEM)) {
                val settings = ThemeSettings(mode)
                val output = BitmapTheme.apply(source, settings, CancellationSignal())
                try {
                    val theme = PixelTheme(settings)
                    val expected = IntArray(pixels.size) { index ->
                        if (index % width >= 256 || pixels[index] == blue) pixels[index] else theme.color(pixels[index])
                    }
                    val actual = IntArray(pixels.size)
                    output.getPixels(actual, 0, width, 0, 0, width, height)
                    assertArrayEquals("$mode antialiased text, diagram edges and grayscale/color photographs", expected, actual)
                } finally { output.recycle() }
            }
            val unchanged = IntArray(pixels.size)
            source.getPixels(unchanged, 0, width, 0, 0, width, height)
            assertArrayEquals(pixels, unchanged)
        } finally { source.recycle() }
    }

    @Test fun imagePoliciesSpanTileEdgesAndDimmingPreservesSourceOwnership() {
        val width = 129
        val height = 67
        val pixels = IntArray(width * height) { index ->
            if (index % width < 64) 0xffdce8f0.toInt() else {
                val gray = 55 + index % 130
                0x80000000.toInt() or (gray shl 16) or (gray shl 8) or gray
            }
        }
        val source = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        try {
            val settings = ThemeSettings(ReadingMode.ADAPTIVE, ImageTreatment.DIM)
            val output = BitmapTheme.apply(source, settings, CancellationSignal())
            try {
                val expected = PixelTheme(settings)
                for (y in intArrayOf(0, 63, 66)) for (x in intArrayOf(64, 127, 128)) {
                    val original = source.getPixel(x, y)
                    val actual = output.getPixel(x, y)
                    val dimmed = expected.dim(original)
                    assertEquals(original ushr 24, actual ushr 24)
                    for (shift in intArrayOf(16, 8, 0)) assertTrue(kotlin.math.abs(
                        (dimmed ushr shift and 255) - (actual ushr shift and 255)) <= 1)
                }
                assertFalse(source.isRecycled)
                assertEquals(0xffdce8f0.toInt(), source.getPixel(0, 0))
            } finally { output.recycle() }
            // A real opaque page follows the same auto background policy across complete and edge tiles.
            val paper = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(0xffdce8f0.toInt()) }
            try {
                val automatic = ThemeSettings(ReadingMode.ADAPTIVE)
                val themed = BitmapTheme.apply(paper, automatic, CancellationSignal())
                try {
                    val expected = PixelTheme(automatic).color(0xffdce8f0.toInt())
                    for (y in intArrayOf(0, 63, 66)) for (x in intArrayOf(0, 63, 64, 128))
                        assertEquals(expected, themed.getPixel(x, y))
                } finally { themed.recycle() }
            } finally { paper.recycle() }
        } finally { source.recycle() }
    }

    @Test fun canceledWorkDoesNotMutateOrRecycleTheSourceEvenInOriginalMode() {
        val source = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        source.eraseColor(0xff445566.toInt())
        try {
            for (mode in ReadingMode.entries) {
                val signal = CancellationSignal().also { it.cancel() }
                try {
                    BitmapTheme.apply(source, ThemeSettings(mode), signal)
                    fail("Canceled rendering returned a bitmap")
                } catch (_: OperationCanceledException) {
                    assertFalse(source.isRecycled)
                    assertEquals(0xff445566.toInt(), source.getPixel(0, 0))
                }
            }
        } finally { source.recycle() }
    }
}
