package app.smallthingz.alldoc.oacp

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.ThreadPoolExecutor
import java.util.UUID

/** Real text parsing with the UI publication queue deliberately held at each ownership boundary. */
class CapabilityOwnershipTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun cancelAfterWorkerCompletionBeforeUiPublicationDeletesOutput() {
        withSource { model, request, directory, source ->
            instrumentation.runOnMainSync {
                model.start(request)
                // The worker can finish, but its main-thread result cannot run inside this block.
                await { outputs(directory, source).isNotEmpty() && workers().activeCount == 0 }
                model.cancel()
            }
            await { outputs(directory, source).isEmpty() }
        }
    }

    @Test fun cancelAfterPublicationBeforeDeliveryDeletesOutput() {
        withSource { model, request, directory, source ->
            instrumentation.runOnMainSync { model.start(request) }
            await {
                var ready = false
                instrumentation.runOnMainSync { ready = model.result is CapabilityOutcome.Success }
                ready
            }
            instrumentation.runOnMainSync { model.cancel() }
            await { outputs(directory, source).isEmpty() }
        }
    }

    @Test fun failedSharingReturnsOwnedOutputForCleanup() {
        withSource { model, request, directory, source ->
            instrumentation.runOnMainSync { model.start(request) }
            await {
                var ready = false
                instrumentation.runOnMainSync { ready = model.result is CapabilityOutcome.Success }
                ready
            }
            instrumentation.runOnMainSync { assertTrue(model.takeResult()); model.rejectResult() }
            await { outputs(directory, source).isEmpty() }
        }
    }

    private fun withSource(test: (DocumentCapabilityModel, CapabilityRequest, File, File) -> Unit) {
        val sandbox = File(application.cacheDir, "oacp-results/fixture-${UUID.randomUUID()}")
        val fixtureApplication = object : Application() {
            init { attachBaseContext(application) }
            override fun getCacheDir() = sandbox
        }
        val directory = File(sandbox, "oacp-results").apply { mkdirs() }
        val source = File(directory, "fixture.md").apply { writeText("Ownership test\n") }
        val request = CapabilityRequest("ownership", "read_document", "text", CapabilityFiles.uri(application, source),
            0, 1200, "", "fixture.md", "text/markdown")
        lateinit var model: DocumentCapabilityModel
        instrumentation.runOnMainSync { model = DocumentCapabilityModel(fixtureApplication) }
        try { test(model, request, directory, source) }
        finally {
            instrumentation.runOnMainSync { model.cancel() }
            await { workers().activeCount == 0 && outputs(directory, source).isEmpty() }
            sandbox.deleteRecursively()
        }
    }
    private fun outputs(directory: File, source: File) = directory.listFiles().orEmpty().filter { it != source }
    private fun workers(): ThreadPoolExecutor = DocumentCapabilityModel::class.java.getDeclaredField("workers").let {
        it.isAccessible = true; it.get(null) as ThreadPoolExecutor
    }
    private fun await(condition: () -> Boolean) {
        val end = android.os.SystemClock.uptimeMillis() + 4000
        while (!condition()) {
            assertTrue("Timed out waiting for output ownership", android.os.SystemClock.uptimeMillis() < end)
            Thread.sleep(5)
        }
    }
}
