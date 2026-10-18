package app.smallthingz.alldoc.theme

import android.graphics.Bitmap
import android.graphics.Color
import android.os.CancellationSignal
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class ScanInkThemeTest {
    @Test fun scannedBlueRetainsPerceptualContrastAndActualPhotoPixels() {
        val source = Bitmap.createBitmap(512, 256, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.WHITE)
        val blue = 0xff235d75.toInt()
        for (y in 61 until 77) for (x in 27 until 41)
            source.setPixel(x, y, DocumentColors.blend(-1, blue,
                if (y in 62 until 76 && x in 28 until 40) 255 else 128))
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open("theme/office-photo.rgb").use { it.readBytes() }
        assertEquals(64 * 64 * 3, bytes.size)
        val photo = IntArray(64 * 64) { at -> Color.rgb(bytes[at * 3].toInt() and 255,
            bytes[at * 3 + 1].toInt() and 255, bytes[at * 3 + 2].toInt() and 255) }
        source.setPixels(photo, 0, 64, 320, 96, 64, 64)
        val original = requireNotNull(source.copy(Bitmap.Config.ARGB_8888, false))
        try {
            for (mode in listOf(ReadingMode.ADAPTIVE, ReadingMode.ADAPTIVE_LIGHT,
                ReadingMode.SEPIA, ReadingMode.HIGH_CONTRAST)) {
                val settings = ThemeSettings(mode)
                val result = BitmapTheme.apply(source, settings, CancellationSignal(), listOf(RasterRegion(0f, 0f, 1f, 1f)))
                try {
                    val paper = result.getPixel(16, 16)
                    assertEquals(DocumentColors.palette(settings).background, paper)
                    assertTrue("$mode blue source contrast", DocumentColors.contrast(result.getPixel(30, 64), paper) >=
                        DocumentColors.contrast(blue, Color.WHITE))
                    for (y in 61 until 77) for (x in 27 until 41)
                        assertTrue("$mode AA at $x,$y", DocumentColors.contrast(result.getPixel(x, y), paper) >=
                            DocumentColors.contrast(source.getPixel(x, y), Color.WHITE))
                    for (y in 96 until 160) for (x in 320 until 384)
                        assertEquals("$mode photo at $x,$y", source.getPixel(x, y), result.getPixel(x, y))
                } finally { result.recycle() }
            }
            assertTrue(source.sameAs(original)); assertFalse(source.isRecycled)
        } finally { original.recycle(); source.recycle() }
    }
}
