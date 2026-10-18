package app.smallthingz.alldoc.theme

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Test

class DocumentColorsTest {
    @Test fun coloredInkRampCannotWeakenQuantizedEdgesEvenWhenLuminanceChangesDirection() {
        val pairs = arrayOf(
            0xff235d75.toInt() to 0xff18a303.toInt(), // Authored slideshow title.
            0xff1045d1.toInt() to 0xffd80623.toInt(), // Correction reverses luminance polarity.
            0xff00b2b9.toInt() to 0xffd4010c.toInt(), // Same polarity; sRGB interpolation still dips.
            0xff86a6bd.toInt() to 0xff777777.toInt(),
            0xff553177.toInt() to 0xff151b2f.toInt(),
        )
        for ((ink, paper) in pairs) {
            val ramp = requireNotNull(DocumentColors.inkRamp(ink, paper))
            assertEquals(paper, ramp[0])
            assertTrue(DocumentColors.contrast(ramp[255], paper) >= 4.5)
            for (amount in 0..255) {
                val source = DocumentColors.blend(paper, ink, amount)
                // Cover the classifier's quantization envelope, not only perfect synthetic AA.
                for (dr in intArrayOf(-3, 0, 3)) for (dg in intArrayOf(-3, 0, 3)) for (db in intArrayOf(-3, 0, 3)) {
                    val value = 0xff000000.toInt() or
                        (((source ushr 16 and 255) + dr).coerceIn(0, 255) shl 16) or
                        (((source ushr 8 and 255) + dg).coerceIn(0, 255) shl 8) or
                        ((source and 255) + db).coerceIn(0, 255)
                    val coverage = DocumentColors.inkCoverage(value, paper, ink)
                    if (coverage > 0) assertTrue("ink=$ink paper=$paper coverage=$coverage",
                        DocumentColors.contrast(ramp[coverage], paper) + 1e-12 >= DocumentColors.contrast(value, paper))
                }
                if (amount > 0) for (shift in 16 downTo 0 step 8) {
                    val previous = ramp[amount - 1] ushr shift and 255
                    val current = ramp[amount] ushr shift and 255
                    if ((ramp[255] ushr shift and 255) >= (paper ushr shift and 255))
                        assertTrue("Coverage cannot run backwards", current >= previous)
                    else assertTrue("Coverage cannot run backwards", current <= previous)
                }
            }
        }
        assertNull("Already readable lettering keeps its exact original raster", DocumentColors.inkRamp(-1, 0xff101820.toInt()))
    }


    @Test fun identifiedColoredInkUsesLinearSrgbContrastAndRetainsAntialiasing() {
        assertEquals(21.0, DocumentColors.contrast(-1, 0xff000000.toInt()), 0.000001)
        assertEquals(0.2126, DocumentColors.luminance(0xffff0000.toInt()), 0.000001)
        assertEquals(0.7152, DocumentColors.luminance(0xff00ff00.toInt()), 0.000001)
        assertEquals(0.0722, DocumentColors.luminance(0xff0000ff.toInt()), 0.000001)
        // Actual office-guide.ppsx title ink and accent1 fill; do not invert both independently.
        val ink = 0xff235d75.toInt()
        val paper = 0xff18a303.toInt()
        assertEquals(2.169614657, DocumentColors.contrast(ink, paper), 0.000001)
        val adjusted = DocumentColors.contrastingInk(ink, paper)
        assertTrue(DocumentColors.contrast(adjusted, paper) >= 4.5)
        assertTrue((adjusted and 255) > (adjusted ushr 16 and 255))
        for (coverage in 0..255) {
            val source = DocumentColors.blend(paper, ink, coverage)
            val recovered = DocumentColors.inkCoverage(source, paper, ink)
            assertTrue(recovered in 0..255)
            assertTrue(kotlin.math.abs(recovered - coverage) <= 2)
            val pixel = DocumentColors.blend(paper, adjusted, recovered)
            if (coverage == 0) assertEquals(paper, pixel)
            if (coverage == 255) assertEquals(adjusted, pixel)
        }
        assertEquals(-1, DocumentColors.inkCoverage(0xffff0000.toInt(), paper, ink))
        for (background in intArrayOf(-1, 0xff101820.toInt(), paper, 0xff777777.toInt())) {
            for (foreground in intArrayOf(ink, 0xff86a6bd.toInt(), 0xffcf4a35.toInt(), -1, 0xff000000.toInt())) {
                val corrected = DocumentColors.contrastingInk(foreground, background)
                assertTrue(DocumentColors.contrast(corrected, background) >= 4.5)
                if (DocumentColors.contrast(foreground, background) >= 4.5)
                    assertEquals(foreground, corrected)
            }
        }
    }

