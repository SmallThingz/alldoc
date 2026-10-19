package app.smallthingz.alldoc.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import app.smallthingz.alldoc.engine.PageRegion
import app.smallthingz.alldoc.state.ViewportState
import kotlin.math.min
import kotlin.math.floor
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.math.roundToInt

/** Crop affects displayed detail; the byte budget still covers the complete source raster. */
internal fun pageRenderWidth(baseWidth: Int, scale: Float, aspect: Float, sideways: Boolean,
    budget: Long, cropWidth: Float = 1f, cropHeight: Float = 1f): Int {
    val fullAspect = aspect.coerceAtLeast(.001f)
    val across = if (sideways) fullAspect * cropHeight else cropWidth
    val desired = ceil(baseWidth * scale / across.coerceAtLeast(.001f)).toInt().coerceIn(1, 4096)
    return minOf(desired, sqrt(budget / 4.0 / fullAspect).toInt().coerceAtLeast(1))
}

/** Unknown margins need one detector-sized probe, including a restored high-zoom view. */
internal fun pageMarginProbeWidth(target: Int, aspect: Float): Int =
    minOf(target, (4096f / max(1f, aspect)).toInt().coerceAtLeast(1))


/** The one source-to-item transform used for both drawing and detail demand. */
internal data class PageImageTransform(val source: Rect, val center: Offset, val scale: Float, val rotation: Int) {
    fun sourceAt(point: Offset): Offset {
        val offset = (point - center) / scale
        val unrotated = when (rotation) {
            1 -> Offset(offset.y, -offset.x)
            2 -> -offset
            3 -> Offset(-offset.y, offset.x)
            else -> offset
        }
        return source.center + unrotated
    }

    fun itemAt(point: Offset): Offset {
        val offset = (point - source.center) * scale
        val rotated = when (rotation) {
            1 -> Offset(-offset.y, offset.x)
            2 -> -offset
            3 -> Offset(offset.y, -offset.x)
            else -> offset
        }
        return center + rotated
    }
}

internal fun pageImageTransform(source: Rect, size: Size, pose: ViewportState,
    horizontal: Boolean, inset: Float): PageImageTransform {
    val sideways = pose.rotation % 2 != 0
    val rotatedWidth = if (sideways) source.height else source.width
    val rotatedHeight = if (sideways) source.width else source.height
    val scale = if (horizontal) min(size.width / rotatedWidth * min(1f, pose.zoom),
        (size.height - 2f * inset).coerceAtLeast(1f) / rotatedHeight * pose.zoom)
        else min(size.width / rotatedWidth * pose.zoom, size.height / rotatedHeight)
    val center = pageCrossCenter(pose, (pose.rotation + if (horizontal) 3 else 0) % 4)
    val axis = if (horizontal) size.height else size.width
    val extent = (if (horizontal) rotatedHeight else rotatedWidth) * scale
    val half = min(.5f, axis / (2f * extent))
    val shift = (.5f - center.coerceIn(half, 1f - half)) * extent
    return PageImageTransform(source,
        Offset(size.width / 2f + if (horizontal) 0f else shift, size.height / 2f + if (horizontal) shift else 0f),
        scale, pose.rotation)
}

/** A display-sized patch of visible source pixels; never allocate the enlarged whole page. */
internal fun pageVisibleRegion(imageWidth: Int, imageHeight: Int, transform: PageImageTransform,
    visible: Rect): PageRegion? {
    if (imageWidth <= 0 || imageHeight <= 0 || !transform.scale.isFinite() || transform.scale <= 1.01f ||
        visible.width <= 0f || visible.height <= 0f) return null
    val corners = listOf(visible.topLeft, visible.topRight, visible.bottomLeft, visible.bottomRight).map(transform::sourceAt)
    val source = transform.source
    val left = maxOf(source.left, corners.minOf { it.x }).coerceIn(0f, imageWidth.toFloat())
    val top = maxOf(source.top, corners.minOf { it.y }).coerceIn(0f, imageHeight.toFloat())
    val right = minOf(source.right, corners.maxOf { it.x }).coerceIn(0f, imageWidth.toFloat())
    val bottom = minOf(source.bottom, corners.maxOf { it.y }).coerceIn(0f, imageHeight.toFloat())
    if (!left.isFinite() || !top.isFinite() || !right.isFinite() || !bottom.isFinite() || left >= right || top >= bottom) return null
    val l = left / imageWidth; val t = top / imageHeight
    val r = right / imageWidth; val b = bottom / imageHeight
    if (l >= r || t >= b) return null
    // Fit one uniform scale after selecting the integer width. Independently rounding
    // each requested axis can distort narrow strips by several pixels at deep zoom.
    val aspect = imageHeight.toDouble() * (b - t) / (imageWidth.toDouble() * (r - l))
    if (!aspect.isFinite() || aspect !in 1.0 / PageRegion.MAX_EDGE..PageRegion.MAX_EDGE.toDouble()) return null
    val limit = minOf(PageRegion.MAX_EDGE.toDouble(), PageRegion.MAX_EDGE / aspect,
        sqrt(PageRegion.MAX_PIXELS / aspect))
    var width = minOf(ceil((right - left).toDouble() * transform.scale), floor(limit))
        .toInt().coerceIn(1, PageRegion.MAX_EDGE)
    var height = (width * aspect).roundToInt().coerceAtLeast(1)
    while ((height > PageRegion.MAX_EDGE || width.toLong() * height > PageRegion.MAX_PIXELS) && width > 1) {
        width--
        height = (width * aspect).roundToInt().coerceAtLeast(1)
    }
    if (height > PageRegion.MAX_EDGE || width.toLong() * height > PageRegion.MAX_PIXELS) return null
    return PageRegion(l, t, r, b, width, height)
}
