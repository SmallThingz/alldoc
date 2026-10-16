package app.smallthingz.alldoc.engine

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.smallthingz.alldoc.theme.ReadingMode
import app.smallthingz.alldoc.theme.ThemeSettings
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EnginePreviewTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun snapshots(): Set<String> = File(context.cacheDir, "documents-${android.os.Process.myPid()}")
        .listFiles()?.map { it.name }?.toSet() ?: emptySet()

    private fun image(format: Bitmap.CompressFormat): ByteArray {
        val bitmap = Bitmap.createBitmap(128, 64, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(Color.BLUE)
            ByteArrayOutputStream().also { assertTrue(bitmap.compress(format, 100, it)) }.toByteArray()
        } finally { bitmap.recycle() }
    }

    private fun pdf(): ByteArray {
        val document = android.graphics.pdf.PdfDocument()
        return try {
            repeat(2) { index ->
                val page = document.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(300, 400, index + 1).create())
                page.canvas.drawColor(Color.WHITE)
                page.canvas.drawText("Saved preview ${index + 1}", 20f, 60f, Paint().apply { color = Color.BLACK; textSize = 24f })
                document.finishPage(page)
            }
            ByteArrayOutputStream().also(document::writeTo).toByteArray()
        } finally { document.close() }
    }

    @Test fun iworkPdfPreviewKeepsSourceIdentityAndExportProvenance() {
        checkIwork("pages", "application/vnd.apple.pages", "QuickLook/Preview.pdf", pdf(), true)
    }

    @Test fun iworkJpegPreviewKeepsMetadataAndDoesNotInventText() {
        checkIwork("key", "application/vnd.apple.keynote", "QuickLook/Thumbnail.jpg", image(Bitmap.CompressFormat.JPEG), false)
    }

    private fun checkIwork(format: String, mime: String, previewPart: String, bytes: ByteArray, hasText: Boolean) {
        val source = File.createTempFile("iwork-preview-", ".zip", context.cacheDir)
        ZipOutputStream(source.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(previewPart)); zip.write(bytes); zip.closeEntry()
        }
        val original = source.readBytes()
        val before = snapshots()
        try {
            val descriptor = ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)
            val handle = descriptor.fileDescriptor
            DocumentEngine.open(context, descriptor, "Shared document", mime, CancellationSignal()).use { session ->
                assertFalse(handle.valid())
                assertEquals("Shared document", session.info.title)
                assertEquals(mime, session.info.mimeType)
                assertEquals(format, session.info.format)
                assertEquals("EMBEDDED_PREVIEW_ONLY", session.info.fidelity)
                assertEquals(if (hasText) 2 else 1, session.info.pageCount)
                assertEquals(hasText, session.info.textAvailable)
                val json = JSONObject(session.json(CancellationSignal()))
                assertEquals(1, json.getInt("version"))
                assertEquals(format, json.getString("format"))
                assertEquals("EMBEDDED_PREVIEW_ONLY", json.getString("fidelity"))
                assertEquals("embedded-preview", json.getString("contentSource"))
                assertEquals(previewPart, json.getString("previewPart"))
                assertEquals(if (hasText) "pdf" else "image", json.getString("previewFormat"))
                assertTrue(json.getJSONArray("warnings").toString().contains("preview"))
                if (hasText) {
                    val text = session.text(CancellationSignal())
                    assertTrue(text.startsWith("[Saved embedded preview;"))
                    assertTrue(text.contains("Saved preview 2"))
                    assertEquals(2, json.getJSONArray("sections").length())
                } else {
                    assertEquals(0, json.getJSONArray("sections").length())
                    try { session.text(CancellationSignal()); fail("JPEG preview claimed extracted text") }
                    catch (failure: IOException) {
                        assertTrue(failure.message.orEmpty().contains("no extracted text"))
                        assertTrue(failure.message.orEmpty().contains("OCR is not included"))
                    }
                }
                val bitmap = session.renderPage(session.info.pageCount - 1, 96, ThemeSettings(ReadingMode.ORIGINAL), CancellationSignal())
                try { assertTrue(bitmap.width > 0 && bitmap.height > 0) } finally { bitmap.recycle() }
                try {
                    session.renderPage(0, 96, ThemeSettings(), CancellationSignal().apply { cancel() })
                    fail("Cancelled preview returned an image")
                } catch (_: OperationCanceledException) { }
                assertTrue(snapshots().size > before.size)
            }
            assertEquals(before, snapshots())
            assertArrayEquals(original, source.readBytes())
        } finally { source.delete() }
    }
}
