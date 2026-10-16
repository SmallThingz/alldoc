package app.smallthingz.alldoc.theme

import android.graphics.Bitmap
import android.os.CancellationSignal
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.smallthingz.alldoc.core.Block
import app.smallthingz.alldoc.core.Document
import app.smallthingz.alldoc.core.Section
import app.smallthingz.alldoc.engine.TextSession
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PaperRenderingTest {
    @Test fun rasterGrainPreservesPhotosSourceAndDeterministicRendering() {
        val pixels = IntArray(128 * 69) { index ->
            val x = index % 128
            if (x < 64) -1 else {
                val gray = 55 + index % 130
                0xff000000.toInt() or (gray shl 16) or (gray shl 8) or gray
            }
        }
        val source = Bitmap.createBitmap(pixels, 128, 69, Bitmap.Config.ARGB_8888)
        val settings = ThemeSettings(ReadingMode.SEPIA, paperRoughness = 100)
        val first = BitmapTheme.apply(source, settings, CancellationSignal())
        val second = BitmapTheme.apply(source, settings, CancellationSignal())
        try {
            val varied = HashSet<Int>()
            for (y in 0 until 69) for (x in 0 until 128) {
                val index = y * 128 + x
                assertEquals(pixels[index], source.getPixel(x, y))
                assertEquals(first.getPixel(x, y), second.getPixel(x, y))
                if (x >= 64) assertEquals("Photographs stay unchanged", pixels[index], first.getPixel(x, y))
                else varied += first.getPixel(x, y)
            }
            assertTrue(varied.size >= 16)
        } finally { first.recycle(); second.recycle(); source.recycle() }
    }

    @Test fun semanticPaperUsesSameGrainWithoutChangingTextOrPagination() {
        val document = Document("text", sections = listOf(Section("", blocks = listOf(Block("paragraph", "AllDoc paper")))))
        TextSession("Paper.md", "text/markdown", document, CancellationSignal()).use { session ->
            val settings = ThemeSettings(ReadingMode.SEPIA, paperRoughness = 83)
            val plain = session.renderPage(0, 640, settings.copy(paperRoughness = 0), CancellationSignal())
            val grain = session.renderPage(0, 640, settings, CancellationSignal())
            try {
                assertEquals(plain.width, grain.width)
                assertEquals(plain.height, grain.height)
                assertEquals("AllDoc paper", session.text(CancellationSignal()))
                val background = DocumentColors.palette(settings).background
                var changed = 0
                for (y in 0 until 128) for (x in 0 until 256) {
                    val previous = plain.getPixel(x, y)
                    val next = grain.getPixel(x, y)
                    if (previous == background && previous != next) changed++
                    if (previous == DocumentColors.palette(settings).foreground) assertEquals(previous, next)
                }
                assertTrue("Semantic pages must show the selected paper texture", changed > 1000)
            } finally { plain.recycle(); grain.recycle() }
        }
    }
}
