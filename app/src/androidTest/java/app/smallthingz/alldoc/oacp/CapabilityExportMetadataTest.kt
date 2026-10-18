package app.smallthingz.alldoc.oacp

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import app.smallthingz.alldoc.engine.DocumentInfo
import app.smallthingz.alldoc.engine.DocumentSession
import app.smallthingz.alldoc.engine.PreviewSession
import app.smallthingz.alldoc.theme.ThemeSettings
import java.io.File
import java.util.UUID
import java.util.concurrent.ThreadPoolExecutor
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CapabilityExportMetadataTest {
    @Test fun nativeOfficeStructureMetadataMatchesSemanticJson() = office("json")
    @Test fun nativeOfficeTextMetadataIncludesSemanticOmissions() = office("text")

    private fun office(format: String) = exported("fixture.pptx", format, { file ->
        InstrumentationRegistry.getInstrumentation().context.assets.open("office/sample.pptx").use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
    }) { result ->
        assertEquals("TEXT_STRUCTURE", result.fidelity)
        assertTrue(result.warnings.any { it.contains("page layout, drawings and embedded objects are omitted") })
        assertFalse(result.warnings.any { it.startsWith("Original-layout view unavailable:") })
        val text = result.file.readText()
        assertTrue(text.isNotBlank())
        if (format == "json") {
            val json = JSONObject(text)
            assertEquals(json.getString("fidelity"), result.fidelity)
            assertTrue(json.getJSONArray("sections").length() > 0)
        }
    }

    @Test fun pdfStructureMetadataDescribesExtractionInsteadOfPageLayout() = exported("fixture.pdf", "json", { file ->
        val pdf = android.graphics.pdf.PdfDocument()
        try {
            val page = pdf.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(300, 400, 1).create())
            page.canvas.drawColor(Color.WHITE)
            page.canvas.drawText("Metadata example", 20f, 60f, Paint().apply { color = Color.BLACK; textSize = 20f })
            pdf.finishPage(page)
            file.outputStream().use(pdf::writeTo)
        } finally { pdf.close() }
    }) { result ->
        val json = JSONObject(result.file.readText())
        assertEquals(json.getString("fidelity"), result.fidelity)
        assertEquals("extracted-text", result.fidelity)
        assertTrue(result.warnings.any { it.contains("reading order") })
        assertTrue(json.getJSONArray("sections").length() > 0)
    }

    @Test fun embeddedPreviewKeepsItsOmissionMetadataWhenTextMetadataIsDelegated() {
        val source = object : DocumentSession {
            override val info = DocumentInfo("Preview", "application/pdf", "pdf", 1, true, "ORIGINAL_LAYOUT")
            override val textInfo = info.copy(fidelity = "extracted-text", warnings = listOf("Text extraction warning"))
            override fun text(signal: CancellationSignal) = "Saved preview text"
            override fun json(signal: CancellationSignal) = "{}"
            override fun renderPage(index: Int, width: Int, settings: ThemeSettings, signal: CancellationSignal): Bitmap = error("Not needed")
            override fun close() = Unit
        }
        PreviewSession(source, "pages", "application/vnd.apple.pages", "preview.pdf", listOf("Saved preview warning")).use { preview ->
            assertEquals("EMBEDDED_PREVIEW_ONLY", preview.textInfo.fidelity)
            assertTrue(preview.textInfo.warnings.contains("Text extraction warning"))
            assertTrue(preview.textInfo.warnings.contains("Saved preview warning"))
            assertTrue(preview.textInfo.warnings.any { it.contains("stale or incomplete") })
            assertEquals("EMBEDDED_PREVIEW_ONLY", preview.info.fidelity)
        }
    }

    private fun exported(name: String, format: String, create: (File) -> Unit, inspect: (CapabilityOutcome.Success) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = ApplicationProvider.getApplicationContext<Application>()
        val sandbox = File(application.cacheDir, "oacp-results/metadata-" + UUID.randomUUID())
        val fixtureApplication = object : Application() {
            init { attachBaseContext(application) }
            override fun getCacheDir() = sandbox
        }
        val folder = File(sandbox, "oacp-results").apply { check(mkdirs()) }
        val source = File(folder, name)
        create(source)
        lateinit var model: DocumentCapabilityModel
        instrumentation.runOnMainSync { model = DocumentCapabilityModel(fixtureApplication) }
        try {
            val capability = if (format == "json") "document_structure" else "read_document"
            val request = CapabilityRequest("metadata", capability, format, CapabilityFiles.uri(application, source),
                0, 160, "", name, null)
            instrumentation.runOnMainSync { assertTrue(model.start(request)) }
            var outcome: CapabilityOutcome? = null
            await(15000) { instrumentation.runOnMainSync { outcome = model.result }; outcome != null }
            assertTrue("Export failed: " + outcome, outcome is CapabilityOutcome.Success)
            inspect(outcome as CapabilityOutcome.Success)
        } finally {
            instrumentation.runOnMainSync { model.cancel() }
            await(8000) { workers().activeCount == 0 && folder.listFiles().orEmpty().all { it == source } }
            sandbox.deleteRecursively()
        }
    }

    private fun workers(): ThreadPoolExecutor = DocumentCapabilityModel::class.java.getDeclaredField("workers").let {
        it.isAccessible = true; it.get(null) as ThreadPoolExecutor
    }
    private fun await(timeout: Long, ready: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeout
        while (!ready()) {
            assertTrue("Timed out waiting for metadata export cleanup", SystemClock.uptimeMillis() < deadline)
            Thread.sleep(10)
        }
    }
}
