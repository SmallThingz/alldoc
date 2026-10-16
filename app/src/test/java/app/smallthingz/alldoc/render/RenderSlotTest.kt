package app.smallthingz.alldoc.render

import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class RenderSlotTest {
    @Test fun waitingRendererAcquiresReleasedSlot() {
        val slot = Semaphore(0, true)
        val entered = CountDownLatch(1)
        val result = FutureTask {
            acquireRenderSlot(slot) { entered.countDown() }
            slot.availablePermits()
        }
        val thread = Thread(result)
        thread.start()
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            slot.release()
            assertEquals(0, result.get(2, TimeUnit.SECONDS).toInt())
        } finally { thread.interrupt(); thread.join(2000) }
    }

    @Test fun cancellationStopsWaitWithoutConsumingFuturePermit() {
        val slot = Semaphore(0, true)
        val entered = CountDownLatch(1)
        val cancelled = AtomicBoolean(false)
        val result = FutureTask {
            try {
                acquireRenderSlot(slot) {
                    entered.countDown()
                    if (cancelled.get()) throw InterruptedIOException("cancelled")
                }
                false
            } catch (_: InterruptedIOException) { true }
        }
        val thread = Thread(result)
        thread.start()
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            cancelled.set(true)
            assertTrue(result.get(2, TimeUnit.SECONDS))
            slot.release()
            assertEquals(1, slot.availablePermits())
        } finally { thread.interrupt(); thread.join(2000) }
    }
}
