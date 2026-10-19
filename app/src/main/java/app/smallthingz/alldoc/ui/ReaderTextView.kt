package app.smallthingz.alldoc.ui

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.delete
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.smallthingz.alldoc.core.MarkdownDocument
import app.smallthingz.alldoc.core.MarkdownKind
import app.smallthingz.alldoc.core.takeScalarSafe
import app.smallthingz.alldoc.state.TextPosition
import app.smallthingz.alldoc.theme.DocumentColors
import app.smallthingz.alldoc.theme.PaperTexture
import app.smallthingz.alldoc.theme.ReadingMode
import app.smallthingz.alldoc.theme.ThemeSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

private class ReaderTextHorizontalState(position: TextPosition) {
    var anchor = position.horizontalOffset.takeIf { it >= 0 }?.let { ReaderHorizontalAnchor(it, position.horizontalFraction) }
}

@Composable
internal fun ReaderTextView(
    text: String?, loading: Boolean, settings: ThemeSettings, searching: Boolean, queryState: TextFieldState,
    onCloseSearch: () -> Unit, modifier: Modifier,
    documentKey: Long, position: TextPosition, onPosition: (TextPosition) -> Unit, onScrollEnd: () -> Unit,
    backEnabled: Boolean = true, requestSearchFocus: Boolean = false, onSearchFocusConsumed: () -> Unit = {},
    contentInsets: WindowInsets = WindowInsets(0, 0, 0, 0),
    onScrollDirection: (Boolean) -> Unit = {},
    contentPadding: PaddingValues = PaddingValues(horizontal = 22.dp, vertical = 20.dp),
    markdown: MarkdownDocument? = null,
    active: Boolean = true,
    prepareContent: Boolean = active,
) {
    val query = queryState.text.toString()
    val colors = DocumentColors.palette(settings)
    val roughness = if (settings.mode == ReadingMode.SEPIA) settings.paperRoughness.coerceIn(0, 100) else 0
    val paper by produceState<android.graphics.Paint?>(null, roughness) {
        value = if (roughness == 0) null else withContext(Dispatchers.Default) { PaperTexture.paint(roughness) }
    }
    BoxWithConstraints(modifier.readerInput(active).background(Color(colors.background)).drawBehind {
        if (roughness > 0) paper?.let { drawContext.canvas.nativeCanvas.drawRect(0f, 0f, size.width, size.height, it) }
    }.windowInsetsPadding(contentInsets)) {
        val density = LocalDensity.current
        val currentDirection by rememberUpdatedState(onScrollDirection)
        val threshold = with(density) { 12.dp.toPx() }
        val scrollObserver = remember(threshold) { object : NestedScrollConnection {
            private var movement = 0f
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && consumed.y != 0f) {
                    if (movement * consumed.y < 0f) movement = 0f
                    movement += consumed.y
                    if (kotlin.math.abs(movement) >= threshold) {
                        currentDirection(movement < 0f)
                        movement = 0f
                    }
                }
                return Offset.Zero
            }
        } }
        val stackedSearch = maxWidth < 320.dp * density.fontScale.coerceAtLeast(1f)
        val hasViewport = constraints.maxWidth > 0 && constraints.maxHeight > 0
        var showSearch by remember(documentKey) { mutableStateOf(searching) }
        LaunchedEffect(searching) { if (searching) showSearch = true }
        val listState = rememberLazyListState()
        val requestedZoom = position.normalized().zoom
        val zoomTier = 2f.pow(ceil(ln((requestedZoom * density.fontScale).coerceAtLeast(1f)) / ln(2f)))
        val chunkTarget = (2400 / (zoomTier * zoomTier)).toInt().coerceIn(8, 2400)
        val prepared by key(documentKey, text, markdown) {
            produceState(2400 to emptyList<ReaderTextChunk>(), chunkTarget) {
                value = chunkTarget to withContext(Dispatchers.Default) {
                    readerTextChunks(text.orEmpty(), markdown, chunkTarget) { ensureActive() }
                }
            }
        }
        val chunks = prepared.second
        // Do not enlarge stale large blocks while smaller off-thread layout units are being prepared.
        val zoom = minOf(requestedZoom, sqrt(2400f / prepared.first) / density.fontScale.coerceAtLeast(1f))
        val layoutKey = remember(constraints.maxWidth, density.density, density.fontScale, zoom) {
            Triple(constraints.maxWidth, density.density, density.fontScale to zoom)
        }
        val request = remember(text, query) { TextSearchRequest(text.orEmpty(), query) }
        val result by produceState<TextSearchResult?>(null, request) {
            value = withContext(Dispatchers.Default) { request.evaluate { ensureActive() } }
        }
        val matches = result?.forRequest(request) ?: NoTextMatches
        val starts = remember(chunks) { IntArray(chunks.size) { chunks[it].start } }
        // Reflow may clamp the visible range; only an actual scroll replaces the saved character anchor.
        val initialPosition = remember(documentKey, chunks, layoutKey, showSearch, contentPadding) { position }
        var restored by remember(documentKey, chunks, layoutKey, showSearch, contentPadding) { mutableStateOf(false) }
        var shown by remember(documentKey, text, markdown) { mutableStateOf(false) }
        val layouts = remember(chunks, layoutKey) { HashMap<Int, TextLayoutResult>() }
        val layoutReady = remember(chunks, layoutKey) { HashMap<Int, CompletableDeferred<TextLayoutResult>>() }
        val currentOnPosition by rememberUpdatedState(onPosition)
        val currentOnScrollEnd by rememberUpdatedState(onScrollEnd)
        val currentPosition by rememberUpdatedState(position)
        val currentDocumentKey by rememberUpdatedState(documentKey)
        val currentChunks by remember { mutableStateOf(chunks, referentialEqualityPolicy()) }.apply { value = chunks }
        val currentLayoutKey by rememberUpdatedState(layoutKey)
        val horizontal = remember(documentKey, text, markdown) { ReaderTextHorizontalState(position) }
        fun visiblePosition(preserveHorizontal: Boolean = false): TextPosition? {
            if (!restored || starts.isEmpty()) return null
            val viewport = listState.layoutInfo
            val row = viewport.visibleItemsInfo.firstOrNull { it.offset + it.size > viewport.viewportStartOffset } ?: return null
            val index = row.index.coerceIn(starts.indices)
            val layout = layouts[index] ?: return null
            val y = (viewport.viewportStartOffset - row.offset).coerceAtLeast(0).toFloat()
            val line = layout.getLineForVerticalPosition(y)
            val top = layout.getLineTop(line)
            val height = (layout.getLineBottom(line) - top).coerceAtLeast(1f)
            val start = starts[index] + layout.getLineStart(line)
            val end = starts[index] + layout.getLineEnd(line, visibleEnd = true)
            var offset: Int? = null
            val kind = chunks[index].block?.kind
            if (preserveHorizontal && start < end && (kind == MarkdownKind.CODE || kind == MarkdownKind.TABLE)) horizontal.anchor?.let { previous ->
                val local = previous.offset - starts[index]
                if (local in 0 until layout.layoutInput.text.length) {
                    // Vertical reading changes the line, while this row keeps its horizontal scroll.
                    val oldGlyph = layout.getBoundingBox(local)
                    val x = (oldGlyph.left + previous.fraction * oldGlyph.width.coerceAtLeast(1f))
                        .coerceIn(-layout.size.width.toFloat(), layout.size.width.toFloat()).let { if (it.isFinite()) it else 0f }
                    val current = layout.getOffsetForPosition(Offset(x, top + height / 2f))
                        .coerceIn(start - starts[index], end - starts[index] - 1)
                    val glyph = layout.getBoundingBox(current)
                    val absolute = starts[index] + current
                    horizontal.anchor = ReaderHorizontalAnchor(absolute, (x - glyph.left) / glyph.width.coerceAtLeast(1f))
                    offset = absolute
                }
            }
            return TextPosition(offset ?: start, ((y - top) / height).coerceIn(0f, 1f), currentPosition.zoom,
                horizontal.anchor?.offset ?: -1, horizontal.anchor?.fraction ?: 0f)
        }
        fun capturePosition() { visiblePosition()?.let(currentOnPosition) }
        val scope = rememberCoroutineScope()
        var zoomJob by remember(documentKey) { mutableStateOf<Job?>(null) }
        val focus = remember(documentKey) { FocusRequester() }
        fun changeZoom(target: Float, animated: Boolean) {
            if (!active || searching || !target.isFinite()) return
            val next = target.coerceIn(TextPosition.MIN_ZOOM, TextPosition.MAX_ZOOM)
            if (next == currentPosition.zoom) return
            focus.requestFocus()
            zoomJob?.cancel()
            val anchor = visiblePosition(preserveHorizontal = true) ?: currentPosition
            if (!animated) currentOnPosition(anchor.copy(zoom = next))
            else zoomJob = scope.launch {
                try { animate(anchor.zoom, next, animationSpec = tween(180)) { value, _ ->
                    currentOnPosition(anchor.copy(zoom = value))
                } } finally { currentOnScrollEnd() }
            }
        }
        val changeZoomNow by rememberUpdatedState<(Float, Boolean) -> Unit>(newValue = { target, animated -> changeZoom(target, animated) })
        DisposableEffect(documentKey) { onDispose { zoomJob?.cancel() } }
        LaunchedEffect(active, searching) { if (!active || searching) zoomJob?.cancel() }
        // An incoming crossfade prepares its saved anchor before it owns any input.
        LaunchedEffect(chunks, documentKey, layoutKey, showSearch, contentPadding, hasViewport, prepareContent) {
            if (!prepareContent || restored || chunks.isEmpty() || !hasViewport) return@LaunchedEffect
            val index = chunkIndex(starts, initialPosition.offset.coerceAtMost(text.orEmpty().length))
            val ready = layoutReady.getOrPut(index) { CompletableDeferred() }
            // Already visible text can reflow in place; do not flash its block's beginning on every zoom frame.
            if (listState.layoutInfo.visibleItemsInfo.none { it.index == index }) listState.scrollToItem(index)
            val layout = layouts[index] ?: ready.await()
            val local = (initialPosition.offset - starts[index]).coerceIn(0, layout.layoutInput.text.length)
            val line = layout.getLineForOffset(local)
            val y = layout.getLineTop(line) + initialPosition.fraction * (layout.getLineBottom(line) - layout.getLineTop(line))
            val padding = if (initialPosition.offset == 0 && initialPosition.fraction == 0f) 0
                else listState.layoutInfo.beforeContentPadding
            listState.scrollToItem(index, y.toInt().coerceAtLeast(0) + padding)
            restored = true
            shown = true
        }
        LaunchedEffect(listState, documentKey, active) {
            if (active) return@LaunchedEffect
            listState.stopScroll(MutatePriority.PreventUserInput)
            if (restored) { capturePosition(); currentOnScrollEnd() }
        }
        LaunchedEffect(listState, restored, documentKey, layoutKey, showSearch, contentPadding, active) {
            if (!active || !restored) return@LaunchedEffect
            var scrolling = false
            snapshotFlow { Triple(listState.isScrollInProgress, listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset) }.collect { (active, _, _) ->
                if (active) {
                    scrolling = true
                    capturePosition()
                } else if (scrolling) {
                    scrolling = false
                    capturePosition()
                    currentOnScrollEnd()
                }
            }
        }
        var match by rememberSaveable(documentKey, query) { mutableIntStateOf(0) }
        val activeStart = matches.getOrNull(match) ?: -1
        // A visit survives restoration but advances even when Next wraps to the same result.
        var searchVisit by rememberSaveable(documentKey) { mutableLongStateOf(0L) }
        var visitQuery by rememberSaveable(documentKey) { mutableStateOf(query) }
        if (visitQuery != query) { visitQuery = query; searchVisit++ }
        var lastSearchVisit by rememberSaveable(documentKey) { mutableStateOf<Long?>(null) }
        var lastSearch by rememberSaveable(documentKey, query) { mutableStateOf<Int?>(null) }
        LaunchedEffect(request, matches, match, searchVisit, starts, restored, active, loading) {
            if (!active || loading || text == null || matches === NoTextMatches) return@LaunchedEffect
            if (activeStart < 0) lastSearch = null
            else if (restored && starts.isNotEmpty() && (lastSearch != activeStart || lastSearchVisit != searchVisit)) {
                val index = chunkIndex(starts, activeStart)
                val ready = layoutReady.getOrPut(index) { CompletableDeferred() }
                listState.scrollToItem(index)
                val layout = layouts[index] ?: ready.await()
                val line = layout.getLineForOffset((activeStart - starts[index]).coerceAtMost(layout.layoutInput.text.length))
                listState.scrollToItem(index, layout.getLineTop(line).toInt().coerceAtLeast(0))
                capturePosition()
                currentOnScrollEnd()
                // A canceled reveal remains pending; closing a modal never repeats a completed one.
                lastSearch = activeStart
                lastSearchVisit = searchVisit
            }
        }
        Column(Modifier.fillMaxSize()) {
            if (showSearch) ReaderMotionLayer(active = active && backEnabled && searching, visible = searching,
                onBack = { onCloseSearch(); showSearch = false }, fromTop = true) { dismissSearch ->
                val focus = remember(documentKey) { FocusRequester() }
                val keyboard = LocalSoftwareKeyboardController.current
                LaunchedEffect(requestSearchFocus, searching, backEnabled, documentKey, active) {
                    if (active && requestSearchFocus && searching && backEnabled && focus.requestFocus()) {
                        keyboard?.show()
                        onSearchFocusConsumed()
                    }
                }
                val hasQuery = query.isNotBlank()
                val count = if (matches.size == 0) "No matches" else
                    "${match + 1} / ${matches.size}${if (matches.size == 10000) "+" else ""}"
                val stepMatch: (Int) -> Unit = { step ->
                    if (matches.size > 0) {
                        match = (match + step + matches.size) % matches.size
                        searchVisit++
                    }
                }
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Column {
                        Row(Modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(state = queryState,
                                modifier = Modifier.weight(1f).focusRequester(focus).semantics { contentDescription = "Find in text" },
                                enabled = active && backEnabled && searching, lineLimits = TextFieldLineLimits.SingleLine,
                                inputTransformation = InputTransformation {
                                    val capped = asCharSequence().toString().takeScalarSafe(128)
                                    if (length > capped.length) delete(capped.length, length)
                                }, placeholder = { Text("Find in text") },
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                onKeyboardAction = { stepMatch(1) },
                                trailingIcon = if (hasQuery && !stackedSearch) ({
                                    Text(count, Modifier.widthIn(max = 80.dp).padding(horizontal = 8.dp),
                                        style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }) else null)
                            if (hasQuery && !stackedSearch) ReaderMatchActions(active && matches.size > 0, stepMatch)
                            ReaderAction(Icons.Outlined.Close, "Close search", onClick = dismissSearch)
                        }
                        if (hasQuery && stackedSearch) Row(Modifier.fillMaxWidth().padding(start = 16.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(count, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                            ReaderMatchActions(active && matches.size > 0, stepMatch)
                        }
                    }
                }
            }
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                text.isNullOrEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(if (text == null) "Text is unavailable." else "No extractable text.", color = Color(colors.foreground))
                }
                else -> SelectionContainer {
                    LazyColumn(state = listState, userScrollEnabled = active, modifier = Modifier.fillMaxSize().nestedScroll(scrollObserver)
                        .semantics {
                            stateDescription = "Text size ${(requestedZoom * 100).toInt()}%"
                            if (active && !searching) customActions = listOf(
                                CustomAccessibilityAction("Zoom in") { changeZoom(currentPosition.zoom * 1.25f, true); true },
                                CustomAccessibilityAction("Zoom out") { changeZoom(currentPosition.zoom / 1.25f, true); true },
                                CustomAccessibilityAction("Reset text size") { changeZoom(1f, true); true })
                        }.onPreviewKeyEvent { event ->
                            if (!active || searching || event.type != KeyEventType.KeyDown || !event.isCtrlPressed) false
                            else when (event.key) {
                                Key.Equals, Key.Plus -> { changeZoom(currentPosition.zoom * 1.25f, true); true }
                                Key.Minus -> { changeZoom(currentPosition.zoom / 1.25f, true); true }
                                Key.Zero -> { changeZoom(1f, true); true }
                                else -> false
                            }
                        }.focusRequester(focus).focusable(active && !searching)
                        .pointerInput(documentKey, active, searching) {
                            if (!active || searching) return@pointerInput
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                focus.requestFocus()
                                zoomJob?.cancel()
                                var motion = 1f
                                var owns = false
                                var target = currentPosition.zoom
                                try {
                                    do {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                        val multiple = event.changes.count { it.pressed } > 1
                                        val factor = event.calculateZoom()
                                        motion *= factor
                                        if (!owns) owns = multiple && abs(1f - motion) * event.calculateCentroidSize(useCurrent = false) > viewConfiguration.touchSlop
                                        if (owns) {
                                            if (multiple) {
                                                target = (target * factor).coerceIn(TextPosition.MIN_ZOOM, TextPosition.MAX_ZOOM)
                                                changeZoomNow(target, false)
                                            }
                                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                                        }
                                    } while (event.changes.any { it.pressed })
                                } finally { if (owns) currentOnScrollEnd() }
                            }
                        }.graphicsLayer { alpha = if (restored || shown) 1f else 0f },
                        contentPadding = contentPadding) {
                        itemsIndexed(chunks) { index, item ->
                            val chunk = item.text
                            val block = item.block
                            key(documentKey, block) {
                            DisposableEffect(index, chunks, layoutKey) { onDispose { layouts.remove(index); layoutReady.remove(index) } }
                            val highlight = MaterialTheme.colorScheme.primaryContainer
                            val foreground = MaterialTheme.colorScheme.onPrimaryContainer
                            val activeBackground = MaterialTheme.colorScheme.primary
                            val activeForeground = MaterialTheme.colorScheme.onPrimary
                            val offset = starts[index]
                            val visible = remember(chunk, index, chunks.size) { visibleTextChunk(chunk, index == chunks.lastIndex) }
                            val richText = markdownText(block, visible, Color(colors.foreground))
                            val marked = remember(richText, query, highlight, foreground, activeStart, offset, activeBackground, activeForeground) {
                                val builder = AnnotatedString.Builder(richText)
                                if (query.isNotBlank()) {
                                    var start = visible.indexOf(query, ignoreCase = true)
                                    while (start >= 0) {
                                        builder.addStyle(SpanStyle(background = highlight, color = foreground), start, start + query.length)
                                        start = visible.indexOf(query, start + query.length, ignoreCase = true)
                                    }
                                }
                                if (activeStart >= 0 && activeStart < offset + visible.length && activeStart + query.length > offset) {
                                    builder.addStyle(SpanStyle(background = activeBackground, color = activeForeground),
                                        maxOf(0, activeStart - offset), minOf(visible.length, activeStart + query.length - offset))
                                }
                                builder.toAnnotatedString()
                            }
                            val onLayout: (TextLayoutResult) -> Unit = { layout ->
                                layouts[index] = layout
                                layoutReady.getOrPut(index) { CompletableDeferred() }.complete(layout)
                            }
                            val searchMatch = if (activeStart in offset until offset + visible.length)
                                searchVisit to TextRange(activeStart - offset, minOf(visible.length, activeStart + query.length - offset)) else null
                            if (block?.kind == MarkdownKind.CODE || block?.kind == MarkdownKind.TABLE)
                                ReaderMarkdownView(block, marked, Color(colors.foreground), onLayout, active = active,
                                    searchMatch = searchMatch, layoutKey = layoutKey, zoom = zoom,
                                    horizontalAnchor = horizontal.anchor, visibleOffset = position.offset,
                                    onHorizontalAnchor = { anchor, closing ->
                                        if (currentDocumentKey == documentKey && currentChunks === chunks && currentLayoutKey == layoutKey &&
                                            (!closing || horizontal.anchor?.offset?.let { it in offset until offset + visible.length } == true)) {
                                            horizontal.anchor = anchor
                                            currentOnPosition(currentPosition.copy(horizontalOffset = anchor.offset, horizontalFraction = anchor.fraction))
                                        }
                                    }, onHorizontalScrollEnd = {
                                        if (currentDocumentKey == documentKey && currentChunks === chunks && currentLayoutKey == layoutKey) currentOnScrollEnd()
                                    })
                            else key(layoutKey) {
                                if (block == null) Text(marked, color = Color(colors.foreground), style = MaterialTheme.typography.bodyLarge.let {
                                    it.copy(fontSize = it.fontSize * zoom, lineHeight = it.lineHeight * zoom)
                                }, onTextLayout = onLayout)
                                else ReaderMarkdownView(block, marked, Color(colors.foreground), onLayout, active = active, zoom = zoom)
                            }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReaderMatchActions(enabled: Boolean, onStep: (Int) -> Unit) {
    ReaderAction(Icons.Outlined.ExpandLess, "Previous match", enabled) { onStep(-1) }
    ReaderAction(Icons.Outlined.ExpandMore, "Next match", enabled) { onStep(1) }
}
