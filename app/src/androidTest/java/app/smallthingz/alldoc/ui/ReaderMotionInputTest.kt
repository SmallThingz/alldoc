package app.smallthingz.alldoc.ui

import android.animation.ValueAnimator
import android.os.SystemClock
import android.view.KeyEvent
import android.view.Window
import androidx.activity.BackEventCompat
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Activity-local focus, keys and semantics; no system accessibility or global input. */
@RunWith(AndroidJUnit4::class)
class ReaderMotionInputTest {
    // Scope keyboard eligibility to these fixtures, without changing Android's touch mode.
    private val keyboardMode = object : InputModeManager {
        override val inputMode = InputMode.Keyboard
        override fun requestInputMode(inputMode: InputMode) = inputMode == InputMode.Keyboard
    }

    private fun await(message: String, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 5000
        while (!condition()) {
            check(SystemClock.uptimeMillis() < end) { message }
            SystemClock.sleep(16)
        }
    }

    private fun nodes(node: SemanticsNode): Sequence<SemanticsNode> = sequence {
        yield(node)
        for (child in node.children) yieldAll(nodes(child))
    }

    private fun labelled(node: SemanticsNode, label: String): Boolean =
        node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true ||
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true ||
            node.children.any { labelled(it, label) }

    // Public children include replaced descendants in an unmerged tree. Input assertions
    // must stop at clearAndSetSemantics boundaries, including recursive label lookup.
    private fun exposedNodes(node: SemanticsNode): Sequence<SemanticsNode> = sequence {
        yield(node)
        if (!node.config.isClearingSemantics) {
            for (child in node.children) yieldAll(exposedNodes(child))
        }
    }

    private fun exposedLabelled(node: SemanticsNode, label: String): Boolean =
        node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true ||
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true ||
            (!node.config.isClearingSemantics && node.children.any { exposedLabelled(it, label) })

