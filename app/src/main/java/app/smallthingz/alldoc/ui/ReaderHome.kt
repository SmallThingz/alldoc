package app.smallthingz.alldoc.ui

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animate
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemKey
import app.smallthingz.alldoc.BuildConfig
import app.smallthingz.alldoc.core.takeScalarSafe
import app.smallthingz.alldoc.state.LibraryCategory
import app.smallthingz.alldoc.state.LibraryDocument
import app.smallthingz.alldoc.state.StoredDocument
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderHome(
    onOpen: () -> Unit, modifier: Modifier = Modifier,
    recent: List<StoredDocument> = emptyList(), onRecent: (String) -> Unit = {},
    currentTitle: String? = null, onResume: () -> Unit = {}, currentHash: String? = null,
    previews: Map<String, Bitmap> = emptyMap(), onPreviewRequest: (String) -> Unit = {}, active: Boolean = true,
    currentPage: Int? = null, currentPageCount: Int? = null,
    library: LazyPagingItems<LibraryEntry>? = null, onLibrary: (LibraryDocument) -> Unit = { it.hash?.let(onRecent) },
    libraryPreviews: Map<Long, Bitmap> = emptyMap(), onLibraryVisible: (List<LibraryDocument>) -> Unit = {},
    onLibrarySelected: (Boolean) -> Unit = {}, onLibrarySettings: () -> Unit = {},
    onLibraryFilter: (String, LibraryCategory?) -> Unit = { _, _ -> },
    onRefresh: () -> Unit = {}, libraryScanning: Boolean = false, onTabSelected: () -> Unit = {},
    onExamples: () -> Unit = {}, coverPaper: Color = Color.White, currentAvailable: Boolean = true,
    onRecentVisible: (List<StoredDocument>) -> Unit = {},
    hiddenLibraryTypes: Set<LibraryCategory> = emptySet(),
    onHiddenLibraryTypesChange: (Set<LibraryCategory>) -> Unit = {}, libraryTypesEnabled: Boolean = true,
) {
    val pager = rememberPagerState { 2 }
    val pullRefresh = rememberPullToRefreshState()
    val tab = pager.currentPage
    val scope = rememberCoroutineScope()
    val dragged by pager.interactionSource.collectIsDraggedAsState()
    val tabSelected by rememberUpdatedState(onTabSelected)
    var query by rememberSaveable { mutableStateOf("") }
    val inputActive = active
    var compactSearch by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val recentScroll = rememberLazyListState()
    val libraryScroll = rememberLazyGridState()
    val recentActive = inputActive && tab == 0
    val libraryActive = inputActive && tab == 1
    LaunchedEffect(pager, inputActive) {
        if (!inputActive) {
            val destination = pager.targetPage
            pager.stopScroll(MutatePriority.PreventUserInput)
            // A retained tab must not return at a fractional page after cancellation.
            pager.scrollToPage(destination)
        }
    }
    LaunchedEffect(recentScroll, recentActive) {
        if (!recentActive) recentScroll.stopScroll(MutatePriority.PreventUserInput)
    }
    LaunchedEffect(libraryScroll, libraryActive) {
        if (!libraryActive) libraryScroll.stopScroll(MutatePriority.PreventUserInput)
    }
    val visibleLibrary by rememberUpdatedState(onLibraryVisible)
    val selectedLibrary by rememberUpdatedState(onLibrarySelected)
    val visibleRecent by rememberUpdatedState(onRecentVisible)
    val filterLibrary by rememberUpdatedState(onLibraryFilter)
    val controlsState = rememberTopAppBarState()
    val controls = TopAppBarDefaults.enterAlwaysScrollBehavior(state = controlsState, canScroll = {
        inputActive && tab == 1 && !pager.isScrollInProgress && (controlsState.heightOffset < 0f ||
            libraryScroll.canScrollForward || libraryScroll.canScrollBackward)
    })
    fun filter(text: String) {
        query = text.takeScalarSafe(128)
        libraryScroll.requestScrollToItem(0)
    }
    LaunchedEffect(dragged) {
        if (dragged && active) { focus.clearFocus(); keyboard?.hide(); tabSelected() }
    }
    LaunchedEffect(active, tab) {
        selectedLibrary(active && tab == 1)
    }
    LaunchedEffect(active, tab, query) {
        if (active && tab == 1) filterLibrary(query, null)
    }
    LaunchedEffect(active, pager) {
        if (!active) return@LaunchedEffect
        snapshotFlow { pager.settledPage to pager.isScrollInProgress }.collect { (settled, moving) ->
            // Reset only after Library is fully offscreen, never at a swipe's midpoint.
            if (!moving && settled == 0) controlsState.heightOffset = 0f
        }
    }
    LaunchedEffect(active, tab) {
        if (!active) return@LaunchedEffect
        snapshotFlow {
            pager.isScrollInProgress to if (tab == 0)
                Triple(recentScroll.isScrollInProgress, recentScroll.canScrollBackward, recentScroll.canScrollForward)
            else Triple(libraryScroll.isScrollInProgress, libraryScroll.canScrollBackward, libraryScroll.canScrollForward)
        }.collectLatest { (swiping, list) ->
            val (scrolling, backward, forward) = list
            if (scrolling) { focus.clearFocus(); keyboard?.hide() }
            // Fitting content has no accessibility scroll action to reveal a hidden header.
            else if (!swiping && !backward && !forward && controlsState.heightOffset < 0f) {
                animate(controlsState.heightOffset, 0f, animationSpec = tween(180)) { offset, _ ->
                    controlsState.heightOffset = offset
                }
            }
        }
    }
    LaunchedEffect(library, active, tab) {
        snapshotFlow {
            if (!active || tab != 1 || library == null) emptyList()
            else libraryScroll.layoutInfo.let { layout ->
                layout.visibleItemsInfo.mapNotNull { row ->
                    if (row.index !in 0 until library.itemCount || row.offset.y + row.size.height <= layout.viewportStartOffset || row.offset.y >= layout.viewportEndOffset)
                        null
                    else (library.peek(row.index) as? LibraryEntry.Document)?.value
                }.distinctBy { it.id }
            }
        }.collect { visibleLibrary(it) }
    }
    DisposableEffect(Unit) { onDispose { selectedLibrary(false); visibleLibrary(emptyList()); visibleRecent(emptyList()) } }
    val current = currentTitle != null && currentAvailable
    val first = remember(recent, currentHash, current) {
        if (current) recent.firstOrNull { it.hash == currentHash } else recent.firstOrNull()
    }
    val firstTitle = if (current) currentTitle else first?.title
    val firstHash = if (current) currentHash else first?.hash
    val remaining = remember(recent, firstHash) { recent.asSequence().filterNot { it.hash == firstHash }.take(5).toList() }
    LaunchedEffect(active, tab, first, remaining) {
        visibleRecent(if (active && tab == 0) listOfNotNull(first) + remaining else emptyList())
    }
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    BoxWithConstraints(modifier, contentAlignment = Alignment.TopCenter) {
        val compact = maxHeight < 240.dp * fontScale || maxWidth < 280.dp * fontScale
        Column(Modifier.widthIn(max = 1080.dp).fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                HorizontalPager(state = pager, beyondViewportPageCount = 1, userScrollEnabled = inputActive,
                    modifier = Modifier.fillMaxSize().clipToBounds().semantics { contentDescription = "Document tabs" }) { page ->
                    Column(Modifier.fillMaxSize().readerInput(tab == page || pager.isScrollInProgress)
                        .nestedScroll(controls.nestedScrollConnection)) {
                        if (page == 1) Box(Modifier.clipToBounds().layout { measurable, constraints ->
                            val placeable = measurable.measure(constraints)
                            val offset = controls.state.heightOffset.roundToInt()
                            layout(placeable.width, (placeable.height + offset).coerceAtLeast(0)) { placeable.placeRelative(0, offset) }
                        }) {
                            Column(Modifier.onSizeChanged {
                                controls.state.heightOffsetLimit = -it.height.toFloat()
                                controls.state.heightOffset = controls.state.heightOffset.coerceIn(-it.height.toFloat(), 0f)
                            }) {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    // Keep the field at the same composition location across an IME/size change.
                                    // Compact search and format choices share one row, so both retain 48 dp targets.
                                    Box(Modifier.weight(1f)) {
                                        androidx.compose.animation.AnimatedVisibility(visible = !compact || compactSearch,
                                            modifier = Modifier.readerInput(active && tab == 1 && (!compact || compactSearch)),
                                            enter = fadeIn(tween(120)), exit = fadeOut(tween(120))) {
                                            OutlinedTextField(query, onValueChange = { filter(it) }, singleLine = true,
                                                modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) compactSearch = true },
                                                placeholder = { Text("Find documents") },
                                                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                                                trailingIcon = if (query.isEmpty()) null else { {
                                                    ReaderAction(Icons.Outlined.Close, "Clear search", onClick = { filter("") })
                                                } })
                                        }
                                        androidx.compose.animation.AnimatedVisibility(visible = compact && !compactSearch,
                                            modifier = Modifier.readerInput(active && tab == 1 && compact && !compactSearch),
                                            enter = fadeIn(tween(120)), exit = fadeOut(tween(120))) {
                                            LibraryFormatFilters(hiddenLibraryTypes, onChange = {
                                                libraryScroll.requestScrollToItem(0)
                                                onHiddenLibraryTypesChange(it)
                                            }, enabled = active && tab == 1 && !compactSearch && libraryTypesEnabled)
                                        }
                                    }
                                    if (compact) ReaderAction(if (compactSearch) Icons.Outlined.Close else Icons.Outlined.Search,
                                        if (compactSearch) "Close library search" else "Find documents", onClick = {
                                            compactSearch = !compactSearch
                                            if (!compactSearch) { focus.clearFocus(); keyboard?.hide() }
                                        })
                                }
                                if (!compact) Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                    LibraryFormatFilters(hiddenLibraryTypes, onChange = {
                                        libraryScroll.requestScrollToItem(0)
                                        onHiddenLibraryTypesChange(it)
                                    }, enabled = active && tab == 1 && libraryTypesEnabled)
                                }
                                Box(Modifier.fillMaxWidth().height(3.dp)) {
                                    if (active && tab == 1) {
                                        if (libraryScanning) LinearProgressIndicator(Modifier.fillMaxSize()
                                            .semantics { contentDescription = "Updating library" })
                                        else if (pullRefresh.distanceFraction > 0f) LinearProgressIndicator(
                                            progress = { pullRefresh.distanceFraction.coerceIn(0f, 1f) },
                                            modifier = Modifier.fillMaxSize().semantics { contentDescription = "Pull to refresh" },
                                            drawStopIndicator = {})
                                    }
                                }
                            }
                        }
                        val padding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 88.dp)
                        if (page == 0) {
                            LazyColumn(state = recentScroll, userScrollEnabled = recentActive, modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = padding,
                                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (firstTitle != null) item(key = firstHash ?: "current") {
                                    LaunchedEffect(firstHash, active, tab) { if (active && tab == 0) firstHash?.let(onPreviewRequest) }
                                    DocumentRow(firstTitle, documentSummary(first?.metadata?.sizeBytes),
                                        firstHash?.let(previews::get), featured = current,
                                        page = currentPage, pageCount = currentPageCount, coverPaper = coverPaper, modifier = Modifier.animateItem()) {
                                        if (current) onResume() else firstHash?.let(onRecent)
                                    }
                                }
                                items(remaining, key = { it.hash }) { item ->
                                    LaunchedEffect(item.hash, active, tab) { if (active && tab == 0) onPreviewRequest(item.hash) }
                                    DocumentRow(item.title, documentSummary(item.metadata.sizeBytes), previews[item.hash],
                                        coverPaper = coverPaper, modifier = Modifier.animateItem()) { onRecent(item.hash) }
                                }
                                if (BuildConfig.DEBUG) item(key = "debug-examples") {
                                    Row(Modifier.fillMaxWidth().animateItem()) {
                                        ReaderAction(Icons.Outlined.CollectionsBookmark, "Examples", onClick = onExamples)
                                    }
                                }
                            }
                        } else PullToRefreshBox(state = pullRefresh, indicator = {},
                            isRefreshing = active && tab == 1 && libraryScanning, onRefresh = {
                            if (libraryActive) { library?.refresh(); onRefresh() }
                        }, modifier = Modifier.weight(1f).fillMaxWidth()) {
                            val refresh = library?.loadState?.refresh
                            LazyVerticalGrid(columns = GridCells.Fixed(3), state = libraryScroll, userScrollEnabled = libraryActive,
                                modifier = Modifier.fillMaxSize(), contentPadding = padding,
                                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                if (library != null && library.itemCount > 0) {
                                    items(library.itemCount, key = library.itemKey { it.key },
                                        span = { index -> if (library.peek(index) is LibraryEntry.Header) GridItemSpan(maxLineSpan) else GridItemSpan(1) },
                                        contentType = { index -> if (library.peek(index) is LibraryEntry.Header) "heading" else "cover" }) { index ->
                                        when (val entry = if (active && tab == 1) library[index] else library.peek(index)) {
                                            is LibraryEntry.Header -> Text(entry.category.label, Modifier.fillMaxWidth().semantics { heading() },
                                                style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            else -> {
                                                val item = (entry as? LibraryEntry.Document)?.value
                                                val featured = currentHash != null && item?.hash == currentHash && current
                                                LibraryCover(item?.title, item?.id?.let(libraryPreviews::get), featured, coverPaper,
                                                    modifier = Modifier.animateItem(), onClick = { item?.let(onLibrary) })
                                            }
                                        }
                                    }
                                } else item(span = { GridItemSpan(maxLineSpan) }) {
                                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                        when {
                                            refresh is LoadState.Loading || libraryScanning -> if (active && tab == 1 && !libraryScanning)
                                                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                                            refresh is LoadState.Error -> Text("Library unavailable", style = MaterialTheme.typography.bodyMedium)
                                            query.isNotBlank() || hiddenLibraryTypes.isNotEmpty() -> Text("No matches", style = MaterialTheme.typography.bodyMedium)
                                            else -> TextButton(onClick = onLibrarySettings) { Text("Library settings") }
                                        }
                                    }
                                }
                                if (library?.loadState?.append is LoadState.Loading && active && tab == 1) item(span = { GridItemSpan(maxLineSpan) }) {
                                    Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                                    }
                                }
                                if (refresh is LoadState.Error || library?.loadState?.append is LoadState.Error) item(span = { GridItemSpan(maxLineSpan) }) {
                                    TextButton(onClick = { library.retry() }) { Text("Retry") }
                                }
                            }
                            if (library?.loadState?.prepend is LoadState.Error) FilledTonalButton(
                                onClick = { library.retry() }, modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp)
                                    .semantics { contentDescription = "Retry earlier documents" }) { Text("Retry") }
                        }
                    }
                }
                val actionsVisible = tab == 0 || !compact || !compactSearch
                androidx.compose.animation.AnimatedVisibility(actionsVisible, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).readerInput(actionsVisible),
                    enter = fadeIn(tween(150)) + scaleIn(tween(180)), exit = fadeOut(tween(120)) + scaleOut(tween(180))) {
                    ReaderTooltip("Open") {
                        FloatingActionButton(onClick = onOpen, modifier = Modifier.semantics { contentDescription = "Open" }) {
                            Icon(Icons.Outlined.Add, null)
                        }
                    }
                }
            }
            HomeNavigation(tab) { destination ->
                if (inputActive) {
                    focus.clearFocus(); keyboard?.hide(); tabSelected()
                    scope.launch { pager.animateScrollToPage(destination) }
                }
            }
        }
    }
}

