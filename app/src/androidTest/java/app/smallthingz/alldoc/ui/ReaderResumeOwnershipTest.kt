package app.smallthingz.alldoc.ui

import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderResumeOwnershipTest {
    @Test fun cancelledRestoreCannotClearNewerPendingAttempt() = ReaderPersistenceTest().Fixture().use { fixture ->
        fixture.launch(width = 0)
        fixture.await { it.compoundSettingsReady }
        fixture.stateBarrier()
        val field = ReaderModel::class.java.getDeclaredField("StateWorker").apply { isAccessible = true }
        val worker = field.get(null) as ExecutorService
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        val releaseSecond = CountDownLatch(1)
        var firstBlocker: Future<*>? = null
        var secondBlocker: Future<*>? = null
        var firstAttempt: Job? = null
        try {
            firstBlocker = worker.submit {
                firstEntered.countDown()
                check(releaseFirst.await(5, TimeUnit.SECONDS)) { "First restore barrier timed out" }
            }
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS))
            fixture.act { reader ->
                val owner = requireNotNull(reader.viewModelScope.coroutineContext[Job])
                val before = owner.children.toSet()
                reader.restoreLastDocument()
                firstAttempt = (owner.children.toSet() - before).single()
                assertTrue(reader.restoringLastDocument)
            }
            // Queue this after attempt 1's read, but before attempt 2's read.
            secondBlocker = worker.submit {
                secondEntered.countDown()
                check(releaseSecond.await(5, TimeUnit.SECONDS)) { "Second restore barrier timed out" }
            }
            fixture.act { reader ->
                reader.cancelAutomaticResume()
                assertFalse("Cancellation releases only the old pending state", reader.restoringLastDocument)
                reader.restoreLastDocument()
                assertTrue("A newer restore owns pending state", reader.restoringLastDocument)
            }
            releaseFirst.countDown()
            assertTrue(secondEntered.await(5, TimeUnit.SECONDS))
            runBlocking { withTimeout(5_000) { requireNotNull(firstAttempt).join() } }
            fixture.act { reader ->
                assertTrue("The completed old finally must not clear the blocked newer restore", reader.restoringLastDocument)
                assertFalse(reader.readerVisible)
                assertNull(reader.info)
                assertFalse(reader.loading)
            }
            releaseSecond.countDown()
            fixture.await { !it.restoringLastDocument }
            fixture.act { reader ->
                assertFalse("An empty saved state finishes at Home", reader.readerVisible)
                assertNull(reader.documentHash)
                assertNull(reader.info)
                assertFalse(reader.loading)
                assertNull(reader.error)
            }
        } finally {
            releaseFirst.countDown()
            releaseSecond.countDown()
            firstBlocker?.get(5, TimeUnit.SECONDS)
            secondBlocker?.get(5, TimeUnit.SECONDS)
        }
    }
}
