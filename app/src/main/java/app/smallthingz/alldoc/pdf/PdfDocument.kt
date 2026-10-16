package app.smallthingz.alldoc.pdf

import android.annotation.TargetApi
import android.content.Context
import org.json.JSONObject
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.LoadParams
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.system.Os
import android.util.JsonWriter
import java.io.Closeable
import java.io.IOException
import java.io.StringWriter
import app.smallthingz.alldoc.engine.DocumentOutlineEntry
import app.smallthingz.alldoc.engine.DocumentNote
import app.smallthingz.alldoc.engine.DocumentNotes

class PdfTextUnavailableException : IOException("PDF text extraction module is unavailable. Image pages remain available; OCR is not included.")
class PdfPasswordUnsupportedException : IOException("Password-protected PDFs require Android 15 or newer.")
class PdfPasswordException(cause: SecurityException) : IOException("This PDF needs a password, or the password is incorrect.", cause)

/**
 * Platform PDFium adapter: no bundled native engine. Call on a worker thread.
 * Owns the supplied descriptor even if opening fails. Renderer calls are serialized; close is idempotent.
 * Contents use an independent descriptor and worker so they do not block page rendering.
 * Cancellation is observed between native operations; Android cannot interrupt an ongoing render.
 */
class PdfDocument private constructor(
    private val renderer: PdfRenderer,
    val name: String,
    private val exportDescriptor: ParcelFileDescriptor?,
    private var metadataPassword: String?,
) : Closeable {
    val pageCount: Int = renderer.pageCount
    val textAvailable: Boolean get() = Build.VERSION.SDK_INT >= 35 || NativePdfExports.available()
    private var exportedJson: String? = null
    private var contents: List<DocumentOutlineEntry>? = null
    private var annotationNotes: DocumentNotes? = null
    private val notesLock = Any()
    private var closed = false

    @Synchronized
    fun pageAspect(index: Int, signal: CancellationSignal): Float {
        ready(signal)
        require(index in 0 until pageCount) { "PDF page is out of range" }
        return renderer.openPage(index).use { page ->
            signal.throwIfCanceled()
            require(page.width > 0 && page.height > 0) { "Invalid PDF page dimensions" }
            page.height.toFloat() / page.width
        }
    }

    @Synchronized
    fun renderPage(index: Int, width: Int, signal: CancellationSignal): Bitmap = render(index, width, signal, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

    @Synchronized
    fun renderPrintPage(index: Int, width: Int, signal: CancellationSignal): Bitmap = render(index, width, signal, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)

    private fun render(index: Int, width: Int, signal: CancellationSignal, mode: Int): Bitmap {
        ready(signal)
        require(index in 0 until pageCount) { "PDF page is out of range" }
        require(width in 1..PageSize.MAX_WIDTH) { "Preview width must be 1–${PageSize.MAX_WIDTH}" }
        return renderer.openPage(index).use { page ->
            signal.throwIfCanceled()
            val size = PageSize.fit(page.width, page.height, width)
            val bitmap = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(Color.WHITE)
                val matrix = Matrix().apply {
                    setScale(size.width.toFloat() / page.width, size.height.toFloat() / page.height)
                }
                page.render(bitmap, null, matrix, mode)
                signal.throwIfCanceled()
                bitmap
            } catch (failure: Throwable) {
                bitmap.recycle()
                throw failure
            }
        }
    }

    fun outline(signal: CancellationSignal): List<DocumentOutlineEntry> {
        val source = synchronized(this) {
            ready(signal)
            contents?.let { return it }
            ParcelFileDescriptor.dup(checkNotNull(exportDescriptor).fileDescriptor) to metadataPassword
        }
        val result = source.first.use { descriptor ->
            JSONObject(NativePdfExports.outline(descriptor, source.second, signal))
        }
        check(result.getInt("version") == 1 && result.getString("format") == "pdf-outline")
        val entries = result.getJSONArray("entries")
        require(entries.length() <= 4096) { "PDF contents exceed their entry limit" }
        val parsed = List(entries.length()) { index ->
            signal.throwIfCanceled()
            val item = entries.getJSONObject(index)
            val title = item.getString("title")
            val page = item.getInt("page")
            val level = item.getInt("level")
            require(title.isNotBlank() && title.length <= 512 && page in 0 until pageCount && level in 0..32) { "Invalid PDF contents entry" }
            DocumentOutlineEntry(title, page, level)
        }
        return synchronized(this) {
            ready(signal)
            contents ?: java.util.Collections.unmodifiableList(parsed).also { contents = it }
        }
    }

    fun notes(signal: CancellationSignal): DocumentNotes = synchronized(notesLock) cached@{
        val source = synchronized(this) {
            ready(signal)
            annotationNotes?.let { return@cached it }
            ParcelFileDescriptor.dup(checkNotNull(exportDescriptor).fileDescriptor) to metadataPassword
        }
        val result = source.first.use { descriptor ->
            JSONObject(NativePdfExports.notes(descriptor, source.second, signal))
        }
        check(result.getInt("version") == 1 && result.getString("format") == "pdf-notes")
        val entries = result.getJSONArray("entries")
        require(entries.length() <= 4096) { "PDF notes exceed their entry limit" }
        var length = 0
        val parsed = List(entries.length()) { index ->
            signal.throwIfCanceled()
            val item = entries.getJSONObject(index)
            val text = item.getString("text")
            val author = item.getString("author")
            val kind = item.getString("kind")
            val page = item.getInt("page")
            length += text.length + author.length
            require(text.isNotBlank() && text.length <= 16_384 && author.length <= 512 &&
                kind.length in 1..32 && page in 0 until pageCount && length <= MAX_EXPORT_CHARACTERS) { "Invalid PDF note" }
            DocumentNote(text, kind, author, "Page ${page + 1}", page)
        }
        synchronized(this) {
            ready(signal)
            DocumentNotes(java.util.Collections.unmodifiableList(parsed), result.getBoolean("truncated"))
                .also { annotationNotes = it }
        }
    }

    @Synchronized
    fun text(signal: CancellationSignal): String {
        ready(signal)
        if (!textAvailable) throw PdfTextUnavailableException()
        if (Build.VERSION.SDK_INT < 35) {
            val document = JSONObject(nativeJson(signal))
            val result = StringBuilder()
            val sections = document.getJSONArray("sections")
            for (index in 0 until sections.length()) {
                signal.throwIfCanceled()
                val page = sections.getJSONObject(index)
                result.append("--- ").append(page.getString("title")).append(" ---\n")
                val blocks = page.getJSONArray("blocks")
                for (block in 0 until blocks.length()) result.append(blocks.getJSONObject(block).getString("text")).append('\n')
                result.append('\n')
            }
            if (document.optBoolean("truncated")) result.append("[Export truncated: document exceeds the text export limit.]\n")
            return result.toString()
        }
        val result = StringBuilder()
        val truncated = extract(signal) { index, blocks ->
            if (index > 0) result.append("\n\n")
            result.append("--- Page ").append(index + 1).append(" ---\n")
            blocks.forEach { result.append(it.text).append('\n') }
        }
        if (truncated) result.append("\n[Export truncated: document exceeds the text export limit.]\n")
        signal.throwIfCanceled()
        return result.toString()
    }

    @Synchronized
    fun json(signal: CancellationSignal): String {
        ready(signal)
        if (Build.VERSION.SDK_INT < 35) {
            if (textAvailable) return nativeJson(signal)
            return JSONObject().put("version", 1).put("format", "pdf").put("name", name)
                .put("pageCount", pageCount).put("textAvailable", false).put("fidelity", "metadata")
                .put("sections", org.json.JSONArray()).put("truncated", false)
                .put("warnings", org.json.JSONArray().put("PDF text extraction module unavailable. Image pages remain available.")).toString()
        }
        val output = StringWriter()
        JsonWriter(output).use { json ->
            json.beginObject().name("version").value(1L)
                .name("name").value(name).name("format").value("pdf")
                .name("pageCount").value(pageCount.toLong())
                .name("fidelity").value("extracted-text")
                .name("sections").beginArray()
            val truncated = extract(signal) { index, blocks ->
                json.beginObject().name("kind").value("page").name("index").value(index.toLong())
                    .name("title").value("Page ${index + 1}").name("blocks").beginArray()
                blocks.forEach { block ->
                    json.beginObject().name("kind").value("paragraph").name("text").value(block.text)
                        .name("bounds").beginArray()
                    block.bounds.forEach { bounds ->
                        json.beginArray().value(bounds.left.toDouble()).value(bounds.top.toDouble())
                            .value(bounds.right.toDouble()).value(bounds.bottom.toDouble()).endArray()
                    }
                    json.endArray().endObject()
                }
                json.endArray().endObject()
            }
            json.endArray().name("truncated").value(truncated).name("warnings").beginArray()
                .value("Text follows the PDF's stored reading order; visual order may differ. Scanned pages need OCR, which is not included.")
            if (truncated) json.value("Text export limit reached; remaining content is omitted.")
            json.endArray().endObject()
        }
        signal.throwIfCanceled()
        return output.toString()
    }

    @TargetApi(35)
    private fun extract(signal: CancellationSignal, emit: (Int, List<TextBlock>) -> Unit): Boolean {
        val budget = PdfTextBudget(MAX_EXPORT_CHARACTERS)
        var blockCount = 0
        var boundsCount = 0
        var truncated = false
        for (index in 0 until minOf(pageCount, MAX_EXPORT_PAGES)) {
            signal.throwIfCanceled()
            val blocks = renderer.openPage(index).use { page ->
                page.textContents.mapNotNull { content ->
                    signal.throwIfCanceled()
                    if (budget.remaining == 0 || blockCount >= MAX_EXPORT_BLOCKS) {
                        if (content.text.isNotEmpty()) truncated = true
                        null
                    } else {
                        val value = budget.take(content.text)
                        if (value.length < content.text.length) truncated = true
                        blockCount++
                        val bounds = content.bounds.take((MAX_EXPORT_BOUNDS - boundsCount).coerceAtLeast(0))
                        if (bounds.size < content.bounds.size) truncated = true
                        boundsCount += bounds.size
                        TextBlock(value, bounds)
                    }
                }
            }
            signal.throwIfCanceled()
            emit(index, blocks)
            if (budget.remaining == 0 || blockCount >= MAX_EXPORT_BLOCKS) {
                return truncated || index + 1 < pageCount
            }
        }
        return truncated || pageCount > MAX_EXPORT_PAGES
    }

    private fun nativeJson(signal: CancellationSignal): String {
        signal.throwIfCanceled()
        exportedJson?.let { return it }
        val raw = NativePdfExports.json(checkNotNull(exportDescriptor), signal)
        val document = JSONObject(raw)
        check(document.getInt("version") == 1 && document.getString("format") == "pdf")
        return document.put("name", name).toString().also { exportedJson = it }
    }

    private data class TextBlock(val text: String, val bounds: List<android.graphics.RectF>)

    private fun ready(signal: CancellationSignal) {
        signal.throwIfCanceled()
        check(!closed) { "PDF is closed" }
    }

    @Synchronized
    override fun close() {
        if (!closed) {
            closed = true
            try { renderer.close() } finally {
                try { exportDescriptor?.close() } finally {
                    exportedJson = null
                    contents = null
                    annotationNotes = null
                    metadataPassword = null
                }
            }
        }
    }

    companion object {
        private const val MAX_EXPORT_CHARACTERS = 2_000_000
        private const val MAX_EXPORT_BLOCKS = 20_000
        private const val MAX_EXPORT_BOUNDS = 50_000
        private const val MAX_EXPORT_PAGES = 2_000

        fun configure(context: Context) = NativePdfExports.configure(context)

        /** Takes ownership of fd immediately, including invalid input and cancellation. */
        fun open(
            fd: ParcelFileDescriptor,
            name: String,
            password: String? = null,
            signal: CancellationSignal = CancellationSignal(),
        ): PdfDocument {
            var renderer: PdfRenderer? = null
            var exportDescriptor: ParcelFileDescriptor? = null
            try {
                signal.throwIfCanceled()
                val length = Os.fstat(fd.fileDescriptor).st_size
                require(length in 1..(128L * 1024 * 1024)) { "PDF must be a seekable file no larger than 128 MiB" }
                if (password != null && Build.VERSION.SDK_INT < 35) throw PdfPasswordUnsupportedException()
                exportDescriptor = ParcelFileDescriptor.dup(fd.fileDescriptor)
                renderer = if (Build.VERSION.SDK_INT >= 35 && password != null) {
                    openWithPassword(fd, password)
                } else {
                    PdfRenderer(fd)
                }
                signal.throwIfCanceled()
                return PdfDocument(renderer, name, exportDescriptor, password)
            } catch (failure: Throwable) {
                try {
                    try { if (renderer != null) renderer.close() else fd.close() }
                    finally { exportDescriptor?.close() }
                } catch (closing: Throwable) {
                    failure.addSuppressed(closing)
                }
                if (failure is SecurityException) throw PdfPasswordException(failure)
                throw failure
            }
        }

        @TargetApi(35)
        private fun openWithPassword(fd: ParcelFileDescriptor, password: String): PdfRenderer =
            PdfRenderer(fd, LoadParams.Builder().setPassword(password).build())

    }
}