    @Test fun backdropInkMaskUsesOriginalRowExactBracketsAndClearsReusedStorage() {
        val blue = 0xff157e9e.toInt()
        val coral = 0xffe96951.toInt()
        val theme = PixelTheme(ThemeSettings(ReadingMode.ADAPTIVE))
        val mask = LongArray(4) { -1L }
        fun check(row: IntArray, expected: Set<Int>) {
            val source = IntArray(row.size + 11) { 0xff010203.toInt() }
            row.copyInto(source, 7)
            val unchanged = source.copyOf()
            theme.backdropInkMask(source, 7, row.size, mask)
            for (x in 0 until mask.size * 64) assertEquals("mask x=$x", x in expected,
                mask[x ushr 6] and (1L shl (x and 63)) != 0L)
            assertArrayEquals(unchanged, source)
            // Only consumed DIAGRAM bits must equal a full-row scan; outside bits are unspecified.
            val full = mask.copyOf()
            for (left in row.indices step 7) for (length in listOf(1, 4, 17, 64)) {
                val right = min(row.size, left + length)
                val bounded = LongArray(mask.size) { -1L }
                theme.backdropInkMask(source, 7, row.size, bounded, left, right)
                for (x in left until right) assertEquals("range=$left..$right x=$x",
                    full[x ushr 6] and (1L shl (x and 63)),
                    bounded[x ushr 6] and (1L shl (x and 63)))
            }
            assertArrayEquals(unchanged, source)
        }
        // Sparse high-shade chromatic marks plus neutral antialiasing reach the final
        // AUTO diagram fallback: 62.5% white paper, 12.5% pale gray,
        // 12.5% neutral midtone and 12.5% chroma (25% total middle pixels).
        // More than eight sampled chromatic shades must not leave a square paper halo.
        val sparse = IntArray(64 * 64) { i ->
            val x = i % 64; val y = i / 64
            when {
                x < 8 -> 0xff000000.toInt() or ((80 + y) shl 16) or ((120 + y) shl 8) or 240
                x < 16 -> 0xffa0a0a0.toInt()
                x < 24 -> 0xffdcdcdc.toInt()
                else -> -1
            }
        }
        val originalSparse = sparse.copyOf()
        assertEquals(PixelTheme.Tile.DIAGRAM, theme.tile(sparse, 64, 0, 0, 64, 64))
        for (y in 0 until 64) for (x in 0 until 8)
            assertEquals(sparse[y * 64 + x], theme.color(sparse[y * 64 + x], preserveColor = true))
        assertEquals(DocumentColors.palette(ThemeSettings(ReadingMode.ADAPTIVE)).background,
            theme.color(-1, preserveColor = true))
        assertArrayEquals(originalSparse, sparse)
        // Cross the 64-bit word boundary with white cores and pale antialias pixels.
        val row = IntArray(145) { blue }
        for (x in 62..68) row[x] = if (x == 62 || x == 68) 0xffe7f3f6.toInt() else -1
        check(row, (62..68).toSet())
        check(IntArray(145) { -1 }, emptySet())
        // A stable different backdrop resets the candidate; white between different plates is paper.
        check(IntArray(40) { when (it) { in 0..7 -> blue; in 12..19 -> coral; in 24..31 -> blue; else -> -1 } }, emptySet())
        for (gap in listOf(64, 65)) {
            check(IntArray(gap + 8) { if (it < 4 || it >= gap + 4) blue else -1 },
                if (gap == 64) (4 until 68).toSet() else emptySet())
        }
        // A 64-pixel gap plus the four-pixel endpoint run reaches the 68-pixel range margin.
        for (offset in listOf(0, 1, 63, 64, 67, 68)) {
            val edge = IntArray(offset + 72) { -1 }
            for (x in offset until offset + 4) edge[x] = blue
            for (x in offset + 68 until offset + 72) edge[x] = blue
            check(edge, (offset + 4 until offset + 68).toSet())
        }
        // A narrow matching margin works only with a strong bracket on the other side.
        for (reverse in listOf(false, true)) {
            val narrow = IntArray(16) { if (it < 2 || it in 8..11) blue else -1 }
            check(if (reverse) narrow.reversedArray() else narrow,
                if (reverse) (8..13).toSet() else (2..7).toSet())
            for (gap in listOf(64, 65)) {
                val edge = IntArray(gap + 6) { if (it < 2 || it >= gap + 2) blue else -1 }
                val expected = if (gap == 64) (2 until gap + 2).toSet() else emptySet()
                check(if (reverse) edge.reversedArray() else edge,
                    if (reverse) expected.map { edge.lastIndex - it }.toSet() else expected)
            }
        }
        // Select the weak margin inside the gap bound, not an earlier out-of-range one.
        check(IntArray(94) { if (it in 13..14 || it in 24..25 || it in 86..89) blue else -1 }, (26..85).toSet())
        check(IntArray(24) { when (it) { in 0..1 -> blue; in 6..9 -> coral; in 16..19 -> blue; else -> -1 } }, emptySet())
        // Three pixels do not establish a flat backdrop; partial alpha does not either.
        check(IntArray(12) { if (it < 3 || it >= 9) blue else -1 }, emptySet())
        check(IntArray(16) { if (it < 4 || it >= 12) blue and 0x7fffffff else -1 }, emptySet())
    }

