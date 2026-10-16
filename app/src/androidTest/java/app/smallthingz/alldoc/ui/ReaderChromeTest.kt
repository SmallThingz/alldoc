package app.smallthingz.alldoc.ui

import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
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
class ReaderChromeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun explicitTextViewPersistsSearchReplacesTheTopBarAndFullscreenStaysAtTheBottom() {
        val file = File(instrumentation.targetContext.cacheDir, "reader-views-${UUID.randomUUID()}.html")
        file.writeText("<html><body><pre>" + UUID.randomUUID().toString() + "\n" + (0 until 160).joinToString("\n") { "AllDoc reader view line $it" } + "</pre></body></html>")
        try { ActivityScenario.launch<MainActivity>(Intent(instrumentation.targetContext, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)).use { scenario ->
            open(scenario, file)
            var minimumTarget = 0f
            scenario.onActivity { minimumTarget = 48f * it.resources.displayMetrics.density }
            var chrome = emptyList<Rect>()
            await {
                val visible = listOfNotNull(bounds("Reading area")) +
                    listOf("Full screen", "Export", "File info").mapNotNull(::actionBounds)
                if (visible.size != 4 || visible[2].width() < minimumTarget - 2f ||
                    visible[2].height() < minimumTarget - 2f) false
                else { chrome = visible; true }
            }
            val (area, fullScreen, export, title) = chrome
            assertTrue(fullScreen.centerY() > area.centerY())
            assertTrue(export.centerY() < area.centerY())
            assertTrue("Export must fit inside the reading surface", area.contains(export))
            assertFalse("The filename must leave space for Export", Rect.intersects(title, export))
            click("Reading view")
            await { listOf("Normal view", "Present view", "Text view").all { bounds(it) != null } }
            click("Text view")
            await { model(scenario) { it.textMode && !it.textLoading } && bounds("Return to pages") != null }
            scenario.onActivity { ViewModelProvider(it)[ReaderModel::class.java].flushDocumentState() }
            open(scenario, file, text = true)
            assertNotNull("Explicit Text preference survives reopening the same bytes", bounds("Return to pages"))
            click("Return to pages")
            await { model(scenario) { !it.textMode } && bounds("Document pages") != null }
            open(scenario, file)
            click("Find in document")
            await { bounds("Close search") != null && bounds("Find in text") != null }
            assertNull("Search replaces the normal top actions", bounds("Export"))
            val search = requireNotNull(bounds("Find in text"))
            assertTrue("Search occupies the top of the reading surface", search.top < area.top + area.height() / 4)
            click("Close search")
            await { bounds("Export") != null && bounds("Find in text") == null }
            assertTrue(model(scenario) { !it.textMode })
        } } finally { file.delete() }
    }

    @Test fun savedVisualPreviewsTogglePageViewsWithoutAnExtraPanel() {
        val context = instrumentation.targetContext
        val file = File(context.cacheDir, "reader-preview-${UUID.randomUUID()}.key")
        val preview = android.graphics.Bitmap.createBitmap(120, 80, android.graphics.Bitmap.Config.ARGB_8888)
        try {
            preview.eraseColor(android.graphics.Color.BLUE)
            java.util.zip.ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(java.util.zip.ZipEntry("QuickLook/Thumbnail.jpg"))
                assertTrue(preview.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, zip)); zip.closeEntry()
            }
        } finally { preview.recycle() }
        try { ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)).use { scenario ->
            open(scenario, file)
            scenario.onActivity { ViewModelProvider(it)[ReaderModel::class.java].choosePageView(false) }
            await { model(scenario) { it.info?.textAvailable == false && !it.horizontalPages } }
            click("Reading view")
            await { model(scenario) { it.horizontalPages } }
            assertNull("Two views need no chooser", bounds("Present view"))
            assertNull(bounds("Text view"))
            click("Reading view")
            await { model(scenario) { !it.horizontalPages } }
        } } finally { file.delete() }
    }

    private fun open(scenario: ActivityScenario<MainActivity>, file: File, text: Boolean = false) {
        var previous = 0L
        scenario.onActivity { val model = ViewModelProvider(it)[ReaderModel::class.java]
            previous = model.documentId; model.open(Uri.fromFile(file)) }
        await { model(scenario) {
            assertNull(it.error)
            it.documentId > previous && it.currentTitle == file.name && it.info != null && !it.loading &&
                it.textMode == text && (!text || !it.textLoading)
        } && bounds(if (text) "Return to pages" else "Document pages") != null }
    }

    private fun model(scenario: ActivityScenario<MainActivity>, block: (ReaderModel) -> Boolean): Boolean {
        var result = false
        scenario.onActivity { result = block(ViewModelProvider(it)[ReaderModel::class.java]) }
        return result
    }

    private fun click(label: String) = await { visit { node -> matches(node, label) && clickOwner(node) } }
    private fun bounds(label: String): Rect? {
        var result: Rect? = null
        visit { if (matches(it, label)) { result = Rect().also(it::getBoundsInScreen); true } else false }
        return result
    }
    private fun actionBounds(label: String): Rect? {
        var result: Rect? = null
        visit { node ->
            if (matches(node, label)) result = actionBounds(node)
            result != null
        }
        return result
    }
    @Suppress("DEPRECATION")
    private fun actionBounds(node: AccessibilityNodeInfo, depth: Int = 0): Rect? {
        if (depth >= 12 || !node.refresh() || !node.isVisibleToUser || !node.isEnabled) return null
        if (node.isClickable) return Rect().also(node::getBoundsInScreen).takeUnless { it.isEmpty }
        val parent = node.parent ?: return null
        return try { actionBounds(parent, depth + 1) } finally { parent.recycle() }
    }
    private fun matches(node: AccessibilityNodeInfo, label: String) = node.isVisibleToUser &&
        (node.text?.toString() == label || node.contentDescription?.toString() == label)

    private fun await(check: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            if (check()) return
            SystemClock.sleep(25)
        }
        fail("Reader chrome did not reach the expected state")
    }

    @Suppress("DEPRECATION")
    private fun clickOwner(node: AccessibilityNodeInfo, depth: Int = 0): Boolean {
        if (node.isClickable) return node.isEnabled && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (depth >= 12) return false
        val parent = node.parent ?: return false
        return try { clickOwner(parent, depth + 1) } finally { parent.recycle() }
    }

    @Suppress("DEPRECATION")
    private fun visit(test: (AccessibilityNodeInfo) -> Boolean): Boolean {
        fun walk(node: AccessibilityNodeInfo, depth: Int): Boolean {
            if (depth > 24 || !node.refresh()) return false
            if (test(node)) return true
            for (index in 0 until minOf(node.childCount, 96)) {
                val child = node.getChild(index) ?: continue
                try { if (walk(child, depth + 1)) return true } finally { child.recycle() }
            }
            return false
        }
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return false
        return try { walk(root, 0) } finally { root.recycle() }
    }
}
