package app.smallthingz.alldoc.oacp

import android.app.Application
import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import app.smallthingz.alldoc.theme.ImageTreatment
import app.smallthingz.alldoc.theme.ReadingMode
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ThreadPoolExecutor
import org.junit.Assert.*
import org.junit.Test

/** Bounded native PNG/ownership smoke; it does not launch the capability Activity or send a broadcast. */
class CapabilitySheetModelTest {
    @Test fun nativeWorksheetPngUsesGridAndReleasesRejectedOutput() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val sandbox = File(application.cacheDir, "oacp-results/worksheet-${UUID.randomUUID()}")
        val fixtureApplication = object : Application() {
            init { attachBaseContext(application) }
            override fun getCacheDir() = sandbox
        }
        val directory = File(sandbox, "oacp-results").apply { check(mkdirs()) }
        val source = File(directory, "fixture.xlsx")
        application.assets.open("examples/office/office-guide.xlsx").use { input -> source.outputStream().use { output -> input.copyTo(output) } }
        val sourceHash = hash(source)
        lateinit var model: DocumentCapabilityModel
        instrumentation.runOnMainSync { model = DocumentCapabilityModel(fixtureApplication) }
        try {
            val request = CapabilityRequest("worksheet-native", "render_sheet", "png", CapabilityFiles.uri(application, source),
                0, 160, "", source.name, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                CapabilitySheetRequest(null, .25, .5, 1.25, 128, ReadingMode.ADAPTIVE, ImageTreatment.AUTO))
            instrumentation.runOnMainSync { assertTrue(model.start(request)) }
            var outcome: CapabilityOutcome? = null
            await(20000) { instrumentation.runOnMainSync { outcome = model.result }; outcome != null }
            assertTrue("Native worksheet failed: $outcome", outcome is CapabilityOutcome.Success)
            val result = outcome as CapabilityOutcome.Success
            assertEquals("VISUAL_BEST_EFFORT", result.fidelity)
            assertFalse(result.warnings.any { it.startsWith("Original-layout view unavailable:") })
            assertEquals("image/png", result.mime)
            assertTrue(result.bytes in 1..CapabilityFiles.OUTPUT_LIMIT)
            val metadata = requireNotNull(result.worksheet)
            assertEquals("worksheet", metadata["view"])
            assertEquals(false, metadata["paginationKnown"])
            assertEquals("auto", metadata["imageTreatment"])
            assertEquals("adaptive", metadata["theme"])
            assertFalse(metadata.containsKey("pageCount"))
            assertFalse(metadata.containsKey("page"))
            val viewport = metadata["viewport"] as Map<*, *>
            assertEquals(.25, viewport["x"])
            assertEquals(.5, viewport["y"])
            assertEquals(1.25, viewport["scale"])
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(result.file.path, bounds)
            assertEquals(160, bounds.outWidth)
            assertEquals(128, bounds.outHeight)
            assertEquals("image/png", bounds.outMimeType)
            assertEquals(sourceHash, hash(source))
            instrumentation.runOnMainSync { assertTrue(model.takeResult()); model.rejectResult() }
            await(10000) { !result.file.exists() }
        } finally {
            instrumentation.runOnMainSync { model.cancel() }
            await(10000) { workers().activeCount == 0 }
            sandbox.deleteRecursively()
        }
    }
    private fun hash(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun workers(): ThreadPoolExecutor = DocumentCapabilityModel::class.java.getDeclaredField("workers").let {
        it.isAccessible = true; it.get(null) as ThreadPoolExecutor
    }
    private fun await(timeout: Long, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeout
        while (!predicate()) {
            assertTrue("Timed out waiting for worksheet ownership", SystemClock.uptimeMillis() < deadline)
            Thread.sleep(10)
        }
    }
}
