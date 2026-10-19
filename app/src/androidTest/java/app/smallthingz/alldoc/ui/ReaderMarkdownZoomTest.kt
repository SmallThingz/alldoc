package app.smallthingz.alldoc.ui

import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.smallthingz.alldoc.core.MarkdownDocument
import app.smallthingz.alldoc.state.DocumentMetadata
import app.smallthingz.alldoc.state.DocumentState
import app.smallthingz.alldoc.state.DocumentStore
import app.smallthingz.alldoc.state.DocumentViewMode
import app.smallthingz.alldoc.state.TextPosition
import app.smallthingz.alldoc.theme.ReadingMode
import app.smallthingz.alldoc.theme.ThemeSettings
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderMarkdownZoomTest {
    @Test fun laterCodeAndTableLinesKeepTheirVisibleSourceColumnAcrossZoomChunks() {
        verifyCodeAndTableZoom(readDown = false)
    }

    @Test fun verticalReadingKeepsThePannedCodeAndTableColumnWhenZoomChangesChunks() {
        verifyCodeAndTableZoom(readDown = true)
    }

    @Test fun disposedCodeAndTableRestoreHashPositionUnderNewIdentity() {
        verifyCodeAndTableZoom(readDown = false, reopen = true)
    }

    private fun verifyCodeAndTableZoom(readDown: Boolean, reopen: Boolean = false) {
        for (table in listOf(false, true)) {
            val marker = if (table) "TARGET_TABLE" else "TARGET_CODE"
            val laterRow = if (readDown) 14 else 12
            val rows = (0 until 30).map { index ->
                "ROW_${index.toString().padStart(2, '0')} " + "wide ".repeat(12) +
                    (when (index) { 8 -> "${marker}_08"; laterRow -> "${marker}_$laterRow"; else -> "ordinary" }) + " gap".repeat(8)
            }
            val source = if (table) "| Heading | Value |\n| --- | --- |\n" + rows.joinToString("\n") { "| $it | end |" }
                else "```\n" + rows.joinToString("\n") + "\n```"
            verifyZoom(MarkdownDocument.parse(source), marker, readDown, reopen)
        }
    }

    private fun verifyZoom(document: MarkdownDocument, marker: String, readDown: Boolean, reopen: Boolean) {
        val text = document.plainText
        val initial = text.indexOf(if (reopen) "${marker}_08" else "ROW_08")
        assertTrue(initial > 600)
        val position = mutableStateOf(TextPosition(initial))
        val root = AtomicReference<ViewRootForTest>()
        val present = mutableStateOf(true)
        val identity = mutableStateOf(5101L)
        val mounted = AtomicBoolean()
        val settled = AtomicInteger()
        launchReaderTestActivity().use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                val host = LocalView.current
                SideEffect { root.set(host as ViewRootForTest) }
                CompositionLocalProvider(LocalDensity provides Density(1f), LocalLayoutDirection provides LayoutDirection.Ltr) {
                    MaterialTheme {
                        if (present.value) key(identity.value) {
                            DisposableEffect(Unit) {
                                mounted.set(true)
                                onDispose { mounted.set(false) }
                            }
                            ReaderTextView(text, false, ThemeSettings(ReadingMode.ORIGINAL), false,
                                rememberTextFieldState(), {}, Modifier.size(260.dp, 220.dp), identity.value,
                                position.value, { position.value = it }, { settled.incrementAndGet() }, markdown = document)
                        }
                    }
                }
            } }
            var readingMarker = "${marker}_08"
            fun probe(target: String = readingMarker, action: ((SemanticsNode, Probe) -> Unit)? = null): Probe? {
                var result: Probe? = null
                scenario.onActivity {
                    result = horizontalProbe(root.get(), text, target, action)
                }
                return result
            }
            await("Later source line was not restored") { probe()?.let { it.glyph.top >= it.clip.top - 2f && it.glyph.bottom <= it.clip.bottom + 2f } == true }
            val beforePan = position.value
            val settledBeforePan = settled.get()
            probe { axis, current ->
                val distance = current.glyph.left - (current.clip.left + current.clip.width * .45f)
                assertTrue(distance > 100f)
                assertTrue(checkNotNull(axis.config.getOrNull(SemanticsActions.ScrollBy)?.action).invoke(distance, 0f))
            }
            var previous: Float? = null
            var unchangedAt = SystemClock.uptimeMillis()
            await("The later line did not settle at its horizontal reading position") {
                val current = probe()
                if (current?.scroll != previous) { previous = current?.scroll; unchangedAt = SystemClock.uptimeMillis() }
                current != null && current.scroll > 100f && current.visible() && SystemClock.uptimeMillis() - unchangedAt >= 250
            }
            val panned = checkNotNull(probe())
            if (reopen) {
                await("Horizontal pan did not publish and settle its saved anchor") {
                    position.value.horizontalOffset >= 0 && settled.get() > settledBeforePan
                }
                assertEquals("Horizontal pan changed the saved vertical offset", beforePan.offset, position.value.offset)
                assertEquals("Horizontal pan changed the saved vertical fraction", beforePan.fraction, position.value.fraction, .0001f)
                assertEquals("Horizontal pan changed text zoom", beforePan.zoom, position.value.zoom, .0001f)
            }
            if (readDown) {
                val later = checkNotNull(probe("${marker}_14"))
                assertEquals("Both lines must start in the same old chunk", panned.chunkStart, later.chunkStart)
                val distance = later.lineTop - panned.lineTop
                assertTrue(distance > 0f)
                scenario.onActivity {
                    val axis = allNodes(root.get().semanticsOwner.unmergedRootSemanticsNode).first {
                        it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null
                    }
                    assertTrue(checkNotNull(axis.config.getOrNull(SemanticsActions.ScrollBy)?.action).invoke(0f, distance))
                }
                readingMarker = "${marker}_14"
                await("Vertical reading did not reach the later line with its horizontal position intact") {
                    probe()?.let { current -> current.visible() && abs(current.lineTop - panned.lineTop) < 2f &&
                        abs(current.scroll - panned.scroll) < 1f && position.value.offset in later.lineStart until later.lineEnd } == true
                }
            }
            val before = checkNotNull(probe())
            val settledBeforeZoom = settled.get()
            scenario.onActivity {
                val action = allNodes(root.get().semanticsOwner.unmergedRootSemanticsNode).flatMap {
                    it.config.getOrNull(SemanticsActions.CustomActions).orEmpty().asSequence()
                }.first { it.label == "Zoom in" }
                assertTrue(action.action())
            }
            await("Zoom lost the authored line or column after chunk replacement") {
                val after = probe()
                position.value.zoom >= 1.249f && after != null && after.visible() &&
                    after.glyph.height > before.glyph.height * 1.1f && abs(after.sourceColumn - before.sourceColumn) <= 1
            }
            if (reopen) await("Zoom did not settle before saving the hash position") {
                position.value.zoom == 1.25f && settled.get() > settledBeforeZoom
            }
            val after = checkNotNull(probe())
            assertNotEquals("Fixture must replace the visible chunk, not merely resize it", before.chunkStart, after.chunkStart)
            if (readDown) assertTrue("The new chunk must exclude the earlier panned line", after.chunkStart > panned.sourceColumn)
            assertTrue("The same source column must remain at the viewport edge", abs(before.sourceColumn - after.sourceColumn) <= 1)
            if (reopen) {
                val saved = position.value
                assertTrue("A horizontal glyph anchor must be saved separately", saved.horizontalOffset >= 0)
                assertTrue(saved.horizontalFraction.isFinite())
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                val database = "markdown-position-${UUID.randomUUID()}.db"
                val bytes = text.toByteArray(Charsets.UTF_8)
                val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                val firstSource = "content://markdown-position/original"
                val nextSource = "content://markdown-position/reopened"
                try {
                    // This test thread owns only its unique database; never do SQLite work in an Activity callback.
                    DocumentStore(context, database).use { store ->
                        store.open(hash, DocumentMetadata("Original.md", "text/markdown", bytes.size.toLong(), firstSource))
                        assertTrue(store.saveState(hash, DocumentState(textPosition = saved, mode = DocumentViewMode.TEXT)))
                    }
                    scenario.onActivity { present.value = false }
                    await("The original reader composition was not disposed") { !mounted.get() }
                    val restored = DocumentStore(context, database).use { store ->
                        val reopened = store.open(hash,
                            DocumentMetadata("Reopened.md", "text/markdown", bytes.size.toLong(), nextSource),
                            DocumentState(textPosition = TextPosition(), mode = DocumentViewMode.PAGES))
                        assertEquals("A different URI for the same hash must reuse its row", 1L, store.count())
                        assertEquals(nextSource, reopened.metadata.sourceUri)
                        assertEquals(DocumentViewMode.TEXT, reopened.state.mode)
                        reopened.state.textPosition
                    }
                    assertEquals("Reopening the hash must retain every text position field", saved, restored)
                    val previousIdentity = identity.value
                    scenario.onActivity {
                        position.value = restored
                        identity.value = previousIdentity + 1L
                        present.value = true
                    }
                    assertNotEquals(previousIdentity, identity.value)
                    await("Reopened Markdown lost its source column, vertical position or zoom") {
                        probe()?.let { reopened -> mounted.get() && reopened.visible() &&
                            abs(reopened.sourceColumn - after.sourceColumn) <= 1 &&
                            abs(reopened.lineTop - after.lineTop) < 2f &&
                            abs(reopened.glyph.height - after.glyph.height) < 1f &&
                            position.value == restored } == true
                    }
                    assertEquals("Restoration changed the saved vertical offset", saved.offset, position.value.offset)
                    assertEquals("Restoration changed the saved vertical fraction", saved.fraction, position.value.fraction, .0001f)
                    assertEquals("Restoration changed text zoom", saved.zoom, position.value.zoom, .0001f)
                } finally { context.deleteDatabase(database) }
            }
        }
    }

    @Test fun shortCodeAndTablePublishPanAfterEqualChunkZoom() {
        verifyShortCodeAndTable(plainTextZoom = false)
    }

    @Test fun plainTextZoomPreservesCodeAndTableColumnWhenReturningToNormal() {
        verifyShortCodeAndTable(plainTextZoom = true)
    }

    private fun verifyShortCodeAndTable(plainTextZoom: Boolean) {
        for (table in listOf(false, true)) {
            val marker = if (table) "TARGET_TABLE" else "TARGET_CODE"
            val row = "ROW " + "wide ".repeat(16) + marker + " tail".repeat(8)
            val document = MarkdownDocument.parse(if (table) "| Heading | Value |\n| --- | --- |\n| $row | end |"
                else "```\n$row\n```")
            val text = document.plainText
            assertTrue("The fixture must stay below both chunk targets", text.length < 600)
            val large = readerTextChunks(text, document, 2400)
            val small = readerTextChunks(text, document, 600)
            assertEquals("Zoom must replace the list without changing its contents", large, small)
            assertNotSame(large, small)
            val position = mutableStateOf(TextPosition(text.indexOf("ROW").also { assertTrue(it >= 0) }))
            val plain = mutableStateOf(false)
            val identity = mutableStateOf(5601L)
            val root = AtomicReference<ViewRootForTest>()
            val settled = AtomicInteger()
            val mounts = AtomicInteger()
            val settledMode = AtomicReference<Boolean?>()
            launchReaderTestActivity().use { scenario ->
                scenario.onActivity { activity -> activity.setContent {
                    val host = LocalView.current
                    SideEffect { root.set(host as ViewRootForTest) }
                    CompositionLocalProvider(LocalDensity provides Density(1f), LocalLayoutDirection provides LayoutDirection.Ltr) {
                        MaterialTheme {
                            Box(Modifier.size(260.dp, 140.dp)) {
                                key(identity.value) {
                                    DisposableEffect(Unit) { mounts.incrementAndGet(); onDispose { } }
                                    ReaderTextTransition(plain.value, destinationReady = true, active = true,
                                        onBack = { plain.value = false }, onSettled = { settledMode.set(it) },
                                        pageContent = { input ->
                                            ReaderTextView(text, false, ThemeSettings(ReadingMode.ORIGINAL), false,
                                                rememberTextFieldState(), {}, Modifier.size(260.dp, 140.dp), identity.value,
                                                position.value, { if (!plain.value) position.value = it }, { settled.incrementAndGet() },
                                                markdown = document, active = input, backEnabled = input)
                                        }, textContent = { input ->
                                            ReaderTextView(text, false, ThemeSettings(ReadingMode.ORIGINAL), false,
                                                rememberTextFieldState(), {}, Modifier.size(260.dp, 140.dp), identity.value,
                                                position.value, { if (plain.value) position.value = it }, { settled.incrementAndGet() },
                                                active = input, backEnabled = input)
                                        })
                                }
                            }
                        }
                    }
                } }
                fun sample(action: ((SemanticsNode, Probe) -> Unit)? = null): Probe? {
                    var result: Probe? = null
                    scenario.onActivity { result = horizontalProbe(root.get(), text, marker, action) }
                    return result
                }
                fun plainLayout(): TextLayoutResult? {
                    var result: TextLayoutResult? = null
                    scenario.onActivity {
                        for (node in allNodes(root.get().semanticsOwner.unmergedRootSemanticsNode)) {
                            val layouts = mutableListOf<TextLayoutResult>()
                            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
                            result = layouts.firstOrNull { marker in it.layoutInput.text.text }
                            if (result != null) break
                        }
                    }
                    return result
                }
                fun zoomIn() {
                    scenario.onActivity {
                        val action = allNodes(root.get().semanticsOwner.unmergedRootSemanticsNode).flatMap {
                            it.config.getOrNull(SemanticsActions.CustomActions).orEmpty().asSequence()
                        }.first { it.label == "Zoom in" }
                        assertTrue(action.action())
                    }
                }
                await("Short Markdown did not become ready") { settledMode.get() == false && sample() != null }
                if (!plainTextZoom) {
                    val original = checkNotNull(sample())
                    val beforeZoom = settled.get()
                    zoomIn()
                    await("Short Markdown did not finish its equal-chunk zoom") {
                        position.value.zoom == 1.25f && settled.get() > beforeZoom &&
                            sample()?.let { it.glyph.height > original.glyph.height * 1.1f } == true
                    }
                }
                val beforePan = position.value
                val beforePanSettled = settled.get()
                sample { axis, current ->
                    val distance = current.glyph.left - (current.clip.left + current.clip.width * .45f)
                    assertTrue(distance > 100f)
                    assertTrue(checkNotNull(axis.config.getOrNull(SemanticsActions.ScrollBy)?.action).invoke(distance, 0f))
                }
                var previous: Float? = null
                var unchangedAt = SystemClock.uptimeMillis()
                await("Short-block pan did not publish and settle its source anchor") {
                    val current = sample()
                    if (current?.scroll != previous) { previous = current?.scroll; unchangedAt = SystemClock.uptimeMillis() }
                    current != null && current.visible() && current.scroll > 100f &&
                        SystemClock.uptimeMillis() - unchangedAt >= 250 &&
                        position.value.horizontalOffset >= 0 && settled.get() > beforePanSettled
                }
                val panned = checkNotNull(sample())
                val saved = position.value
                assertEquals(beforePan.offset, saved.offset)
                assertEquals(beforePan.fraction, saved.fraction, .0001f)
                if (plainTextZoom) {
                    scenario.onActivity { plain.value = true }
                    await("Plain Text transition did not settle") { settledMode.get() == true && plainLayout() != null }
                    // Cross the 20px top padding so published motion proves restoration is active.
                    val beforeRead = position.value
                    val beforeReadSettled = settled.get()
                    scenario.onActivity {
                        val axis = allNodes(root.get().semanticsOwner.unmergedRootSemanticsNode).first {
                            it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null
                        }
                        val range = checkNotNull(axis.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange))
                        assertTrue("The wrapped plain-text fixture must have scroll room", range.maxValue() > range.value())
                        assertTrue(checkNotNull(axis.config.getOrNull(SemanticsActions.ScrollBy)?.action).invoke(0f, 24f))
                    }
                    await("Plain Text did not publish its restored vertical reading position") {
                        settled.get() > beforeReadSettled && (position.value.offset != beforeRead.offset ||
                            abs(position.value.fraction - beforeRead.fraction) > .0001f)
                    }
                    assertEquals("Plain Text reading replaced the independent Markdown column", saved.horizontalOffset, position.value.horizontalOffset)
                    assertEquals("Plain Text reading replaced the independent glyph fraction", saved.horizontalFraction, position.value.horizontalFraction, .0001f)
                    val layout = checkNotNull(plainLayout())
                    val start = text.indexOf(layout.layoutInput.text.text)
                    assertTrue(start >= 0)
                    assertTrue("The stored Markdown column must wrap onto a later plain-text line",
                        layout.getLineForOffset(saved.horizontalOffset - start) > layout.getLineForOffset(position.value.offset - start))
                    val beforeZoom = settled.get()
                    zoomIn()
                    await("Plain Text zoom did not settle") { position.value.zoom == 1.25f && settled.get() > beforeZoom }
                    assertEquals("Plain Text zoom replaced the independent Markdown column", saved.horizontalOffset, position.value.horizontalOffset)
                    assertEquals("Plain Text zoom replaced the independent glyph fraction", saved.horizontalFraction, position.value.horizontalFraction, .0001f)
                    scenario.onActivity { plain.value = false }
                    await("Returning to Normal lost the saved Markdown column") {
                        settledMode.get() == false && sample()?.let { it.visible() && it.scroll > 100f &&
                            it.glyph.height > panned.glyph.height * 1.1f && abs(it.sourceColumn - panned.sourceColumn) <= 1 } == true
                    }
                } else {
                    val beforeMount = mounts.get()
                    scenario.onActivity { identity.value += 1L }
                    await("The equal-chunk pan was not restored by the replacement reader") {
                        mounts.get() > beforeMount && sample()?.let { it.visible() && it.scroll > 100f &&
                            abs(it.sourceColumn - panned.sourceColumn) <= 1 && abs(it.glyph.height - panned.glyph.height) < 1f } == true
                    }
                    assertEquals(saved.horizontalOffset, position.value.horizontalOffset)
                    assertEquals(saved.horizontalFraction, position.value.horizontalFraction, .0001f)
                }
            }
        }
    }

    private fun horizontalProbe(root: ViewRootForTest?, text: String, target: String,
        action: ((SemanticsNode, Probe) -> Unit)? = null): Probe? {
        val nodes = root?.let { allNodes(it.semanticsOwner.unmergedRootSemanticsNode).toList() }
            ?: return null
        for (axis in nodes.filter { it.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange) != null }) {
            for (node in allNodes(axis)) {
                val layouts = mutableListOf<TextLayoutResult>()
                node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
                val layout = layouts.firstOrNull() ?: continue
                val chunk = layout.layoutInput.text.text
                val offset = chunk.indexOf(target)
                if (offset < 0) continue
                val start = text.indexOf(chunk)
                check(start >= 0) { "Rendered chunk must retain authored source text" }
                val bounds = axis.boundsInRoot
                val origin = node.positionInRoot
                val glyph = layout.getBoundingBox(offset).translate(origin)
                val line = layout.getLineForOffset(offset)
                val local = layout.getOffsetForPosition(Offset(bounds.left - origin.x + 1f,
                    (layout.getLineTop(line) + layout.getLineBottom(line)) / 2f)).coerceIn(0, chunk.length - 1)
                val result = Probe(start, start + local, bounds, glyph,
                    axis.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange)!!.value(),
                    origin.y + layout.getLineTop(line), start + layout.getLineStart(line),
                    start + layout.getLineEnd(line, visibleEnd = true))
                action?.invoke(axis, result)
                return result
            }
        }
        return null
    }

    private data class Probe(val chunkStart: Int, val sourceColumn: Int, val clip: Rect, val glyph: Rect, val scroll: Float,
        val lineTop: Float, val lineStart: Int, val lineEnd: Int) {
        fun visible() = glyph.left >= clip.left - 2f && glyph.right <= clip.right + 2f &&
            glyph.top >= clip.top - 2f && glyph.bottom <= clip.bottom + 2f
    }

    private fun allNodes(node: SemanticsNode): Sequence<SemanticsNode> = sequence {
        yield(node)
        for (child in node.children) yieldAll(allNodes(child))
    }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 7000
        while (!condition()) {
            check(SystemClock.uptimeMillis() < deadline) { message }
            SystemClock.sleep(20)
        }
    }
}
