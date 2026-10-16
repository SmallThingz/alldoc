package app.smallthingz.alldoc.ui

import android.graphics.Bitmap
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
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemKey
import app.smallthingz.alldoc.core.takeScalarSafe
import app.smallthingz.alldoc.state.LibraryCategory
import app.smallthingz.alldoc.state.LibraryDocument
import app.smallthingz.alldoc.state.StoredDocument
import kotlin.math.roundToInt

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
    onExamples: () -> Unit = {}, coverPaper: Color = Color.White,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var categoryName by rememberSaveable { mutableStateOf<String?>(null) }
    val category = categoryName?.let { name -> LibraryCategory.available.firstOrNull { it.name == name } }
    var filterOpen by remember { mutableStateOf(false) }
    var compactSearch by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val recentScroll = rememberLazyListState()
    val libraryScroll = rememberLazyGridState()
    val visibleLibrary by rememberUpdatedState(onLibraryVisible)
    val selectedLibrary by rememberUpdatedState(onLibrarySelected)
    val filterLibrary by rememberUpdatedState(onLibraryFilter)
    val controlsState = rememberTopAppBarState()
    val controls = TopAppBarDefaults.enterAlwaysScrollBehavior(state = controlsState, canScroll = {
        active && (controlsState.heightOffset < 0f || if (tab == 0)
            recentScroll.canScrollForward || recentScroll.canScrollBackward
        else libraryScroll.canScrollForward || libraryScroll.canScrollBackward)
    })
    fun filter(text: String = query, type: LibraryCategory? = category) {
        query = text.takeScalarSafe(128)
        categoryName = type?.name
        libraryScroll.requestScrollToItem(0)
    }
    LaunchedEffect(active, tab) {
        if (!active) filterOpen = false
        selectedLibrary(active && tab == 1)
    }
    LaunchedEffect(active, tab, query, category) {
        if (active && tab == 1) filterLibrary(query, category)
    }
    LaunchedEffect(tab) { controls.state.heightOffset = 0f }
    LaunchedEffect(active, tab) {
        if (!active) return@LaunchedEffect
        snapshotFlow {
            if (tab == 0) Triple(recentScroll.isScrollInProgress, recentScroll.canScrollBackward, recentScroll.canScrollForward)
            else Triple(libraryScroll.isScrollInProgress, libraryScroll.canScrollBackward, libraryScroll.canScrollForward)
        }.collect { (scrolling, backward, forward) ->
            if (scrolling) { focus.clearFocus(); keyboard?.hide() }
            // Fitting content has no accessibility scroll action to reveal a hidden header.
            else if (!backward && !forward) controlsState.heightOffset = 0f
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
    DisposableEffect(Unit) { onDispose { selectedLibrary(false); visibleLibrary(emptyList()) } }
    val current = currentTitle != null
    val first = remember(recent, currentHash, current) {
        if (current) recent.firstOrNull { it.hash == currentHash } else recent.firstOrNull()
    }
    val firstTitle = currentTitle ?: first?.title
    val firstHash = if (current) currentHash else first?.hash
    val remaining = remember(recent, firstHash) { recent.asSequence().filterNot { it.hash == firstHash }.take(5).toList() }
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    BoxWithConstraints(modifier, contentAlignment = Alignment.TopCenter) {
        val compact = maxHeight < 240.dp * fontScale || maxWidth < 280.dp * fontScale
        Column(Modifier.widthIn(max = 1080.dp).fillMaxSize().nestedScroll(controls.nestedScrollConnection)) {
            Box(Modifier.clipToBounds().layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                val offset = controls.state.heightOffset.roundToInt()
                layout(placeable.width, (placeable.height + offset).coerceAtLeast(0)) { placeable.placeRelative(0, offset) }
            }) {
                Column(Modifier.onSizeChanged {
                    controls.state.heightOffsetLimit = -it.height.toFloat()
                    controls.state.heightOffset = controls.state.heightOffset.coerceIn(-it.height.toFloat(), 0f)
                }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SecondaryTabRow(selectedTabIndex = tab, modifier = Modifier.weight(1f), containerColor = Color.Transparent) {
                            Tab(selected = tab == 0, onClick = { focus.clearFocus(); onTabSelected(); tab = 0 }, text = { Text("Recents", maxLines = 1, overflow = TextOverflow.Ellipsis) })
                            Tab(selected = tab == 1, onClick = { focus.clearFocus(); onTabSelected(); tab = 1 }, text = { Text("Library", maxLines = 1, overflow = TextOverflow.Ellipsis) })
                        }
                        if (tab == 1 && compact) ReaderAction(if (compactSearch) Icons.Outlined.Close else Icons.Outlined.Search,
                            if (compactSearch) "Close library search" else "Find documents", onClick = {
                                compactSearch = !compactSearch
                                if (!compactSearch) { focus.clearFocus() }
                            })
                        ReaderAction(Icons.Outlined.CollectionsBookmark, "Examples", onClick = onExamples)
                        ReaderAction(Icons.Outlined.FolderOpen, "Open", onClick = onOpen)
                    }
                    if (tab == 1 && (!compact || compactSearch)) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(query, onValueChange = { filter(it) }, singleLine = true,
                            modifier = Modifier.weight(1f).onFocusChanged { if (it.isFocused) compactSearch = true },
                            placeholder = { Text("Find documents") },
                            leadingIcon = { Icon(Icons.Outlined.Search, null) },
                            trailingIcon = if (query.isEmpty()) null else { {
                                ReaderAction(Icons.Outlined.Close, "Clear search", onClick = { filter("") })
                            } })
                        Box {
                            ReaderTooltip("Document type") {
                                IconToggleButton(checked = category != null, onCheckedChange = { filterOpen = true },
                                    modifier = Modifier.semantics { contentDescription = "Document type"; stateDescription = category?.label ?: "All types" }) {
                                    Icon(Icons.Outlined.FilterList, null)
                                }
                            }
                            if (active) DropdownMenu(filterOpen, onDismissRequest = { filterOpen = false }) {
                                DropdownMenuItem(text = { Text("All types") }, onClick = { filter(type = null); filterOpen = false })
                                LibraryCategory.available.forEach { type ->
                                    DropdownMenuItem(text = { Text(type.label) }, onClick = { filter(type = type); filterOpen = false })
                                }
                            }
                        }
                    }
                }
            }
            val padding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 16.dp)
            if (tab == 0) {
                LazyColumn(state = recentScroll, modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = padding,
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (firstTitle != null) item(key = "current") {
                        LaunchedEffect(firstHash, active) { if (active) firstHash?.let(onPreviewRequest) }
                        DocumentRow(firstTitle, documentSummary(first?.metadata?.sizeBytes),
                            firstHash?.let(previews::get), featured = current,
                            page = currentPage, pageCount = currentPageCount, coverPaper = coverPaper) {
                            if (current) onResume() else firstHash?.let(onRecent)
                        }
                    }
                    items(remaining, key = { it.hash }) { item ->
                        LaunchedEffect(item.hash, active) { if (active) onPreviewRequest(item.hash) }
                        DocumentRow(item.title, documentSummary(item.metadata.sizeBytes), previews[item.hash],
                            coverPaper = coverPaper) { onRecent(item.hash) }
                    }
                }
            } else PullToRefreshBox(isRefreshing = active && libraryScanning, onRefresh = {
                library?.refresh(); onRefresh()
            }, modifier = Modifier.weight(1f).fillMaxWidth()) {
                val refresh = library?.loadState?.refresh
                LazyVerticalGrid(columns = GridCells.Fixed(3), state = libraryScroll,
                    modifier = Modifier.fillMaxSize(), contentPadding = padding,
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (library != null && library.itemCount > 0) {
                        items(library.itemCount, key = library.itemKey { it.key },
                            span = { index -> if (library.peek(index) is LibraryEntry.Header) GridItemSpan(maxLineSpan) else GridItemSpan(1) },
                            contentType = { index -> if (library.peek(index) is LibraryEntry.Header) "heading" else "cover" }) { index ->
                            when (val entry = if (active) library[index] else library.peek(index)) {
                                is LibraryEntry.Header -> Text(entry.category.label, Modifier.fillMaxWidth().semantics { heading() },
                                    style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                else -> {
                                    val item = (entry as? LibraryEntry.Document)?.value
                                    val featured = currentHash != null && item?.hash == currentHash && current
                                    LibraryCover(item?.title, item?.id?.let(libraryPreviews::get), featured, coverPaper,
                                        onClick = { item?.let(onLibrary) })
                                }
                            }
                        }
                    } else item(span = { GridItemSpan(maxLineSpan) }) {
                        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            when {
                                refresh is LoadState.Loading || libraryScanning -> if (!libraryScanning)
                                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                                refresh is LoadState.Error -> Text("Library unavailable", style = MaterialTheme.typography.bodyMedium)
                                query.isNotBlank() || category != null -> Text("No matches", style = MaterialTheme.typography.bodyMedium)
                                else -> TextButton(onClick = onLibrarySettings) { Text("Library settings") }
                            }
                        }
                    }
                    if (library?.loadState?.append is LoadState.Loading && active) item(span = { GridItemSpan(maxLineSpan) }) {
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
}

@Composable
private fun LibraryCover(title: String?, bitmap: Bitmap?, current: Boolean, paper: Color, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = title != null, color = Color.Transparent,
        shape = RoundedCornerShape(6.dp), modifier = Modifier.fillMaxWidth().semantics {
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
    page: Int? = null, pageCount: Int? = null, coverPaper: Color = Color.White, onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val progress = if (featured && page != null && pageCount != null && pageCount > 1 && page in 0 until pageCount)
        (page + 1).toFloat() / pageCount else null
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth().semantics {
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
