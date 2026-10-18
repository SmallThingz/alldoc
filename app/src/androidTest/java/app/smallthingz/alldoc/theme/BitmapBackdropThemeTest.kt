package app.smallthingz.alldoc.theme

import android.graphics.Bitmap
import android.os.CancellationSignal
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BitmapBackdropThemeTest {
    @Test fun completeVectorHintsKeepMixedBlueAndRedInkOnBlackPaper() {
        val width = 256
        val height = 128
        val paper = 0xff000000.toInt()
        val pixels = IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            val ink = if (x < 128) 0xff3366cc.toInt() else 0xffcc3333.toInt()
            when {
                y !in 32 until 72 || x % 128 !in 48 until 56 -> paper
                x % 128 == 48 -> DocumentColors.blend(paper, ink, 64)
                x % 128 == 55 -> DocumentColors.blend(paper, ink, 128)
                else -> ink
            }
        }
        val source = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        try {
            val output = BitmapTheme.apply(source, ThemeSettings(ReadingMode.ADAPTIVE), CancellationSignal(), emptyList())
            try {
                val actual = IntArray(pixels.size)
                output.getPixels(actual, 0, width, 0, 0, width, height)
                assertArrayEquals("Complete vector hints keep dark paper and every mixed-ink AA contrast", pixels, actual)
                source.getPixels(actual, 0, width, 0, 0, width, height)
                assertArrayEquals("Original pixels remain reusable", pixels, actual)
            } finally { output.recycle() }
        } finally { source.recycle() }
    }

    @Test fun completeHintsKeepBlackPaperAndReadableBlueInkWithoutChangingImagePolicies() {
        val width = 256
        val height = 128
        val paper = 0xff000000.toInt()
        val ink = 0xff3366cc.toInt()
        val pixels = IntArray(width * height) { index ->
            val x = index % width % 128
            val y = index / width
            when {
                y !in 32 until 72 || x !in 48 until 56 -> paper
                x == 48 -> DocumentColors.blend(paper, ink, 64)
                x == 55 -> DocumentColors.blend(paper, ink, 128)
                else -> ink
            }
        }
        val source = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        val settings = ThemeSettings(ReadingMode.ADAPTIVE)
        val protected = listOf(RasterRegion(.5f, 0f, 1f, 1f))
        try {
            for (hints in listOf(emptyList(), protected)) {
                val output = BitmapTheme.apply(source, settings, CancellationSignal(), hints)
                try {
                    val actual = IntArray(pixels.size)
                    output.getPixels(actual, 0, width, 0, 0, width, height)
                    for (index in pixels.indices) {
                        when {
                            hints.isNotEmpty() && index % width >= width / 2 ->
                                assertEquals("Image pixel $index", pixels[index], actual[index])
                            pixels[index] == paper -> assertEquals("Dark paper $index", paper, actual[index])
                            pixels[index] == ink -> assertTrue("Blue core $index",
                                DocumentColors.contrast(actual[index], paper) >= 4.5)
                            else -> assertTrue("Blue AA $index", DocumentColors.contrast(actual[index], paper) + 1e-12 >=
                                DocumentColors.contrast(pixels[index], paper))
                        }
                    }
                } finally { output.recycle() }
            }
            for (policy in listOf(ImageTreatment.KEEP, ImageTreatment.DIM)) {
                val explicit = settings.copy(imageTreatment = policy)
                val baseline = BitmapTheme.apply(source, explicit, CancellationSignal())
                try {
                    val hinted = BitmapTheme.apply(source, explicit, CancellationSignal(), protected)
                    try {
                        val expected = IntArray(pixels.size)
                        val actual = IntArray(pixels.size)
                        baseline.getPixels(expected, 0, width, 0, 0, width, height)
                        hinted.getPixels(actual, 0, width, 0, 0, width, height)
                        assertArrayEquals("$policy keeps its existing policy", expected, actual)
                    } finally { hinted.recycle() }
                } finally { baseline.recycle() }
            }
            val unchanged = IntArray(pixels.size)
            source.getPixels(unchanged, 0, width, 0, 0, width, height)
            assertArrayEquals("Original pixels remain reusable", pixels, unchanged)
        } finally { source.recycle() }
    }
}
