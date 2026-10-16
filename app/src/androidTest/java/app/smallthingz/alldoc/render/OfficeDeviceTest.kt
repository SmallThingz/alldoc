package app.smallthingz.alldoc.render

import android.graphics.Color
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.smallthingz.alldoc.engine.DocumentEngine
import app.smallthingz.alldoc.theme.ReadingMode
import app.smallthingz.alldoc.theme.ThemeSettings
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OfficeDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val original = ThemeSettings(ReadingMode.ORIGINAL)

    private fun fixture(name: String): File = File.createTempFile("office-", ".fixture", context.cacheDir).also { file ->
        instrumentation.context.assets.open("office/$name").use { input -> file.outputStream().use { input.copyTo(it) } }
    }

    @Test fun packagedOfficeWorkersRenderAndExportRealDocuments() {
        val cases = listOf(
            Triple("sample.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "AllDoc reader"),
            Triple("sample.pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation", "AllDoc slides"),
            Triple("sample.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "Reader")
        )
        for ((name, mime, text) in cases) {
            val file = fixture(name)
            try {
                val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                DocumentEngine.open(context, fd, name, mime, CancellationSignal()).use { session ->
                    assertEquals(name, "VISUAL_BEST_EFFORT", session.info.fidelity)
                    assertTrue(name, session.info.pageCount >= if (name.endsWith("xlsx")) 1 else 2)
                    assertTrue(name, session.info.textAvailable)
                    assertTrue(name, session.text(CancellationSignal()).contains(text))
                    val json = JSONObject(session.json(CancellationSignal()))
                    assertTrue(name, json.getJSONArray("sections").length() > 0)
                    assertTrue(name, json.toString().contains(text))
                    for ((page, width) in listOf(0 to 320, (session.info.pageCount - 1) to 64)) {
                        val bitmap = session.renderPage(page, width, original, CancellationSignal())
                        try {
                            assertTrue(name, bitmap.width in 1..width)
                            assertTrue(name, bitmap.height > 0)
                            val pixels = IntArray(bitmap.width * bitmap.height)
                            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                            assertTrue("$name blank preview", pixels.any { Color.alpha(it) > 0 && Color.red(it) < 240 })
                        } finally { bitmap.recycle() }
                    }
                    try { session.renderPage(session.info.pageCount, 320, original, CancellationSignal()); fail("Accepted an out-of-range page") }
                    catch (_: IllegalArgumentException) { }
                    try { session.renderPage(0, 320, original, CancellationSignal().apply { cancel() }); fail("Rendered after cancellation") }
                    catch (_: OperationCanceledException) { }
                }
            } finally { file.delete() }
        }
    }

    @Test fun canceledOfficeOpenClosesTheAdoptedHandle() {
        val file = fixture("sample.docx")
        try {
            val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val descriptor = fd.fileDescriptor
            try {
                DocumentEngine.open(context, fd, "sample.docx", null, CancellationSignal().apply { cancel() })
                fail("Canceled Office document opened")
            } catch (_: OperationCanceledException) { assertFalse(descriptor.valid()) }
        } finally { file.delete() }
    }
}
