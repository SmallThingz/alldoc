package app.smallthingz.alldoc.ui

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.smallthingz.alldoc.engine.SheetFrame
import app.smallthingz.alldoc.engine.SheetGeometry
import app.smallthingz.alldoc.engine.SheetViewport
import app.smallthingz.alldoc.engine.WorkbookInfo
import app.smallthingz.alldoc.state.SheetPosition
import app.smallthingz.alldoc.state.ViewportState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Cell geometry and pixels come from one sheet renderer; this surface never arranges print pages. */
@Composable
internal fun ReaderWorksheet(
    documentKey: Long, workbook: WorkbookInfo?, geometry: SheetGeometry?, position: SheetPosition,
    frame: SheetFrame?, loading: Boolean, error: String?, onPosition: (SheetPosition) -> Unit,
    onRequest: (SheetViewport) -> Unit, onSelectSheet: (Int) -> Unit, onGestureEnd: () -> Unit,
    onRetry: () -> Unit, modifier: Modifier = Modifier, active: Boolean = true,
    paper: Color = Color.White, onScrollDirection: (Boolean) -> Unit = {}, onTap: () -> Unit = {},
    onViewport: (IntSize, Float) -> Unit = { _, _ -> },
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val changed by rememberUpdatedState(onPosition)
    val requested by rememberUpdatedState(onRequest)
    val viewportChanged by rememberUpdatedState(onViewport)
    val finished by rememberUpdatedState(onGestureEnd)
    val direction by rememberUpdatedState(onScrollDirection)
    val tapped by rememberUpdatedState(onTap)
    var pose by remember(documentKey) { mutableStateOf(position) }
    var dragging by remember(documentKey) { mutableStateOf(false) }
    var settling by remember(documentKey) { mutableStateOf<Job?>(null) }
    SideEffect { if (!dragging && settling?.isActive != true && pose != position) pose = position }
    LaunchedEffect(active, position.sheet) {
        settling?.cancel()
        if (!active) finished()
        if (pose.sheet != position.sheet) pose = position
    }
    DisposableEffect(documentKey) { onDispose { settling?.cancel(); finished(); viewportChanged(IntSize.Zero, 0f) } }
    val colors = MaterialTheme.colorScheme
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = colors.onSurfaceVariant)
    val labels = rememberTextMeasurer(cacheSize = 256)
    val focus = remember { FocusRequester() }
    val tabs = rememberLazyListState()
    val tabsActive by rememberUpdatedState(active)
    var revealTabPending by remember(documentKey, workbook, position.sheet) { mutableStateOf(true) }
    LaunchedEffect(documentKey, workbook, position.sheet, active) {
        if (!active) { tabs.stopScroll(MutatePriority.PreventUserInput); return@LaunchedEffect }
        if (!revealTabPending || workbook == null || workbook.sheets.size < 2) return@LaunchedEffect
        val index = workbook?.sheets?.indexOfFirst { it.index == position.sheet } ?: -1
        if (index < 0) return@LaunchedEffect
        val layout = tabs.layoutInfo
        val shown = layout.visibleItemsInfo.any {
            it.index == index && it.offset >= layout.viewportStartOffset && it.offset + it.size <= layout.viewportEndOffset
        }
        try {
            if (!shown) {
                if (layout.visibleItemsInfo.isEmpty()) tabs.scrollToItem(index) else tabs.animateScrollToItem(index)
            }
        } catch (cancelled: CancellationException) {
            if (tabsActive) revealTabPending = false // Manual scrolling replaces an unfinished reveal.
            throw cancelled
        }
        revealTabPending = false
    }
    Column(modifier.background(paper).clipToBounds()) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val sheet = geometry?.takeIf { it.sheet.index == pose.sheet }
            val labelHeight = labels.measure("123", labelStyle).size.height.toFloat()
            val left = if (sheet?.sheet?.showHeadings == false) 0f else max(with(density) { 44.dp.toPx() },
                labels.measure((sheet?.sheet?.rowCount ?: 999).toString(), labelStyle).size.width + with(density) { 16.dp.toPx() })
            val top = if (sheet?.sheet?.showHeadings == false) 0f else max(with(density) { 28.dp.toPx() }, labelHeight + with(density) { 8.dp.toPx() })
            val width = (constraints.maxWidth - left).coerceAtLeast(0f)
            val height = (constraints.maxHeight - top).coerceAtLeast(0f)
            val baseScale = density.density.toDouble()
            val origin by rememberUpdatedState(Offset(left, top))
            val currentFrame = frame?.takeIf { it.viewport.sheet == pose.sheet }
            val image = remember(currentFrame?.bitmap) { currentFrame?.bitmap?.asImageBitmap() }
            fun move(pan: Offset, factor: Float = 1f, anchor: Offset = Offset(width / 2, height / 2)): Boolean {
                val current = sheet ?: return false
                val next = worksheetTransform(pose, current.sheet.width, current.sheet.height, width.toDouble(), height.toDouble(),
                    baseScale, anchor.x.toDouble(), anchor.y.toDouble(), pan.x.toDouble(), pan.y.toDouble(), factor)
                if (next == pose) return false
                pose = next; changed(next)
                if (abs(pan.y) > abs(pan.x) && pan.y != 0f) direction(pan.y < 0f)
                return true
            }
            // Callable-reference equality ignores changed captured geometry; keep these as lambdas.
            val moveNow by rememberUpdatedState<(Offset, Float, Offset) -> Boolean> { pan, factor, anchor ->
                move(pan, factor, anchor)
            }
            fun zoom(anchor: Offset, target: Float) {
                settling?.cancel()
                val start = pose
                val current = sheet ?: return
                settling = scope.launch {
                    try {
                        animate(start.zoom, target.coerceIn(.25f, ViewportState.MAX_ZOOM), animationSpec = tween(220)) { value, _ ->
                            pose = worksheetTransform(start, current.sheet.width, current.sheet.height, width.toDouble(), height.toDouble(),
                                baseScale, anchor.x.toDouble(), anchor.y.toDouble(), 0.0, 0.0, value / start.zoom)
                            changed(pose)
                        }
                    } finally { finished() }
                }
            }
            val zoomNow by rememberUpdatedState<(Offset, Float) -> Unit> { anchor, target -> zoom(anchor, target) }
            fun step(x: Float, y: Float): Boolean {
                settling?.cancel()
                if (!move(Offset(-x, -y))) return false
                finished(); return true
            }
            LaunchedEffect(documentKey, sheet, width, height, baseScale) {
                if (sheet == null || width < 1f || height < 1f) return@LaunchedEffect
                settling?.cancel()
                val bounded = worksheetTransform(pose, sheet.sheet.width, sheet.sheet.height,
                    width.toDouble(), height.toDouble(), baseScale, 0.0, 0.0, 0.0, 0.0, 1f)
                if (bounded != pose) { pose = bounded; changed(bounded); finished() }
            }
            LaunchedEffect(documentKey, width, height, baseScale) {
                viewportChanged(IntSize(ceil(width.toDouble()).toInt(), ceil(height.toDouble()).toInt()), baseScale.toFloat())
            }
            LaunchedEffect(documentKey, active, sheet, width, height, baseScale) {
                if (!active || sheet == null || width < 1f || height < 1f) return@LaunchedEffect
                snapshotFlow { worksheetRequest(pose, width.toDouble(), height.toDouble(), baseScale) }
                    .conflate().collect { request ->
                        requested(request)
                        // One latest viewport waits behind a request; never rasterize each pointer frame.
                        delay(120)
                    }
            }
            val gestures = Modifier.fillMaxSize().focusRequester(focus).semantics {
                contentDescription = "Worksheet"
                stateDescription = sheet?.let {
                    "${it.sheet.name}, row ${it.rowAt(pose.y) + 1}, column ${worksheetColumn(it.columnAt(pose.x))}, ${(pose.zoom * 100).toInt()}%"
                } ?: if (error != null) "Unavailable" else "Loading"
                customActions = if (!active || sheet == null) emptyList() else listOf(
                    CustomAccessibilityAction("Scroll left") { step(-width * .8f, 0f) },
                    CustomAccessibilityAction("Scroll right") { step(width * .8f, 0f) },
                    CustomAccessibilityAction("Scroll up") { step(0f, -height * .8f) },
                    CustomAccessibilityAction("Scroll down") { step(0f, height * .8f) },
                    CustomAccessibilityAction("Zoom in") { zoom(Offset(width / 2, height / 2), pose.zoom * 1.5f); true },
                    CustomAccessibilityAction("Zoom out") { zoom(Offset(width / 2, height / 2), pose.zoom / 1.5f); true })
            }.onPreviewKeyEvent { event ->
                if (!active || event.type != KeyEventType.KeyDown) false else when (event.key) {
                    Key.DirectionLeft -> step(-with(density) { 64.dp.toPx() }, 0f)
                    Key.DirectionRight -> step(with(density) { 64.dp.toPx() }, 0f)
                    Key.DirectionUp -> step(0f, -with(density) { 48.dp.toPx() })
                    Key.DirectionDown -> step(0f, with(density) { 48.dp.toPx() })
                    Key.PageUp -> step(0f, -height * .8f)
                    Key.PageDown -> step(0f, height * .8f)
                    Key.MoveHome -> { settling?.cancel(); pose = pose.copy(x = 0.0, y = 0.0); changed(pose); finished(); true }
                    Key.Equals, Key.Plus -> if (event.isCtrlPressed) { zoom(Offset(width / 2, height / 2), pose.zoom * 1.5f); true } else false
                    Key.Minus -> if (event.isCtrlPressed) { zoom(Offset(width / 2, height / 2), pose.zoom / 1.5f); true } else false
                    else -> false
                }
            }.focusable(active).pointerInput(documentKey, active, pose.sheet, width, height) {
                if (!active) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    settling?.cancel(); focus.requestFocus()
                    val velocity = VelocityTracker().also { it.addPosition(down.uptimeMillis, down.position) }
                    var motion = Offset.Zero
                    var zoomMotion = 1f
                    var owns = false
                    var pinched = false
                    var moved = false
                    var released = false
                    try {
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.changes.any { it.isConsumed }) break
                            val multiple = event.changes.count { it.pressed } > 1
                            val pan = event.calculatePan()
                            val factor = event.calculateZoom()
                            motion += pan; zoomMotion *= factor; pinched = pinched || multiple
                            event.changes.firstOrNull { it.id == down.id }?.let { velocity.addPosition(it.uptimeMillis, it.position) }
                            if (!owns) owns = motion.getDistance() > viewConfiguration.touchSlop ||
                                multiple && abs(1f - zoomMotion) * event.calculateCentroidSize(useCurrent = false) > viewConfiguration.touchSlop
                            if (owns) {
                                dragging = true
                                // Final UP has no shared pressed pointers, so its centroid is unspecified.
                                if (pan != Offset.Zero || multiple && factor != 1f) {
                                    val anchor = event.calculateCentroid(useCurrent = false) - origin
                                    moved = moveNow(pan, if (multiple) factor else 1f, anchor) || moved
                                }
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                            released = event.changes.none { it.pressed }
                        } while (!released)
                    } finally {
                        dragging = false
                        if (moved) {
                            val speed = velocity.calculateVelocity()
                            if (released && !pinched && active && max(abs(speed.x), abs(speed.y)) > 180f) {
                                settling = scope.launch {
                                    var previous = Offset.Zero
                                    try {
                                        AnimationState(Offset.VectorConverter, Offset.Zero,
                                            AnimationVector2D(speed.x.coerceIn(-12000f, 12000f), speed.y.coerceIn(-12000f, 12000f)))
                                            .animateDecay(exponentialDecay()) {
                                                val delta = value - previous; previous = value
                                                if (!moveNow(delta, 1f, Offset.Zero)) cancelAnimation()
                                            }
                                    } finally { finished() }
                                }
                            } else finished()
                        }
                    }
                }
            }.pointerInput(documentKey, active, pose.sheet, width, height) {
                if (!active) return@pointerInput
                detectTapGestures(onTap = { tapped() }, onDoubleTap = { point ->
                    zoomNow(point - origin, if (pose.zoom > 1.5f) 1f else 2f)
                })
            }.pointerInput(documentKey, active) {
                if (!active) return@pointerInput
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Scroll) {
                            val delta = event.changes.fold(Offset.Zero) { sum, change -> sum + change.scrollDelta }
                            if (moveNow(delta * -with(density) { 48.dp.toPx() }, 1f, Offset.Zero)) {
                                event.changes.forEach { it.consume() }
                                settling?.cancel(); settling = scope.launch { delay(150); finished() }
                            }
                        }
                    }
                }
            }
            Canvas(gestures) {
                val pixelScale = worksheetScale(pose.zoom, baseScale).toFloat()
                clipRect(left, top, size.width, size.height) {
                    if (image != null && currentFrame != null) {
                        val ratio = pixelScale / currentFrame.viewport.scale.toFloat()
                        val x = left + ((currentFrame.viewport.x - pose.x) * pixelScale).toFloat()
                        val y = top + ((currentFrame.viewport.y - pose.y) * pixelScale).toFloat()
                        if (x < size.width && y < size.height && x + image.width * ratio > left && y + image.height * ratio > top)
                            withTransform({ translate(x, y); scale(ratio, ratio, Offset.Zero) }) { drawImage(image) }
                    }
                }
                if (sheet != null && left > 0 && top > 0) {
                    drawRect(colors.surfaceContainerHigh, size = Size(size.width, top))
                    drawRect(colors.surfaceContainerHigh, size = Size(left, size.height))
                    clipRect(left, 0f, size.width, top) {
                        var column = sheet.columnAt(pose.x)
                        while (column < sheet.sheet.columnCount) {
                            val startEdge = sheet.columnEdge(column)
                            val endEdge = sheet.columnEdge(column + 1)
                            val x = left + ((startEdge - pose.x) * pixelScale).toFloat()
                            if (x >= size.width) break
                            val end = left + ((endEdge - pose.x) * pixelScale).toFloat()
                            if (end - x >= 12.dp.toPx()) {
                                val label = labels.measure(worksheetColumn(column), labelStyle)
                                if (end - x >= label.size.width + 6.dp.toPx()) drawText(label, topLeft = Offset((x + end - label.size.width) / 2, (top - label.size.height) / 2))
                            }
                            drawLine(colors.outlineVariant, Offset(end, 0f), Offset(end, top))
                            column = worksheetHeaderNext(column, startEdge, endEdge, 12.dp.toPx() / pixelScale.toDouble(), sheet::columnAt)
                        }
                    }
                    clipRect(0f, top, left, size.height) {
                        var row = sheet.rowAt(pose.y)
                        while (row < sheet.sheet.rowCount) {
                            val startEdge = sheet.rowEdge(row)
                            val endEdge = sheet.rowEdge(row + 1)
                            val y = top + ((startEdge - pose.y) * pixelScale).toFloat()
                            if (y >= size.height) break
                            val end = top + ((endEdge - pose.y) * pixelScale).toFloat()
                            if (end - y >= labelHeight + 2.dp.toPx()) {
                                val label = labels.measure((row + 1).toString(), labelStyle)
                                drawText(label, topLeft = Offset((left - label.size.width) / 2, (y + end - label.size.height) / 2))
                            }
                            drawLine(colors.outlineVariant, Offset(0f, end), Offset(left, end))
                            row = worksheetHeaderNext(row, startEdge, endEdge, (labelHeight + 2.dp.toPx()) / pixelScale.toDouble(), sheet::rowAt)
                        }
                    }
                    drawLine(colors.outlineVariant, Offset(left, 0f), Offset(left, size.height))
                    drawLine(colors.outlineVariant, Offset(0f, top), Offset(size.width, top))
                }
                if (sheet != null) {
                    val horizontal = worksheetThumb(pose.x, width / pixelScale.toDouble(), sheet.sheet.width, width.toDouble())
                    val vertical = worksheetThumb(pose.y, height / pixelScale.toDouble(), sheet.sheet.height, height.toDouble())
                    val thickness = 3.dp.toPx()
                    if (horizontal != null) drawRoundRect((if (paper.luminance() > .5f) Color.Black else Color.White).copy(alpha = .45f),
                        topLeft = Offset(left + horizontal.first.toFloat(), size.height - thickness),
                        size = Size(horizontal.second.toFloat(), thickness))
                    if (vertical != null) drawRoundRect((if (paper.luminance() > .5f) Color.Black else Color.White).copy(alpha = .45f),
                        topLeft = Offset(size.width - thickness, top + vertical.first.toFloat()),
                        size = Size(thickness, vertical.second.toFloat()))
                }
            }
            if (active && loading && currentFrame == null && error == null) CircularProgressIndicator(Modifier.align(Alignment.Center).size(28.dp), strokeWidth = 2.dp)
            if (error != null) Column(Modifier.align(if (currentFrame == null) Alignment.Center else Alignment.BottomCenter).padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                if (currentFrame == null) Text("Worksheet unavailable", color = if (paper.luminance() > .5f) Color(0xff334155) else Color(0xffe2e8f0))
                FilledTonalButton(onClick = onRetry, enabled = active,
                    modifier = Modifier.semantics { contentDescription = "Retry worksheet" }) { Text("Retry") }
            }
        }
        if (workbook != null && workbook.sheets.size > 1) LazyRow(Modifier.fillMaxWidth().background(colors.surfaceContainer).selectableGroup(),
            state = tabs, userScrollEnabled = active, contentPadding = PaddingValues(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            items(workbook.sheets, key = { it.index }) { sheet ->
                TextButton(onClick = { settling?.cancel(); finished(); onSelectSheet(sheet.index) }, enabled = active,
                    modifier = Modifier.heightIn(min = 48.dp).widthIn(max = 240.dp).semantics { role = Role.Tab; selected = sheet.index == pose.sheet }) {
                    Text(sheet.name, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (sheet.index == pose.sheet) colors.primary else colors.onSurfaceVariant)
                }
            }
        }
    }
}

