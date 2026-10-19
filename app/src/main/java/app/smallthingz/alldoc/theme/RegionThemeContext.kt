package app.smallthingz.alldoc.theme

import app.smallthingz.alldoc.engine.PageRegion
import kotlin.math.floor

/** Full-source decisions shared by previews and detail tiles. No page pixels are retained. */
class RegionThemeContext internal constructor(
    internal val settings: ThemeSettings,
    internal val width: Int,
    internal val height: Int,
    internal val sourceRegions: List<RasterRegion>?,
    internal val backdrops: BackdropRegions?,
    internal val transparent: Boolean,
    internal val sourceBounds: IntArray?,
    internal val scans: Array<ScanPaperMask?>?,
    internal val treatments: Array<PixelTheme.Tile>,
    internal val ink: RegionInkContext?,
) {
    /** Conservative retained arrays/list/object storage; callers include this with their page bitmap budget. */
    val byteCount: Long = 192L + (sourceRegions?.let { 128L + it.size * 64L } ?: 0L) +
        (sourceBounds?.let { 24L + it.size * 4L } ?: 0L) +
        (scans?.let { values -> 24L + values.size * 8L + values.sumOf { it?.storageBytes ?: 0L } } ?: 0L) +
        24L + treatments.size * 8L + (backdrops?.byteCount ?: 0L) + (ink?.byteCount ?: 0L)
    // Texture coordinates stay fixed even if the first preview supplied a smaller classification raster.
    private val paperWidth = probeWidth(height.toFloat() / width)
    private val paperHeight = if (height >= width) 512 else (512.0 * height / width).toInt().coerceAtLeast(1)
    internal fun x(x: Int, outputWidth: Int, region: PageRegion?): Int = coordinate(
        x, outputWidth, region?.left ?: 0f, region?.widthFraction ?: 1f, width)
    internal fun y(y: Int, outputHeight: Int, region: PageRegion?): Int = coordinate(
        y, outputHeight, region?.top ?: 0f, region?.heightFraction ?: 1f, height)
    internal fun paperX(x: Int, outputWidth: Int, region: PageRegion?): Int = coordinate(
        x, outputWidth, region?.left ?: 0f, region?.widthFraction ?: 1f, paperWidth)
    internal fun paperY(y: Int, outputHeight: Int, region: PageRegion?): Int = coordinate(
        y, outputHeight, region?.top ?: 0f, region?.heightFraction ?: 1f, paperHeight)

    internal fun treatment(x: Int, y: Int): PixelTheme.Tile = treatments[(y / 64) * ((width + 63) / 64) + x / 64]

    companion object {
        const val MAX_PIXELS = 1_048_576
        const val MAX_EDGE = 1024
        const val MAX_BYTE_COUNT = 512L * 1024
        internal const val MAX_SCAN_BYTES = 256L * 1024

        /** A canonical source raster, independent of the requested zoom or first preview width. */
        fun probeWidth(aspect: Float): Int {
            require(aspect.isFinite() && aspect > 0f)
            return minOf(512, (512.0 / aspect).toInt().coerceAtLeast(1))
        }

        private fun coordinate(pixel: Int, outputSize: Int, start: Float, fraction: Float, sourceSize: Int): Int {
            val offset = (pixel + .5) / outputSize
            val position = (start.toDouble() + offset * fraction.toDouble()) * sourceSize
            val nearest = kotlin.math.round(position)
            // Float-normalized crop edges may straddle an exact source-pixel boundary.
            // Snap only within their input rounding interval, as for source-image edges.
            val uncertainty = (Math.ulp(start).toDouble() + offset * Math.ulp(fraction)) * sourceSize * .5
            val stable = if (kotlin.math.abs(position - nearest) <= uncertainty) nearest else position
            return floor(stable).toInt().coerceIn(0, sourceSize - 1)
        }
    }
}

/** Underlying fill of proven white-ink spans, never a cached glyph/output color. */
internal class RegionInkContext {
    private var spans = IntArray(0)
    private var count = 0
    private var borrowed = LongArray(0)
    val byteCount: Long get() = 104L + spans.size * 4L + borrowed.size * 8L

    fun append(y: Int, papers: IntArray, seeds: IntArray? = null, rejections: Boolean = false) {
        var left = 0
        while (left < papers.size && count < MAX_SPANS) {
            val paper = papers[left]
            val support = seeds != null && seeds[left] == 0
            var right = left + 1
            while (right < papers.size && papers[right] == paper &&
                (seeds == null || (seeds[right] == 0) == support)) right++
            if (paper != 0 && (paper == REJECTED) == rejections) {
                if (count * 4 == spans.size) spans = spans.copyOf(minOf(MAX_SPANS * 4, maxOf(64, spans.size * 2)))
                if (support) {
                    if (count / 64 >= borrowed.size) borrowed = borrowed.copyOf((spans.size / 4 + 63) / 64)
                    borrowed[count / 64] = borrowed[count / 64] or (1L shl (count % 64))
                }
                val at = count++ * 4
                spans[at] = y; spans[at + 1] = left; spans[at + 2] = right; spans[at + 3] = paper
            }
            left = right
        }
    }

