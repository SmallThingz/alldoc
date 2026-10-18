package app.smallthingz.alldoc.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import android.os.CancellationSignal
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import app.smallthingz.alldoc.core.Document
import app.smallthingz.alldoc.pdf.PageSize
import app.smallthingz.alldoc.theme.PaperTexture
import app.smallthingz.alldoc.theme.DocumentColors
import app.smallthingz.alldoc.theme.RasterRegion
import app.smallthingz.alldoc.theme.ReadingMode
import app.smallthingz.alldoc.theme.ThemeSettings

internal class TextSession(name: String, mime: String, private val document: Document, signal: CancellationSignal) : DocumentSession {
    init { signal.throwIfCanceled() }
    private val content = document.toPlainText()
    private val sheets = document.takeIf { it.format in setOf("XLS", "XLSX", "ODS") && it.sections.any { section -> section.kind == "sheet" } }
        ?.let { SemanticSpreadsheet(it, signal) }
    @Synchronized override fun workbook(signal: CancellationSignal): WorkbookInfo? { ready(signal); return sheets?.info }
    @Synchronized override fun sheetGeometry(index: Int, signal: CancellationSignal): SheetGeometry? { ready(signal); return sheets?.geometry(index) }
    @Synchronized override fun renderSheet(viewport: SheetViewport, settings: ThemeSettings, signal: CancellationSignal): Bitmap {
        ready(signal); return requireNotNull(sheets).render(viewport, settings, signal)
    }
    @Synchronized override fun sheetRasterRegions(viewport: SheetViewport, signal: CancellationSignal): List<RasterRegion> {
        ready(signal); viewport.checked(); requireNotNull(sheets).geometry(viewport.sheet)
        return emptyList()
    }
    private class Pagination(val paint: TextPaint, val layout: StaticLayout, val pages: List<Int>)
    private var pagination: Pagination? = null
    private var closed = false
    override val info = DocumentInfo(name, mime, document.format, 0, content.isNotEmpty(), document.fidelity,
        (document.warnings + if (document.truncated) listOf("Document excerpt: remaining content omitted.") else emptyList()).distinct(),
        reflowable = true, supportsSheets = sheets != null)
    @Synchronized override fun pageCount(signal: CancellationSignal): Int = pages(signal).pages.size - 1

    @Synchronized override fun rasterRegions(index: Int, signal: CancellationSignal): List<RasterRegion> {
        ready(signal); require(index in 0 until pageCount(signal)) { "Invalid page" }
        return emptyList()
    }
    @Synchronized override fun pageAspect(index: Int, signal: CancellationSignal): Float {
        ready(signal)
        require(index in 0 until pageCount(signal)) { "Invalid page" }
        return PAGE_HEIGHT.toFloat() / PAGE_WIDTH
    }

    // The parser bounds semantic content; one cached Unicode layout supplies the same
    // line boundaries to vertical pages, horizontal pages, printing and image export.
    private fun pages(signal: CancellationSignal): Pagination {
        ready(signal)
        pagination?.let { return it }
        val paint = TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 20f; typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        val layout = StaticLayout.Builder.obtain(content, 0, content.length, paint, PAGE_WIDTH - 2 * MARGIN)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).setLineSpacing(4f, 1f).build()
        signal.throwIfCanceled()
        val offsets = buildList {
            add(0)
            for (line in 1 until layout.lineCount) {
                if (line % 128 == 0) signal.throwIfCanceled()
                if (layout.getLineBottom(line) - last() > BODY_HEIGHT) add(layout.getLineTop(line))
            }
            add(layout.height)
        }
        ready(signal)
        return Pagination(paint, layout, offsets).also { pagination = it }
    }
    @Synchronized override fun text(signal: CancellationSignal): String { ready(signal); return content }
    @Synchronized override fun json(signal: CancellationSignal): String { ready(signal); return document.toJson() }
    @Synchronized override fun renderPage(index: Int, width: Int, settings: ThemeSettings, signal: CancellationSignal): Bitmap {
        ready(signal)
        require(width in 1..4096) { "Invalid preview width" }
        val pagination = pages(signal)
        val paint = pagination.paint
        val layout = pagination.layout
        val pages = pagination.pages
        require(index in 0 until pages.size - 1) { "Invalid page" }
        val size = PageSize.fit(PAGE_WIDTH, PAGE_HEIGHT, width)
        val actual = size.width
        val bitmap = Bitmap.createBitmap(actual, size.height, Bitmap.Config.ARGB_8888)
        val invert = settings.mode == ReadingMode.INVERT
        try {
            val colors = DocumentColors.palette(if (invert) settings.copy(mode = ReadingMode.ORIGINAL) else settings)
            bitmap.eraseColor(colors.background); paint.color = colors.foreground
            val canvas = Canvas(bitmap)
            PaperTexture.background(canvas, bitmap.width, bitmap.height, settings)
            canvas.scale(actual.toFloat() / PAGE_WIDTH, actual.toFloat() / PAGE_WIDTH)
            canvas.clipRect(MARGIN.toFloat(), MARGIN.toFloat(), (PAGE_WIDTH - MARGIN).toFloat(), (MARGIN + minOf(BODY_HEIGHT, pages[index + 1] - pages[index])).toFloat())
            canvas.translate(MARGIN.toFloat(), (MARGIN - pages[index]).toFloat()); layout.draw(canvas)
            ready(signal)
        } catch (failure: Throwable) { bitmap.recycle(); throw failure }
        return if (invert) themed(bitmap, settings, signal) else bitmap
    }
    private fun ready(signal: CancellationSignal) { signal.throwIfCanceled(); check(!closed) { "Document is closed" } }
    @Synchronized override fun close() { closed = true; pagination = null }
    companion object { private const val PAGE_WIDTH = 640; private const val PAGE_HEIGHT = 900; private const val MARGIN = 32; private const val BODY_HEIGHT = PAGE_HEIGHT - 2 * MARGIN }
}
