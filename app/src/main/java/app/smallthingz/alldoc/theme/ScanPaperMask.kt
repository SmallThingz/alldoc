package app.smallthingz.alldoc.theme

import java.util.BitSet
import kotlin.math.max
import kotlin.math.min

/**
 * Conservative paper/ink selection inside ONE proven source-image footprint.
 * Page margins must not be included, and unknown image bounds are not evidence.
 * A null result preserves the existing image treatment. This is not OCR.
 */
internal class ScanPaperMask private constructor(
    val width: Int,
    val height: Int,
    private val selected: LongArray,
) {
    fun contains(x: Int, y: Int): Boolean =
        x >= 0 && x < width && y >= 0 && y < height &&
            (y * width + x).let { bit -> bit / 64 < selected.size && selected[bit / 64] and (1L shl (bit and 63)) != 0L }

    val storageBytes: Long get() = selected.size * 8L + 64

    /** Copy a bounded row slice; no bitset operation may scan a later row's suffix. */
    fun markRow(y: Int, destination: LongArray, offset: Int, retain: Boolean = false) {
        if (y !in 0 until height) return
        val start = y * width
        val end = start + width
        var next = start
        while (next < end) {
            val shift = next and 63
            val count = min(64 - shift, end - next)
            val word = next ushr 6
            val coverage = -1L ushr (64 - count)
            val bits = (if (word < selected.size) selected[word] ushr shift else 0L) and coverage
            val target = offset + next - start
            val targetWord = target ushr 6
            val targetShift = target and 63
            val head = bits shl targetShift
            destination[targetWord] = if (retain) destination[targetWord] and (head or (coverage shl targetShift).inv())
                else destination[targetWord] or head
            if (targetShift + count > 64) {
                val tail = bits ushr (64 - targetShift)
                destination[targetWord + 1] = if (retain) destination[targetWord + 1] and
                    (tail or (coverage ushr (64 - targetShift)).inv()) else destination[targetWord + 1] or tail
            }
            next += count
        }
    }

    companion object {
        const val MAX_PIXELS = 8_000_000
        const val MAX_AUXILIARY_BYTES = 4L * 1024 * 1024
        private const val STACK_CAPACITY = 8192
        private const val INK_RUN_CAPACITY = 4096
        private const val MAX_RUNS = 262_144
        private const val MAX_POPS = MAX_RUNS * 4

        // Three detector bitsets plus the final packed copy, one row and fixed traversal storage.
        fun auxiliaryBytes(width: Int, height: Int): Long =
            ((width.toLong() * height + 63) / 64) * 8 * 4 + width.toLong() * 4 +
                STACK_CAPACITY * 4L + INK_RUN_CAPACITY * 8L + 1024

        /** The row callback fills the reusable buffer with untouched ARGB pixels. */
        fun detect(width: Int, height: Int, readRow: (Int, IntArray) -> Unit,
            checkCancelled: () -> Unit = {}): ScanPaperMask? {
            checkCancelled()
            if (width < 16 || height < 16) return null
            val count = width.toLong() * height
            if (count > MAX_PIXELS) return null
            if (auxiliaryBytes(width, height) > MAX_AUXILIARY_BYTES) return null
            return Detector(width, height, count.toInt(), checkCancelled).detect(readRow)
        }
    }

    private class Detector(val width: Int, val height: Int, val count: Int,
        val checkCancelled: () -> Unit) {
        // available starts as near-white pixels, then retains connected paper.
        // blockedInk starts as chromatic pixels, then also tracks visited ink.
        val available = BitSet(count)
        val selected = BitSet(count)
        val blockedInk = BitSet(count)
        val stack = IntArray(STACK_CAPACITY)
        val inkRuns = IntArray(INK_RUN_CAPACITY * 2)
        var stackSize = 0
        var processedRuns = 0
        var pops = 0
        var paperCount = 0
        val inkWidth = max(16, width / 9)
        val inkHeight = max(8, width / 18)

        fun detect(readRow: (Int, IntArray) -> Unit): ScanPaperMask? {
            val row = IntArray(width)
            var edgePaper = 0
            var chromatic = false
            var seenEdges = 0L
            val perimeter = 2L * width + 2L * height - 4
            for (y in 0 until height) {
                checkCancelled()
                readRow(y, row)
                for (x in 0 until width) {
                    val color = row[x]
                    if (color ushr 24 != 255) return null
                    val r = color ushr 16 and 255
                    val g = color ushr 8 and 255
                    val b = color and 255
                    val chroma = max(r, max(g, b)) - min(r, min(g, b))
                    val index = y * width + x
                    if (chroma > 40) { chromatic = true; blockedInk.set(index) }
                    val lightness = (54 * r + 183 * g + 19 * b) ushr 8
                    if (chroma <= 6 && lightness >= 245) {
                        available.set(index)
                        if (y == 0 || y == height - 1 || x == 0 || x == width - 1) edgePaper++
                    }
                }
                seenEdges += if (y == 0 || y == height - 1) width.toLong() else 2L
                // Even entirely white unread edges cannot rescue this footprint.
                if ((edgePaper + perimeter - seenEdges) * 4 < perimeter * 3) return null
            }
            for (x in 0 until width) {
                if ((x and 255) == 0) checkCancelled()
                if (!flood(x, true) || !flood((height - 1) * width + x, true)) return null
            }
            for (y in 1 until height - 1) {
                if ((y and 255) == 0) checkCancelled()
                if (!flood(y * width, true) || !flood((y + 1) * width - 1, true)) return null
            }
            if (paperCount * 2L < count) return null
            checkCancelled()
            available.clear()
            available.or(selected)
            blockedInk.or(selected)
            var next = blockedInk.nextClearBit(0)
            while (next < count) {
                if (!flood(next, false)) return null
                next = blockedInk.nextClearBit(next)
            }
            // Revisit whole non-paper components, joining colored cores with their neutral
            // antialiasing. Only small components surrounded on ALL four paper sides qualify;
            // photographs and clipped/ambiguous color remain untouched. Reuse traversal storage.
            if (chromatic) {
                checkCancelled()
                blockedInk.clear()
                blockedInk.or(available)
                next = blockedInk.nextClearBit(0)
                while (next < count) {
                    if (!flood(next, false, 4)) return null
                    next = blockedInk.nextClearBit(next)
                }
            }
            checkCancelled()
            return ScanPaperMask(width, height, selected.toLongArray())
        }

        fun eligible(index: Int, paper: Boolean): Boolean =
            if (paper) available[index] else !blockedInk[index]

        // Local probes must stop at the current row/overlap, not scan a suffix
        // of the entire image before their result is clamped by the caller.
        fun nextEligible(start: Int, limit: Int, paper: Boolean): Int {
            var next = start
            while (next < limit) {
                if ((next and 1023) == 0) checkCancelled()
                if (eligible(next, paper)) break
                next++
            }
            return next
        }

        fun nextBlocked(start: Int, limit: Int, paper: Boolean): Int {
            var next = start
            while (next < limit) {
                if ((next and 1023) == 0) checkCancelled()
                if (!eligible(next, paper)) break
                next++
            }
            return next
        }

        fun previousBlocked(start: Int, limit: Int, paper: Boolean): Int {
            var previous = start
            while (previous >= limit) {
                if ((previous and 1023) == 0) checkCancelled()
                if (!eligible(previous, paper)) break
                previous--
            }
            return previous
        }

        fun pushNeighbors(start: Int, end: Int, offset: Int, paper: Boolean): Boolean {
            val limit = end + offset
            var next = nextEligible(start + offset, limit, paper)
            while (next < limit) {
                if (stackSize == stack.size) return false
                stack[stackSize++] = next
                val blocked = nextBlocked(next, limit, paper)
                if (blocked == limit) break
                next = nextEligible(blocked, limit, paper)
            }
            return true
        }

        // A clipped glyph can meet color on one side. A neutral detail along a
        // photo edge must not qualify merely because one side touches page paper.
        // Inspect only actual component runs against immutable connected paper.
        fun surroundedByPaper(runs: Int, left: Int, right: Int, top: Int, bottom: Int, requiredSides: Int): Boolean {
            var sides = 0
            for (run in 0 until runs) {
                if ((run and 127) == 0) checkCancelled()
                val start = inkRuns[run * 2]
                val end = inkRuns[run * 2 + 1]
                val y = start / width
                val rowStart = y * width
                if (start - rowStart == left && left > 0 && available[start - 1]) sides = sides or 1
                if (end - rowStart == right && right < width && available[end]) sides = sides or 2
                if (y == top && y > 0 && (sides and 4) == 0) {
                    for (pixel in start until end) {
                        if ((pixel and 1023) == 0) checkCancelled()
                        if (available[pixel - width]) { sides = sides or 4; break }
                    }
                }
                if (y + 1 == bottom && bottom < height && (sides and 8) == 0) {
                    for (pixel in start until end) {
                        if ((pixel and 1023) == 0) checkCancelled()
                        if (available[pixel + width]) { sides = sides or 8; break }
                    }
                }
                if (Integer.bitCount(sides) >= requiredSides) return true
            }
            return false
        }

        fun flood(seed: Int, paper: Boolean, requiredSides: Int = 3): Boolean {
            if (!eligible(seed, paper)) return true
            stackSize = 1
            stack[0] = seed
            var left = width
            var right = 0
            var top = height
            var bottom = 0
            var ink = !paper
            var savedRuns = 0
            while (stackSize > 0) {
                if ((pops and 127) == 0) checkCancelled()
                if (++pops > MAX_POPS) return false
                val index = stack[--stackSize]
                if (!eligible(index, paper)) continue
                if (++processedRuns > MAX_RUNS) return false
                val y = index / width
                val rowStart = y * width
                val start = previousBlocked(index, rowStart, paper) + 1
                val end = nextBlocked(index, rowStart + width, paper)
                if (paper) {
                    available.clear(start, end)
                    selected.set(start, end)
                    paperCount += end - start
                } else {
                    blockedInk.set(start, end)
                    left = min(left, start - rowStart)
                    right = max(right, end - rowStart)
                    top = min(top, y)
                    bottom = max(bottom, y + 1)
                    if (left == 0 || right == width || top == 0 || bottom == height ||
                        right - left > inkWidth || bottom - top > inkHeight ||
                        savedRuns == INK_RUN_CAPACITY) ink = false
                    if (ink) {
                        inkRuns[savedRuns * 2] = start
                        inkRuns[savedRuns * 2 + 1] = end
                        savedRuns++
                    }
                }
                if (y > 0 && !pushNeighbors(start, end, -width, paper)) return false
                if (y + 1 < height && !pushNeighbors(start, end, width, paper)) return false
            }
            if (ink) ink = surroundedByPaper(savedRuns, left, right, top, bottom, requiredSides)
            if (ink) for (run in 0 until savedRuns) {
                if ((run and 127) == 0) checkCancelled()
                selected.set(inkRuns[run * 2], inkRuns[run * 2 + 1])
            }
            return true
        }
    }
}

/** Packed range fill: at most two partial words, with full interior words. */
internal fun markBits(mask: LongArray, start: Int, end: Int, set: Boolean = true) {
    if (start >= end) return
    val first = start ushr 6
    val last = (end - 1) ushr 6
    val head = -1L shl (start and 63)
    val tail = -1L ushr (63 - ((end - 1) and 63))
    if (first == last) mask[first] = if (set) mask[first] or (head and tail) else mask[first] and (head and tail).inv()
    else {
        mask[first] = if (set) mask[first] or head else mask[first] and head.inv()
        for (word in first + 1 until last) mask[word] = if (set) -1L else 0L
        mask[last] = if (set) mask[last] or tail else mask[last] and tail.inv()
    }
}

