package app.smallthingz.alldoc.theme

import android.graphics.Bitmap
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.smallthingz.alldoc.engine.DocumentEngine
import app.smallthingz.alldoc.ui.DocumentPreviews
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BitmapThemeTest {
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
                                        assertEquals("$extension/$width chromatic mark at $x,$y", value, actual[index])
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
