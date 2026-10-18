package app.smallthingz.alldoc.oacp

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.oacp.android.Oacp
import java.io.File
import java.util.UUID
import java.util.concurrent.ThreadPoolExecutor

/** Real provider/export path; no Activity, broadcasts, permissions, or global UI automation. */
class CapabilityOptionalMetadataTest {
    @Test fun unavailableDisplayAndMimeMetadataStillExportsReadableDocument() = exported("metadata.md") { result, _ ->
        assertReadable(result)
    }

    @Test fun suppliedDisplayNameDoesNotRequireProviderMimeMetadata() = exported("metadata.md", "Shared.md") { result, _ ->
        assertReadable(result)
    }

    @Test fun providerMetadataCancellationDoesNotPublishOutput() = exported("metadata-cancel.md") { result, directory ->
        assertTrue("Expected metadata cancellation, got $result", result is CapabilityOutcome.Failure)
        assertEquals(Oacp.ERROR_CANCELLED, (result as CapabilityOutcome.Failure).code)
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    private fun assertReadable(result: CapabilityOutcome) {
        assertTrue("Readable provider failed: $result", result is CapabilityOutcome.Success)
        val output = result as CapabilityOutcome.Success
        assertEquals("text/plain", output.mime)
        assertEquals("text", output.format)
        assertTrue(output.bytes > 0)
        assertTrue(output.file.readText().contains("AllDoc fixture metadata.md"))
    }

    private fun exported(path: String, name: String? = null, inspect: (CapabilityOutcome, File) -> Unit) {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val sandbox = File(application.cacheDir, "oacp-results/optional-${UUID.randomUUID()}")
        val fixtureApplication = object : Application() {
            init { attachBaseContext(application) }
            override fun getCacheDir() = sandbox
        }
        val directory = File(sandbox, "oacp-results").apply { check(mkdirs()) }
        val uri = Uri.parse("content://app.smallthingz.alldoc.test.reader-fixtures/$path")
        val request = CapabilityRequest("optional-metadata", "read_document", "text", uri, 0, 160, "", name, null)
        lateinit var model: DocumentCapabilityModel
        instrumentation.runOnMainSync { model = DocumentCapabilityModel(fixtureApplication) }
        try {
            instrumentation.runOnMainSync { assertTrue(model.start(request)) }
            var outcome: CapabilityOutcome? = null
            await { instrumentation.runOnMainSync { outcome = model.result }; outcome != null }
            instrumentation.runOnMainSync { assertFalse(model.running) }
            inspect(requireNotNull(outcome), directory)
        } finally {
            instrumentation.runOnMainSync { model.cancel() }
            await { workers().activeCount == 0 && directory.listFiles().orEmpty().isEmpty() }
            sandbox.deleteRecursively()
        }
    }

    private fun workers(): ThreadPoolExecutor = DocumentCapabilityModel::class.java.getDeclaredField("workers").let {
        it.isAccessible = true; it.get(null) as ThreadPoolExecutor
    }
    private fun await(ready: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 8_000
        while (!ready()) {
            assertTrue("Timed out waiting for optional metadata export", SystemClock.uptimeMillis() < deadline)
            Thread.sleep(10)
        }
    }
}
