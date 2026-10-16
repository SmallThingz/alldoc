package app.smallthingz.alldoc.ui

import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inspector.WindowInspector
import androidx.activity.BackEventCompat
import androidx.activity.ComponentDialog
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.findViewTreeOnBackPressedDispatcherOwner
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import app.smallthingz.alldoc.MainActivity
import app.smallthingz.alldoc.BuildConfig
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29) // Public WindowInspector resolves the real focused Dialog owner.
class ReaderPredictiveBackTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private enum class Surface(val probe: String, val destination: String, val motionProbe: String = probe) {
        SETTINGS("App theme", "Open"),
        APP("Version ${BuildConfig.VERSION_NAME}", "Open"),
        READER("Find in document", "Open"),
        TEXT("Return to pages", "Document pages", "AllDoc PDF fixture"),
        SEARCH("Close search", "Document pages"),
        SHEET("Original", "Document pages"),
        INFO("Type", "Document pages"),
        EXPORT("JSON", "Document pages"),
    }

    @Test fun realSurfacesMoveCancelWithoutNavigatingAndCommitExactlyOneLevel() {
        for (surface in Surface.entries) {
            val file = File(instrumentation.targetContext.cacheDir, "back-${UUID.randomUUID()}.pdf")
            writeReaderPdf(file)
            val intent = Intent(instrumentation.targetContext, MainActivity::class.java).setAction(Intent.ACTION_MAIN)
            try { ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                scenario.onActivity { ViewModelProvider(it)[ReaderModel::class.java].open(Uri.fromFile(file)) }
                await("Loaded owned fixture") {
                    var ready = false
                    scenario.onActivity {
                        val model = ViewModelProvider(it)[ReaderModel::class.java]
                        assertNull(model.error)
                        ready = model.info != null && !model.loading
                    }
                    ready
                }
                scenario.onActivity { val model = ViewModelProvider(it)[ReaderModel::class.java]
                    model.setTextMode(false) }
                await("Page surface") {
                    var rendered = false
                    scenario.onActivity { val model = ViewModelProvider(it)[ReaderModel::class.java]
                        rendered = model.bitmap != null && !model.pageLoading }
                    rendered && bounds("Document pages") != null
                }
                when (surface) {
                    Surface.SETTINGS -> { click("Home"); click("Settings") }
                    Surface.APP -> { click("Home"); click("About AllDoc") }
                    Surface.TEXT -> await("Accessible document text action") {
                        visit { node -> node.isVisibleToUser && node.actionList.firstOrNull {
                            it.label?.toString() == "Read document text"
                        }?.let { node.performAction(it.id) } == true }
                    }
                    Surface.SEARCH -> click("Find in document")
                    Surface.SHEET -> {
                        scenario.onActivity { ViewModelProvider(it)[ReaderModel::class.java].setAppearance(
                            app.smallthingz.alldoc.theme.ThemeSettings(app.smallthingz.alldoc.theme.ReadingMode.SYSTEM)) }
                        click("Reading appearance")
                    }
                    Surface.INFO -> click("File info")
                    Surface.EXPORT -> click("Export") { diagnostic(scenario, surface) }
                    Surface.READER -> Unit
                }
                if (surface == Surface.SEARCH || surface == Surface.TEXT) await("Text body ready") {
                    var ready = false
                    scenario.onActivity {
                        val model = ViewModelProvider(it)[ReaderModel::class.java]
                        ready = !model.textLoading && model.text?.contains("AllDoc PDF fixture") == true
                    }
                    ready && bounds("AllDoc PDF fixture") != null
                }
                if (surface == Surface.SHEET) {
                    val modes = listOf("System", "Light", "Dark", "Paper", "Original", "Contrast", "Invert")
                    await("Direct appearance choices") { modes.all { bounds(it) != null } }
                    val selected = modes.count { mode ->
                        visit { node -> node.isVisibleToUser && matches(node, mode) && selectedMode(node) }
                    }
                    assertEquals("Appearance exposes one selected mode without a nested menu", 1, selected)
                    var cards = emptyList<Rect>()
                    await("Appearance radio bounds", detail = {
                        modes.joinToString { "$it=${choiceBounds(it)}" } + "; " + activeWindowDiagnostic()
                    }) {
                        val found = modes.mapNotNull(::choiceBounds)
                        (found.size == modes.size).also { if (it) cards = found }
                    }
                    var target = 0f
                    scenario.onActivity { target = 48f * it.resources.displayMetrics.density }
                    cards.forEach { box ->
                        assertTrue("Theme controls retain accessible touch targets: $box", box.width() >= target - 2 && box.height() >= target - 2)
                        assertTrue("Theme cards must have equal widths", abs(box.width() - cards[0].width()) <= 2)
                        assertTrue("Theme cards must have equal heights", abs(box.height() - cards[0].height()) <= 2)
                    }
                    for (first in cards.indices) for (second in first + 1 until cards.size) {
                        assertFalse("Appearance choices must not overlap", Rect.intersects(cards[first], cards[second]))
                    }
                    var compactWidth = false
                    scenario.onActivity { compactWidth = it.resources.configuration.run { screenWidthDp >= 360 && fontScale <= 1.2f } }
                    if (compactWidth) assertTrue("Compact theme cards share rows", cards.groupBy { it.top }.values.any { it.size >= 4 })
                    assertTrue("Image handling choices must be direct", listOf("Auto", "Keep", "Dim", "Recolor").all { bounds(it) != null })
                }
                if (surface == Surface.EXPORT) await("Direct export formats") {
                    listOf("Text", "JSON", "Page image", "Print to PDF").all { bounds(it) != null }
                }
                if (surface == Surface.INFO || surface == Surface.EXPORT) {
                    val heading = settledBounds(if (surface == Surface.INFO) "File info" else "Export")
                    var height = 0
                    scenario.onActivity { height = it.window.decorView.height }
                    assertTrue("Information and export panels must start near the top edge", heading.top < height / 3)
                }
                // The fixed reader chrome is outside the Text transition. Measure its actual body,
                // while retaining the separate route probe for the one-level commit assertion.
                settledBounds(surface.probe) { diagnostic(scenario, surface) }
                val original = settledBounds(surface.motionProbe) { diagnostic(scenario, surface) }
                val initialState = state(scenario)
                var owner = dispatcher(scenario, surface in listOf(Surface.SHEET, Surface.INFO, Surface.EXPORT, Surface.APP))
                // A short progress step keeps the probe onscreen; disappearance is never motion evidence.
                start(scenario, owner, .10f)
                await("${surface.name}: partial progress must move actual content") {
                    bounds(surface.motionProbe)?.let { !near(it, original) } == true
                }
                assertEquals("${surface.name}: partial Back must not navigate", initialState, state(scenario))
                scenario.onActivity { owner.dispatchOnBackCancelled() }
                await("${surface.name}: cancellation must restore the visible surface") {
                    bounds(surface.motionProbe)?.let { near(it, original) } == true
                }
                assertEquals("${surface.name}: cancellation must preserve reader state", initialState, state(scenario))

                owner = dispatcher(scenario, surface in listOf(Surface.SHEET, Surface.INFO, Surface.EXPORT, Surface.APP))
                start(scenario, owner, .10f)
                await("${surface.name}: second gesture must receive progress") {
                    bounds(surface.motionProbe)?.let { !near(it, original) } == true
                }
                scenario.onActivity { owner.onBackPressed() }
                await("${surface.name}: commit must close exactly one surface",
                    detail = { diagnostic(scenario, surface) }) {
                    bounds(surface.probe) == null && bounds(surface.destination) != null
                }
                scenario.onActivity {
                    val model = ViewModelProvider(it)[ReaderModel::class.java]
                    assertNotNull(model.info)
                    assertEquals(surface !in listOf(Surface.READER, Surface.SETTINGS, Surface.APP), model.readerVisible)
                    assertFalse(model.textMode)
                    assertEquals(initialState[0], model.documentId)
                }
            } } finally { file.delete() }
        }
    }

    private fun choiceBounds(label: String): Rect? {
        var result: Rect? = null
        visit { node ->
            if (node.isVisibleToUser && matches(node, label)) result = choiceOwnerBounds(node)
            result != null
        }
        return result
    }
    @Suppress("DEPRECATION")
    private fun choiceOwnerBounds(node: AccessibilityNodeInfo, depth: Int = 0): Rect? {
        if (depth >= 16 || !node.refresh() || !node.isVisibleToUser || !node.isEnabled) return null
        // Compose removes clickability from the selected radio; its owner still defines the tile.
        if (node.isCheckable || node.isClickable) return Rect().also(node::getBoundsInScreen).takeUnless { it.isEmpty }
        val parent = node.parent ?: return null
        return try { choiceOwnerBounds(parent, depth + 1) } finally { parent.recycle() }
    }

    private fun start(scenario: ActivityScenario<MainActivity>, owner: OnBackPressedDispatcher, progress: Float) {
        scenario.onActivity { owner.dispatchOnBackStarted(BackEventCompat(0f, 100f, 0f, BackEventCompat.EDGE_LEFT)) }
        scenario.onActivity { owner.dispatchOnBackProgressed(BackEventCompat(100f, 100f, progress, BackEventCompat.EDGE_LEFT)) }
    }

    private fun dispatcher(scenario: ActivityScenario<MainActivity>, dialog: Boolean): OnBackPressedDispatcher {
        var result: OnBackPressedDispatcher? = null
        await("Expected focused ${if (dialog) "Dialog" else "Activity"} dispatcher") {
            scenario.onActivity { activity ->
                val owner = WindowInspector.getGlobalWindowViews().filter { it.isShown && it.hasWindowFocus() }
                    .mapNotNull { it.findViewTreeOnBackPressedDispatcherOwner() }.distinct().singleOrNull()
                // No injected owner or Activity fallback: Dialog must exercise its own dispatcher.
                result = owner?.takeIf { if (dialog) it is ComponentDialog else it === activity }?.onBackPressedDispatcher
            }
            result != null
        }
        return checkNotNull(result)
    }

    private fun state(scenario: ActivityScenario<MainActivity>): List<Any> {
        var value: List<Any>? = null
        scenario.onActivity {
            val model = ViewModelProvider(it)[ReaderModel::class.java]
            value = listOf(model.documentId, model.readerVisible, model.textMode, model.page, model.viewport, model.textPosition, model.notesVisible, model.horizontalPages)
        }
        return checkNotNull(value)
    }

    private fun settledBounds(label: String, detail: () -> String = { "" }): Rect {
        var previous: Rect? = null
        var stable = 0
        await("Settled $label", detail = detail) {
            val current = bounds(label)
            stable = if (current != null && current == previous) stable + 1 else 0
            previous = current
            stable >= 5
        }
        return checkNotNull(previous)
    }
    private fun near(a: Rect, b: Rect) = abs(a.left - b.left) <= 2 && abs(a.top - b.top) <= 2 &&
        abs(a.right - b.right) <= 2 && abs(a.bottom - b.bottom) <= 2
    private fun diagnostic(scenario: ActivityScenario<MainActivity>, surface: Surface): String {
        var modelState = ""
        var nativeWindows = ""
        scenario.onActivity {
            val model = ViewModelProvider(it)[ReaderModel::class.java]
            modelState = "reader=${model.readerVisible}, textMode=${model.textMode}, textLoading=${model.textLoading}, " +
                "textLength=${model.text?.length}, position=${model.textPosition}, page=${model.page}, " +
                "canExport=${model.canExport}, exporting=${model.exporting}, " +
                "supportsNotes=${model.info?.supportsNotes}, notesLoading=${model.notesLoading}, noteCount=${model.notes.entries.size}, " +
                "notes=${model.notes.entries.take(4).map { it.text.take(100) }}, " +
                "noteWarnings=${model.notes.warnings.take(4).map { it.take(100) }}, error=${model.error?.take(160)}"
            val windows = WindowInspector.getGlobalWindowViews()
            nativeWindows = "nativeWindows=${windows.size}:" + windows.take(12).joinToString { view ->
                "${view.findViewTreeOnBackPressedDispatcherOwner()?.javaClass?.simpleName}" +
                    "(focused=${view.hasWindowFocus()},attached=${view.isAttachedToWindow},shown=${view.isShown}," +
                    "size=${view.width}x${view.height})"
            }
        }
        return "probe=${bounds(surface.probe)}, motion=${bounds(surface.motionProbe)}, destination=${bounds(surface.destination)}, $modelState; " +
            "$nativeWindows; ${activeWindowDiagnostic()}"
    }
    @Suppress("DEPRECATION")
    private fun activeWindowDiagnostic(): String = runCatching {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return@runCatching "activeRoot=null"
        try {
            val refreshed = root.refresh()
            val labels = ArrayList<String>()
            var seen = 0
            fun walk(node: AccessibilityNodeInfo, depth: Int) {
                if (depth > 12 || seen++ >= 256 || !node.refresh()) return
                val label = (node.contentDescription ?: node.text)?.toString()?.take(100)
                if (!label.isNullOrBlank() && labels.size < 24) labels += "$label(visible=${node.isVisibleToUser})"
                for (index in 0 until minOf(node.childCount, 32)) {
                    val child = node.getChild(index) ?: continue
                    try { walk(child, depth + 1) } finally { child.recycle() }
                }
            }
            walk(root, 0)
            "activeRoot=${root.packageName}/${root.windowId}, refreshed=$refreshed, labels=$labels"
        } finally { root.recycle() }
    }.getOrElse { "activeRootError=${it.javaClass.simpleName}:${it.message?.take(160)}" }

    private fun await(message: String, detail: () -> String = { "" }, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (predicate()) return
            SystemClock.sleep(40)
        }
        fail(listOf(message, detail()).filter(String::isNotEmpty).joinToString("; "))
    }
    // These are fixed chrome actions. Scrolling an unrelated document while waiting for its
    // accessibility tree can hide the very control this lookup is trying to find.
    private fun click(label: String, detail: () -> String = { activeWindowDiagnostic() }) =
        await("Clickable $label", detail = detail) {
            visit { node -> node.isVisibleToUser && matches(node, label) && clickNode(node) }
        }
    private fun bounds(label: String): Rect? {
        var result: Rect? = null
        visit { node ->
            if (node.isVisibleToUser && matches(node, label)) {
                val rectangle = Rect().also(node::getBoundsInScreen)
                if (!rectangle.isEmpty) result = rectangle
            }
            result != null
        }
        return result
    }
    private fun matches(node: AccessibilityNodeInfo, label: String) = node.contentDescription?.toString() == label ||
        node.text?.toString()?.let { if (label == "AllDoc PDF fixture") it.contains(label) else it.lineSequence().any { line -> line == label } } == true
    @Suppress("DEPRECATION")
    private fun visit(test: (AccessibilityNodeInfo) -> Boolean): Boolean {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return false
        fun walk(node: AccessibilityNodeInfo, depth: Int, seen: IntArray): Boolean {
            if (depth > 24 || seen[0]++ >= 512 || !node.refresh()) return false
            if (node.packageName?.toString() == "app.smallthingz.alldoc" && test(node)) return true
            for (index in 0 until minOf(node.childCount, 64)) {
                val child = node.getChild(index) ?: continue
                try { if (walk(child, depth + 1, seen)) return true } finally { child.recycle() }
            }
            return false
        }
        return try { walk(root, 0, intArrayOf(0)) } finally { root.recycle() }
    }
    @Suppress("DEPRECATION")
    private fun selectedMode(node: AccessibilityNodeInfo, depth: Int = 0): Boolean {
        if (depth >= 16 || !node.refresh() || !node.isVisibleToUser) return false
        if (node.isCheckable || node.isSelected) return node.isChecked || node.isSelected
        val parent = node.parent ?: return false
        return try { selectedMode(parent, depth + 1) } finally { parent.recycle() }
    }
    @Suppress("DEPRECATION")
    private fun clickNode(node: AccessibilityNodeInfo, depth: Int = 0): Boolean {
        if (!node.refresh()) return false
        if (node.isClickable) return node.isEnabled && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (depth >= 16) return false
        val parent = node.parent ?: return false
        return try { clickNode(parent, depth + 1) } finally { parent.recycle() }
    }
}
