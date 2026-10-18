package app.smallthingz.alldoc.ui

import android.graphics.RectF
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.smallthingz.alldoc.ReaderTestActivity
import app.smallthingz.alldoc.core.MarkdownDocument
import app.smallthingz.alldoc.state.TextPosition
import app.smallthingz.alldoc.theme.ReadingMode
import app.smallthingz.alldoc.theme.ThemeSettings
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderMarkdownSearchRestorationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun codeAndTableKeepManualHorizontalPositionsAcrossRecreationAndReflow() {
        fun document(prefix: Int = 24) = MarkdownDocument.parse(
            "```\nCODE_START " + "wide ".repeat(prefix) + "needle " + " gap ".repeat(16) + "other\n```\n\n" + (0 until 80).joinToString("\n\n", postfix = "\n\n") { "Spacer paragraph $it." } +
                "| Heading | Value |\n| --- | --- |\n| TABLE_START " + "wide ".repeat(prefix) +
                "needle " + " gap ".repeat(16) + "other | End |\n")
        val flow = mutableStateOf(document())
        val hydrated = mutableStateOf(true)
        val width = mutableStateOf(260)
        val identity = mutableStateOf(1L)
        val position = mutableStateOf(TextPosition())
        val query = AtomicReference<TextFieldState>()
        val viewport = AtomicReference<RectF>()
        val mounted = AtomicInteger()
        ActivityScenario.launch(ReaderTestActivity::class.java).use { scenario ->
            fun mount() = scenario.onActivity { activity -> activity.setContent {
                val search = rememberTextFieldState("needle")
                SideEffect { query.set(search); mounted.incrementAndGet() }
                ReaderTheme(dark = false) {
                    ReaderTextView(if (hydrated.value) flow.value.plainText else null, !hydrated.value,
                        ThemeSettings(ReadingMode.ORIGINAL), true, search, {}, Modifier.size(width.value.dp, 440.dp)
                            .onGloballyPositioned {
                                val origin = it.localToScreen(Offset.Zero)
                                viewport.set(RectF(origin.x, origin.y, origin.x + it.size.width, origin.y + it.size.height))
                            }, identity.value, position.value, { position.value = it }, {},
                        markdown = if (hydrated.value) flow.value else null)
                }
            } }
            fun horizontal(label: String, clip: ((RectF) -> Unit)? = null,
                action: ((SemanticsNode) -> Unit)? = null): Float? {
                var value: Float? = null
                scenario.onActivity { activity ->
                    fun contains(node: SemanticsNode): Boolean =
                        node.config.getOrNull(SemanticsProperties.Text)?.any { label in it.text } == true ||
                            node.children.any(::contains)
                    fun find(node: SemanticsNode): SemanticsNode? =
                        if (node.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange) != null && contains(node)) node
                        else node.children.firstNotNullOfOrNull(::find)
                    val root = composeRoot(activity.window.decorView)?.semanticsOwner?.unmergedRootSemanticsNode
                    root?.let(::find)?.let { node ->
                        value = node.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange)?.value?.invoke()
                        if (clip != null) {
                            val screen = IntArray(2); val window = IntArray(2)
                            activity.window.decorView.getLocationOnScreen(screen)
                            activity.window.decorView.getLocationInWindow(window)
                            val bounds = node.boundsInWindow
                            val x = screen[0] - window[0]; val y = screen[1] - window[1]
                            clip(RectF(bounds.left + x, bounds.top + y, bounds.right + x, bounds.bottom + y))
                        }
                        action?.invoke(node)
                    }
                }
                return value
            }
            fun revealed(label: String, marker: String): Boolean {
                var clip: RectF? = null
                horizontal(label, clip = { clip = it })
                return glyphVisible(label, marker, clip)
            }
            fun settled(label: String, differentFrom: Float? = null): Float {
                var last: Float? = null
                var unchangedAt = SystemClock.uptimeMillis()
                await {
                    val current = horizontal(label)
                    if (current != last) { last = current; unchangedAt = SystemClock.uptimeMillis() }
                    current != null && (differentFrom == null || kotlin.math.abs(current - differentFrom) > 10f) &&
                        SystemClock.uptimeMillis() - unchangedAt >= 250
                }
                return requireNotNull(last)
            }
            fun count(value: String): Boolean {
                var found = false
                scenario.onActivity { activity ->
                    fun visit(node: SemanticsNode): Boolean =
                        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == value } == true ||
                            node.children.any(::visit)
                    found = composeRoot(activity.window.decorView)?.semanticsOwner?.unmergedRootSemanticsNode?.let(::visit) == true
                }
                return found
            }
            fun step(label: String) = scenario.onActivity { activity ->
                fun named(node: SemanticsNode): Boolean =
                    node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true ||
                        node.children.any(::named)
                fun click(node: SemanticsNode): Boolean =
                    node.config.getOrNull(SemanticsActions.OnClick)?.action?.takeIf { named(node) }?.invoke() == true ||
                        node.children.any(::click)
                assertTrue(click(checkNotNull(composeRoot(activity.window.decorView)).semanticsOwner.unmergedRootSemanticsNode))
            }
            fun recreate() {
                scenario.recreate()
                // Reattach the component after the Activity saved its old Compose registry.
                // Hold hydration so the restored search selection exists before text/layout return.
                scenario.onActivity { hydrated.value = false }
                val before = mounted.get()
                mount()
                await { mounted.get() > before && query.get() != null }
                scenario.onActivity { hydrated.value = true }
            }
            mount()
            val manualPositions = HashMap<String, Float>()
            for ((index, label) in listOf("CODE_START", "TABLE_START").withIndex()) {
                if (index > 0) step("Next match")
                await { count("${index + 1} / 2") && revealed(label, "needle") }
                val revealed = settled(label)
                horizontal(label) { node ->
                    assertTrue(checkNotNull(node.config.getOrNull(SemanticsActions.ScrollBy)?.action).invoke(-200f, 0f))
                }
                val manual = settled(label, revealed)
                assertTrue("The manual position must remain away from both edges", manual > 0f && manual < revealed - 10f)
                manualPositions[label] = manual
                recreate()
                await { count("${index + 1} / 2") && horizontal(label) != null }
                assertEquals("$label: recreation must preserve the user's horizontal position", manual, settled(label), 1f)
                var expectedWidth = 0f
                scenario.onActivity {
                    width.value = if (width.value == 260) 230 else 260
                    expectedWidth = width.value * it.resources.displayMetrics.density
                }
                await { viewport.get()?.width()?.let { kotlin.math.abs(it - expectedWidth) <= 1f } == true }
                assertEquals("$label: reflow must preserve the user's horizontal position", manual, settled(label), 1f)
            }
            step("Previous match")
            await { count("1 / 2") && revealed("CODE_START", "needle") }
            assertTrue("Returning to an earlier result must reveal it again",
                kotlin.math.abs(settled("CODE_START") - requireNotNull(manualPositions["CODE_START"])) > 10f)
            scenario.onActivity { query.get().edit { replace(0, length, "other") } }
            await { count("1 / 2") && revealed("CODE_START", "other") }
            val changedQuery = settled("CODE_START")
            horizontal("CODE_START") { checkNotNull(it.config.getOrNull(SemanticsActions.ScrollBy)?.action).invoke(-200f, 0f) }
            val beforeDocument = settled("CODE_START", changedQuery)
            scenario.onActivity { identity.value++ }
            await { revealed("CODE_START", "other") }
            assertTrue("The same query and result in a new document must reveal",
                kotlin.math.abs(settled("CODE_START") - beforeDocument) > 10f)
            val beforeBlock = settled("CODE_START")
            horizontal("CODE_START") { checkNotNull(it.config.getOrNull(SemanticsActions.ScrollBy)?.action).invoke(-200f, 0f) }
            settled("CODE_START", beforeBlock)
            scenario.onActivity { flow.value = document(prefix = 30) }
            await { revealed("CODE_START", "other") }
            scenario.onActivity { query.get().edit { replace(0, length, "CODE_START") } }
            await { count("1 / 1") && revealed("CODE_START", "CODE_START") }
            scenario.onActivity { activity ->
                fun scroll(node: SemanticsNode): Boolean =
                    node.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null &&
                        node.config.getOrNull(SemanticsActions.ScrollBy)?.action?.invoke(0f, 2000f) == true ||
                        node.children.any(::scroll)
                assertTrue(scroll(checkNotNull(composeRoot(activity.window.decorView)).semanticsOwner.unmergedRootSemanticsNode))
            }
            await { horizontal("CODE_START") == null }
            step("Next match")
            await { count("1 / 1") && revealed("CODE_START", "CODE_START") }
            val onlyMatch = settled("CODE_START")
            horizontal("CODE_START") { checkNotNull(it.config.getOrNull(SemanticsActions.ScrollBy)?.action).invoke(200f, 0f) }
            settled("CODE_START", onlyMatch)
            step("Next match")
            await { count("1 / 1") && revealed("CODE_START", "CODE_START") }
            assertEquals("Next must reveal even when the query has only one match", onlyMatch, settled("CODE_START"), 1f)
        }
    }

    private fun composeRoot(view: View): ViewRootForTest? =
        (view as? ViewRootForTest) ?: (view as? ViewGroup)?.let { group ->
            (0 until group.childCount).firstNotNullOfOrNull { composeRoot(group.getChildAt(it)) }
        }

    // UiAutomation character geometry requires an exclusive foreground test window.
    // All input above uses Compose semantics; this read checks each glyph against its actual scroll clip.
    @Suppress("DEPRECATION")
    private fun glyphVisible(label: String, marker: String, viewport: RectF?): Boolean {
        if (viewport == null) return false
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return false
        fun visit(node: AccessibilityNodeInfo): Boolean {
            val text = node.text?.toString().orEmpty()
            val start = text.indexOf(marker)
            if (!node.isEditable && node.isVisibleToUser && label in text && start >= 0) {
                node.refreshWithExtraData(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY, Bundle().apply {
                    putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, start)
                    putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, marker.length)
                })
                val glyphs = node.extras.getParcelableArray(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY)
                if (glyphs?.size == marker.length && glyphs.all { value ->
                    val rect = value as? RectF
                    rect != null && rect.width() > 0 && rect.left >= viewport.left && rect.right <= viewport.right &&
                        rect.top >= viewport.top && rect.bottom <= viewport.bottom
                }) return true
            }
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                try { if (visit(child)) return true } finally { child.recycle() }
            }
            return false
        }
        return try { visit(root) } finally { root.recycle() }
    }

    private fun await(test: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 7000
        while (SystemClock.uptimeMillis() < deadline) {
            if (test()) return
            SystemClock.sleep(25)
        }
        assertTrue("Expected Markdown search/restoration state", test())
    }
}
