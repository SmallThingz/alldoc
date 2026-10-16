package app.smallthingz.alldoc.ui

import android.net.Uri
import android.graphics.RectF
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.smallthingz.alldoc.ReaderTestActivity
import app.smallthingz.alldoc.core.MarkdownKind
import app.smallthingz.alldoc.core.MarkdownBlock
import app.smallthingz.alldoc.core.MarkdownDocument
import app.smallthingz.alldoc.state.TextPosition
import app.smallthingz.alldoc.theme.ThemeSettings
import app.smallthingz.alldoc.theme.ReadingMode
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderMarkdownTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun markdownIsOneRichFlowWithSearchAndDurableAnchorsAcrossResizeAndAliasReopen() =
        ReaderPersistenceTest().Fixture().use { fixture ->
            val file = File(fixture.context.root, "guide.md").apply { writeText(buildString {
                append("# A continuous field guide\n\nA **bold** introduction with *emphasis* and [a link](https://example.org).\n\n")
                append("- First item\n- Second item\n\n> Quoted material\n\n```kotlin\nval answer = 42\n```\n\n")
                append("| Name | Value |\n| --- | --- |\n| Sample | 42 |\n\n")
                repeat(120) { append("Paragraph $it: needle$it. ").append("A readable line with meaningful spacing. ".repeat(12)).append("\n\n") }
            }) }
            val alias = File(fixture.context.root, "renamed.md").also { file.copyTo(it) }
            fixture.launch()
            fixture.act { it.open(Uri.fromFile(file)) }
            fixture.await { it.markdown != null && !it.loading && !it.textLoading }
            var hash = ""
            fixture.act { reader ->
                assertTrue(reader.info!!.continuous)
                assertEquals(1, reader.info!!.pageCount)
                assertFalse(reader.textMode)
                assertFalse(reader.horizontalPages)
                assertNull("Normal Markdown does not allocate a full document bitmap", reader.bitmap)
                assertTrue(reader.pageImages.isEmpty())
                val flow = requireNotNull(reader.markdown)
                assertTrue(flow.blocks.size > 120)
                assertTrue(flow.blocks.any { it.kind == MarkdownKind.TABLE })
                assertTrue(flow.blocks.any { it.content.spans.any { span -> span.bold } })
                assertFalse(reader.text!!.contains("**bold**"))
                hash = requireNotNull(reader.documentHash)
            }
            val landscape = mutableStateOf(false)
            var anchor = TextPosition()
            var anchorText = ""
            ActivityScenario.launch(ReaderTestActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent {
                    Box(Modifier.width(if (landscape.value) 520.dp else 360.dp)
                        .height(if (landscape.value) 360.dp else 560.dp)) {
                        ReaderScreen(fixture.reader, {}, { _, _ -> })
                    }
                } }
                await { visible("Markdown document") && visit { node ->
                    node.text?.toString()?.trim() == "A continuous field guide" &&
                        AccessibilityNodeInfoCompat.wrap(node).isHeading
                } }
                assertFalse(visible("Document pages"))
                assertFalse(visible("Jump to page"))
                assertFalse("Offscreen paragraphs are not all composed", visible("Paragraph 119: needle119."))
                await { visit { node -> node.isVisibleToUser && node.isScrollable &&
                    node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) } }
                await {
                    fixture.act { anchor = it.textPosition }
                    anchor.offset > 0 && fixture.store.get(hash)?.state?.textPosition == anchor
                }
                fixture.act { reader ->
                    anchorText = requireNotNull(reader.markdown).blocks.last { it.start <= anchor.offset }.content.text
                }
                scenario.onActivity { landscape.value = true }
                await { visible(anchorText.trim()) }
                fixture.act { assertEquals("Reflow retains the character and line-fraction anchor", anchor, it.textPosition) }
                // Reopen a new owner with the same bytes; the visual paragraph must follow the saved hash.
            }
            fixture.stateBarrier()
            fixture.launch()
            fixture.act { it.open(Uri.fromFile(alias)) }
            fixture.await { it.documentHash == hash && it.markdown != null && !it.textLoading }
            fixture.act { assertEquals(anchor, it.textPosition); assertFalse(it.textMode); assertNull(it.bitmap) }
            ActivityScenario.launch(ReaderTestActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent { ReaderScreen(fixture.reader, {}, { _, _ -> }) } }
                await { visible("Markdown document") && visible(anchorText.trim()) }
                click("Find in document")
                await { visit { node -> node.isVisibleToUser && node.isEditable && node.performAction(
                    AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "needle88")
                    }) } }
                await { visible("Paragraph 88: needle88.") && visible("1 / 1") }
                assertFalse("Searching still uses the rich flow", visible("Document pages"))
                click("Close search")
                await { visible("Markdown document") && !visible("Close search") }
                fixture.act { assertFalse(it.textMode); assertEquals(1, it.info!!.pageCount); assertTrue(it.pageImages.isEmpty()) }
                click("Reading view")
                await { visible("Return to pages") }
                assertFalse("Continuous content has no redundant Normal/Present chooser", visible("Present view"))
                click("Return to pages")
                await { visible("Markdown document") && !visible("Return to pages") }
                click("Reading appearance")
                await { visible("System") }
                click("Dark")
                fixture.await { it.settings.mode == ReadingMode.ADAPTIVE }
                for (label in listOf("Images", "Auto", "Keep", "Dim", "Recolor"))
                    assertFalse("Markdown must omit image-only controls: $label", visible(label))
                click("Paper")
                await { visible("Paper roughness") }
                assertFalse("Raster-only rotation must not be a no-op control", visible("Rotate page"))
            }
        }

    @Test fun searchRevealsActualGlyphsInHorizontallyScrolledCodeAndTables() {
        val code = "HORIZONTAL_CODE_TARGET"
        val table = "HORIZONTAL_TABLE_TARGET"
        val prefix = "wide ".repeat(64)
        val flow = MarkdownDocument.parse("```\n$prefix$code\n```\n\n" +
            "| Left | Right |\n| --- | --- |\n| $prefix | $table |\n")
        val viewport = AtomicReference<RectF>()
        ActivityScenario.launch(ReaderTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                ReaderTheme(dark = false) {
                    ReaderTextView(flow.plainText, false, ThemeSettings(ReadingMode.ORIGINAL), true,
                        rememberTextFieldState(code), {}, Modifier.size(260.dp, 440.dp).onGloballyPositioned {
                            val origin = it.localToScreen(Offset.Zero)
                            viewport.set(RectF(origin.x, origin.y, origin.x + it.size.width, origin.y + it.size.height))
                        }, 1L, TextPosition(), {}, {}, markdown = flow)
                }
            } }
            fun reveal(marker: String) {
                var diagnostic = "No matching non-editable accessibility node for $marker"
                try {
                    await {
                        val clip = viewport.get()
                        clip != null && visit { node ->
                            val start = node.text?.toString()?.indexOf(marker) ?: -1
                            if (node.isEditable || start < 0) false
                            else {
                                val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
                                diagnostic = "marker=$marker viewport=$clip node=$bounds visible=${node.isVisibleToUser} " +
                                    "textLength=${node.text?.length} offset=$start"
                                if (!node.isVisibleToUser) false
                                else {
                                    val refreshed = node.refreshWithExtraData(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY, Bundle().apply {
                                        putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, start)
                                        putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, marker.length)
                                    })
                                    @Suppress("DEPRECATION")
                                    val glyphs = node.extras.getParcelableArray(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY)
                                    diagnostic += " refreshed=$refreshed extraKeys=${node.availableExtraData} glyphs=${glyphs?.contentToString()}"
                                    glyphs != null && glyphs.size == marker.length && glyphs.all { value ->
                                        val rect = value as? RectF
                                        rect != null && rect.width() > 0 && rect.left >= clip.left && rect.right <= clip.right &&
                                            rect.bottom > clip.top && rect.top < clip.bottom
                                    }
                                }
                            }
                        }
                    }
                } catch (error: AssertionError) {
                    throw AssertionError(diagnostic, error)
                }
            }
            reveal(code)
            await { visit { node -> node.isVisibleToUser && node.isEditable && node.performAction(
                AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, table)
                }) } }
            reveal(table)
        }
    }

    @Test fun wideListContinuationsKeepTheirActualGlyphGutterWithoutRepeatingMarkers() {
        data class Case(val name: String, val first: MarkdownBlock, val continuation: MarkdownBlock, val rtl: Boolean,
            val width: Int = 280, val fontScale: Float = 1f)
        fun pair(name: String, document: MarkdownDocument, level: Int = 0, rtl: Boolean = false): Case {
            val blocks = document.blocks.filter { it.kind == MarkdownKind.LIST_ITEM && it.level == level }
            assertTrue("$name must contain a real continuation", blocks.size >= 2)
            val first = blocks.first()
            val continuation = blocks.last()
            assertEquals("100000000.", first.marker)
            assertEquals("The continuation must not repeat a marker", "", continuation.marker)
            return Case(name, first, continuation, rtl)
        }
        val loose = MarkdownDocument.parse("100000000. First paragraph\n\n           Continuation paragraph")
        val chunked = MarkdownDocument.parse("100000000. " + "word ".repeat(1800))
        assertTrue("The fixture exercises bounded parser chunks", chunked.blocks.size >= 2)
        assertTrue(chunked.blocks.zipWithNext().all { (a, b) -> a.start + a.content.text.length == b.start })
        val nested = MarkdownDocument.parse("100000000. Outer paragraph\n\n           100000000. Nested paragraph\n\n" +
            "                      Nested continuation\n\n           Outer continuation")
        val deep = MarkdownDocument.parse(buildString {
            repeat(9) { level -> append("           ".repeat(level)).append("100000000. Level $level\n\n") }
            append("           ".repeat(9)).append("Deep continuation")
        })
        val cases = listOf(pair("loose", loose), pair("chunked", chunked), pair("nested", nested, 1),
            pair("parent after nested list", nested), pair("RTL loose", loose, rtl = true),
            pair("narrow large text", loose).copy(width = 180, fontScale = 2f),
            pair("deep large text", deep, 8).copy(width = 236, fontScale = 2f),
            pair("RTL deep large text", deep, 8, rtl = true).copy(width = 236, fontScale = 2f))
        val selected = mutableStateOf(0)
        val viewport = AtomicReference<RectF>()
        val minimumGutter = AtomicReference<Float>()
        fun probe(index: Int) = when {
            cases[index].fontScale > 1f -> if (cases[index].rtl) "اب$index" else "B$index"
            cases[index].rtl -> "اختبار المحاذاة $index"
            else -> "WIDE alignment $index"
        }
        ActivityScenario.launch(ReaderTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                ReaderTheme(dark = false) {
                    val index = selected.value
                    val sample = cases[index]
                    minimumGutter.set(with(LocalDensity.current) { 48.dp.toPx() })
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalLayoutDirection provides if (sample.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                        LocalDensity provides Density(density.density, sample.fontScale)) {
                        Box(Modifier.width(sample.width.dp).height(if (sample.fontScale > 1f) 260.dp else 180.dp).onGloballyPositioned {
                            val origin = it.localToScreen(Offset.Zero)
                            viewport.set(RectF(origin.x, origin.y, origin.x + it.size.width, origin.y + it.size.height))
                        }) {
                            Column {
                                // Real parsed blocks supply the list context; the same short glyph probe
                                // isolates indentation without composing an entire 8,192-character chunk.
                                for (block in listOf(sample.first, sample.continuation))
                                    ReaderMarkdownView(block, AnnotatedString(probe(index)), Color.Black, {})
                            }
                        }
                    }
                }
            } }
            for ((index, sample) in cases.withIndex()) {
                scenario.onActivity { selected.value = index }
                val glyphs = ArrayList<RectF>()
                val markerGlyphs = ArrayList<RectF>()
                await {
                    glyphs.clear(); markerGlyphs.clear()
                    val clip = viewport.get()
                    visit { node ->
                        if (node.isVisibleToUser && node.text?.toString() == probe(index)) {
                            node.refreshWithExtraData(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY, Bundle().apply {
                                putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, 0)
                                putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, 1)
                            })
                            @Suppress("DEPRECATION")
                            val value = node.extras.getParcelableArray(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY)?.singleOrNull() as? RectF
                            if (value != null && value.width() > 0 && value.height() > 0 && clip != null &&
                                value.left >= clip.left && value.right <= clip.right && value.top >= clip.top && value.bottom <= clip.bottom)
                                glyphs.add(RectF(value))
                        } else if (node.isVisibleToUser && node.text?.toString() == sample.first.marker) {
                            node.refreshWithExtraData(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY, Bundle().apply {
                                putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, 0)
                                putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, sample.first.marker.length)
                            })
                            @Suppress("DEPRECATION")
                            val values = node.extras.getParcelableArray(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY)
                            values?.forEach { value -> if (value is RectF) markerGlyphs.add(RectF(value)) }
                        }
                        false
                    }
                    glyphs.size == 2 && minimumGutter.get() != null && clip != null &&
                        markerGlyphs.size == sample.first.marker.length && markerGlyphs.all {
                            it.width() > 0 && it.height() > 0 && it.left >= clip.left && it.right <= clip.right &&
                                it.top >= clip.top && it.bottom <= clip.bottom
                        }
                }
                val positions = glyphs.sortedBy { it.top }
                assertTrue("${sample.name}: two distinct text rows", positions[1].top > positions[0].top)
                val first = if (sample.rtl) positions[0].right else positions[0].left
                val next = if (sample.rtl) positions[1].right else positions[1].left
                assertEquals("${sample.name}: actual continuation glyph must retain the item gutter", first, next, 1f)
                val clip = requireNotNull(viewport.get())
                val gutter = if (sample.rtl) clip.right - first else first - clip.left
                assertTrue("${sample.name}: the wide marker must reserve more than the default gutter", gutter > minimumGutter.get())
                var markers = 0
                visit { node -> if (node.isVisibleToUser && node.text?.toString() == sample.first.marker) markers++; false }
                assertEquals("${sample.name}: show the authored list number only once", 1, markers)
            }
        }
    }

    @Test fun markdownLinkConsumesItsTapWithoutTogglingReaderControls() {
        val block = MarkdownDocument.parse("[Read more](https://example.invalid/reader)").blocks.single()
        val controls = ReaderControls(mutableStateOf(true), mutableStateOf(false))
        val opened = AtomicInteger()
        val origin = AtomicReference<Offset>()
        val blank = AtomicReference<Offset>()
        val layout = AtomicReference<TextLayoutResult>()
        val timeout = AtomicLong()
        val uriHandler = object : UriHandler {
            override fun openUri(uri: String) {
                assertEquals("https://example.invalid/reader", uri)
                opened.incrementAndGet()
            }
        }
        ActivityScenario.launch(ReaderTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                ReaderTheme(dark = false) {
                    timeout.set(LocalViewConfiguration.current.doubleTapTimeoutMillis)
                    // The component host is edge-to-edge; keep physical taps out of system windows.
                    Box(Modifier.safeDrawingPadding()) {
                    CompositionLocalProvider(LocalUriHandler provides uriHandler) {
                        Box(Modifier.size(260.dp, 160.dp).readingGestures(controls, true)
                            .onGloballyPositioned {
                                origin.set(it.localToScreen(Offset.Zero))
                                blank.set(it.localToScreen(Offset(it.size.width * .8f, it.size.height * .8f)))
                            }) {
                            ReaderMarkdownView(block, markdownText(block, block.content.text, Color.Black),
                                Color.Black, { layout.set(it) })
                        }
                    }
                    }
                }
            } }
            await {
                var focused = false
                scenario.onActivity { focused = it.window.decorView.hasWindowFocus() }
                focused && origin.get() != null && blank.get() != null && layout.get() != null
            }
            fun tap(point: Offset) {
                val down = SystemClock.uptimeMillis()
                for (action in intArrayOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, point.x, point.y, 0)
                        .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
                    try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
                    if (action == MotionEvent.ACTION_DOWN) SystemClock.sleep(40)
                }
                instrumentation.waitForIdleSync()
            }
            tap(origin.get() + layout.get().getBoundingBox(1).center)
            await { opened.get() == 1 }
            // Wait beyond the parent's delayed single-tap action before checking consumption.
            SystemClock.sleep(timeout.get() + 100)
            scenario.onActivity {
                assertTrue("A link tap must not also hide the reading controls", controls.shown)
                assertFalse(controls.held)
            }
            tap(blank.get())
            await {
                var hidden = false
                scenario.onActivity { hidden = !controls.shown && !controls.held }
                hidden
            }
            assertEquals("An unconsumed background tap does not follow a link", 1, opened.get())
        }
    }

    private fun await(predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (predicate()) return
            SystemClock.sleep(30)
        }
        fail("Markdown reading surface did not settle")
    }
    private fun visible(label: String) = visit { it.isVisibleToUser &&
        (it.contentDescription?.toString() == label || it.text?.toString()?.contains(label) == true) }
    private fun click(label: String) = await { visit { it.isVisibleToUser &&
        (it.contentDescription?.toString() == label || it.text?.toString() == label) && clickOwner(it) } }
    @Suppress("DEPRECATION")
    private fun clickOwner(node: AccessibilityNodeInfo, depth: Int = 0): Boolean {
        if (!node.refresh() || !node.isEnabled || !node.isVisibleToUser || node.packageName?.toString() != "app.smallthingz.alldoc") return false
        if (node.isClickable) return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (depth == 16) return false
        val parent = node.parent ?: return false
        return try { clickOwner(parent, depth + 1) } finally { parent.recycle() }
    }
    @Suppress("DEPRECATION")
    private fun visit(action: (AccessibilityNodeInfo) -> Boolean): Boolean {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return false
        return try { visit(root, 0, intArrayOf(0), action) } finally { root.recycle() }
    }
    @Suppress("DEPRECATION")
    private fun visit(node: AccessibilityNodeInfo, depth: Int, visited: IntArray, action: (AccessibilityNodeInfo) -> Boolean): Boolean {
        if (depth > 24 || visited[0]++ >= 512 || !node.refresh()) return false
        if (node.packageName?.toString() == "app.smallthingz.alldoc" && action(node)) return true
        for (i in 0 until minOf(node.childCount, 64)) {
            val child = node.getChild(i) ?: continue
            try { if (visit(child, depth + 1, visited, action)) return true } finally { child.recycle() }
        }
        return false
    }
}
