package app.smallthingz.alldoc.ui

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

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
