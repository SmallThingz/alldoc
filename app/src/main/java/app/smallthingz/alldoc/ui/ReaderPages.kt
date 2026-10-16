package app.smallthingz.alldoc.ui

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.snapping.SnapLayoutInfoProvider
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import app.smallthingz.alldoc.state.ViewportState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/** Published bitmaps remain owned by the render pool; drawing never copies or recycles them. */
internal data class ReaderPageImage(val bitmap: Bitmap, val crop: RectF? = null, val renderWidth: Int = bitmap.width)

internal data class PageAnchor(val page: Int, val fraction: Float)
private data class PageScroll(val page: Int, val offset: Int, val height: Int, val scrolling: Boolean,
    val ready: Boolean, val placement: Int)
private data class TransformPage(val index: Int, val offset: Float, val extent: Float)
private class PageGesture(var pose: ViewportState, var page: TransformPage? = null)
private data class PageLayout(val width: Float, val height: Float, val pose: ViewportState, val horizontal: Boolean, val leading: Float)

/** Continuous vertical pages or viewport-wide presentation slots; only visible pages render. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ReaderPages(
    documentKey: Long,
    pageCount: Int,
    page: Int,
    fraction: Float,
    viewport: ViewportState,
    positionRevision: Long,
    images: Map<Int, ReaderPageImage>,
    aspects: Map<Int, Float>,
    onVisiblePages: (IntRange) -> Unit,
    onPosition: (Int, Float) -> Unit,
    onViewportChange: (ViewportState) -> Unit,
    onGestureEnd: () -> Unit,
    modifier: Modifier = Modifier,
    paper: Color = Color.White,
    horizontal: Boolean = false,
    failedPages: Set<Int> = emptySet(),
    onRetryPage: (Int) -> Unit = {},
    onReadText: (() -> Unit)? = null,
    onCurrentPage: (Int) -> Unit = {},
    onScrollDirection: (Boolean) -> Unit = {},
    onTap: () -> Unit = {},
    normalEdgePadding: Dp = 16.dp,
) {
    if (pageCount <= 0) return
    val currentPose by rememberUpdatedState(viewport.normalized())
    val gesture = remember(documentKey) { PageGesture(viewport.normalized()) }
    SideEffect {
        if (gesture.pose != currentPose) gesture.page = null
        gesture.pose = currentPose
    }
    val position by rememberUpdatedState(onPosition)
    val updatePose by rememberUpdatedState(onViewportChange)
    val requestPages by rememberUpdatedState(onVisiblePages)
    val finish by rememberUpdatedState(onGestureEnd)
    val currentPage by rememberUpdatedState(onCurrentPage)
    val scrollDirection by rememberUpdatedState(onScrollDirection)
    val tap by rememberUpdatedState(onTap)
    val ratios by rememberUpdatedState(aspects)
    val count by rememberUpdatedState(pageCount)
    val scope = rememberCoroutineScope()
    var settling by remember(documentKey) { mutableStateOf<Job?>(null) }
    val list = remember(documentKey) { LazyListState(firstVisibleItemIndex = page.coerceIn(0, pageCount - 1)) }
    var anchor by remember(documentKey) { mutableStateOf(PageAnchor(page.coerceIn(0, pageCount - 1), fraction.initialPageFraction())) }
    var appliedRevision by remember(documentKey) { mutableStateOf<Long?>(null) }
    var ready by remember(documentKey) { mutableStateOf(false) }
    var placement by remember(documentKey) { mutableIntStateOf(0) }
    var requestedZoom by remember(documentKey) { mutableStateOf<Float?>(null) }
    var transforming by remember(documentKey) { mutableStateOf(false) }
    BoxWithConstraints(modifier.readerBackground(color = MaterialTheme.colorScheme.surfaceContainerLowest).clipToBounds()) {
        val density = LocalDensity.current
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val gap = with(density) { 8.dp.roundToPx() }
        val breathing = with(density) { 16.dp.toPx() }.coerceAtMost(height / 8f)
        val imageHeight = if (horizontal) (height - 2f * breathing).coerceAtLeast(1f) else height
        val leading = if (horizontal) 0f else with(density) { normalEdgePadding.toPx() }
            .coerceIn(0f, height / 3f).roundToInt().toFloat()
        val usable = width > 0 && constraints.maxHeight > 0
        var previousLayout by remember(documentKey) { mutableStateOf<PageLayout?>(null) }
        fun rowLength(index: Int, pose: ViewportState = currentPose): Float =
            pageRowLength(width, imageHeight, ratios[index] ?: DefaultPageAspect, pose, horizontal)
        suspend fun place(target: PageAnchor, pose: ViewportState = currentPose, complete: () -> Unit = {}) {
            val revision = ++placement
            ready = false
            try {
                val extent = rowLength(target.page, pose)
                val requested = (target.fraction.coerceAtLeast(0f) * (extent + gap)).roundToInt()
                val offset = pageScrollOffset(requested, extent, width, horizontal)
                list.scrollToItem(target.page, offset)
                if (revision == placement) {
                    complete()
                    if (horizontal && (target.fraction >= 0f || pose.zoom == 1f)) {
                        anchor = PageAnchor(target.page, offset / (extent + gap))
                        position(anchor.page, anchor.fraction)
                    }
                }
            } finally {
                if (revision == placement) ready = true
            }
        }
        fun presentationOffset(velocity: Float = 0f): Float {
            val index = list.firstVisibleItemIndex
            val row = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return 0f
            return presentationSnapOffset(list.firstVisibleItemScrollOffset, row.size, width.roundToInt(), gap,
                index < count - 1, velocity, with(density) { 400.dp.toPx() })
        }
        suspend fun snapPresentation(velocity: Float = 0f) {
            if (horizontal) presentationOffset(velocity).takeIf { it != 0f }?.let { list.scrollBy(it) }
        }
        fun capture(): PageAnchor? {
            if (!ready || !usable) return null
            val index = list.firstVisibleItemIndex.coerceIn(0, count - 1)
            val row = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return null
            return PageAnchor(index, (list.firstVisibleItemScrollOffset.toFloat() / (row.size + gap).coerceAtLeast(1)).pageFraction())
                .also { anchor = it; position(it.page, it.fraction) }
        }
        fun transform(focus: Offset, pan: Offset, change: Float) {
            if (!usable) return
            val before = gesture.pose
            val factor = change.takeIf { it.isFinite() && it > 0f } ?: 1f
            val zoom = (before.zoom * factor).coerceIn(1f, 8f)
            val axis = if (horizontal) height else width
            val crossFocus = if (horizontal) focus.y else focus.x
            val crossPan = if (horizontal) pan.y else pan.x
            val focusAxis = if (horizontal) focus.x else focus.y
            val panAxis = if (horizontal) pan.x else pan.y
            val row = gesture.page ?: pageAtProbe(list.layoutInfo.visibleItemsInfo, focusAxis - leading,
                { it.offset.toFloat() }, { it.size.toFloat() })?.let {
                TransformPage(it.index, it.offset + leading, it.size.toFloat())
            }
            val ratio = row?.let { ratios[it.index] } ?: DefaultPageAspect
            val rotatedRatio = if (before.rotation % 2 == 0) ratio else 1f / ratio
            val crossExtent = if (horizontal) min(imageHeight, width * rotatedRatio) else width
            val next = pagePan(before.copy(zoom = zoom), axis, crossFocus, crossPan, before.zoom, horizontal, crossExtent)
            if (next == before && panAxis == 0f) return
            if (anchor.fraction < 0f && row != null) {
                capture() // Effective input replaces the pending legacy restore, even before a bitmap arrives.
            }
            if (zoom != before.zoom || panAxis != 0f) {
                if (row != null) {
                    val oldHeight = row.extent.coerceAtLeast(1f)
                    val oldImage = if (horizontal) pageImageLength(width, imageHeight, ratio, before, true) else oldHeight
                    val local = pageAlongFraction((focusAxis - row.offset) / oldHeight, oldHeight, oldImage)
                    val newHeight = rowLength(row.index, next)
                    val newImage = if (horizontal) pageImageLength(width, imageHeight, ratio, next, true) else newHeight
                    val requested = if (horizontal && next.zoom == 1f) 0 else
                        (pageSlotFraction(local, newHeight, newImage) * newHeight - focusAxis - panAxis + leading).roundToInt()
                    val offset = pageScrollOffset(requested, newHeight, width, horizontal, row.index == 0)
                    gesture.page = TransformPage(row.index, leading - offset, newHeight)
                    list.requestScrollToItem(row.index, offset)
                    anchor = PageAnchor(row.index, (offset.toFloat() / (newHeight + gap)).pageFraction())
                    position(anchor.page, anchor.fraction)
                }
            }
            if (zoom != before.zoom) requestedZoom = zoom
            gesture.pose = next
            updatePose(next)
        }
        val currentCapture by rememberUpdatedState(::capture)
        val currentTransform by rememberUpdatedState(::transform)
        fun settle() {
            settling?.cancel()
            settling = scope.launch {
                // Frame callbacks precede layout; capture after the requested remeasure completed.
                repeat(2) { withFrameNanos { } }
                gesture.page = null
                snapPresentation()
                if (anchor.fraction >= 0f) currentCapture()
                finish()
            }
        }
        val settleTransform by rememberUpdatedState(::settle)
        val restoredPosition = remember(documentKey, positionRevision) {
            PageAnchor(page.coerceIn(0, pageCount - 1), fraction.initialPageFraction())
        }
        // Relayout never saves a clamped temporary position over the document anchor.
        LaunchedEffect(documentKey, positionRevision, width, height, leading, viewport.rotation, horizontal, usable) {
            if (!usable) return@LaunchedEffect
            var target = if (appliedRevision != positionRevision) restoredPosition else anchor
            val old = previousLayout
            val transformed = ready && old != null && (old.pose.rotation != viewport.rotation || old.horizontal != horizontal) &&
                appliedRevision == positionRevision && target.fraction >= 0f
            var nextPose = currentPose
            if (transformed) {
                val oldBreathing = with(density) { 16.dp.toPx() }.coerceAtMost(old.height / 8f)
                val oldImageHeight = if (old.horizontal) (old.height - 2f * oldBreathing).coerceAtLeast(1f) else old.height
                val oldViewport = if (old.horizontal) old.width else old.height - 2f * old.leading
                val changingMode = old.horizontal != horizontal
                val oldProbe = if (changingMode) (if (old.horizontal) old.width else old.height) / 3f - old.leading else oldViewport / 2f
                val focal = pageFocalAnchor(target.page, target.fraction, oldViewport, count, gap.toFloat(), probeOffset = oldProbe) {
                    pageRowLength(old.width, oldImageHeight, ratios[it] ?: DefaultPageAspect, old.pose, old.horizontal)
                }
                val ratio = ratios[focal.page] ?: DefaultPageAspect
                val axis = if (old.horizontal) old.height else old.width
                val rotatedRatio = if (old.pose.rotation % 2 == 0) ratio else 1f / ratio
                val base = if (old.horizontal) min(oldImageHeight, old.width * rotatedRatio) else old.width
                val visible = pagePan(currentPose.copy(rotation = old.pose.rotation, zoom = old.pose.zoom), axis, axis / 2f, 0f,
                    horizontal = old.horizontal, baseExtent = base)
                val focalFraction = if (old.horizontal) pageAlongFraction(focal.fraction,
                    pageRowLength(old.width, oldImageHeight, ratio, old.pose, true),
                    pageImageLength(old.width, oldImageHeight, ratio, old.pose, true)) else focal.fraction
                nextPose = pageFocalPose(visible.copy(rotation = viewport.rotation, zoom = viewport.zoom), old.pose.rotation, focalFraction, old.horizontal)
                val center = pageCrossCenter(nextPose, (viewport.rotation + if (horizontal) 0 else 3) % 4)
                val slotCenter = if (horizontal) pageSlotFraction(center, rowLength(focal.page, nextPose),
                    pageImageLength(width, imageHeight, ratio, nextPose, true)) else center
                val nextViewport = if (horizontal) width else height - 2f * leading
                val nextProbe = if (changingMode) (if (horizontal) width else height) / 3f - leading else nextViewport / 2f
                target = pageScrollAnchor(focal.page, slotCenter, nextViewport, gap.toFloat(),
                    discrete = horizontal, probeOffset = nextProbe) { rowLength(it, nextPose) }
                gesture.pose = nextPose
                updatePose(nextPose)
            }
            gesture.page = null
            place(target, nextPose) {
                anchor = target
                previousLayout = PageLayout(width, height, nextPose, horizontal, leading)
                appliedRevision = positionRevision
            }
            if (transformed) { capture(); finish() }
        }
        val legacyImage = if (anchor.fraction < 0f) images[anchor.page] else null
        LaunchedEffect(legacyImage, width, height, horizontal, viewport.rotation) {
            if (!usable || anchor.fraction >= 0f || legacyImage == null) return@LaunchedEffect
            val extent = rowLength(anchor.page)
            val resolved = legacyPageFraction(extent, if (horizontal) width else height - 2f * leading, currentPose, horizontal, gap.toFloat())
            val target = PageAnchor(anchor.page, resolved)
            place(target) {
                anchor = target
                position(target.page, target.fraction)
            }
            finish()
        }
        var previousZoom by remember(documentKey) { mutableStateOf(viewport.zoom) }
        LaunchedEffect(viewport.zoom) {
            val changed = previousZoom != viewport.zoom
            previousZoom = viewport.zoom
            previousLayout = previousLayout?.let { it.copy(pose = it.pose.copy(zoom = viewport.zoom)) }
            val gesture = requestedZoom == viewport.zoom
            requestedZoom = null
            if (ready && changed && !gesture) place(anchor)
        }
        var previousAspects by remember(documentKey) { mutableStateOf(aspects) }
        LaunchedEffect(aspects) {
            val old = previousAspects[anchor.page] ?: DefaultPageAspect
            previousAspects = aspects
            if (ready && (anchor.fraction < 0f || old != (aspects[anchor.page] ?: DefaultPageAspect)) &&
                !list.isScrollInProgress && !transforming) place(anchor)
        }
        LaunchedEffect(list, documentKey, usable) {
            if (!usable) return@LaunchedEffect
            snapshotFlow {
                val visible = list.layoutInfo.visibleItemsInfo
                if (visible.isEmpty()) null else visible.first().index..visible.last().index
            }.collect { range -> if (range != null) requestPages(range) }
        }
        LaunchedEffect(list, documentKey, usable, horizontal, width, height) {
            if (!usable) return@LaunchedEffect
            snapshotFlow {
                if (!ready) null else list.layoutInfo.let { layout ->
                    pageAtProbe(layout.visibleItemsInfo,
                        layout.viewportStartOffset + (if (horizontal) width else height) / 3f,
                        { it.offset.toFloat() }, { it.size.toFloat() })?.index
                }
            }.collect { index -> if (index != null) currentPage(index) }
        }
        LaunchedEffect(list, documentKey, width, height, viewport.rotation, horizontal, viewport.zoom, aspects) {
            var moved = false
            var previous: PageScroll? = null
            snapshotFlow {
                PageScroll(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset,
                    list.layoutInfo.visibleItemsInfo.firstOrNull()?.size ?: 0, list.isScrollInProgress,
                    ready && !transforming, placement)
            }.collect { scroll ->
                val old = previous
                previous = scroll
                val discrete = horizontal && requestedZoom == null && gesture.page == null
                val movedPosition = old?.ready == true && old.placement == scroll.placement &&
                    (old.page != scroll.page || old.offset != scroll.offset)
                // Geometry can restart this observer after a direct scroll already became idle.
                val changed = (anchor.fraction < 0f || discrete) && movedPosition ||
                    discrete && old == null && scroll.offset != 0
                if (!scroll.ready || scroll.placement != placement || !ready || transforming) {
                    moved = false
                } else if (scroll.scrolling || moved || changed) {
                    moved = scroll.scrolling
                    if (!moved && discrete && presentationOffset() != 0f) {
                        // Touch flings already snapped; semantic/key steps retain their requested direction.
                        try { snapPresentation(if (list.lastScrolledForward) Float.MAX_VALUE else -Float.MAX_VALUE) }
                        catch (_: CancellationException) {
                            // A new input mutation may interrupt settling without cancelling this observer.
                            currentCoroutineContext().ensureActive()
                            return@collect
                        }
                        // The synchronous correction is already handled; its next snapshot is not a new gesture.
                        previous = scroll.copy(page = list.firstVisibleItemIndex,
                            offset = list.firstVisibleItemScrollOffset, scrolling = false)
                    }
                    currentCapture()
                    if (!moved) finish()
                }
            }
        }
        DisposableEffect(documentKey) {
            onDispose { settling?.cancel(); finish() }
        }
        val scrollObserver = remember(documentKey, horizontal, density) {
            object : NestedScrollConnection {
                var distance = 0f
                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    val delta = if (horizontal) consumed.x else consumed.y
                    if (source == NestedScrollSource.UserInput && delta != 0f) {
                        if (distance * delta < 0f) distance = 0f
                        distance += delta
                        if (abs(distance) >= with(density) { 12.dp.toPx() }) {
                            scrollDirection(distance < 0f)
                            distance = 0f
                        }
                    }
                    return Offset.Zero
                }
            }
        }
        val gestures = Modifier.fillMaxSize().nestedScroll(scrollObserver).semantics {
                contentDescription = "Document pages"
                customActions = listOf(
                    CustomAccessibilityAction("Zoom in") { currentTransform(Offset(width / 2, constraints.maxHeight / 2f), Offset.Zero, 1.5f); settleTransform(); true },
                    CustomAccessibilityAction("Zoom out") { currentTransform(Offset(width / 2, constraints.maxHeight / 2f), Offset.Zero, 1f / 1.5f); settleTransform(); true },
                ) + listOfNotNull(onReadText?.let { read -> CustomAccessibilityAction("Read document text") { read(); true } })
            }.pointerInput(documentKey, horizontal) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    settling?.cancel()
                    gesture.page = null
                    var motion = Offset.Zero
                    var zoomMotion = 1f
                    var owns = false
                    var crossAxis = false
                    var changed = false
                    try {
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.changes.any { it.isConsumed }) break
                            val multiple = event.changes.count { it.pressed } > 1
                            val pan = event.calculatePan()
                            val factor = event.calculateZoom()
                            motion += pan
                            zoomMotion *= factor
                            if (!owns) {
                                val pinch = multiple && abs(1f - zoomMotion) * event.calculateCentroidSize(useCurrent = false) > viewConfiguration.touchSlop
                                val cross = if (horizontal) motion.y else motion.x
                                val along = if (horizontal) motion.x else motion.y
                                crossAxis = !multiple && currentPose.zoom > 1f && abs(cross) > viewConfiguration.touchSlop && abs(cross) > abs(along)
                                owns = pinch || crossAxis
                            }
                            if (owns) {
                                transforming = true
                                currentTransform(event.calculateCentroid(useCurrent = false),
                                    if (crossAxis && !multiple) { if (horizontal) Offset(0f, pan.y) else Offset(pan.x, 0f) } else pan, if (multiple) factor else 1f)
                                changed = true
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                    } finally {
                        transforming = false
                        if (changed) settleTransform()
                    }
                }
            }.pointerInput(documentKey, horizontal) {
                detectTapGestures(onTap = { tap() }, onDoubleTap = { focus ->
                    val zoom = currentPose.zoom
                    currentTransform(focus, Offset.Zero, (if (zoom > 1.5f) 1f else 2f) / zoom)
                    settleTransform()
                })
            }
        val placeholderInk = if (paper.luminance() > .5f) Color(0xff334155) else Color(0xffe2e8f0)
        val content: LazyListScope.() -> Unit = {
            items(pageCount, key = { it }, contentType = { "page" }) { index ->
                val rendered = images[index]
                val extent = with(density) { rowLength(index).toDp() }
                val size = if (horizontal) Modifier.width(extent).height(maxHeight) else Modifier.fillMaxWidth().height(extent)
                PageImage(rendered, currentPose, paper, horizontal, if (horizontal) breathing else 0f, size
                    .semantics {
                        contentDescription = "Page ${index + 1}"
                        stateDescription = when { rendered != null -> "Ready"; index in failedPages -> "Unavailable"; else -> "Loading" }
                        if (index in failedPages) customActions = listOf(CustomAccessibilityAction("Retry page ${index + 1}") { onRetryPage(index); true })
                    }) {
                    if (index in failedPages) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Page unavailable", color = placeholderInk,
                            style = MaterialTheme.typography.labelSmall)
                        TextButton(onClick = { onRetryPage(index) }, colors = ButtonDefaults.textButtonColors(contentColor = placeholderInk)) { Text("Retry") }
                    } else Text("${index + 1}", color = placeholderInk,
                        style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (horizontal) CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            val snapOffset by rememberUpdatedState(::presentationOffset)
            val snap = remember(list) {
                object : SnapLayoutInfoProvider {
                    // A presentation swipe selects the adjacent page; momentum must not skip slides.
                    override fun calculateApproachOffset(velocity: Float, decayOffset: Float) = 0f
                    override fun calculateSnapOffset(velocity: Float) = snapOffset(velocity)
                }
            }
            LazyRow(state = list, horizontalArrangement = Arrangement.spacedBy(8.dp),
                flingBehavior = rememberSnapFlingBehavior(snap), modifier = gestures, content = content)
        } else LazyColumn(state = list, verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = with(density) { leading.toDp() }), modifier = gestures, content = content)
    }
}

private const val DefaultPageAspect = 1.4142135f

/** Choose the visible page covering the reading probe, or the nearer edge across a gap. */
internal inline fun <T> pageAtProbe(items: List<T>, probe: Float, offset: (T) -> Float, extent: (T) -> Float): T? =
    items.minByOrNull { maxOf(offset(it) - probe, probe - offset(it) - extent(it), 0f) }

