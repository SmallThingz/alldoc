package app.smallthingz.alldoc.ui

import android.graphics.Bitmap
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import app.smallthingz.alldoc.engine.ContinuousPageLimitException
import app.smallthingz.alldoc.engine.DocumentSession
import app.smallthingz.alldoc.ipc.DescriptorOutput
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import kotlin.math.min

/** Holds a borrowed session until finishing and all canceled work has drained. */
internal class DocumentPrintAdapter(
    private val session: DocumentSession,
    private val title: String,
    private val onRelease: () -> Unit,
) : PrintDocumentAdapter() {
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val jobs = mutableSetOf<Work>()
    private val worker = ThreadPoolExecutor(1, 1, 10, TimeUnit.SECONDS, ArrayBlockingQueue(1)) {
        Thread(it, "AllDoc-print").apply { isDaemon = true }
    }.apply { allowCoreThreadTimeOut(true) }
    private var finished = false
    private var released = false
    private var layout: Layout? = null

    override fun onLayout(oldAttributes: PrintAttributes?, newAttributes: PrintAttributes,
        cancellationSignal: CancellationSignal, callback: LayoutResultCallback, extras: Bundle?) {
        submit(cancellationSignal, { callback.onLayoutCancelled() }, { callback.onLayoutFailed("Couldn’t prepare printing.") }) {
            val count = session.pageCount(cancellationSignal)
            require(count in 1..100_000) { "Invalid print page count" }
            val next = Layout(newAttributes, count)
            next.paper()
            cancellationSignal.throwIfCanceled()
            synchronized(lock) { layout = next }
            val info = PrintDocumentInfo.Builder(title.ifBlank { "Document" }).setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .setPageCount(count).build()
            ({ callback.onLayoutFinished(info, oldAttributes != newAttributes) })
        }
    }

    override fun onWrite(pages: Array<out PageRange>, destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal, callback: WriteResultCallback) {
        val current = synchronized(lock) { layout }
        submit(cancellationSignal, { callback.onWriteCancelled() }, { failure -> callback.onWriteFailed((failure as? ContinuousPageLimitException)?.message ?: "Couldn’t print this document.") },
            cleanup = { destination.close() }) {
            val value = requireNotNull(current) { "Print layout is missing" }
            val selected = selectPages(pages, value.count, cancellationSignal)
            require(selected.isNotEmpty()) { "No pages selected" }
            val output = DescriptorOutput(destination, cancellationSignal, limit = 1024L * 1024 * 1024, timeoutMillis = 30 * 60_000L)
            BufferedOutputStream(output, 64 * 1024).use { buffered ->
                writePdf(buffered, value.paper(), selected, value.attributes.colorMode == PrintAttributes.COLOR_MODE_MONOCHROME, cancellationSignal)
            }
            cancellationSignal.throwIfCanceled()
            ({ callback.onWriteFinished(pageRanges(selected)) })
        }
    }

    override fun onFinish() = abort()

    fun abort() {
        synchronized(lock) {
            if (finished) return
            finished = true
            jobs.toList().forEach { job ->
                job.signal.cancel()
                if (worker.remove(job)) job.complete(job.cancelled)
            }
            worker.shutdown()
            releaseIfDone()
        }
    }

    private fun submit(signal: CancellationSignal, cancelled: () -> Unit, failed: (Exception?) -> Unit,
        cleanup: () -> Unit = {}, action: () -> (() -> Unit)) {
        val job = Work(signal, cancelled, failed, cleanup, action)
        synchronized(lock) {
            if (finished) { job.complete(cancelled); return }
            jobs.toList().forEach { old ->
                old.signal.cancel()
                if (worker.remove(old)) old.complete(old.cancelled)
            }
            jobs.add(job)
            try { worker.execute(job) } catch (_: java.util.concurrent.RejectedExecutionException) { job.complete { failed(null) } }
        }
    }

    private inner class Work(val signal: CancellationSignal, val cancelled: () -> Unit, val failed: (Exception?) -> Unit,
        val cleanup: () -> Unit, val action: () -> (() -> Unit)) : Runnable {
        private val done = AtomicBoolean()
        override fun run() {
            val result = try { signal.throwIfCanceled(); action() } catch (failure: Exception) {
                if (signal.isCanceled) cancelled else ({ failed(failure) })
            } catch (_: OutOfMemoryError) { { failed(null) } }
            complete(result)
        }
        fun complete(result: () -> Unit) {
            if (!done.compareAndSet(false, true)) return
            val delivery = try { cleanup(); result } catch (_: Exception) { { failed(null) } }
            main.post {
                try {
                    if (signal.isCanceled || synchronized(lock) { finished }) cancelled() else delivery()
                } finally { synchronized(lock) { jobs.remove(this); releaseIfDone() } }
            }
        }
    }

    private fun releaseIfDone() {
        if (finished && jobs.isEmpty() && !released) { released = true; main.post { onRelease() } }
    }

    private data class Layout(val attributes: PrintAttributes, val count: Int) {
        fun paper(): Paper {
            val media = requireNotNull(attributes.mediaSize)
            val margin = attributes.minMargins ?: PrintAttributes.Margins.NO_MARGINS
            fun points(mils: Int) = mils.toDouble() * 72 / 1000
            return Paper(points(media.widthMils), points(media.heightMils), points(margin.leftMils), points(margin.topMils),
                points(margin.rightMils), points(margin.bottomMils)).also {
                require(it.width in 1.0..20_000.0 && it.height in 1.0..20_000.0 && it.left >= 0 && it.top >= 0 &&
                    it.right >= 0 && it.bottom >= 0 && it.width > it.left + it.right && it.height > it.top + it.bottom)
            }
        }
    }
    private data class Paper(val width: Double, val height: Double, val left: Double, val top: Double, val right: Double, val bottom: Double)

    /** PDF image objects stream row by row; neither page bitmaps nor encoded pages accumulate. */
    private fun writePdf(destination: OutputStream, paper: Paper, pages: IntArray, monochrome: Boolean, signal: CancellationSignal) {
        val output = CountingStream(destination)
        val offsets = LongArray(3 + pages.size * 4)
        fun text(value: String) { output.write(value.toByteArray(Charsets.US_ASCII)) }
        fun start(id: Int) { signal.throwIfCanceled(); offsets[id] = output.count; text("$id 0 obj\n") }
        fun number(value: Double) = String.format(Locale.ROOT, "%.3f", value)
        text("%PDF-1.4\n")
        output.write(byteArrayOf(37, -30, -29, -49, -45, 10))
        start(1); text("<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        start(2); text("<< /Type /Pages /Count ${pages.size} /Kids [")
        pages.indices.forEach { text("${3 + it * 4} 0 R ") }
        text("] >>\nendobj\n")
        pages.forEachIndexed { position, index ->
            signal.throwIfCanceled()
            val bitmap = session.renderPrintPage(index, 1600, signal)
            try {
                require(bitmap.width in 1..4096 && bitmap.height > 0 && bitmap.width.toLong() * bitmap.height <= 16_000_000)
                val id = 3 + position * 4
                val scale = min((paper.width - paper.left - paper.right) / bitmap.width, (paper.height - paper.top - paper.bottom) / bitmap.height)
                val width = bitmap.width * scale
                val height = bitmap.height * scale
                val x = paper.left + (paper.width - paper.left - paper.right - width) / 2
                val y = paper.bottom + (paper.height - paper.top - paper.bottom - height) / 2
                val command = "q ${number(width)} 0 0 ${number(height)} ${number(x)} ${number(y)} cm /Im0 Do Q\n"
                start(id)
                text("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${number(paper.width)} ${number(paper.height)}] /Resources << /XObject << /Im0 ${id + 1} 0 R >> >> /Contents ${id + 3} 0 R >>\nendobj\n")
                start(id + 1)
                text("<< /Type /XObject /Subtype /Image /Width ${bitmap.width} /Height ${bitmap.height} /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /FlateDecode /Length ${id + 2} 0 R >>\nstream\n")
                val begin = output.count
                writePixels(bitmap, output, monochrome, signal)
                val length = output.count - begin
                text("\nendstream\nendobj\n")
                start(id + 2); text("$length\nendobj\n")
                start(id + 3); text("<< /Length ${command.length} >>\nstream\n${command}endstream\nendobj\n")
            } finally { bitmap.recycle() }
        }
        val xref = output.count
        text("xref\n0 ${offsets.size}\n0000000000 65535 f \n")
        for (id in 1 until offsets.size) {
            signal.throwIfCanceled()
            check(offsets[id] in 1..9_999_999_999L)
            text(String.format(Locale.ROOT, "%010d 00000 n \n", offsets[id]))
        }
        text("trailer\n<< /Size ${offsets.size} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        signal.throwIfCanceled()
    }

    private fun writePixels(bitmap: Bitmap, output: OutputStream, monochrome: Boolean, signal: CancellationSignal) {
        val pixels = IntArray(bitmap.width)
        val rgb = ByteArray(bitmap.width * 3)
        val deflater = Deflater(Deflater.BEST_SPEED)
        try {
            val compressed = DeflaterOutputStream(output, deflater, 64 * 1024)
            for (y in 0 until bitmap.height) {
                signal.throwIfCanceled()
                bitmap.getPixels(pixels, 0, bitmap.width, 0, y, bitmap.width, 1)
                for (x in pixels.indices) {
                    val pixel = pixels[x]
                    val alpha = pixel ushr 24
                    val gray = if (monochrome) (54 * (pixel ushr 16 and 255) + 183 * (pixel ushr 8 and 255) + 19 * (pixel and 255)) ushr 8 else 0
                    for (channel in 0..2) {
                        val value = if (monochrome) gray else pixel ushr (16 - channel * 8) and 255
                        rgb[x * 3 + channel] = ((value * alpha + 255 * (255 - alpha) + 127) / 255).toByte()
                    }
                }
                compressed.write(rgb)
            }
            compressed.finish()
        } finally { deflater.end() }
    }

    private class CountingStream(private val output: OutputStream) : OutputStream() {
        var count = 0L; private set
        override fun write(value: Int) { check(count < 9_999_999_999L); output.write(value); count++ }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            check(count + length <= 9_999_999_999L); output.write(bytes, offset, length); count += length
        }
    }

    companion object {
        private fun selectPages(ranges: Array<out PageRange>, count: Int, signal: CancellationSignal): IntArray {
            require(ranges.size <= 100_000) { "Too many print ranges" }
            val selected = BooleanArray(count)
            var end = -1
            signal.throwIfCanceled()
            for (range in ranges.sortedBy { it.start }) {
                signal.throwIfCanceled()
                if (range.start >= count) break
                val start = maxOf(range.start, end + 1)
                val next = min(range.end, count - 1)
                if (start <= next) selected.fill(true, start, next + 1)
                end = maxOf(end, next)
            }
            val pages = IntArray(selected.count { it })
            var index = 0
            selected.forEachIndexed { page, include -> if (include) pages[index++] = page }
            return pages
        }
        private fun pageRanges(pages: IntArray): Array<PageRange> {
            val result = mutableListOf<PageRange>()
            var start = pages[0]
            var end = start
            for (index in 1 until pages.size) {
                val page = pages[index]
                if (page == end + 1) end = page else { result.add(PageRange(start, end)); start = page; end = page }
            }
            result.add(PageRange(start, end))
            return result.toTypedArray()
        }
    }
}
