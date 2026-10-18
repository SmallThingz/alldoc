package app.smallthingz.alldoc.theme

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
