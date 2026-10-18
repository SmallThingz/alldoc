package app.smallthingz.alldoc.ui

import android.animation.ValueAnimator
import android.app.Application
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewTreeObserver
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.smallthingz.alldoc.ReaderTestActivity
import app.smallthingz.alldoc.state.CopySettings
import app.smallthingz.alldoc.state.DocumentMetadata
import app.smallthingz.alldoc.state.DocumentState
import app.smallthingz.alldoc.state.LibraryDocument
import app.smallthingz.alldoc.state.StoredDocument
import app.smallthingz.alldoc.theme.ReadingMode
import app.smallthingz.alldoc.theme.ThemeSettings
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Activity-local semantics only. Uses the runner's exclusively owned Activity display. */
@RunWith(AndroidJUnit4::class)
class ReaderHomeInputLifecycleTest {
    private data class Axis(val node: SemanticsNode, val range: ScrollAxisRange, val scroll: (Float, Float) -> Boolean)

    private fun await(message: String, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 5000
        while (!condition()) {
            check(SystemClock.uptimeMillis() < end) { message }
            SystemClock.sleep(16)
        }
    }

    private fun find(node: SemanticsNode, predicate: (SemanticsNode) -> Boolean): SemanticsNode? =
        if (predicate(node)) node else node.children.firstNotNullOfOrNull { find(it, predicate) }

    private fun labelled(node: SemanticsNode, label: String): Boolean =
        node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true ||
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true ||
            node.children.any { labelled(it, label) }

    private fun axis(scenario: ActivityScenario<ReaderTestActivity>, root: AtomicReference<ViewRootForTest>,
        horizontal: Boolean = false, label: String? = null): Axis {
        val result = AtomicReference<Axis>()
        await("Scrollable viewport was not composed") {
            scenario.onActivity {
                val tree = root.get()?.semanticsOwner?.unmergedRootSemanticsNode
                val key = if (horizontal) SemanticsProperties.HorizontalScrollAxisRange else SemanticsProperties.VerticalScrollAxisRange
                tree?.let { find(it) { node ->
                    node.config.getOrNull(key)?.maxValue()?.let { it > 0f } == true &&
                        node.config.getOrNull(SemanticsActions.ScrollBy)?.action != null &&
                        (label == null || labelled(node, label))
                } }?.let { node -> result.set(Axis(node, node.config[key], node.config[SemanticsActions.ScrollBy].action!!)) }
            }
            result.get() != null
        }
        return result.get()
    }

    private fun click(root: AtomicReference<ViewRootForTest>, label: String) {
        val tree = root.get().semanticsOwner.unmergedRootSemanticsNode
        val action = find(tree) { it.config.getOrNull(SemanticsActions.OnClick)?.action != null && labelled(it, label) }
            ?.config?.getOrNull(SemanticsActions.OnClick)?.action
        assertNotNull("Missing action: " + label, action)
        assertTrue(action!!.invoke())
    }

    private fun click(scenario: ActivityScenario<ReaderTestActivity>, root: AtomicReference<ViewRootForTest>, label: String) {
        scenario.onActivity { click(root, label) }
    }

