package app.smallthingz.alldoc.ui

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.smallthingz.alldoc.core.MarkdownDocument
import app.smallthingz.alldoc.core.takeScalarSafe
import app.smallthingz.alldoc.state.TextPosition
import app.smallthingz.alldoc.theme.DocumentColors
import app.smallthingz.alldoc.theme.PaperTexture
import app.smallthingz.alldoc.theme.ReadingMode
import app.smallthingz.alldoc.theme.ThemeSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

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
) {
    val query = queryState.text.toString()
    val colors = DocumentColors.palette(settings)
    val roughness = if (settings.mode == ReadingMode.SEPIA) settings.paperRoughness.coerceIn(0, 100) else 0
    val paper by produceState<android.graphics.Paint?>(null, roughness) {
        value = if (roughness == 0) null else withContext(Dispatchers.Default) { PaperTexture.paint(roughness) }
    }
    BoxWithConstraints(modifier.background(Color(colors.background)).drawBehind {
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
        val layoutKey = remember(constraints.maxWidth, density.density, density.fontScale) {
            Triple(constraints.maxWidth, density.density, density.fontScale)
        }
        val hasViewport = constraints.maxWidth > 0 && constraints.maxHeight > 0
        var showSearch by remember(documentKey) { mutableStateOf(searching) }
        LaunchedEffect(searching) { if (searching) showSearch = true }
        val listState = rememberLazyListState()
        val chunks by produceState<List<String>>(emptyList(), text, markdown) {
            value = withContext(Dispatchers.Default) {
                if (markdown == null) splitText(text.orEmpty()) { ensureActive() }
                else markdown.blocks.mapIndexed { index, block ->
                    ensureActive()
                    markdown.plainText.substring(block.start, markdown.blocks.getOrNull(index + 1)?.start ?: markdown.plainText.length)
                }
            }
        }
        val request = remember(text, query) { TextSearchRequest(text.orEmpty(), query) }
        val result by produceState<TextSearchResult?>(null, request) {
            value = withContext(Dispatchers.Default) { request.evaluate { ensureActive() } }
        }
        val matches = result?.forRequest(request) ?: NoTextMatches
        val starts = remember(chunks, markdown) {
            var offset = 0
            IntArray(chunks.size) { index -> markdown?.blocks?.getOrNull(index)?.start ?: offset.also { offset += chunks[index].length } }
        }
        // Reflow may clamp the visible range; only an actual scroll replaces the saved character anchor.
        val initialPosition = remember(documentKey, chunks, layoutKey, showSearch, contentPadding) { position }
        var restored by remember(documentKey, chunks, layoutKey, showSearch, contentPadding) { mutableStateOf(false) }
        val layouts = remember(chunks, layoutKey) { HashMap<Int, TextLayoutResult>() }
        val layoutReady = remember(chunks, layoutKey) { HashMap<Int, CompletableDeferred<TextLayoutResult>>() }
        val currentOnPosition by rememberUpdatedState(onPosition)
        val currentOnScrollEnd by rememberUpdatedState(onScrollEnd)
        fun capturePosition() {
            if (!restored || starts.isEmpty()) return
            val viewport = listState.layoutInfo
            val row = viewport.visibleItemsInfo.firstOrNull { it.offset + it.size > viewport.viewportStartOffset } ?: return
            val index = row.index.coerceIn(starts.indices)
            val layout = layouts[index] ?: return
            val y = (viewport.viewportStartOffset - row.offset).coerceAtLeast(0).toFloat()
            val line = layout.getLineForVerticalPosition(y)
            val top = layout.getLineTop(line)
            val height = (layout.getLineBottom(line) - top).coerceAtLeast(1f)
            currentOnPosition(TextPosition(starts[index] + layout.getLineStart(line), ((y - top) / height).coerceIn(0f, 1f)))
        }
        LaunchedEffect(chunks, documentKey, layoutKey, showSearch, contentPadding, hasViewport) {
            if (restored || chunks.isEmpty() || !hasViewport) return@LaunchedEffect
            val index = chunkIndex(starts, initialPosition.offset.coerceAtMost(text.orEmpty().length))
            val ready = layoutReady.getOrPut(index) { CompletableDeferred() }
            listState.scrollToItem(index)
            val layout = layouts[index] ?: ready.await()
            val local = (initialPosition.offset - starts[index]).coerceIn(0, layout.layoutInput.text.length)
            val line = layout.getLineForOffset(local)
            val y = layout.getLineTop(line) + initialPosition.fraction * (layout.getLineBottom(line) - layout.getLineTop(line))
            val padding = if (initialPosition.offset == 0 && initialPosition.fraction == 0f) 0
                else listState.layoutInfo.beforeContentPadding
            listState.scrollToItem(index, y.toInt().coerceAtLeast(0) + padding)
            restored = true
        }
        LaunchedEffect(listState, restored, documentKey, layoutKey, showSearch, contentPadding) {
            if (!restored) return@LaunchedEffect
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
        var match by remember(query, text) { mutableIntStateOf(0) }
        val activeStart = matches.getOrNull(match) ?: -1
        var lastSearch by remember(documentKey) { mutableStateOf<Pair<String, Int>?>(null) }
        var pendingSearch by remember(documentKey) { mutableStateOf<Pair<String, Int>?>(null) }
        var matchLine by remember(matches, match, layoutKey) { mutableStateOf<Pair<Int, Int>?>(null) }
        LaunchedEffect(request, matches, match, starts, restored) {
            if (activeStart < 0) { lastSearch = null; pendingSearch = null }
            else if (restored && starts.isNotEmpty() && lastSearch != (query to activeStart)) {
                lastSearch = query to activeStart
                pendingSearch = lastSearch
                val index = chunkIndex(starts, activeStart)
                listState.scrollToItem(index)
                layouts[index]?.let { layout ->
                    matchLine = index to layout.getLineTop(layout.getLineForOffset((activeStart - starts[index]).coerceAtMost(layout.layoutInput.text.length))).toInt().coerceAtLeast(0)
                }
            }
        }
        LaunchedEffect(request, matchLine, restored) {
            if (restored && pendingSearch == (query to activeStart)) matchLine?.let { (index, offset) ->
                listState.scrollToItem(index, offset)
                capturePosition()
                currentOnScrollEnd()
                pendingSearch = null
            }
        }
        Column(Modifier.fillMaxSize()) {
            if (showSearch) ReaderMotionLayer(active = backEnabled && searching, visible = searching,
                onBack = { onCloseSearch(); showSearch = false }, fromTop = true) { dismissSearch ->
                val focus = remember(documentKey) { FocusRequester() }
                val keyboard = LocalSoftwareKeyboardController.current
                LaunchedEffect(requestSearchFocus, searching, backEnabled, documentKey) {
                    if (requestSearchFocus && searching && backEnabled && focus.requestFocus()) {
                        keyboard?.show()
                        onSearchFocusConsumed()
                    }
                }
                val hasQuery = query.isNotBlank()
                val count = if (matches.size == 0) "No matches" else
                    "${match + 1} / ${matches.size}${if (matches.size == 10000) "+" else ""}"
                val stepMatch: (Int) -> Unit = { step ->
                    if (matches.size > 0) match = (match + step + matches.size) % matches.size
                }
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Column {
                        Row(Modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(state = queryState,
                                modifier = Modifier.weight(1f).focusRequester(focus).semantics { contentDescription = "Find in text" },
                                enabled = backEnabled && searching, lineLimits = TextFieldLineLimits.SingleLine,
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
                            if (hasQuery && !stackedSearch) ReaderMatchActions(matches.size > 0, stepMatch)
                            ReaderAction(Icons.Outlined.Close, "Close search", onClick = dismissSearch)
                        }
                        if (hasQuery && stackedSearch) Row(Modifier.fillMaxWidth().padding(start = 16.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(count, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                            ReaderMatchActions(matches.size > 0, stepMatch)
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
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().nestedScroll(scrollObserver).graphicsLayer { alpha = if (restored) 1f else 0f },
                        contentPadding = contentPadding) {
                        itemsIndexed(chunks) { index, chunk ->
                            key(layoutKey) {
                            DisposableEffect(index) { onDispose { layouts.remove(index); layoutReady.remove(index) } }
                            val highlight = MaterialTheme.colorScheme.primaryContainer
                            val foreground = MaterialTheme.colorScheme.onPrimaryContainer
                            val activeBackground = MaterialTheme.colorScheme.primary
                            val activeForeground = MaterialTheme.colorScheme.onPrimary
                            val offset = starts[index]
                            val visible = remember(chunk, index, chunks.size) { visibleTextChunk(chunk, index == chunks.lastIndex) }
                            val block = markdown?.blocks?.getOrNull(index)
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
                                if (pendingSearch == (query to activeStart) && activeStart in offset until offset + chunk.length) {
                                    val line = layout.getLineForOffset((activeStart - offset).coerceAtMost(visible.length))
                                    matchLine = index to layout.getLineTop(line).toInt().coerceAtLeast(0)
                                }
                            }
                            if (block == null) Text(marked, color = Color(colors.foreground), style = MaterialTheme.typography.bodyLarge, onTextLayout = onLayout)
                            else ReaderMarkdownView(block, marked, Color(colors.foreground), onLayout,
                                searchMatch = if (activeStart in offset until offset + visible.length)
                                    query to TextRange(activeStart - offset, minOf(visible.length, activeStart + query.length - offset)) else null)
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