@Composable
private fun HomeNavigation(selected: Int, onSelect: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val tabs = remember { listOf("Recents" to Icons.Outlined.History, "Library" to Icons.Outlined.FolderOpen) }
    val style = MaterialTheme.typography.labelMedium
    val text = rememberTextMeasurer(cacheSize = 2)
    val density = LocalDensity.current
    Surface(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
        shape = RoundedCornerShape(20.dp), color = colors.surfaceContainerLow,
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = .4f))) {
        Row(Modifier.padding(4.dp).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            tabs.forEachIndexed { index, (label, icon) ->
                val chosen = selected == index
                val fill by animateColorAsState(if (chosen) colors.secondaryContainer else Color.Transparent,
                    tween(180), label = "Home tab")
                BoxWithConstraints(Modifier.weight(1f)) {
                    val showLabel = text.measure(label, style = style, maxLines = 1, softWrap = false).size.width <=
                        with(density) { (maxWidth - 46.dp).roundToPx() }
                    ReaderTooltip(label, Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(16.dp))
                            .background(fill).selectable(chosen, role = Role.Tab, onClick = { onSelect(index) })
                            .semantics { if (!showLabel) contentDescription = label }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                            Icon(icon, null, Modifier.size(20.dp), tint = if (chosen) colors.onSecondaryContainer else colors.onSurfaceVariant)
                            if (showLabel) {
                                Spacer(Modifier.width(6.dp))
                                Text(label, style = style,
                                    color = if (chosen) colors.onSecondaryContainer else colors.onSurfaceVariant,
                                    maxLines = 1, softWrap = false)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryCover(title: String?, bitmap: Bitmap?, current: Boolean, paper: Color, modifier: Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = title != null, color = Color.Transparent,
        shape = RoundedCornerShape(6.dp), modifier = modifier.fillMaxWidth().semantics {
            if (current) stateDescription = "Continue reading"
        }) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DocumentCover(bitmap, Modifier.fillMaxWidth().aspectRatio(.75f), paper)
            if (title != null) Text(title, Modifier.padding(horizontal = 2.dp), style = MaterialTheme.typography.bodyMedium,
                maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal)
        }
    }
}

@Composable
private fun DocumentCover(bitmap: Bitmap?, modifier: Modifier, paper: Color) {
    val colors = MaterialTheme.colorScheme
    val image = remember(bitmap) { bitmap?.asImageBitmap() }
    Box(modifier.clip(RoundedCornerShape(3.dp)).background(if (image == null) colors.surfaceContainerLowest else paper)
        .border(1.dp, colors.onSurface.copy(alpha = .08f), RoundedCornerShape(3.dp)), contentAlignment = Alignment.Center) {
        if (image != null) Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        else Icon(Icons.Outlined.Description, null, Modifier.size(28.dp), tint = colors.onSurfaceVariant)
    }
}

private fun documentSummary(bytes: Long?) = bytes?.takeIf { it > 0 }?.let(::readerBytes).orEmpty()

@Composable
internal fun DocumentRow(title: String, subtitle: String, bitmap: Bitmap?, featured: Boolean = false,
    page: Int? = null, pageCount: Int? = null, coverPaper: Color = Color.White, modifier: Modifier = Modifier, onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val progress = if (featured && page != null && pageCount != null && pageCount > 1 && page in 0 until pageCount)
        (page + 1).toFloat() / pageCount else null
    Surface(onClick = onClick, modifier = modifier.fillMaxWidth().semantics {
        if (featured) stateDescription = "Continue reading"
    }, shape = RoundedCornerShape(8.dp),
        color = Color.Transparent) {
        Box {
            Row(Modifier.fillMaxWidth().heightIn(min = 84.dp).padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DocumentCover(bitmap, Modifier.size(48.dp, 64.dp), coverPaper)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (featured) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (subtitle.isNotEmpty() || progress != null) Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(subtitle, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (progress != null) Text("${requireNotNull(page) + 1} / $pageCount",
                            style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    }
                }
            }
            if (featured) Box(Modifier.align(Alignment.CenterStart).width(2.dp).height(44.dp).background(colors.primary))
            if (progress != null) LinearProgressIndicator(progress = { progress },
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 72.dp, end = 12.dp, bottom = 4.dp)
                    .fillMaxWidth().height(2.dp),
                color = colors.primary, trackColor = colors.primary.copy(alpha = .12f), drawStopIndicator = {})
        }
    }
}
