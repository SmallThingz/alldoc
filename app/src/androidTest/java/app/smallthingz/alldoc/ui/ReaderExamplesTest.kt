package app.smallthingz.alldoc.ui

import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.smallthingz.alldoc.MainActivity
import app.smallthingz.alldoc.ReaderTestActivity
import app.smallthingz.alldoc.sources.ExampleDocuments
import app.smallthingz.alldoc.state.DocumentMetadata
import app.smallthingz.alldoc.state.DocumentState
import app.smallthingz.alldoc.state.DocumentStore
import app.smallthingz.alldoc.state.StoredDocument
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderExamplesTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun filteredExamplesReturnFromReaderAndFailedOpenAcrossRecreation() {
        val example = ExampleDocuments.list(instrumentation.targetContext).single { it.extension == "pdf" }
        val intent = Intent(instrumentation.targetContext, MainActivity::class.java).setAction(Intent.ACTION_MAIN)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            scenario.onActivity {
                val model = ViewModelProvider(it)[ReaderModel::class.java]
                model.cancelAutomaticResume(); model.cancelLoading(); model.dismissError(); model.showReader(false)
            }
            await { visible("Recents") && visible("Examples") }
            click("Examples")
            await { visible("Find a format") && visible("Back") }
            setQuery("no-such-format-549a")
            await { visible("No matches") }
            setQuery(example.name)
            await { visible(example.title) && !visible("No matches") }
            scenario.recreate()
            await { visible(example.name) && visible(example.title) && visible("Back") }
            click(example.title)
            fun loaded() = await {
                var ready = false
                scenario.onActivity {
                    val model = ViewModelProvider(it)[ReaderModel::class.java]
                    assertNull(model.error)
                    ready = model.documentHash == example.sha256 && model.info != null && model.bitmap != null && !model.loading
                }
                ready && visible("Find in document") && !visible("Find a format")
            }
            loaded()
            scenario.recreate()
            loaded()
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            await { visible("Find a format") && visible(example.name) && visible(example.title) }
            scenario.onActivity {
                assertFalse(ViewModelProvider(it)[ReaderModel::class.java].readerVisible)
                ViewModelProvider(it)[ReaderModel::class.java].openExample(example.copy(sha256 = "0".repeat(64)))
            }
            await { visible("Couldn’t open document") }
            click("Close")
            await { !visible("Couldn’t open document") && visible("Home") }
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            await { visible("Find a format") && visible(example.name) && visible(example.title) }
            click("Back")
            await { visible("Recents") && visible("Library") && !visible("Find a format") }
        }
    }

    @Test fun invalidExternalIntentFromExamplesPreservesDocumentStateAndBackExits() {
        val context = instrumentation.targetContext
        val example = ExampleDocuments.list(context).single { it.extension == "html" }
        val worker = ReaderModel::class.java.getDeclaredField("StateWorker").apply { isAccessible = true }.get(null) as ExecutorService
        fun savedState() = worker.submit<DocumentState?> {
            DocumentStore(context).use { it.get(example.sha256)?.state }
        }.get(15, TimeUnit.SECONDS)
        val trackedIntent = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN)
        var activityLifecycle: Lifecycle? = null
        ActivityScenario.launch<MainActivity>(trackedIntent).use { scenario ->
            // Isolate this Home-origin test from a previous activity's pending or failed auto-resume.
            scenario.onActivity { activity ->
                activityLifecycle = activity.lifecycle
                ViewModelProvider(activity)[ReaderModel::class.java].apply {
                    cancelAutomaticResume(); cancelLoading(); dismissError(); showReader(false)
                }
            }
            await { visible("Recents") && visible("Examples") }
            click("Examples")
            await { visible("Find a format") }
            setQuery(example.name); await { visible(example.title) }; click(example.title)
            await {
                var ready = false
                scenario.onActivity { val model = ViewModelProvider(it)[ReaderModel::class.java]
                    assertNull(model.error)
                    ready = model.documentHash == example.sha256 && model.info != null && !model.loading }
                ready && visible("Find in document")
            }
            var previousIdentity = 0L
            scenario.onActivity { previousIdentity = ViewModelProvider(it)[ReaderModel::class.java].documentId
                it.onBackPressedDispatcher.onBackPressed() }
            await { visible("Find a format") }
            val before = savedState()
            assertNotNull(before)
            // ActivityScenario matches lifecycle events against its supplied Intent by value.
            // Follow the new request so setIntent() does not leave its observer stuck at PAUSED.
            scenario.onActivity {
                trackedIntent.setAction(Intent.ACTION_VIEW)
                    .setDataAndType(Uri.parse("https://example.invalid/document.pdf"), "application/pdf")
                // The launcher adds NEW_TASK | CLEAR_TASK; the re-delivery must keep neither.
                it.startActivity(Intent(trackedIntent).setFlags(0))
            }
            await {
                var rejected = false
                scenario.onActivity { val model = ViewModelProvider(it)[ReaderModel::class.java]
                    rejected = model.documentId > previousIdentity && model.readerVisible && !model.loading &&
                        model.info == null && model.error == "Only local files are supported." }
                rejected && visible("Only local files are supported.")
            }
            assertEquals("Rejecting a new request must preserve the previous document state", before, savedState())
            click("Close")
            await { !visible("Couldn’t open document") && visible("Home") && !visible("Find a format") }
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            await { scenario.state == Lifecycle.State.DESTROYED }
            instrumentation.runOnMainSync { assertEquals(Lifecycle.State.DESTROYED, activityLifecycle?.currentState) }
        }
    }

    @Test fun changingAndClearingAFilterShowsTheFirstResultsFromAScrolledCatalog() {
        val catalog = ExampleDocuments.list(instrumentation.targetContext)
        val lastTitle = catalog.last().title
        ActivityScenario.launch(ReaderTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                MaterialTheme {
                    Box(Modifier.width(360.dp).height(280.dp).clipToBounds()) {
                        ReaderExamplesScreen(onBack = {}, onOpen = {})
                    }
                }
            } }
            await { visible("Find a format") && visible(catalog.first().category) }
            assertFalse(visible(lastTitle))
            await {
                visit { it.isVisibleToUser && it.isScrollable && it.contentDescription?.toString() != "Document tabs" && it.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) }
                visible(lastTitle)
            }
            // Three nonempty results exceed this short viewport; keeping the old index hides the first.
            setQuery("ppt")
            await { visible("PPT") && visible("Presentations") && !visible(lastTitle) }
            click("Clear search")
            await { visible(catalog.first().category) && !visible(lastTitle) }
        }
    }

    @Test fun homeExamplesAndOpenRemainSeparateInShortLargeTextViewport() {
        val opened = AtomicBoolean()
        val examples = AtomicBoolean()
        val height = mutableStateOf(180.dp)
        val history = List(6) { index -> StoredDocument(index.toString().repeat(64),
            DocumentMetadata("Recent $index.pdf", "application/pdf", 1024), DocumentState(), index.toLong()) }
        ActivityScenario.launch(ReaderTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                    MaterialTheme {
                        Box(Modifier.fillMaxSize()) {
                            ReaderHome({ opened.set(true) }, Modifier.width(280.dp).height(height.value).clipToBounds(), recent = history,
                                onExamples = { examples.set(true) })
                        }
                    }
                }
            } }
            await {
                val first = bounds("Open"); val second = bounds("Examples")
                first != null && second != null && !Rect.intersects(first, second)
            }
            click("Open"); click("Examples")
            assertTrue(opened.get()); assertTrue(examples.get())
            scenario.onActivity { height.value = 320.dp }
            await { bounds(history.first().title)?.height()?.let { it > 0 } == true }
            val titleHeight = requireNotNull(bounds(history.first().title)).height()
            await {
                visit { it.isVisibleToUser && it.isScrollable && it.contentDescription?.toString() != "Document tabs" && it.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) }
                val last = bounds(history.last().title)
                last != null && last.height() >= titleHeight
            }
        }
    }

    private fun setQuery(value: String) = await {
        visit { node -> node.isVisibleToUser && node.isEditable && node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }) }
    }

    private fun bounds(label: String): Rect? {
        var found: Rect? = null
        visit { node ->
            if (node.isVisibleToUser && matches(node, label)) { found = Rect().also(node::getBoundsInScreen); true } else false
        }
        return found
    }
    private fun click(label: String) = await { visit { it.isVisibleToUser && matches(it, label) && clickNode(it) } }
    private fun clickNode(node: AccessibilityNodeInfo, depth: Int = 0): Boolean {
        if (node.isEnabled && node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        if (depth == 4) return false
        val parent = node.parent ?: return false
        return try { clickNode(parent, depth + 1) } finally { @Suppress("DEPRECATION") parent.recycle() }
    }
    private fun visible(label: String) = visit { it.isVisibleToUser && matches(it, label) }
    private fun matches(node: AccessibilityNodeInfo, label: String) = node.text?.toString() == label || node.contentDescription?.toString() == label
    private fun await(check: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (!check()) {
            if (SystemClock.uptimeMillis() >= deadline) throw AssertionError("Examples UI did not reach the expected state")
            SystemClock.sleep(50)
        }
    }
    private fun visit(test: (AccessibilityNodeInfo) -> Boolean): Boolean {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return false
        return try { visit(root, 0, intArrayOf(0), test) } finally { @Suppress("DEPRECATION") root.recycle() }
    }
    private fun visit(node: AccessibilityNodeInfo, depth: Int, count: IntArray, test: (AccessibilityNodeInfo) -> Boolean): Boolean {
        if (depth > 48 || count[0]++ > 4096) return false
        if (test(node)) return true
        repeat(node.childCount) { index ->
            val child = node.getChild(index) ?: return@repeat
            try { if (visit(child, depth + 1, count, test)) return true }
            finally { @Suppress("DEPRECATION") child.recycle() }
        }
        return false
    }
}
