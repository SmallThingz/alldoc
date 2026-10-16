package app.smallthingz.alldoc.pdf

import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import app.smallthingz.alldoc.engine.DocumentOutlineEntry
import app.smallthingz.alldoc.engine.DocumentEngine
import app.smallthingz.alldoc.engine.DocumentNote
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class PdfOutlineTest {
    @Test fun annotationsPreserveTextAndPageLocationsAndJoinExportsWithoutActions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("notes-fixture-", ".pdf", context.cacheDir)
        file.writeBytes(pdfOutlineFixture(notes = true))
        try {
            val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            DocumentEngine.open(context, descriptor, "notes.pdf", "application/pdf", CancellationSignal()).use { session ->
                assertTrue(session.info.supportsNotes)
                val notes = session.notes(CancellationSignal())
                assertFalse(notes.truncated)
                assertEquals(listOf(
                    DocumentNote("Check हिन्दी 😀", "comment", "Maya", "Page 1", 0),
                    DocumentNote("Keep this passage", "highlight", "", "Page 2", 1),
                ), notes.entries)
                assertSame(notes, session.notes(CancellationSignal()))
                val body = session.bodyText(CancellationSignal())
                assertTrue(body.contains("Outline fixture"))
                assertFalse("Reader body must not append the notes panel", body.contains("Check हिन्दी 😀"))
                val json = org.json.JSONObject(session.json(CancellationSignal()))
                assertEquals(2, json.getJSONArray("notes").length())
                assertEquals("Keep this passage", json.getJSONArray("notes").getJSONObject(1).getString("text"))
                assertEquals(1, json.getJSONArray("notes").getJSONObject(1).getInt("page"))
                assertTrue(session.text(CancellationSignal()).contains("Check हिन्दी 😀"))
                assertThrows(OperationCanceledException::class.java) {
                    session.notes(CancellationSignal().apply { cancel() })
                }
                session.close()
                assertThrows(IllegalStateException::class.java) { session.notes(CancellationSignal()) }
            }
            assertFalse(descriptor.fileDescriptor.valid())
        } finally { file.delete() }
    }

    @Test fun realBookmarksPreserveDuplicatesAndHierarchyAndPrintUsesPlatformPages() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PdfDocument.configure(context)
        val file = File.createTempFile("outline-fixture-", ".pdf", context.cacheDir)
        file.writeBytes(pdfOutlineFixture())
        try {
            val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            PdfDocument.open(descriptor, "contents.pdf").use { pdf ->
                val entries = pdf.outline(CancellationSignal())
                assertEquals(listOf(DocumentOutlineEntry("Repeated", 0), DocumentOutlineEntry("Child", 1, 1), DocumentOutlineEntry("Repeated", 1)), entries)
                assertSame(entries, pdf.outline(CancellationSignal()))
                try { pdf.outline(CancellationSignal().apply { cancel() }); fail("Canceled contents returned") }
                catch (_: OperationCanceledException) { }
                val bitmap = pdf.renderPrintPage(1, 120, CancellationSignal())
                try { assertEquals(120, bitmap.width); assertEquals(160, bitmap.height) }
                finally { bitmap.recycle() }
                pdf.close()
                try { pdf.outline(CancellationSignal()); fail("Closed PDF returned contents") }
                catch (_: IllegalStateException) { }
            }
            assertFalse(descriptor.fileDescriptor.valid())
        } finally { file.delete() }
    }
}

// Self-contained ordinary PDF objects, no downloaded or copyrighted content.
internal fun pdfOutlineFixture(notes: Boolean = false, pageHeight: Int = 400): ByteArray {
    val content = "BT /F1 18 Tf 30 350 Td (Outline fixture) Tj ET\n"
    val objects = mutableListOf(
        "<< /Type /Catalog /Pages 2 0 R /Outlines 6 0 R >>",
        "<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 >>",
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 $pageHeight] /Resources << /Font << /F1 << /Type /Font /Subtype /Type1 /BaseFont /Helvetica >> >> >> ${if (notes) "/Annots [10 0 R 12 0 R 13 0 R 14 0 R]" else ""} /Contents 5 0 R >>",
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 $pageHeight] /Resources << /Font << /F1 << /Type /Font /Subtype /Type1 /BaseFont /Helvetica >> >> >> ${if (notes) "/Annots [11 0 R]" else ""} /Contents 5 0 R >>",
        "<< /Length ${content.length} >>\nstream\n${content}endstream",
        "<< /Type /Outlines /First 7 0 R /Last 8 0 R /Count 3 >>",
        "<< /Title (Repeated) /Parent 6 0 R /Dest [3 0 R /Fit] /First 9 0 R /Last 9 0 R /Count 1 /Next 8 0 R >>",
        "<< /Title (Repeated) /Parent 6 0 R /Dest [4 0 R /Fit] /Prev 7 0 R >>",
        "<< /Title (Child) /Parent 7 0 R /Dest [4 0 R /Fit] >>",
    )
    if (notes) {
        val unicode = ("\uFEFFCheck हिन्दी 😀").toByteArray(Charsets.UTF_16BE).joinToString("") { "%02x".format(it.toInt() and 255) }
        objects += listOf(
            "<< /Type /Annot /Subtype /Text /Rect [20 20 40 40] /Contents <$unicode> /T (Maya) >>",
            "<< /Type /Annot /Subtype /Highlight /Rect [20 60 100 80] /Contents (Keep this passage) >>",
            "<< /Type /Annot /Subtype /Popup /Rect [20 20 40 40] /Contents (Duplicate popup) >>",
            "<< /Type /Annot /Subtype /FileAttachment /Rect [20 20 40 40] /Contents (Attachment) >>",
            "<< /Type /Annot /Subtype /Text /Rect [20 20 40 40] /Contents << /Invalid true >> >>",
        )
    }
    val output = ByteArrayOutputStream()
    fun write(text: String) { output.write(text.toByteArray(Charsets.US_ASCII)) }
    write("%PDF-1.7\n")
    val offsets = objects.mapIndexed { index, content -> output.size().also { write("${index + 1} 0 obj\n$content\nendobj\n") } }
    val xref = output.size()
    write("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
    offsets.forEach { write(it.toString().padStart(10, '0') + " 00000 n \n") }
    write("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
    return output.toByteArray()
}