    @Test fun backdropInkMaskLeavesExplicitPoliciesAndTransparentCanvasUntouched() {
        val pixels = IntArray(20) { if (it in 8..11) -1 else 0xff157e9e.toInt() }
        for (policy in ImageTreatment.entries.filter { it != ImageTreatment.AUTO }) {
            val mask = LongArray(1) { -1L }
            PixelTheme(ThemeSettings(ReadingMode.ADAPTIVE, imageTreatment = policy))
                .backdropInkMask(pixels, 0, pixels.size, mask)
            assertArrayEquals("$policy", longArrayOf(0L), mask)
        }
        val mask = LongArray(1) { -1L }
        PixelTheme(ThemeSettings(ReadingMode.ADAPTIVE), transparentCanvas = true)
            .backdropInkMask(pixels, 0, pixels.size, mask)
        assertArrayEquals(longArrayOf(0L), mask)
    }


    @Test fun everyNeutralLevelMatchesPaletteInterpolation() {
        for (mode in ReadingMode.entries) {
            val settings = ThemeSettings(mode)
            val palette = DocumentColors.palette(settings)
            val theme = PixelTheme(settings)
            for (level in 0..255) for (darkPaper in listOf(false, true)) {
                val input = 0x70000000 or (level shl 16) or (level shl 8) or level
                val expected = when (mode) {
                    ReadingMode.ORIGINAL -> input
                    ReadingMode.INVERT -> input xor 0x00ffffff
                    else -> {
                        val amount = (if (darkPaper) 255 - level else level) / 255.0
                        var color = input and -0x1000000
                        for (shift in intArrayOf(16, 8, 0)) {
                            val ink = palette.foreground ushr shift and 255
                            val paper = palette.background ushr shift and 255
                            color = color or ((ink + (paper - ink) * amount).roundToInt() shl shift)
                        }
                        color
                    }
                }
                assertEquals("$mode level=$level darkPaper=$darkPaper", expected, theme.color(input, darkPaper))
            }
        }
    }

