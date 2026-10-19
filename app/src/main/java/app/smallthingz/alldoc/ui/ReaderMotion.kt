package app.smallthingz.alldoc.ui

import android.animation.ValueAnimator
import android.os.Build
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Requested fullscreen state, independent of temporarily revealed system bars. */
internal val LocalReaderImmersive = staticCompositionLocalOf { false }

/** Deferred edits check the current transition owner, including before recomposition. */
internal val LocalReaderInputAllowed = compositionLocalOf<() -> Boolean> { { true } }

/** Dialogs own a separate window; inherit reader chrome without polling or another Back handler. */
@Composable
internal fun ReaderModalWindow() {
    val view = LocalView.current
    val immersive = LocalReaderImmersive.current
    val lightBars = !LocalAppTheme.current.isDark(isSystemInDarkTheme())
    DisposableEffect(view, immersive, lightBars) {
        val window = generateSequence(view.parent) { it.parent }
            .filterIsInstance<DialogWindowProvider>().firstOrNull()?.window
        if (window == null) return@DisposableEffect onDispose { }
        val decor = window.decorView
        val controller = WindowCompat.getInsetsController(window, decor)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = when {
                !immersive -> WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                Build.VERSION.SDK_INT >= 30 -> WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                else -> WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        val applyChrome = Runnable {
            controller.isAppearanceLightStatusBars = lightBars
            controller.isAppearanceLightNavigationBars = lightBars
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (immersive) controller.hide(WindowInsetsCompat.Type.systemBars())
            else controller.show(WindowInsetsCompat.Type.systemBars())
        }
        val focus = ViewTreeObserver.OnWindowFocusChangeListener { focused ->
            if (focused) { decor.removeCallbacks(applyChrome); decor.post(applyChrome) }
        }
        val attach = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { decor.post(applyChrome) }
            override fun onViewDetachedFromWindow(v: View) { decor.removeCallbacks(applyChrome) }
        }
        val observer = decor.viewTreeObserver
        observer.addOnWindowFocusChangeListener(focus)
        decor.addOnAttachStateChangeListener(attach)
        applyChrome.run()
        onDispose {
            decor.removeCallbacks(applyChrome)
            decor.removeOnAttachStateChangeListener(attach)
            if (observer.isAlive) observer.removeOnWindowFocusChangeListener(focus)
            else decor.viewTreeObserver.removeOnWindowFocusChangeListener(focus)
        }
    }
}

/** Transition ownership belongs to one visible surface, with no work scheduled while settled. */
@Stable
internal class ReaderMotionState(initial: Float, private val scope: CoroutineScope) {
    val openness = Animatable(initial)
    var onClosed: () -> Unit = {}
    var closing by mutableStateOf(false)
        private set
    private var revision = 0L
    private var settling: Job? = null

    private suspend fun settle(target: Float) {
        if (!ValueAnimator.areAnimatorsEnabled()) openness.snapTo(target)
        else openness.animateTo(target, tween((220 * kotlin.math.abs(target - openness.value))
            .roundToInt().coerceAtLeast(1), easing = FastOutSlowInEasing))
    }
    fun reveal() {
        closing = false
        val owner = ++revision
        settling?.cancel()
        settling = scope.launch { if (owner == revision) settle(1f) }
    }
    fun dismiss() {
        closing = true
        val owner = ++revision
        settling?.cancel()
        settling = scope.launch {
            settle(0f)
            if (owner == revision) onClosed()
        }
    }
    suspend fun follow(events: Flow<BackEventCompat>, stillEnabled: () -> Boolean) {
        // A committed exit keeps ownership. PredictiveBackHandler still requires collection;
        // cancellation of this later gesture must not enter the original owner's recovery path.
        if (closing) {
            events.collect { }
            return
        }
        closing = false
        val owner = ++revision
        settling?.cancel()
        settling = null
        val start = openness.value
        var committed = false
        try {
            events.collect { event ->
                if (owner == revision && stillEnabled()) openness.snapTo(start * (1f - event.progress.coerceIn(0f, 1f)))
            }
            if (owner == revision && stillEnabled()) {
                committed = true
                closing = true
                // AndroidX cancels the previous callback when another Back starts. Once
                // committed, the composition owns the exit until completion or supersession.
                val finish = scope.launch {
                    settle(0f)
                    if (owner == revision) {
                        if (stillEnabled()) onClosed() else {
                            closing = false
                            settle(1f)
                        }
                    }
                }
                settling = finish
                finish.join()
            } else if (owner == revision) {
                closing = false
                settle(1f)
            }
        } catch (cancelled: CancellationException) {
            // Only cancellation before commit returns the surface. A committed exit has its own job.
            if (owner == revision && !committed) {
                closing = false
                settling = scope.launch { settle(1f) }
            }
            throw cancelled
        }
    }
}

