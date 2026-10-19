package app.smallthingz.alldoc.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.ExifInterface
import android.os.CancellationSignal
import android.os.OperationCanceledException
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.smallthingz.alldoc.theme.ReadingMode
import app.smallthingz.alldoc.theme.ThemeSettings
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImageSessionRegionTest {
    @Test fun aWhitePhotoCornerKeepsTheCompletePreviewThemeAtDeepZoom() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(source)
        val paint = Paint()
        for (y in 0 until 128 step 8) for (x in 0 until 128 step 8) {
            paint.color = Color.rgb((x * 5 + y * 3) % 256, (x * 11 + 60) % 256, (y * 7 + 100) % 256)
            canvas.drawRect(x.toFloat(), y.toFloat(), x + 8f, y + 8f, paint)
        }
        paint.color = Color.WHITE; canvas.drawRect(0f, 0f, 40f, 40f, paint)
        paint.color = Color.BLACK; canvas.drawRect(10f, 10f, 13f, 30f, paint)
        val file = File(context.cacheDir, "photo-context-${UUID.randomUUID()}.jpg")
        try {
            file.outputStream().use { assertTrue(source.compress(Bitmap.CompressFormat.JPEG, 100, it)) }
            for (regionFirst in listOf(false, true)) {
                val signal = CancellationSignal()
                val settings = ThemeSettings(ReadingMode.ADAPTIVE)
                ImageSession(file, "Saved.pages", "image/jpeg", signal).use { session ->
                    val region = PageRegion(0f, 0f, .25f, .25f, 256, 256)
                    var full: Bitmap? = if (regionFirst) null else session.renderPage(0, 128, settings, signal)
                    val detail = requireNotNull(session.renderRegion(0, region, settings, signal))
                    try {
                        if (full == null) full = session.renderPage(0, 128, settings, signal)
                        val paper = requireNotNull(full).getPixel(24, 8)
                        val zoomed = detail.getPixel(192, 64)
                        assertTrue("The white corner belongs to a photograph, not a newly inferred scanned page", Color.red(paper) > 220)
                        assertTrue("Full-page and region-first paths must agree on that image's treatment",
                            kotlin.math.abs(Color.red(paper) - Color.red(zoomed)) <= 8 &&
                            kotlin.math.abs(Color.green(paper) - Color.green(zoomed)) <= 8 &&
                            kotlin.math.abs(Color.blue(paper) - Color.blue(zoomed)) <= 8)
                    } finally { detail.recycle(); full?.recycle() }
                }
            }
        } finally { source.recycle(); file.delete() }
    }

    @Suppress("DEPRECATION")
    @Test fun savedPreviewRegionsPreserveAllExifOrientationsAndNativePixels() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = Bitmap.createBitmap(320, 192, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        for ((index, color) in listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW).withIndex()) {
            paint.color = color
            val x = (index % 2) * 160f; val y = (index / 2) * 96f
            canvas.drawRect(x, y, x + 160, y + 96, paint)
        }
        // Neutral luma detail avoids JPEG chroma subsampling masquerading as lost pixels.
        paint.color = Color.WHITE; canvas.drawRect(96f, 40f, 224f, 152f, paint)
        paint.color = Color.BLACK
        for (x in 96 until 224 step 4) canvas.drawRect(x.toFloat(), 40f, x + 1f, 152f, paint)
        try {
            for (orientation in 1..8) {
                val file = File(context.cacheDir, "region-${UUID.randomUUID()}.jpg")
                try {
                    file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)) }
                    ExifInterface(file.path).apply { setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString()); saveAttributes() }
                    val signal = CancellationSignal()
                    val settings = ThemeSettings(ReadingMode.ORIGINAL)
                    ImageSession(file, "Saved.pages", "image/jpeg", signal).use { session ->
                        val width = if (orientation >= 5) 192 else 320
                        val height = if (orientation >= 5) 320 else 192
                        assertEquals(height.toFloat() / width, session.pageAspect(0, signal), .001f)
                        val full = session.renderPage(0, width, settings, signal)
                        val region = PageRegion(.25f, .25f, .75f, .75f, width / 2, height / 2)
                        val detail = requireNotNull(session.renderRegion(0, region, settings, signal))
                        try {
                            assertEquals(region.width, detail.width); assertEquals(region.height, detail.height)
                            if (orientation == 1) {
                                assertTrue("Encoded fixture must retain native one-pixel contrast",
                                    Color.red(full.getPixel(114, 68)) - Color.red(full.getPixel(112, 68)) > 150)
                                assertTrue("Native one-pixel lines must survive viewport decoding",
                                    Color.red(detail.getPixel(34, 20)) - Color.red(detail.getPixel(32, 20)) > 150)
                            }
                            // Sample the colored corners, outside the central sharpness pattern.
                            for ((u, v) in listOf(.05f to .05f, .95f to .05f, .05f to .95f, .95f to .95f)) {
                                val x = (detail.width * u).toInt(); val y = (detail.height * v).toInt()
                                val expected = full.getPixel(width / 4 + x, height / 4 + y)
                                val actual = detail.getPixel(x, y)
                                assertTrue("EXIF $orientation region must match the original-layout preview",
                                    kotlin.math.abs(Color.red(expected) - Color.red(actual)) <= 12 &&
                                    kotlin.math.abs(Color.green(expected) - Color.green(actual)) <= 12 &&
                                    kotlin.math.abs(Color.blue(expected) - Color.blue(actual)) <= 12)
                            }
                        } finally { detail.recycle(); full.recycle() }
                        val deep = requireNotNull(session.renderRegion(0, region.copy(width = width * 2, height = height * 2), settings, signal))
                        try {
                            assertEquals(width * 2, deep.width); assertEquals(height * 2, deep.height)
                            assertTrue(deep.allocationByteCount <= PageRegion.MAX_PIXELS * 4)
                        } finally { deep.recycle() }
                        assertThrows(OperationCanceledException::class.java) {
                            session.renderRegion(0, region, settings, CancellationSignal().apply { cancel() })
                        }
                    }
                } finally { file.delete() }
            }
        } finally { bitmap.recycle() }
    }
}
