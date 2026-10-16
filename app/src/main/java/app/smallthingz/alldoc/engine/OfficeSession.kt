package app.smallthingz.alldoc.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import app.smallthingz.alldoc.core.Document
import app.smallthingz.alldoc.pdf.PdfDocument
import app.smallthingz.alldoc.render.DocRenderCore
import app.smallthingz.alldoc.theme.ThemeSettings
import java.io.File
import java.io.IOException

internal class OfficeSession(private val file: File, private val name: String, mime: String, private val document: Document, signal: CancellationSignal, private val renderName: String = name) : DocumentSession {
    private val pages = LinkedHashMap<Int, ByteArray>(3, 0.75f, true)
    private var pdf: PdfDocument? = null
    private var pdfFile: File? = null
    private var closed = false
    override val info: DocumentInfo
    init {
        val page = native(0, signal)
        try {
            if (page.format == DocRenderCore.RenderedPage.PDF) {
                val preview = File.createTempFile("office-", ".pdf", file.parentFile); pdfFile = preview; preview.writeBytes(page.cachedBytes())
                pdf = PdfDocument.open(ParcelFileDescriptor.open(preview, ParcelFileDescriptor.MODE_READ_ONLY), name, null, signal)
            }
            if (page.format == DocRenderCore.RenderedPage.PNG) remember(0, page.cachedBytes())
            info = DocumentInfo(name, mime, document.format, pdf?.pageCount ?: page.pageCount, document.sections.any { it.blocks.any { b -> b.text.isNotBlank() } }, page.fidelity, document.warnings + listOfNotNull(page.note.takeIf { it.isNotBlank() }), supportsMargins = true)
        } catch (failure: Throwable) { pdf?.close(); pdfFile?.delete(); throw failure }
    }
    private fun native(index: Int, signal: CancellationSignal): DocRenderCore.RenderedPage = DocRenderCore.renderFile(file, renderName, DocRenderCore.ViewState(index, 0, file.name), 1400, DocRenderCore.Check { signal.isCanceled })
    @Synchronized override fun pageAspect(index: Int, signal: CancellationSignal): Float {
        ready(signal); require(index in 0 until info.pageCount) { "Invalid page" }
        pdf?.let { return it.pageAspect(index, signal) }
        val data = pages[index] ?: return super<DocumentSession>.pageAspect(index, signal)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        signal.throwIfCanceled()
        return if (bounds.outWidth > 0 && bounds.outHeight > 0) bounds.outHeight.toFloat() / bounds.outWidth
            else super<DocumentSession>.pageAspect(index, signal)
    }
    @Synchronized override fun text(signal: CancellationSignal): String { ready(signal); return document.toPlainText() }
    @Synchronized override fun json(signal: CancellationSignal): String { ready(signal); return document.toJson() }
    @Synchronized override fun renderPage(index: Int, width: Int, settings: ThemeSettings, signal: CancellationSignal): Bitmap {
        ready(signal); require(index in 0 until info.pageCount && width in 1..4096) { "Invalid page or preview width" }
        pdf?.let { return themed(it.renderPage(index, width, signal), settings, signal) }
        val data = pages[index] ?: native(index, signal).let { page ->
            if (page.format != DocRenderCore.RenderedPage.PNG) throw IOException("Unexpected visual page encoding")
            page.cachedBytes().also { remember(index, it) }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        val actual = width.coerceAtMost(1400)
        val options = BitmapFactory.Options()
        while (bounds.outWidth / (options.inSampleSize.coerceAtLeast(1) * 2) >= actual) options.inSampleSize = options.inSampleSize.coerceAtLeast(1) * 2
        val decoded = BitmapFactory.decodeByteArray(data, 0, data.size, options) ?: throw IOException("Cannot decode the rendered page")
        val bitmap = if (decoded.width > actual) {
            try { Bitmap.createScaledBitmap(decoded, actual, maxOf(1, (decoded.height.toLong() * actual / decoded.width).toInt()), true) }
            finally { decoded.recycle() }
        } else decoded
        return themed(bitmap, settings, signal)
    }
    private fun remember(index: Int, data: ByteArray) {
        if (data.size > 4 * 1024 * 1024) return
        pages[index] = data
        while (pages.size > 3 || pages.values.sumOf { it.size } > 4 * 1024 * 1024) pages.remove(pages.keys.first())
    }
    private fun ready(signal: CancellationSignal) { signal.throwIfCanceled(); check(!closed) { "Document is closed" } }
    @Synchronized override fun close() { if (!closed) { closed = true; pages.clear(); try { pdf?.close() } finally { pdfFile?.delete() } } }
}
