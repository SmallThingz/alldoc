package org.smallthingz.alldoc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** No Android, rendering, wall-clock sleeps or coroutine scheduler needed for recognition tests. */
class GestureGateTest {
    private fun gate() = ImmersiveGestureGate(8f, 500, 300)

    @Test
    fun stationaryTapRevealsOnceOnlyAfterDoubleTapWindow() {
        val gate = gate()
        gate.onDown(0, 20f, 30f)
        gate.onUp(70, 20f, 30f)
        assertEquals(370L, gate.revealAtMillis)
        assertFalse(gate.advanceTime(369))
        assertTrue(gate.advanceTime(370))
        assertFalse(gate.advanceTime(1_000))
    }

    @Test
    fun movementInsideTouchSlopStillAllowsDeliberateTap() {
        val gate = gate()
        gate.onDown(0, 20f, 30f)
        gate.onMove(20, 24f, 34f)
        gate.onUp(70, 25f, 34f)
        assertTrue(gate.advanceTime(370))
    }

    @Test
    fun verticalHorizontalAndDiagonalDragNeverRevealEvenAfterReturningToOrigin() {
        for ((x, y) in listOf(0f to 9f, 9f to 0f, 6f to 6f)) {
            val gate = gate()
            gate.onDown(0, 0f, 0f)
            gate.onMove(20, x, y)
            gate.onMove(40, 0f, 0f)
            gate.onUp(70, 0f, 0f)
            assertFalse(gate.advanceTime(1_000))
        }
    }

    @Test
    fun displacementFirstSeenOnReleaseCancelsTap() {
        val gate = gate()
        gate.onDown(0, 0f, 0f)
        gate.onUp(70, 12f, 0f)
        assertFalse(gate.advanceTime(1_000))
    }

    @Test
    fun secondPointerCancelsUntilAllPointersAreReleased() {
        val gate = gate()
        gate.onDown(0, 0f, 0f)
        gate.onMove(20, 0f, 0f, pointerCount = 2)
        gate.onMove(30, 0f, 0f, pointerCount = 1)
        gate.onUp(70, 0f, 0f)
        assertFalse(gate.advanceTime(1_000))
    }

    @Test
    fun extraDownCannotReplaceFirstPointer() {
        val gate = gate()
        gate.onDown(0, 0f, 0f)
        gate.onDown(20, 10f, 10f)
        gate.onUp(70, 10f, 10f)
        assertFalse(gate.advanceTime(1_000))
    }

    @Test
    fun simultaneousPinchDownNeverBecomesTap() {
        val gate = gate()
        gate.onDown(0, 0f, 0f, pointerCount = 2)
        gate.onUp(70, 0f, 0f)
        assertFalse(gate.advanceTime(1_000))
    }

    @Test
    fun longPressAndSelectionHoldNeverReveal() {
        for (duration in listOf(500L, 800L)) {
            val gate = gate()
            gate.onDown(0, 0f, 0f)
            gate.onUp(duration, 0f, 0f)
            assertFalse(gate.advanceTime(duration + 300))
        }
    }

    @Test
    fun doubleTapNeverFlashesControlsOnEitherRelease() {
        val gate = gate()
        gate.onDown(0, 0f, 0f)
        gate.onUp(70, 0f, 0f)
        gate.onDown(200, 0f, 0f)
        assertNull(gate.revealAtMillis)
        assertFalse(gate.advanceTime(370))
        gate.onUp(400, 0f, 0f)
        assertFalse(gate.advanceTime(1_000))
    }

    @Test
    fun tripleTapCannotTreatLastReleaseAsSingleTap() {
        val gate = gate()
        for (start in listOf(0L, 150L, 300L)) {
            gate.onDown(start, 0f, 0f)
            gate.onUp(start + 50, 0f, 0f)
        }
        assertFalse(gate.advanceTime(1_000))
    }

    @Test
    fun freshTapAfterDoubleTapWindowCanReveal() {
        val gate = gate()
        gate.onDown(0, 0f, 0f)
        gate.onUp(50, 0f, 0f)
        gate.onDown(150, 0f, 0f)
        gate.onUp(200, 0f, 0f)
        gate.onDown(600, 0f, 0f)
        gate.onUp(650, 0f, 0f)
        assertTrue(gate.advanceTime(950))
    }

