package app.smallthingz.alldoc.theme

import android.graphics.Bitmap
import android.os.CancellationSignal
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream

class ScanThemeTest {
    private fun tile(name: String): Bitmap {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        // Android packages the source .rgb.gz fixtures as decoded .rgb assets.
        val rgb = assets.open("theme/$name.rgb").use { decoded ->
            DataInputStream(decoded).use { input ->
                ByteArray(64 * 64 * 3).also { pixels ->
                    input.readFully(pixels)
                    assertEquals("$name must contain exactly one RGB tile", -1, input.read())
                }
            }
        }
        val pixels = IntArray(64 * 64) { index ->
            val at = index * 3
            -0x1000000 or ((rgb[at].toInt() and 255) shl 16) or
                ((rgb[at + 1].toInt() and 255) shl 8) or (rgb[at + 2].toInt() and 255)
        }
        return Bitmap.createBitmap(pixels, 64, 64, Bitmap.Config.ARGB_8888)
    }

    @Test fun realScannedTextAdaptsWithoutTileHolesAndPhotoPixelsRemainOriginal() {
        // Exact 64px crops of the authored scan's Android Original1080 raster, not synthetic flat text.
        for (name in listOf("scan-text", "scan-subtitle", "scan-photo")) {
            val source = tile(name)
            val snapshot = requireNotNull(source.copy(Bitmap.Config.ARGB_8888, false))
            val generation = source.generationId
            try {
                val output = BitmapTheme.apply(source, ThemeSettings(ReadingMode.ADAPTIVE), CancellationSignal())
                try {
                    if (name == "scan-photo") assertTrue("Photo detail remains original", source.sameAs(output))
                    else {
                        var paper = 0; var ink = 0; var chromatic = 0
                        for (y in 0 until 64) for (x in 0 until 64) {
                            val original = source.getPixel(x, y); val result = output.getPixel(x, y)
                            val channels = intArrayOf(original ushr 16 and 255, original ushr 8 and 255, original and 255)
                            val low = channels.min(); val high = channels.max()
                            val resultLow = minOf(result ushr 16 and 255, result ushr 8 and 255, result and 255)
                            val resultHigh = maxOf(result ushr 16 and 255, result ushr 8 and 255, result and 255)
                            if (low >= 245) {
                                paper++; assertTrue("$name paper at $x,$y", resultHigh < 64)
                            } else if (high < 100 && high - low <= 40) {
                                ink++; assertTrue("$name ink at $x,$y", resultLow > 120)
                            } else if (high - low > 40) {
                                chromatic++; assertEquals("$name colored header at $x,$y", original, result)
                            }
                        }
                        assertTrue("$name contains substantial paper", paper > 2000)
                        assertTrue("$name contains actual dark scan ink", ink > 0)
                        if (name == "scan-subtitle") assertTrue("Subtitle straddles the colored header", chromatic > 0)
                    }
                } finally { if (output !== source) output.recycle() }
                // The explicit preservation modes keep their existing behavior on these ambiguous scans.
                for (policy in listOf(ImageTreatment.KEEP, ImageTreatment.DIM)) {
                    val settings = ThemeSettings(ReadingMode.ADAPTIVE, policy)
                    val kernel = PixelTheme(settings)
                    val preserved = BitmapTheme.apply(source, settings, CancellationSignal())
                    try {
                        for (y in 0 until 64) for (x in 0 until 64) {
                            val original = source.getPixel(x, y)
                            val expected = if (policy == ImageTreatment.KEEP) original else kernel.dim(original)
                            assertEquals("$name explicit $policy at $x,$y", expected, preserved.getPixel(x, y))
                        }
                    } finally { if (preserved !== source) preserved.recycle() }
                }
                assertFalse(source.isRecycled)
                assertEquals(generation, source.generationId)
                assertTrue("Original pixels stay reusable", source.sameAs(snapshot))
            } finally { snapshot.recycle(); source.recycle() }
        }
    }
    @Test fun fullConfirmationLeavesKnownBackdropPhotoContextsOnTheirEstablishedPath() {
        // A recognized neutral plate leaves a gray picture fragment beside white margin.
        // The regular sample grid overrepresents its 17px gray band; its established KEEP
        // decision must not be overturned by recounting only the unmasked remainder.
        val pixels = IntArray(64 * 64) { index ->
            val x = index % 64; val y = index / 64
            val value = if (y < 40) 16 else if (x < 17) 80 + (x * 7 + y * 11) % 100 else 255
            -0x1000000 or (value shl 16) or (value shl 8) or value
        }
        val known = LongArray(64) { row -> if (row < 40) -1L else 0L }
        val before = pixels.copyOf(); val knownBefore = known.copyOf()
        val theme = PixelTheme(ThemeSettings(ReadingMode.ADAPTIVE))
        assertEquals(PixelTheme.Tile.KEEP, theme.tile(pixels, 64, 0, 0, 64, 64, known, known))
        // The same unsegmented raster is eligible for full confirmation, exercising the guard.
        assertEquals(PixelTheme.Tile.RECOLOR, theme.tile(pixels.copyOfRange(40 * 64, pixels.size), 64, 0, 0, 64, 24))
        // A flat neutral remainder must not bypass the same masked-context guard.
        // Its white margin and flat gray interior otherwise satisfy the gray-title rule.
        val flat = IntArray(64 * 64) { index ->
            val x = index % 64; val y = index / 64
            val value = if (y < 40) 16 else if (x < 24) 108 else 255
            -0x1000000 or (value shl 16) or (value shl 8) or value
        }
        val flatBefore = flat.copyOf()
        assertEquals(PixelTheme.Tile.KEEP, theme.tile(flat, 64, 0, 0, 64, 64, known, known))
        assertArrayEquals(flatBefore, flat)
        assertArrayEquals(before, pixels)
        assertArrayEquals(knownBefore, known)
    }

