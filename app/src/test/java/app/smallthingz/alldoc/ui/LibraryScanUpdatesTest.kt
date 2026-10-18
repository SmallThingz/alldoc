package app.smallthingz.alldoc.ui

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test

class LibraryScanUpdatesTest {
    @Test fun committedBatchRefreshesWithoutAnotherProgressCallback() {
        val owner = SupervisorJob()
        val dispatcher = QueuedDispatcher()
        val scope = CoroutineScope(dispatcher + owner)
        var now = 0L
        var catalogRevision = 1
        val visible = mutableListOf<Int>()
        val updates = LibraryScanUpdates(scope, { now }) { visible += catalogRevision }
        try {
            updates.changed()
            assertEquals(listOf(1), visible)
            now = 100
            catalogRevision = 2; updates.changed()
            catalogRevision = 3; updates.changed()
            assertEquals(listOf(1), visible)
            dispatcher.runNext()
            // Enumeration can now spend minutes on unsupported entries without another callback.
            now = 250
            dispatcher.runNext()
            assertEquals(listOf(1, 3), visible)
            assertFalse("No timer should remain after publication", owner.children.any())
        } finally { updates.finish(); scope.cancel() }
    }

    @Test fun stopFlushesCommittedRowsAndLateCallbacksCannotRestartTheTimer() {
        val owner = SupervisorJob()
        val dispatcher = QueuedDispatcher()
        val scope = CoroutineScope(dispatcher + owner)
        var now = 0L
        var catalogRevision = 1
        val visible = mutableListOf<Int>()
        val updates = LibraryScanUpdates(scope, { now }) { visible += catalogRevision }
        try {
            updates.changed()
            now = 100
            catalogRevision = 2; updates.changed()
            updates.finish()
            assertEquals(listOf(1, 2), visible)
            dispatcher.runNext() // Drain the cancelled child without running its delayed refresh.
            // A resumed scan can report zero changes: its cached page must already be invalidated.
            val resumed = LibraryScanUpdates(scope, { now }) { visible += catalogRevision }
            resumed.finish()
            assertEquals(listOf(1, 2), visible)
            catalogRevision = 3; updates.changed()
            updates.finish()
            assertEquals(listOf(1, 2, 3), visible)
            assertFalse("A stopped scan must not schedule another refresh", owner.children.any())
        } finally { updates.finish(); scope.cancel() }
    }

    /** Real delay resumes are queued, so host scheduling cannot race the input assertions. */
    private class QueuedDispatcher : CoroutineDispatcher() {
        private val queue = LinkedBlockingQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queue.add(block) }
        fun runNext() = requireNotNull(queue.poll(5, TimeUnit.SECONDS)) { "Scheduled refresh did not resume" }.run()
    }
}