internal fun worksheetScale(zoom: Float, density: Double): Double {
    val level = zoom.takeIf { it.isFinite() && it > 0f } ?: 1f
    val pixels = density.takeIf { it.isFinite() && it > 0.0 } ?: 1.0
    return (level * pixels).coerceIn(.25, SheetViewport.MAX_SCALE)
}

/** Preserve the document point beneath the fingers, then clamp to the sheet bounds. */
internal fun worksheetTransform(before: SheetPosition, sheetWidth: Double, sheetHeight: Double,
    width: Double, height: Double, density: Double, focusX: Double, focusY: Double,
    panX: Double, panY: Double, factor: Float): SheetPosition {
    val zoom = (before.zoom * factor.takeIf { it.isFinite() && it > 0f }.let { it ?: 1f }).coerceIn(.25f, ViewportState.MAX_ZOOM)
    val old = worksheetScale(before.zoom, density)
    val next = worksheetScale(zoom, density)
    return before.copy(zoom = zoom,
        x = (before.x + focusX / old - (focusX + panX) / next).coerceIn(0.0, max(0.0, sheetWidth - width / next)),
        y = (before.y + focusY / old - (focusY + panY) / next).coerceIn(0.0, max(0.0, sheetHeight - height / next)))
}

/** Spend the raster budget on visible pixels first; quantized overscan only uses spare capacity. */
internal fun worksheetRequest(position: SheetPosition, width: Double, height: Double, density: Double): SheetViewport {
    val scale = worksheetScale(position.zoom, density)
    val visibleWidth = ceil(width).coerceAtLeast(1.0)
    val visibleHeight = ceil(height).coerceAtLeast(1.0)
    val spareArea = (sqrt((visibleWidth - visibleHeight) * (visibleWidth - visibleHeight) +
        4.0 * SheetViewport.MAX_PIXELS) - visibleWidth - visibleHeight) / 2.0
    val padding = minOf(192.0, SheetViewport.MAX_EDGE - visibleWidth, SheetViewport.MAX_EDGE - visibleHeight,
        spareArea).coerceAtLeast(0.0)
    val step = floor(padding / 2.0)
    val paddedWidth = visibleWidth + 2.0 * step
    val paddedHeight = visibleHeight + 2.0 * step
    val fit = minOf(1.0, SheetViewport.MAX_EDGE / paddedWidth, SheetViewport.MAX_EDGE / paddedHeight,
        sqrt(SheetViewport.MAX_PIXELS / (paddedWidth * paddedHeight)))
    val rasterWidth = floor(paddedWidth * fit).toInt().coerceIn(1, SheetViewport.MAX_EDGE)
    val rasterHeight = floor(paddedHeight * fit).toInt().coerceIn(1, SheetViewport.MAX_EDGE)
    // Derive density from the rounded allocation so both axes still cover the full visible extent.
    val downsample = minOf(1.0, rasterWidth / paddedWidth, rasterHeight / paddedHeight)
    val x = if (step == 0.0) position.x else max(0.0, (floor(position.x * scale / step) * step - step) / scale)
    val y = if (step == 0.0) position.y else max(0.0, (floor(position.y * scale / step) * step - step) / scale)
    return SheetViewport(position.sheet, x, y, scale * downsample, rasterWidth, rasterHeight).checked()
}

