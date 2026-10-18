package app.smallthingz.alldoc.theme

import kotlin.math.max
import kotlin.math.min
import org.junit.Assert.*
import org.junit.Test

class ScanInkTest {
    @Test fun scannedBlueKeepsItsSourceContrastAndAntialiasedEdgesAcrossReadingPalettes() {
        val blue = 0xff235d75.toInt()
        for (mode in listOf(ReadingMode.ADAPTIVE, ReadingMode.ADAPTIVE_LIGHT,
            ReadingMode.SEPIA, ReadingMode.HIGH_CONTRAST)) {
            val settings = ThemeSettings(mode)
            val theme = PixelTheme(settings)
            val ink = ScanInk(theme, settings)
            val paper = DocumentColors.palette(settings).background
            val maximum = max(DocumentColors.contrast(-1, paper),
                DocumentColors.contrast(0xff000000.toInt(), paper))
            for (amount in 1..255) {
                val source = DocumentColors.blend(-1, blue, amount)
                val mapped = ink.color(source)
                val required = min(maximum, DocumentColors.contrast(source, -1))
                val r = source ushr 16 and 255; val g = source ushr 8 and 255; val b = source and 255
                val chroma = maxOf(r, g, b) - minOf(r, g, b)
                val paperOrNeutral = chroma == 0 || chroma <= 6 && (54 * r + 183 * g + 19 * b) ushr 8 >= 245
                if (paperOrNeutral) assertEquals("Neutral or near-paper fringe", theme.color(source), mapped)
                else assertTrue("$mode chromatic AA coverage $amount", DocumentColors.contrast(mapped, paper) >= required)
                assertEquals(mapped, ink.color(source))
            }
            val mapped = ink.color(blue)
            assertTrue("Blue remains distinguishable", (mapped and 255) > (mapped ushr 16 and 255))
            assertTrue(DocumentColors.contrast(mapped, paper) >= 7.25)
            for (gray in 0..255) {
                val source = 0xff000000.toInt() or (gray * 0x010101)
                assertEquals("Neutral AA unchanged", theme.color(source), ink.color(source))
            }
        }
    }

    @Test fun texturedPaperAndPaleInkRespectActualPerceptualContrast() {
        val sources = listOf(0xff235d75.toInt(), 0xfff0faff.toInt(), 0xfff8fcff.toInt())
        for (mode in listOf(ReadingMode.ADAPTIVE, ReadingMode.ADAPTIVE_LIGHT,
            ReadingMode.SEPIA, ReadingMode.HIGH_CONTRAST)) {
            val settings = ThemeSettings(mode)
            val theme = PixelTheme(settings)
            val ink = ScanInk(theme, settings)
            for (r in 239..255) for (g in 239..255) for (b in 239..255) {
                if (maxOf(r, g, b) - minOf(r, g, b) > 6 || (54 * r + 183 * g + 19 * b) ushr 8 < 245) continue
                val paper = 0xff000000.toInt() or (r shl 16) or (g shl 8) or b
                val mappedPaper = theme.color(paper)
                assertEquals("Paper tint is not ink", mappedPaper, ink.color(paper))
                for (source in sources)
                    assertTrue("$mode source/paper luminance bound", DocumentColors.contrast(ink.color(source), mappedPaper) >=
                        DocumentColors.contrast(source, paper))
            }
            for (source in listOf(0xff010203.toInt(), 0xff05012a.toInt(), 0xffd4010c.toInt(), 0xff00b2b9.toInt()))
                assertTrue("Finite readable ink", DocumentColors.contrast(ink.color(source), DocumentColors.palette(settings).background) >=
                    min(4.5, DocumentColors.contrast(source, -1)))
            val transparent = 0x80235d75.toInt()
            assertEquals(theme.color(transparent), ink.color(transparent))
        }
        assertEquals(2048, ScanInk.CACHE_BYTES)
    }

    @Test fun maximumSepiaGrainPreservesBlueAndPaleInkContrast() {
        val settings = ThemeSettings(ReadingMode.SEPIA, paperRoughness = 100)
        val theme = PixelTheme(settings)
        val ink = ScanInk(theme, settings)
        fun textured(color: Int): Set<Int> = buildSet {
            for (y in 0 until 64) for (x in 0 until 64) add(PaperTexture.pixel(color, x, y, 100))
        }
        for (paper in listOf(-1, 0xfff5f5f5.toInt(), 0xfff6f6f0.toInt())) {
            val backgrounds = textured(theme.color(paper))
            for (source in listOf(0xff235d75.toInt(), 0xfff0faff.toInt(), 0xfff8fcff.toInt())) {
                val required = DocumentColors.contrast(source, paper)
                for (foreground in textured(ink.color(source))) for (background in backgrounds)
                    assertTrue("Independent paper/ink grain extrema", DocumentColors.contrast(foreground, background) >= required)
            }
        }
    }

}