/** A pinch/pan may move inside a Present page, never into another page's slot. */
internal fun pageScrollOffset(requested: Int, extent: Float, viewport: Float, discrete: Boolean, first: Boolean = false): Int =
    if (discrete) requested.coerceIn(0, (extent - viewport).roundToInt().coerceAtLeast(0))
    else if (first) requested.coerceAtLeast(0) else requested

/** Zoomed pages may pan internally, but a resting viewport never straddles two pages. */
internal fun presentationSnapOffset(offset: Int, extent: Int, viewport: Int, gap: Int, hasNext: Boolean,
    velocity: Float = 0f, minimumVelocity: Float = Float.POSITIVE_INFINITY): Float {
    val end = (extent - viewport).coerceAtLeast(0)
    if (offset <= end) return 0f
    val previous = (end - offset).toFloat()
    val next = (extent + gap - offset).toFloat()
    return if (hasNext && (velocity >= minimumVelocity || velocity > -minimumVelocity && next < -previous)) next else previous
}

internal fun pageRowLength(width: Float, height: Float, aspect: Float, pose: ViewportState, horizontal: Boolean): Float {
    val image = pageImageLength(width, height, aspect, pose, horizontal)
    return if (horizontal) maxOf(width, image).coerceIn(1f, 131000f) else image
}