/** Export visible cells, independently of padded render requests or their completion. */
internal fun worksheetVisibleViewport(position: SheetPosition, size: IntSize, density: Double): SheetViewport? {
    if (size.width <= 0 || size.height <= 0 || !density.isFinite() || density <= 0) return null
    val downsample = min(1.0, 2800.0 / max(size.width, size.height))
    val scale = worksheetScale(position.zoom, density) * downsample
    if (scale < .0625) return null
    return SheetViewport(position.sheet, position.x, position.y, scale,
        ceil(size.width * downsample).toInt().coerceIn(1, 2800),
        ceil(size.height * downsample).toInt().coerceIn(1, 2800)).checked()
}

/** Skip hidden bands and subpixel runs without imposing an index cutoff on the visible headers. */
internal inline fun worksheetHeaderNext(index: Int, start: Double, end: Double, minimumUnits: Double, at: (Double) -> Int) =
    max(index + 1, at(max(end, start + minimumUnits)))

internal fun worksheetColumn(index: Int): String {
    var number = index.coerceAtLeast(0) + 1
    val result = StringBuilder()
    while (number > 0) { number--; result.append(('A'.code + number % 26).toChar()); number /= 26 }
    return result.reverse().toString()
}

internal fun worksheetThumb(position: Double, viewport: Double, extent: Double, track: Double): Pair<Double, Double>? {
    if (extent <= viewport || track <= 0) return null
    val length = (track * viewport / extent).coerceIn(min(24.0, track), track)
    return ((position / (extent - viewport)).coerceIn(0.0, 1.0) * (track - length)) to length
}
