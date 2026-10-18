package app.smallthingz.alldoc.theme

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// Opaque ARGB colors are negative; this sentinel latches an unknown ink family without a palette.
private const val MIXED_NEUTRAL_INK = 1

/** Bounded streaming recognition of closed, flat plates; never a text/image segmentation claim. */
internal class BackdropRegions(
    private val width: Int,
    private val allowNeutralColoredInk: Boolean = false,
) {
    private val active = IntArray(32 * 9)
    private val regions = IntArray(64 * 7)
    private val previous = IntArray(width)
    private val inkRamps = arrayOfNulls<IntArray>(64)
    private var pending = 0
    private var count = 0
    private var y = 0
    var transparent = false
        private set
    val isEmpty get() = count == 0
    private val radius = (width / 32).coerceAtLeast(4)

    fun scan(pixels: IntArray, rows: Int, checkCancelled: () -> Unit) {
        for (row in 0 until rows) {
            checkCancelled()
            val offset = row * width
            if (count == 64) {
                pending = 0
                for (x in 0 until width) if (pixels[offset + x] ushr 24 < 230) {
                    transparent = true; count = 0; return
                }
                y++
                continue
            }
            var index = 0
            while (index < pending) {
                val at = index * 9
                val left = active[at]
                val right = active[at + 1]
                val color = active[at + 3]
                var same = 0
                var detail = 0
                var valid = pixels[offset + left] == color && pixels[offset + right - 1] == color &&
                    (left == 0 || pixels[offset + left - 1] != color) && (right == width || pixels[offset + right] != color)
                if (valid) for (x in left until right) {
                    val value = pixels[offset + x]
                    if (value == color) same++ else {
                        if (!ink(value, color) && !coloredInk(at, value, color)) { valid = false; break }
                        if (lightness(value) < 210) detail++
                    }
                }
                if (valid) {
                    active[at + 4] += same
                    active[at + 5] += right - left
                    active[at + 6] = if (same == right - left) 1 else 0
                    active[at + 7] += detail
                    index++
                } else {
                    var continuation = false
                    for (x in left until right) if (pixels[offset + x] == color) { continuation = true; break }
                    if (!continuation) finish(at)
                    pending--
                    active.copyInto(active, at, pending * 9, pending * 9 + 9)
                }
            }
            var left = 0
            while (left < width) {
                val color = pixels[offset + left]
                if (color ushr 24 < 230) { transparent = true; pending = 0; count = 0; return }
                var right = left + 1
                while (right < width && pixels[offset + right] == color) right++
                if (pending < 32 && right - left >= radius * 2 && backdrop(color)) {
                    var clear = true
                    if (y > 0) for (x in left until right) if (previous[x] == color) { clear = false; break }
                    if (clear) for (candidate in 0 until pending) {
                        val at = candidate * 9
                        if (left < active[at + 1] && right > active[at]) { clear = false; break }
                    }
                    if (clear) {
                        val at = pending++ * 9
                        active[at] = left; active[at + 1] = right; active[at + 2] = y; active[at + 3] = color
                        active[at + 4] = right - left; active[at + 5] = right - left; active[at + 6] = 1; active[at + 7] = 0; active[at + 8] = 0
                    }
                }
                left = right
            }
            pixels.copyInto(previous, 0, offset, offset + width)
            y++
        }
    }

    fun finish() {
        for (index in 0 until pending) finish(index * 9)
        pending = 0
    }

    private fun finish(at: Int) {
        val columns = active[at + 1] - active[at]
        val rows = y - active[at + 2]
        val total = active[at + 5]
        val neutral = chroma(active[at + 3]) <= 40
        // Small/thin neutral rectangles can be ordinary glyph strokes or rules, not paper.
        if (count == 64 || rows < radius || active[at + 6] == 0 || active[at + 4].toLong() * 5 < total.toLong() * 3 ||
            active[at + 7].toLong() * 5 > total || neutral &&
            (total.toLong() * 32 < width.toLong() * width || columns > rows * 8 || rows > columns * 8)) return
        val to = count++ * 7
        regions[to] = active[at]; regions[to + 1] = active[at + 1]; regions[to + 2] = active[at + 2]
        regions[to + 3] = y
        regions[to + 5] = active[at + 3]
        regions[to + 6] = if (active[at + 8] == MIXED_NEUTRAL_INK) 0 else active[at + 8]
        regions[to + 4] = if (neutral) 1 else if (active[at + 8] != 0) 0 else if (chroma(active[at + 3]) <= 96 && lightness(active[at + 3]) < 96) 2 else 0
    }

    fun masks(top: Int, rows: Int, all: LongArray, neutral: LongArray, muted: LongArray? = null): Boolean {
        all.fill(0L); neutral.fill(0L); muted?.fill(0L)
        var present = false
        for (index in 0 until count) {
            val at = index * 7
            val first = max(top, regions[at + 2])
            val last = min(top + rows, regions[at + 3])
            if (first >= last) continue
            present = true
            for (row in first until last) {
                val start = (row - top) * width + regions[at]
                val end = (row - top) * width + regions[at + 1]
                mark(all, start, end)
                if (regions[at + 4] == 1) mark(neutral, start, end)
                if (regions[at + 4] == 2 && muted != null) mark(muted, start, end)
            }
        }
        return present
    }

    fun prepareInk() {
        for (index in 0 until count) {
            val at = index * 7
            inkRamps[index] = if (regions[at + 6] == 0) null else
                DocumentColors.inkRamp(regions[at + 6], regions[at + 5])
        }
    }

    /** Correct identified opaque lettering; weak antialias edges keep at least their original contrast. */
    fun adaptInk(pixels: IntArray, offset: Int, y: Int, images: LongArray?) {
        for (index in 0 until count) {
            val at = index * 7
            val ink = regions[at + 6]
            val ramp = inkRamps[index] ?: continue
            if (y < regions[at + 2] || y >= regions[at + 3]) continue
            val paper = regions[at + 5]
            for (x in regions[at] until regions[at + 1]) {
                val pixel = offset + x
                if (pixels[pixel] ushr 24 != 255 || images != null && PixelTheme.marked(images, pixel)) continue
                val coverage = DocumentColors.inkCoverage(pixels[pixel], paper, ink)
                if (coverage > 0) pixels[pixel] = (pixels[pixel] and -0x1000000) or
                    (ramp[coverage] and 0x00ffffff)
            }
        }
    }

    private fun coloredInk(at: Int, value: Int, background: Int): Boolean {
        if (value ushr 24 != 255 || background ushr 24 != 255 ||
            chroma(background) <= 40 && !allowNeutralColoredInk) return false
        val ink = active[at + 8]
        if (ink == MIXED_NEUTRAL_INK || ink == value) return true
        if (ink == 0 || DocumentColors.inkCoverage(ink, background, value) >= 0) {
            active[at + 8] = value
            return true
        }
        if (DocumentColors.inkCoverage(value, background, ink) >= 0) return true
        if (chroma(background) <= 40 && allowNeutralColoredInk) {
            // Complete image bounds protect photos. Keep dark paper with mixed vector ink,
            // but leave all its colors untouched when one AA family cannot be established.
            active[at + 8] = MIXED_NEUTRAL_INK
            return true
        }
        return false
    }

    private fun backdrop(value: Int): Boolean {
        val chroma = chroma(value)
        val light = lightness(value)
        return if (chroma <= 40) light < 96 else chroma > 96 || light < 160
    }

    private fun ink(value: Int, background: Int): Boolean {
        if (value ushr 24 < 230) return false
        val spread = chroma(value)
        if (spread <= 40) return true
        val baseSpread = chroma(background)
        if (baseSpread <= 40 || spread > baseSpread + 3) return false
        val low = min(value ushr 16 and 255, min(value ushr 8 and 255, value and 255))
        val baseLow = min(background ushr 16 and 255, min(background ushr 8 and 255, background and 255))
        val neutralOffset = low * baseSpread - baseLow * spread
        if (neutralOffset < -3 * baseSpread || neutralOffset > 255 * (baseSpread - spread) + 3 * baseSpread) return false
        for (shift in 16 downTo 0 step 8) if (abs(((value ushr shift and 255) - low) * baseSpread -
                ((background ushr shift and 255) - baseLow) * spread) > baseSpread * 3) return false
        return true
    }

    private fun chroma(value: Int): Int {
        val r = value ushr 16 and 255; val g = value ushr 8 and 255; val b = value and 255
        return max(r, max(g, b)) - min(r, min(g, b))
    }
    private fun lightness(value: Int) = (54 * (value ushr 16 and 255) + 183 * (value ushr 8 and 255) + 19 * (value and 255)) ushr 8

    private fun mark(mask: LongArray, start: Int, end: Int) {
        var index = start
        while (index < end) {
            val size = min(64 - (index and 63), end - index)
            val bits = if (size == 64) -1L else ((1L shl size) - 1) shl (index and 63)
            mask[index ushr 6] = mask[index ushr 6] or bits
            index += size
        }
    }
}