internal fun pageImageLength(width: Float, height: Float, aspect: Float, pose: ViewportState, horizontal: Boolean): Float {
    val ratio = aspect.takeIf { it.isFinite() && it > 0f } ?: DefaultPageAspect
    val rotated = if (pose.rotation % 2 == 0) ratio else 1f / ratio
    val extent = if (horizontal) min(width, height / rotated) else width * rotated
    // Compose constraints have a finite integer encoding; huge source pages remain viewable.
    return (extent * pose.zoom).coerceIn(1f, 131000f)
}

/** Convert between centered page pixels and the surrounding presentation slot. */
internal fun pageAlongFraction(slotFraction: Float, slotExtent: Float, imageExtent: Float): Float =
    (slotFraction * slotExtent - (slotExtent - imageExtent) / 2f) / imageExtent.coerceAtLeast(1f)

internal fun pageSlotFraction(imageFraction: Float, slotExtent: Float, imageExtent: Float): Float =
    ((slotExtent - imageExtent) / 2f + imageFraction * imageExtent) / slotExtent.coerceAtLeast(1f)

private fun Float.pageFraction() = if (isFinite()) coerceIn(0f, 1f) else 0f
private fun Float.initialPageFraction() = if (this == -1f) this else pageFraction()

/** Locate the page under a viewport probe; a gap uses its nearer page edge. */
internal fun pageFocalAnchor(page: Int, fraction: Float, viewport: Float, count: Int, gap: Float,
    probeOffset: Float = viewport / 2f, extent: (Int) -> Float): PageAnchor {
    var index = page
    var offset = fraction * (extent(index) + gap) + probeOffset
    while (index + 1 < count && offset > extent(index) + gap / 2f) {
        offset -= extent(index) + gap
        index++
    }
    return PageAnchor(index, (offset / extent(index)).pageFraction())
}

