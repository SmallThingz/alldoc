package app.smallthingz.alldoc.ui

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.smallthingz.alldoc.ReaderTestActivity
import app.smallthingz.alldoc.engine.DocumentInfo
import app.smallthingz.alldoc.engine.DocumentNotes
import app.smallthingz.alldoc.engine.DocumentNote
import app.smallthingz.alldoc.engine.SheetInfo
import app.smallthingz.alldoc.engine.WorkbookInfo
import app.smallthingz.alldoc.state.SheetPosition
import app.smallthingz.alldoc.state.TextPosition
import app.smallthingz.alldoc.state.ViewportState
import app.smallthingz.alldoc.theme.ReadingMode
import app.smallthingz.alldoc.theme.ThemeSettings
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Activity-local input only; the runner still needs an exclusively coordinated UI window. */
@RunWith(AndroidJUnit4::class)
class ReaderInputLifecycleTest {
    private fun await(message: String, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 5000
        while (!condition()) {
            check(SystemClock.uptimeMillis() < end) { message }
            SystemClock.sleep(16)
        }
    }

    private fun find(node: SemanticsNode, predicate: (SemanticsNode) -> Boolean): SemanticsNode? {
        if (predicate(node)) return node
        return node.children.firstNotNullOfOrNull { find(it, predicate) }
    }

