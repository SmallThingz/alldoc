package app.smallthingz.alldoc.ui

import android.content.res.Configuration
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.smallthingz.alldoc.engine.DocumentInfo
import app.smallthingz.alldoc.engine.DocumentNote
import app.smallthingz.alldoc.engine.DocumentNotes
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Activity-local composition and semantics; never rotates or injects input into another display. */
@RunWith(AndroidJUnit4::class)
class ReaderNotesRestorationTest {
    @Test fun adaptiveMovesAndActivityRecreationPreserveBothSubtrees() = Fixture().use { fixture ->
        fixture.scrollTo(12)
        val before = fixture.anchor()
        fixture.scenario.onActivity { fixture.documentState.get().intValue = 41; fixture.landscape.value = true }
        fixture.frames()
        fixture.await { fixture.anchor() == before }
        assertEquals(1, fixture.documentOwners.get())
        assertEquals(1, fixture.notesOwners.get())
        assertEquals(0, fixture.documentDisposals.get())
        assertEquals(0, fixture.notesDisposals.get())
        assertEquals(41, fixture.documentState.get().intValue)
        fixture.recreate()
        fixture.await { fixture.anchor() == before }
        assertEquals(41, fixture.documentState.get().intValue)
        assertEquals(2, fixture.documentOwners.get())
        assertEquals(2, fixture.notesOwners.get())
        fixture.scenario.onActivity { fixture.landscape.value = false }
        fixture.frames()
        fixture.await { fixture.anchor() == before }
        assertEquals(2, fixture.documentOwners.get())
        assertEquals(2, fixture.notesOwners.get())
    }

    @Test fun deliberateFilterAndPageChangesKeepTheirPendingResetAcrossRecreation() = Fixture().use { fixture ->
        val showAll = fixture.action("Show all notes")
        fixture.scrollTo(12)
        fixture.scenario.onActivity { assertTrue(showAll()) }
        fixture.frames()
        fixture.await { fixture.anchor() == "Note 0/0" }
        val showCurrent = fixture.action("Show current page notes")
        fixture.scrollTo(12)
        val before = fixture.anchor()
        fixture.scenario.onActivity { fixture.page.intValue = 1 }
        fixture.frames()
        assertEquals("All-notes mode ignores changes to the current page", before, fixture.anchor())
        fixture.scenario.onActivity { assertTrue(showCurrent()) }
        fixture.frames()
        fixture.await { fixture.anchor() == "Note 1/0" }
        fixture.scrollTo(12)
        fixture.scenario.onActivity { fixture.active.value = false; fixture.page.intValue = 0 }
        fixture.frames() // The page reset is pending while the pane cannot take input.
        fixture.recreate()
        fixture.scenario.onActivity { fixture.active.value = true }
        fixture.frames()
        fixture.await { fixture.anchor() == "Note 0/0" }
    }

    private class Fixture : AutoCloseable {
        val scenario = launchReaderTestActivity()
        val landscape = mutableStateOf(false)
        val active = mutableStateOf(true)
        val page = mutableIntStateOf(0)
        val documentState = AtomicReference<MutableIntState>()
        val documentOwners = AtomicInteger()
        val documentDisposals = AtomicInteger()
        val notesOwners = AtomicInteger()
        val notesDisposals = AtomicInteger()
        private val root = AtomicReference<ViewRootForTest?>()
        private val deadline = SystemClock.uptimeMillis() + 25_000
        private val info = DocumentInfo("Slides", "application/test", "PPTX", 2, true, "FULL", supportsNotes = true)
        private val notes = DocumentNotes((0..1).flatMap { page -> (0..39).map { index ->
            DocumentNote("Note $page/$index: " + "Retained note content. ".repeat(18), page = page)
        } })

        init {
            try { install(); frames(); await { anchor() == "Note 0/0" } }
            catch (failure: Throwable) { scenario.close(); throw failure }
        }

        private fun install() = scenario.onActivity { activity ->
            activity.verifyTestDisplay()
            activity.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply {
                orientation = if (landscape.value) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
            }
            val view = LocalView.current
            SideEffect { root.set(view as ViewRootForTest) }
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                MaterialTheme {
                    ReaderNotesLayout(true, Modifier.fillMaxSize(), notes = { modifier ->
                        DisposableEffect(Unit) { notesOwners.incrementAndGet(); onDispose { notesDisposals.incrementAndGet() } }
                        ReaderNotesContent(info, notes, false, page.intValue, 991L, modifier, {}, active.value)
                    }, content = { modifier ->
                        val state = rememberSaveable { mutableIntStateOf(0) }
                        SideEffect { documentState.set(state) }
                        DisposableEffect(Unit) { documentOwners.incrementAndGet(); onDispose { documentDisposals.incrementAndGet() } }
                        Box(modifier) { Text("Document state ${state.intValue}") }
                    })
                }
            }
        } }

        fun recreate() { root.set(null); scenario.recreate(); install(); frames(); await { root.get() != null } }

        fun frames() {
            val done = CountDownLatch(1)
            scenario.onActivity { activity ->
                val view = activity.window.decorView
                fun frame(left: Int) { view.postOnAnimation { if (left == 1) done.countDown() else frame(left - 1) } }
                frame(3)
            }
            assertTrue("The owned Activity did not produce frames", done.await(3, TimeUnit.SECONDS))
        }

        fun await(condition: () -> Boolean) {
            val end = minOf(deadline, SystemClock.uptimeMillis() + 5_000)
            while (!condition()) {
                check(SystemClock.uptimeMillis() < end) { "Notes state did not settle" }
                SystemClock.sleep(16)
            }
        }

        private fun find(node: SemanticsNode, test: (SemanticsNode) -> Boolean): SemanticsNode? =
            if (test(node)) node else node.children.firstNotNullOfOrNull { find(it, test) }

        private fun scrollNode(): SemanticsNode? = root.get()?.semanticsOwner?.unmergedRootSemanticsNode?.let { tree ->
            find(tree) { it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null }
        }

        fun anchor(): String? {
            var value: String? = null
            scenario.onActivity {
                val scroll = scrollNode() ?: return@onActivity
                val bounds = scroll.boundsInRoot
                val node = find(scroll) { candidate ->
                    candidate.boundsInRoot.bottom > bounds.top && candidate.boundsInRoot.top < bounds.bottom &&
                        candidate.config.getOrNull(SemanticsProperties.Text)?.any { it.text.startsWith("Note ") } == true
                }
                value = node?.config?.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text?.substringBefore(':')
            }
            return value
        }

        fun scrollTo(index: Int) {
            scenario.onActivity {
                assertTrue(checkNotNull(scrollNode()?.config?.getOrNull(SemanticsActions.ScrollToIndex)?.action).invoke(index))
            }
            frames()
            await { anchor() == "Note ${page.intValue}/$index" }
        }

        fun action(label: String): () -> Boolean {
            var result: (() -> Boolean)? = null
            scenario.onActivity {
                val tree = checkNotNull(root.get()).semanticsOwner.unmergedRootSemanticsNode
                var node = find(tree) { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true }
                while (node != null && result == null) {
                    result = node.config.getOrNull(SemanticsActions.OnClick)?.action
                    node = node.parent
                }
            }
            return checkNotNull(result) { "Missing Notes action: $label" }
        }

        override fun close() { scenario.close() }
    }
}
