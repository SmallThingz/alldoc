package app.smallthingz.alldoc.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import app.smallthingz.alldoc.core.Document
import app.smallthingz.alldoc.pdf.PdfDocument
import app.smallthingz.alldoc.render.DocRenderCore
import app.smallthingz.alldoc.theme.RasterRegion
import app.smallthingz.alldoc.theme.ThemeSettings
import java.io.File
import java.io.IOException

internal class OfficeSession(private val file: File, private val name: String, mime: String, private val document: Document, signal: CancellationSignal, private val renderName: String = name) : DocumentSession {
    private class CachedPage(val side: Int, val page: DocRenderCore.RenderedPage)
    private val pages = LinkedHashMap<Int, CachedPage>(3, 0.75f, true)
    // Tiny metadata cache remains available even when a PNG exceeds the encoded-cache budget.
    private val regions = LinkedHashMap<Int, List<RasterRegion>?>(3, 0.75f, true)
    private var aspect = 1.4142135f
    private var pdf: PdfDocument? = null
    private var pdfFile: File? = null
    private var closed = false
    private var sheets: app.smallthingz.alldoc.render.WorkbookProcess? = null
    private var sheetViewport: SheetViewport? = null
    private var sheetRegions: List<RasterRegion>? = null
    private fun sheets() = sheets ?: DocRenderCore.workbook(file).also { sheets = it }
    @Synchronized override fun workbook(signal: CancellationSignal): WorkbookInfo? {
        ready(signal); return if (info.supportsSheets) sheets().workbook(signal) else null
    }
    @Synchronized override fun sheetGeometry(index: Int, signal: CancellationSignal): SheetGeometry? {
        ready(signal); return if (info.supportsSheets) sheets().sheet(index, signal) else null
    }
    @Synchronized override fun renderSheet(viewport: SheetViewport, settings: ThemeSettings, signal: CancellationSignal): Bitmap {
        ready(signal); check(info.supportsSheets)
        sheetViewport = null; sheetRegions = null
        val image = sheets().render(viewport, signal)
        return themed(image.bitmap, settings, signal, image.regions).also {
            sheetViewport = viewport; sheetRegions = image.regions
        }
    }
    @Synchronized override fun sheetRasterRegions(viewport: SheetViewport, signal: CancellationSignal): List<RasterRegion>? {
        ready(signal)
        return if (viewport == sheetViewport) sheetRegions else null
    }
    @Synchronized override fun pauseSheets() { sheets?.pause() }
    @Volatile override var info = DocumentInfo(name, mime, document.format, 0,
        document.sections.any { it.blocks.any { block -> block.text.isNotBlank() } }, "VISUAL_BEST_EFFORT",
        document.warnings.filterNot { it == "Text and structure preview; page layout, drawings and embedded objects are omitted." },
        supportsMargins = true, supportsSheets = document.format.equals("XLSX", ignoreCase = true))
        private set
    override val textInfo get() = info.copy(fidelity = document.fidelity,
        warnings = document.warnings + if (document.truncated) listOf("Document excerpt: remaining content omitted.") else emptyList())
    init {
        try {
            if (info.supportsSheets) {
                val workbook = sheets().workbook(signal)
                info = info.copy(warnings = (info.warnings + workbook.warnings).distinct())
            } else loadPrintInfo(signal)
        } catch (failure: Throwable) { close(); throw failure }
    }
    @Synchronized override fun printInfo(signal: CancellationSignal): DocumentInfo {
        ready(signal)
        return loadPrintInfo(signal)
    }
    @Synchronized override fun pageCount(signal: CancellationSignal): Int = printInfo(signal).pageCount
    private fun loadPrintInfo(signal: CancellationSignal): DocumentInfo {
        if (info.pageCount > 0) return info
        sheets?.pause()
        val page = native(0, 1400, signal)
        var preview: File? = null
        var opened: PdfDocument? = null
        try {
            if (page.format == DocRenderCore.RenderedPage.PDF) {
                preview = File.createTempFile("office-", ".pdf", file.parentFile)
                preview.writeBytes(page.cachedBytes())
                opened = PdfDocument.open(ParcelFileDescriptor.open(preview, ParcelFileDescriptor.MODE_READ_ONLY), name, null, signal)
            }
            signal.throwIfCanceled()
            val next = info.copy(pageCount = opened?.pageCount ?: page.pageCount, fidelity = page.fidelity,
                warnings = (info.warnings + listOfNotNull(page.note.takeIf { it.isNotBlank() })).distinct())
            check(next.pageCount > 0) { "Print layout has no pages" }
            if (page.format == DocRenderCore.RenderedPage.PNG) remember(0, 1400, page)
            pdf = opened; pdfFile = preview
            aspect = page.height.toFloat() / page.width
            info = next
            return next
        } catch (failure: Throwable) { try { opened?.close() } finally { preview?.delete() }; throw failure }
    }
    private fun native(index: Int, side: Int, signal: CancellationSignal): DocRenderCore.RenderedPage = DocRenderCore.renderFile(file, renderName, DocRenderCore.ViewState(index, 0, file.name), side, DocRenderCore.Check { signal.isCanceled })
    @Synchronized override fun pageAspect(index: Int, signal: CancellationSignal): Float {
        ready(signal); require(index in 0 until loadPrintInfo(signal).pageCount) { "Invalid page" }
        pdf?.let { return it.pageAspect(index, signal) }
        val page = pages[index]?.page ?: return aspect
        return page.height.toFloat() / page.width
    }
    @Synchronized override fun rasterRegions(index: Int, signal: CancellationSignal): List<RasterRegion>? {
        ready(signal); require(index in 0 until loadPrintInfo(signal).pageCount) { "Invalid page" }
        return regions[index]
    }
    @Synchronized override fun text(signal: CancellationSignal): String { ready(signal); return document.toPlainText() }
    @Synchronized override fun json(signal: CancellationSignal): String { ready(signal); return document.toJson() }
    @Synchronized override fun renderPage(index: Int, width: Int, settings: ThemeSettings, signal: CancellationSignal): Bitmap {
        ready(signal); require(width in 1..4096 && index >= 0) { "Invalid page or preview width" }
        require(index < loadPrintInfo(signal).pageCount) { "Invalid page" }
        pdf?.let { return themed(it.renderPage(index, width, signal), settings, signal) }
        val cached = pages[index]
        // Native tiers bound the long edge, while callers request source-pixel width.
        val detail = cached?.page?.let {
            width.toLong() * maxOf(it.width, it.height) > 1400L * it.width
        } ?: (width * maxOf(1f, aspect) > 1400f)
        var side = if (detail) DocRenderCore.SIDE_LIMIT else 1400
        fun render(): DocRenderCore.RenderedPage = native(index, side, signal).also {
            if (it.format != DocRenderCore.RenderedPage.PNG) throw IOException("Unexpected visual page encoding")
            remember(index, side, it)
        }
        var page = cached?.takeIf { it.side >= side }?.page ?: render()
        // A mixed-size document can differ from its first page. Upgrade at most once.
        if (page.width < width && side < DocRenderCore.SIDE_LIMIT) {
            side = DocRenderCore.SIDE_LIMIT
            page = render()
        }
        rememberRegions(index, page)
        val data = page.cachedBytes()
        val actual = width.coerceAtMost(DocRenderCore.SIDE_LIMIT)
        val options = BitmapFactory.Options()
        while (page.width / (options.inSampleSize.coerceAtLeast(1) * 2) >= actual) options.inSampleSize = options.inSampleSize.coerceAtLeast(1) * 2
        val decoded = BitmapFactory.decodeByteArray(data, 0, data.size, options) ?: throw IOException("Cannot decode the rendered page")
        val bitmap = if (decoded.width > actual) {
            try { Bitmap.createScaledBitmap(decoded, actual, maxOf(1, (decoded.height.toLong() * actual / decoded.width).toInt()), true) }
            finally { decoded.recycle() }
        } else decoded
        return themed(bitmap, settings, signal, page.rasterRegions)
    }
    private fun rememberRegions(index: Int, page: DocRenderCore.RenderedPage) {
        regions[index] = page.rasterRegions
        while (regions.size > 3) regions.remove(regions.keys.first())
    }
    private fun remember(index: Int, side: Int, page: DocRenderCore.RenderedPage) {
        rememberRegions(index, page)
        if (page.encodedBytes() > 4 * 1024 * 1024) return
        pages[index] = CachedPage(side, page)
        while (pages.size > 3 || pages.values.sumOf { it.page.encodedBytes() } > 4 * 1024 * 1024) pages.remove(pages.keys.first())
    }
    private fun ready(signal: CancellationSignal) { signal.throwIfCanceled(); check(!closed) { "Document is closed" } }
    @Synchronized override fun close() {
        if (closed) return
        closed = true; pages.clear(); regions.clear(); sheetViewport = null; sheetRegions = null
        val workbook = sheets; sheets = null
        val print = pdf; pdf = null
        val preview = pdfFile; pdfFile = null
        try { workbook?.close() } finally { try { print?.close() } finally { preview?.delete() } }
    }
}