    @Test fun semanticTextHasReadableContrast() {
        for (mode in ReadingMode.entries) {
            val palette = DocumentColors.palette(ThemeSettings(mode))
            for (text in intArrayOf(palette.foreground, palette.secondary, palette.link)) {
                assertTrue("$mode contrast", contrast(text, palette.background) >= 4.5)
            }
        }
    }

    @Test fun inversionIsExplicitAndReversibleIncludingAlpha() {
        val values = intArrayOf(0, 0x7f012345, 0xffFFFFFF.toInt(), 0xff102030.toInt())
        val original = PixelTheme(ThemeSettings(ReadingMode.ORIGINAL))
        val invert = PixelTheme(ThemeSettings(ReadingMode.INVERT))
        values.forEach {
            assertEquals(it, original.color(it))
            assertEquals(it, invert.color(invert.color(it)))
            assertEquals(it ushr 24, invert.color(it) ushr 24)
        }
    }

    @Test fun paperAndInkMapToPaletteWithoutLosingAlpha() {
        for (mode in listOf(ReadingMode.ADAPTIVE, ReadingMode.ADAPTIVE_LIGHT, ReadingMode.SEPIA, ReadingMode.HIGH_CONTRAST)) {
            val settings = ThemeSettings(mode)
            val theme = PixelTheme(settings)
            val palette = DocumentColors.palette(settings)
            assertEquals(palette.background, theme.color(-1))
            assertEquals(palette.foreground, theme.color(0xff000000.toInt()))
            assertEquals(0x70, theme.color(0x70FFFFFF) ushr 24)
            assertEquals(0x00abcdef, theme.color(0x00abcdef))
        }
    }

    @Test fun mixedPagePreservesPhotographsAndExistingDarkRegions() {
        val theme = PixelTheme(ThemeSettings(ReadingMode.ADAPTIVE))
        val page = IntArray(64 * 64) { index -> if (index % 17 == 0) 0xff000000.toInt() else -1 }
        assertFalse(theme.preserveTile(page, 64, 0, 0, 64, 64))
        val photo = IntArray(64 * 64) { index ->
            val gray = 55 + index % 130
            0xff000000.toInt() or (gray shl 16) or (gray shl 8) or gray
        }
        assertTrue(theme.preserveTile(photo, 64, 0, 0, 64, 64))
        assertTrue(theme.preserveTile(IntArray(4096) { 0xff161616.toInt() }, 64, 0, 0, 64, 64))
        assertTrue(theme.preserveTile(IntArray(4096) { 0xffff3300.toInt() }, 64, 0, 0, 64, 64))
        assertFalse(PixelTheme(ThemeSettings(preserveImages = false)).preserveTile(photo, 64, 0, 0, 64, 64))
    }

    @Test fun darkTransparentLineArtRemainsVisibleOnDarkPaper() {
        val pixels = IntArray(64 * 64) { index ->
            if (index % 64 in 8..56 && index / 64 in 8..56) 0xff000000.toInt() else 0
        }
        val theme = PixelTheme(ThemeSettings(ReadingMode.ADAPTIVE))
        assertFalse(theme.preserveTile(pixels, 64, 0, 0, 64, 64))
        assertEquals(DocumentColors.palette(ThemeSettings(ReadingMode.ADAPTIVE)).foreground, theme.color(0xff000000.toInt()))
        assertEquals(0, theme.color(0))
    }

    @Test fun systemModeFollowsConfigurationWithoutChangingExplicitAppearance() {
        val automatic = ThemeSettings(ReadingMode.SYSTEM, preserveImages = false)
        assertEquals(ThemeSettings(ReadingMode.ADAPTIVE, false), automatic.resolved(true))
        assertEquals(ThemeSettings(ReadingMode.ADAPTIVE_LIGHT, false), automatic.resolved(false))
        for (mode in ReadingMode.entries.filter { it != ReadingMode.SYSTEM }) {
            val explicit = ThemeSettings(mode)
            assertSame(explicit, explicit.resolved(true))
            assertSame(explicit, explicit.resolved(false))
        }
        assertEquals(ReadingMode.SYSTEM, automatic.mode)
        assertEquals(ReadingMode.SYSTEM, ThemeSettings().mode)
        assertEquals(ImageTreatment.AUTO, ThemeSettings().imageTreatment)
    }

