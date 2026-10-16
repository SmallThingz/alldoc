package app.smallthingz.alldoc.pdf

import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PdfDocumentTest {
    private fun fixture(): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PdfDocument.configure(context)
        return File.createTempFile("pdf-fixture-", ".pdf", context.cacheDir).also { file ->
            val pdf = android.graphics.pdf.PdfDocument()
            try {
                repeat(2) { index ->
                    val page = pdf.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(300, 400, index + 1).create())
                    page.canvas.drawColor(Color.WHITE)
                    page.canvas.drawText("Hello page ${index + 1}", 20f, 60f, Paint().apply {
                        color = Color.BLACK
                        textSize = 24f
                    })
                    pdf.finishPage(page)
                }
                file.outputStream().use(pdf::writeTo)
            } finally { pdf.close() }
        }
    }

    @Test fun rendersPagesAndExportsAvailableText() {
        val file = fixture()
        try {
            PdfDocument.open(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY), file.name).use { pdf ->
                assertEquals(2, pdf.pageCount)
                assertEquals(4f / 3f, pdf.pageAspect(0, CancellationSignal()), .0001f)
                assertEquals(4f / 3f, pdf.pageAspect(1, CancellationSignal()), .0001f)
                val bitmap = pdf.renderPage(1, 600, CancellationSignal())
                try {
                    assertEquals(600, bitmap.width)
                    assertEquals(800, bitmap.height)
                    assertEquals(Color.WHITE, bitmap.getPixel(0, 0))
                    val pixels = IntArray(bitmap.width * bitmap.height)
                    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    assertTrue(pixels.any { Color.red(it) < 100 })
                } finally { bitmap.recycle() }
                if (pdf.textAvailable) {
                    assertTrue(pdf.text(CancellationSignal()).contains("Hello page 2"))
                    val json = JSONObject(pdf.json(CancellationSignal()))
                    assertEquals(2, json.getJSONArray("sections").length())
                    assertFalse(json.getBoolean("truncated"))
                    assertEquals(1, json.getJSONArray("sections").getJSONObject(1).getInt("index"))
                } else {
                    assertFalse(pdf.textAvailable)
                    try { pdf.text(CancellationSignal()); fail("Text API must be explicit on older Android") }
                    catch (_: PdfTextUnavailableException) { }
                }
                try { pdf.renderPage(2, 600, CancellationSignal()); fail("Out-of-range page accepted") }
                catch (_: IllegalArgumentException) { }
                assertThrows(IllegalArgumentException::class.java) { pdf.pageAspect(2, CancellationSignal()) }
            }
        } finally { file.delete() }
    }

    @Test fun cancellationAndOpenFailuresReleaseDescriptors() {
        val file = fixture()
        try {
            val cancelled = CancellationSignal().apply { cancel() }
            val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            try { PdfDocument.open(fd, file.name, signal = cancelled); fail("Cancelled open succeeded") }
            catch (_: OperationCanceledException) { }
            assertFalse(fd.fileDescriptor.valid())
            PdfDocument.open(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY), file.name).use { pdf ->
                try { pdf.renderPage(0, 600, cancelled); fail("Cancelled render succeeded") }
                catch (_: OperationCanceledException) { }
                assertThrows(OperationCanceledException::class.java) { pdf.pageAspect(0, cancelled) }
                pdf.renderPage(0, 300, CancellationSignal()).recycle()
                pdf.close()
                try { pdf.renderPage(0, 300, CancellationSignal()); fail("Closed PDF rendered") }
                catch (_: IllegalStateException) { }
                assertThrows(IllegalStateException::class.java) { pdf.pageAspect(0, CancellationSignal()) }
            }
            file.writeText("not a pdf")
            val invalidFd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            try { PdfDocument.open(invalidFd, file.name); fail("Invalid PDF opened") }
            catch (_: java.io.IOException) { }
            assertFalse(invalidFd.fileDescriptor.valid())
        } finally { file.delete() }
    }
    @Test fun encryptedPdfRequiresCorrectPassword() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        PdfDocument.configure(instrumentation.targetContext)
        val file = File.createTempFile("encrypted-", ".pdf", instrumentation.targetContext.cacheDir)
        instrumentation.context.assets.open("encrypted.pdf").use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        fun descriptor() = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        try {
            if (Build.VERSION.SDK_INT >= 35) {
                try { PdfDocument.open(descriptor(), "locked.pdf"); fail("Encrypted PDF opened without password") }
                catch (_: PdfPasswordException) { }
                try { PdfDocument.open(descriptor(), "locked.pdf", "incorrect"); fail("Wrong password accepted") }
                catch (_: PdfPasswordException) { }
                PdfDocument.open(descriptor(), "locked.pdf", "alldoc-test").use { pdf ->
                    assertEquals(1, pdf.pageCount)
                    assertTrue(pdf.outline(CancellationSignal()).isEmpty())
                    assertTrue(pdf.notes(CancellationSignal()).entries.isEmpty())
                    pdf.renderPage(0, 300, CancellationSignal()).recycle()
                }
            } else {
                try { PdfDocument.open(descriptor(), "locked.pdf", "alldoc-test"); fail("Unsupported password accepted") }
                catch (_: PdfPasswordUnsupportedException) { }
            }
        } finally { file.delete() }
    }

    @Test fun nativeWorkerRunsOnDeviceAndHonoursCancellation() {
        val file = fixture()
        try {
            assertTrue("PDF extraction executable must be packaged", NativePdfExports.available())
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                val document = JSONObject(NativePdfExports.json(fd, CancellationSignal()))
                assertEquals(2, document.getInt("pageCount"))
                assertTrue(document.getJSONArray("sections").getJSONObject(1).getJSONArray("blocks")
                    .getJSONObject(0).getString("text").contains("Hello page 2"))
                try { NativePdfExports.json(fd, CancellationSignal().apply { cancel() }); fail("Cancelled native export succeeded") }
                catch (_: OperationCanceledException) { }
            }
        } finally { file.delete() }
    }

    @Test fun nativeExportRecreatesRemovedCacheParent() {
        val file = fixture()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "pdf-exports-${android.os.Process.myPid()}")
        try {
            // Delete only an empty parent; never remove another export's files.
            assertTrue("Expected an empty PDF export directory", directory.delete())
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                val document = JSONObject(NativePdfExports.json(fd, CancellationSignal()))
                assertEquals(2, document.getInt("pageCount"))
                assertTrue(directory.isDirectory)
            }
        } finally { file.delete() }
    }

}