@Composable
private fun rememberReaderMotion(onClosed: () -> Unit, animateEntrance: Boolean = true): ReaderMotionState {
    val scope = rememberCoroutineScope()
    var entered by rememberSaveable { mutableStateOf(false) }
    val motion = remember { ReaderMotionState(if (entered || !animateEntrance) 1f else 0f, scope) }
    SideEffect { motion.onClosed = onClosed }
    LaunchedEffect(motion) { entered = true }
    return motion
}

@Composable
private fun ReaderBackGesture(motion: ReaderMotionState, enabled: Boolean) {
    val currentEnabled by rememberUpdatedState(enabled)
    PredictiveBackHandler(enabled = enabled) { events -> motion.follow(events) { currentEnabled } }
}

/** Forward and backward travel share the same progress, including cancelled predictive gestures. */
@Composable
internal fun ReaderMotionLayer(
    active: Boolean, onBack: () -> Unit, modifier: Modifier = Modifier, fromTop: Boolean = false, visible: Boolean = true,
    animateEntrance: Boolean = true, backdrop: @Composable (ReaderMotionState) -> Unit = {},
    viewportModifier: Modifier? = null,
    content: @Composable (back: () -> Unit) -> Unit,
) {
    val motion = rememberReaderMotion(onBack, animateEntrance)
    ReaderBackGesture(motion, active && visible)
    LaunchedEffect(visible) { if (visible) motion.reveal() else motion.dismiss() }
    backdrop(motion)
    // Back eligibility can be disabled for a nested reader; exit ownership is independent.
    val parentInputAllowed = LocalReaderInputAllowed.current
    val currentVisible by rememberUpdatedState(visible)
    val inputAllowed = remember(parentInputAllowed, motion) {
        { parentInputAllowed() && currentVisible && !motion.closing }
    }
    val moving = modifier.readerInput(inputAllowed()).graphicsLayer {
        val hidden = 1f - motion.openness.value
        if (fromTop) translationY = -size.height * hidden else translationX = size.width * hidden
        alpha = 1f - 0.12f * hidden
    }
    // A stationary viewport clips foreground travel while the backdrop remains full-window.
    CompositionLocalProvider(LocalReaderInputAllowed provides inputAllowed) {
        if (viewportModifier == null) Box(moving) { content(motion::dismiss) }
        else Box(viewportModifier) { Box(moving) { content(motion::dismiss) } }
    }
}

/** Fullscreen Back restores chrome without treating text mode as another screen. */
@Composable
internal fun ReaderFullscreenLayer(
    active: Boolean, fullscreen: Boolean, onExit: () -> Unit, content: @Composable () -> Unit,
) {
    val scope = key(fullscreen) { rememberCoroutineScope() }
    val motion = remember(fullscreen) { ReaderMotionState(1f, scope) }
    SideEffect { motion.onClosed = onExit }
    ReaderBackGesture(motion, active)
    Box(Modifier.fillMaxSize().graphicsLayer {
        val scale = .97f + .03f * motion.openness.value
        scaleX = scale
        scaleY = scale
    }) { content() }
}

/** Blocking and destructive prompts share the edge-anchored sheet and one Back owner. */
@Composable
internal fun ReaderDialog(
    title: String, onDismiss: () -> Unit,
    buttons: @Composable RowScope.(dismiss: () -> Unit) -> Unit,
    content: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit,
) = ReaderSheet(title, onDismiss, buttons = buttons, content = content)

/** One bounded scroller keeps the title, fields and actions reachable in short/IME windows. */
@Composable
internal fun ReaderDialogContent(
    title: String, onDismiss: () -> Unit, modifier: Modifier = Modifier,
    buttons: (@Composable RowScope.(dismiss: () -> Unit) -> Unit)?,
    showTitle: Boolean = true,
    content: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit,
) {
    Column(modifier.readerBackground(raised = true).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (showTitle) Text(title, style = MaterialTheme.typography.titleLarge)
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) { content(onDismiss) }
        if (buttons != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically) { buttons(onDismiss) }
    }
}
