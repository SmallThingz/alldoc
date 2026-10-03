package org.smallthingz.alldoc

import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalViewConfiguration

/**
 * Pure, non-consuming recognition of a deliberate single tap.
 *
 * An accepted release only schedules a reveal. [advanceTime] must be called after the platform's
 * double-tap window before revealing anything. Another press, wheel scroll, cancellation, or a
 * blocked timer cancels that pending reveal. The result is a reveal request, never a visibility
 * toggle. Keep visibility and its accessible/keyboard alternatives outside this recognizer.
 */
internal class ImmersiveGestureGate(
    private val touchSlop: Float,
    private val longPressTimeoutMillis: Long,
    private val doubleTapTimeoutMillis: Long,
) {
    init {
        require(touchSlop.isFinite() && touchSlop >= 0f)
        require(longPressTimeoutMillis > 0)
        require(doubleTapTimeoutMillis >= 0)
    }

    private data class Press(
        val downMillis: Long,
        val x: Float,
        val y: Float,
        val followsTap: Boolean,
        var cancelled: Boolean,
    )

    private var press: Press? = null
    private var lastStationaryUpMillis: Long? = null

    var revealAtMillis: Long? = null
        private set

    /** Snapshot [scrollInProgress] before the document can stop its own fling. */
    fun onDown(
        timeMillis: Long,
        x: Float,
        y: Float,
        pointerCount: Int = 1,
        childConsumed: Boolean = false,
        scrollInProgress: Boolean = false,
        excluded: Boolean = false,
    ) {
        revealAtMillis = null
        if (press != null) {
            // A second pointer cannot replace the first and become a fresh tap.
            press?.cancelled = true
            return
        }
        val followsTap =
            lastStationaryUpMillis?.let {
                val gap = timeMillis - it
                gap >= 0 && gap < doubleTapTimeoutMillis
            } ?: false
        press =
            Press(
                timeMillis,
                x,
                y,
                followsTap,
                pointerCount != 1 ||
                    childConsumed ||
                    scrollInProgress ||
                    excluded ||
                    !x.isFinite() ||
                    !y.isFinite(),
            )
    }

    fun onMove(
        timeMillis: Long,
        x: Float,
        y: Float,
        pointerCount: Int = 1,
        childConsumed: Boolean = false,
        scrollInProgress: Boolean = false,
        excluded: Boolean = false,
    ) {
        val current = press ?: return
        val dx = x - current.x
        val dy = y - current.y
        if (
            pointerCount != 1 ||
                childConsumed ||
                scrollInProgress ||
                excluded ||
                !x.isFinite() ||
                !y.isFinite() ||
                timeMillis < current.downMillis ||
                timeMillis - current.downMillis >= longPressTimeoutMillis ||
                dx * dx + dy * dy > touchSlop * touchSlop
        ) {
            current.cancelled = true
        }
    }

    fun onUp(
        timeMillis: Long,
        x: Float,
        y: Float,
        pointerCount: Int = 1,
        childConsumed: Boolean = false,
        scrollInProgress: Boolean = false,
        excluded: Boolean = false,
    ) {
        onMove(timeMillis, x, y, pointerCount, childConsumed, scrollInProgress, excluded)
        val current = press ?: return
        press = null
        if (current.cancelled) return

        // Suppress every tap in a double/triple-tap cluster, including its last release.
        lastStationaryUpMillis = timeMillis
        if (!current.followsTap) revealAtMillis = timeMillis + doubleTapTimeoutMillis
    }

    /** Returns true exactly once for an eligible single tap, after all ambiguity has expired. */
    fun advanceTime(timeMillis: Long, blocked: Boolean = false): Boolean {
        if (blocked) {
            cancel()
            return false
        }
        val deadline = revealAtMillis ?: return false
        if (press != null || timeMillis < deadline) return false
        revealAtMillis = null
        return true
    }

    /** Cancel the entire sequence, for wheel input, pointer cancellation, or viewer disposal. */
    fun cancel() {
        press = null
        revealAtMillis = null
        lastStationaryUpMillis = null
    }
}

/** Already-consumed Initial events include Compose's synthetic CANCEL release. Never opt them in. */
internal fun rejectsImmersiveTapConsumption(
    consumedAtInitial: Boolean,
    consumedAtFinal: Boolean,
    allowChildHandledTap: Boolean,
): Boolean = consumedAtInitial || (consumedAtFinal && !allowChildHandledTap)

/**
 * Observes document input without consuming it or installing a competing click/selection handler.
 *
 * Put this on the document surface, not on an ancestor containing overlay buttons. Child-consumed
 * events are ineligible by default: fields, selection, links and document controls keep their normal
 * behavior. [allowChildHandledTap] may opt document-only surfaces into simultaneous caret placement
 * and reveal; it must remain false for actionable controls. The owner must exclude active selection
 * or action-mode regions using [isExcluded] when enabling that opt-in. All movement, duration and
 * multi-pointer checks still apply. [isExcluded] receives local coordinates. Provide actual
 * scrolling/flinging state through [isScrollInProgress]; a tap which
 * merely stops a fling must not reveal controls. [resetKey] should identify the open document/mode.
 *
 * This has no semantics or focus side effects. The owner must provide an accessible Show/Hide
 * controls action and a keyboard alternative without intercepting editor typing.
 */