    @Test fun adaptiveLightCorrectsDarkPaperAndWhiteLineArtButKeepsPhotographs() {
        val theme = PixelTheme(ThemeSettings(ReadingMode.ADAPTIVE_LIGHT))
        val darkPage = IntArray(4096) { if (it % 17 == 0) -1 else 0xff000000.toInt() }
        assertEquals(PixelTheme.Tile.DARK_PAPER, theme.tile(darkPage, 64, 0, 0, 64, 64))
        val palette = DocumentColors.palette(ThemeSettings(ReadingMode.ADAPTIVE_LIGHT))
        assertEquals(palette.background, theme.color(0xff000000.toInt(), darkPaper = true))
        assertEquals(palette.foreground, theme.color(-1, darkPaper = true))
        val lineArt = IntArray(4096) { if (it % 64 in 8..56 && it / 64 in 8..56) -1 else 0 }
        assertEquals(PixelTheme.Tile.DARK_PAPER, theme.tile(lineArt, 64, 0, 0, 64, 64))
        assertEquals(0, theme.color(0, darkPaper = true))
        assertEquals(0x70, theme.color(0x70ffffff, darkPaper = true) ushr 24)
        val photograph = IntArray(4096) {
            val value = 55 + it % 130
            0xff000000.toInt() or (value shl 16) or (value shl 8) or value
        }
        assertTrue(theme.preserveTile(photograph, 64, 0, 0, 64, 64))
        assertFalse(PixelTheme(ThemeSettings(ReadingMode.ADAPTIVE_LIGHT, false)).preserveTile(photograph, 64, 0, 0, 64, 64))
    }

    @Test fun semanticModesDistinguishTransparentInkFromOpaquePaperAndPhotos() {
        val black = IntArray(4096) { 0xff000000.toInt() }
        val white = IntArray(4096) { -1 }
        val photo = IntArray(4096) {
            val gray = 55 + it % 130
            0xff000000.toInt() or (gray shl 16) or (gray shl 8) or gray
        }
        for (mode in listOf(ReadingMode.ADAPTIVE, ReadingMode.ADAPTIVE_LIGHT, ReadingMode.SEPIA, ReadingMode.HIGH_CONTRAST, ReadingMode.SYSTEM)) {
            for (preserve in listOf(false, true)) {
                val settings = ThemeSettings(mode, preserve)
                val opaque = PixelTheme(settings)
                val darkPaper = when {
                    mode == ReadingMode.ADAPTIVE_LIGHT || mode == ReadingMode.SYSTEM -> PixelTheme.Tile.DARK_PAPER
                    preserve -> PixelTheme.Tile.KEEP
                    else -> PixelTheme.Tile.RECOLOR
                }
                assertEquals(darkPaper, opaque.tile(black, 64, 0, 0, 64, 64))
                assertEquals(PixelTheme.Tile.RECOLOR, opaque.tile(white, 64, 0, 0, 64, 64))
                val transparent = PixelTheme(settings, transparentCanvas = true)
                assertEquals(PixelTheme.Tile.RECOLOR, transparent.tile(black, 64, 0, 0, 64, 64))
                assertEquals(PixelTheme.Tile.DARK_PAPER, transparent.tile(white, 64, 0, 0, 64, 64))
                val whiteWithColor = IntArray(4096) { if (it % 64 < 8) 0xff285ca0.toInt() else -1 }
                assertEquals(PixelTheme.Tile.DARK_PAPER, transparent.tile(whiteWithColor, 64, 0, 0, 64, 64))
                assertEquals(PixelTheme.Tile.RECOLOR, transparent.tile(IntArray(4096) { 0x80000000.toInt() }, 64, 0, 0, 64, 64))
                assertEquals(PixelTheme.Tile.DARK_PAPER, transparent.tile(IntArray(4096) { 0x80ffffff.toInt() }, 64, 0, 0, 64, 64))
                val expectedPhoto = if (preserve) PixelTheme.Tile.KEEP else PixelTheme.Tile.RECOLOR
                assertEquals(expectedPhoto, opaque.tile(photo, 64, 0, 0, 64, 64))
                assertEquals(expectedPhoto, transparent.tile(photo, 64, 0, 0, 64, 64))
                assertEquals(expectedPhoto, transparent.tile(IntArray(photo.size) { (photo[it] and 0x00ffffff) or 0x80000000.toInt() }, 64, 0, 0, 64, 64))
            }
        }
    }

