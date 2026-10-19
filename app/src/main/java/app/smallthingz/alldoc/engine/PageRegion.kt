package app.smallthingz.alldoc.engine

import app.smallthingz.alldoc.theme.RasterRegion

/** An unrotated, normalized source rectangle rasterized into a bounded display-size bitmap. */
data class PageRegion(val left: Float, val top: Float, val right: Float, val bottom: Float,
    val width: Int, val height: Int) {
    init {
        require(left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite() &&
            left >= 0f && top >= 0f && right <= 1f && bottom <= 1f && left < right && top < bottom)
        require(width in 1..MAX_EDGE && height in 1..MAX_EDGE && width.toLong() * height <= MAX_PIXELS)
    }
    val widthFraction get() = right - left
    val heightFraction get() = bottom - top

    /** Image-preservation hints must use the same coordinate system as the new raster. */
    fun rasterRegions(regions: List<RasterRegion>?): List<RasterRegion>? =
        RasterRegion.snapshot(regions)?.mapNotNull { region ->
            val x0 = maxOf(left, region.left); val y0 = maxOf(top, region.top)
            val x1 = minOf(right, region.right); val y1 = minOf(bottom, region.bottom)
            if (x0 >= x1 || y0 >= y1) null else RasterRegion(
                ((x0 - left) / widthFraction).coerceIn(0f, 1f),
                ((y0 - top) / heightFraction).coerceIn(0f, 1f),
                ((x1 - left) / widthFraction).coerceIn(0f, 1f),
                ((y1 - top) / heightFraction).coerceIn(0f, 1f))
        }

    companion object {
        const val MAX_EDGE = 4096
        const val MAX_PIXELS = 8_000_000L
    }
}
