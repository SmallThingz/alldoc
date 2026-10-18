package app.smallthingz.alldoc.engine

import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.smallthingz.alldoc.render.DocRenderCore
import app.smallthingz.alldoc.pdf.PdfDocument
import app.smallthingz.alldoc.render.RenderProcess
import app.smallthingz.alldoc.render.WorkbookProcess
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkbookFailureTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun missingVisualModuleKeepsTheParsedSemanticWorkbook() {
        // PDF configuration is process-wide and initialized once; preserve its real module.
        PdfDocument.configure(context)
        val directory = File.createTempFile("workbook-fallback-", "", context.cacheDir).apply {
            check(delete()); check(mkdir())
        }
        val snapshot = File(directory, "sample.xlsx")
        val unavailable = object : ContextWrapper(context) {
            override fun getApplicationInfo() = ApplicationInfo(super.getApplicationInfo()).apply {
                nativeLibraryDir = File(directory, "missing-native-libraries").path
            }
        }
        try {
            instrumentation.context.assets.open("office/sample.xlsx").use { input ->
                snapshot.outputStream().use { input.copyTo(it) }
            }
            val signal = CancellationSignal()
            DocumentEngine.open(unavailable, ParcelFileDescriptor.open(snapshot, ParcelFileDescriptor.MODE_READ_ONLY),
                "sample.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", signal).use { session ->
                assertEquals("TEXT_STRUCTURE", session.info.fidelity)
                assertTrue(session.info.supportsSheets)
                assertTrue(session.info.warnings.any { it.startsWith("Original-layout view unavailable:") })
                assertTrue(requireNotNull(session.workbook(signal)).sheets.isNotEmpty())
                assertTrue(session.text(signal).isNotBlank())
            }
            assertThrows(OperationCanceledException::class.java) {
                DocumentEngine.open(unavailable, ParcelFileDescriptor.open(snapshot, ParcelFileDescriptor.MODE_READ_ONLY),
                    "sample.xlsx", null, CancellationSignal().apply { cancel() })
            }
        } finally { DocRenderCore.configure(context); directory.deleteRecursively() }
    }

    @Test fun interruptedVisualStartupDoesNotBecomeSemanticFallback() {
        PdfDocument.configure(context)
        val snapshot = File.createTempFile("workbook-interrupt-", ".xlsx", context.cacheDir)
        val signal = CancellationSignal()
        var reachedVisualConfiguration = false
        val interrupted = object : ContextWrapper(context) {
            override fun getApplicationInfo(): ApplicationInfo {
                reachedVisualConfiguration = true
                Thread.currentThread().interrupt()
                return super.getApplicationInfo()
            }
        }
        try {
            instrumentation.context.assets.open("office/sample.xlsx").use { input ->
                snapshot.outputStream().use { input.copyTo(it) }
            }
            assertThrows(InterruptedIOException::class.java) {
                DocumentEngine.open(interrupted, ParcelFileDescriptor.open(snapshot, ParcelFileDescriptor.MODE_READ_ONLY),
                    "sample.xlsx", null, signal).use { fail("Interrupted visual startup must not publish a semantic session") }
            }
            assertTrue(reachedVisualConfiguration)
            assertFalse(signal.isCanceled)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted(); DocRenderCore.configure(context); snapshot.delete() }
    }

    @Test fun interruptedQueuedAcquisitionPreservesTheFlagAndDoesNotReleaseAnotherOwnersPermit() {
        val directory = File.createTempFile("workbook-queued-", "", context.cacheDir).apply {
            check(delete()); check(mkdir())
        }
        val snapshot = File(directory, "input.xlsx").apply { writeBytes(byteArrayOf(1)) }
        val module = RenderProcess.Module(File(directory, "absent-renderer"), File(directory, "absent-launcher"), directory)
        val finished = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        val interrupted = AtomicBoolean()
        val caller = Thread({
            try {
                WorkbookProcess(module, snapshot).use { it.workbook(CancellationSignal()) }
                failure.set(AssertionError("Interrupted acquisition unexpectedly succeeded"))
            } catch (error: Throwable) { failure.set(error) }
            finally { interrupted.set(Thread.currentThread().isInterrupted); finished.countDown() }
        }, "workbook-queued-test")
        var ownsPermit = false
        try {
            ownsPermit = RenderProcess.SLOT.tryAcquire(5, TimeUnit.SECONDS)
            assertTrue("Test must own the render permit before queueing its caller", ownsPermit)
            caller.start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            var queued = false
            while (!queued && caller.isAlive && System.nanoTime() < deadline) {
                if (RenderProcess.SLOT.hasQueuedThreads()) { queued = true; caller.interrupt() }
                else Thread.sleep(1)
            }
            assertTrue("Caller must have queued for the owned render permit", queued)
            assertTrue("Interrupted caller must return promptly", finished.await(5, TimeUnit.SECONDS))
            assertTrue("Expected InterruptedIOException, got ${failure.get()}", failure.get() is InterruptedIOException)
            assertTrue("Caller interrupt flag must survive acquisition failure", interrupted.get())
            assertEquals("Failed acquisition must not release another owner's permit", 0, RenderProcess.SLOT.availablePermits())
        } finally {
            caller.interrupt()
            if (ownsPermit) RenderProcess.SLOT.release()
            caller.join(5000)
            directory.deleteRecursively()
        }
        assertFalse("Owned caller must terminate", caller.isAlive)
        assertEquals(1, RenderProcess.SLOT.availablePermits())
    }

    @Test fun startupEofIsAnIoFailureAndReleasesTheRenderPermit() {
        val directory = File.createTempFile("workbook-eof-", "", context.cacheDir).apply {
            check(delete()); check(mkdir())
        }
        val snapshot = File(directory, "input.xlsx").apply { writeBytes(byteArrayOf(1)) }
        val launcher = File(context.applicationInfo.nativeLibraryDir, "liballrun.so")
        // The first launcher execs a second launcher with worksheet arguments. Its
        // invalid parent argument makes it exit without a ZSG1 frame, deterministically.
        val module = RenderProcess.Module(launcher, launcher, directory)
        try {
            assertTrue(module.available())
            repeat(2) {
                WorkbookProcess(module, snapshot).use { workbook ->
                    val failure = assertThrows(IOException::class.java) { workbook.workbook(CancellationSignal()) }
                    assertEquals("Worksheet response unavailable", failure.message)
                    assertTrue(failure.cause is EOFException)
                }
                assertEquals(1, RenderProcess.SLOT.availablePermits())
            }
            assertFalse("EOF must not masquerade as caller interruption", Thread.currentThread().isInterrupted)
        } finally { directory.deleteRecursively() }
    }
}