/** Continuous reading can span earlier rows; Present stays within the focal page. */
internal fun pageScrollAnchor(page: Int, center: Float, viewport: Float, gap: Float, discrete: Boolean = false,
    probeOffset: Float = viewport / 2f, extent: (Int) -> Float): PageAnchor {
    var index = page
    val length = extent(index)
    var offset = center * length - probeOffset
    if (discrete) return PageAnchor(page, offset.coerceIn(0f, (length - viewport).coerceAtLeast(0f)) / (length + gap))
    while (index > 0 && offset < 0f) {
        index--
        offset += extent(index) + gap
    }
    return PageAnchor(index, (offset / (extent(index) + gap)).pageFraction())
}

internal fun pageFocalPose(pose: ViewportState, previousRotation: Int, alongCenter: Float, horizontal: Boolean): ViewportState {
    val axis = (previousRotation + if (horizontal) 0 else 3) % 4
    return withPageCrossCenter(pose, axis, alongCenter.coerceIn(0f, 1f))
}

internal fun legacyPageFraction(extent: Float, viewport: Float, pose: ViewportState, horizontal: Boolean, gap: Float): Float {
    val rotation = (pose.rotation + if (horizontal) 0 else 3) % 4
    val center = pageCrossCenter(pose, rotation)
    return ((center * extent - viewport / 2f).coerceAtLeast(0f) / (extent + gap).coerceAtLeast(1f)).pageFraction()
}

