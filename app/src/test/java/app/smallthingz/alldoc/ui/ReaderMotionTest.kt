package app.smallthingz.alldoc.ui

import androidx.activity.BackEventCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class ReaderMotionTest {
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
