package app.smallthingz.alldoc.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.CancellationSignal
import android.os.OperationCanceledException
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PageMarginsTest {
    @Test fun boundsIncludeFaintEdgeInkAndLeaveSourceUnchanged() {
        val bitmap = Bitmap.createBitmap(200, 300, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.WHITE)
            Canvas(bitmap).drawRect(30f, 40f, 170f, 260f, Paint().apply { color = Color.BLACK })
            bitmap.setPixel(16, 40, Color.rgb(254, 254, 254))
            val before = IntArray(200 * 300)
            bitmap.getPixels(before, 0, 200, 0, 0, 200, 300)
            val crop = requireNotNull(detectPageMargins(bitmap, CancellationSignal()))
            assertEquals(RectF(12f / 200, 36f / 300, 174f / 200, 264f / 300), crop)
            val after = IntArray(before.size)
            bitmap.getPixels(after, 0, 200, 0, 0, 200, 300)
            assertArrayEquals("Detection must not crop or modify the source bitmap", before, after)
        } finally { bitmap.recycle() }
    }

    @Test fun blankSparseAndNonPaperImagesKeepTheirFullBounds() {
        val bitmap = Bitmap.createBitmap(200, 300, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.WHITE)
            assertNull(detectPageMargins(bitmap, CancellationSignal()))
            bitmap.setPixel(100, 150, Color.BLACK)
            assertNull("One isolated mark does not establish a page region", detectPageMargins(bitmap, CancellationSignal()))
            bitmap.eraseColor(Color.rgb(40, 80, 100))
            assertNull(detectPageMargins(bitmap, CancellationSignal()))
            bitmap.eraseColor(Color.WHITE)
            Canvas(bitmap).drawRect(30f, 40f, 170f, 260f, Paint().apply { color = Color.BLACK })
            bitmap.setPixel(199, 0, Color.LTGRAY)
            assertNull("Different corners cannot establish uniform paper", detectPageMargins(bitmap, CancellationSignal()))
        } finally { bitmap.recycle() }
    }

    @Test fun cancellationIsNotReportedAsAnUncroppedSuccess() {
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        try {
            val signal = CancellationSignal().apply { cancel() }
            try { detectPageMargins(bitmap, signal); fail("Cancelled detection returned a result") }
            catch (_: OperationCanceledException) { }
        } finally { bitmap.recycle() }
    }
}
