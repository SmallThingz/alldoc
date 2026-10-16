package app.smallthingz.alldoc.ui

import android.view.ViewTreeObserver
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Stable
internal class ReaderControls(private val visibility: MutableState<Boolean>, private val pinned: State<Boolean>) {
    private var pointerHolds by mutableIntStateOf(0)
    var focused by mutableStateOf(false)
    var activity by mutableIntStateOf(0)
        private set
    val shown get() = visibility.value || pinned.value || focused
    val held get() = pinned.value || focused || pointerHolds > 0
    fun reveal() { visibility.value = true; activity++ }
    fun hide() { if (!held) visibility.value = false }
    fun toggle() { if (shown) hide() else reveal() }
    fun scroll(forward: Boolean) {
        if (!pinned.value && !focused && visibility.value == forward) {
            visibility.value = !forward
            activity++
        }
    }
    fun press() { pointerHolds++ }
    fun release() { pointerHolds--; activity++ }
}

@Composable
internal fun rememberReaderControls(fullscreen: Boolean, pinned: Boolean): ReaderControls {
    val context = LocalContext.current
    val view = LocalView.current
    val accessibility = remember(context) { context.getSystemService(AccessibilityManager::class.java) }
    var exploring by remember(accessibility) { mutableStateOf(accessibility?.isTouchExplorationEnabled == true) }
    var windowFocused by remember(view) { mutableStateOf(view.hasWindowFocus()) }
    DisposableEffect(accessibility, view) {
        val touch = AccessibilityManager.TouchExplorationStateChangeListener { exploring = it }
        val focus = ViewTreeObserver.OnWindowFocusChangeListener { windowFocused = it }
        accessibility?.addTouchExplorationStateChangeListener(touch)
        view.viewTreeObserver.addOnWindowFocusChangeListener(focus)
        onDispose {
            accessibility?.removeTouchExplorationStateChangeListener(touch)
            if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnWindowFocusChangeListener(focus)
        }
    }
    val visibility = rememberSaveable(fullscreen) { mutableStateOf(true) }
    val pin = rememberUpdatedState(pinned || exploring)
    val controls = remember(visibility) { ReaderControls(visibility, pin) }
    val timeout = LocalAccessibilityManager.current?.calculateRecommendedTimeoutMillis(
        originalTimeoutMillis = 5000, containsIcons = true, containsText = true, containsControls = true) ?: 5000L
    LaunchedEffect(fullscreen, controls.shown, controls.held, controls.activity, windowFocused, timeout) {
        if (fullscreen && controls.shown && !controls.held && windowFocused && timeout != Long.MAX_VALUE) {
            delay(timeout)
            controls.hide()
        }
    }
    return controls
}

internal fun Modifier.readingArea(fullscreen: Boolean, controls: ReaderControls): Modifier = semantics {
    contentDescription = "Reading area"
    stateDescription = if (fullscreen) "Full screen" else "Windowed"
    customActions = when {
        !controls.shown -> listOf(CustomAccessibilityAction("Show reading controls") { controls.reveal(); true })
        !controls.held -> listOf(CustomAccessibilityAction("Hide reading controls") { controls.hide(); true })
        else -> emptyList()
    }
}

/** Observe without consuming: scrolling, text selection and PDF zoom retain their gestures. */
internal fun Modifier.readingGestures(controls: ReaderControls, enabled: Boolean, toggle: Boolean = true): Modifier =
    pointerInput(controls, enabled, toggle) {
        if (!enabled) return@pointerInput
        coroutineScope {
            val scope = this
            var pending: Job? = null
            var lastUp = Long.MIN_VALUE
            try {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val doubleTap = lastUp != Long.MIN_VALUE && down.uptimeMillis - lastUp <= viewConfiguration.doubleTapTimeoutMillis
                    pending?.cancel()
                    pending = null
                    controls.press()
                    var tap = toggle && !doubleTap && !down.isConsumed
                    var upAt = down.uptimeMillis
                    try {
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Final)
                            if (event.changes.size != 1) tap = false
                            event.changes.forEach { change ->
                                if (change.isConsumed || (change.position - down.position).getDistance() > viewConfiguration.touchSlop) tap = false
                                upAt = change.uptimeMillis
                            }
                        } while (event.changes.any { it.pressed })
                    } finally { controls.release() }
                    if (upAt - down.uptimeMillis >= viewConfiguration.longPressTimeoutMillis) tap = false
                    lastUp = if (tap) upAt else Long.MIN_VALUE
                    if (tap) pending = scope.launch {
                        delay(viewConfiguration.doubleTapTimeoutMillis)
                        controls.toggle()
                    }
                }
            } finally { pending?.cancel() }
        }
    }
