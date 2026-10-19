package app.smallthingz.alldoc.ui

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.*
import org.junit.Test

class ReaderThemeTest {
    @Test fun normalThemesKeepEveryMaterialPaletteRole() {
        for (palette in listOf(lightColorScheme(), darkColorScheme())) {
            assertSame(palette, readerColorScheme(palette, black = false))
        }
    }

    @Test fun amoledChangesOnlyTheRootSurfaceTones() {
        val palette = darkColorScheme(secondaryContainer = Color(0xFF305940),
            onSecondaryContainer = Color(0xFFC5ECD0), surfaceContainer = Color(0xFF18231C),
            surfaceContainerHigh = Color(0xFF233127), onSurface = Color(0xFFDAE8DC),
            onSurfaceVariant = Color(0xFFBBCABD), outlineVariant = Color(0xFF3D4B40))
        val black = readerColorScheme(palette, black = true)
        assertEquals(Color.Black, black.background)
        assertEquals(Color.Black, black.surface)
        assertEquals(Color.Black, black.surfaceDim)
        assertEquals(Color.Black, black.surfaceContainerLowest)
        assertEquals(palette.secondaryContainer, black.secondaryContainer)
        assertEquals(palette.onSecondaryContainer, black.onSecondaryContainer)
        assertEquals(palette.surfaceContainer, black.surfaceContainer)
        assertEquals(palette.surfaceContainerHigh, black.surfaceContainerHigh)
        assertEquals(palette.onSurface, black.onSurface)
        assertEquals(palette.onSurfaceVariant, black.onSurfaceVariant)
        assertEquals(palette.outlineVariant, black.outlineVariant)
        assertEquals(palette.primary, black.primary)
        assertEquals(palette.onPrimary, black.onPrimary)
    }
}
