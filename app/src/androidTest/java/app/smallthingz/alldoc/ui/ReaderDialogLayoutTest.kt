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
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.smallthingz.alldoc.ReaderTestActivity
import app.smallthingz.alldoc.theme.ImageTreatment
import app.smallthingz.alldoc.theme.ReadingMode
import app.smallthingz.alldoc.theme.ThemeSettings
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
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
