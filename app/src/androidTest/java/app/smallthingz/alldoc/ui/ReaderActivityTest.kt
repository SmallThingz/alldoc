package app.smallthingz.alldoc.ui

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.smallthingz.alldoc.MainActivity
import app.smallthingz.alldoc.theme.ReadingMode
import app.smallthingz.alldoc.theme.ThemeSettings
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderActivityTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun fixture(name: String) = Uri.parse("content://app.smallthingz.alldoc.test.reader-fixtures/$name")
    private fun launch() = ActivityScenario.launch<MainActivity>(
        Intent(instrumentation.targetContext, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW).setDataAndType(fixture("one"), "text/html")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    )
    private fun model(scenario: ActivityScenario<MainActivity>, action: (ReaderModel) -> Unit) {
        scenario.onActivity { action(ViewModelProvider(it)[ReaderModel::class.java]) }
    }
    private fun await(scenario: ActivityScenario<MainActivity>, timeout: Long = 15_000, test: (ReaderModel) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false
            model(scenario) {
                if (it.info != null) it.loadText() // Explicit extraction for text/export assertions; the reader stays in page mode.
                ready = test(it)
            }
            if (ready) return
            SystemClock.sleep(25)
        }
        model(scenario) { fail("Reader did not settle: ${it.error ?: it.info?.title}") }
    }

    @Test fun opensTextAsPagesAndRetainsSessionOnRecreation() {
        launch().use { scenario ->
            await(scenario) { it.info != null && it.text != null && !it.textLoading && it.bitmap != null && !it.pageLoading }
            var retained: ReaderModel? = null
            model(scenario) { retained = it; it.loadText() }
            await(scenario) { it.text != null }
            scenario.recreate()
            model(scenario) {
                assertSame(retained, it)
                assertTrue(it.text!!.contains("café, क्षि"))
                assertNotNull("Normal view must render a text page", it.bitmap)
                assertTrue(it.info!!.reflowable)
                assertFalse(it.textMode)
                assertNull(it.error)
            }
        }
    }

    @Test fun queuedCancelAfterReadinessPreservesTheDocument() {
        launch().use { scenario ->
            await(scenario) { it.info != null && it.text != null && !it.textLoading && it.bitmap != null && !it.pageLoading }
            model(scenario) {
                val identity = it.documentId
                val source = it.source
                val info = it.info
                val text = requireNotNull(it.text)
                assertFalse(it.loading)
                assertFalse(it.openingCancelled)
                it.cancelLoading()
                assertEquals(identity, it.documentId)
                assertEquals(source, it.source)
                assertSame(info, it.info)
                assertSame(text, it.text)
                assertNotNull(it.bitmap)
                assertFalse(it.textMode)
                assertFalse(it.openingCancelled)
                assertFalse(it.pageLoading)
                assertNull(it.error)
                it.loadText()
            }
            await(scenario) { it.text != null }
            model(scenario) {
                assertTrue(it.text!!.contains("fixture one"))
                assertFalse(it.openingCancelled)
                assertNull(it.error)
            }
        }
    }

    @Test fun supersededOpenCannotPublishAndCancelledOpenRecovers() {
        launch().use { scenario ->
            await(scenario) { it.info != null }
            model(scenario) { it.open(fixture("slow")) }
            SystemClock.sleep(60)
            model(scenario) { it.open(fixture("two")) }
            await(scenario) { it.info?.title == "two.html" && it.text != null && !it.textLoading }
            SystemClock.sleep(500)
            model(scenario) { assertEquals("two.html", it.info?.title); it.open(fixture("slow")) }
            SystemClock.sleep(60)
            model(scenario) { it.cancelLoading() }
            SystemClock.sleep(500)
            model(scenario) {
                assertFalse(it.loading)
                assertNull(it.error)
                it.open(fixture("one"))
            }
            await(scenario) { it.info?.title == "one.html" && it.text != null && !it.textLoading }
        }
    }

    @Test fun stalledExportCancelsAndLeavesTheDocumentUsable() {
        try {
            launch().use { scenario ->
                model(scenario) { it.open(fixture("large")) }
                await(scenario) { it.info?.title == "large.html" && it.text != null && !it.textLoading }
                model(scenario) { it.prepareExport(ReaderModel.ExportKind.TEXT); it.export(fixture("stalled")) }
                SystemClock.sleep(250)
                model(scenario) { assertTrue(it.exporting); it.cancelExport() }
                await(scenario, 4000) { !it.exporting }
                model(scenario) {
                    assertNull(it.error)
                    assertNotNull(it.info)
                    it.prepareExport(ReaderModel.ExportKind.TEXT); it.export(fixture("export"))
                }
                await(scenario) { !it.exporting }
                val text = instrumentation.context.contentResolver.openInputStream(fixture("export"))!!.bufferedReader().use { it.readText() }
                assertEquals(8192, text.lineSequence().count { it == "AllDoc export fixture" })
            }
        } finally {
            instrumentation.context.contentResolver.call(fixture("stalled"), "releasePipes", null, null)
        }
    }

    @Test fun textAndJsonExportPreserveUnicodeAcrossWriterChunks() {
        val content = "a".repeat(8191) + "😀".repeat(5000) + "b" + "😀".repeat(5000) + " café, क्षि, 🇦🇺"
        val input = File.createTempFile("export-unicode-", ".html", instrumentation.targetContext.cacheDir).canonicalFile
        try {
            input.writeText("<html><body><pre>$content</pre></body></html>", Charsets.UTF_8)
            launch().use { scenario ->
                model(scenario) { it.open(Uri.fromFile(input)) }
                await(scenario) { it.info?.title == input.name && !it.loading }
                for (kind in listOf(ReaderModel.ExportKind.TEXT, ReaderModel.ExportKind.JSON)) {
                    model(scenario) {
                        assertNotNull(it.prepareExport(kind))
                        it.export(fixture("export"))
                    }
                    await(scenario) { !it.exporting }
                    model(scenario) { assertNull(it.error) }
                    val bytes = instrumentation.context.contentResolver.openInputStream(fixture("export"))!!.use { it.readBytes() }
                    val serialized = bytes.toString(Charsets.UTF_8)
                    assertTrue("The fixture must split a supplementary scalar at a writer boundary",
                        (8192 until serialized.length step 8192).any {
                            serialized[it - 1].isHighSurrogate() && serialized[it].isLowSurrogate()
                        })
                    if (kind == ReaderModel.ExportKind.TEXT) assertArrayEquals(content.toByteArray(Charsets.UTF_8), bytes)
                    else assertEquals(content, JSONObject(serialized).getJSONArray("sections").getJSONObject(0)
                        .getJSONArray("blocks").getJSONObject(0).getString("text"))
                }
            }
        } finally { input.delete() }
    }

    @Test fun accessibleAppearanceControlsPersistAcrossActivityLifetimes() {
        launch().use { scenario ->
            await(scenario) { it.info != null }
            model(scenario) { it.setAppearance(ThemeSettings(ReadingMode.ORIGINAL)) }
            clickLabel("Reading appearance")
            clickLabel("Paper")
            await(scenario) { it.settings.mode == ReadingMode.SEPIA }
            // Appearance stays open so image handling can be adjusted without reopening.
            clickLabel("Recolor")
            await(scenario) { !it.settings.preserveImages }
            assertTrue(instrumentation.uiAutomation.performGlobalAction(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
            awaitAppearanceDismissed()
        }
        launch().use { scenario ->
            await(scenario) { it.info != null && it.settings == ThemeSettings(ReadingMode.SEPIA, false) }
            model(scenario) { it.setAppearance(ThemeSettings(ReadingMode.ORIGINAL)) }
        }
    }

    private fun awaitAppearanceDismissed() {
        val deadline = SystemClock.uptimeMillis() + 5000
        var diagnostic = "No active window"
        while (SystemClock.uptimeMillis() < deadline) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            val labels = StringBuilder()
            val dismissed = root != null &&
                findControl(root, "Reading appearance", 0, intArrayOf(0), labels, activate = false) &&
                !findControl(root, "Paper", 0, intArrayOf(0), labels, activate = false)
            diagnostic = "package=${root?.packageName}, labels=$labels"
            @Suppress("DEPRECATION")
            root?.recycle()
            if (dismissed) return
            SystemClock.sleep(25)
        }
        fail("Appearance did not dismiss before closing its activity: $diagnostic")
    }

    private fun clickLabel(label: String) {
        val deadline = SystemClock.uptimeMillis() + 5000
        var diagnostic = "No active window"
        while (SystemClock.uptimeMillis() < deadline) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            val labels = StringBuilder()
            val clicked = root?.let { findControl(it, label, 0, intArrayOf(0), labels) } == true
            diagnostic = "package=${root?.packageName}, class=${root?.className}, labels=$labels"
            @Suppress("DEPRECATION")
            root?.recycle()
            if (clicked) return
            SystemClock.sleep(25)
        }
        fail("No labeled clickable accessibility control: $label; $diagnostic")
    }

    @Suppress("DEPRECATION")
    private fun findControl(node: android.view.accessibility.AccessibilityNodeInfo, label: String,
        depth: Int, visited: IntArray, labels: StringBuilder, activate: Boolean = true): Boolean {
        if (depth > 24 || visited[0]++ >= 512 || !node.refresh()) return false
        val text = node.text?.toString()
        val description = node.contentDescription?.toString()
        if (node.packageName?.toString() == "app.smallthingz.alldoc" && labels.length < 768 &&
            (!text.isNullOrBlank() || !description.isNullOrBlank())) {
            labels.append("[").append((description ?: text).orEmpty().take(80)).append("]")
        }
        if (node.packageName?.toString() == "app.smallthingz.alldoc" &&
            (text?.lineSequence()?.any { it == label } == true || description == label) &&
            node.isVisibleToUser && (!activate || clickControl(node))) return true
        for (index in 0 until minOf(node.childCount, 64)) {
            val child = node.getChild(index) ?: continue
            try { if (findControl(child, label, depth + 1, visited, labels, activate)) return true }
            finally { child.recycle() }
        }
        return false
    }

    @Suppress("DEPRECATION")
    private fun clickControl(node: android.view.accessibility.AccessibilityNodeInfo, depth: Int = 0): Boolean {
        if (node.isClickable) return node.isEnabled && node.isVisibleToUser &&
            node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
        if (depth == 16) return false
        val parent = node.parent ?: return false
        return try { clickControl(parent, depth + 1) } finally { parent.recycle() }
    }

    @Test fun appearanceAndExportUseTheSelectedDocument() {
        launch().use { scenario ->
            await(scenario) { it.info != null && it.text != null && !it.textLoading }
            var firstIdentity = 0L
            model(scenario) {
                firstIdentity = it.documentId
                it.setAppearance(it.settings.copy(mode = ReadingMode.ADAPTIVE))
                assertEquals("one.txt", it.prepareExport(ReaderModel.ExportKind.TEXT, firstIdentity))
                it.open(fixture("two"))
                assertNull(it.prepareExport(ReaderModel.ExportKind.JSON))
                it.export(fixture("export"))
            }
            await(scenario) { !it.exporting && it.info?.title == "two.html" }
            val text = instrumentation.context.contentResolver.openInputStream(fixture("export"))!!.bufferedReader().use { it.readText() }
            assertTrue(text.contains("fixture one"))
            assertFalse(text.contains("fixture two"))
            model(scenario) {
                assertNull(it.error)
                assertTrue(firstIdentity != it.documentId)
                assertTrue(it.canExport)
                assertNull("A queued action from the previous document must be rejected",
                    it.prepareExport(ReaderModel.ExportKind.TEXT, firstIdentity))
                assertTrue("A rejected action must not install a pending export lease", it.canExport)
                assertEquals("two.txt", it.prepareExport(ReaderModel.ExportKind.TEXT, it.documentId))
                it.export(fixture("export"))
            }
            await(scenario) { !it.exporting }
            val currentText = instrumentation.context.contentResolver.openInputStream(fixture("export"))!!.bufferedReader().use { it.readText() }
            assertTrue(currentText.contains("fixture two"))
            assertFalse(currentText.contains("fixture one"))
            model(scenario) { assertNull(it.error); it.setAppearance(it.settings.copy(mode = ReadingMode.ORIGINAL)) }
        }
    }
}
