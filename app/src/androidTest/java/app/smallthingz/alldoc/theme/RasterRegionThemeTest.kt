package app.smallthingz.alldoc.theme

import android.graphics.Bitmap
import android.graphics.Color
import android.os.CancellationSignal
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class RasterRegionThemeTest {
    private fun asset(name: String, width: Int, height: Int): Bitmap {
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open("theme/$name.rgb").use { it.readBytes() }
        assertEquals(width * height * 3, bytes.size)
        val colors = IntArray(width * height) { at -> Color.rgb(bytes[at * 3].toInt() and 255,
            bytes[at * 3 + 1].toInt() and 255, bytes[at * 3 + 2].toInt() and 255) }
        return Bitmap.createBitmap(colors, width, height, Bitmap.Config.ARGB_8888)
    }

    @Test fun actualOfficeHeadingAndChartKeepContinuousPaperAndColoredMarks() {
        // Exact authored Word/PPTX Android Original crops, including native antialiasing.
        val heading = asset("office-heading", 640, 40)
        val snapshot = requireNotNull(heading.copy(Bitmap.Config.ARGB_8888, false))
        val generation = heading.generationId
        try {
            for (mode in listOf(ReadingMode.ADAPTIVE, ReadingMode.ADAPTIVE_LIGHT, ReadingMode.SEPIA, ReadingMode.HIGH_CONTRAST)) {
                val settings = ThemeSettings(mode); val kernel = PixelTheme(settings)
                val result = BitmapTheme.apply(heading, settings, CancellationSignal(), emptyList())
                try {
                    var paper = 0; var coloredInk = 0; var coloredEdge = 0
                    for (y in 0 until heading.height) for (x in 0 until heading.width) {
                        val original = heading.getPixel(x, y)
                        assertEquals("$mode heading $x,$y", kernel.color(original), result.getPixel(x, y))
                        val low = minOf(Color.red(original), Color.green(original), Color.blue(original))
                        val high = maxOf(Color.red(original), Color.green(original), Color.blue(original))
                        if (low == 255) paper++
                        else if (high - low > 40 && low < 100) coloredInk++
                        else if (high - low in 1..40) coloredEdge++
                    }
                    assertTrue("Actual paper", paper > 10000); assertTrue("Actual blue cores", coloredInk > 100)
                    assertTrue("Actual colored AA", coloredEdge > 50)
                } finally { result.recycle() }
            }
            assertEquals(generation, heading.generationId); assertTrue(heading.sameAs(snapshot))
        } finally { snapshot.recycle(); heading.recycle() }
        val chart = asset("office-chart", 128, 192)
        try {
            val settings = ThemeSettings(ReadingMode.ADAPTIVE)
            val result = BitmapTheme.apply(chart, settings, CancellationSignal(), emptyList())
            try {
                var paper = 0; var blue = 0; var coral = 0
                val blues = HashSet<Int>(); val corals = HashSet<Int>()
                for (y in 0 until chart.height) for (x in 0 until chart.width) {
                    val original = chart.getPixel(x, y); val mapped = result.getPixel(x, y)
                    val r = Color.red(original); val g = Color.green(original); val b = Color.blue(original)
                    if (r == 255 && g == 255 && b == 255) {
                        paper++; assertEquals("Chart paper $x,$y", DocumentColors.palette(settings).background, mapped)
                    }
                    if (b - r > 60 && g - r > 40) {
                        blue++; blues += mapped; assertTrue("Blue hue", Color.blue(mapped) - Color.red(mapped) > 20)
                    }
                    if (r - g > 60 && r - b > 60) {
                        coral++; corals += mapped; assertTrue("Coral hue", Color.red(mapped) - Color.blue(mapped) > 20)
                    }
                }
                assertTrue(paper > 5000); assertTrue(blue > 500); assertTrue(coral > 500)
                assertTrue("Distinct series", blues.intersect(corals).isEmpty())
            } finally { result.recycle() }
        } finally { chart.recycle() }
    }

    @Test fun knownPhotoAndItsAuthoredWhiteRimSurviveBandBoundariesExactly() {
        val photo = asset("office-photo", 64, 64)
        val source = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.WHITE)
        val colors = IntArray(photo.width * photo.height)
        photo.getPixels(colors, 0, photo.width, 0, 0, photo.width, photo.height)
        source.setPixels(colors, 0, photo.width, 40, 40, photo.width, photo.height); photo.recycle()
        val snapshot = requireNotNull(source.copy(Bitmap.Config.ARGB_8888, false))
        // Authored image has an eight-pixel white frame, distinct from the surrounding page.
        val region = RasterRegion(32f / 256, 32f / 256, 112f / 256, 112f / 256)
        try {
            val settings = ThemeSettings(ReadingMode.ADAPTIVE)
            val result = BitmapTheme.apply(source, settings, CancellationSignal(), listOf(region))
            try {
                var detail = 0
                for (y in 0 until 256) for (x in 0 until 256) {
                    val inside = x in 32 until 112 && y in 32 until 112
                    val expected = if (inside) source.getPixel(x, y) else DocumentColors.palette(settings).background
                    assertEquals("Image enclosure $x,$y", expected, result.getPixel(x, y))
                    if (inside && source.getPixel(x, y) != Color.WHITE && source.getPixel(x, y) != Color.BLACK) detail++
                }
                assertTrue("Authored photo detail", detail > 1000)
            } finally { result.recycle() }
            assertTrue(source.sameAs(snapshot)); assertFalse(source.isRecycled)
        } finally { snapshot.recycle(); source.recycle() }
    }

    @Test fun sourceHintsRetainPlateInkAndDoNotOverrideExplicitOrTransparentPolicies() {
        val source = Bitmap.createBitmap(256, 128, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.WHITE)
        for (y in 16 until 80) for (x in 16 until 240) source.setPixel(x, y, Color.rgb(20, 70, 150))
        for (y in 30 until 66) for (x in 80 until 84) source.setPixel(x, y, Color.WHITE)
        try {
            val output = BitmapTheme.apply(source, ThemeSettings(ReadingMode.ADAPTIVE), CancellationSignal(), emptyList())
            try {
                for (y in 16 until 80) for (x in 16 until 240) assertEquals("Plate/white ink", source.getPixel(x, y), output.getPixel(x, y))
                assertEquals(DocumentColors.palette(ThemeSettings(ReadingMode.ADAPTIVE)).background, output.getPixel(0, 0))
            } finally { output.recycle() }
            for (policy in listOf(ImageTreatment.KEEP, ImageTreatment.DIM, ImageTreatment.RECOLOR)) {
                val settings = ThemeSettings(ReadingMode.ADAPTIVE, policy)
                val old = BitmapTheme.apply(source, settings, CancellationSignal())
                val hinted = BitmapTheme.apply(source, settings, CancellationSignal(), emptyList())
                try { assertTrue("Explicit $policy", old.sameAs(hinted)) } finally { old.recycle(); hinted.recycle() }
            }
            source.eraseColor(Color.TRANSPARENT)
            for (y in 16 until 80) for (x in 16 until 24) source.setPixel(x, y, Color.WHITE)
            val settings = ThemeSettings(ReadingMode.ADAPTIVE)
            val old = BitmapTheme.apply(source, settings, CancellationSignal())
            val hinted = BitmapTheme.apply(source, settings, CancellationSignal(), emptyList())
            try {
                assertTrue("Hints retain the existing transparent-canvas treatment", old.sameAs(hinted))
                assertEquals("Opaque glyph follows the selected ink palette",
                    DocumentColors.palette(settings).foreground, hinted.getPixel(20, 30))
                assertEquals("Transparent background stays transparent", Color.TRANSPARENT, hinted.getPixel(0, 0))
                assertEquals("Source glyph remains unchanged", Color.WHITE, source.getPixel(20, 30))
            }
            finally { old.recycle(); hinted.recycle() }
        } finally { source.recycle() }
    }

    @Test fun trustedScanSelectionIsLocalAndKnownFullPagePhotosStayOriginal() {
        val settings = ThemeSettings(ReadingMode.ADAPTIVE)
        val photo = asset("office-photo", 64, 64)
        try {
            val preserved = BitmapTheme.apply(photo, settings, CancellationSignal(), listOf(RasterRegion(0f, 0f, 1f, 1f)))
            try { assertTrue("Failed scan confidence preserves the known full-page photo", photo.sameAs(preserved)) }
            finally { preserved.recycle() }
        } finally { photo.recycle() }
        val source = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.WHITE)
        // One small neutral glyph with a real intermediate AA rim crosses the y=63/64 band boundary.
        for (y in 62 until 72) for (x in 50 until 58) source.setPixel(x, y, Color.GRAY)
        for (y in 63 until 71) for (x in 51 until 57) source.setPixel(x, y, Color.BLACK)
        for (y in 112 until 176) for (x in 128 until 192)
            source.setPixel(x, y, Color.rgb(40 + (x * 3 + y) % 130, 60 + (y * 5) % 150, 80 + (x + y * 7) % 160))
        val original = requireNotNull(source.copy(Bitmap.Config.ARGB_8888, false))
        try {
            val region = RasterRegion(32f / 256, 48f / 256, 224f / 256, 208f / 256)
            val result = BitmapTheme.apply(source, settings, CancellationSignal(), listOf(region))
            val kernel = PixelTheme(settings)
            try {
                for (y in 0 until 256) for (x in 0 until 256) {
                    val pixel = source.getPixel(x, y)
                    val expected = if (x in 128 until 192 && y in 112 until 176) pixel else kernel.color(pixel)
                    assertEquals("Inset scan/AA/photo at $x,$y", expected, result.getPixel(x, y))
                }
                assertEquals(DocumentColors.palette(settings).foreground, result.getPixel(52, 64))
                assertEquals(DocumentColors.palette(settings).background, result.getPixel(32, 48))
            } finally { result.recycle() }
            // These hints have identical integer footprints, but only the exact full-page
            // hint bypasses backdrop detection. Compare all pixels, including a flat plate.
            val fullPage = listOf(RasterRegion(0f, 0f, 1f, 1f))
            val roundedPage = listOf(RasterRegion(.25f / source.width, .25f / source.height, 1f, 1f))
            val comparison = requireNotNull(source.copy(Bitmap.Config.ARGB_8888, true))
            try {
                for (y in 192 until 224) for (x in 16 until 80) comparison.setPixel(x, y, Color.DKGRAY)
                for (y in 202 until 214) for (x in 36 until 42) comparison.setPixel(x, y, Color.WHITE)
                for (alpha in listOf(255, 230, 229)) {
                    comparison.setHasAlpha(true)
                    comparison.setPixel(0, 0, Color.argb(alpha, 255, 255, 255))
                    for (mode in listOf(ReadingMode.ADAPTIVE, ReadingMode.ADAPTIVE_LIGHT, ReadingMode.SEPIA)) {
                        val palette = ThemeSettings(mode, paperRoughness = 35)
                        val legacyPath = BitmapTheme.apply(comparison, palette, CancellationSignal(), roundedPage)
                        try {
                            val fullPath = BitmapTheme.apply(comparison, palette, CancellationSignal(), fullPage)
                            try { assertTrue("Full-page bypass preserves $mode at alpha $alpha", legacyPath.sameAs(fullPath)) }
                            finally { fullPath.recycle() }
                        } finally { legacyPath.recycle() }
                    }
                }
                comparison.setPixel(0, 0, Color.WHITE)
                comparison.setHasAlpha(false)
                val legacyPath = BitmapTheme.apply(comparison, settings, CancellationSignal(), roundedPage)
                try {
                    val fullPath = BitmapTheme.apply(comparison, settings, CancellationSignal(), fullPage)
                    try { assertTrue("Opaque bitmap flag keeps the same scan pixels", legacyPath.sameAs(fullPath)) }
                    finally { fullPath.recycle() }
                } finally { legacyPath.recycle() }
            } finally { comparison.recycle() }
            val baseline = BitmapTheme.apply(source, settings, CancellationSignal())
            try {
                for (hints in listOf(listOf(RasterRegion(Float.NaN, 0f, 1f, 1f)),
                    List(RasterRegion.LIMIT + 1) { RasterRegion(0f, 0f, .5f, .5f) })) {
                    val result = BitmapTheme.apply(source, settings, CancellationSignal(), hints)
                    try { assertTrue("Invalid hints retain legacy AUTO", baseline.sameAs(result)) } finally { result.recycle() }
                }
            } finally { baseline.recycle() }
            assertTrue(source.sameAs(original))
            val mutable = mutableListOf(RasterRegion(.1f, .2f, .7f, .9f))
            val copy = requireNotNull(RasterRegion.snapshot(mutable))
            mutable.clear(); assertEquals(1, copy.size)
            assertThrows(UnsupportedOperationException::class.java) { (copy as MutableList<RasterRegion>).clear() }
            assertNull(RasterRegion.snapshot(null)); assertEquals(emptyList<RasterRegion>(), RasterRegion.snapshot(emptyList()))
        } finally { original.recycle(); source.recycle() }
    }
    @Test fun adjacentScannedTilesKeepContinuousPaperAndInkWhileBothKindsOfPhotoStayOriginal() {
        val source = Bitmap.createBitmap(512, 256, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.WHITE)
        // Two separately embedded scans meet at x=144; their glyphs cross a 64-row processing boundary.
        for (left in listOf(50, 178)) {
            for (y in 62 until 70) for (x in left until left + 8) source.setPixel(x, y, Color.GRAY)
            for (y in 63 until 69) for (x in left + 1 until left + 7) source.setPixel(x, y, Color.BLACK)
        }
        val photo = asset("office-photo", 64, 64)
        val colors = IntArray(64 * 64)
        try { photo.getPixels(colors, 0, 64, 0, 0, 64, 64) } finally { photo.recycle() }
        // One photograph is inside a scanned page. The other is an independent image with a white rim.
        source.setPixels(colors, 0, 64, 176, 80, 64, 64)
        source.setPixels(colors, 0, 64, 312, 48, 64, 64)
        val hints = listOf(RasterRegion(16f / 512, 32f / 256, 144f / 512, 160f / 256),
            RasterRegion(144f / 512, 32f / 256, 272f / 512, 160f / 256),
            RasterRegion(304f / 512, 40f / 256, 384f / 512, 120f / 256))
        val original = requireNotNull(source.copy(Bitmap.Config.ARGB_8888, false))
        try {
            for (mode in listOf(ReadingMode.ADAPTIVE, ReadingMode.ADAPTIVE_LIGHT, ReadingMode.HIGH_CONTRAST)) {
                val settings = ThemeSettings(mode)
                val kernel = PixelTheme(settings)
                val result = BitmapTheme.apply(source, settings, CancellationSignal(), hints)
                try {
                    for (y in 0 until source.height) for (x in 0 until source.width) {
                        val preserved = x in 176 until 240 && y in 80 until 144 ||
                            x in 304 until 384 && y in 40 until 120
                        val pixel = source.getPixel(x, y)
                        assertEquals("$mode tile seam, ink or photo at $x,$y",
                            if (preserved) pixel else kernel.color(pixel), result.getPixel(x, y))
                    }
                    assertEquals(DocumentColors.palette(settings).foreground, result.getPixel(52, 64))
                    assertEquals(DocumentColors.palette(settings).background, result.getPixel(144, 96))
                } finally { result.recycle() }
            }
            assertTrue(source.sameAs(original)); assertFalse(source.isRecycled)
        } finally { original.recycle(); source.recycle() }
    }

    @Test fun overlappingAndPartiallyTransparentRasterLayersStayConservative() {
        val source = Bitmap.createBitmap(256, 128, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.WHITE)
        for (y in 40 until 46) for (x in listOf(30, 110, 210))
            for (offset in 0 until 4) source.setPixel(x + offset, y, Color.BLACK)
        val hints = listOf(RasterRegion(16f / 256, 16f / 128, 112f / 256, 112f / 128),
            RasterRegion(96f / 256, 16f / 128, 176f / 256, 112f / 128),
            RasterRegion(192f / 256, 16f / 128, 240f / 256, 112f / 128))
        val settings = ThemeSettings(ReadingMode.ADAPTIVE)
        val kernel = PixelTheme(settings)
        try {
            val result = BitmapTheme.apply(source, settings, CancellationSignal(), hints)
            try {
                for (y in 0 until 128) for (x in 0 until 256) {
                    val pixel = source.getPixel(x, y)
                    assertEquals("Ambiguous overlapping images at $x,$y",
                        if (x in 16 until 176 && y in 16 until 112) pixel else kernel.color(pixel), result.getPixel(x, y))
                }
            } finally { result.recycle() }
            // Alpha inside one proven footprint invalidates only its scan override, not its neighbor.
            source.setHasAlpha(true)
            source.setPixel(210, 80, Color.argb(240, 255, 255, 255))
            val partial = BitmapTheme.apply(source, settings, CancellationSignal(), hints)
            try {
                assertEquals(Color.WHITE, partial.getPixel(200, 20))
                assertEquals(source.getPixel(210, 80), partial.getPixel(210, 80))
                assertEquals(Color.BLACK, partial.getPixel(210, 42))
            } finally { partial.recycle() }
            val signal = CancellationSignal().apply { cancel() }
            assertThrows(android.os.OperationCanceledException::class.java) { BitmapTheme.apply(source, settings, signal, hints) }
            assertFalse(source.isRecycled)
        } finally { source.recycle() }
    }

    @Test fun roundedNormalizedSeamsDoNotInventOverlapButRealSubpixelOverlapStillPreservesImages() {
        val source = Bitmap.createBitmap(1000, 128, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.WHITE)
        for (y in 40 until 46) for (left in listOf(20, 120))
            for (x in left until left + 4) source.setPixel(x, y, Color.BLACK)
        val settings = ThemeSettings(ReadingMode.ADAPTIVE)
        val kernel = PixelTheme(settings)
        try {
            val adjacent = listOf(RasterRegion(0f, 0f, .1f, 1f), RasterRegion(.1f, 0f, .2f, 1f))
            val result = BitmapTheme.apply(source, settings, CancellationSignal(), adjacent)
            try {
                for (y in 0 until 128) for (x in 0 until 1000)
                    assertEquals("Normalized seam at $x,$y", kernel.color(source.getPixel(x, y)), result.getPixel(x, y))
            } finally { result.recycle() }
            val fractional = listOf(RasterRegion(0f, 0f, .10025f, 1f), RasterRegion(.10025f, 0f, .20025f, 1f))
            val joined = BitmapTheme.apply(source, settings, CancellationSignal(), fractional)
            try {
                for (y in 0 until 128) for (x in 0 until 1000)
                    assertEquals("Fractional adjoining seam at $x,$y", kernel.color(source.getPixel(x, y)), joined.getPixel(x, y))
            } finally { joined.recycle() }
            source.setPixel(120, 80, Color.argb(240, 255, 255, 255))
            val unknown = BitmapTheme.apply(source, settings, CancellationSignal(), fractional)
            try {
                for (y in 0 until 128) for (x in 0 until 1000) {
                    val pixel = source.getPixel(x, y)
                    assertEquals("Unknown image owns shared boundary at $x,$y",
                        if (x in 100 until 201) pixel else kernel.color(pixel), unknown.getPixel(x, y))
                }
            } finally { unknown.recycle() }
            source.setPixel(120, 80, Color.WHITE)
            // This is an actual quarter-pixel overlap, well outside normalized Float uncertainty.
            val overlap = listOf(RasterRegion(0f, 0f, .10025f, 1f), RasterRegion(.1f, 0f, .2f, 1f))
            val retained = BitmapTheme.apply(source, settings, CancellationSignal(), overlap)
            try {
                for (y in 0 until 128) for (x in 0 until 1000) {
                    val pixel = source.getPixel(x, y)
                    assertEquals("Real overlap at $x,$y", if (x < 200) pixel else kernel.color(pixel), retained.getPixel(x, y))
                }
            } finally { retained.recycle() }
        } finally { source.recycle() }
    }

}
