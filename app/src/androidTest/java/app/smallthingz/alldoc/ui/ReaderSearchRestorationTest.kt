package app.smallthingz.alldoc.ui

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.smallthingz.alldoc.MainActivity
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

    private fun withDocument(test: (File, ActivityScenario<MainActivity>) -> Unit) {
        val context = instrumentation.targetContext
        val file = File(context.cacheDir, "search-restored-${UUID.randomUUID()}.pdf")
        writeReaderPdf(file, pages = 4)
        try { ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW).setDataAndType(Uri.fromFile(file), "application/pdf")).use { scenario ->
            await { var ready = false; scenario.onActivity {
                val model = ViewModelProvider(it)[ReaderModel::class.java]
                assertNull(model.error); ready = model.info != null && !model.loading && model.bitmap != null
            }; ready }
            test(file, scenario)
        } } finally { file.delete() }
    }
    private fun queryIs(value: String) = visit { it.isVisibleToUser && it.className?.toString() == "android.widget.EditText" && it.text?.toString() == value }
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
