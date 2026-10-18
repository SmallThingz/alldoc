package app.smallthingz.alldoc.ui

import androidx.activity.BackEventCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class ReaderMotionTest {
    @Test fun laterBackFlowsCannotReplaceAnExplicitDismiss() = runBlocking {
        val state = ReaderMotionState(1f, this)
        var closes = 0
        var collected = 0
        state.onClosed = { closes++ }
        state.dismiss()
        assertTrue(state.closing)
        assertEquals(0, closes)
        // The composition-owned dismiss is queued on this scope until yield(). Neither
        // completing nor cancelling a later flow may acquire its revision or recovery path.
        for (cancel in listOf(false, true)) {
            try {
                state.follow(flow {
                    emit(BackEventCompat(0f, 0f, .6f, BackEventCompat.EDGE_LEFT))
                    collected++
                    assertTrue("A later gesture reopened the committed exit", state.closing)
                    assertEquals(1f, state.openness.value, 0f)
                    if (cancel) throw CancellationException("Later gesture cancelled")
                }) { true }
            } catch (_: CancellationException) { assertTrue(cancel) }
            assertTrue(state.closing)
            assertEquals(0, closes)
        }
        assertEquals("Both later flows must still be collected", 2, collected)
        yield()
        assertEquals("The original dismiss must close exactly once", 1, closes)
        assertEquals(0f, state.openness.value, 0f)
        assertTrue(state.closing)
    }

    @Test fun committedBackSurvivesCallbackCancellation() = runBlocking {
        val state = ReaderMotionState(1f, this)
        var closes = 0
        state.onClosed = { closes++ }
        val callback = launch(start = CoroutineStart.UNDISPATCHED) {
            state.follow(flow { }) { true }
        }
        assertTrue("The callback must be waiting for its queued committed exit", callback.isActive)
        assertTrue(state.closing)
        assertEquals(0, closes)
        // PredictiveBackHandler cancels this callback before starting the next gesture.
        // Its composition-owned exit must remain queued and close only once.
        callback.cancel()
        callback.join()
        yield()
        assertTrue(callback.isCancelled)
        assertTrue("Callback cancellation reopened a committed Back", state.closing)
        assertEquals(0f, state.openness.value, 0f)
        assertEquals(1, closes)
    }

    @Test fun disabledGestureCancellationRestoresItsSurfaceWithoutClosing() = runBlocking {
        val state = ReaderMotionState(1f, this)
        var enabled = true
        var closes = 0
        state.onClosed = { closes++ }
        try {
            state.follow(flow {
                emit(BackEventCompat(0f, 0f, .6f, BackEventCompat.EDGE_LEFT))
                assertEquals(.4f, state.openness.value, .001f)
                enabled = false
                throw CancellationException("Overlay took back priority")
            }) { enabled }
        } catch (_: CancellationException) { }
        yield()
        assertEquals(1f, state.openness.value, .001f)
        assertEquals(0, closes)
    }
    @Test fun disabledGestureCompletionRestoresItsSurfaceWithoutClosing() = runBlocking {
        val state = ReaderMotionState(1f, this)
        var enabled = true
        var closes = 0
        state.onClosed = { closes++ }
        state.follow(flow {
            emit(BackEventCompat(0f, 0f, .7f, BackEventCompat.EDGE_RIGHT))
            enabled = false
        }) { enabled }
        assertEquals(1f, state.openness.value, .001f)
        assertEquals(0, closes)
    }
    @Test fun losingBackPriorityDuringCommitRollsBack() = runBlocking {
        val state = ReaderMotionState(1f, this)
        var checks = 0
        var closes = 0
        state.onClosed = { closes++ }
        state.follow(flow { emit(BackEventCompat(0f, 0f, .5f, BackEventCompat.EDGE_LEFT)) }) { ++checks < 3 }
        assertEquals(1f, state.openness.value, .001f)
        assertEquals(0, closes)
    }
}