    @Test fun antialiasedTextDoesNotChangeFlatColorButMixedGrayscalePhotosStayPreserved() {
        val blue = 0xff285ca0.toInt()
        val textAndShape = IntArray(4096) { index ->
            val x = index % 64
            val y = index / 64
            when {
                y >= 56 -> blue
                y in 8..15 && x in 4..55 -> {
                    val gray = 16 + (x / 4) * 13
                    0xff000000.toInt() or (gray shl 16) or (gray shl 8) or gray
                }
                else -> -1
            }
        }
        val photograph = IntArray(4096) { index ->
            when {
                index % 64 < 8 -> blue
                index / 64 < 8 -> -1
                else -> {
                    val gray = 55 + index % 130
                    0xff000000.toInt() or (gray shl 16) or (gray shl 8) or gray
                }
            }
        }
        for (mode in listOf(ReadingMode.ADAPTIVE, ReadingMode.ADAPTIVE_LIGHT, ReadingMode.SEPIA, ReadingMode.HIGH_CONTRAST, ReadingMode.SYSTEM)) {
            val theme = PixelTheme(ThemeSettings(mode))
            assertEquals("$mode text and shape", PixelTheme.Tile.DIAGRAM, theme.tile(textAndShape, 64, 0, 0, 64, 64))
            assertEquals("$mode grayscale photograph with colored accent", PixelTheme.Tile.KEEP, theme.tile(photograph, 64, 0, 0, 64, 64))
            assertEquals(PixelTheme.Tile.RECOLOR,
                PixelTheme(ThemeSettings(mode, false)).tile(textAndShape, 64, 0, 0, 64, 64))
        }
    }

    @Test fun denseFlatHeadersAdaptPaperDespiteNeutralAndColoredGlyphAntialiasing() {
        val blue = 0xff285ca0.toInt()
        val tiles = listOf(false, true).map { coloredInk ->
            IntArray(4096) { index ->
                val x = index % 64
                val y = index / 64
                when {
                    coloredInk && y < 4 && x < 28 -> 0xff205080.toInt() + x * 257
                    y < 24 -> blue
                    y in 28..31 && x in 4..55 -> {
                        val gray = 16 + (x / 4) * 13
                        0xff000000.toInt() or (gray shl 16) or (gray shl 8) or gray
                    }
                    else -> -1
                }
            }
        }
        // Flat color beside varied photographic detail must not hide that detail.
        val variedColors = IntArray(256) { index ->
            when {
                index < 40 -> 0xff000000.toInt() or ((index * 59 and 255) shl 16) or
                    ((index * 37 and 255) shl 8) or (index * 13 and 255)
                index < 100 -> blue
                index < 110 -> 0xff606060.toInt() + (index - 100) * 0x010101
                else -> -1
            }
        }
        val texturedGreen = IntArray(4096) { index ->
            when (index / 64) {
                in 0..23 -> 0xff109010.toInt() + (index % 31) * 0x010101
                in 28..31 -> 0xff606060.toInt() + (index % 31) * 0x010101
                else -> -1
            }
        }
        for (mode in listOf(ReadingMode.ADAPTIVE, ReadingMode.ADAPTIVE_LIGHT,
            ReadingMode.SEPIA, ReadingMode.HIGH_CONTRAST, ReadingMode.SYSTEM)) {
            val settings = ThemeSettings(mode)
            val theme = PixelTheme(settings)
            assertEquals("$mode textured green photograph", PixelTheme.Tile.KEEP,
                theme.tile(texturedGreen, 64, 0, 0, 64, 64))
            for (tile in tiles) {
                assertEquals("$mode dense header", PixelTheme.Tile.DIAGRAM, theme.tile(tile, 64, 0, 0, 64, 64))
                for (index in tile.indices) {
                    val expected = if (index / 64 < 24) tile[index] else theme.color(tile[index])
                    assertEquals(expected, theme.color(tile[index], preserveColor = true))
                }
                assertEquals(PixelTheme.Tile.KEEP,
                    PixelTheme(settings.copy(imageTreatment = ImageTreatment.KEEP)).tile(tile, 64, 0, 0, 64, 64))
                assertEquals(PixelTheme.Tile.DIM,
                    PixelTheme(settings.copy(imageTreatment = ImageTreatment.DIM)).tile(tile, 64, 0, 0, 64, 64))
                assertEquals(PixelTheme.Tile.KEEP,
                    PixelTheme(settings, transparentCanvas = true).tile(tile, 64, 0, 0, 64, 64))
            }
            for (photo in listOf(variedColors, variedColors.reversedArray())) {
                assertEquals("$mode varied color remains preserved", PixelTheme.Tile.KEEP,
                    theme.tile(photo, 16, 0, 0, 16, 16))
            }
        }
    }