internal fun Modifier.immersiveTapObserver(
    enabled: Boolean = true,
    resetKey: Any? = Unit,
    isScrollInProgress: () -> Boolean = { false },
    allowChildHandledTap: () -> Boolean = { false },
    isExcluded: (Offset) -> Boolean = { false },
    onReveal: () -> Unit,
): Modifier = composed {
    val currentScrollInProgress by rememberUpdatedState(isScrollInProgress)
    val currentAllowChildHandledTap by rememberUpdatedState(allowChildHandledTap)
    val currentExcluded by rememberUpdatedState(isExcluded)
    val currentReveal by rememberUpdatedState(onReveal)
    val configuration = LocalViewConfiguration.current
    pointerInput(
        enabled,
        resetKey,
        configuration.touchSlop,
        configuration.longPressTimeoutMillis,
        configuration.doubleTapTimeoutMillis,
    ) {
        if (!enabled) return@pointerInput
        val gate =
            ImmersiveGestureGate(
                configuration.touchSlop,
                configuration.longPressTimeoutMillis,
                configuration.doubleTapTimeoutMillis,
            )
        var pointer: PointerId? = null
        try {
            awaitPointerEventScope {
                var latestEventMillis = 0L
                while (true) {
                    val deadline = gate.revealAtMillis
                    val event =
                        if (deadline == null) {
                            awaitPointerEvent(PointerEventPass.Initial)
                        } else {
                            // Pointer injection and coroutine test time need not share Android's
                            // SystemClock. Keep deadlines in event time and let this scope's timeout
                            // measure the remaining duration; never mix independent clock origins.
                            val remaining = deadline - latestEventMillis
                            if (remaining <= 0) null
                            else {
                                withTimeoutOrNull(remaining) {
                                    awaitPointerEvent(PointerEventPass.Initial)
                                }
                            }
                        }
                    if (event == null) {
                        if (deadline != null && gate.advanceTime(deadline, currentScrollInProgress())) {
                            currentReveal()
                        }
                        continue
                    }
                    latestEventMillis =
                        event.changes.maxOfOrNull { it.uptimeMillis } ?: latestEventMillis

                    // Initial sees a running fling before the child handles its stopping down.
                    val scrollingAtInitial = currentScrollInProgress()
                    val consumedAtInitial = event.changes.any { it.isConsumed }
                    val finalEvent = awaitPointerEvent(PointerEventPass.Final)
                    if (event.type == PointerEventType.Scroll) {
                        gate.cancel()
                        pointer = null
                        continue
                    }
                    val freshDown =
                        if (pointer == null && event.changes.none { it.previousPressed }) {
                            event.changes.firstOrNull { it.pressed }
                        } else null
                    if (freshDown != null) pointer = freshDown.id
                    val change = finalEvent.changes.firstOrNull { it.id == pointer } ?: continue
                    val participating = finalEvent.changes.count { it.pressed || it.previousPressed }
                    val allowChildTap = currentAllowChildHandledTap()
                    val consumed =
                        rejectsImmersiveTapConsumption(
                            consumedAtInitial,
                            finalEvent.changes.any { it.isConsumed },
                            allowChildTap,
                        )
                    // A caret tap can start bring-into-view while the child handles UP, especially
                    // with large fonts or an IME. It is not a user scroll: the pointer remained
                    // stationary and no motion existed before that release reached the child.
                    // Existing motion, every DOWN, and every MOVE retain the normal motion veto.
                    val childHandledRelease =
                        allowChildTap && change.previousPressed && !change.pressed
                    val scrolling =
                        scrollingAtInitial || (!childHandledRelease && currentScrollInProgress())
                    val position = change.position
                    val excluded =
                        position.x < 0 ||
                            position.y < 0 ||
                            position.x > size.width ||
                            position.y > size.height ||
                            currentExcluded(position)
                    if (freshDown != null) {
                        gate.onDown(
                            change.uptimeMillis,
                            position.x,
                            position.y,
                            participating,
                            consumed,
                            scrolling,
                            excluded,
                        )
                    } else if (change.previousPressed && !change.pressed) {
                        gate.onUp(
                            change.uptimeMillis,
                            position.x,
                            position.y,
                            participating,
                            consumed,
                            scrolling,
                            excluded,
                        )
                        pointer = null
                    } else {
                        gate.onMove(
                            change.uptimeMillis,
                            position.x,
                            position.y,
                            participating,
                            consumed,
                            scrolling,
                            excluded,
                        )
                    }
                }
            }
        } finally {
            gate.cancel()
        }
    }
}
