package org.smallthingz.alldoc

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.smallthingz.alldoc.core.DocumentEngine

internal enum class PresentationMode {
    Pages,
    Slides,
}

internal fun Modifier.documentPinch(onTransform: (Float, Offset) -> Unit): Modifier =
    pointerInput(onTransform) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var transformed = false
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.count { it.pressed } >= 2) {
                    val zoom = event.calculateZoom()
                    if (zoom.isFinite() && zoom > 0) onTransform(zoom, event.calculatePan())
                    event.changes.forEach { it.consume() }
                    transformed = true
                } else if (transformed) event.changes.forEach { it.consume() }
            } while (event.changes.any { it.pressed })
        }
    }

@Composable
internal fun PresentationCanvas(
    session: DocumentEngine.Session,
    edits: Map<String, String>,
    mode: PresentationMode,
    selected: Int,
    onSelect: (Int) -> Unit,
    onWarnings: (List<String>) -> Unit,
    navigationRequest: Int = 0,
    modifier: Modifier = Modifier,
) {
    val rasters = remember(session) { SlideRasterStore() }
    DisposableEffect(rasters) { onDispose { rasters.close() } }
    val slides = session.slides
    if (slides.isEmpty()) return
    var zoom by remember(session) { mutableFloatStateOf(1f) }
    val list =
        rememberLazyListState(initialFirstVisibleItemIndex = selected.coerceIn(0, slides.lastIndex))
    val pager =
        rememberPagerState(initialPage = selected.coerceIn(0, slides.lastIndex)) { slides.size }
    val horizontal = rememberScrollState()
    val scope = rememberCoroutineScope()
    val interaction = LocalDocumentInteraction.current
    RegisterDocumentScroll(list)
    RegisterDocumentScroll(horizontal)
    RegisterDocumentMotion(pager) { pager.isScrollInProgress }
    var changingMode by remember { mutableStateOf(false) }
    LaunchedEffect(mode, navigationRequest) {
        changingMode = true
        if (mode == PresentationMode.Pages)
            list.scrollToItem(selected.coerceIn(0, slides.lastIndex))
        else pager.scrollToPage(selected.coerceIn(0, slides.lastIndex))
        changingMode = false
    }
    LaunchedEffect(mode, list, pager) {
        snapshotFlow {
            (if (mode == PresentationMode.Pages) list.firstVisibleItemIndex
            else pager.currentPage) to changingMode
        }
            .distinctUntilChanged()
            .collect { (page, navigating) -> if (!navigating) onSelect(page) }
    }
    BoxWithConstraints(
        modifier
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .testTag("Presentation viewport")
    ) {
        val viewportWidth = maxWidth
        val viewportHeight = maxHeight
        val baseWidth = viewportWidth
        val transform: (Float, Offset) -> Unit =
            remember(mode, list, horizontal) {
                { factor, pan ->
                    interaction?.nativeMotion()
                    zoom = (zoom * factor).coerceIn(1f, 4f)
                    scope.launch {
                        horizontal.scrollBy(-pan.x)
                        if (mode == PresentationMode.Pages) list.scrollBy(-pan.y)
                    }
                }
            }
        if (mode == PresentationMode.Pages) {
            LazyColumn(
                state = list,
                modifier =
                    Modifier.fillMaxSize().documentPinch(transform).horizontalScroll(horizontal),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                itemsIndexed(slides, key = { _, slide -> slide.id }) { index, slide ->
                    val ratio =
                        if (safeSlideCanvas(slide.width, slide.height))
                            (slide.height / slide.width).toFloat()
                        else .5625f
                    ImmersiveSlidePage(
                        session,
                        edits,
                        index,
                        rasters,
                        { if (index == selected) onWarnings(it) },
                        Modifier.width(baseWidth * zoom)
                            .height(baseWidth * zoom * ratio)
                            .testTag("Slide page ${index+1}"),
                    )
                }
            }
        } else
            HorizontalPager(
                state = pager,
                userScrollEnabled = zoom <= 1.001f,
                modifier = Modifier.fillMaxSize().documentPinch(transform),
            ) { index ->
                val slide = slides[index]
                val ratio =
                    if (safeSlideCanvas(slide.width, slide.height))
                        (slide.height / slide.width).toFloat()
                    else .5625f
                val fittedWidth = minOf(viewportWidth, viewportHeight / ratio)
                val vertical = rememberScrollState()
                val pageHorizontal = rememberScrollState()
                RegisterDocumentScroll(vertical)
                RegisterDocumentScroll(pageHorizontal)
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier.fillMaxSize()
                            .verticalScroll(vertical)
                            .horizontalScroll(pageHorizontal),
                        contentAlignment = Alignment.Center,
                    ) {
                        ImmersiveSlidePage(
                            session,
                            edits,
                            index,
                            rasters,
                            { if (index == selected) onWarnings(it) },
                            Modifier.width(fittedWidth * zoom)
                                .height(fittedWidth * zoom * ratio)
                                .testTag("Slide page ${index+1}"),
                        )
                    }
                }
            }
    }
}
