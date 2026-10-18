package app.smallthingz.alldoc.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.CancellationSignal
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.AllDocLayoutResultCallback
import android.print.AllDocWriteResultCallback
import android.print.PrintDocumentInfo
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.smallthingz.alldoc.core.MarkdownDocument
import app.smallthingz.alldoc.engine.MarkdownSession
import app.smallthingz.alldoc.engine.DocumentInfo
import app.smallthingz.alldoc.engine.DocumentSession
import app.smallthingz.alldoc.theme.ReadingMode
import app.smallthingz.alldoc.theme.ThemeSettings
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DocumentPrintAdapterTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val attributes = PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4)
        .setResolution(PrintAttributes.Resolution("test", "Test", 300, 300))
        .setMinMargins(PrintAttributes.Margins.NO_MARGINS).setColorMode(PrintAttributes.COLOR_MODE_COLOR).build()

    @Test fun selectedPagesProduceReadablePdfWithOriginalColorsAndWhiteAlpha() {
        val rendered = mutableListOf<Int>()
        val recycled = mutableListOf<Bitmap>()
        val session = object : StubSession() {
            override fun renderPage(index: Int, width: Int, settings: ThemeSettings, signal: CancellationSignal): Bitmap {
                assertNotSame(Looper.getMainLooper(), Looper.myLooper())
                assertEquals(ReadingMode.ORIGINAL, settings.mode)
                assertEquals(1600, width)
                rendered.add(index)
                return Bitmap.createBitmap(32, 64, Bitmap.Config.ARGB_8888).also { bitmap ->
                    bitmap.eraseColor(if (index == 1) Color.RED else Color.BLUE)
                    for (y in 0..15) for (x in 0..31) bitmap.setPixel(x, y, Color.TRANSPARENT)
                    recycled.add(bitmap)
                }
            }
        }
        val releases = AtomicInteger()
        val released = CountDownLatch(1)
        val adapter = DocumentPrintAdapter(session, "Test ) /Title (", { releases.incrementAndGet(); released.countDown() })
        val file = File.createTempFile("print-test-", ".pdf", instrumentation.targetContext.cacheDir)
        try {
            layout(adapter)
            val result = CountDownLatch(1)
            val failure = AtomicReference<String?>()
            val ranges = AtomicReference<Array<out PageRange>?>()
            instrumentation.runOnMainSync {
                adapter.onWrite(arrayOf(PageRange(3, 3), PageRange(1, 1), PageRange(1, 1)),
                    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE),
                    CancellationSignal(), object : AllDocWriteResultCallback() {
                        override fun onWriteFinished(pages: Array<out PageRange>) { ranges.set(pages); result.countDown() }
                        override fun onWriteFailed(error: CharSequence?) { failure.set(error?.toString()); result.countDown() }
                        override fun onWriteCancelled() { failure.set("Cancelled"); result.countDown() }
                    })
            }
            assertTrue(result.await(15, TimeUnit.SECONDS))
            assertNull(failure.get())
            assertEquals(listOf(1, 3), rendered)
            assertArrayEquals(arrayOf(PageRange(1, 1), PageRange(3, 3)), ranges.get())
            assertTrue(recycled.all { it.isRecycled })
            PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)).use { pdf ->
                assertEquals(2, pdf.pageCount)
                for (index in 0 until pdf.pageCount) pdf.openPage(index).use { page ->
                    val output = Bitmap.createBitmap(200, 282, Bitmap.Config.ARGB_8888)
                    try {
                        output.eraseColor(Color.WHITE)
                        page.render(output, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        assertEquals(Color.WHITE, output.getPixel(100, 40))
                        assertEquals(if (index == 0) Color.RED else Color.BLUE, output.getPixel(100, 200))
                    } finally { output.recycle() }
                }
            }
        } finally {
            instrumentation.runOnMainSync { adapter.onFinish(); adapter.abort() }
            assertTrue(released.await(5, TimeUnit.SECONDS))
            assertEquals(1, releases.get())
            file.delete()
        }
    }

    @Test fun finishCancelsActivePrintBeforeReleasingItsSession() {
        val entered = CountDownLatch(1)
        val leave = CountDownLatch(1)
        val release = CountDownLatch(1)
        val releases = AtomicInteger()
        val callbacks = AtomicInteger()
        val cancelled = CountDownLatch(1)
        val session = object : StubSession() {
            override fun renderPage(index: Int, width: Int, settings: ThemeSettings, signal: CancellationSignal): Bitmap {
                entered.countDown()
                check(leave.await(10, TimeUnit.SECONDS))
                signal.throwIfCanceled()
                error("Canceled printing kept rendering")
            }
        }
        val adapter = DocumentPrintAdapter(session, "Cancellation", { releases.incrementAndGet(); release.countDown() })
        val file = File.createTempFile("print-cancel-", ".pdf", instrumentation.targetContext.cacheDir)
        try {
            layout(adapter)
            instrumentation.runOnMainSync {
                adapter.onWrite(arrayOf(PageRange.ALL_PAGES),
                    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE),
                    CancellationSignal(), object : AllDocWriteResultCallback() {
                        override fun onWriteCancelled() { callbacks.incrementAndGet(); cancelled.countDown() }
                        override fun onWriteFinished(pages: Array<out PageRange>) { callbacks.addAndGet(100); cancelled.countDown() }
                        override fun onWriteFailed(error: CharSequence?) { callbacks.addAndGet(100); cancelled.countDown() }
                    })
            }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            instrumentation.runOnMainSync { adapter.onFinish(); adapter.abort() }
            assertEquals(1L, release.count)
            leave.countDown()
            assertTrue(cancelled.await(5, TimeUnit.SECONDS))
            assertTrue(release.await(5, TimeUnit.SECONDS))
            assertEquals(1, callbacks.get())
            assertEquals(1, releases.get())
        } finally { leave.countDown(); instrumentation.runOnMainSync { adapter.abort() }; file.delete() }
    }

    @Test fun finishCancelsCompletionAlreadyPostedToTheMainQueue() {
        val entered = CountDownLatch(1)
        val drained = CountDownLatch(1)
        val done = CountDownLatch(1)
        val release = CountDownLatch(1)
        val callbacks = AtomicInteger()
        val session = object : StubSession() {
            override fun renderPage(index: Int, width: Int, settings: ThemeSettings, signal: CancellationSignal): Bitmap {
                entered.countDown()
                return Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            }
        }
        val adapter = DocumentPrintAdapter(session, "Queued completion", { release.countDown() })
        val file = File.createTempFile("print-main-", ".pdf", instrumentation.targetContext.cacheDir)
        try {
            layout(adapter)
            instrumentation.runOnMainSync {
                adapter.onWrite(arrayOf(PageRange(0, 0)),
                    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE),
                    CancellationSignal(), object : AllDocWriteResultCallback() {
                        override fun onWriteCancelled() { callbacks.incrementAndGet(); done.countDown() }
                        override fun onWriteFinished(pages: Array<out PageRange>) { callbacks.addAndGet(100); done.countDown() }
                        override fun onWriteFailed(error: CharSequence?) { callbacks.addAndGet(100); done.countDown() }
                    })
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                // Queue a fence behind the writer while main delivery is deliberately blocked.
                val field = DocumentPrintAdapter::class.java.getDeclaredField("worker").apply { isAccessible = true }
                (field.get(adapter) as ThreadPoolExecutor).execute { drained.countDown() }
                assertTrue(drained.await(5, TimeUnit.SECONDS))
                adapter.abort()
                assertEquals(1L, release.count)
            }
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertTrue(release.await(5, TimeUnit.SECONDS))
            assertEquals(1, callbacks.get())
        } finally { instrumentation.runOnMainSync { adapter.abort() }; file.delete() }
    }

    @Test fun cancellationUnblocksAnUnreadOutputPipe() {
        val released = CountDownLatch(1)
        val callbacks = AtomicInteger()
        val done = CountDownLatch(1)
        val signal = CancellationSignal()
        val session = object : StubSession() {
            override fun renderPage(index: Int, width: Int, settings: ThemeSettings, signal: CancellationSignal): Bitmap {
                val random = java.util.Random(17)
                val pixels = IntArray(512 * 512) { random.nextInt() or 0xff000000.toInt() }
                return Bitmap.createBitmap(pixels, 512, 512, Bitmap.Config.ARGB_8888)
            }
        }
        val adapter = DocumentPrintAdapter(session, "Pipe", { released.countDown() })
        val pipe = ParcelFileDescriptor.createPipe()
        try {
            layout(adapter)
            instrumentation.runOnMainSync {
                adapter.onWrite(arrayOf(PageRange(0, 0)), pipe[1], signal, object : AllDocWriteResultCallback() {
                    override fun onWriteCancelled() { callbacks.incrementAndGet(); done.countDown() }
                    override fun onWriteFinished(pages: Array<out PageRange>) { callbacks.addAndGet(100); done.countDown() }
                    override fun onWriteFailed(error: CharSequence?) { callbacks.addAndGet(100); done.countDown() }
                })
            }
            val poll = StructPollfd().apply { fd = pipe[0].fileDescriptor; events = OsConstants.POLLIN.toShort() }
            assertTrue("Print writer did not reach the pipe", Os.poll(arrayOf(poll), 10_000) > 0)
            assertFalse(done.await(150, TimeUnit.MILLISECONDS))
            signal.cancel()
            assertTrue("Cancellation remained blocked on output", done.await(5, TimeUnit.SECONDS))
            assertEquals(1, callbacks.get())
        } finally {
            signal.cancel()
            instrumentation.runOnMainSync { adapter.abort() }
            assertTrue(released.await(5, TimeUnit.SECONDS))
            pipe[0].close()
            pipe[1].close()
        }
    }

    @Test fun oversizedMarkdownReportsItsPrintLimitWhileOtherFailuresStayPrivate() {
        val flow = MarkdownDocument.parse((1..2000).joinToString("\n\n") {
            "Paragraph $it: continuous Markdown remains available without artificial page breaks."
        })
        val sessions = listOf(
            MarkdownSession("long.md", "text/markdown", flow.toDocument(), CancellationSignal()) to
                "This continuous document is too tall for one image or printed page. Export text or JSON instead.",
            object : StubSession() {
                override fun renderPage(index: Int, width: Int, settings: ThemeSettings, signal: CancellationSignal): Bitmap =
                    throw IOException("Private provider path and renderer implementation details")
                override fun close() { }
            } to "Couldn’t print this document.",
        )
        for ((session, expected) in sessions) session.use {
            val releases = AtomicInteger()
            val released = CountDownLatch(1)
            val adapter = DocumentPrintAdapter(session, "Print fixture", { releases.incrementAndGet(); released.countDown() })
            val file = File.createTempFile("print-limit-", ".pdf", instrumentation.targetContext.cacheDir)
            try {
                layout(adapter, session.info.pageCount)
                val done = CountDownLatch(1)
                val callbacks = AtomicInteger()
                val failure = AtomicReference<String?>()
                val destination = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE)
                instrumentation.runOnMainSync {
                    adapter.onWrite(arrayOf(PageRange(0, 0)), destination, CancellationSignal(), object : AllDocWriteResultCallback() {
                        override fun onWriteFailed(error: CharSequence?) { failure.set(error?.toString()); callbacks.incrementAndGet(); done.countDown() }
                        override fun onWriteFinished(pages: Array<out PageRange>) { failure.set("Unexpected success"); callbacks.incrementAndGet(); done.countDown() }
                        override fun onWriteCancelled() { failure.set("Unexpected cancellation"); callbacks.incrementAndGet(); done.countDown() }
                    })
                }
                assertTrue(done.await(15, TimeUnit.SECONDS))
                assertEquals(expected, failure.get())
                assertEquals(1, callbacks.get())
                assertFalse("Failed print closes its owned output", destination.fileDescriptor.valid())
                assertEquals("Print session remains borrowed until finish", 0, releases.get())
            } finally {
                instrumentation.runOnMainSync { adapter.onFinish(); adapter.abort() }
                assertTrue(released.await(5, TimeUnit.SECONDS))
                assertEquals(1, releases.get())
                file.delete()
            }
        }
    }

    @Test fun reliablePrintFailureAfterCompletePdfReportsOneFailure() {
        ReportedDestinationFailure { bytes ->
            val text = String(bytes, Charsets.US_ASCII)
            text.startsWith("%PDF-1.4\n") && text.endsWith("%%EOF\n")
        }.use { destination ->
            val released = CountDownLatch(1)
            val releases = AtomicInteger()
            val callbacks = AtomicInteger()
            val done = CountDownLatch(1)
            val outcome = AtomicReference<String?>()
            val session = object : StubSession() {
                override fun renderPage(index: Int, width: Int, settings: ThemeSettings, signal: CancellationSignal): Bitmap =
                    Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            }
            val adapter = DocumentPrintAdapter(session, "Reliable output", { releases.incrementAndGet(); released.countDown() })
            try {
                layout(adapter)
                instrumentation.runOnMainSync {
                    adapter.onWrite(arrayOf(PageRange(0, 0)), destination.output, CancellationSignal(), object : AllDocWriteResultCallback() {
                        override fun onWriteFailed(error: CharSequence?) { outcome.set(error?.toString()); callbacks.incrementAndGet(); done.countDown() }
                        override fun onWriteFinished(pages: Array<out PageRange>) { outcome.set("Unexpected success"); callbacks.incrementAndGet(); done.countDown() }
                        override fun onWriteCancelled() { outcome.set("Unexpected cancellation"); callbacks.incrementAndGet(); done.countDown() }
                    })
                }
                assertTrue(done.await(10, TimeUnit.SECONDS))
                assertEquals("Couldn’t print this document.", outcome.get())
                assertEquals(1, callbacks.get())
                assertEquals(1, destination.checks)
                assertTrue("PDF trailer must be flushed before the peer reports its failure", destination.completePayload)
                assertFalse(destination.output.fileDescriptor.valid())
            } finally {
                instrumentation.runOnMainSync { adapter.onFinish(); adapter.abort() }
                assertTrue(released.await(5, TimeUnit.SECONDS))
                assertEquals(1, releases.get())
            }
        }
    }

    private fun layout(adapter: DocumentPrintAdapter, expectedPages: Int = 5) {
        val done = CountDownLatch(1)
        val error = AtomicReference<String?>()
        instrumentation.runOnMainSync {
            adapter.onLayout(null, attributes, CancellationSignal(), object : AllDocLayoutResultCallback() {
                override fun onLayoutFinished(info: PrintDocumentInfo, changed: Boolean) {
                    assertSame(Looper.getMainLooper(), Looper.myLooper())
                    assertEquals(expectedPages, info.pageCount)
                    done.countDown()
                }
                override fun onLayoutFailed(failure: CharSequence?) { error.set(failure?.toString()); done.countDown() }
                override fun onLayoutCancelled() { error.set("Cancelled"); done.countDown() }
            }, null)
        }
        assertTrue(done.await(10, TimeUnit.SECONDS))
        assertNull(error.get())
    }

    private open class StubSession : DocumentSession {
        override val info = DocumentInfo("Print fixture", "text/markdown", "text", 5, true, "TEXT")
        override fun text(signal: CancellationSignal) = "Test"
        override fun json(signal: CancellationSignal) = "{}"
        override fun renderPage(index: Int, width: Int, settings: ThemeSettings, signal: CancellationSignal): Bitmap = error("Unexpected render")
        override fun close(): Unit = error("Borrowed sessions must be released by their owner")
    }
}
