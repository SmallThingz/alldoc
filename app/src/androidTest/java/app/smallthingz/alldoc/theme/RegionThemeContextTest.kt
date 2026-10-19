package app.smallthingz.alldoc.theme

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.CancellationSignal
import android.os.OperationCanceledException
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.smallthingz.alldoc.engine.PageRegion
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RegionThemeContextTest {
    @Test fun nonBinaryPhotoCropEdgesKeepExteriorPaperThemed() {
        val source = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        try {
            source.eraseColor(Color.WHITE)
            for (y in 200 until 300) for (x in 200 until 300)
                source.setPixel(x, y, Color.rgb(40 + x % 100, 60 + y % 120, 180))
            val hints = listOf(RasterRegion(.5f, .5f, .75f, .75f))
            for (settings in listOf(ThemeSettings(ReadingMode.ADAPTIVE), ThemeSettings(ReadingMode.SEPIA, paperRoughness = 100))) {
                val context = BitmapTheme.prepareContext(source, settings, CancellationSignal(), hints)
                val full = BitmapTheme.apply(source, settings, CancellationSignal(), context = context)
                try {
                    assertNotEquals(Color.WHITE, full.getPixel(199, 220))
                    assertEquals(source.getPixel(200, 220), full.getPixel(200, 220))
                    for (region in listOf(PageRegion(.3f, .3f, .8f, .8f, 200, 200),
                        PageRegion(.65f, .65f, .95f, .95f, 120, 120))) {
                        val left = kotlin.math.round(region.left * 400).toInt()
                        val top = kotlin.math.round(region.top * 400).toInt()
                        val crop = Bitmap.createBitmap(source, left, top, region.width, region.height)
                        try {
                            val detail = BitmapTheme.apply(crop, settings, CancellationSignal(), context = context, region = region)
                            try { assertCrop(full, detail, left, top) } finally { detail.recycle() }
                        } finally { crop.recycle() }
                    }
                } finally { full.recycle() }
            }
        } finally { source.recycle() }
    }

    @Test fun nonBinaryCropEdgesDoNotAddBrightBordersAroundPreservedColoredPlates() {
        val source = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        try {
            source.eraseColor(Color.WHITE)
            for (y in 200 until 300) for (x in 200 until 300) source.setPixel(x, y, 0xff00aa00.toInt())
            for (settings in listOf(ThemeSettings(ReadingMode.ADAPTIVE), ThemeSettings(ReadingMode.SEPIA, paperRoughness = 100))) {
                val context = BitmapTheme.prepareContext(source, settings, CancellationSignal(), emptyList())
                val full = BitmapTheme.apply(source, settings, CancellationSignal(), context = context)
                try {
                    for (region in listOf(PageRegion(.3f, .3f, .8f, .8f, 200, 200),
                        PageRegion(.65f, .65f, .95f, .95f, 120, 120))) {
                        val left = kotlin.math.round(region.left * 400).toInt()
                        val top = kotlin.math.round(region.top * 400).toInt()
                        val crop = Bitmap.createBitmap(source, left, top, region.width, region.height)
                        try {
                            val detail = BitmapTheme.apply(crop, settings, CancellationSignal(), context = context, region = region)
                            try { assertCrop(full, detail, left, top) } finally { detail.recycle() }
                        } finally { crop.recycle() }
                    }
                } finally { full.recycle() }
            }
        } finally { source.recycle() }
    }

    @Test fun retainedContextStorageIsBoundedAndPlainPagesDoNotPayForUnusedInkArrays() {
        val source = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        try {
            source.eraseColor(Color.WHITE)
            val settings = ThemeSettings(ReadingMode.ADAPTIVE)
            val plain = BitmapTheme.prepareContext(source, settings, CancellationSignal(), emptyList())
            org.junit.Assert.assertTrue("Empty plate and ink state should not consume a page-sized allocation", plain.byteCount < 4096)
            val fullImage = RasterRegion(0f, 0f, 1f, 1f)
            for (hints in listOf(listOf(fullImage), List(RasterRegion.LIMIT) { fullImage })) {
                val context = BitmapTheme.prepareContext(source, settings, CancellationSignal(), hints)
                val maskBytes = context.scans?.sumOf { it?.storageBytes ?: 0L } ?: 0L
                org.junit.Assert.assertTrue(maskBytes <= RegionThemeContext.MAX_SCAN_BYTES)
                org.junit.Assert.assertTrue(context.byteCount >= maskBytes)
                org.junit.Assert.assertTrue(context.byteCount <= RegionThemeContext.MAX_BYTE_COUNT)
            }
        } finally { source.recycle() }
    }

    @Test fun photoBorderAndScannedPaperKeepTheirWholeSourcePolicy() {
        val source = Bitmap.createBitmap(384, 256, Bitmap.Config.ARGB_8888)
        try {
            source.eraseColor(Color.WHITE)
            // The authored photo has a white left border; its other three edges are photographic.
            for (y in 32 until 224) for (x in 64 until 160)
                source.setPixel(x, y, Color.rgb(40 + x % 100, 60 + y % 120, 180))
            // A separate scanned page has complete white perimeter and enclosed text strokes.
            for (y in 96 until 100) for (x in 244 until 256) source.setPixel(x, y, Color.BLACK)
            val hints = listOf(RasterRegion(32f / 384, .125f, 160f / 384, .875f),
                RasterRegion(224f / 384, .125f, 352f / 384, .875f))
            for (settings in listOf(ThemeSettings(ReadingMode.ADAPTIVE), ThemeSettings(ReadingMode.SEPIA, paperRoughness = 80))) {
                val context = BitmapTheme.prepareContext(source, settings, CancellationSignal(), hints)
                val full = BitmapTheme.apply(source, settings, CancellationSignal(), context = context)
                try {
                    assertEquals(Color.WHITE, full.getPixel(40, 96))
                    assertNotEquals(Color.WHITE, full.getPixel(250, 120))
                    assertNotEquals(Color.BLACK, full.getPixel(250, 97))
                    for (box in listOf(intArrayOf(40, 96, 16, 64), intArrayOf(240, 80, 64, 80), intArrayOf(16, 16, 192, 192))) {
                        val region = PageRegion(box[0] / 384f, box[1] / 256f,
                            (box[0] + box[2]) / 384f, (box[1] + box[3]) / 256f, box[2], box[3])
                        val crop = Bitmap.createBitmap(source, box[0], box[1], box[2], box[3])
                        try {
                            val output = BitmapTheme.apply(crop, settings, CancellationSignal(), context = context, region = region)
                            try { assertCrop(full, output, box[0], box[1]) } finally { output.recycle() }
                        } finally { crop.recycle() }
                    }
                } finally { full.recycle() }
            }
        } finally { source.recycle() }
    }

    @Test fun contextClassifiesRegionsWithoutReplacingNewVectorInkPixels() {
        val probe = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        val detail = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        try {
            probe.eraseColor(Color.WHITE)
            val settings = ThemeSettings(ReadingMode.ADAPTIVE)
            val context = BitmapTheme.prepareContext(probe, settings, CancellationSignal(), emptyList())
            for (y in 0 until 64) for (x in 0 until 64) detail.setPixel(x, y,
                if (x % 4 == 0) Color.BLACK else if (x % 4 == 1) Color.rgb(128, 128, 128) else Color.WHITE)
            val output = BitmapTheme.apply(detail, settings, CancellationSignal(), context = context,
                region = PageRegion(.25f, .25f, .28125f, .28125f, 64, 64))
            try {
                val theme = PixelTheme(settings)
                for (x in 0 until 64) assertEquals(theme.color(detail.getPixel(x, 32)), output.getPixel(x, 32))
            } finally { output.recycle() }
            assertThrows(OperationCanceledException::class.java) {
                BitmapTheme.prepareContext(probe, settings, CancellationSignal().apply { cancel() }, emptyList())
            }
            assertFalse(probe.isRecycled)
        } finally { probe.recycle(); detail.recycle() }
    }

    @Test fun whiteInkKeepsItsFillContrastWhenBothFillAnchorsAreOutsideTheViewport() {
        independentlyRasterizedGlyphUsesCompatibleNeighborFill()
        neutralPanelBesideGlyphDoesNotBorrowFill(Color.WHITE)
        neutralPanelBesideGlyphDoesNotBorrowFill(0xfffcfcfc.toInt())
        rejectedTintedPanelBesideGlyphKeepsItsSourcePolicy(30) // diagonal seed
        rejectedTintedPanelBesideGlyphKeepsItsSourcePolicy(31) // orthogonal seed
        val source = Bitmap.createBitmap(192, 83, Bitmap.Config.ARGB_8888)
        val settings = ThemeSettings(ReadingMode.ADAPTIVE)
        val paper = 0xff86d07d.toInt()
        try {
            source.eraseColor(paper)
            for (y in 3 until 11) for (x in 62 until 66) source.setPixel(x, y, Color.WHITE)
            val context = BitmapTheme.prepareContext(source, settings, CancellationSignal(), listOf(RasterRegion(.5f, 0f, 1f, 1f)))
            val full = BitmapTheme.apply(source, settings, CancellationSignal(), context = context)
            val crop = Bitmap.createBitmap(source, 63, 4, 2, 4)
            try {
                val region = PageRegion(63f / 192, 4f / 83, 65f / 192, 8f / 83, 2, 4)
                val detail = BitmapTheme.apply(crop, settings, CancellationSignal(), context = context, region = region)
                try {
                    assertCrop(full, detail, 63, 4)
                    assertNotEquals(PixelTheme(settings).color(Color.WHITE), detail.getPixel(0, 0))
                } finally { detail.recycle() }
            } finally { full.recycle(); crop.recycle() }
        } finally { source.recycle() }
    }

    @Test fun semanticPaperGrainUsesTheSamePageOriginAfterCroppingAndZooming() {
        val full = Bitmap.createBitmap(256, 128, Bitmap.Config.ARGB_8888)
        val crop = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        try {
            val settings = ThemeSettings(ReadingMode.SEPIA, paperRoughness = 100)
            PaperTexture.background(Canvas(full), full.width, full.height, settings)
            PaperTexture.background(Canvas(crop), crop.width, crop.height, settings,
                originX = 32f, originY = 16f, scaleX = .5f, scaleY = .5f)
            for (y in 0 until crop.height) for (x in 0 until crop.width)
                assertEquals(full.getPixel(32 + x / 2, 16 + y / 2), crop.getPixel(x, y))
        } finally { full.recycle(); crop.recycle() }
    }

    // Reduced policy fixture: known opaque glyph cores are proven seeds. Probe AA cells
    // intentionally remain unseeded, as can happen at an independently rasterized edge.
    // This does not claim the complete detector reproduces a particular native fixture.
    private fun independentlyRasterizedGlyphUsesCompatibleNeighborFill() {
        val paper = 0xff86d07d.toInt()
        val settings = ThemeSettings(ReadingMode.ADAPTIVE)
        val path = Path().apply {
            addRect(RectF(10.6f, 4f, 12.4f, 20f), Path.Direction.CW)
            addRect(RectF(10.6f, 18f, 18.4f, 20f), Path.Direction.CW)
        }
        val probe = renderGlyph(path, paper, 24, 24, 0f, 0f, 24f, 24f)
        val detail = renderGlyph(path, paper, 256, 256, 10f, 8f, 1f, 4f)
        try {
            val exact = coreSeeds(probe, paper)
            val supported = exact.withFillSupport(24, 24) { y, row -> probe.getPixels(row, 0, 24, 0, y, 24, 1) }
            assertEquals(Color.WHITE, probe.getPixel(11, 9))
            assertTrue(DocumentColors.inkCoverage(probe.getPixel(10, 9), paper, Color.WHITE) in 1..254)
            val region = PageRegion(10f / 24, 8f / 24, 11f / 24, 12f / 24, 256, 256)
            val before = BitmapTheme.apply(detail, settings, CancellationSignal(), context = inkContext(exact, settings), region = region)
            val after = BitmapTheme.apply(detail, settings, CancellationSignal(), context = inkContext(supported, settings), region = region)
            try {
                val theme = PixelTheme(settings)
                val ramp = requireNotNull(DocumentColors.inkRamp(theme.color(Color.WHITE), theme.color(paper)))
                var newlyRenderedInk = 0
                var antialiasedInk = 0
                for (y in 0 until detail.height) for (x in 0 until detail.width) {
                    if (detail.getPixel(x, y) == Color.WHITE) {
                        // Every pixel here projects into source column10: AA, not a seeded core.
                        assertEquals(theme.color(Color.WHITE), before.getPixel(x, y))
                        assertEquals(ramp[255], after.getPixel(x, y))
                        newlyRenderedInk++
                    } else if (detail.getPixel(x, y) == paper) assertEquals(theme.color(paper), after.getPixel(x, y))
                    else {
                        val coverage = DocumentColors.inkCoverage(detail.getPixel(x, y), paper, Color.WHITE)
                        assertTrue("The independent edge must retain valid partial coverage", coverage in 1..254)
                        assertEquals(ramp[coverage], after.getPixel(x, y))
                        antialiasedInk++
                    }
                }
                assertTrue("The independently drawn edge must exercise missing coarse membership", newlyRenderedInk > 10_000)
                assertTrue("Subpixel edge coverage must remain independently rasterized", antialiasedInk > 0)
                // Borrowed support may fill missing ink, but must not replace a valid local decision.
                val colors = intArrayOf(0xff123456.toInt())
                supported.row(intArrayOf(10), 9, intArrayOf(Color.WHITE), 0, null, null, colors) { ramp }
                assertEquals(0xff123456.toInt(), colors[0])
                assertTrue(supported.byteCount <= RegionThemeContext.MAX_BYTE_COUNT)
            } finally { before.recycle(); after.recycle() }
        } finally { probe.recycle(); detail.recycle() }
    }

    private fun neutralPanelBesideGlyphDoesNotBorrowFill(panel: Int) {
        val paper = 0xff86d07d.toInt()
        val settings = ThemeSettings(ReadingMode.ADAPTIVE)
        val path = Path().apply { addRect(RectF(10f, 4f, 12f, 20f), Path.Direction.CW) }
        fun render(width: Int, height: Int, left: Float, top: Float, spanX: Float, spanY: Float): Bitmap {
            val bitmap = renderGlyph(path, paper, width, height, left, top, spanX, spanY)
            Canvas(bitmap).apply {
                scale(width / spanX, height / spanY); translate(-left, -top)
                drawRect(12.4f, 0f, 24f, 24f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = panel })
            }
            return bitmap
        }
        val probe = render(24, 24, 0f, 0f, 24f, 24f)
        val detail = render(256, 256, 12f, 8f, 1f, 4f)
        try {
            // Only the authored glyph is evidence: the visually similar panel is not an ink seed.
            val exact = RegionInkContext().apply {
                for (y in 4 until 20) append(y, IntArray(24).apply { this[10] = paper; this[11] = paper })
            }
            assertTrue(DocumentColors.inkCoverage(probe.getPixel(12, 9), paper, Color.WHITE) in 1..254)
            val supported = exact.withFillSupport(24, 24) { y, row -> probe.getPixels(row, 0, 24, 0, y, 24, 1) }
            val region = PageRegion(12f / 24, 8f / 24, 13f / 24, 12f / 24, 256, 256)
            val after = BitmapTheme.apply(detail, settings, CancellationSignal(), context = inkContext(supported, settings), region = region)
            try {
                val theme = PixelTheme(settings)
                var panelPixels = 0
                for (y in 0 until detail.height) for (x in 0 until detail.width) {
                    if (detail.getPixel(x, y) == panel) {
                        assertEquals("Neutral panel must keep its ordinary paper treatment", theme.color(panel), after.getPixel(x, y))
                        panelPixels++
                    }
                }
                assertTrue(panelPixels > 10_000)
            } finally { after.recycle() }
        } finally { probe.recycle(); detail.recycle() }
    }

    // Use the real source detector here: a partial panel ending at a black shape has
    // no white core or vertically bounded ink, despite matching the white-ink ramp.
    private fun rejectedTintedPanelBesideGlyphKeepsItsSourcePolicy(glyphLeft: Int) {
        val paper = 0xff86d07d.toInt()
        val panel = DocumentColors.blend(paper, Color.WHITE, 167)
        val settings = ThemeSettings(ReadingMode.ADAPTIVE)
        val theme = PixelTheme(settings)
        val ramp = requireNotNull(DocumentColors.inkRamp(theme.color(Color.WHITE), theme.color(paper)))
        val ordinaryPanel = theme.color(panel)
        assertEquals(167, DocumentColors.inkCoverage(panel, paper, Color.WHITE))
        assertNotEquals("The panel oracle must distinguish ordinary color from borrowed ink", ordinaryPanel, ramp[167])
        val source = Bitmap.createBitmap(128, 96, Bitmap.Config.ARGB_8888)
        val detail = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        try {
            source.eraseColor(paper)
            for (y in 0 until 96) source.setPixel(71, y, Color.BLACK)
            for (y in 2 until 10) for (x in glyphLeft until glyphLeft + 3) source.setPixel(x, y, Color.WHITE)
            for (y in 10 until 82) for (x in 33 until 71) source.setPixel(x, y, panel)
            // Local classification already preserves this authored panel and recognizes the glyph.
            val local = BitmapTheme.apply(source, settings, CancellationSignal(), emptyList())
            try {
                assertEquals(ordinaryPanel, local.getPixel(33, 10))
                assertEquals(ramp[255], local.getPixel(glyphLeft + 2, 9))
            } finally { local.recycle() }
            val context = BitmapTheme.prepareContext(source, settings, CancellationSignal(), emptyList())
            val actualSeed = intArrayOf(0)
            requireNotNull(context.ink).row(intArrayOf(glyphLeft + 2), 9, intArrayOf(Color.WHITE),
                0, null, null, actualSeed) { ramp }
            assertEquals("The actual prepared context must contain the neighboring proven glyph", ramp[255], actualSeed[0])
            val full = BitmapTheme.apply(source, settings, CancellationSignal(), context = context)
            try {
                for (y in 10 until 82) for (x in 33 until 71)
                    assertEquals("Source context must preserve the rejected panel", ordinaryPanel, full.getPixel(x, y))
                assertEquals(ramp[255], full.getPixel(glyphLeft + 2, 9))
            } finally { full.recycle() }
            // Independently rendered flat fill, not a resized source bitmap. Every output
            // pixel projects into the rejected cell touching the diagonal/orthogonal seed.
            detail.eraseColor(panel)
            val region = PageRegion(33f / 128, 10f / 96, 34f / 128, 11f / 96, 256, 256)
            val output = BitmapTheme.apply(detail, settings, CancellationSignal(), context = context, region = region)
            try {
                val pixels = IntArray(256 * 256)
                output.getPixels(pixels, 0, 256, 0, 0, 256, 256)
                assertArrayEquals(IntArray(pixels.size) { ordinaryPanel }, pixels)
            } finally { output.recycle() }
        } finally { source.recycle(); detail.recycle() }
    }

    private fun coreSeeds(probe: Bitmap, paper: Int) = RegionInkContext().apply {
        for (y in 0 until probe.height) append(y, IntArray(probe.width) { x -> if (probe.getPixel(x, y) == Color.WHITE) paper else 0 })
    }

    private fun inkContext(ink: RegionInkContext, settings: ThemeSettings) = RegionThemeContext(
        settings, 24, 24, emptyList(), null, false, IntArray(0), emptyArray(),
        arrayOf(PixelTheme.Tile.RECOLOR), ink)

    private fun renderGlyph(path: Path, paper: Int, width: Int, height: Int,
        left: Float, top: Float, spanX: Float, spanY: Float): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.eraseColor(paper)
            Canvas(bitmap).apply {
                scale(width / spanX, height / spanY); translate(-left, -top)
                drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
            }
        }

    private fun assertCrop(full: Bitmap, crop: Bitmap, left: Int, top: Int) {
        val expected = IntArray(crop.width * crop.height)
        val actual = IntArray(expected.size)
        full.getPixels(expected, 0, crop.width, left, top, crop.width, crop.height)
        crop.getPixels(actual, 0, crop.width, 0, 0, crop.width, crop.height)
        assertArrayEquals(expected, actual)
    }
}