    @Test fun automaticTreatmentAdaptsMutedBackgroundsWithoutInvertingPhotographs() {
        val settings = ThemeSettings(ReadingMode.ADAPTIVE)
        val theme = PixelTheme(settings)
        for (paper in intArrayOf(0xffdce8f0.toInt(), 0xffc8c8c8.toInt(), 0xffe8e2bf.toInt())) {
            val tile = IntArray(4096) { if (it % 64 in 12..48 && it / 64 in 24..28) 0xff202020.toInt() else paper }
            assertEquals(PixelTheme.Tile.RECOLOR, theme.tile(tile, 64, 0, 0, 64, 64))
            assertTrue("Muted paper keeps legible ink", contrast(theme.color(paper), theme.color(0xff202020.toInt())) >= 4.5)
            if (paper == 0xffc8c8c8.toInt()) assertEquals(PixelTheme.Tile.KEEP,
                PixelTheme(settings.copy(imageTreatment = ImageTreatment.KEEP)).tile(tile, 64, 0, 0, 64, 64))
        }
        val photo = IntArray(4096) { 0xff000000.toInt() or ((it * 59 and 255) shl 16) or ((it * 37 and 255) shl 8) or (it * 13 and 255) }
        assertEquals(PixelTheme.Tile.KEEP, theme.tile(photo, 64, 0, 0, 64, 64))
        val darkPaper = IntArray(4096) { if (it % 64 in 12..48 && it / 64 in 24..28) -1 else 0xff183f55.toInt() }
        val light = PixelTheme(settings.copy(mode = ReadingMode.ADAPTIVE_LIGHT))
        assertEquals(PixelTheme.Tile.DARK_PAPER, light.tile(darkPaper, 64, 0, 0, 64, 64))
        assertTrue(contrast(light.color(0xff183f55.toInt(), darkPaper = true), light.color(-1, darkPaper = true)) >= 4.5)
    }

    @Test fun imagePoliciesKeepLegacyCodesAndDimmingRetainsHueAndAlpha() {
        assertEquals(ImageTreatment.KEEP, ThemeSettings(preserveImages = true).imageTreatment)
        assertEquals(ImageTreatment.RECOLOR, ThemeSettings(preserveImages = false).imageTreatment)
        assertEquals(ImageTreatment.KEEP, ImageTreatment.fromCode(1))
        assertEquals(ImageTreatment.RECOLOR, ImageTreatment.fromCode(0))
        assertEquals(ImageTreatment.AUTO, ImageTreatment.fromCode(-1))
        for (policy in ImageTreatment.entries) assertEquals(policy, ImageTreatment.fromCode(policy.code))
        val theme = PixelTheme(ThemeSettings(ReadingMode.ADAPTIVE, ImageTreatment.DIM))
        assertEquals(0x0099aabb, theme.dim(0x0099aabb))
        val source = 0x804080c0.toInt()
        val dimmed = theme.dim(source)
        assertEquals(source ushr 24, dimmed ushr 24)
        assertEquals(0x80306090.toInt(), dimmed)
        val photo = IntArray(4096) { val gray = 55 + it % 130; 0xff000000.toInt() or (gray shl 16) or (gray shl 8) or gray }
        assertEquals(PixelTheme.Tile.DIM, theme.tile(photo, 64, 0, 0, 64, 64))
        assertEquals(PixelTheme.Tile.RECOLOR, theme.tile(IntArray(4096) { -1 }, 64, 0, 0, 64, 64))
    }

