package app.smallthingz.alldoc.ipc

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.smallthingz.alldoc.api.IAllDocCallback
import app.smallthingz.alldoc.api.IAllDocService
import app.smallthingz.alldoc.api.IAllDocSession
import app.smallthingz.alldoc.engine.DocumentInfo
import app.smallthingz.alldoc.engine.DocumentSession
import app.smallthingz.alldoc.theme.ThemeSettings
import java.io.Closeable
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DocumentServiceMemoryTest {
    @Test fun openingAndAdmissionMemoryFailuresReleaseOwnershipAndAllowRetry() {
        for (admission in listOf(false, true)) Fixture().use { fixture ->
            if (admission) {
                val original = fixture.workers.threadFactory
                fixture.workers.threadFactory = ThreadFactory { throw OutOfMemoryError("Synthetic admission failure") }
                try {
                    try { fixture.open(); fail("Admission must fail") } catch (error: IllegalStateException) {
                        assertTrue(error.message.orEmpty().contains("memory"))
                    }
                } finally { fixture.workers.threadFactory = original }
                fixture.drainClosers()
                assertTrue(fixture.currentSessions().isEmpty())
                val timer = field(fixture.instance, "timer") as java.util.concurrent.ScheduledThreadPoolExecutor
                assertTrue("Admission timeout must be removed", timer.queue.isEmpty())
            } else {
                val installed = AtomicReference<DocumentSession>()
                val failed = fixture.open(expected = "error") { state ->
                    if (state.getString("state") == "ready") {
                        installed.set(field(fixture.currentSessions().single(), "document") as DocumentSession)
                        throw OutOfMemoryError("Synthetic publication failure")
                    }
                }
                assertNull(field(failed, "document"))
                assertTrue(failed.status().getString("error").orEmpty().contains("memory"))
                try { checkNotNull(installed.get()).text(CancellationSignal()); fail("Installed source must be closed") }
                catch (_: IllegalStateException) { }
                failed.close()
                fixture.drainClosers()
            }
            assertFalse("Failed open must close its input", checkNotNull(fixture.input).valid())
            val recovered = fixture.open()
            assertEquals("Small document", fixture.read(recovered, "text").toString(Charsets.UTF_8))
            recovered.close()
        }
    }

    @Test fun exportMemoryFailuresClosePipesRetireIdsAndKeepOtherSessionsUsable() = Fixture().use { fixture ->
        val session = fixture.open()
        val sibling = fixture.open()
        val fake = FailingSession()
        val lock = field(session, "access") as ReentrantLock
        lock.withLock {
            val source = field(session, "document") as DocumentSession
            session.javaClass.getDeclaredField("document").apply { isAccessible = true }.set(session, fake)
            source.close()
        }
        for (format in listOf("text", "json", "png")) {
            fake.failure.set(format)
            try { fixture.read(session, format); fail("Memory failure must end the reliable pipe with an error") }
            catch (error: IOException) { assertTrue(error.message.orEmpty().contains("memory")) }
            val bytes = fixture.read(session, format) // Reuse the same request ID immediately after error EOF.
            if (format == "png") {
                val image = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
                try { assertEquals(2, image.width) } finally { image.recycle() }
            } else assertEquals(if (format == "text") "Recovered" else "{}", bytes.toString(Charsets.UTF_8))
            assertEquals("Small document", fixture.read(sibling, "text").toString(Charsets.UTF_8))
        }
        assertTrue(fake.images.all { it.isRecycled })
        session.close() // Fake close throws OOM too; the cleanup executor must still retire admission.
        fixture.drainClosers()
        assertEquals(1, fake.closes)
        assertFalse(fixture.currentSessions().contains(session))
        val recovered = fixture.open()
        assertEquals("Small document", fixture.read(recovered, "text").toString(Charsets.UTF_8))
        recovered.close()
        sibling.close()
    }

    private class FailingSession : DocumentSession {
        override val info = DocumentInfo("Memory fixture", "text/markdown", "text", 1, true, "TEXT")
        val failure = AtomicReference<String?>()
        val images = ArrayList<Bitmap>()
        var closes = 0
        private fun check(format: String) { if (failure.getAndSet(null) == format) throw OutOfMemoryError("Synthetic $format failure") }
        override fun text(signal: CancellationSignal): String { check("text"); return "Recovered" }
        override fun json(signal: CancellationSignal): String { check("json"); return "{}" }
        override fun renderPage(index: Int, width: Int, settings: ThemeSettings, signal: CancellationSignal): Bitmap {
            check("png")
            return Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).also { images.add(it) }
        }
        override fun close() { closes++; throw OutOfMemoryError("Synthetic close failure") }
    }

    private class Fixture : Closeable {
        private val context = InstrumentationRegistry.getInstrumentation().targetContext
        val instance = DocumentService().also {
            ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
                .apply { isAccessible = true }.invoke(it, context)
        }
        private val service = IAllDocService.Stub.asInterface(instance.onBind(Intent()))
        val workers = field(instance, "workers") as ThreadPoolExecutor
        private val closers = field(instance, "closers") as ThreadPoolExecutor
        private val errors = ConcurrentLinkedQueue<Throwable>()
        private val source = File.createTempFile("service-memory-", ".md", context.cacheDir).apply { writeText("Small document") }
        var input: FileDescriptor? = null
            private set
        init {
            for (pool in listOf(workers, closers)) {
                val original = pool.threadFactory
                pool.threadFactory = ThreadFactory { work -> original.newThread(work).apply {
                    uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, error -> errors.add(error) }
                } }
            }
        }
        fun currentSessions(): List<Any> {
            val sessions = field(instance, "sessions") as Set<*>
            return synchronized(sessions) { sessions.filterNotNull() }
        }
        fun open(expected: String = "ready", observe: (Bundle) -> Unit = {}): IAllDocSession {
            val settled = CountDownLatch(1)
            val status = AtomicReference<Bundle>()
            val callback = object : IAllDocCallback.Stub() {
                override fun onState(value: Bundle) { observe(value); status.set(value); settled.countDown() }
            }
            val descriptor = ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)
            input = descriptor.fileDescriptor
            val session = service.open(descriptor, "fixture.md", "text/markdown", "", callback)
            assertTrue("Open did not settle: $errors", settled.await(10, TimeUnit.SECONDS))
            assertEquals(status.get().getString("error"), expected, status.get().getString("state"))
            return session
        }
        fun read(session: IAllDocSession, format: String): ByteArray {
            val descriptor = session.read(17, format, 0, 64, "original", true)
            try {
                // Fixtures fit in the pipe: wait for writer closure so a broken worker cannot hang the test.
                val poll = StructPollfd().apply { fd = descriptor.fileDescriptor; events = 0 }
                val deadline = SystemClock.elapsedRealtime() + 5_000
                while (true) {
                    Os.poll(arrayOf(poll), 100)
                    if (poll.revents.toInt() and OsConstants.POLLHUP != 0) break
                    check(SystemClock.elapsedRealtime() < deadline) { "Output writer did not close: $errors" }
                }
                return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
            } finally { descriptor.close() }
        }
        fun drainClosers() {
            val done = CountDownLatch(1)
            closers.execute { done.countDown() }
            assertTrue("Cleanup did not drain", done.await(5, TimeUnit.SECONDS))
        }
        override fun close() {
            try {
                instance.onDestroy()
                assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS))
                assertTrue(closers.awaitTermination(10, TimeUnit.SECONDS))
                assertTrue("Unexpected worker failures: $errors", errors.isEmpty())
            } finally { source.delete() }
        }
    }

    companion object {
        private fun field(owner: Any, name: String): Any? =
            owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
    }
}
