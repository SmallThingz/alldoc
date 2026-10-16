package app.smallthingz.alldoc.ui

import android.graphics.RectF
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.smallthingz.alldoc.ReaderTestActivity
import app.smallthingz.alldoc.state.TextPosition
import app.smallthingz.alldoc.theme.ReadingMode
import app.smallthingz.alldoc.theme.ThemeSettings
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderTextRestorationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun nearEndAnchorRemainsVisibleAcrossReflowAndFreshCompositionWithoutSavingClamps() {
        val marker = "TARGET final line."
        val text = (0 until 599).joinToString("\n", postfix = "\n$marker") {
            "Line $it has enough text to wrap differently when the viewport changes."
        }
        val saved = TextPosition(text.indexOf(marker), .35f)
        val wide = mutableStateOf(false)
        val viewportHeight = mutableStateOf(0)
        val generation = mutableStateOf(0)
        val viewport = AtomicReference<Pair<Int, RectF>>()
        val writes = AtomicInteger()
        val flushes = AtomicInteger()
        val compositions = AtomicInteger()
        ActivityScenario.launch(ReaderTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    val density = LocalDensity.current.density
                    val compositionGeneration = generation.value
                    key(compositionGeneration) {
                        DisposableEffect(Unit) { compositions.incrementAndGet(); onDispose { } }
                        CompositionLocalProvider(LocalDensity provides Density(density, if (wide.value) 1.8f else 1f)) {
                            ReaderTheme(dark = false) {
                                ReaderTextView(text, false, ThemeSettings(ReadingMode.ORIGINAL), false, rememberTextFieldState(), {},
                                    Modifier.size(if (wide.value) 230.dp else 150.dp, viewportHeight.value.dp)
                                        .semantics { contentDescription = generationLabel(compositionGeneration) }
                                        .onGloballyPositioned {
                                        val top = it.localToScreen(Offset.Zero)
                                        viewport.set(compositionGeneration to RectF(top.x, top.y, top.x + it.size.width, top.y + it.size.height))
                                    }, 1L, saved, { writes.incrementAndGet() }, { flushes.incrementAndGet() },
                                    backEnabled = false)
                            }
                        }
                    }
                }
            }
            fun resizeHeight(value: Int) {
                var expected = 0f
                scenario.onActivity {
                    expected = value * it.resources.displayMetrics.density
                    viewportHeight.value = value
                }
                val deadline = SystemClock.uptimeMillis() + 5000
                while (SystemClock.uptimeMillis() < deadline &&
                    viewport.get()?.second?.height()?.let { kotlin.math.abs(it - expected) <= 1f } != true) SystemClock.sleep(25)
                assertNotNull("The requested viewport must be laid out", viewport.get())
                assertEquals("Height change must reach layout before inspecting glyphs", expected, viewport.get().second.height(), 1f)
            }
            resizeHeight(0)
            resizeHeight(210)
            val initial = visibleCharacter(marker, viewport, compositions)
            resizeHeight(0)
            resizeHeight(210)
            visibleCharacter(marker, viewport, compositions)
            scenario.onActivity { wide.value = true }
            val reflowed = visibleCharacter(marker, viewport, compositions, minimumHeight = initial.height() * 1.2f)
            assertTrue("The text must actually reflow at the larger local font scale", reflowed.height() > initial.height() * 1.2f)
            scenario.onActivity { generation.value++ }
            visibleCharacter(marker, viewport, compositions, generation = 1, minimumHeight = initial.height() * 1.2f)
            instrumentation.waitForIdleSync()
            assertEquals("Fresh composition must discard the prior LazyList state", 2, compositions.get())
            assertEquals("Initial/end-of-list layout clamps must not replace the saved character anchor", 0, writes.get())
            assertEquals("Restoration is not a user scroll and must not flush state", 0, flushes.get())
        }
    }

    private fun generationLabel(generation: Int) = "Text restoration generation $generation"

    private fun visibleCharacter(
        marker: String, viewport: AtomicReference<Pair<Int, RectF>>, compositions: AtomicInteger,
        generation: Int = 0, minimumHeight: Float = 0f,
    ): RectF {
        val deadline = SystemClock.uptimeMillis() + 5000
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            // Looper idleness can precede Compose's next frame. Never accept the old
            // generation's identical glyphs while its replacement is still scheduled.
            val clip = viewport.get()?.takeIf { it.first == generation }?.second
            if (compositions.get() == generation + 1 && clip != null) {
                val root = instrumentation.uiAutomation.rootInActiveWindow
                val bounds = try { root?.let { characterBounds(it, marker, generationLabel(generation), false, 0, intArrayOf(0)) } }
                    finally { @Suppress("DEPRECATION") root?.recycle() }
                if (bounds != null && bounds.height() > minimumHeight && RectF.intersects(bounds, clip)) return bounds
            }
            SystemClock.sleep(25)
        }
        fail("Generation $generation did not compose, lay out and expose the saved near-end character (compositions=${compositions.get()}, layout=${viewport.get()?.first})")
        error("Unreachable")
    }

    @Suppress("DEPRECATION")
    private fun characterBounds(
        node: AccessibilityNodeInfo, marker: String, generationLabel: String,
        inGeneration: Boolean, depth: Int, visited: IntArray,
    ): RectF? {
        if (depth > 24 || visited[0]++ > 512) return null
        val owned = inGeneration || node.contentDescription?.toString() == generationLabel
        val start = node.text?.toString()?.indexOf(marker) ?: -1
        if (owned && start >= 0 && node.isVisibleToUser) {
            node.refreshWithExtraData(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY, Bundle().apply {
                putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, start)
                putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, 1)
            })
            (node.extras.getParcelableArray(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY)
                ?.firstOrNull() as? RectF)?.let { return it }
        }
        for (index in 0 until minOf(node.childCount, 64)) {
            val child = node.getChild(index) ?: continue
            try { characterBounds(child, marker, generationLabel, owned, depth + 1, visited)?.let { return it } }
            finally { child.recycle() }
        }
        return null
    }
}