    @Test @SdkSuppress(minSdkVersion = 29)
    fun escapeFromFocusedSheetFieldClosesOnceAndRejectsClosingActivation() {
        assumeTrue("An exit interval requires animation", ValueAnimator.areAnimatorsEnabled())
        launchReaderTestActivity().use { scenario ->
            val open = mutableStateOf(true)
            val entry = mutableStateOf("3")
            val closed = AtomicInteger()
            val activated = AtomicInteger()
            val fieldFocused = AtomicBoolean()
            val actionFocused = AtomicBoolean()
            val window = AtomicReference<Window>()
            val fieldFocus = FocusRequester()
            val actionFocus = FocusRequester()
            scenario.onActivity { activity -> activity.setContent {
                MaterialTheme {
                    if (open.value) ReaderSheet("Jump fixture", onDismiss = {
                        closed.incrementAndGet(); open.value = false
                    }) {
                        val view = LocalView.current
                        SideEffect {
                            window.set(generateSequence(view.parent) { it.parent }
                                .filterIsInstance<DialogWindowProvider>().firstOrNull()?.window)
                        }
                        CompositionLocalProvider(LocalInputModeManager provides keyboardMode) {
                            OutlinedTextField(entry.value, { entry.value = it }, singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                                keyboardActions = KeyboardActions(onGo = { activated.incrementAndGet() }),
                                modifier = Modifier.focusRequester(fieldFocus)
                                    .onFocusChanged { fieldFocused.set(it.isFocused) })
                            TextButton(onClick = { activated.incrementAndGet() },
                                modifier = Modifier.focusRequester(actionFocus)
                                    .onFocusChanged { actionFocused.set(it.isFocused) }) { Text("Apply fixture") }
                        }
                    }
                }
            } }
            await("The sheet did not acquire its owned window") {
                var ready = false
                scenario.onActivity { activity ->
                    activity.verifyTestDisplay()
                    ready = window.get()?.decorView?.hasWindowFocus() == true
                }
                ready
            }
            val owned = requireNotNull(window.get())
            fun key(code: Int) {
                owned.decorView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                owned.decorView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
            }
            await("The visible open action did not accept keyboard focus") {
                var accepted = false
                scenario.onActivity { activity ->
                    activity.verifyTestDisplay()
                    assertSame(owned, window.get())
                    assertEquals(activity.window.decorView.display?.displayId, owned.decorView.display?.displayId)
                    val visible = activity.testSemantics(includeDialogs = true).asSequence().flatMap(::exposedNodes).any { node ->
                        val bounds = node.boundsInWindow
                        node.config.getOrNull(SemanticsActions.OnClick)?.action != null && exposedLabelled(node, "Apply fixture") &&
                            bounds.width > 0f && bounds.height > 0f && bounds.right > 0f && bounds.bottom > 0f &&
                            bounds.left < owned.decorView.width && bounds.top < owned.decorView.height
                    }
                    if (visible && owned.decorView.hasWindowFocus())
                        accepted = actionFocused.get() || actionFocus.requestFocus()
                }
                accepted && actionFocused.get()
            }
            scenario.onActivity {
                key(KeyEvent.KEYCODE_ENTER)
                assertEquals("The open action must accept real keys before testing rejection", 1, activated.get())
                activated.set(0)
                assertTrue(fieldFocus.requestFocus())
            }
            await("The page-like text field did not take keyboard focus") { fieldFocused.get() }
            scenario.onActivity {
                key(KeyEvent.KEYCODE_ENTER)
                assertEquals("The focused field must accept Go before testing rejection", 1, activated.get())
                activated.set(0)
            }
            val attempted = AtomicBoolean()
            val failure = AtomicReference<Throwable>()
            val deadline = SystemClock.uptimeMillis() + 5_000
            var observer: Runnable? = null
            try {
                scenario.onActivity { activity ->
                    assertTrue(owned.decorView.hasWindowFocus())
                    assertTrue(fieldFocused.get())
                    val check = object : Runnable {
                        override fun run() {
                            if (SystemClock.uptimeMillis() >= deadline) return
                            try {
                                activity.verifyTestDisplay()
                                val exposed = activity.testSemantics(includeDialogs = true).asSequence()
                                    .flatMap(::exposedNodes).toList()
                                val pane = exposed.any { it.config.getOrNull(SemanticsProperties.PaneTitle) == "Jump fixture" }
                                val action = exposed.any {
                                    it.config.getOrNull(SemanticsActions.OnClick)?.action != null && exposedLabelled(it, "Apply fixture")
                                }
                                if (!attempted.get() && closed.get() == 0 && pane && !action) {
                                    actionFocus.requestFocus()
                                    key(KeyEvent.KEYCODE_ENTER)
                                    key(KeyEvent.KEYCODE_ESCAPE)
                                    assertEquals("A closing sheet activated its retained action", 0, activated.get())
                                    attempted.set(true)
                                }
                                if (!attempted.get() && closed.get() == 0 && owned.decorView.isAttachedToWindow)
                                    owned.decorView.postOnAnimation(this)
                            } catch (error: Throwable) { failure.set(error) }
                        }
                    }
                    observer = check
                    owned.decorView.postOnAnimation(check)
                    key(KeyEvent.KEYCODE_ESCAPE)
                    // No frame or focus transfer: Enter targets the same field immediately after Escape.
                    key(KeyEvent.KEYCODE_ENTER)
                    assertEquals("Escape must block Go before recomposition", 0, activated.get())
                }
                await("Escape did not gate input while the sheet was closing") {
                    failure.get()?.let { throw it }
                    attempted.get()
                }
                await("Escape did not dismiss the sheet exactly once") { closed.get() == 1 }
                scenario.onActivity {
                    assertFalse(open.value)
                    assertEquals("Escape must not change the page input", "3", entry.value)
                    assertEquals(0, activated.get())
                    assertEquals(1, closed.get())
                }
            } finally {
                scenario.onActivity { observer?.let(owned.decorView::removeCallbacks) }
            }
        }
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    fun closingLayerRejectsFocusedRowActivationBeforeExitCompletes() {
        assumeTrue("An exit interval requires animation", ValueAnimator.areAnimatorsEnabled())
        launchReaderTestActivity().use { scenario ->
            val catalog = mutableStateOf(true)
            val reader = mutableStateOf(false)
            val opened = AtomicInteger()
            val closed = AtomicInteger()
            val focused = AtomicBoolean()
            val coordinates = AtomicReference<LayoutCoordinates>()
            val requester = FocusRequester()
            scenario.onActivity { activity -> activity.setContent {
                CompositionLocalProvider(LocalInputModeManager provides keyboardMode) { MaterialTheme {
                    Text("Home")
                    if (catalog.value) ReaderMotionLayer(active = !reader.value, onBack = {
                        // The route stack only pops the current destination, like ReaderScreen.
                        closed.incrementAndGet()
                        if (!reader.value) catalog.value = false
                    }, animateEntrance = false, modifier = Modifier.width(320.dp).height(360.dp)) { back ->
                        Column {
                            ReaderHeader("Examples", onBack = back)
                            TextButton(onClick = { opened.incrementAndGet(); reader.value = true },
                                modifier = Modifier.focusRequester(requester).onFocusChanged { focused.set(it.isFocused) }
                                    .onGloballyPositioned { coordinates.set(it) }) { Text("Open example") }
                            LaunchedEffect(Unit) { requester.requestFocus() }
                        }
                    }
                    if (reader.value) Text("Reader route")
                } }
            } }
            await("The visible layer did not give its row keyboard focus") { focused.get() && coordinates.get() != null }
            var origin = 0f
            var width = 0f
            scenario.onActivity { activity ->
                origin = coordinates.get().positionInWindow().x
                width = coordinates.get().size.width.toFloat()
                val back = activity.testSemantics().asSequence().flatMap(::nodes).first { node ->
                    node.config.getOrNull(SemanticsActions.OnClick)?.action != null && labelled(node, "Back")
                }
                // Semantic activation of the header does not transfer the row's keyboard focus.
                assertTrue(back.config[SemanticsActions.OnClick].action!!.invoke())
            }
            val attempted = AtomicBoolean()
            await("The test did not observe a partial exit") {
                scenario.onActivity { activity ->
                    val current = coordinates.get()
                    if (!attempted.get() && current.isAttached && closed.get() == 0) {
                        val distance = current.positionInWindow().x - origin
                        if (distance > 1f && distance < width - 1f) {
                            // Attempt real local keys in the same UI turn as the partial-exit observation.
                            requester.requestFocus()
                            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
                            attempted.set(true)
                        }
                    }
                }
                attempted.get()
            }
            assertEquals("A closing route accepted another row action", 0, opened.get())
            await("Accepted Back did not finish") { closed.get() == 1 }
            scenario.onActivity {
                assertFalse("The dismissed catalog remained in the route stack", catalog.value)
                assertFalse(reader.value)
            }
            SystemClock.sleep(100)
            assertEquals("The exit committed more than once", 1, closed.get())

            // Cover both ReaderSheet's action dismissal and Material's own dismiss action.
            for (wrapperDismiss in listOf(true, false)) {
                val sheetOpen = mutableStateOf(true)
                val sheetClosed = AtomicInteger()
                val chosen = AtomicInteger()
                val sheetWindow = AtomicReference<Window>()
                val sheetCoordinates = AtomicReference<LayoutCoordinates>()
                val sheetFocused = AtomicBoolean()
                val sheetRequester = FocusRequester()
                scenario.onActivity { activity -> activity.setContent {
                    MaterialTheme {
                        if (sheetOpen.value) ReaderSheet("Reading view", onDismiss = {
                            sheetClosed.incrementAndGet(); sheetOpen.value = false
                        }) { dismiss ->
                            val view = LocalView.current
                            SideEffect {
                                sheetWindow.set(generateSequence(view.parent) { it.parent }
                                    .filterIsInstance<DialogWindowProvider>().firstOrNull()?.window)
                            }
                            CompositionLocalProvider(LocalInputModeManager provides keyboardMode) {
                                Column(Modifier.onGloballyPositioned { sheetCoordinates.set(it) }) {
                                    TextButton(onClick = { chosen.set(1); dismiss() }) { Text("Choose Text") }
                                    TextButton(onClick = { chosen.set(2) },
                                        modifier = Modifier.focusRequester(sheetRequester)
                                            .onFocusChanged { sheetFocused.set(it.isFocused) }) { Text("Choose Present") }
                                }
                            }
                        }
                    }
                } }
                var lastY = Float.NaN
                var stableSince = SystemClock.uptimeMillis()
                var readiness = "No readiness sample"
                var focusRequested = false
                try {
                    await("The owned sheet did not settle with its initial keyboard focus") {
                        var ready = false
                        scenario.onActivity { activity ->
                            activity.verifyTestDisplay()
                            val window = sheetWindow.get()
                            val row = sheetCoordinates.get()
                            if (window != null && row != null && row.isAttached && window.decorView.hasWindowFocus()) {
                                assertEquals(activity.window.decorView.display?.displayId, window.decorView.display?.displayId)
                                if (!focusRequested) {
                                    focusRequested = true
                                    assertTrue("The attached keyboard fixture rejected its only focus request", sheetRequester.requestFocus())
                                }
                                if (sheetFocused.get()) {
                                    val y = row.positionInWindow().y
                                    if (!lastY.isFinite() || kotlin.math.abs(y - lastY) > .5f) stableSince = SystemClock.uptimeMillis()
                                    lastY = y
                                    ready = SystemClock.uptimeMillis() - stableSince >= 100
                                }
                            }
                            val attached = row?.isAttached == true
                            val sampleY = if (attached) row!!.positionInWindow().y else Float.NaN
                            val exposed = activity.testSemantics(includeDialogs = true).asSequence().flatMap(::exposedNodes).any {
                                it.config.getOrNull(SemanticsActions.OnClick)?.action != null && exposedLabelled(it, "Choose Present")
                            }
                            readiness = "wrapper=$wrapperDismiss window=${window != null} attached=$attached " +
                                "nativeFocus=${window?.decorView?.hasWindowFocus()} composeFocus=${sheetFocused.get()} focusRequested=$focusRequested " +
                                "y=$sampleY lastY=$lastY stableMs=${SystemClock.uptimeMillis() - stableSince} " +
                                "activityDisplay=${activity.window.decorView.display?.displayId} " +
                                "sheetDisplay=${window?.decorView?.display?.displayId} choiceExposed=$exposed"
                        }
                        ready
                    }
                } catch (failure: IllegalStateException) {
                    throw IllegalStateException("${failure.message}; $readiness", failure)
                }
                val ownedWindow = requireNotNull(sheetWindow.get())
                val triedSheet = AtomicBoolean()
                val frameFailure = AtomicReference<Throwable>()
                val hideObservation = AtomicReference("No hide frame was observed")
                var hideObserver: Runnable? = null
                try {
                    scenario.onActivity { activity ->
                        assertTrue(ownedWindow.decorView.hasWindowFocus())
                        assertTrue(sheetFocused.get())
                        // Prove this target and window accept Enter while open before testing rejection.
                        ownedWindow.decorView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                        ownedWindow.decorView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
                        assertEquals("The open keyboard fixture did not activate its focused choice", 2, chosen.get())
                        chosen.set(0)
                        val current = activity.testSemantics(includeDialogs = true).asSequence().flatMap(::exposedNodes).toList()
                        assertTrue("The open sheet did not publish its retained pane",
                            current.any { it.config.getOrNull(SemanticsProperties.PaneTitle) == "Reading view" })
                        assertTrue("The open sheet did not publish its choice action",
                            current.any { it.config.getOrNull(SemanticsActions.OnClick)?.action != null && exposedLabelled(it, "Choose Present") })
                        val observeUntil = SystemClock.uptimeMillis() + 5_000
                        var frames = 0
                        // onActivity waits for main-loop idle. Observe the composed closing gate
                        // while this owned Dialog is still open, independently of hide geometry.
                        val observer = object : Runnable {
                            override fun run() {
                                if (SystemClock.uptimeMillis() >= observeUntil) return
                                try {
                                    activity.verifyTestDisplay()
                                    frames++
                                    val row = sheetCoordinates.get()
                                    val attached = row?.isAttached == true
                                    val nativeFocus = ownedWindow.decorView.hasWindowFocus()
                                    val sheetSemantics = activity.testSemantics(includeDialogs = true).asSequence().flatMap(::exposedNodes).toList()
                                    val panePresent = sheetSemantics.any {
                                        it.config.getOrNull(SemanticsProperties.PaneTitle) == "Reading view"
                                    }
                                    val choiceExposed = sheetSemantics.any {
                                        it.config.getOrNull(SemanticsActions.OnClick)?.action != null && exposedLabelled(it, "Choose Present")
                                    }
                                    hideObservation.set("wrapper=$wrapperDismiss frames=$frames attached=$attached " +
                                        "nativeFocus=$nativeFocus closed=${sheetClosed.get()} " +
                                        "panePresent=$panePresent choiceExposed=$choiceExposed")
                                    if (!triedSheet.get() && attached && sheetClosed.get() == 0 && nativeFocus &&
                                        panePresent && !choiceExposed) {
                                        assertSame(ownedWindow, sheetWindow.get())
                                        sheetRequester.requestFocus()
                                        // Dialog input belongs to its own window, not the Activity window.
                                        ownedWindow.decorView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                                        ownedWindow.decorView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
                                        assertEquals("A closing sheet changed the accepted reading choice",
                                            if (wrapperDismiss) 1 else 0, chosen.get())
                                        assertFalse("A closing sheet still published the second choice",
                                            activity.testSemantics(includeDialogs = true).asSequence().flatMap(::exposedNodes).any {
                                                it.config.getOrNull(SemanticsActions.OnClick)?.action != null && exposedLabelled(it, "Choose Present")
                                            })
                                        triedSheet.set(true)
                                    }
                                    if (!triedSheet.get() && sheetClosed.get() == 0 &&
                                        ownedWindow.decorView.isAttachedToWindow && SystemClock.uptimeMillis() < observeUntil) {
                                        ownedWindow.decorView.postOnAnimation(this)
                                    }
                                } catch (failure: Throwable) {
                                    frameFailure.set(failure)
                                }
                            }
                        }
                        hideObserver = observer
                        ownedWindow.decorView.postOnAnimation(observer)
                        if (wrapperDismiss) {
                            val choice = current.first { it.config.getOrNull(SemanticsActions.OnClick)?.action != null &&
                                exposedLabelled(it, "Choose Text") }
                            assertTrue(choice.config[SemanticsActions.OnClick].action!!.invoke())
                        } else {
                            val dismiss = current.first { it.config.getOrNull(SemanticsActions.Dismiss)?.action != null }
                            assertTrue(dismiss.config[SemanticsActions.Dismiss].action!!.invoke())
                        }
                    }
                    await("The closing input gate was not observed before the sheet closed (wrapper=$wrapperDismiss)") {
                        frameFailure.get()?.let { throw it }
                        triedSheet.get()
                    }
                } catch (failure: IllegalStateException) {
                    throw IllegalStateException("${failure.message}; ${hideObservation.get()}", failure)
                } finally {
                    scenario.onActivity { hideObserver?.let(ownedWindow.decorView::removeCallbacks) }
                }
                await("The sheet did not close exactly once") { sheetClosed.get() == 1 && !sheetOpen.value }
                SystemClock.sleep(100)
                assertEquals("The sheet dismissal committed more than once", 1, sheetClosed.get())
            }
        }
    }

    @Test fun cancelledAndSupersededBackRestoreInputOwnership() {
        assumeTrue("An interrupted exit requires animation", ValueAnimator.areAnimatorsEnabled())
        launchReaderTestActivity().use { scenario ->
            val state = AtomicReference<ReaderMotionState>()
            val scope = AtomicReference<CoroutineScope>()
            val closed = AtomicInteger()
            val opened = AtomicInteger()
            val composedClosing = AtomicBoolean()
            val focused = AtomicBoolean()
            val requester = FocusRequester()
            val clock = BroadcastFrameClock()
            val holdFrames = AtomicBoolean()
            scenario.onActivity { activity -> activity.setContent {
                val owner = rememberCoroutineScope()
                val motionScope = remember(owner) {
                    val liveClock = requireNotNull(owner.coroutineContext[MonotonicFrameClock])
                    val motionClock = object : MonotonicFrameClock {
                        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R =
                            (if (holdFrames.get()) clock else liveClock).withFrameNanos(onFrame)
                    }
                    // Retain the composition Job; only this fixture's frame source changes.
                    CoroutineScope(owner.coroutineContext + motionClock)
                }
                val motion = remember { ReaderMotionState(1f, motionScope) }
                val closing = motion.closing
                SideEffect {
                    state.set(motion); scope.set(owner); motion.onClosed = { closed.incrementAndGet() }
                    composedClosing.set(closing)
                }
                // The first test covers the real Layer. This surface exercises its shared input
                // gate with a directly controlled predictive flow and a composition-owned state.
                CompositionLocalProvider(LocalInputModeManager provides keyboardMode) { MaterialTheme { Column(Modifier.readerInput(!closing)) {
                    TextButton(onClick = { opened.incrementAndGet() },
                        modifier = Modifier.focusRequester(requester).onFocusChanged { focused.set(it.isFocused) }) {
                        Text("Open example")
                    }
                    LaunchedEffect(Unit) { requester.requestFocus() }
                } } }
            } }
            await("Motion owner and focused action were not composed") { state.get() != null && focused.get() }
            val motion = state.get()
            scenario.onActivity {
                motion.dismiss()
                assertTrue("Dismiss must publish closing state synchronously", motion.closing)
            }
            val reversed = AtomicBoolean()
            await("The exit did not reach an intermediate frame") {
                scenario.onActivity {
                    if (!reversed.get() && motion.openness.value in .01f..<.99f) {
                        motion.reveal()
                        assertFalse(motion.closing)
                        reversed.set(true)
                    }
                }
                reversed.get()
            }
            await("The superseded exit did not reopen") { !motion.closing && motion.openness.value == 1f }
            SystemClock.sleep(250)
            assertEquals("A superseded exit committed", 0, closed.get())

            val gesture = AtomicReference<Job>()
            scenario.onActivity {
                gesture.set(scope.get().launch {
                    motion.follow(flow {
                        emit(BackEventCompat(0f, 100f, .4f, BackEventCompat.EDGE_LEFT))
                        awaitCancellation()
                    }) { true }
                })
            }
            await("Predictive Back progress must not acquire the programmatic exit gate") { !motion.closing && kotlin.math.abs(motion.openness.value - .6f) < .001f }
            scenario.onActivity { gesture.get().cancel() }
            await("Cancelled Back did not restore input ownership") { !motion.closing && motion.openness.value == 1f }
            assertEquals("Cancelled Back committed", 0, closed.get())
            // Hold the motion owner's clock, so its independent committed job is deterministic
            // without pausing recomposition or changing device animation settings.
            var frameNanos = 0L
            fun motionStatus() = "closing=${motion.closing}, composedClosing=${composedClosing.get()}, " +
                "openness=${motion.openness.value}, heldWaiters=${clock.hasAwaiters}, " +
                "callbackCompleted=${gesture.get()?.isCompleted}, closed=${closed.get()}, opened=${opened.get()}"
            fun beginCommit(phase: String, enabled: () -> Boolean = { true }) {
                scenario.onActivity {
                    holdFrames.set(true)
                    gesture.set(scope.get().launch(clock) { motion.follow(flow { }) { enabled() } })
                }
                try {
                    // A previous reveal can still have a waiter at openness1. Require this commit's gate.
                    await("Committed Back did not reach its residual settle") { motion.closing && clock.hasAwaiters }
                    await("The closing input gate did not recompose") { motion.closing && composedClosing.get() }
                } catch (failure: IllegalStateException) {
                    throw IllegalStateException("$phase: ${failure.message}; ${motionStatus()}", failure)
                }
            }
            fun advanceUntil(message: String, condition: () -> Boolean) {
                await(message) {
                    scenario.onActivity {
                        if (clock.hasAwaiters) {
                            frameNanos += 1_000_000_000L
                            clock.sendFrame(frameNanos)
                        }
                    }
                    condition()
                }
            }
            fun activateRow(expected: Int, blocked: Boolean, phase: String) {
                scenario.onActivity { activity ->
                    // Read current semantics after recomposition; never call a saved OnClick.
                    val hasAction = activity.testSemantics().asSequence().flatMap(::exposedNodes).any { node ->
                        node.config.getOrNull(SemanticsActions.OnClick)?.action != null && exposedLabelled(node, "Open example")
                    }
                    requester.requestFocus()
                    activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                    activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
                    assertEquals("$phase: The row accepted input with the wrong owner; ${motionStatus()}", expected, opened.get())
                    assertEquals("$phase: The current accessibility action disagrees with input ownership; ${motionStatus()}", !blocked, hasAction)
                }
            }
            fun assertRecovered(expected: Int) {
                await("The returned surface did not restore its composed input gate") {
                    !motion.closing && !composedClosing.get() && motion.openness.value == 1f
                }
                assertEquals("A reversed Back committed", 0, closed.get())
                activateRow(expected, blocked = false, phase = "recovered-$expected")
            }

            val enabled = AtomicBoolean(true)
            beginCommit("lost-eligibility", enabled::get)
            activateRow(0, blocked = true, phase = "before-eligibility-loss")
            assertTrue("Completed gesture collection must relinquish action ownership", motion.closing)
            assertEquals(0, closed.get())
            enabled.set(false)
            advanceUntil("Lost Back eligibility did not clear the gate before returning") { !motion.closing }
            assertTrue("The gate was only restored after the return completed", motion.openness.value < 1f)
            advanceUntil("The ineligible committed gesture did not reopen") { gesture.get().isCompleted }
            assertRecovered(1)

            beginCommit("explicit-reveal")
            scenario.onActivity { motion.reveal() }
            await("Superseded committed Back did not finish") { gesture.get().isCompleted }
            advanceUntil("Explicit reveal did not restore the surface") { motion.openness.value == 1f }
            assertRecovered(2)

            beginCommit("final-commit")
            activateRow(2, blocked = true, phase = "committed-before-callback-cancel")
            // Match AndroidX ordering: cancel the old callback before starting the next flow.
            scenario.onActivity { gesture.get().cancel() }
            await("The replaced Back callback did not cancel") { gesture.get().isCompleted }
            assertTrue("Replacing the callback reopened its committed exit", motion.closing)
            assertEquals("The held committed exit completed without a frame", 0, closed.get())
            activateRow(2, blocked = true, phase = "committed-after-callback-cancel")
            val laterGesture = AtomicReference<Job>()
            val laterCollected = AtomicBoolean()
            scenario.onActivity {
                laterGesture.set(scope.get().launch {
                    motion.follow(flow {
                        emit(BackEventCompat(0f, 100f, .4f, BackEventCompat.EDGE_LEFT))
                        laterCollected.set(true)
                        awaitCancellation()
                    }) { true }
                })
            }
            try {
                await("The later Back flow was not collected") { laterCollected.get() }
                assertTrue("A later Back gesture reopened the committed exit", motion.closing)
                activateRow(2, blocked = true, phase = "later-flow-collected")
                scenario.onActivity { laterGesture.get().cancel() }
                await("The later Back gesture did not cancel") { laterGesture.get().isCompleted }
                assertTrue("Cancelling a later gesture restored the closing owner", motion.closing)
                activateRow(2, blocked = true, phase = "later-flow-cancelled")
            } finally {
                scenario.onActivity { laterGesture.get()?.cancel() }
            }
            advanceUntil("The composition-owned committed Back did not close") { closed.get() == 1 }
            assertEquals("Accepted predictive Back must close exactly once", 1, closed.get())
            assertEquals("The committed surface did not finish its exit", 0f, motion.openness.value, 0f)
            assertTrue("A closed owner must remain noninteractive until disposal/reveal", motion.closing)
            SystemClock.sleep(100)
            assertEquals("The committed gesture closed more than once", 1, closed.get())
        }
    }
    @Test fun fullscreenReentryCancelsThePreviousCommittedExit() {
        assumeTrue("A pending fullscreen exit requires animation", ValueAnimator.areAnimatorsEnabled())
        launchReaderTestActivity().use { scenario ->
            val fullscreen = mutableStateOf(true)
            val composedFullscreen = AtomicReference<Boolean>()
            val exits = AtomicInteger()
            val contentCreated = AtomicInteger()
            val contentDisposed = AtomicInteger()
            val contentIdentity = AtomicReference<Any>()
            val effects = AtomicReference<BroadcastFrameClock>()
            val recomposer = AtomicReference<Recomposer>()
            val owner = AtomicReference<CoroutineScope>()
            val runner = AtomicReference<Job>()
            try {
                scenario.onActivity { activity ->
                    val scope = CoroutineScope(AndroidUiDispatcher.CurrentThread + Job())
                    val composition = Recomposer(scope.coroutineContext)
                    owner.set(scope); recomposer.set(composition)
                    runner.set(scope.launch { composition.runRecomposeAndApplyChanges() })
                    activity.setContent(parent = composition) {
                        val fixtureScope = rememberCoroutineScope()
                        SideEffect {
                            effects.set(fixtureScope.coroutineContext[MonotonicFrameClock] as BroadcastFrameClock)
                        }
                        MaterialTheme {
                            ReaderFullscreenLayer(active = fullscreen.value, fullscreen = fullscreen.value,
                                onExit = { exits.incrementAndGet(); fullscreen.value = false }) {
                                val token = remember { Any() }
                                DisposableEffect(Unit) {
                                    contentCreated.incrementAndGet()
                                    onDispose { contentDisposed.incrementAndGet() }
                                }
                                SideEffect {
                                    contentIdentity.set(token); composedFullscreen.set(fullscreen.value)
                                }
                                TextButton(onClick = { fullscreen.value = !fullscreen.value }) {
                                    Text(if (fullscreen.value) "Exit full screen" else "Full screen")
                                }
                            }
                        }
                    }
                }
                fun settled() = effects.get()?.hasAwaiters == false &&
                    recomposer.get().currentState.value == Recomposer.State.Idle
                await("The fullscreen fixture did not settle") { composedFullscreen.get() == true && settled() }
                val token = contentIdentity.get()
                scenario.onActivity { activity ->
                    activity.verifyTestDisplay()
                    assertTrue(activity.window.decorView.hasWindowFocus())
                    // Pause effects only: real recomposition can still replace the keyed owner.
                    recomposer.get().pauseCompositionFrameClock()
                    activity.onBackPressedDispatcher.onBackPressed()
                }
                await("The accepted fullscreen Back never awaited an animation frame") { effects.get().hasAwaiters }
                assertEquals("The paused exit completed before replacement", 0, exits.get())
                fun toggle(label: String, expected: Boolean) {
                    scenario.onActivity { activity ->
                        val action = activity.testSemantics().asSequence().flatMap(::exposedNodes).firstOrNull {
                            it.config.getOrNull(SemanticsActions.OnClick)?.action != null && exposedLabelled(it, label)
                        }
                        assertNotNull("The current fullscreen toggle is missing: $label", action)
                        assertTrue(action!!.config[SemanticsActions.OnClick].action!!.invoke())
                    }
                    await("The fullscreen toggle did not compose: $expected") {
                        composedFullscreen.get() == expected && recomposer.get().currentState.value == Recomposer.State.Idle
                    }
                }
                toggle("Exit full screen", false)
                toggle("Full screen", true)
                assertSame("Toggling fullscreen recreated reader content", token, contentIdentity.get())
                assertEquals(1, contentCreated.get())
                assertEquals(0, contentDisposed.get())
                // The clock is still paused: an uncancelled old exit cannot finish naturally.
                await("The superseded fullscreen owner retained its paused animation") { settled() }
                scenario.onActivity {
                    assertTrue("An old Back exit closed the new fullscreen session", fullscreen.value)
                    assertEquals("A replaced fullscreen owner called onExit", 0, exits.get())
                    recomposer.get().resumeCompositionFrameClock()
                }
                // Positive control: the current owner must still handle and complete Back.
                scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                await("The new fullscreen owner did not complete Back") {
                    exits.get() == 1 && composedFullscreen.get() == false && settled()
                }
                assertSame(token, contentIdentity.get())
                assertEquals(1, contentCreated.get())
                assertEquals(0, contentDisposed.get())
                assertEquals("Fullscreen Back closed more than once", 1, exits.get())
            } finally {
                scenario.onActivity { recomposer.get()?.cancel(); owner.get()?.cancel() }
                await("The fullscreen test recomposer did not stop") { runner.get()?.isCompleted != false }
            }
        }
    }
}
