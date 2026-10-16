package app.smallthingz.alldoc.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
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
class ReaderAccessibleTextTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun normalTextPagesExposeAuthoredContentThroughTheAccessibleReadAction() {
        val file = File(instrumentation.targetContext.cacheDir, "accessible-${UUID.randomUUID()}.html")
        val marker = "Authored accessible text हिन्दी 日本語"
        file.writeText("<html><body><pre>" + marker + "\n" + (0 until 100).joinToString("\n") { "Document line $it" } + "</pre></body></html>")
        try { verifyReadAndReturn(file, marker, horizontal = false, notes = false) }
        finally { file.delete() }
    }

    @Test fun presentationReadAndReturnPreservesThePage() {
        val file = File(instrumentation.targetContext.cacheDir, "accessible-${UUID.randomUUID()}.pdf")
        val marker = "Authored accessible PDF"
        writeReaderPdf(file, pages = 3, marker = marker)
        try { verifyReadAndReturn(file, marker, horizontal = true, notes = false) }
        finally { file.delete() }
    }

    @Test fun presentationTextAndBackKeepRealSpeakerNotesAndTheSelectedPage() {
        val context = instrumentation.targetContext
        val file = File(context.cacheDir, "accessible-notes-${UUID.randomUUID()}.pptx")
        context.assets.open("examples/office/office-guide.pptx").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        try { verifyReadAndReturn(file, "AllDoc field guide", horizontal = true, notes = true) }
        finally { file.delete() }
    }

    private fun verifyReadAndReturn(file: File, marker: String, horizontal: Boolean, notes: Boolean) {
        ActivityScenario.launch<MainActivity>(Intent(instrumentation.targetContext, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)).use { scenario ->
            scenario.onActivity { ViewModelProvider(it)[ReaderModel::class.java].open(Uri.fromFile(file)) }
            await {
                var ready = false
                scenario.onActivity {
                    val model = ViewModelProvider(it)[ReaderModel::class.java]
                    assertNull(model.error)
                    ready = model.currentTitle == file.name && model.info != null && !model.loading
                }
                ready
            }
            scenario.onActivity {
                val model = ViewModelProvider(it)[ReaderModel::class.java]
                model.choosePageView(horizontal)
                model.goToPage(1)
                model.setNotesVisible(notes)
            }
            await {
                var ready = false
                scenario.onActivity {
                    val model = ViewModelProvider(it)[ReaderModel::class.java]
                    ready = model.page == 1 && model.bitmap != null && !model.pageLoading && !model.notesLoading
                }
                ready && hasLabel("Document pages") && (!notes || hasAuthoredText("Speaker note 2: Normal view scrolls from top to bottom."))
            }
            assertTrue("The page container must offer a read action without requiring Search", visit { node ->
                node.actionList.firstOrNull { it.label?.toString() == "Read document text" }
                    ?.let { node.performAction(it.id) } ?: false
            })
            await { hasAuthoredText(marker) && hasLabel("Return to pages") &&
                (!notes || hasAuthoredText("Speaker note 2: Normal view scrolls from top to bottom.")) }
            assertFalse("Reading text must not open a search input", hasLabel("Find in text"))
            scenario.onActivity {
                val model = ViewModelProvider(it)[ReaderModel::class.java]
                assertTrue(model.textMode)
                assertEquals(horizontal, model.horizontalPages)
                assertEquals(notes, model.notesVisible)
                assertEquals(1, model.page)
                if (Build.VERSION.SDK_INT >= 30) assertFalse("Read action must not summon the keyboard",
                    it.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true)
            }
            assertTrue("Return must be an accessible clickable control", visit { node ->
                node.isVisibleToUser && (node.text?.toString() == "Return to pages" || node.contentDescription?.toString() == "Return to pages") && clickOwner(node)
            })
            await {
                var ready = false
                scenario.onActivity {
                    val model = ViewModelProvider(it)[ReaderModel::class.java]
                    assertTrue(model.readerVisible)
                    assertEquals(horizontal, model.horizontalPages)
                    assertEquals(notes, model.notesVisible)
                    ready = !model.textMode && model.page == 1 && model.bitmap != null && !model.pageLoading
                }
                ready && hasLabel("Document pages") && (!notes || hasAuthoredText("Speaker note 2: Normal view scrolls from top to bottom."))
            }
        }
    }

    // Material buttons expose the label as a child; the button owns ACTION_CLICK.
    @Suppress("DEPRECATION")
    private fun clickOwner(node: AccessibilityNodeInfo, depth: Int = 0): Boolean {
        if (!node.isVisibleToUser || !node.isEnabled) return false
        if (node.isClickable) return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (depth >= 8) return false
        val parent = node.parent ?: return false
        return try { clickOwner(parent, depth + 1) } finally { parent.recycle() }
    }

    private fun hasLabel(label: String) = visit { it.isVisibleToUser &&
        (it.contentDescription?.toString() == label || it.text?.toString() == label) }

    private fun hasAuthoredText(marker: String) = visit {
        it.isVisibleToUser && it.text?.toString()?.contains(marker) == true
    }

    @Suppress("DEPRECATION")
    private fun visit(predicate: (AccessibilityNodeInfo) -> Boolean): Boolean {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return false
        var visited = 0
        fun walk(node: AccessibilityNodeInfo, depth: Int): Boolean {
            if (depth > 32 || visited++ > 1024) return false
            if (predicate(node)) return true
            for (index in 0 until minOf(node.childCount, 128)) {
                val child = node.getChild(index) ?: continue
                try { if (walk(child, depth + 1)) return true } finally { child.recycle() }
            }
            return false
        }
        return try { walk(root, 0) } finally { root.recycle() }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            if (condition()) return
            SystemClock.sleep(25)
        }
        fail("Accessible reading action did not reach its expected content and page state")
    }
}
