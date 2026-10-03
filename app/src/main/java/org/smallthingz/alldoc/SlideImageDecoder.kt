package org.smallthingz.alldoc

import android.graphics.Bitmap
import android.graphics.BitmapFactory

internal const val MAX_SLIDE_IMAGE_BYTES = 2 * 1024 * 1024
internal const val MAX_IMAGE_DECODE_PIXELS = 2_000_000L
internal const val MAX_SLIDE_DECODE_PIXELS = 4_000_000L
internal const val MAX_IMAGE_SOURCE_PIXELS = 16_000_000L

/**
 * Only inert raster bytes from validated package members reach this decoder. Call off the UI
 * thread.
 */
internal fun decodeSlideRaster(bytes: ByteArray, pixelBudget: Long): Bitmap {
    require(bytes.isNotEmpty() && bytes.size <= MAX_SLIDE_IMAGE_BYTES) {
        "Image exceeds the preview byte limit"
    }
    require(pixelBudget in 1..MAX_IMAGE_DECODE_PIXELS) { "Image preview budget is unavailable" }
    val png =
        bytes.size >= 8 &&
            bytes.take(8).map { it.toInt() and 255 } == listOf(137, 80, 78, 71, 13, 10, 26, 10)
    val jpeg =
        bytes.size >= 3 &&
            bytes[0] == 0xff.toByte() &&
            bytes[1] == 0xd8.toByte() &&
            bytes[2] == 0xff.toByte()
    require(png || jpeg) { "Only embedded PNG and JPEG previews are supported" }
    val bounds =
        BitmapFactory.Options().apply {
            inJustDecodeBounds = true
            inScaled = false
        }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    require(bounds.outMimeType in setOf("image/png", "image/jpeg")) {
        "Image data does not match a supported raster format"
    }
    require(
        bounds.outWidth in 1..8192 &&
            bounds.outHeight in 1..8192 &&
            bounds.outWidth.toLong() * bounds.outHeight <= MAX_IMAGE_SOURCE_PIXELS
    ) {
        "Image dimensions exceed the safe preview limit"
    }
    val sample = slideRasterSample(bounds.outWidth, bounds.outHeight, pixelBudget)
    val options =
        BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
            inMutable = false
        }
    val bitmap =
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: error("This image could not be decoded")
    if (
        bitmap.width.toLong() * bitmap.height > pixelBudget ||
            bitmap.allocationByteCount.toLong() > pixelBudget * 4
    ) {
        bitmap.recycle()
        error("Image decoder exceeded the safe preview limit")
    }
    return bitmap
}

internal fun slideRasterSample(width: Int, height: Int, pixelBudget: Long): Int {
    require(width in 1..8192 && height in 1..8192 && pixelBudget > 0)
    var sample = 1
    while (
        ((width.toLong() + sample - 1) / sample) * ((height.toLong() + sample - 1) / sample) >
            pixelBudget
    ) sample *= 2
    return sample
}
