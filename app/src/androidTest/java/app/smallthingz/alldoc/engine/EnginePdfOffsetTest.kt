package app.smallthingz.alldoc.engine

import android.graphics.Paint
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import androidx.test.platform.app.InstrumentationRegistry
import app.smallthingz.alldoc.theme.ReadingMode
import app.smallthingz.alldoc.theme.ThemeSettings
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class EnginePdfOffsetTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun fixture(): File = File.createTempFile("pdf-offset-", ".pdf", context.cacheDir).also { file ->
        val pdf = android.graphics.pdf.PdfDocument()
        try {
            val page = pdf.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(240, 320, 1).create())
            page.canvas.drawText("Offset remains intact", 12f, 40f, Paint().apply { textSize = 18f })
            pdf.finishPage(page)
            file.outputStream().use(pdf::writeTo)
        } finally { pdf.close() }
    }
    private fun position(fd: ParcelFileDescriptor) = Os.lseek(fd.fileDescriptor, 0, OsConstants.SEEK_CUR)

    @Test fun pdfReadsPreserveCallerOffsetWithIndependentAndSnapshotInputs() {
        for (denyReopen in listOf(false, true)) {
            val file = fixture()
            try {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { caller ->
                    Os.lseek(caller.fileDescriptor, 17, OsConstants.SEEK_SET)
                    if (denyReopen) Os.chmod(file.path, 0)
                    val adopted = ParcelFileDescriptor.dup(caller.fileDescriptor)
                    DocumentEngine.open(context, adopted, "offset.pdf", "application/pdf", CancellationSignal()).use { session ->
                        assertEquals(17L, position(caller))
                        session.renderPage(0, 120, ThemeSettings(ReadingMode.ORIGINAL), CancellationSignal()).recycle()
                        assertEquals(17L, position(caller))
                        assertTrue(session.text(CancellationSignal()).contains("Offset remains intact"))
                        assertEquals(17L, position(caller))
                    }
                    assertEquals(17L, position(caller))
                    assertTrue(caller.fileDescriptor.valid())
                    assertFalse(adopted.fileDescriptor.valid())
                }
            } finally { Os.chmod(file.path, 0x180); file.delete() }
        }
    }

    @Test fun rejectedPdfDoesNotMoveCallerOffset() {
        val file = File.createTempFile("bad-pdf-offset-", ".pdf", context.cacheDir).apply { writeText("%PDF-1.7\nnot a valid PDF document\n") }
        try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { caller ->
                Os.lseek(caller.fileDescriptor, 4, OsConstants.SEEK_SET)
                val adopted = ParcelFileDescriptor.dup(caller.fileDescriptor)
                try {
                    DocumentEngine.open(context, adopted, "bad.pdf", "application/pdf", CancellationSignal()).use {
                        fail("Invalid PDF opened")
                    }
                } catch (_: java.io.IOException) { }
                assertEquals(4L, position(caller))
                assertTrue(caller.fileDescriptor.valid())
                assertFalse(adopted.fileDescriptor.valid())
            }
        } finally { file.delete() }
    }
}