    @Test fun modalStopsPageZoomAtItsCurrentPoseAndRejectsStaleZoomActions() {
        assumeTrue("Reduced motion has no in-flight zoom to interrupt", ValueAnimator.areAnimatorsEnabled())
        val bitmap = Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888)
        try { ActivityScenario.launch(ReaderTestActivity::class.java).use { scenario ->
            val active = mutableStateOf(true)
            val pose = mutableStateOf(ViewportState())
            val root = AtomicReference<ViewRootForTest>()
            val pause = AtomicBoolean(true)
            val animated = AtomicBoolean()
            val anchor = AtomicReference(0 to 0f)
            val zoom = AtomicReference<() -> Boolean>()
            scenario.onActivity { activity -> activity.setContent {
                val view = LocalView.current
                SideEffect { root.set(view as ViewRootForTest) }
                MaterialTheme { ReaderPages(900L, 4, 0, 0f, pose.value, 0L,
                    (0..3).associateWith { ReaderPageImage(bitmap) }, (0..3).associateWith { 1.5f }, {},
                    { page, fraction -> anchor.set(page to fraction) }, {
                        pose.value = it
                        if (it.zoom > 1f && pause.compareAndSet(true, false)) active.value = false
                    }, {}, Modifier.width(240.dp).height(320.dp), onZoomAnimation = animated::set, active = active.value)
                }
            } }
            await("Zoom action was not composed") {
                scenario.onActivity {
                    root.get()?.semanticsOwner?.unmergedRootSemanticsNode?.let { tree ->
                        find(tree) { node -> node.config.getOrNull(SemanticsActions.CustomActions)?.any { it.label == "Zoom in" } == true }
                            ?.config?.getOrNull(SemanticsActions.CustomActions)?.first { it.label == "Zoom in" }?.action?.let(zoom::set)
                    }
                }
                zoom.get() != null
            }
            scenario.onActivity { assertTrue(zoom.get().invoke()) }
            await("Modal did not cancel zoom ownership") { !active.value && !animated.get() }
            SystemClock.sleep(80)
            val stopped = pose.value
            val position = anchor.get()
            assertTrue("The modal must interrupt, not wait for the target", stopped.zoom in 1.00001f..<1.5f)
            scenario.onActivity { zoom.get().invoke() }
            SystemClock.sleep(300)
            assertEquals(stopped, pose.value)
            assertEquals(position, anchor.get())
            assertFalse("Retained page pixels must survive modal ownership", bitmap.isRecycled)
            scenario.onActivity { active.value = true }
            SystemClock.sleep(80)
            scenario.onActivity { zoom.get().invoke() }
            await("Closing the modal did not restore zoom input") { pose.value.zoom > stopped.zoom + .01f }
        } } finally { bitmap.recycle() }
    }

    @Test fun modalStopsRealPageAndTextFlingsWithoutResettingTheirAnchor() {
        val bitmap = Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888)
        val text = (0..1800).joinToString("\n") { "Line $it: retained text at a stable reading position." }
        try { for (textView in listOf(false, true)) ActivityScenario.launch(ReaderTestActivity::class.java).use { scenario ->
            val active = mutableStateOf(true)
            val pointerUp = AtomicBoolean()
            val query = TextFieldState()
            val coast = AtomicInteger()
            val position = AtomicReference<Any>(if (textView) TextPosition() else 0 to 0f)
            val bounds = AtomicReference<Rect>()
            fun moved(value: Any) {
                val old = position.getAndSet(value)
                if (pointerUp.get() && old != value && coast.incrementAndGet() == 3) active.value = false
            }
            scenario.onActivity { activity -> activity.setContent {
                val modifier = Modifier.width(240.dp).height(320.dp).onGloballyPositioned { bounds.set(it.boundsInWindow()) }
                MaterialTheme {
                    if (textView) ReaderTextView(text, false, ThemeSettings(ReadingMode.ORIGINAL), false,
                        query, {}, modifier, 901L, TextPosition(), ::moved, {}, active = active.value)
                    else ReaderPages(901L, 30, 0, 0f, ViewportState(), 0L,
                        (0..29).associateWith { ReaderPageImage(bitmap) }, (0..29).associateWith { 1.5f }, {},
                        { page, fraction -> moved(page to fraction) }, {}, {}, modifier, active = active.value)
                }
            } }
            await("Reader has no viewport") { bounds.get() != null }
            SystemClock.sleep(150)
            val box = bounds.get()
            val down = SystemClock.uptimeMillis()
            fun send(kind: Int, amount: Float) {
                scenario.onActivity { activity ->
                    val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), kind,
                        box.center.x, box.top + box.height * (.8f - .6f * amount), 0)
                    try { activity.dispatchTouchEvent(event) } finally { event.recycle() }
                }
            }
            send(MotionEvent.ACTION_DOWN, 0f)
            repeat(6) { SystemClock.sleep(12); send(MotionEvent.ACTION_MOVE, (it + 1) / 6f) }
            send(MotionEvent.ACTION_UP, 1f)
            pointerUp.set(true)
            await("Reader must demonstrate inertia before its modal interrupts it (text=$textView)") { !active.value }
            SystemClock.sleep(80)
            val stopped = position.get()
            SystemClock.sleep(300)
            assertEquals("Inactive fling moved behind its modal (text=$textView)", stopped, position.get())
            scenario.onActivity { active.value = true }
            SystemClock.sleep(100)
            assertEquals("Closing a modal reset the reading anchor (text=$textView)", stopped, position.get())
        } } finally { bitmap.recycle() }
    }

    @Test fun searchWaitsForActiveTextThenDoesNotUndoLaterManualScrolling() {
        val active = mutableStateOf(true)
        val query = TextFieldState()
        val text = (0..300).joinToString("\n\n") { "Paragraph $it. " + "Ordinary document text. ".repeat(12) } + "\n\nunique-match"
        val position = AtomicReference(TextPosition())
        val root = AtomicReference<ViewRootForTest>()
        val flushes = AtomicInteger()
        ActivityScenario.launch(ReaderTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                val view = LocalView.current
                SideEffect { root.set(view as ViewRootForTest) }
                MaterialTheme { ReaderTextView(text, false, ThemeSettings(ReadingMode.ORIGINAL), true,
                    query, {}, Modifier.width(320.dp).height(440.dp), 902L, TextPosition(), position::set,
                    { flushes.incrementAndGet() }, active = active.value) }
            } }
            await("Text did not compose") { root.get() != null }
            SystemClock.sleep(150)
            scenario.onActivity { active.value = false; query.edit { replace(0, length, "unique-match") } }
            SystemClock.sleep(300)
            assertEquals("Search must not move an inactive reader", TextPosition(), position.get())
            scenario.onActivity { active.value = true }
            await("Pending search did not resume") { position.get().offset > text.length / 2 && flushes.get() > 0 }
            val found = position.get()
            val before = flushes.get()
            scenario.onActivity {
                val tree = root.get().semanticsOwner.unmergedRootSemanticsNode
                val scroll = checkNotNull(find(tree) { it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null &&
                    it.config.getOrNull(SemanticsActions.ScrollBy)?.action != null })
                assertTrue(checkNotNull(scroll.config.getOrNull(SemanticsActions.ScrollBy)?.action).invoke(0f, -240f))
            }
            await("Manual text scroll did not finish") { flushes.get() > before && position.get() != found }
            val manual = position.get()
            scenario.onActivity { active.value = false }
            SystemClock.sleep(80)
            scenario.onActivity { active.value = true }
            SystemClock.sleep(300)
            assertEquals("Completed search must not run again after a modal", manual, position.get())
        }
    }

    @Test fun notesScrollStopsBehindModalAndClosingItPreservesPosition() {
        val active = mutableStateOf(true)
        val root = AtomicReference<ViewRootForTest>()
        val scrollPosition = AtomicReference<() -> Float>()
        val notes = DocumentNotes((0..100).map { DocumentNote("Comment $it: " + "Long speaker notes. ".repeat(20)) })
        ActivityScenario.launch(ReaderTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                val view = LocalView.current
                SideEffect { root.set(view as ViewRootForTest) }
                MaterialTheme { ReaderNotesContent(
                    DocumentInfo("Slides", "application/test", "PPTX", 3, true, "FULL", supportsNotes = true),
                    notes, false, 0, 903L, Modifier.width(240.dp).height(320.dp), {}, active = active.value) }
            } }
            await("Notes scroll owner was not composed") {
                scenario.onActivity {
                    root.get()?.semanticsOwner?.unmergedRootSemanticsNode?.let { tree ->
                        find(tree) { it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null }
                            ?.config?.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.value?.let(scrollPosition::set)
                    }
                }
                scrollPosition.get() != null
            }
            scenario.onActivity {
                val tree = root.get().semanticsOwner.unmergedRootSemanticsNode
                val node = checkNotNull(find(tree) { it.config.getOrNull(SemanticsActions.ScrollBy)?.action != null })
                checkNotNull(node.config.getOrNull(SemanticsActions.ScrollBy)?.action).invoke(0f, 4000f)
            }
            await("Notes never started scrolling") { scrollPosition.get().invoke() > 0f }
            scenario.onActivity { active.value = false }
            SystemClock.sleep(80)
            val stopped = scrollPosition.get().invoke()
            SystemClock.sleep(300)
            assertEquals(stopped, scrollPosition.get().invoke(), 0f)
            scenario.onActivity { active.value = true }
            SystemClock.sleep(300)
            assertEquals("Modal close must not reset the notes filter position", stopped, scrollPosition.get().invoke(), 0f)
        }
    }


    @Test fun worksheetTabsStopBehindModalAndOnlyPendingSelectionRevealsOnResume() {
        val active = mutableStateOf(true)
        val position = mutableStateOf(SheetPosition(sheet = 0))
        val root = AtomicReference<ViewRootForTest>()
        val scrollPosition = AtomicReference<() -> Float>()
        val workbook = WorkbookInfo((0..39).map { index ->
            SheetInfo(index, "Sheet $index", 1000, 128, 20, 10, 15360.0, 28000.0)
        }, 0)
        ActivityScenario.launch(ReaderTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                val view = LocalView.current
                SideEffect { root.set(view as ViewRootForTest) }
                MaterialTheme { ReaderWorksheet(905L, workbook, null, position.value, null, false, null,
                    { position.value = it }, {}, { position.value = position.value.copy(sheet = it) }, {}, {},
                    Modifier.width(240.dp).height(320.dp), active = active.value) }
            } }
            await("Worksheet tab scroll owner was not composed") {
                scenario.onActivity {
                    root.get()?.semanticsOwner?.unmergedRootSemanticsNode?.let { tree ->
                        find(tree) { it.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange) != null }
                            ?.config?.getOrNull(SemanticsProperties.HorizontalScrollAxisRange)?.value?.let(scrollPosition::set)
                    }
                }
                scrollPosition.get() != null
            }
            SystemClock.sleep(100)
            scenario.onActivity {
                val tree = root.get().semanticsOwner.unmergedRootSemanticsNode
                val tabs = checkNotNull(find(tree) { it.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange) != null })
                assertTrue(checkNotNull(tabs.config.getOrNull(SemanticsActions.ScrollBy)?.action).invoke(4000f, 0f))
            }
            await("Sheet tabs never started scrolling") { scrollPosition.get().invoke() > 0f }
            scenario.onActivity { active.value = false }
            SystemClock.sleep(80)
            val stopped = scrollPosition.get().invoke()
            SystemClock.sleep(300)
            assertEquals("Tab animation continued behind the modal", stopped, scrollPosition.get().invoke(), 0f)
            assertEquals("Tab scrolling must not select a different worksheet", SheetPosition(sheet = 0), position.value)
            scenario.onActivity { active.value = true }
            SystemClock.sleep(300)
            assertEquals("Modal close must preserve manual tab-strip scrolling", stopped, scrollPosition.get().invoke(), 0f)
            scenario.onActivity { active.value = false; position.value = position.value.copy(sheet = 30) }
            SystemClock.sleep(300)
            assertEquals("A pending sheet reveal must wait while inactive", stopped, scrollPosition.get().invoke(), 0f)
            scenario.onActivity { active.value = true }
            await("Pending sheet selection was not revealed on resume") {
                val shown = AtomicBoolean()
                scenario.onActivity {
                    val tree = root.get().semanticsOwner.unmergedRootSemanticsNode
                    val tabs = find(tree) { it.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange) != null }
                    val selected = find(tree) { node ->
                        node.config.getOrNull(SemanticsProperties.Selected) == true && find(node) { child ->
                            child.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "Sheet 30" } == true
                        } != null
                    }
                    shown.set(tabs != null && selected != null && selected.boundsInRoot.left >= tabs.boundsInRoot.left &&
                        selected.boundsInRoot.right <= tabs.boundsInRoot.right)
                }
                shown.get()
            }
            assertEquals(SheetPosition(sheet = 30), position.value)
            val beforeReveal = scrollPosition.get().invoke()
            scenario.onActivity { position.value = position.value.copy(sheet = 0) }
            await("Automatic tab reveal never started") { scrollPosition.get().invoke() != beforeReveal }
            scenario.onActivity {
                val tree = root.get().semanticsOwner.unmergedRootSemanticsNode
                val tabs = checkNotNull(find(tree) { it.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange) != null })
                assertTrue(checkNotNull(tabs.config.getOrNull(SemanticsActions.ScrollBy)?.action).invoke(400f, 0f))
            }
            SystemClock.sleep(400)
            val manual = scrollPosition.get().invoke()
            assertTrue("The manual interruption must leave the selected first sheet offscreen", manual > 0f)
            scenario.onActivity { active.value = false }
            SystemClock.sleep(80)
            scenario.onActivity { active.value = true }
            SystemClock.sleep(300)
            assertEquals("A manually interrupted reveal must not resume after a modal", manual, scrollPosition.get().invoke(), 0f)
            assertEquals(SheetPosition(sheet = 0), position.value)
        }
    }

    @Test fun renderedPagesAcceptPageKeysWithoutARecoveryButtonAndRespectModalOwnership() {
        val bitmap = Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888)
        try { for (horizontal in listOf(false, true)) ActivityScenario.launch(ReaderTestActivity::class.java).use { scenario ->
            val active = mutableStateOf(true)
            val bounds = AtomicReference<Rect>()
            val root = AtomicReference<ViewRootForTest>()
            val position = AtomicReference(0 to 0f)
            val finishes = AtomicInteger()
            scenario.onActivity { activity -> activity.setContent {
                val view = LocalView.current
                SideEffect { root.set(view as ViewRootForTest) }
                MaterialTheme { ReaderPages(904L, 4, 0, 0f, ViewportState(), 0L,
                    (0..3).associateWith { ReaderPageImage(bitmap) }, (0..3).associateWith { 1.5f }, {},
                    { page, fraction -> position.set(page to fraction) }, {}, { finishes.incrementAndGet() },
                    Modifier.width(240.dp).height(320.dp).onGloballyPositioned { bounds.set(it.boundsInWindow()) },
                    horizontal = horizontal, active = active.value) }
            } }
            await("Rendered pages did not lay out") { bounds.get() != null }
            SystemClock.sleep(100)
            fun key(code: Int): Boolean {
                val consumed = AtomicBoolean()
                scenario.onActivity { activity ->
                    consumed.set(activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code)))
                    activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
                }
                return consumed.get()
            }
            // Keyboard-only startup reaches the document through ordinary Tab traversal.
            assertTrue(key(KeyEvent.KEYCODE_TAB))
            await("Tab did not focus the rendered document surface") {
                val focused = AtomicBoolean()
                scenario.onActivity {
                    val tree = root.get().semanticsOwner.unmergedRootSemanticsNode
                    focused.set(find(tree) { node ->
                        node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Document pages") == true &&
                            node.config.getOrNull(SemanticsProperties.Focused) == true
                    } != null)
                }
                focused.get()
            }
            assertTrue("Document must consume Page Down (horizontal=$horizontal)", key(KeyEvent.KEYCODE_PAGE_DOWN))
            await("Page Down did not scroll a rendered document") {
                finishes.get() > 0 && if (horizontal) position.get().first == 1 && position.get().second < .01f
                    else position.get().first > 0 || position.get().second > .1f
            }
            val ended = finishes.get()
            assertTrue(key(KeyEvent.KEYCODE_PAGE_UP))
            await("Page Up did not restore the first page") {
                finishes.get() > ended && position.get().first == 0 && position.get().second < .01f
            }
            scenario.onActivity { active.value = false }
            SystemClock.sleep(80)
            val stopped = position.get()
            key(KeyEvent.KEYCODE_PAGE_DOWN)
            SystemClock.sleep(300)
            assertEquals("A modal owns keys while document input is inactive", stopped, position.get())
            scenario.onActivity { active.value = true }
            SystemClock.sleep(80)
            scenario.onActivity { activity ->
                val point = bounds.get().center
                val time = SystemClock.uptimeMillis()
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val event = MotionEvent.obtain(time, time, action, point.x, point.y, 0)
                    try { activity.dispatchTouchEvent(event) } finally { event.recycle() }
                }
            }
            assertTrue("Touch must restore document key ownership after the modal", key(KeyEvent.KEYCODE_PAGE_DOWN))
            await("Restored document focus did not scroll") { position.get() != stopped }
        } } finally { bitmap.recycle() }
    }

}
