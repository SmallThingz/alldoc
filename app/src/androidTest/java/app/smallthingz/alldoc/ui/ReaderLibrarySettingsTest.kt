package app.smallthingz.alldoc.ui

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.activity.compose.setContent
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.smallthingz.alldoc.state.LibraryCatalog
import app.smallthingz.alldoc.state.LibraryCategory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderLibrarySettingsTest {
    @Test fun pendingTypeSettingsRejectEarlyTapsAndKeepSavedExclusions() = ReaderPersistenceTest().Fixture().use { fixture ->
        val saved = setOf(LibraryCategory.BOOKS)
        LibraryCatalog(fixture.context).use { it.setHiddenTypes(saved) }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var blocker: Future<*>? = null
        val worker = ReaderModel::class.java.getDeclaredField("StateWorker").let {
            it.isAccessible = true; it.get(null) as ExecutorService
        }
        try {
            fixture.launch(width = 0, beforeCreate = {
                blocker = worker.submit { entered.countDown(); check(release.await(15, TimeUnit.SECONDS)) }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
            })
            launchReaderTestActivity().use { scenario ->
                val view = AtomicReference<View>()
                scenario.onActivity { activity -> activity.setContent {
                    val current = LocalView.current
                    SideEffect { view.set(current) }
                    ReaderTheme { ReaderScreen(fixture.reader, onOpen = {}, onExport = { _, _ -> }) }
                } }
                fun node(label: String, nodes: List<SemanticsNode>) = find(nodes) {
                    label in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() ||
                        it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == label }
                }
                await("Library tab is unavailable") {
                    var opened = false
                    scenario.onActivity { activity -> opened = node("Library", activity.testSemantics())?.let { label ->
                        generateSequence(label) { it.parent }.mapNotNull {
                            it.config.getOrNull(SemanticsActions.OnClick)?.action
                        }.firstOrNull()?.invoke() == true
                    } == true }
                    opened
                }
                val label = "Library: Documents"
                await("Library type controls did not lay out") {
                    var visible = false
                    scenario.onActivity { activity ->
                        val choice = node(label, activity.testSemantics())
                        if (choice != null) {
                            visible = choice.boundsInRoot.width >= 24 && choice.boundsInRoot.height >= 24
                            if (!visible) {
                                var parent: SemanticsNode? = choice
                                while (parent != null && parent.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) == null) parent = parent.parent
                                parent?.config?.getOrNull(SemanticsActions.ScrollBy)?.action?.invoke(0f, 160f)
                            }
                        }
                    }
                    visible
                }
                fun tap() = scenario.onActivity { activity ->
                    val choice = requireNotNull(node(label, activity.testSemantics()))
                    val offset = IntArray(2).also { requireNotNull(view.get()).getLocationInWindow(it) }
                    val point = choice.boundsInRoot.center
                    val now = SystemClock.uptimeMillis()
                    for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                        val event = MotionEvent.obtain(now, now + if (action == MotionEvent.ACTION_UP) 20 else 0,
                            action, point.x + offset[0], point.y + offset[1], 0)
                        try { activity.dispatchTouchEvent(event) } finally { event.recycle() }
                    }
                }
                tap()
                SystemClock.sleep(80)
                scenario.onActivity { activity ->
                    assertFalse(fixture.reader.librarySettingsLoaded.value)
                    assertTrue("An early UI tap must not replace unseen saved types", fixture.reader.libraryHiddenTypes.isEmpty())
                    assertNotNull(requireNotNull(node(label, activity.testSemantics())).config.getOrNull(SemanticsProperties.Disabled))
                    assertNull("Document opening must remain usable", requireNotNull(node("Open", activity.testSemantics()))
                        .config.getOrNull(SemanticsProperties.Disabled))
                }
                release.countDown()
                blocker!!.get(5, TimeUnit.SECONDS)
                await("Saved types did not hydrate and enable their controls") {
                    var ready = false
                    scenario.onActivity { activity -> ready = fixture.reader.librarySettingsLoaded.value &&
                        fixture.reader.libraryHiddenTypes == saved && node(label, activity.testSemantics())?.let {
                            it.config.getOrNull(SemanticsProperties.Disabled) == null
                        } == true }
                    ready
                }
                tap()
                val expected = saved + LibraryCategory.DOCUMENTS
                await("The later toggle discarded the saved exclusion") {
                    var ready = false
                    fixture.act { ready = it.libraryHiddenTypes == expected }
                    ready
                }
                fixture.stateBarrier()
                LibraryCatalog(fixture.context).use { assertEquals(expected, it.hiddenTypes()) }
            }
        } finally {
            release.countDown()
            blocker?.get(15, TimeUnit.SECONDS)
        }
    }

    private fun await(message: String, ready: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 5_000
        while (!ready()) {
            check(SystemClock.uptimeMillis() < end) { message }
            SystemClock.sleep(16)
        }
    }

    private fun find(nodes: List<SemanticsNode>, predicate: (SemanticsNode) -> Boolean): SemanticsNode? {
        for (node in nodes) {
            if (predicate(node)) return node
            find(node.children, predicate)?.let { return it }
        }
        return null
    }
}