    // Observe without consuming input. The header can consume pixels before the list/grid,
    // so a short text displacement alone does not prove the requested animation is unfinished.
    private class ScrollProbe : NestedScrollConnection {
        private var offeredY = 0f
        var pixels = 0f
            private set
        var onScrolled: (() -> Unit)? = null
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            offeredY = available.y
            return Offset.Zero
        }
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            pixels += available.y - offeredY
            onScrolled?.invoke()
            return Offset.Zero
        }
    }

    private fun nodes(node: SemanticsNode): Sequence<SemanticsNode> = sequence {
        yield(node)
        for (child in node.children) yieldAll(nodes(child))
    }

    private data class Travel(val axis: Axis, val text: SemanticsNode, val origin: Float,
        val before: Float, val target: Float) {
        fun partial(probe: ScrollProbe): Boolean {
            val moved = origin - (text.positionInRoot.y - axis.node.positionInRoot.y)
            if (moved == 0f) return false
            val details = "moved=$moved consumed=${probe.pixels} target=$target range=${axis.range.value()}"
            assertTrue("The same visible text must actually move within its viewport: $details",
                moved > 0f && text.boundsInRoot.intersect(axis.node.boundsInRoot).height > 0f)
            assertTrue("The list/grid must itself scroll: $details", axis.range.value() > before)
            assertTrue("Interrupt before the accepted pixel target, including header consumption: $details",
                probe.pixels > 0f && probe.pixels < target - 1f)
            return true
        }
    }

    private fun travel(scenario: ActivityScenario<ReaderTestActivity>, axis: Axis): Travel {
        val result = AtomicReference<Travel>()
        scenario.onActivity {
            val viewport = axis.node
            val text = nodes(viewport).filter { it.config.getOrNull(SemanticsProperties.Text) != null &&
                it.boundsInRoot.intersect(viewport.boundsInRoot).height > 0f }.maxBy { it.positionInRoot.y }
            result.set(Travel(axis, text, text.positionInRoot.y - viewport.positionInRoot.y,
                axis.range.value(), viewport.boundsInRoot.height * .75f))
            assertTrue("Fixture viewport must have a useful pixel target", result.get().target > 2f)
        }
        return result.get()
    }

    // Arm before starting; interrupt in the same pre-draw callback that sees placed motion.
    // A later ActivityScenario.onActivity call may wait until the animation has already ended.
    private fun interrupt(scenario: ActivityScenario<ReaderTestActivity>, begin: () -> Unit,
        partial: () -> Boolean, cover: () -> Unit) {
        val completed = AtomicReference(false)
        val failure = AtomicReference<Throwable>()
        val view = AtomicReference<View>()
        val observer = AtomicReference<ViewTreeObserver.OnPreDrawListener>()
        fun remove() {
            val tree = view.get()?.viewTreeObserver
            observer.get()?.let { if (tree?.isAlive == true) tree.removeOnPreDrawListener(it) }
        }
        try {
            scenario.onActivity { activity ->
                val owned = activity.window.decorView
                view.set(owned)
                val deadline = SystemClock.uptimeMillis() + 5000
                val listener = ViewTreeObserver.OnPreDrawListener {
                    try {
                        check(SystemClock.uptimeMillis() < deadline) { "Placed motion observation expired" }
                        check(owned.isAttachedToWindow) { "Owned viewport detached before interruption" }
                        if (partial()) { remove(); cover(); completed.set(true) }
                    } catch (error: Throwable) { failure.set(error); remove() }
                    true
                }
                observer.set(listener)
                owned.viewTreeObserver.addOnPreDrawListener(listener)
                begin()
            }
            await("No placed partial motion was available to interrupt") { completed.get() || failure.get() != null }
            failure.get()?.let { throw it }
        } finally { scenario.onActivity { remove() } }
    }

    // Grid scroll state is published before onPostScroll, but placement can be deferred.
    // Interrupt at that synchronous boundary; the caller still verifies placed text after stopping.
    private fun interruptScroll(scenario: ActivityScenario<ReaderTestActivity>, probe: ScrollProbe,
        travel: Travel, cover: () -> Unit) {
        val completed = AtomicReference(false)
        val failure = AtomicReference<Throwable>()
        try {
            scenario.onActivity { activity ->
                val deadline = SystemClock.uptimeMillis() + 5000
                probe.onScrolled = {
                    try {
                        check(SystemClock.uptimeMillis() < deadline) { "Grid scroll observation expired" }
                        check(activity.window.decorView.isAttachedToWindow) { "Owned viewport detached before interruption" }
                        if (travel.axis.range.value() > travel.before) {
                            probe.onScrolled = null
                            assertTrue("Grid must advance before the accepted pixel target, including header consumption: " +
                                "consumed=${probe.pixels} target=${travel.target} range=${travel.axis.range.value()}",
                                probe.pixels > 0f && probe.pixels < travel.target - 1f)
                            cover()
                            completed.set(true)
                        }
                    } catch (error: Throwable) { probe.onScrolled = null; failure.set(error) }
                }
                assertTrue(travel.axis.scroll(0f, travel.target))
            }
            await("No partial grid scroll was available to interrupt") { completed.get() || failure.get() != null }
            failure.get()?.let { throw it }
        } finally { scenario.onActivity { probe.onScrolled = null } }
    }

    // This fixture has two fill-sized, start-aligned pages. Foundation exposes pixel offsets,
    // so normalize against its actual last-page offset rather than treating pixels as page indices.
    private fun pagerPosition(axis: Axis, value: Float = axis.range.value()): Float {
        val end = axis.range.maxValue()
        check(value.isFinite() && end.isFinite() && end > 0f) { "Pager must expose a finite positive scroll range" }
        return value / end
    }

    private fun stoppedValue(axis: Axis): Float {
        SystemClock.sleep(80)
        val stopped = axis.range.value()
        SystemClock.sleep(400)
        assertEquals("An inactive retained viewport continued scrolling", stopped, axis.range.value(), .001f)
        return stopped
    }

    private fun requireMotion() = assumeTrue("Reduced motion has no in-flight scroll to interrupt", ValueAnimator.areAnimatorsEnabled())

    private fun recent() = (1..6).map { number ->
        StoredDocument(number.toString(16).padStart(64, '0'),
            DocumentMetadata("Recent " + number + ".md", "text/markdown", 12), DocumentState(), number.toLong())
    }

    @Test fun homeRecentScrollStopsAndInterruptedTabReturnsFullyAligned() {
        requireMotion()
        launchReaderTestActivity().use { scenario ->
            val active = mutableStateOf(true)
            val root = AtomicReference<ViewRootForTest>()
            val documents = recent()
            val motion = ScrollProbe()
            scenario.onActivity { activity -> activity.setContent {
                val view = LocalView.current
                SideEffect { root.set(view as ViewRootForTest) }
                MaterialTheme { ReaderHome({}, Modifier.width(360.dp).height(320.dp).nestedScroll(motion), recent = documents, active = active.value) }
            } }
            val vertical = axis(scenario, root)
            val recentTravel = travel(scenario, vertical)
            interrupt(scenario, begin = { assertTrue(vertical.scroll(0f, recentTravel.target)) },
                partial = { recentTravel.partial(motion) }, cover = { active.value = false })
            val stopped = stoppedValue(vertical)
            scenario.onActivity { assertTrue("Recent motion completed before cancellation", recentTravel.partial(motion)) }
            assertTrue("The fixture must interrupt before reaching the end", stopped < vertical.range.maxValue() - .01f)
            scenario.onActivity { active.value = true }
            SystemClock.sleep(100)
            assertEquals("Returning replayed the old recent scroll", stopped, vertical.range.value(), .001f)

            val pager = axis(scenario, root, horizontal = true, label = "Document tabs")
            val startX = AtomicReference<Float>()
            scenario.onActivity { startX.set(vertical.node.positionInRoot.x) }
            interrupt(scenario, begin = { click(root, "Library") }, partial = {
                val moved = startX.get() - vertical.node.positionInRoot.x
                if (moved == 0f) false else {
                    assertTrue("The retained Recents page must still be partially visible",
                        moved > 0f && moved < pager.node.boundsInRoot.width &&
                            vertical.node.boundsInRoot.intersect(pager.node.boundsInRoot).width > 0f)
                    assertTrue("Tab must be between its accepted endpoints", pagerPosition(pager) in .0001f..<.9999f)
                    true
                }
            }, cover = { active.value = false })
            await("Inactive pager did not settle its accepted destination") { kotlin.math.abs(pagerPosition(pager) - 1f) < .0001f }
            assertEquals(1f, pagerPosition(pager, stoppedValue(pager)), .0001f)
            scenario.onActivity { active.value = true }
            SystemClock.sleep(100)
            assertEquals("Returning must show a fully aligned Library tab", 1f, pagerPosition(pager), .0001f)
        }
    }

    @Test fun libraryTypePopupStopsScrollWithoutReplayingItAfterReturn() {
        requireMotion()
        launchReaderTestActivity().use { scenario ->
            val active = mutableStateOf(true)
            val root = AtomicReference<ViewRootForTest>()
            val entries: List<LibraryEntry> = (1L..18L).map { id ->
                LibraryEntry.Document(LibraryDocument(id, null, "Library " + id + ".pdf", "application/pdf",
                    1024, "content://home-input/" + id, 1, 1))
            }
            val pages = flowOf(PagingData.from(entries))
            val motion = ScrollProbe()
            scenario.onActivity { activity -> activity.setContent {
                val view = LocalView.current
                SideEffect { root.set(view as ViewRootForTest) }
                val library = pages.collectAsLazyPagingItems()
                MaterialTheme { ReaderHome({}, Modifier.width(360.dp).height(400.dp).nestedScroll(motion), library = library, active = active.value) }
            } }
            val pager = axis(scenario, root, horizontal = true, label = "Document tabs")
            click(scenario, root, "Library")
            await("Library tab did not settle") { kotlin.math.abs(pagerPosition(pager) - 1f) < .0001f }
            scenario.onActivity {
                assertNotNull("Library tab is not selected", find(root.get().semanticsOwner.unmergedRootSemanticsNode) { node ->
                    node.config.getOrNull(SemanticsProperties.Selected) == true && labelled(node, "Library")
                })
            }
            val vertical = axis(scenario, root, label = "Library 1.pdf")
            val libraryTravel = travel(scenario, vertical)
            interruptScroll(scenario, motion, libraryTravel) { click(root, "Document type") }
            val stopped = stoppedValue(vertical)
            scenario.onActivity { assertTrue("Grid motion completed before popup cancellation", libraryTravel.partial(motion)) }
            assertTrue("The fixture must interrupt before reaching the end", stopped < vertical.range.maxValue() - .01f)
            // Route departure closes this local popup. Returning must keep the stopped grid offset.
            scenario.onActivity { active.value = false }
            SystemClock.sleep(80)
            scenario.onActivity { active.value = true }
            SystemClock.sleep(100)
            assertEquals("Closing the popup replayed the grid scroll", stopped, vertical.range.value(), .001f)
        }
    }

    @Test fun compactHomeAndAccessControlsRejectInputDuringExitAndRecoverOnReopen() {
        requireMotion()
        launchReaderTestActivity().use { scenario ->
            val root = AtomicReference<ViewRootForTest>()
            val effects = AtomicReference<BroadcastFrameClock>()
            val recomposer = AtomicReference<Recomposer>()
            val owner = AtomicReference<CoroutineScope>()
            val runner = AtomicReference<Job>()
            val opened = AtomicInteger()
            val query = AtomicReference("")
            val homeInput = mutableStateOf(true)
            val composedHomeInput = AtomicBoolean(true)
            val application = ApplicationProvider.getApplicationContext<Application>()
            val preferences = "access-exit-test-" + UUID.randomUUID()
            val accessStore = ViewModelStore()
            val permission = AtomicBoolean(false)
            val requested = AtomicInteger()
            val accessOnly = mutableStateOf(false)
            val composedGrant = AtomicBoolean(false)
            lateinit var access: ReaderAccessModel
            val keyboardMode = object : InputModeManager {
                override val inputMode = InputMode.Keyboard
                override fun requestInputMode(inputMode: InputMode) = inputMode == InputMode.Keyboard
            }
            // Raw children prove retained composition; exposed children stop at the input gate.
            fun exposed(node: SemanticsNode): Sequence<SemanticsNode> = sequence {
                yield(node)
                if (!node.config.isClearingSemantics) for (child in node.children) yieldAll(exposed(child))
            }
            fun matches(node: SemanticsNode, label: String, raw: Boolean): Boolean =
                node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true ||
                    node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true ||
                    ((raw || !node.config.isClearingSemantics) && node.children.any {
                        it.config.getOrNull(SemanticsActions.OnClick)?.action == null && matches(it, label, raw)
                    })
            fun action(label: String, raw: Boolean = false): SemanticsNode? {
                val tree = root.get().semanticsOwner.unmergedRootSemanticsNode
                return (if (raw) nodes(tree) else exposed(tree)).firstOrNull {
                    it.config.getOrNull(SemanticsActions.OnClick)?.action != null && matches(it, label, raw)
                }
            }
            fun settled() = effects.get()?.hasAwaiters == false && recomposer.get().currentState.value == Recomposer.State.Idle
            fun enter(activity: ReaderTestActivity, label: String, allowed: Boolean) {
                val node = requireNotNull(action(label, raw = true)) { "The $label node was disposed before the input probe" }
                assertNull("$label resolved to an editable ancestor", node.config.getOrNull(SemanticsActions.SetText))
                assertEquals("$label focus admission", allowed,
                    requireNotNull(node.config.getOrNull(SemanticsActions.RequestFocus)?.action).invoke())
                activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
            }
            fun setQuery() {
                scenario.onActivity {
                    val field = exposed(root.get().semanticsOwner.unmergedRootSemanticsNode).first {
                        it.config.getOrNull(SemanticsActions.SetText)?.action != null
                    }
                    assertTrue(field.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("keep")))
                }
                await("The library query did not update") { query.get() == "keep" }
            }
            fun pauseAndClick(label: String, next: String) {
                scenario.onActivity {
                    recomposer.get().pauseCompositionFrameClock()
                    click(root, label)
                }
                await("$label did not compose its visibility change with a pending animation") {
                    var changed = false
                    scenario.onActivity { changed = action(next) != null }
                    changed && effects.get().hasAwaiters && recomposer.get().currentState.value == Recomposer.State.Idle
                }
            }
            fun resume() {
                scenario.onActivity { recomposer.get().resumeCompositionFrameClock() }
                await("Home control animation did not settle", ::settled)
            }
            try {
                scenario.onActivity { activity ->
                    access = ReaderAccessModel(application, permission::get, preferences)
                    accessStore.put("access", access)
                    val scope = CoroutineScope(AndroidUiDispatcher.CurrentThread + Job())
                    val composition = Recomposer(scope.coroutineContext)
                    owner.set(scope); recomposer.set(composition)
                    runner.set(scope.launch { composition.runRecomposeAndApplyChanges() })
                    activity.setContent(parent = composition) {
                        val view = LocalView.current
                        val inputEnabled = homeInput.value
                        val fixtureScope = rememberCoroutineScope()
                        SideEffect {
                            root.set(view as ViewRootForTest)
                            effects.set(fixtureScope.coroutineContext[MonotonicFrameClock] as BroadcastFrameClock)
                            composedHomeInput.set(inputEnabled)
                        }
                        CompositionLocalProvider(LocalInputModeManager provides keyboardMode) {
                            MaterialTheme {
                                if (accessOnly.value) {
                                    val granted = access.granted
                                    SideEffect { composedGrant.set(granted) }
                                    Box(Modifier.width(260.dp)) {
                                        LibraryAccessSetting(access) { requested.incrementAndGet() }
                                    }
                                } else ReaderHome({ opened.incrementAndGet() },
                                    Modifier.width(260.dp).height(400.dp).readerInput(inputEnabled),
                                    onLibraryFilter = { text, _ -> query.set(text) })
                            }
                        }
                    }
                }
                await("Home fixture did not settle") { root.get() != null && settled() }
                click(scenario, root, "Library")
                await("Compact library did not settle") {
                    var ready = false
                    scenario.onActivity { ready = action("Find documents") != null }
                    ready && settled()
                }
                scenario.onActivity { activity -> enter(activity, "Open", true); assertEquals(1, opened.get()) }
                pauseAndClick("Find documents", "Close library search")
                scenario.onActivity { activity ->
                    assertNotNull("The exiting FAB must still be composed", action("Open", raw = true))
                    assertNull("A closing FAB must leave accessibility navigation", action("Open"))
                    enter(activity, "Open", false)
                    assertEquals("The exiting FAB opened a document", 1, opened.get())
                }
                resume()
                setQuery()
                scenario.onActivity { activity -> enter(activity, "Clear search", true) }
                await("The open clear action did not accept Enter") { query.get().isEmpty() }
                setQuery()
                pauseAndClick("Close library search", "Find documents")
                scenario.onActivity { activity ->
                    assertNotNull("The exiting search must still be composed", action("Clear search", raw = true))
                    assertNull("A closing search exposed its clear action", action("Clear search"))
                    assertNull("A closing search exposed its filter", action("Document type"))
                    assertFalse("A closing search remained editable", exposed(root.get().semanticsOwner.unmergedRootSemanticsNode).any {
                        it.config.getOrNull(SemanticsActions.SetText)?.action != null
                    })
                    enter(activity, "Clear search", false)
                    assertEquals("Enter changed the closing query", "keep", query.get())
                }
                resume()
                assertEquals("Closing search changed its retained query", "keep", query.get())
                scenario.onActivity { activity -> enter(activity, "Open", true); assertEquals(2, opened.get()) }
                click(scenario, root, "Find documents")
                await("Reopened search did not settle") {
                    var ready = false
                    scenario.onActivity { ready = action("Close library search") != null && action("Clear search") != null }
                    ready && settled()
                }
                scenario.onActivity { activity ->
                    assertEquals("Reopening lost the query", "keep", query.get())
                    assertNotNull("Reopening lost the filter", action("Document type"))
                    enter(activity, "Clear search", true)
                }
                await("Reopened clear action did not accept Enter") { query.get().isEmpty() }
                // A covered route must also evict a nested focus owner, without Home clearing it.
                setQuery()
                scenario.onActivity {
                    val clear = requireNotNull(action("Clear search", raw = true))
                    assertTrue(clear.config[SemanticsActions.RequestFocus].action!!.invoke())
                    assertEquals(true, action("Clear search", raw = true)!!.config.getOrNull(SemanticsProperties.Focused))
                    homeInput.value = false
                }
                await("The retained Home input gate did not close") {
                    !composedHomeInput.get() && recomposer.get().currentState.value == Recomposer.State.Idle
                }
                scenario.onActivity { activity ->
                    val clear = requireNotNull(action("Clear search", raw = true))
                    assertEquals("A covered nested button retained focus", false,
                        clear.config.getOrNull(SemanticsProperties.Focused))
                    assertNull("A covered Home exposed its clear action", action("Clear search"))
                    enter(activity, "Clear search", false)
                    assertEquals("Enter changed the covered query", "keep", query.get())
                    homeInput.value = true
                }
                await("The retained Home input gate did not reopen") { composedHomeInput.get() && settled() }
                scenario.onActivity { activity -> enter(activity, "Clear search", true) }
                await("The restored nested action did not accept Enter") { query.get().isEmpty() }
                // Injected access and a counting callback: no platform permission or Settings launch.
                scenario.onActivity { accessOnly.value = true }
                await("The access card did not settle") {
                    var ready = false
                    scenario.onActivity { ready = access.ready && action("Device access") != null }
                    ready && settled()
                }
                scenario.onActivity { activity ->
                    enter(activity, "Device access", true)
                    assertEquals(1, requested.get())
                    recomposer.get().pauseCompositionFrameClock()
                    permission.set(true)
                    assertTrue(access.refresh())
                }
                await("Grant did not compose with a pending exit animation") {
                    composedGrant.get() && effects.get().hasAwaiters &&
                        recomposer.get().currentState.value == Recomposer.State.Idle
                }
                scenario.onActivity { activity ->
                    assertNotNull("The exiting access card must still be composed", action("Device access", raw = true))
                    assertNull("Granted access remained in accessibility navigation", action("Device access"))
                    enter(activity, "Device access", false)
                    assertEquals("The exiting card requested access", 1, requested.get())
                }
                resume()
                scenario.onActivity { permission.set(false); assertTrue(access.refresh()) }
                await("Revoked access did not restore the card") {
                    var ready = false
                    scenario.onActivity { ready = !composedGrant.get() && action("Device access") != null }
                    ready && settled()
                }
                scenario.onActivity { activity -> enter(activity, "Device access", true); assertEquals(2, requested.get()) }
            } finally {
                scenario.onActivity { recomposer.get()?.cancel(); owner.get()?.cancel(); accessStore.clear() }
                try { await("The Home input test recomposer did not stop") { runner.get()?.isCompleted != false } }
                finally { runBlocking { ReaderModel.orderedStateRead { application.deleteSharedPreferences(preferences) } } }
            }
        }
    }

    @Test fun settingsStopsVerticalAndNarrowChoiceScrollingWithoutResetOnReturn() {
        requireMotion()
        launchReaderTestActivity().use { scenario ->
            val active = mutableStateOf(true)
            val root = AtomicReference<ViewRootForTest>()
            scenario.onActivity { activity -> activity.setContent {
                val view = LocalView.current
                SideEffect { root.set(view as ViewRootForTest) }
                MaterialTheme { Box(Modifier.width(148.dp).height(440.dp)) {
                    ReaderSettingsScreen(CopySettings(), "Default", 0, 0, false, null,
                        {}, {}, {}, {}, ThemeSettings(ReadingMode.ORIGINAL), {}, {}, active = active.value)
                } }
            } }
            val horizontal = axis(scenario, root, horizontal = true)
            val vertical = axis(scenario, root)
            val initialX = horizontal.range.value()
            val initialY = vertical.range.value()
            val horizontalTarget = horizontal.range.maxValue() * .75f
            scenario.onActivity { activity ->
                assertTrue(horizontal.scroll(horizontalTarget, 0f))
                assertTrue(vertical.scroll(0f, 6000f * activity.resources.displayMetrics.density))
            }
            await("Settings scrolls did not both start") {
                horizontal.range.value() > initialX && vertical.range.value() > initialY
            }
            scenario.onActivity {
                assertTrue("Horizontal fixture must still be moving when deactivated",
                    horizontal.range.value() > initialX && horizontal.range.value() < horizontalTarget - .01f)
                active.value = false
            }
            SystemClock.sleep(80)
            val x = horizontal.range.value()
            val y = vertical.range.value()
            SystemClock.sleep(400)
            assertEquals("Inactive choices continued scrolling", x, horizontal.range.value(), .001f)
            assertEquals("Inactive Settings continued scrolling", y, vertical.range.value(), .001f)
            assertTrue("Horizontal fixture completed instead of being interrupted", x < horizontalTarget - .01f)
            assertTrue("Vertical fixture reached its end before interruption", y < vertical.range.maxValue() - .01f)
            scenario.onActivity { active.value = true }
            SystemClock.sleep(100)
            assertEquals("Returning reset or replayed the choices", x, horizontal.range.value(), .001f)
            assertEquals("Returning reset or replayed Settings", y, vertical.range.value(), .001f)
        }
    }
}