internal fun pagePan(pose: ViewportState, width: Float, focus: Float, pan: Float, previousZoom: Float = pose.zoom, horizontal: Boolean = false, baseExtent: Float = width): ViewportState {
    if (width <= 0f || !width.isFinite() || baseExtent <= 0f || !baseExtent.isFinite() || !focus.isFinite() || !pan.isFinite()) return pose
    val rotation = (pose.rotation + if (horizontal) 3 else 0) % 4
    val previousHalf = min(.5f, width / (2f * baseExtent * previousZoom))
    // Stored centers may lie outside what the old renderer could display after a resize or rotation.
    val center = pageCrossCenter(pose, rotation).coerceIn(previousHalf, 1f - previousHalf)
    val half = min(.5f, width / (2f * baseExtent * pose.zoom))
    val next = (center + (focus - width / 2f) / (baseExtent * previousZoom) -
        (focus + pan - width / 2f) / (baseExtent * pose.zoom)).coerceIn(half, 1f - half)
    return withPageCrossCenter(pose, rotation, next)
}

private fun withPageCrossCenter(pose: ViewportState, rotation: Int, value: Float): ViewportState = when (rotation) {
    1 -> pose.copy(centerY = 1f - value)
    2 -> pose.copy(centerX = 1f - value)
    3 -> pose.copy(centerY = value)
    else -> pose.copy(centerX = value)
}

