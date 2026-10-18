package app.smallthingz.alldoc.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.CancellationSignal
import app.smallthingz.alldoc.core.Document
import app.smallthingz.alldoc.pdf.PageSize
import app.smallthingz.alldoc.theme.RasterRegion
import app.smallthingz.alldoc.theme.ThemeSettings
import java.io.File

/** Internal saved-image previews for document containers, never standalone image admission. */
internal class ImageSession(private val file: File, name: String, mime: String, signal: CancellationSignal, warnings: List<String> = emptyList()) : DocumentSession {
    private var closed = false
    private var aspect: Float? = null
    private var regions: List<RasterRegion>? = null
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
        val ratio = if (bounds.outWidth > 0 && bounds.outHeight > 0) bounds.outHeight.toFloat() / bounds.outWidth else Float.NaN
        ready(signal)
        return (ratio.takeIf { it.isFinite() && it > 0f } ?: super<DocumentSession>.pageAspect(index, signal)).also { aspect = it }
    }
    @Synchronized override fun renderPage(index: Int, width: Int, settings: ThemeSettings, signal: CancellationSignal): Bitmap {
        ready(signal); require(index == 0 && width in 1..4096) { "Invalid page or preview width" }
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, sourceInfo, _ ->
            signal.throwIfCanceled()
            val w = sourceInfo.size.width; val h = sourceInfo.size.height
            require(w > 0 && h > 0 && w.toLong() * h <= 100_000_000) { "Image dimensions exceed preview limits" }
            val target = PageSize.fit(w, h, minOf(width, w))
            decoder.setTargetSize(target.width, target.height)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        aspect = bitmap.height.toFloat() / bitmap.width
        regions = if (bitmap.hasAlpha()) null else listOf(RasterRegion(0f, 0f, 1f, 1f))
        return themed(bitmap, settings, signal, regions)
    }
    private fun ready(signal: CancellationSignal) { signal.throwIfCanceled(); check(!closed) { "Document is closed" } }
    @Synchronized override fun close() { closed = true; regions = null }
}
