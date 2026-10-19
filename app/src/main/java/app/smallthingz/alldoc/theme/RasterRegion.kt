package app.smallthingz.alldoc.theme

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.round

/** Complete normalized bounds of opaque rectangular raster draws on the rendered page. */
data class RasterRegion(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    companion object {
        const val LIMIT = 64

        /** Null means unknown; an empty complete list proves there are no raster draws. */
        fun snapshot(regions: List<RasterRegion>?): List<RasterRegion>? {
            if (regions == null || regions.size > LIMIT) return null
            val copy = regions.take(LIMIT + 1)
            if (copy.size > LIMIT || copy.any { !it.left.isFinite() || !it.top.isFinite() || !it.right.isFinite() || !it.bottom.isFinite() ||
                it.left < 0f || it.top < 0f || it.right > 1f || it.bottom > 1f || it.left >= it.right || it.top >= it.bottom }) return null
            return java.util.Collections.unmodifiableList(copy)
        }
    }
}

/** Project a source edge without turning Float crop rounding into an extra preserved pixel. */
internal fun projectRasterEdge(value: Double, valueError: Double, start: Float, fraction: Float,
    outputSize: Int, upper: Boolean): Int {
    val distance = value - start
    val denominator = fraction.toDouble()
    val position = distance * outputSize / denominator
    val nearest = round(position)
    val startError = Math.ulp(start).toDouble() * .5
    val fractionError = Math.ulp(fraction).toDouble() * .5
    val uncertainty = outputSize * (valueError + startError + abs(distance) * fractionError / denominator) /
        (denominator - fractionError) + Math.ulp(position) * 2
    // Snap only inside the input rounding interval; real subpixel edges keep outward coverage.
    val stable = if (abs(position - nearest) <= uncertainty) nearest else position
    return (if (upper) ceil(stable) else floor(stable)).toInt().coerceIn(0, outputSize)
}
