package app.smallthingz.alldoc.ui

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.smallthingz.alldoc.MainActivity
import app.smallthingz.alldoc.state.TextPosition
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderSearchRestorationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun restoredSearchReconcilesFreshModelAndReturnsToItsOriginalSurface() {
        for (textOrigin in listOf(false, true)) withDocument { file, scenario ->
            scenario.onActivity { activity -> ViewModelProvider(activity)[ReaderModel::class.java].apply {
                choosePageView(true); goToPage(1); setNotesVisible(true)
            } }
            await { var ready = false; scenario.onActivity { activity ->
                val model = ViewModelProvider(activity)[ReaderModel::class.java]
                ready = model.page == 1 && !model.pageLoading && model.bitmap != null
            }; ready }
            if (textOrigin) action("Read document text")
            click("Find in document")
            await { visible("Close search") }
            setQuery("fixture")
            var old: ReaderModel? = null
            scenario.onActivity { activity ->
                old = ViewModelProvider(activity)[ReaderModel::class.java]
                old.flushDocumentState()
                activity.viewModelStore.clear()
            }
            scenario.recreate()
            await {
                var restored = false
                scenario.onActivity { activity ->
                    val model = ViewModelProvider(activity)[ReaderModel::class.java]
                    assertNotSame(old, model); assertNull(model.error)
                    restored = model.info != null && !model.loading && model.textMode && !model.textLoading
                }
                restored && visible("Close search") && queryIs("fixture")
            }
            scenario.onActivity { activity -> ViewModelProvider(activity)[ReaderModel::class.java].apply {
                assertEquals(1, page); assertTrue(horizontalPages); assertTrue(notesVisible)
            } }
            click("Close search")
            await { !visible("Close search") && visible(if (textOrigin) "Return to pages" else "Document pages") }
            if (textOrigin) click("Return to pages")
            await { visible("Document pages") }
            scenario.onActivity { activity -> ViewModelProvider(activity)[ReaderModel::class.java].apply {
                assertFalse(textMode); assertTrue(readerVisible); assertEquals(1, page)
                assertTrue(horizontalPages); assertTrue(notesVisible)
            } }
        }
    }

    @Test fun laterSearchHitAndItsAnchorSurviveRecreationButNewQueriesAndDocumentsReset() = withDocument(pages = 12) { file, scenario ->
        click("Find in document"); await { visible("Close search") }; setQuery("fixture")
        await { visible("1 / 12") }
        repeat(6) {
            click("Next match")
            await { visible("${it + 2} / 12") }
        }
        var selectedPosition = TextPosition()
        var selectedHighlight: String? = null
        await {
            var anchorReached = false
            scenario.onActivity {
                val model = ViewModelProvider(it)[ReaderModel::class.java]
                selectedPosition = model.textPosition
                val offsets = searchOffsets(model.text.orEmpty(), "fixture")
                anchorReached = offsets.size == 12 && selectedPosition.offset in (offsets[5] + 1)..offsets[6]
            }
            selectedHighlight = activeHighlight(scenario, "fixture")
            anchorReached && selectedHighlight?.startsWith("fixture 7") == true
        }
        scenario.recreate()
        await { visible("7 / 12") && activeHighlight(scenario, "fixture") == selectedHighlight }
        SystemClock.sleep(150) // Let restoration and search reveal publish their final reading anchor.
        scenario.onActivity {
            val restored = ViewModelProvider(it)[ReaderModel::class.java].textPosition
            assertEquals("Rotation must preserve the selected result's reading anchor", selectedPosition.offset, restored.offset)
            assertEquals(selectedPosition.fraction, restored.fraction, .01f)
        }
        scrollText(scenario, -240f)
        val manualPosition = settledTextPosition(scenario, selectedPosition)
        scenario.recreate()
        await { visible("7 / 12") && activeHighlight(scenario, "fixture") == selectedHighlight }
        val restoredManual = settledTextPosition(scenario)
        assertEquals("Rotation must preserve manual scrolling after a completed search", manualPosition.offset, restoredManual.offset)
        assertEquals(manualPosition.fraction, restoredManual.fraction, .01f)
        setQuery("Readable")
        await { visible("1 / 12") && activeHighlight(scenario, "Readable")?.startsWith("Readable PDF content") == true }
        repeat(2) {
            click("Next match")
            await { visible("${it + 2} / 12") }
        }
        val nextFile = File(file.parentFile, "search-next-${UUID.randomUUID()}.pdf")
        writeReaderPdf(nextFile, pages = 12, marker = "Another PDF fixture")
        try {
            scenario.onActivity { ViewModelProvider(it)[ReaderModel::class.java].open(Uri.fromFile(nextFile), mimeHint = "application/pdf") }
            await {
                var ready = false
                scenario.onActivity {
                    val model = ViewModelProvider(it)[ReaderModel::class.java]
                    ready = model.source == Uri.fromFile(nextFile) && model.info != null && !model.loading
                }
                ready && !visible("Close search")
            }
            click("Find in document"); await { visible("Close search") && queryIs("") }; setQuery("Readable")
            await { visible("1 / 12") && activeHighlight(scenario, "Readable")?.startsWith("Readable PDF content") == true }
        } finally { nextFile.delete() }
    }

    private fun composeRoot(view: View): ViewRootForTest? = (view as? ViewRootForTest) ?: (view as? ViewGroup)?.let { group ->
        (0 until group.childCount).firstNotNullOfOrNull { composeRoot(group.getChildAt(it)) }
    }

    private fun scrollText(scenario: ActivityScenario<MainActivity>, delta: Float) {
        scenario.onActivity { activity ->
            fun scroll(node: SemanticsNode): Boolean {
                if (node.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null &&
                    node.config.getOrNull(SemanticsActions.ScrollBy)?.action?.invoke(0f, delta) == true) return true
                return node.children.any(::scroll)
            }
            assertTrue(scroll(checkNotNull(composeRoot(activity.window.decorView)).semanticsOwner.unmergedRootSemanticsNode))
        }
    }

    private fun settledTextPosition(scenario: ActivityScenario<MainActivity>, differentFrom: TextPosition? = null): TextPosition {
        var position = TextPosition()
        var unchangedAt = SystemClock.uptimeMillis()
        await {
            scenario.onActivity {
                val current = ViewModelProvider(it)[ReaderModel::class.java].textPosition
                if (current != position) { position = current; unchangedAt = SystemClock.uptimeMillis() }
            }
            position != differentFrom && SystemClock.uptimeMillis() - unchangedAt >= 200
        }
        return position
    }

    // The selected hit has the ordinary match span plus the overriding active color span.
    // Inspect the actual text annotation so a retained count/query cannot hide a wrong highlight.
    private fun activeHighlight(scenario: ActivityScenario<MainActivity>, query: String): String? {
        var result: String? = null
        scenario.onActivity { activity ->
            fun visit(node: SemanticsNode) {
                node.config.getOrNull(SemanticsProperties.Text)?.forEach { text ->
                    text.spanStyles.groupBy { it.start to it.end }.forEach { (range, spans) ->
                        if (text.text.substring(range.first, range.second).equals(query, ignoreCase = true) &&
                            spans.map { it.item.background }.distinct().size > 1) {
                            result = text.text.substring(range.first, minOf(text.length, range.second + 20))
                        }
                    }
                }
                node.children.forEach(::visit)
            }
            composeRoot(activity.window.decorView)?.semanticsOwner?.unmergedRootSemanticsNode?.let(::visit)
        }
        return result
    }

    @Test fun failedFreshModelOpenWithRestoredSearchStillAllowsSystemBack() = withDocument { file, scenario ->
        click("Find in document"); await { visible("Close search") }; setQuery("fixture")
        assertTrue(file.delete())
        scenario.onActivity { it.viewModelStore.clear() }
        scenario.recreate()
        await { var failed = false; scenario.onActivity {
            val model = ViewModelProvider(it)[ReaderModel::class.java]
            failed = !model.loading && model.info == null && model.error != null
        }; failed }
        click("Close")
        awaitErrorDialogClosed(scenario)
        assertTrue(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
        await { scenario.state == Lifecycle.State.DESTROYED }
    }

    @Test fun failedRestoredDocumentAppearanceNeverBecomesGlobalAppearance() = withDocument { file, scenario ->
        var defaultAppearance: app.smallthingz.alldoc.theme.ThemeSettings? = null
        val stateWorker = ReaderModel::class.java.getDeclaredField("StateWorker").apply { isAccessible = true }
            .get(null) as java.util.concurrent.ExecutorService
        stateWorker.submit {}.get(15, java.util.concurrent.TimeUnit.SECONDS)
        scenario.onActivity {
            val model = ViewModelProvider(it)[ReaderModel::class.java]
            assertTrue(model.showReader(false))
            defaultAppearance = model.appearanceForControls // Home controls expose the real global default.
            assertTrue(model.showReader(true))
            val distinct = if (defaultAppearance.mode == app.smallthingz.alldoc.theme.ReadingMode.SEPIA)
                app.smallthingz.alldoc.theme.ReadingMode.INVERT else app.smallthingz.alldoc.theme.ReadingMode.SEPIA
            model.setAppearance(app.smallthingz.alldoc.theme.ThemeSettings(distinct))
            assertNotEquals(defaultAppearance, model.appearanceForControls)
        }
        click("Reading appearance"); await { visible("Original") }
        assertTrue(file.delete())
        scenario.onActivity { it.viewModelStore.clear() }
        scenario.recreate()
        await { var failed = false; scenario.onActivity {
            val model = ViewModelProvider(it)[ReaderModel::class.java]
            failed = !model.loading && model.info == null && model.error != null
        }; failed }
        click("Close")
        awaitErrorDialogClosed(scenario)
        await { !visible("Original") }
        assertFalse("A document popup must not expose global appearance controls", visible("Reset zoom and rotation"))
        stateWorker.submit {}.get(15, java.util.concurrent.TimeUnit.SECONDS)
        scenario.onActivity {
            val model = ViewModelProvider(it)[ReaderModel::class.java]
            assertNull(model.documentHash); assertNull(model.info)
            assertEquals("Failed document appearance must not replace the global default", defaultAppearance, model.appearanceForControls)
        }
        assertTrue(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
        await { scenario.state == Lifecycle.State.DESTROYED }
    }

    private fun awaitErrorDialogClosed(scenario: ActivityScenario<MainActivity>) = await {
        var activityOwnsBack = false
        scenario.onActivity {
            activityOwnsBack = ViewModelProvider(it)[ReaderModel::class.java].error == null &&
                it.window.decorView.hasWindowFocus()
        }
        activityOwnsBack && !visible("Couldn’t open document")
    }

    private fun withDocument(pages: Int = 4, test: (File, ActivityScenario<MainActivity>) -> Unit) {
        val context = instrumentation.targetContext
        val file = File(context.cacheDir, "search-restored-${UUID.randomUUID()}.pdf")
        writeReaderPdf(file, pages = pages)
        try { ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW).setDataAndType(Uri.fromFile(file), "application/pdf")).use { scenario ->
            await { var ready = false; scenario.onActivity {
                val model = ViewModelProvider(it)[ReaderModel::class.java]
                assertNull(model.error); ready = model.info != null && !model.loading && model.bitmap != null
            }; ready }
            test(file, scenario)
        } } finally { file.delete() }
    }
    private fun queryIs(value: String) = visit { it.isVisibleToUser && it.className?.toString() == "android.widget.EditText" && it.text?.toString().orEmpty() == value }
    private fun setQuery(value: String) = await { visit {
        it.isVisibleToUser && it.className?.toString() == "android.widget.EditText" && it.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) })
    } }
    private fun action(label: String) = await { visit { node -> node.isVisibleToUser && node.actionList.firstOrNull {
        it.label?.toString() == label }?.let { node.performAction(it.id) } == true } }
    private fun click(label: String) = await { visit { it.isVisibleToUser && matches(it, label) && clickOwner(it) } }
    private fun visible(label: String) = visit { it.isVisibleToUser && matches(it, label) }
    private fun matches(node: AccessibilityNodeInfo, label: String) = node.text?.toString() == label || node.contentDescription?.toString() == label
    @Suppress("DEPRECATION") private fun clickOwner(node: AccessibilityNodeInfo, depth: Int = 0): Boolean {
        if (depth > 16 || !node.refresh()) return false
        if (node.isClickable) return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        val parent = node.parent ?: return false
        return try { clickOwner(parent, depth + 1) } finally { parent.recycle() }
    }
    @Suppress("DEPRECATION") private fun visit(test: (AccessibilityNodeInfo) -> Boolean): Boolean {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return false
        var count = 0
        fun walk(node: AccessibilityNodeInfo, depth: Int): Boolean {
            if (depth > 32 || count++ > 1024) return false
            if (test(node)) return true
            for (index in 0 until minOf(node.childCount, 128)) {
                val child = node.getChild(index) ?: continue
                try { if (walk(child, depth + 1)) return true } finally { child.recycle() }
            }
            return false
        }
        return try { walk(root, 0) } finally { root.recycle() }
    }
    private fun await(test: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15000
        while (SystemClock.uptimeMillis() < deadline) { if (test()) return; SystemClock.sleep(25) }
        fail("Search restoration did not reach its expected visible state")
    }
}
