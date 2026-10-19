package app.smallthingz.alldoc.engine

import android.graphics.Bitmap
import android.os.CancellationSignal
import android.os.OperationCanceledException
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.smallthingz.alldoc.core.Block
import app.smallthingz.alldoc.core.Document
import app.smallthingz.alldoc.core.Section
import app.smallthingz.alldoc.theme.DocumentColors
import app.smallthingz.alldoc.theme.ReadingMode
import app.smallthingz.alldoc.theme.ThemeSettings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TextSessionRegionTest {
    @Test fun readerThemePageUsesNativeTextColorsAndTheSameCanonicalPaperAsDetail() {
        val signal = CancellationSignal()
        val document = Document("txt", sections = listOf(Section("", blocks = listOf(Block("p", "Semantic theme continuity")))))
        TextSession("theme.txt", "text/plain", document, signal).use { session ->
            for (width in listOf(319, 640)) for (mode in listOf(ReadingMode.ADAPTIVE, ReadingMode.SEPIA)) {
                val settings = ThemeSettings(mode, paperRoughness = 100)
                val source = session.renderPage(0, width, ThemeSettings(ReadingMode.ORIGINAL), signal)
                val original = IntArray(source.width * source.height)
                source.getPixels(original, 0, source.width, 0, 0, source.width, source.height)
                val full = session.themePage(0, source, settings, signal)
                val direct = session.renderPage(0, width, settings, signal)
                try {
                    assertSame("Adopted mutable preview should not require another full page", source, full)
                    val expected = IntArray(original.size)
                    val actual = IntArray(original.size)
                    direct.getPixels(expected, 0, width, 0, 0, width, direct.height)
                    full.getPixels(actual, 0, width, 0, 0, width, full.height)
                    val originalColors = DocumentColors.palette(ThemeSettings(ReadingMode.ORIGINAL))
                    val targetColors = DocumentColors.palette(settings)
                    for (at in original.indices) {
                        if (original[at] == originalColors.background) assertEquals("Exact semantic paper at $at/$width/$mode", expected[at], actual[at])
                        if (original[at] == originalColors.foreground) assertEquals(targetColors.foreground, actual[at])
                    }
                    var maximumDelta = 0
                    var firstMismatch = -1
                    val withinTolerance = actual.indices.count { at ->
                        var pixelDelta = 0
                        for (shift in 0..16 step 8) pixelDelta = maxOf(pixelDelta,
                            kotlin.math.abs((actual[at] ushr shift and 255) - (expected[at] ushr shift and 255)))
                        maximumDelta = maxOf(maximumDelta, pixelDelta)
                        if (pixelDelta > 3 && firstMismatch < 0) firstMismatch = at
                        pixelDelta <= 3
                    }
                    assertTrue("Native AA $width/$mode: within=$withinTolerance/${actual.size}, " +
                        "maxDelta=$maximumDelta, firstMismatch=$firstMismatch", withinTolerance > actual.size * .999)
                    assertArrayEquals("Adopted and direct native page pixels at $width/$mode", expected, actual)
                    // This blank lower corner has no glyph-antialiasing ambiguity.
                    if (width == 640) {
                        val detail = session.renderRegion(0, PageRegion(.5f, .5f, 1f, 1f, 320, 450), settings, signal)
                        try {
                            val crop = IntArray(320 * 450)
                            val region = IntArray(crop.size)
                            full.getPixels(crop, 0, 320, 320, 450, 320, 450)
                            detail.getPixels(region, 0, 320, 0, 0, 320, 450)
                            assertArrayEquals("Detail grain must use the same page origin", crop, region)
                        } finally { detail.recycle() }
                    }
                } finally { direct.recycle(); full.recycle() }
            }
            val cancelled = session.renderPage(0, 160, ThemeSettings(ReadingMode.ORIGINAL), signal)
            assertThrows(OperationCanceledException::class.java) {
                session.themePage(0, cancelled, ThemeSettings(ReadingMode.SEPIA), CancellationSignal().apply { cancel() })
            }
            assertTrue("themePage adopts cancelled input", cancelled.isRecycled)
        }
    }

    @Test fun regionCoordinatesMatchTheFullPageAtReducedScale() {
        val signal = CancellationSignal()
        val document = Document("rtf", sections = listOf(Section("", blocks = listOf(
            Block("p", (1..20).joinToString("\n") { "Line $it, native text at a reduced scale." })))))
        TextSession("small.rtf", "application/rtf", document, signal).use { session ->
            val settings = ThemeSettings(ReadingMode.ORIGINAL)
            val full = session.renderPage(0, 160, settings, signal)
            val region = PageRegion(.2f, .16f, .8f, .64f, 96, 108)
            val part = session.renderRegion(0, region, settings, signal)
            try {
                val wholePixels = IntArray(96 * 108)
                val regionPixels = IntArray(wholePixels.size)
                full.getPixels(wholePixels, 0, 96, 32, 36, 96, 108)
                part.getPixels(regionPixels, 0, 96, 0, 0, 96, 108)
                assertTrue("Normalized source coordinates must stay aligned below half-size",
                    wholePixels.indices.count { wholePixels[it] == regionPixels[it] } > wholePixels.size * .98)
            } finally { part.recycle(); full.recycle() }
        }
    }

    @Test fun deepZoomRasterizesNativeInkWithinTheRequestedViewport() {
        val signal = CancellationSignal()
        val document = Document("html", sections = listOf(Section("", blocks = listOf(Block("p", "Zoom detail")))))
        val region = PageRegion(32f / 640, 32f / 900, 112f / 640, 64f / 900, 640, 256)
        TextSession("detail.html", "text/html", document, signal).use { session ->
            val original = ThemeSettings(ReadingMode.ORIGINAL)
            val preview = session.renderPage(0, 640, original, signal)
            val crop = Bitmap.createBitmap(preview, 32, 32, 80, 32)
            val stretched = Bitmap.createScaledBitmap(crop, 640, 256, true)
            try {
                for (mode in listOf(ReadingMode.ORIGINAL, ReadingMode.ADAPTIVE, ReadingMode.INVERT)) {
                    val settings = ThemeSettings(mode)
                    val detail = session.renderRegion(0, region, settings, signal)
                    try {
                        assertEquals(640, detail.width); assertEquals(256, detail.height)
                        assertEquals(640 * 256 * 4, detail.allocationByteCount)
                        val pixels = IntArray(640 * 256)
                        detail.getPixels(pixels, 0, 640, 0, 0, 640, 256)
                        val paper = if (mode == ReadingMode.INVERT) 0xff000000.toInt() else DocumentColors.palette(settings).background
                        assertTrue("The requested source region contains native text in $mode", pixels.count { it != paper } > 1000)
                        if (mode == ReadingMode.ORIGINAL) {
                            val coarse = IntArray(pixels.size)
                            stretched.getPixels(coarse, 0, 640, 0, 0, 640, 256)
                            assertTrue("Deep zoom must render new glyph detail, not magnify the preview",
                                pixels.indices.count { pixels[it] != coarse[it] } > 1000)
                        }
                    } finally { detail.recycle() }
                }
                assertThrows(OperationCanceledException::class.java) {
                    session.renderRegion(0, region, original, CancellationSignal().apply { cancel() })
                }
            } finally { stretched.recycle(); crop.recycle(); preview.recycle() }
        }
    }
}
