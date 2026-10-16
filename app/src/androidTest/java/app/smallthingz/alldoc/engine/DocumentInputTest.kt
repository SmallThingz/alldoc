package app.smallthingz.alldoc.engine

import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DocumentInputTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun open(fd: ParcelFileDescriptor, signal: CancellationSignal = CancellationSignal()) =
        DocumentEngine.open(context, fd, "stream.md", "text/markdown", signal)

    @Test fun cachedSnapshotDirectoryIsRecreatedAfterRemoval() {
        val first = DocumentEngine.newSnapshot(context)
        val directory = requireNotNull(first.parentFile)
        assertTrue(first.delete())
        // Non-recursive deletion cannot remove another session's files.
        assertTrue("Expected an empty snapshot directory", directory.delete())
        val reopened = DocumentEngine.newSnapshot(context)
        try {
            assertEquals(directory, reopened.parentFile)
            assertTrue(reopened.isFile)
        } finally { reopened.delete() }
    }

    @Test fun providerErrorDoesNotBecomeSuccessfulPartialText() {
        val pipe = ParcelFileDescriptor.createReliablePipe()
        val handle = pipe[0].fileDescriptor
        try {
            val bytes = "Incomplete provider document".toByteArray()
            Os.write(pipe[1].fileDescriptor, bytes, 0, bytes.size)
            pipe[1].closeWithError("Provider download failed")
            try { open(pipe[0]).use { }; fail("Failed provider stream was accepted") }
            catch (error: IOException) { assertTrue(error.message.orEmpty().contains("Provider download failed")) }
            assertFalse(handle.valid())
        } finally { pipe.forEach { it.close() } }
    }

    @Test fun normalReliableEndOfStreamPreservesText() {
        val pipe = ParcelFileDescriptor.createReliablePipe()
        try {
            ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write("Complete provider document".toByteArray()) }
            open(pipe[0]).use {
                assertEquals("Complete provider document", it.text(CancellationSignal()))
                assertFalse(JSONObject(it.json(CancellationSignal())).getBoolean("truncated"))
            }
        } finally { pipe.forEach { it.close() } }
    }

    @Test fun emptyNonblockingSourceRetriesUntilProviderWrites() = delayedProvider(cancel = false)
    @Test fun emptyNonblockingSourceRemainsCancellable() = delayedProvider(cancel = true)

    private fun delayedProvider(cancel: Boolean) {
        val pipe = ParcelFileDescriptor.createReliablePipe()
        val observer = ParcelFileDescriptor.dup(pipe[0].fileDescriptor)
        val originalFlags = Os.fcntlInt(observer.fileDescriptor, OsConstants.F_GETFL, 0)
        val signal = CancellationSignal()
        val worker = Executors.newSingleThreadExecutor()
        val result = worker.submit<String> { open(pipe[0], signal).use { it.text(signal) } }
        try {
            val deadline = SystemClock.elapsedRealtime() + 3000
            while (Os.fcntlInt(observer.fileDescriptor, OsConstants.F_GETFL, 0) and OsConstants.O_NONBLOCK == 0 && SystemClock.elapsedRealtime() < deadline)
                SystemClock.sleep(5)
            assertTrue("Stream input must use nonblocking reads", Os.fcntlInt(observer.fileDescriptor, OsConstants.F_GETFL, 0) and OsConstants.O_NONBLOCK != 0)
            assertFalse("Empty provider stream must wait", result.isDone)
            if (cancel) {
                signal.cancel()
                try { result.get(3, TimeUnit.SECONDS); fail("Stalled read ignored cancellation") }
                catch (error: ExecutionException) { assertTrue(error.cause is OperationCanceledException) }
            } else {
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write("Delayed provider document".toByteArray()) }
                assertEquals("Delayed provider document", result.get(3, TimeUnit.SECONDS))
            }
            assertEquals("Shared descriptor flags must be restored", originalFlags,
                Os.fcntlInt(observer.fileDescriptor, OsConstants.F_GETFL, 0))
        } finally {
            signal.cancel()
            pipe[1].close()
            worker.shutdown()
            assertTrue("Input worker did not release ownership", worker.awaitTermination(3, TimeUnit.SECONDS))
            observer.close()
            pipe[0].close()
        }
    }
}