@Composable
private fun PageImage(rendered: ReaderPageImage?, pose: ViewportState, paper: Color, horizontal: Boolean, inset: Float, modifier: Modifier,
    placeholder: @Composable () -> Unit,
) {
    val image = remember(rendered?.bitmap) { rendered?.bitmap?.asImageBitmap() }
    val source = remember(image, rendered?.crop) {
        image?.let {
            val crop = rendered?.crop?.takeIf { region ->
                region.left.isFinite() && region.top.isFinite() && region.right.isFinite() && region.bottom.isFinite() &&
                    region.left >= 0f && region.top >= 0f && region.right <= 1f && region.bottom <= 1f && region.width() > 0f && region.height() > 0f
            }
            if (crop == null) Rect(0f, 0f, it.width.toFloat(), it.height.toFloat())
            else Rect(crop.left * it.width, crop.top * it.height, crop.right * it.width, crop.bottom * it.height)
        }
    }
    val edge = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f)
    Box(modifier.then(if (!horizontal) Modifier.shadow(2.dp, ambientColor = Color.Black, spotColor = Color.Black) else Modifier)
        .clipToBounds().then(if (image == null) Modifier.background(paper) else Modifier).drawWithCache {
        val page = image
        val region = source
        onDrawBehind {
            if (page != null && region != null && region.width > 0 && region.height > 0) {
                val sideways = pose.rotation % 2 != 0
                val rotatedWidth = if (sideways) region.height else region.width
                val rotatedHeight = if (sideways) region.width else region.height
                val scale = if (horizontal) min(size.width / rotatedWidth, (size.height - 2f * inset).coerceAtLeast(1f) / rotatedHeight * pose.zoom)
                    else min(size.width / rotatedWidth * pose.zoom, size.height / rotatedHeight)
                val center = pageCrossCenter(pose, (pose.rotation + if (horizontal) 3 else 0) % 4)
                val axis = if (horizontal) size.height else size.width
                val extent = (if (horizontal) rotatedHeight else rotatedWidth) * scale
                val half = min(.5f, axis / (2f * extent))
                val shift = (.5f - center.coerceIn(half, 1f - half)) * extent
                withTransform({
                    translate(size.width / 2 + if (horizontal) 0f else shift, size.height / 2 + if (horizontal) shift else 0f)
                    rotate(pose.rotation * 90f, Offset.Zero)
                    scale(scale, scale, Offset.Zero)
                    translate(-region.width / 2, -region.height / 2)
                }) {
                    if (horizontal) {
                        val spread = 2.dp.toPx() / scale
                        drawRect(Color.Black.copy(alpha = .18f), topLeft = Offset(-spread, -spread),
                            size = Size(region.width + spread * 2f, region.height + spread * 2f))
                    }
                    clipRect(0f, 0f, region.width, region.height) {
                        drawRect(paper, size = Size(region.width, region.height))
                        drawImage(page, topLeft = Offset(-region.left, -region.top))
                        val line = 1.dp.toPx() / scale
                        drawRect(edge, topLeft = Offset(line / 2f, line / 2f),
                            size = Size((region.width - line).coerceAtLeast(0f), (region.height - line).coerceAtLeast(0f)),
                            style = Stroke(line))
                    }
                }
            }
        }
    }, contentAlignment = Alignment.Center) { if (image == null) placeholder() }
}

private fun pageCrossCenter(pose: ViewportState, rotation: Int): Float = when (rotation) {
    1 -> 1f - pose.centerY
    2 -> 1f - pose.centerX
    3 -> pose.centerY
    else -> pose.centerX
}
