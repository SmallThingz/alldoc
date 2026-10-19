package app.smallthingz.alldoc.ui

import app.smallthingz.alldoc.engine.PageRegion
import kotlin.math.sqrt
import kotlin.math.roundToInt

// 48 MiB of whole-page images plus 16 MiB of visible detail. One worker replaces patches.
internal fun pageDetailBudget(count: Int) = 16L * 1024 * 1024 / count.coerceIn(1, 2)

internal fun pageDetailRegion(region: PageRegion, count: Int): PageRegion {
    val pixels = pageDetailBudget(count) / 4
    val scale = minOf(1.0, sqrt(pixels.toDouble() / (region.width.toLong() * region.height)))
    if (scale == 1.0) return region
    var width = (region.width * scale).toInt().coerceAtLeast(1)
    fun height() = (region.height.toDouble() * width / region.width).roundToInt().coerceAtLeast(1)
    while (width > 1 && width.toLong() * height() > pixels) width--
    return region.copy(width = width, height = height())
}