    @Test fun nativeGrayTitleAdaptsWithoutWeakeningPhotoOrExplicitImagePolicies() {
        // Exact RGB tiles from layered-48.pdf page0 Original2160: (128,64) and (384,64).
        // The source title inherits nonstroking alpha .66; its solid ink is gray, not black.
        for (name in listOf("layered-title-gray", "layered-title-dense")) {
            val source = tile(name)
            val snapshot = requireNotNull(source.copy(Bitmap.Config.ARGB_8888, false))
            val generation = source.generationId
            try {
                val settings = ThemeSettings(ReadingMode.ADAPTIVE)
                val paper = DocumentColors.palette(settings).background
                val output = BitmapTheme.apply(source, settings, CancellationSignal())
                try {
                    var paperPixels = 0
                    var corePixels = 0
                    for (y in 0 until 64) for (x in 0 until 64) {
                        val original = source.getPixel(x, y)
                        val low = minOf(original ushr 16 and 255, original ushr 8 and 255, original and 255)
                        val high = maxOf(original ushr 16 and 255, original ushr 8 and 255, original and 255)
                        val result = output.getPixel(x, y)
                        if (low >= 245) {
                            paperPixels++
                            assertTrue("$name left a white tile patch at $x,$y",
                                maxOf(result ushr 16 and 255, result ushr 8 and 255, result and 255) < 64)
                        }
                        if (low in 100..120 && high - low <= 6) {
                            corePixels++
                            assertTrue("$name gray title core lacks contrast at $x,$y",
                                DocumentColors.contrast(result, paper) >= 4.5)
                        }
                    }
                    assertTrue("Fixture must retain substantial actual page paper", paperPixels > 2000)
                    assertTrue("Fixture must exercise composited gray glyph cores", corePixels > 100)
                } finally { output.recycle() }
                for (policy in listOf(ImageTreatment.KEEP, ImageTreatment.DIM)) {
                    val explicit = ThemeSettings(ReadingMode.ADAPTIVE, policy)
                    val kernel = PixelTheme(explicit)
                    val output = BitmapTheme.apply(source, explicit, CancellationSignal())
                    try {
                        for (y in 0 until 64) for (x in 0 until 64) {
                            val original = source.getPixel(x, y)
                            assertEquals("$name explicit $policy at $x,$y",
                                if (policy == ImageTreatment.KEEP) original else kernel.dim(original), output.getPixel(x, y))
                        }
                    } finally { output.recycle() }
                }
                assertEquals(generation, source.generationId)
                assertTrue(source.sameAs(snapshot))
            } finally { snapshot.recycle(); source.recycle() }
        }
        val photo = tile("scan-photo")
        val neutral = Bitmap.createBitmap(IntArray(64 * 64) { index ->
            val x = index % 64; val y = index / 64
            val value = if (x < 24) 60 + (x * 7 + y * 11) % 120 else 255
            -0x1000000 or (value shl 16) or (value shl 8) or value
        }, 64, 64, Bitmap.Config.ARGB_8888)
        try {
            for (source in listOf(photo, neutral)) {
                val output = BitmapTheme.apply(source, ThemeSettings(ReadingMode.ADAPTIVE), CancellationSignal())
                try { assertTrue("Textured photo detail and its paper-like margin must stay original", source.sameAs(output)) }
                finally { output.recycle() }
            }
        } finally { photo.recycle(); neutral.recycle() }
    }


}
