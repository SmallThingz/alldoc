package app.smallthingz.alldoc.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.CancellationSignal
import android.text.Layout
import android.text.Spannable
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import app.smallthingz.alldoc.core.Document
import app.smallthingz.alldoc.core.MarkdownDocument
import app.smallthingz.alldoc.core.MarkdownKind
import app.smallthingz.alldoc.theme.DocumentColors
import app.smallthingz.alldoc.theme.PaperTexture
import app.smallthingz.alldoc.theme.RasterRegion
import app.smallthingz.alldoc.theme.ReadingMode
import app.smallthingz.alldoc.theme.ThemeSettings
import java.io.IOException
import kotlin.math.ceil

/** One logical document. The Compose reader lays out visible blocks; exports have an explicit pixel ceiling. */
internal class MarkdownSession(name: String, mime: String, document: Document, signal: CancellationSignal) : DocumentSession {
    private val flow = requireNotNull(document.markdown)
    private var closed = false
    private var cached: StaticLayout? = null
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 20f; typeface = Typeface.DEFAULT }
    init { signal.throwIfCanceled() }
    override val info = DocumentInfo(name, mime, "md", 1, flow.plainText.isNotEmpty(), "SEMANTIC_LAYOUT",
        flow.warnings, reflowable = true, continuous = true)

    @Synchronized override fun markdown(signal: CancellationSignal): MarkdownDocument { ready(signal); return flow }
    @Synchronized override fun bodyText(signal: CancellationSignal): String { ready(signal); return flow.plainText }
    @Synchronized override fun text(signal: CancellationSignal): String { ready(signal); return flow.exportText() }
    @Synchronized override fun json(signal: CancellationSignal): String { ready(signal); return flow.toJson() }
    @Synchronized override fun pageCount(signal: CancellationSignal): Int { ready(signal); return 1 }
    @Synchronized override fun rasterRegions(index: Int, signal: CancellationSignal): List<RasterRegion> {
        ready(signal); require(index == 0) { "Invalid Markdown document page" }
        return emptyList()
    }
    @Synchronized override fun pageAspect(index: Int, signal: CancellationSignal): Float {
        ready(signal); require(index == 0) { "Invalid Markdown document page" }
        return (layout(signal).height + 2 * MARGIN).toFloat() / WIDTH
    }
    private fun layout(signal: CancellationSignal): StaticLayout {
        ready(signal)
        cached?.let { return it }
        val styled = SpannableString(flow.plainText.ifEmpty { " " })
        fun span(value: Any, start: Int, end: Int) {
            if (end > start) styled.setSpan(value, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        var leadingEnd = -1
        for ((index, block) in flow.blocks.withIndex()) {
            ready(signal)
            val start = block.start; val end = start + block.content.text.length
            when (block.kind) {
                MarkdownKind.HEADING -> {
                    span(StyleSpan(Typeface.BOLD), start, end)
                    span(RelativeSizeSpan(when (block.level) { 1 -> 1.8f; 2 -> 1.5f; else -> 1.2f }), start, end)
                }
                MarkdownKind.CODE, MarkdownKind.TABLE -> span(TypefaceSpan("monospace"), start, end)
                else -> Unit
            }
            if ((block.kind == MarkdownKind.QUOTE || block.kind == MarkdownKind.LIST_ITEM) && start >= leadingEnd) {
                // Parser chunks share one paragraph: Android sums all leading spans in that paragraph.
                var until = end
                var next = index + 1
                while (next < flow.blocks.size) {
                    ready(signal)
                    val continuation = flow.blocks[next]
                    if (continuation.start != until || continuation.kind != block.kind || continuation.level != block.level ||
                        (block.kind == MarkdownKind.LIST_ITEM && continuation.marker.isNotEmpty())) break
                    until += continuation.content.text.length
                    next++
                }
                leadingEnd = until
                val margin = if (block.kind == MarkdownKind.QUOTE) LeadingMarginSpan.Standard(20)
                    else MarkerSpan(block.marker, block.level, maxOf(28, ceil(paint.measureText(block.markerContext)).toInt() + 10))
                span(margin, start, until)
            }
            for (style in block.content.spans) {
                val from = start + style.start; val to = start + style.end
                if (style.bold || style.italic) span(StyleSpan((if (style.bold) Typeface.BOLD else 0) or
                    (if (style.italic) Typeface.ITALIC else 0)), from, to)
                if (style.strike) span(StrikethroughSpan(), from, to)
                if (style.code) span(TypefaceSpan("monospace"), from, to)
                if (style.link != null) span(UnderlineSpan(), from, to)
            }
        }
        val result = StaticLayout.Builder.obtain(styled, 0, styled.length, paint, WIDTH - 2 * MARGIN)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).setLineSpacing(4f, 1f).build()
        ready(signal)
        cached = result
        return result
    }

    @Synchronized override fun renderCover(index: Int, width: Int, height: Int, signal: CancellationSignal): Bitmap {
        require(width in 1..512 && height in 1..512)
        return render(index, width, ThemeSettings(ReadingMode.ORIGINAL), signal, height)
    }

    @Synchronized override fun renderPage(index: Int, width: Int, settings: ThemeSettings, signal: CancellationSignal): Bitmap =
        render(index, width, settings, signal)

    private fun render(index: Int, width: Int, settings: ThemeSettings, signal: CancellationSignal, coverHeight: Int? = null): Bitmap {
        ready(signal); require(index == 0) { "Markdown has one continuous document page" }
        require(width in 1..4096) { "Invalid preview width" }
        val layout = layout(signal)
        val actualWidth = width.coerceAtMost(1600)
        val fullHeight = ceil((layout.height.toDouble() + 2 * MARGIN) * actualWidth / WIDTH).toLong().coerceAtLeast(1)
        val height = minOf(fullHeight, coverHeight?.toLong() ?: fullHeight)
        if (height > 8192 || actualWidth.toLong() * height > 8_000_000L)
            throw ContinuousPageLimitException()
        val bitmap = Bitmap.createBitmap(actualWidth, height.toInt(), Bitmap.Config.ARGB_8888)
        val invert = settings.mode == ReadingMode.INVERT
        try {
            val palette = DocumentColors.palette(if (invert) settings.copy(mode = ReadingMode.ORIGINAL) else settings)
            bitmap.eraseColor(palette.background); paint.color = palette.foreground
            val canvas = Canvas(bitmap)
            PaperTexture.background(canvas, bitmap.width, bitmap.height, settings)
            val scale = actualWidth.toFloat() / WIDTH
            canvas.scale(scale, scale); canvas.translate(MARGIN.toFloat(), MARGIN.toFloat())
            layout.draw(canvas)
            ready(signal)
        } catch (failure: Throwable) { bitmap.recycle(); throw failure }
        return if (invert) themed(bitmap, settings, signal) else bitmap
    }
    private fun ready(signal: CancellationSignal) { signal.throwIfCanceled(); check(!closed) { "Document is closed" } }
    @Synchronized override fun close() { closed = true; cached = null }
    private class MarkerSpan(private val marker: String, private val depth: Int, private val width: Int) : LeadingMarginSpan {
        override fun getLeadingMargin(first: Boolean) = width + 16 * depth
        override fun drawLeadingMargin(canvas: Canvas, paint: Paint, x: Int, dir: Int, top: Int, baseline: Int,
            bottom: Int, text: CharSequence, start: Int, end: Int, first: Boolean, layout: Layout?) {
            if (first && marker.isNotEmpty() && text is Spanned && text.getSpanStart(this) == start) {
                val edge = (x + dir * depth * 16).toFloat()
                canvas.drawText(marker, if (dir < 0) edge - paint.measureText(marker) else edge, baseline.toFloat(), paint)
            }
        }
    }
    private companion object { const val WIDTH = 640; const val MARGIN = 28 }
}

/** A fixed user-facing limit, distinct from provider and renderer implementation errors. */
internal class ContinuousPageLimitException : IOException(
    "This continuous document is too tall for one image or printed page. Export text or JSON instead."
)