    @Test fun automaticMulticolorMarksAdaptWhitePaperAndAdjacentInkWithoutChangingPhotosOrKeep() {
        // More than eight sampled chromatic shades, as in rasterized emoji/colored PDF text.
        // Black strokes in the bottom rows stand for the adjacent text sharing this tile.
        val marks = IntArray(4096) { index ->
            val x = index % 64
            val y = index / 64
            when {
                x in 8..31 && y < 48 -> 0xff000000.toInt() or
                    ((160 + y) shl 16) or ((40 + x) shl 8) or 10
                y in 56..59 && x % 8 < 4 -> 0xff606060.toInt()
                y >= 60 && x % 8 < 4 -> 0xff000000.toInt()
                else -> -1
            }
        }
        val photograph = IntArray(4096) { index ->
            val gray = 55 + index % 130
            0xff000000.toInt() or (gray shl 16) or (gray shl 8) or gray
        }
        for (mode in listOf(ReadingMode.ADAPTIVE, ReadingMode.ADAPTIVE_LIGHT,
            ReadingMode.SEPIA, ReadingMode.HIGH_CONTRAST, ReadingMode.SYSTEM)) {
            val settings = ThemeSettings(mode)
            val theme = PixelTheme(settings)
            assertEquals("$mode multicolor marks on paper", PixelTheme.Tile.DIAGRAM,
                theme.tile(marks, 64, 0, 0, 64, 64))
            val palette = DocumentColors.palette(settings)
            for (pixel in marks) {
                val expected = when (pixel) {
                    -1 -> palette.background
                    0xff000000.toInt() -> palette.foreground
                    0xff606060.toInt() -> theme.color(pixel)
                    else -> pixel
                }
                assertEquals("$mode pixel", expected, theme.color(pixel, preserveColor = true))
            }
            assertEquals("$mode photograph", PixelTheme.Tile.KEEP,
                theme.tile(photograph, 64, 0, 0, 64, 64))
            val photoOnWhite = IntArray(4096) { index ->
                when {
                    index / 64 < 40 -> -1
                    index / 64 < 48 -> marks[index % 32 + 8]
                    else -> photograph[index]
                }
            }
            assertEquals("$mode photograph on white with neutral detail", PixelTheme.Tile.KEEP,
                theme.tile(photoOnWhite, 64, 0, 0, 64, 64))
            assertEquals("$mode explicit preservation", PixelTheme.Tile.KEEP,
                PixelTheme(settings.copy(imageTreatment = ImageTreatment.KEEP)).tile(marks, 64, 0, 0, 64, 64))
            assertEquals("$mode explicit dimming", PixelTheme.Tile.DIM,
                PixelTheme(settings.copy(imageTreatment = ImageTreatment.DIM)).tile(marks, 64, 0, 0, 64, 64))
        }
    }

    private fun contrast(a: Int, b: Int): Double {
        fun luminance(color: Int): Double {
            fun channel(shift: Int): Double {
                val c = (color ushr shift and 255) / 255.0
                return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
            }
            return channel(16) * 0.2126 + channel(8) * 0.7152 + channel(0) * 0.0722
        }
        val x = luminance(a)
        val y = luminance(b)
        return (max(x, y) + 0.05) / (min(x, y) + 0.05)
    }
}
