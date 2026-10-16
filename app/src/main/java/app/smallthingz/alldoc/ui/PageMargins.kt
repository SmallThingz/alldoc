package app.smallthingz.alldoc.ui

import android.graphics.Bitmap
import android.graphics.RectF
import android.os.CancellationSignal
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Worker-only paper detection. Null keeps the complete page; the bitmap is never changed. */
internal fun detectPageMargins(bitmap: Bitmap, signal: CancellationSignal): RectF? {
    signal.throwIfCanceled()
    if (bitmap.isRecycled || bitmap.config == Bitmap.Config.HARDWARE) return null
    val width = bitmap.width
    val height = bitmap.height
    if (width < 32 || height < 32 || width > 4096 || height > 4096) return null
    val corners = intArrayOf(bitmap.getPixel(0, 0), bitmap.getPixel(width - 1, 0),
        bitmap.getPixel(0, height - 1), bitmap.getPixel(width - 1, height - 1))
    val paper = corners[0]
    fun different(color: Int, tolerance: Int): Boolean = color ushr 24 != 255 ||
        abs((color ushr 16 and 255) - (paper ushr 16 and 255)) > tolerance ||
        abs((color ushr 8 and 255) - (paper ushr 8 and 255)) > tolerance ||
        abs((color and 255) - (paper and 255)) > tolerance
    if (corners.any { different(it, 3) || minOf(it ushr 16 and 255, it ushr 8 and 255, it and 255) < 224 }) return null
    val row = IntArray(width)
    var left = width
    var top = height
    var right = -1
    var bottom = -1
    for (y in 0 until height) {
        if (y % 32 == 0) signal.throwIfCanceled()
        bitmap.getPixels(row, 0, width, 0, y, width, 1)
        for (x in row.indices) if (row[x] != paper) {
            left = min(left, x); right = max(right, x)
            top = min(top, y); bottom = y
        }
    }
    signal.throwIfCanceled()
    if (right < left) return null
    val padding = max(4, min(width, height) / 100)
    left = max(0, left - padding); top = max(0, top - padding)
    right = min(width, right + padding + 1); bottom = min(height, bottom + padding + 1)
    // Sparse marks and decorative borders are not reliable page-content bounds.
    if (left > width / 4 || top > height / 4 || width - right > width / 4 || height - bottom > height / 4 ||
        left + top + width - right + height - bottom < padding * 2) return null
    return RectF(left.toFloat() / width, top.toFloat() / height, right.toFloat() / width, bottom.toFloat() / height)
}
