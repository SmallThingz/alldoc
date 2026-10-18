package app.smallthingz.alldoc.theme

import android.graphics.Bitmap
import android.os.CancellationSignal
import kotlin.math.ceil
import kotlin.math.min

object BitmapTheme {
    private const val TILE = 64

    /**
     * Call off the UI thread. ORIGINAL returns [bitmap]; other modes return a new
     * software bitmap. Never recycles or mutates the input. Ordinary processing uses
     * a 64-row pixel band; known vector ink adds an 80-row original context band and an
     * optional packed image mask. Scan analysis adds at most 4 MiB of auxiliary memory.
     * Raster classification is heuristic. Use ORIGINAL when exact colors matter.
     */
    fun apply(bitmap: Bitmap, settings: ThemeSettings, signal: CancellationSignal, rasterRegions: List<RasterRegion>? = null): Bitmap {
        signal.throwIfCanceled()
        if (settings.mode == ReadingMode.ORIGINAL) return bitmap
        require(bitmap.config != Bitmap.Config.HARDWARE) { "Theme processing needs a software bitmap" }
        val width = bitmap.width
        val height = bitmap.height
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            output.density = bitmap.density
            val pixels = IntArray(Math.multiplyExact(width, min(TILE, height)))
            val tileCount = (width - 1) / TILE + 1
            val treatments = Array(tileCount) { PixelTheme.Tile.KEEP }
            val inkMask = LongArray(tileCount)
            val roughness = if (settings.mode == ReadingMode.SEPIA) settings.paperRoughness.coerceIn(0, 100) else 0
            val sourceRegions = if (settings.imageTreatment == ImageTreatment.AUTO && settings.mode != ReadingMode.INVERT)
                RasterRegion.snapshot(rasterRegions) else null
            // A full-page image owns every pixel, so inferred backdrop masks cannot affect its output.
            // The existing alpha scan still disables source hints for transparent foreground art.
            val fullCanvasImage = sourceRegions?.singleOrNull()?.let {
                it.left == 0f && it.top == 0f && it.right == 1f && it.bottom == 1f
            } == true
            val regions = if (settings.imageTreatment == ImageTreatment.AUTO && settings.mode != ReadingMode.INVERT && !fullCanvasImage) {
                // Complete image bounds protect photos while neutral dark paper admits colored vector ink.
                BackdropRegions(width, allowNeutralColoredInk = settings.mode == ReadingMode.ADAPTIVE && sourceRegions != null).also { detector ->
                    for (top in 0 until height step TILE) {
                        signal.throwIfCanceled()
                        val rows = min(TILE, height - top)
                        bitmap.getPixels(pixels, 0, width, 0, top, width, rows)
                        detector.scan(pixels, rows, signal::throwIfCanceled)
                        if (detector.transparent) break
                    }
                    detector.finish()
                    detector.prepareInk()
                }
            } else null
            val transparent = regions?.transparent ?: (settings.mode != ReadingMode.INVERT && hasTransparency(bitmap, pixels, signal))
            // Complete image bounds on an opaque canvas replace photographic guesses. Unknown pages retain raster heuristics.
            // Transparent foreground art retains its existing polarity; explicit image policies are unchanged.
            val sourceBounds = if (!transparent)
                sourceRegions?.let { values -> IntArray(values.size * 4).also { bounds ->
                    values.forEachIndexed { index, value ->
                        bounds[index * 4] = imageEdge(value.left, width, false)
                        bounds[index * 4 + 1] = imageEdge(value.top, height, false)
                        bounds[index * 4 + 2] = imageEdge(value.right, width, true)
                        bounds[index * 4 + 3] = imageEdge(value.bottom, height, true)
                    }
                } } else null
            val sourceMask = if (sourceBounds != null && sourceBounds.isNotEmpty()) LongArray((pixels.size - 1) / 64 + 1) else null
            val scans = sourceBounds?.let { scanImages(bitmap, requireNotNull(sourceRegions), it, sourceMask?.size ?: 0, signal) }
            val scanMask = if (scans?.any { it != null } == true) LongArray(requireNotNull(sourceMask).size) else null
            val theme = PixelTheme(settings, transparent)
            val scanInk = if (scanMask != null) ScanInk(theme, settings) else null
            val coloredInk = if (settings.mode == ReadingMode.ADAPTIVE && sourceBounds != null && !fullCanvasImage)
                ColoredWhiteInk(width, theme, sourceBounds) else null
            val backdrops = if (regions != null && !regions.isEmpty) LongArray((pixels.size - 1) / 64 + 1) else null
            val neutralBackdrops = backdrops?.let { LongArray(it.size) }
            val mutedBackdrops = if (backdrops != null && (settings.mode == ReadingMode.ADAPTIVE_LIGHT || settings.mode == ReadingMode.SYSTEM))
                LongArray(backdrops.size) else null
            var lastDocumentColor = 0
            var lastDocumentMapped = 0
            for (top in 0 until height step TILE) {
                signal.throwIfCanceled()
                val rows = min(TILE, height - top)
                if (coloredInk != null) coloredInk.read(bitmap, top, rows, pixels)
                else bitmap.getPixels(pixels, 0, width, 0, top, width, rows)
                val bandBackdrops = if (backdrops != null && requireNotNull(regions).masks(top, rows, backdrops,
                    requireNotNull(neutralBackdrops), mutedBackdrops)) backdrops else null
                if (sourceMask != null) {
                    sourceMask.fill(0L)
                    for (at in requireNotNull(sourceBounds).indices step 4) {
                        val firstRow = maxOf(0, sourceBounds[at + 1] - top)
                        val lastRow = min(rows, sourceBounds[at + 3] - top)
                        for (row in firstRow until lastRow) markBits(sourceMask, row * width + sourceBounds[at], row * width + sourceBounds[at + 2])
                    }
                }
                if (scanMask != null) {
                    scanMask.fill(0L)
                    // A rounded boundary can belong to two adjoining source images. Recolor it only
                    // when every covering image selected that pixel; a photo or unknown layer wins.
                    val masks = requireNotNull(scans)
                    for (pass in 0..1) for (index in masks.indices) {
                        val retain = pass != 0
                        val scan = masks[index]
                        if (scan == null && !retain) continue
                        val at = index * 4
                        val bounds = requireNotNull(sourceBounds)
                        val firstRow = maxOf(0, bounds[at + 1] - top)
                        val lastRow = min(rows, bounds[at + 3] - top)
                        for (row in firstRow until lastRow) {
                            val offset = row * width + bounds[at]
                            if (scan != null) scan.markRow(top + row - bounds[at + 1], scanMask, offset, retain)
                            else markBits(scanMask, offset, row * width + bounds[at + 2], false)
                        }
                    }
                }
                // Classify the original band before mutation; ink brackets may cross tiles.
                var diagramLeft = width
                var diagramRight = 0
                for (tile in 0 until tileCount) {
                    signal.throwIfCanceled()
                    val left = tile * TILE
                    val treatment = if (sourceBounds != null) PixelTheme.Tile.RECOLOR else
                        theme.tile(pixels, width, left, 0, min(TILE, width - left), rows, if (bandBackdrops != null) neutralBackdrops else null, bandBackdrops)
                    treatments[tile] = treatment
                    if (treatment == PixelTheme.Tile.DIAGRAM) {
                        diagramLeft = min(diagramLeft, left)
                        diagramRight = maxOf(diagramRight, min(width, left + TILE))
                    }
                }
                for (row in 0 until rows) {
                    signal.throwIfCanceled()
                    val rowStart = row * width
                    if (diagramLeft < diagramRight) theme.backdropInkMask(pixels, rowStart, width, inkMask, diagramLeft, diagramRight, bandBackdrops)
                    coloredInk?.row(pixels, rowStart, top + row, sourceMask, bandBackdrops)
                    if (bandBackdrops != null) regions?.adaptInk(pixels, rowStart, top + row, sourceMask)
                    for (tile in 0 until tileCount) {
                        val treatment = treatments[tile]
                        if (treatment == PixelTheme.Tile.KEEP && bandBackdrops == null && sourceBounds == null) continue
                        val left = tile * TILE
                        val columns = min(TILE, width - left)
                        val darkPaper = treatment == PixelTheme.Tile.DARK_PAPER
                        val preserveColor = treatment == PixelTheme.Tile.DIAGRAM
                        val dim = treatment == PixelTheme.Tile.DIM
                        for (x in left until left + columns) {
                            val index = rowStart + x
                            val imagePixel = sourceMask != null && PixelTheme.marked(sourceMask, index)
                            if (imagePixel && (scanMask == null || !PixelTheme.marked(scanMask, index))) continue
                            if (!imagePixel && bandBackdrops != null && PixelTheme.marked(bandBackdrops, index)) {
                                if (PixelTheme.marked(requireNotNull(neutralBackdrops), index) || mutedBackdrops != null && PixelTheme.marked(mutedBackdrops, index)) {
                                    val mapped = theme.backdropColor(pixels[index])
                                    pixels[index] = if (roughness > 0) PaperTexture.pixel(mapped, x, top + row, roughness) else mapped
                                }
                                continue
                            }
                            val documentPixel = sourceBounds != null
                            if (!documentPixel && (treatment == PixelTheme.Tile.KEEP || preserveColor && (inkMask[tile] and (1L shl (x and 63))) != 0L)) continue
                            val mapped = if (imagePixel) requireNotNull(scanInk).color(pixels[index])
                                else if (documentPixel) {
                                    val corrected = coloredInk?.colors?.get(x) ?: 0
                                    if (corrected != 0) corrected else {
                                        val original = pixels[index]
                                        if (original != lastDocumentColor) {
                                            lastDocumentColor = original
                                            lastDocumentMapped = theme.color(original)
                                        }
                                        lastDocumentMapped
                                    }
                                }
                                else if (dim) theme.dim(pixels[index]) else theme.color(pixels[index], darkPaper, preserveColor)
                            pixels[index] = if (roughness > 0 && !dim) PaperTexture.pixel(mapped, x, top + row, roughness) else mapped
                        }
                    }
                }
                output.setPixels(pixels, 0, width, 0, top, width, rows)
            }
            signal.throwIfCanceled()
            return output
        } catch (error: Throwable) {
            output.recycle()
            throw error
        }
    }

    /** White vector ink surrounded by a flat colored fill; complete image masks are required. */
    private class ColoredWhiteInk(private val width: Int, private val theme: PixelTheme, private val imageBounds: IntArray) {
        val colors = IntArray(width)
        // Keep context separate from the output band: earlier rows have already been recolored.
        private val context = IntArray(Math.multiplyExact(width, TILE + 16))
        private val contextImages = if (imageBounds.isNotEmpty()) LongArray((context.size - 1) / 64 + 1) else null
        private var contextTop = 0
        private var contextRows = 0
        private val papers = IntArray(64)
        private val ramps = arrayOfNulls<IntArray>(papers.size)
        private var count = 0
        private var next = 0

        fun read(bitmap: Bitmap, top: Int, rows: Int, pixels: IntArray) {
            contextTop = maxOf(0, top - 8)
            contextRows = min(bitmap.height, top + rows + 8) - contextTop
            bitmap.getPixels(context, 0, width, 0, contextTop, width, contextRows)
            val first = (top - contextTop) * width
            context.copyInto(pixels, 0, first, first + rows * width)
            contextImages?.let { mask ->
                mask.fill(0L)
                for (at in imageBounds.indices step 4) {
                    val firstRow = maxOf(0, imageBounds[at + 1] - contextTop)
                    val lastRow = min(contextRows, imageBounds[at + 3] - contextTop)
                    for (row in firstRow until lastRow)
                        markBits(mask, row * width + imageBounds[at], row * width + imageBounds[at + 2])
                }
            }
        }

        fun row(pixels: IntArray, offset: Int, y: Int, images: LongArray?, backdrops: LongArray?) {
            colors.fill(0)
            var start = 0
            while (start < width) {
                val paper = pixels[offset + start]
                var end = start + 1
                while (end < width && pixels[offset + end] == paper) end++
                val r = paper ushr 16 and 255
                val g = paper ushr 8 and 255
                val b = paper and 255
                if (end - start >= 2 && paper ushr 24 == 255 && maxOf(r, g, b) - minOf(r, g, b) > 40) {
                    if (!excluded(offset + end - 2, images, backdrops) && !excluded(offset + end - 1, images, backdrops))
                        span(pixels, offset, y, paper, end, 1, images, backdrops)
                    if (!excluded(offset + start, images, backdrops) && !excluded(offset + start + 1, images, backdrops))
                        span(pixels, offset, y, paper, start - 1, -1, images, backdrops)
                }
                start = end
            }
        }

        private fun excluded(index: Int, images: LongArray?, backdrops: LongArray?) =
            images != null && PixelTheme.marked(images, index) || backdrops != null && PixelTheme.marked(backdrops, index)

        private fun span(pixels: IntArray, offset: Int, y: Int, paper: Int, first: Int, step: Int,
            images: LongArray?, backdrops: LongArray?) {
            if (first !in 0 until width) return
            var trailingPaper = 0
            var hasUncorrectedInk = false
            var hasWhiteCore = false
            // A chart may cover one end of a glyph. Examine either exposed fill anchor,
            // stopping at an opaque vector edge; image/backdrop/alpha edges never qualify.
            for (distance in 0 until min(128, if (step > 0) width - first else first + 1)) {
                val x = first + distance * step
                val index = offset + x
                if (excluded(index, images, backdrops) || pixels[index] ushr 24 != 255) return
                val coverage = DocumentColors.inkCoverage(pixels[index], paper, -1)
                if (coverage > 0) {
                    if (colors[x] == 0) hasUncorrectedInk = true
                    if (coverage == 255) hasWhiteCore = true
                    trailingPaper = 0
                }
                else if (coverage == 0) trailingPaper++
                if (coverage < 0 || trailingPaper == 2) {
                    if (hasUncorrectedInk && supported(paper, first, x, step, y) &&
                        (coverage >= 0 || hasWhiteCore || distance <= 2 || verticallyBounded(paper, first, x - step, y)))
                        ramp(paper)?.let { ramp ->
                        for (at in 0 until distance) {
                            val ink = first + at * step
                            val amount = DocumentColors.inkCoverage(pixels[offset + ink], paper, -1)
                            if (amount > 0 && colors[ink] == 0) colors[ink] = ramp[amount]
                        }
                    }
                    return
                }
            }
        }

        // An open, partially covered span may instead be a translucent white panel ending
        // at another shape. Wider spans need an ink column bounded by the same original fill
        // above and below; one/two-pixel AA stems and opaque cores retain their direct evidence.
        private fun verticallyBounded(paper: Int, first: Int, last: Int, y: Int): Boolean {
            val left = min(first, last)
            val right = maxOf(first, last)
            // At most nine columns and sixteen neighbors per column, using the existing halo.
            for (sample in 0..8) {
                val x = left + (right - left) * sample / 8
                val index = (y - contextTop) * width + x
                if (DocumentColors.inkCoverage(context[index], paper, -1) <= 0) continue
                if (reachesPaper(paper, x, y, -1) && reachesPaper(paper, x, y, 1)) return true
            }
            return false
        }

        private fun reachesPaper(paper: Int, x: Int, y: Int, step: Int): Boolean {
            for (distance in 1..8) {
                val row = y + distance * step
                if (row !in contextTop until contextTop + contextRows) return false
                val index = (row - contextTop) * width + x
                val value = context[index]
                if (value ushr 24 != 255 || contextImages != null && PixelTheme.marked(contextImages, index)) return false
                if (value == paper) return true
                if (DocumentColors.inkCoverage(value, paper, -1) <= 0) return false
            }
            return false
        }

        private fun supported(paper: Int, first: Int, last: Int, step: Int, y: Int): Boolean {
            // Use the exposed anchor side, not the other layer beyond an occluding edge.
            val l = maxOf(0, if (step > 0) first - 8 else last + 1)
            val r = min(width, if (step > 0) last else first + 8)
            val top = maxOf(contextTop, y - 8)
            val bottom = min(contextTop + contextRows, y + 9)
            val red = paper ushr 16 and 255
            val green = paper ushr 8 and 255
            val blue = paper and 255
            var total = 0
            var fill = 0
            var exact = 0
            var white = 0
            // At most 81 original samples per span, including AA-only rows. Image and
            // nonopaque pixels cannot provide evidence of surrounding flat vector fill.
            for (row in top until bottom step 2) for (sample in 0..8) {
                val x = l + (r - l - 1) * sample / 8
                val index = (row - contextTop) * width + x
                val value = context[index]
                total++
                if (value ushr 24 != 255 || contextImages != null && PixelTheme.marked(contextImages, index)) continue
                // Context tolerates raster quantization; the row's two-pixel anchor stays exact.
                if ((value ushr 16 and 255) - red in -3..3 && (value ushr 8 and 255) - green in -3..3 &&
                    (value and 255) - blue in -3..3) {
                    fill++
                    if (value == paper) exact++
                } else if (value == -1) white++
            }
            // A thin quantized shape edge must not borrow a different dominant fill as its paper.
            return total > 0 && fill * 3 >= total && fill >= white * 2 && exact * 2 >= fill
        }

        private fun ramp(paper: Int): IntArray? {
            for (index in 0 until count) if (papers[index] == paper) return ramps[index]
            val ramp = DocumentColors.inkRamp(theme.color(-1), theme.color(paper))
            // Capacity limits retention, never whether a qualifying glyph is corrected.
            papers[next] = paper
            ramps[next] = ramp
            next = (next + 1) % papers.size
            if (count < papers.size) count++
            return ramp
        }
    }

    private fun imageEdge(value: Float, size: Int, upper: Boolean): Int {
        val position = value.toDouble() * size
        val nearest = kotlin.math.round(position)
        // Normalized metadata is Float: only snap when its rounding interval contains an integer.
        // This makes .1f at width 1000 meet at pixel 100, without snapping genuine subpixel edges.
        val snapped = if (kotlin.math.abs(position - nearest) <= Math.ulp(value).toDouble() * size * .5) nearest else position
        return (if (upper) ceil(snapped) else kotlin.math.floor(snapped)).toInt().coerceIn(0, size)
    }

    private fun scanImages(bitmap: Bitmap, regions: List<RasterRegion>, bounds: IntArray, bandWords: Int,
        signal: CancellationSignal): Array<ScanPaperMask?> {
        val scans = arrayOfNulls<ScanPaperMask>(regions.size)
        var pixelsLeft = ScanPaperMask.MAX_PIXELS.toLong()
        // Shared across all retained masks and the extra band, not a fresh budget per image.
        var bytesLeft = ScanPaperMask.MAX_AUXILIARY_BYTES - bandWords * 8L - RasterRegion.LIMIT * 64L - ScanInk.CACHE_BYTES
        for (at in bounds.indices step 4) {
            signal.throwIfCanceled()
            val left = bounds[at]; val top = bounds[at + 1]
            val right = bounds[at + 2]; val bottom = bounds[at + 3]
            val width = right - left; val height = bottom - top
            val count = width.toLong() * height
            if (width < 16 || height < 16 || count > pixelsLeft ||
                ScanPaperMask.auxiliaryBytes(width, height) > bytesLeft) continue
            // Compare authored geometry: fractional adjacent edges may share a raster pixel
            // without either source image being drawn over the other.
            val region = regions[at / 4]
            var overlap = false
            for (other in regions.indices) {
                val value = regions[other]
                if (other != at / 4 && region.left < value.right && region.right > value.left &&
                    region.top < value.bottom && region.bottom > value.top) { overlap = true; break }
            }
            if (overlap) continue
            pixelsLeft -= count
            val mask = ScanPaperMask.detect(width, height, { y, row ->
                bitmap.getPixels(row, 0, width, left, top + y, width, 1)
            }, signal::throwIfCanceled) ?: continue
            bytesLeft -= mask.storageBytes
            scans[at / 4] = mask
        }
        return scans
    }

    // Ink can fill a whole tile. Its transparent canvas still distinguishes it from dark paper.
    private fun hasTransparency(bitmap: Bitmap, pixels: IntArray, signal: CancellationSignal): Boolean {
        if (!bitmap.hasAlpha()) return false
        for (top in 0 until bitmap.height step TILE) {
            signal.throwIfCanceled()
            val rows = min(TILE, bitmap.height - top)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, top, bitmap.width, rows)
            for (index in 0 until bitmap.width * rows) if (pixels[index] ushr 24 < 230) return true
        }
        return false
    }
}
