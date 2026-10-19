package app.smallthingz.alldoc.ui

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import app.smallthingz.alldoc.core.MarkdownBlock
import kotlinx.coroutines.flow.collect
import app.smallthingz.alldoc.core.MarkdownKind

/** Only visible blocks are styled; ReaderTextView owns search, selection and durable line anchors. */
@Composable
internal fun markdownText(block: MarkdownBlock?, visible: String, ink: Color): AnnotatedString {
    val uri = LocalUriHandler.current
    return remember(block, visible, ink, uri) {
        val result = AnnotatedString.Builder(visible)
        block?.content?.spans?.forEach { span ->
            val start = span.start.coerceIn(0, visible.length)
            val end = span.end.coerceIn(start, visible.length)
            if (start < end) {
                result.addStyle(SpanStyle(
                    fontWeight = if (span.bold) FontWeight.Bold else null,
                    fontStyle = if (span.italic) FontStyle.Italic else null,
                    textDecoration = if (span.strike) TextDecoration.LineThrough else null,
                    fontFamily = if (span.code) FontFamily.Monospace else null,
                    background = if (span.code) ink.copy(alpha = .10f) else Color.Unspecified,
                ), start, end)
                span.link?.takeIf { it.startsWith("https://", true) || it.startsWith("http://", true) ||
                    it.startsWith("mailto:", true) }?.let { target ->
                    result.addLink(LinkAnnotation.Clickable(target,
                        styles = TextLinkStyles(style = SpanStyle(color = ink, textDecoration = TextDecoration.Underline)),
                        linkInteractionListener = { runCatching { uri.openUri(target) } }), start, end)
                }
            }
        }
        if (block?.kind == MarkdownKind.TABLE && block.level == 1) {
            result.addStyle(SpanStyle(fontWeight = FontWeight.Bold), 0, visible.indexOf('\n').let { if (it < 0) visible.length else it })
        }
        result.toAnnotatedString()
    }
}