    /** One source-cell allowance for independently rasterized glyph edges, not glyph dilation. */
    fun withFillSupport(width: Int, height: Int, rejected: RegionInkContext? = null,
        readRow: (Int, IntArray) -> Unit): RegionInkContext {
        // A full rejection list may omit later exclusions; preserve the original positive policy.
        if (count == 0 || rejected?.count == MAX_SPANS) return this
        val pixels = Array(3) { IntArray(width) }
        val seeds = Array(3) { IntArray(width) }
        val papers = IntArray(width)
        fun load(slot: Int, y: Int) {
            pixels[slot].fill(0)
            seeds[slot].fill(0)
            if (y !in 0 until height) return
            readRow(y, pixels[slot])
            var at = firstSpan(y) * 4
            while (at < count * 4 && spans[at] == y) {
                seeds[slot].fill(spans[at + 3], spans[at + 1], spans[at + 2])
                at += 4
            }
            if (rejected != null) {
                at = rejected.firstSpan(y) * 4
                while (at < rejected.count * 4 && rejected.spans[at] == y) {
                    for (x in rejected.spans[at + 1] until rejected.spans[at + 2])
                        if (seeds[slot][x] == 0) seeds[slot][x] = REJECTED
                    at += 4
                }
            }
        }
        load(0, -1); load(1, 0); load(2, 1)
        val expanded = RegionInkContext()
        for (y in 0 until height) {
            seeds[1].copyInto(papers)
            for (x in 0 until width) {
                if (papers[x] != 0 || pixels[1][x] ushr 24 != 255) continue
                // An unclassified neutral panel/page edge is ambiguous, even if it touches ink.
                var neutralPaper = false
                for (row in 0..2) {
                    for (near in maxOf(0, x - 1)..minOf(width - 1, x + 1)) {
                        val value = pixels[row][near]
                        val r = value ushr 16 and 255; val g = value ushr 8 and 255; val b = value and 255
                        if (seeds[row][near] ushr 24 != 255 && value ushr 24 == 255 &&
                            maxOf(r, g, b) - minOf(r, g, b) <= 3) { neutralPaper = true; break }
                    }
                    if (neutralPaper) break
                }
                if (neutralPaper) continue
                var paper = 0
                for (row in 0..2) for (near in maxOf(0, x - 1)..minOf(width - 1, x + 1)) {
                    val candidate = seeds[row][near]
                    if (candidate ushr 24 == 255 && DocumentColors.inkCoverage(pixels[1][x], candidate, -1) in 0..254) {
                        if (paper == 0) paper = candidate
                        else if (paper != candidate) paper = 1 // conflicting opaque ARGB papers
                    }
                }
                if (paper != 1) papers[x] = paper
            }
            expanded.append(y, papers, seeds[1])
            // Never trade later proven spans for an early halo when the existing cap is full.
            if (expanded.count == MAX_SPANS) return this
            val oldPixels = pixels[0]; pixels[0] = pixels[1]; pixels[1] = pixels[2]; pixels[2] = oldPixels
            val oldSeeds = seeds[0]; seeds[0] = seeds[1]; seeds[1] = seeds[2]; seeds[2] = oldSeeds
            load(2, y + 2)
        }
        return expanded
    }

    private fun firstSpan(y: Int): Int {
        var low = 0
        var high = count
        while (low < high) {
            val middle = (low + high) ushr 1
            if (spans[middle * 4] < y) low = middle + 1 else high = middle
        }
        return low
    }

    fun row(pageX: IntArray, y: Int, pixels: IntArray, offset: Int, images: LongArray?, backdrops: LongArray?,
        colors: IntArray, rampForPaper: (Int) -> IntArray?) {
        var index = firstSpan(y)
        var x = 0
        while (index < count && spans[index * 4] == y) {
            val support = index / 64 < borrowed.size && borrowed[index / 64] and (1L shl (index % 64)) != 0L
            val at = index++ * 4
            val left = spans[at + 1]
            val right = spans[at + 2]
            val paper = spans[at + 3]
            while (x < pageX.size && pageX[x] < left) x++
            var ramp: IntArray? = null
            while (x < pageX.size && pageX[x] < right) {
                val pixel = offset + x
                if ((!support || colors[x] == 0) && pixels[pixel] ushr 24 == 255 && (images == null || !PixelTheme.marked(images, pixel)) &&
                    (backdrops == null || !PixelTheme.marked(backdrops, pixel))) {
                    val coverage = DocumentColors.inkCoverage(pixels[pixel], paper, -1)
                    if (coverage > 0) {
                        if (ramp == null) ramp = rampForPaper(paper)
                        ramp?.let { colors[x] = it[coverage] }
                    }
                }
                x++
            }
        }
    }

    companion object {
        private const val MAX_SPANS = 8192
        // Transient detector exclusion, never an opaque paper or a retained output color.
        const val REJECTED = 1
    }
}
