package app.smallthingz.alldoc.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.media.ExifInterface
import android.os.CancellationSignal
import app.smallthingz.alldoc.core.Document
import app.smallthingz.alldoc.pdf.PageSize
import app.smallthingz.alldoc.theme.RasterRegion
import app.smallthingz.alldoc.theme.BitmapTheme
import app.smallthingz.alldoc.theme.ReadingMode
import app.smallthingz.alldoc.theme.RegionThemeContext
import app.smallthingz.alldoc.theme.ThemeSettings
import java.io.File
import java.io.IOException
import kotlin.math.ceil
import kotlin.math.floor

/** Internal saved-image previews for document containers, never standalone image admission. */
internal class ImageSession(private val file: File, name: String, mime: String, signal: CancellationSignal, warnings: List<String> = emptyList()) : DocumentSession {
    private var closed = false
    private var aspect: Float? = null
    private var regions: List<RasterRegion>? = null
    private var regionDecoder: BitmapRegionDecoder? = null
    private var themeContext: RegionThemeContext? = null
    private val orientation by lazy {
        try { ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            .takeIf { it in 1..8 } ?: ExifInterface.ORIENTATION_NORMAL }
        catch (_: IOException) { ExifInterface.ORIENTATION_NORMAL }
    }
    private val source = Document("image", "METADATA_ONLY", warnings = warnings + "Saved image preview; OCR is not included.")
    override val info = DocumentInfo(name, mime, "image", 1, false, "IMAGE", source.warnings)
    override val textInfo get() = info.copy(fidelity = source.fidelity)
    init { signal.throwIfCanceled() }
    @Synchronized override fun rasterRegions(index: Int, signal: CancellationSignal): List<RasterRegion>? {
        ready(signal); require(index == 0) { "Invalid page" }
        return regions
    }
    @Synchronized override fun text(signal: CancellationSignal): String {
        ready(signal)
        throw java.io.IOException("Saved image preview has no extracted text; OCR is not included.")
    }
    @Synchronized override fun json(signal: CancellationSignal): String { ready(signal); return source.toJson() }
    @Synchronized override fun pageAspect(index: Int, signal: CancellationSignal): Float {
        ready(signal); require(index == 0) { "Invalid page" }
        aspect?.let { return it }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val ratio = if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            if (orientation >= 5) bounds.outWidth.toFloat() / bounds.outHeight else bounds.outHeight.toFloat() / bounds.outWidth
        } else Float.NaN
        ready(signal)
        return (ratio.takeIf { it.isFinite() && it > 0f } ?: super<DocumentSession>.pageAspect(index, signal)).also { aspect = it }
    }
    @Synchronized override fun renderPage(index: Int, width: Int, settings: ThemeSettings, signal: CancellationSignal): Bitmap {
        ready(signal); require(index == 0 && width in 1..4096) { "Invalid page or preview width" }
        return themePage(index, decodePage(width, signal), settings, signal)
    }
    private fun decodePage(width: Int, signal: CancellationSignal): Bitmap {
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, sourceInfo, _ ->
            signal.throwIfCanceled()
            val w = sourceInfo.size.width; val h = sourceInfo.size.height
            require(w > 0 && h > 0 && w.toLong() * h <= 100_000_000) { "Image dimensions exceed preview limits" }
            aspect = h.toFloat() / w
            val target = PageSize.fit(w, h, minOf(width, w))
            decoder.setTargetSize(target.width, target.height)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        regions = if (bitmap.hasAlpha()) null else listOf(RasterRegion(0f, 0f, 1f, 1f))
        return bitmap
    }
    @Synchronized override fun themePage(index: Int, bitmap: Bitmap, settings: ThemeSettings, signal: CancellationSignal): Bitmap {
        try {
            ready(signal); require(index == 0) { "Invalid page" }
            return themed(bitmap, settings, signal, regions, contextFor(settings, signal, bitmap))
        } catch (failure: Throwable) { if (!bitmap.isRecycled) bitmap.recycle(); throw failure }
    }
    private fun contextFor(settings: ThemeSettings, signal: CancellationSignal, source: Bitmap? = null): RegionThemeContext? {
        if (settings.mode == ReadingMode.ORIGINAL || settings.mode == ReadingMode.INVERT) return null
        themeContext?.takeIf { it.settings == settings }?.let { return it }
        val prepared = if (source != null) BitmapTheme.prepareContext(source, settings, signal, regions) else {
            val probe = decodePage(RegionThemeContext.probeWidth(pageAspect(0, signal)), signal)
            try { BitmapTheme.prepareContext(probe, settings, signal, regions) }
            finally { probe.recycle() }
        }
        ready(signal)
        themeContext = prepared
        return prepared
    }

    @Suppress("DEPRECATION")
    @Synchronized override fun renderRegion(index: Int, region: PageRegion, settings: ThemeSettings, signal: CancellationSignal): Bitmap? =
        renderRegionWithContext(index, region, settings, signal, null)
    @Synchronized override fun pageThemeContext(index: Int, settings: ThemeSettings, signal: CancellationSignal): RegionThemeContext? {
        ready(signal); require(index == 0) { "Invalid page" }
        return themeContext?.takeIf { it.settings == settings }
    }
    @Suppress("DEPRECATION")
    @Synchronized override fun renderRegionWithContext(index: Int, region: PageRegion, settings: ThemeSettings, signal: CancellationSignal,
        context: RegionThemeContext?): Bitmap? {
        ready(signal); require(index == 0) { "Invalid page" }
        val applied = context ?: contextFor(settings, signal)
        val decoder = regionDecoder ?: try {
            BitmapRegionDecoder.newInstance(file.path, false)?.also { regionDecoder = it }
        } catch (_: IOException) { null } ?: return null
        val width = decoder.width; val height = decoder.height
        require(width > 0 && height > 0 && width.toLong() * height <= 100_000_000) { "Image dimensions exceed preview limits" }
        val orientedWidth = if (orientation >= 5) height else width
        val orientedHeight = if (orientation >= 5) width else height
        val view = RectF(region.left * orientedWidth, region.top * orientedHeight,
            region.right * orientedWidth, region.bottom * orientedHeight)
        val transform = imageOrientation(width, height, orientation)
        val inverse = Matrix()
        check(transform.invert(inverse))
        val raw = RectF(view).also { inverse.mapRect(it) }
        val left = floor(raw.left).toInt().coerceIn(0, width - 1)
        val top = floor(raw.top).toInt().coerceIn(0, height - 1)
        val crop = Rect(left, top, ceil(raw.right).toInt().coerceIn(left + 1, width),
            ceil(raw.bottom).toInt().coerceIn(top + 1, height))
        val scaleX = region.width / view.width()
        val scaleY = region.height / view.height()
        require(scaleX.isFinite() && scaleY.isFinite()) { "Preview region exceeds its coordinate limit" }
        var sample = 1
        val desiredSample = 1f / maxOf(scaleX, scaleY)
        while (sample < (1 shl 29) && sample * 2 <= desiredSample) sample *= 2
        fun decodedEdge(value: Int) = (value.toLong() + sample - 1) / sample
        while (decodedEdge(crop.width()) > PageRegion.MAX_EDGE || decodedEdge(crop.height()) > PageRegion.MAX_EDGE ||
            decodedEdge(crop.width()) * decodedEdge(crop.height()) > PageRegion.MAX_PIXELS) sample *= 2
        ready(signal)
        val decoded = decoder.decodeRegion(crop, BitmapFactory.Options().apply {
            inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: throw IOException("Cannot decode the saved preview region")
        var output: Bitmap? = null
        try {
            ready(signal)
            require(decoded.width <= PageRegion.MAX_EDGE && decoded.height <= PageRegion.MAX_EDGE &&
                decoded.width.toLong() * decoded.height <= PageRegion.MAX_PIXELS) { "Decoded preview exceeds viewport limits" }
            val bitmap = Bitmap.createBitmap(region.width, region.height, Bitmap.Config.ARGB_8888)
            output = bitmap
            val matrix = Matrix().apply {
                setRectToRect(RectF(0f, 0f, decoded.width.toFloat(), decoded.height.toFloat()), RectF(crop), Matrix.ScaleToFit.FILL)
                postConcat(transform)
                postTranslate(-view.left, -view.top)
                postScale(scaleX, scaleY)
            }
            Canvas(bitmap).drawBitmap(decoded, matrix, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            decoded.recycle()
            ready(signal)
            output = null
            return themed(bitmap, settings, signal, context = applied, region = region)
        } finally { output?.recycle(); if (!decoded.isRecycled) decoded.recycle() }
    }
    private fun ready(signal: CancellationSignal) { signal.throwIfCanceled(); check(!closed) { "Document is closed" } }
    @Synchronized override fun close() { closed = true; regions = null; themeContext = null; regionDecoder?.recycle(); regionDecoder = null }
}

private fun imageOrientation(width: Int, height: Int, orientation: Int) = Matrix().apply {
    val w = width.toFloat(); val h = height.toFloat()
    val values = when (orientation) {
        2 -> floatArrayOf(-1f, 0f, w, 0f, 1f, 0f)
        3 -> floatArrayOf(-1f, 0f, w, 0f, -1f, h)
        4 -> floatArrayOf(1f, 0f, 0f, 0f, -1f, h)
        5 -> floatArrayOf(0f, 1f, 0f, 1f, 0f, 0f)
        6 -> floatArrayOf(0f, -1f, h, 1f, 0f, 0f)
        7 -> floatArrayOf(0f, -1f, h, -1f, 0f, w)
        8 -> floatArrayOf(0f, 1f, 0f, -1f, 0f, w)
        else -> floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f)
    }
    setValues(values + floatArrayOf(0f, 0f, 1f))
}