    @Test
    fun tapThatStopsFlingIsRejectedEvenWhenScrollHasStoppedBeforeRelease() {
        val gate = gate()
        gate.onDown(0, 0f, 0f, scrollInProgress = true)
        gate.onUp(70, 0f, 0f, scrollInProgress = false)
        assertFalse(gate.advanceTime(1_000))
    }

    @Test
    fun scrollingDuringPressOrDelayedRevealCancels() {
        val gate = gate()
        gate.onDown(0, 0f, 0f)
        gate.onMove(20, 0f, 0f, scrollInProgress = true)
        gate.onUp(70, 0f, 0f)
        assertFalse(gate.advanceTime(370))
        gate.onDown(1_000, 0f, 0f)
        gate.onUp(1_070, 0f, 0f)
        assertFalse(gate.advanceTime(1_370, blocked = true))
        assertFalse(gate.advanceTime(2_000))
    }

    @Test
    fun childCaretSelectionLinkAndToolbarInputWinAtAnyPhase() {
        for (phase in listOf("down", "move", "up")) {
            val gate = gate()
            gate.onDown(0, 0f, 0f, childConsumed = phase == "down")
            gate.onMove(20, 0f, 0f, childConsumed = phase == "move")
            gate.onUp(70, 0f, 0f, childConsumed = phase == "up")
            assertFalse(gate.advanceTime(1_000))
        }
    }

    @Test
    fun excludedRegionWinsAtAnyPhase() {
        for (phase in listOf("down", "move", "up")) {
            val gate = gate()
            gate.onDown(0, 0f, 0f, excluded = phase == "down")
            gate.onMove(20, 0f, 0f, excluded = phase == "move")
            gate.onUp(70, 0f, 0f, excluded = phase == "up")
            assertFalse(gate.advanceTime(1_000))
        }
    }

    @Test
    fun dragStartingWithinDoubleTapWindowCancelsEarlierSingleTap() {
        val gate = gate()
        gate.onDown(0, 0f, 0f)
        gate.onUp(50, 0f, 0f)
        gate.onDown(150, 0f, 0f)
        gate.onMove(200, 0f, 40f)
        gate.onUp(250, 0f, 40f)
        assertFalse(gate.advanceTime(1_000))
    }

    @Test
    fun cancellationOrDisposalCancelsHeldAndPendingGestures() {
        val gate = gate()
        gate.onDown(0, 0f, 0f)
        gate.cancel()
        gate.onUp(50, 0f, 0f)
        assertFalse(gate.advanceTime(350))
        gate.onDown(1_000, 0f, 0f)
        gate.onUp(1_050, 0f, 0f)
        gate.cancel()
        assertFalse(gate.advanceTime(1_350))
    }

    @Test
    fun invalidCoordinatesAndBackwardsEventTimeCannotReveal() {
        val gate = gate()
        gate.onDown(100, 0f, 0f)
        gate.onUp(99, 0f, 0f)
        assertFalse(gate.advanceTime(1_000))
        gate.onDown(1_100, 0f, 0f)
        gate.onUp(1_150, Float.NaN, 0f)
        assertFalse(gate.advanceTime(2_000))
    }

    @Test
    fun caretCoexistenceOptInAllowsOnlyConsumptionAddedByDocumentChild() {
        assertFalse(rejectsImmersiveTapConsumption(false, true, true))
        assertTrue(rejectsImmersiveTapConsumption(false, true, false))
        assertFalse(rejectsImmersiveTapConsumption(false, false, false))
        val gate = gate()
        val caretConsumed = rejectsImmersiveTapConsumption(false, true, true)
        gate.onDown(0, 0f, 0f, childConsumed = caretConsumed)
        gate.onUp(50, 0f, 0f, childConsumed = caretConsumed)
        assertTrue(gate.advanceTime(350))
    }

    @Test
    fun syntheticCancelAndOuterGestureOwnershipStillWinWithCaretOptIn() {
        assertTrue(rejectsImmersiveTapConsumption(true, true, true))
        val gate = gate()
        gate.onDown(0, 0f, 0f)
        gate.onUp(
            50,
            0f,
            0f,
            childConsumed = rejectsImmersiveTapConsumption(true, true, true),
        )
        assertFalse(gate.advanceTime(350))
    }
}
