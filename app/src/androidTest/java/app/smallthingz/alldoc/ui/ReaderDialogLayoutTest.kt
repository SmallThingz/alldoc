package app.smallthingz.alldoc.ui

import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import app.smallthingz.alldoc.ReaderTestActivity
import app.smallthingz.alldoc.state.AppTheme
import app.smallthingz.alldoc.state.CopySettings
import app.smallthingz.alldoc.state.LibraryCategory
import app.smallthingz.alldoc.theme.ImageTreatment
import app.smallthingz.alldoc.theme.ReadingMode
import app.smallthingz.alldoc.theme.ThemeSettings
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderDialogLayoutTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun saveRemainsReachableAtLargeFontInAShortDialogViewport() {
        val saved = AtomicBoolean()
        ActivityScenario.launch(ReaderTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                        ReaderTheme {
                            Box(Modifier.fillMaxSize()) {
                                // Constrain the actual content used by ReaderDialog, not its separate window's parent.
                                Box(Modifier.width(320.dp).height(180.dp).clipToBounds()) {
                                    ReaderDialogContent("Storage limits", {}, Modifier.fillMaxSize(),
                                        buttons = {
                                            TextButton(onClick = { saved.set(true) }) { Text("Save") }
                                        }) {
                                        Text("Retained copies must fit within both limits. Reducing a limit can remove older copies. ".repeat(6))
                                        OutlinedTextField("256", {}, label = { Text("Storage (MiB)") }, singleLine = true)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            instrumentation.waitForIdleSync()
            val deadline = SystemClock.uptimeMillis() + 10_000
            var scrolled = false
            while (!saved.get() && SystemClock.uptimeMillis() < deadline) {
                if (scrolled) visit { node ->
                    (node.text?.toString() == "Save" || node.contentDescription?.toString() == "Save") && click(node)
                }
                if (!saved.get()) {
                    scrolled = visit { node ->
                        node.isEnabled && node.isVisibleToUser && node.isScrollable &&
                            node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    } || scrolled
                    SystemClock.sleep(60)
                }
            }
            val diagnostic = if (saved.get()) "" else describeVisibleNodes()
            assertTrue("The compact dialog must expose accessibility scrolling. $diagnostic", scrolled)
            assertTrue("Save must remain reachable and invoke its action after scrolling. $diagnostic", saved.get())
        }
    }

    @Test fun appearanceChoicesStayReachableAtLargeFontInAShortViewport() {
        val changed = AtomicReference(ThemeSettings())
        ActivityScenario.launch(ReaderTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                    ReaderTheme {
                        var value by remember { mutableStateOf(ThemeSettings()) }
                        Box(Modifier.width(320.dp).height(240.dp).clipToBounds()) {
                            ReaderDialogContent("Appearance", {}, Modifier.fillMaxSize(), buttons = null) {
                                ReadingAppearanceContent(value) { value = it; changed.set(it) }
                            }
                        }
                    }
                }
            } }
            fun select(label: String) {
                val deadline = SystemClock.uptimeMillis() + 10_000
                while (SystemClock.uptimeMillis() < deadline) {
                    if (visit { node -> node.isVisibleToUser &&
                        (node.text?.toString() == label || node.contentDescription?.toString() == label) && click(node) }) return
                    visit { it.isVisibleToUser && it.isScrollable &&
                        it.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) }
                    SystemClock.sleep(50)
                }
                throw AssertionError("Appearance choice is unreachable: $label; ${describeVisibleNodes()}")
            }
            select("Dark")
            assertEquals(ReadingMode.ADAPTIVE, changed.get().mode)
            select("Recolor")
            assertEquals(ImageTreatment.RECOLOR, changed.get().imageTreatment)
            assertEquals("Image handling must not reset the chosen theme", ReadingMode.ADAPTIVE, changed.get().mode)
        }
    }

    @Test fun readingViewCardsFitLargeTextAndRemainSelectableInAShortViewport() {
        val labels = listOf("Normal", "Present", "Text")
        val selected = AtomicReference(0)
        val widths = AtomicReference<Map<String, Int>>()
        val target = AtomicReference(0f)
        ActivityScenario.launch(ReaderTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 3f)) {
                    ReaderTheme {
                        var choice by remember { mutableStateOf(0) }
                        val measurer = rememberTextMeasurer()
                        val measured = labels.associateWith { measurer.measure(it, MaterialTheme.typography.labelLarge).size.width }
                        SideEffect { widths.set(measured); target.set(48f * density.density) }
                        Box(Modifier.width(320.dp).height(240.dp).clipToBounds()) {
                            ReaderDialogContent("Reading view", {}, Modifier.fillMaxSize(), buttons = null, showTitle = false) {
                                ReaderViewChoices(choice) { choice = it; selected.set(it) }
                            }
                        }
                    }
                }
            } }
            fun select(index: Int) {
                val label = labels[index]
                val deadline = SystemClock.uptimeMillis() + 10_000
                while (SystemClock.uptimeMillis() < deadline) {
                    var clicked = false
                    visit { node ->
                        if (node.isVisibleToUser && node.contentDescription?.toString() == "$label view") {
                            val box = Rect().also(node::getBoundsInScreen)
                            // This compares actual control bounds with independently measured glyphs.
                            assertTrue("$label must have room for its complete label", box.width() >= requireNotNull(widths.get())[label]!!)
                            if (box.height() >= target.get() - 1) clicked = click(node)
                        }
                        clicked
                    }
                    if (clicked) {
                        while (SystemClock.uptimeMillis() < deadline) {
                            if (visit { node -> node.isVisibleToUser && node.contentDescription?.toString() == "$label view" &&
                                selectedChoice(node) }) {
                                assertEquals("The actual card must invoke its view choice", index, selected.get())
                                return
                            }
                            SystemClock.sleep(25)
                        }
                        throw AssertionError("The chosen card did not expose selection: $label; callback=${selected.get()}; ${describeVisibleNodes()}")
                    }
                    visit { node -> node.isVisibleToUser && node.isScrollable && node.performAction(
                        if (index == 0) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) }
                    SystemClock.sleep(50)
                }
                throw AssertionError("Reading view is unreachable: $label; ${describeVisibleNodes()}")
            }
            select(1); select(2); select(0)
        }
    }

    @Test fun narrowSettingsKeepFourThemeAndLibraryTargetsInOneRow() {
        val theme = AtomicReference(AppTheme.SYSTEM)
        val hidden = AtomicReference(emptySet<LibraryCategory>())
        val target = AtomicReference(0f)
        launchReaderTestActivity().use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                val density = LocalDensity.current
                SideEffect { target.set(48f * density.density) }
                CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                    ReaderTheme {
                        var selected by remember { mutableStateOf(AppTheme.SYSTEM) }
                        var excluded by remember { mutableStateOf(emptySet<LibraryCategory>()) }
                        Box(Modifier.width(240.dp).height(360.dp).clipToBounds()) {
                            ReaderSettingsScreen(CopySettings(), "Documents", 0, 0, false, null,
                                {}, {}, {}, {}, ThemeSettings(), {}, {}, appTheme = selected,
                                onAppThemeChange = { selected = it; theme.set(it) }, hiddenLibraryTypes = excluded,
                                onHiddenLibraryTypesChange = { excluded = it; hidden.set(it) })
                        }
                    }
                }
            } }
            fun row(labels: List<String>) {
                val deadline = SystemClock.uptimeMillis() + 8_000
                while (SystemClock.uptimeMillis() < deadline) {
                    var ready = false
                    scenario.onActivity { activity ->
                        val roots = activity.testSemantics()
                        val bounds = labels.mapNotNull { label -> find(roots) {
                            label in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                        }?.boundsInRoot }
                        ready = bounds.size == labels.size && bounds.all { it.width >= target.get() - 1 && it.height >= target.get() - 1 }
                        if (ready) assertTrue("Choices must remain one compact row: $bounds",
                            bounds.maxOf { it.center.y } - bounds.minOf { it.center.y } <= 1f)
                        else find(roots) { it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null }
                            ?.config?.getOrNull(SemanticsActions.ScrollBy)?.action?.invoke(0f, target.get() * 2)
                    }
                    if (ready) return
                    SystemClock.sleep(50)
                }
                throw AssertionError("Settings row clipped or inaccessible: $labels")
            }
            fun choose(label: String) = scenario.onActivity { activity ->
                val choice = requireNotNull(find(activity.testSemantics()) {
                    label in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                })
                assertTrue(requireNotNull(choice.config.getOrNull(SemanticsActions.OnClick)?.action).invoke())
            }
            row(listOf("System", "Light", "Dark", "AMOLED").map { "App theme: $it" })
            choose("App theme: AMOLED")
            instrumentation.waitForIdleSync()
            assertEquals(AppTheme.AMOLED, theme.get())
            awaitSemantics("Theme selection is not exposed") {
                var selected = false
                scenario.onActivity { activity -> selected = find(activity.testSemantics()) {
                    "App theme: AMOLED" in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                }?.config?.getOrNull(SemanticsProperties.Selected) == true }
                selected
            }
            row(LibraryCategory.available.map { "Library: ${it.label}" })
            choose("Library: Presentations")
            instrumentation.waitForIdleSync()
            assertEquals(setOf(LibraryCategory.PRESENTATIONS), hidden.get())
            awaitSemantics("Excluded document type is not exposed") {
                var excluded = false
                scenario.onActivity { activity -> excluded = find(activity.testSemantics()) {
                    "Library: Presentations" in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                }?.config?.getOrNull(SemanticsProperties.ToggleableState) == ToggleableState.Off }
                excluded
            }
        }
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    fun storageSliderPreservesFileCountAndExposesMegabytesWithoutTextFields() {
        val initial = CopySettings(enabled = false, maxCount = 37, treeUri = "content://storage/tree/docs")
        val changed = AtomicReference(initial)
        val writes = AtomicInteger()
        launchReaderTestActivity().use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                ReaderTheme {
                    var value by remember { mutableStateOf(initial) }
                    CopyLimitsDialog(value, { value = it; changed.set(it); writes.incrementAndGet() }, {})
                }
            } }
            val deadline = SystemClock.uptimeMillis() + 8_000
            var set = false
            while (!set && SystemClock.uptimeMillis() < deadline) {
                scenario.onActivity { activity ->
                    val node = find(activity.testSemantics(includeDialogs = true)) {
                        "Storage limit" in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() &&
                            it.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo) != null
                    }
                    if (node != null) {
                        val range = requireNotNull(node.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo))
                        assertEquals(1f, range.range.start, 0f)
                        assertEquals(8192f, range.range.endInclusive, 0f)
                        set = node.config.getOrNull(SemanticsActions.SetProgress)?.action?.invoke(512f) == true
                    }
                }
                if (!set) SystemClock.sleep(50)
            }
            assertTrue("Storage slider must support an accessible numeric value", set)
            instrumentation.waitForIdleSync()
            assertEquals(1, writes.get())
            assertEquals(initial.copy(maxBytes = 512L * 1024 * 1024), changed.get())
            awaitSemantics("Slider does not expose 512 MB") {
                var updated = false
                scenario.onActivity { activity ->
                    val roots = activity.testSemantics(includeDialogs = true)
                    updated = find(roots) {
                        "Storage limit" in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() &&
                            it.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo) != null
                    }?.config?.getOrNull(SemanticsProperties.StateDescription) == "512 MB"
                    assertFalse("Storage limits must not open the keyboard", find(roots) {
                        it.config.getOrNull(SemanticsActions.SetText) != null || it.config.getOrNull(SemanticsProperties.EditableText) != null
                    } != null)
                }
                updated
            }
        }
    }


    @Test fun openTopPanelUpdatesSystemBarContrastWithAppTheme() {
        val theme = mutableStateOf(AppTheme.LIGHT)
        val observedTheme = AtomicReference<AppTheme>()
        val panelWindow = AtomicReference<android.view.Window>()
        launchReaderTestActivity().use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                ReaderTheme(theme.value) {
                    ReaderTopPanel("Theme", onDismiss = {}) {
                        val view = LocalView.current
                        val currentTheme = LocalAppTheme.current
                        SideEffect {
                            panelWindow.set(generateSequence(view.parent) { it.parent }
                                .filterIsInstance<DialogWindowProvider>().firstOrNull()?.window)
                            observedTheme.set(currentTheme)
                        }
                        Text("Open panel")
                    }
                }
            } }
            fun awaitChrome(mode: AppTheme) = awaitSemantics("Open panel kept stale system-bar contrast for $mode") {
                var matched = false
                scenario.onActivity { activity ->
                    activity.verifyTestDisplay()
                    val window = panelWindow.get()
                    if (window != null && window.decorView.isAttachedToWindow && window.decorView.hasWindowFocus()) {
                        assertEquals(activity.window.decorView.display?.displayId, window.decorView.display?.displayId)
                        val controller = WindowCompat.getInsetsController(window, window.decorView)
                        val light = mode == AppTheme.LIGHT
                        matched = observedTheme.get() == mode && controller.isAppearanceLightStatusBars == light &&
                            controller.isAppearanceLightNavigationBars == light
                    }
                }
                matched
            }
            awaitChrome(AppTheme.LIGHT)
            // Drain the initial attach/focus callback before simulating delayed preference hydration.
            instrumentation.waitForIdleSync()
            val original = requireNotNull(panelWindow.get())
            for (mode in listOf(AppTheme.DARK, AppTheme.AMOLED, AppTheme.LIGHT)) {
                scenario.onActivity { activity -> activity.verifyTestDisplay(); theme.value = mode }
                awaitChrome(mode)
                assertSame("Theme changes must update the existing panel window", original, panelWindow.get())
            }
        }
    }

    private fun awaitSemantics(message: String, ready: () -> Boolean) {
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

    @Suppress("DEPRECATION")
    private fun visit(action: (AccessibilityNodeInfo) -> Boolean): Boolean {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return false
        return try { visit(root, 0, intArrayOf(0), action) } finally { root.recycle() }
    }

    @Suppress("DEPRECATION")
    private fun visit(node: AccessibilityNodeInfo, depth: Int, visited: IntArray,
        action: (AccessibilityNodeInfo) -> Boolean): Boolean {
        if (depth > 24 || visited[0]++ >= 512 || !node.refresh()) return false
        if (node.packageName?.toString() == "app.smallthingz.alldoc" && action(node)) return true
        for (index in 0 until minOf(node.childCount, 64)) {
            val child = node.getChild(index) ?: continue
            try { if (visit(child, depth + 1, visited, action)) return true }
            finally { child.recycle() }
        }
        return false
    }

    private fun describeVisibleNodes(): String = buildString {
        visit { node ->
            if (length < 6000 && (node.isVisibleToUser || node.isClickable)) {
                val bounds = Rect().also(node::getBoundsInScreen)
                append("[text=").append(node.text?.toString()?.take(100))
                    .append(", description=").append(node.contentDescription?.toString()?.take(100))
                    .append(", visible=").append(node.isVisibleToUser)
                    .append(", enabled=").append(node.isEnabled)
                    .append(", clickable=").append(node.isClickable)
                    .append(", actions=").append(node.actionList.map { it.id })
                    .append(", bounds=").append(bounds.toShortString()).append("] ")
            }
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun selectedChoice(node: AccessibilityNodeInfo, depth: Int = 0): Boolean {
        if (depth >= 16 || !node.refresh() || !node.isVisibleToUser) return false
        if (node.isCheckable || node.isSelected) return node.isChecked || node.isSelected
        val parent = node.parent ?: return false
        return try { selectedChoice(parent, depth + 1) } finally { parent.recycle() }
    }

    @Suppress("DEPRECATION")
    private fun click(node: AccessibilityNodeInfo, depth: Int = 0): Boolean {
        if (!node.refresh()) return false
        if (node.isClickable) return node.isEnabled && node.isVisibleToUser &&
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (depth >= 16) return false
        val parent = node.parent ?: return false
        return try { click(parent, depth + 1) } finally { parent.recycle() }
    }
}