/** A bounded rich block, never a document-sized bitmap or a separate artificial page. */
@Composable
internal fun ReaderMarkdownView(block: MarkdownBlock, text: AnnotatedString, ink: Color,
    onTextLayout: (TextLayoutResult) -> Unit, searchMatch: Pair<Long, TextRange>? = null, active: Boolean = true,
    layoutKey: Any? = null,
    zoom: Float = 1f,
    horizontalAnchor: ReaderHorizontalAnchor? = null,
    visibleOffset: Int = block.start,
    onHorizontalAnchor: (ReaderHorizontalAnchor, Boolean) -> Unit = { _, _ -> },
    onHorizontalScrollEnd: () -> Unit = {},
) {
    val typography = MaterialTheme.typography
    val maximumLine = with(LocalDensity.current) { 8192.toDp() }
    val baseStyle = when (block.kind) {
        MarkdownKind.HEADING -> (when (block.level) {
            1 -> typography.headlineLarge
            2 -> typography.headlineMedium
            3 -> typography.headlineSmall
            else -> typography.titleLarge
        }).copy(fontWeight = FontWeight.Bold)
        MarkdownKind.CODE, MarkdownKind.TABLE -> typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
        else -> typography.bodyLarge
    }
    val style = baseStyle.copy(fontSize = baseStyle.fontSize * zoom, lineHeight = baseStyle.lineHeight * zoom)
    var modifier: Modifier = Modifier
    when (block.kind) {
        MarkdownKind.HEADING -> modifier = modifier.semantics { heading() }
        MarkdownKind.QUOTE -> modifier = modifier.drawBehind {
            drawLine(ink.copy(alpha = .28f), Offset.Zero, Offset(0f, size.height), 2.dp.toPx())
        }.padding(start = 14.dp)
        MarkdownKind.CODE, MarkdownKind.TABLE -> {
            val scroll = rememberScrollState()
            var viewport by remember(layoutKey) { mutableIntStateOf(0) }
            var layout by remember(layoutKey) { mutableStateOf<TextLayoutResult?>(null) }
            val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
            val inset = with(LocalDensity.current) { 10.dp.toPx() }
            val currentHorizontalAnchor by rememberUpdatedState(onHorizontalAnchor)
            val currentHorizontalScrollEnd by rememberUpdatedState(onHorizontalScrollEnd)
            var restoringHorizontal by remember { mutableStateOf(false) }
            var restoreRevision by remember { mutableIntStateOf(0) }
            var horizontalRestored by remember(layoutKey) { mutableStateOf(false) }
            val currentLayoutKey by rememberUpdatedState(layoutKey)
            fun captureHorizontal(sourceOffset: Int, closing: Boolean = false) {
                val measured = layout ?: return
                val length = measured.layoutInput.text.length
                if (length == 0 || viewport <= 0 || scroll.maxValue == Int.MAX_VALUE || measured.layoutInput.text != text) return
                val line = measured.getLineForOffset((sourceOffset - block.start).coerceIn(0, length - 1))
                val x = (if (rtl) scroll.maxValue - scroll.value else scroll.value).toFloat() - inset
                val y = (measured.getLineTop(line) + measured.getLineBottom(line)) / 2f
                val offset = measured.getOffsetForPosition(Offset(x, y)).coerceIn(0, length - 1)
                val glyph = measured.getBoundingBox(offset)
                currentHorizontalAnchor(ReaderHorizontalAnchor(block.start + offset, (x - glyph.left) / glyph.width.coerceAtLeast(1f)), closing)
            }
            val captureHorizontalNow by rememberUpdatedState({ closing: Boolean -> captureHorizontal(visibleOffset, closing) })
            LaunchedEffect(scroll, active, layoutKey) {
                if (!active) { scroll.stopScroll(MutatePriority.PreventUserInput); return@LaunchedEffect }
                var moved = false
                var revision = restoreRevision
                try {
                    snapshotFlow { Triple(scroll.value, scroll.isScrollInProgress, restoreRevision to restoringHorizontal) }.collect { (_, scrolling, restore) ->
                        if (currentLayoutKey != layoutKey) return@collect
                        if (revision != restore.first) { revision = restore.first; moved = false }
                        else if (restore.second) moved = false
                        else if (scrolling) { moved = true; captureHorizontalNow(false) }
                        else if (moved) {
                            moved = false
                            captureHorizontalNow(false)
                            currentHorizontalScrollEnd()
                        }
                    }
                } finally {
                    if (moved && currentLayoutKey == layoutKey) {
                        if (revision == restoreRevision && !restoringHorizontal) captureHorizontalNow(true)
                        currentHorizontalScrollEnd()
                    }
                }
            }
            var revealedVisit by rememberSaveable { mutableStateOf<Long?>(null) }
            LaunchedEffect(horizontalAnchor, layout, viewport, scroll.maxValue, rtl, inset, searchMatch) {
                val measured = layout ?: return@LaunchedEffect
                if (horizontalRestored || viewport <= 0 || scroll.maxValue == Int.MAX_VALUE || measured.layoutInput.text != text) return@LaunchedEffect
                horizontalRestored = true
                val anchor = horizontalAnchor ?: return@LaunchedEffect
                val offset = anchor.offset - block.start
                if (scroll.isScrollInProgress || offset !in 0 until measured.layoutInput.text.length ||
                    searchMatch?.let { revealedVisit != it.first } == true) return@LaunchedEffect
                val glyph = measured.getBoundingBox(offset)
                val logical = (glyph.left + anchor.fraction * glyph.width.coerceAtLeast(1f) + inset).toInt().coerceIn(0, scroll.maxValue)
                restoringHorizontal = true
                restoreRevision++
                try {
                    // Default-priority restoration yields to any newly started user drag.
                    scroll.scrollTo(if (rtl) scroll.maxValue - logical else logical)
                } finally { restoringHorizontal = false }
            }
            // Hydration/reflow never replays a completed reveal over the user's horizontal position.
            // The parent visit also changes when an evicted block's result is selected again.
            LaunchedEffect(searchMatch, layout, viewport, scroll.maxValue, rtl, inset, active) {
                if (!active) return@LaunchedEffect
                val selection = searchMatch ?: return@LaunchedEffect
                val range = selection.second
                if (revealedVisit == selection.first) return@LaunchedEffect
                val measured = layout ?: return@LaunchedEffect
                if (viewport <= 0 || scroll.maxValue == Int.MAX_VALUE || measured.layoutInput.text != text ||
                    range.start !in 0 until measured.layoutInput.text.length) return@LaunchedEffect
                val first = measured.getBoundingBox(range.start)
                val lineEnd = measured.getLineEnd(measured.getLineForOffset(range.start), visibleEnd = true)
                var left = first.left
                var right = first.right
                for (offset in range.start + 1 until minOf(range.end, lineEnd)) {
                    val glyph = measured.getBoundingBox(offset)
                    left = minOf(left, glyph.left); right = maxOf(right, glyph.right)
                }
                // An oversized/multiline match begins at its first visible glyph.
                if (right - left > viewport) { left = first.left; right = first.right }
                left += inset; right += inset
                val current = if (rtl) scroll.maxValue - scroll.value else scroll.value
                val next = when {
                    left < current -> kotlin.math.floor(left).toInt()
                    right > current + viewport -> kotlin.math.ceil(right - viewport).toInt()
                    else -> current
                }.coerceIn(0, scroll.maxValue)
                scroll.scrollTo(if (rtl) scroll.maxValue - next else next)
                revealedVisit = selection.first
                captureHorizontal(block.start + range.start)
                currentHorizontalScrollEnd()
            }
            // Keep text coordinates inside the scroll node for accessibility character bounds.
            key(layoutKey) {
                Box(Modifier.background(ink.copy(alpha = .06f), MaterialTheme.shapes.extraSmall)
                    .onSizeChanged { viewport = it.width }.horizontalScroll(scroll, enabled = active)) {
                    Text(text, color = ink, style = style,
                        modifier = Modifier.widthIn(max = maximumLine).padding(horizontal = 10.dp),
                        onTextLayout = { layout = it; onTextLayout(it) })
                }
            }
            return
        }
        MarkdownKind.RULE -> modifier = modifier.fillMaxWidth().drawBehind {
            drawLine(ink.copy(alpha = .25f), Offset(0f, size.height / 3), Offset(size.width, size.height / 3), 1.dp.toPx())
        }.clearAndSetSemantics { contentDescription = "Section break" }
        else -> Unit
    }
    if (block.kind == MarkdownKind.LIST_ITEM) {
        // Continuations retain the item's gutter without painting its number again.
        val measuredMarker = rememberTextMeasurer().measure(block.markerContext, style).size.width
        Layout(content = {
            if (block.marker.isNotEmpty()) Text(block.marker, color = ink, style = style)
            Text(text, color = ink, style = style, onTextLayout = onTextLayout)
        }, modifier = Modifier.fillMaxWidth()) { children, constraints ->
            val width = constraints.maxWidth
            val markerWidth = maxOf(28.dp.roundToPx(), measuredMarker + 8.dp.roundToPx())
            val bodyMinimum = minOf(64.dp.roundToPx(), width / 2)
            val indent = minOf((block.level.coerceIn(0, 8) * 12).dp.roundToPx(),
                (width - markerWidth - bodyMinimum).coerceAtLeast(0))
            val gutter = minOf(markerWidth, width - indent - bodyMinimum)
            val childConstraints = constraints.copy(minWidth = 0, minHeight = 0)
            val marker = if (block.marker.isEmpty()) null else children.first().measure(childConstraints.copy(
                maxWidth = gutter - minOf(8.dp.roundToPx(), gutter / 2)))
            val bodyWidth = width - indent - gutter
            val body = children.last().measure(childConstraints.copy(minWidth = bodyWidth, maxWidth = bodyWidth))
            layout(width, constraints.constrainHeight(maxOf(body.height, marker?.height ?: 0))) {
                marker?.placeRelative(indent, 0)
                body.placeRelative(indent + gutter, 0)
            }
        }
    } else Text(text, color = if (block.kind == MarkdownKind.RULE) Color.Transparent else ink,
        style = style, modifier = modifier,
        onTextLayout = onTextLayout)
}
