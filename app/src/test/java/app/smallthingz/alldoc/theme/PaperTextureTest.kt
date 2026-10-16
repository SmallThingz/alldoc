package app.smallthingz.alldoc.theme

import app.smallthingz.alldoc.state.AppTheme
import org.junit.Assert.*
import org.junit.Test

class PaperTextureTest {
    @Test fun grainIsStaticBoundedAndLeavesInkAndTransparencyUntouched() {
        val paper = DocumentColors.palette(ThemeSettings(ReadingMode.SEPIA)).background
        val values = HashSet<Int>()
        for (y in 0 until 64) for (x in 0 until 64) {
            assertEquals(paper, PaperTexture.pixel(paper, x, y, 0))
            val grain = PaperTexture.pixel(paper, x, y, 100)
            values += grain
            assertEquals(grain, PaperTexture.pixel(paper, x + 64, y + 64, 100))
            assertEquals(grain, PaperTexture.pixel(paper, x, y, Int.MAX_VALUE))
            assertEquals(255, grain ushr 24)
            for (shift in intArrayOf(0, 8, 16))
                assertTrue(kotlin.math.abs((grain ushr shift and 255) - (paper ushr shift and 255)) <= 10)
            for (color in intArrayOf(0, 0x7ff4ecd8, 0xff332719.toInt(), 0xff157e9e.toInt()))
                assertEquals(color, PaperTexture.pixel(color, x, y, 100))
        }
        assertTrue("Rough paper has visible variation", values.size >= 16)
        assertEquals(0, ThemeSettings(paperRoughness = -1).normalized().paperRoughness)
        assertEquals(100, ThemeSettings(paperRoughness = 1000).normalized().paperRoughness)
        assertEquals(0, ThemeSettings(ReadingMode.SEPIA, true).paperRoughness)
    }

    @Test fun appBrightnessDoesNotOverrideDocumentTreatment() {
        for (systemDark in listOf(false, true)) {
            assertEquals(systemDark, AppTheme.SYSTEM.isDark(systemDark))
            assertFalse(AppTheme.LIGHT.isDark(systemDark))
            assertTrue(AppTheme.DARK.isDark(systemDark))
            assertTrue(AppTheme.AMOLED.isDark(systemDark))
        }
        val paper = ThemeSettings(ReadingMode.SEPIA, paperRoughness = 37)
        assertSame(paper, paper.resolved(false))
        assertSame(paper, paper.resolved(true))
        assertEquals(37, paper.copy(mode = ReadingMode.SYSTEM).resolved(true).paperRoughness)
    }
}
