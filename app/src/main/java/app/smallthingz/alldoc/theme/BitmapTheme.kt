package app.smallthingz.alldoc.theme

import android.graphics.Bitmap
import android.os.CancellationSignal
import kotlin.math.min

object BitmapTheme {
    private const val TILE = 64

    /**
     * Call off the UI thread. ORIGINAL returns [bitmap]; other modes return a new
     * software bitmap. Never recycles or mutates the input. Temporary pixel memory
     * is limited to a 64-row band plus one original row for Auto. Raster image preservation is heuristic: use
     * ORIGINAL when exact colors matter, especially for scans and photographs.
     */
    fun apply(bitmap: Bitmap, settings: ThemeSettings, signal: CancellationSignal): Bitmap {
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
            val regions = if (settings.imageTreatment == ImageTreatment.AUTO && settings.mode != ReadingMode.INVERT) {
                BackdropRegions(width).also { detector ->
                    for (top in 0 until height step TILE) {
                        signal.throwIfCanceled()
                        val rows = min(TILE, height - top)
                        bitmap.getPixels(pixels, 0, width, 0, top, width, rows)
                        detector.scan(pixels, rows, signal::throwIfCanceled)
                        if (detector.transparent) break
                    }
                    detector.finish()
                }
            } else null
            val transparent = regions?.transparent ?: (settings.mode != ReadingMode.INVERT && hasTransparency(bitmap, pixels, signal))
            val theme = PixelTheme(settings, transparent)
            val backdrops = if (regions != null && !regions.isEmpty) LongArray((pixels.size - 1) / 64 + 1) else null
            val neutralBackdrops = backdrops?.let { LongArray(it.size) }
            val mutedBackdrops = if (backdrops != null && (settings.mode == ReadingMode.ADAPTIVE_LIGHT || settings.mode == ReadingMode.SYSTEM))
                LongArray(backdrops.size) else null
            for (top in 0 until height step TILE) {
                signal.throwIfCanceled()
                val rows = min(TILE, height - top)
                bitmap.getPixels(pixels, 0, width, 0, top, width, rows)
                val bandBackdrops = if (backdrops != null && requireNotNull(regions).masks(top, rows, backdrops,
                    requireNotNull(neutralBackdrops), mutedBackdrops)) backdrops else null
                // Classify the original band before mutation; ink brackets may cross tiles.
                var diagramLeft = width
                var diagramRight = 0
                for (tile in 0 until tileCount) {
                    signal.throwIfCanceled()
                    val left = tile * TILE
                    val treatment = theme.tile(pixels, width, left, 0, min(TILE, width - left), rows, if (bandBackdrops != null) neutralBackdrops else null, bandBackdrops)
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
                    for (tile in 0 until tileCount) {
                        val treatment = treatments[tile]
                        if (treatment == PixelTheme.Tile.KEEP && bandBackdrops == null) continue
                        val left = tile * TILE
                        val columns = min(TILE, width - left)
                        val darkPaper = treatment == PixelTheme.Tile.DARK_PAPER
                        val preserveColor = treatment == PixelTheme.Tile.DIAGRAM
                        val dim = treatment == PixelTheme.Tile.DIM
                        for (x in left until left + columns) {
                            val index = rowStart + x
                            if (bandBackdrops != null && PixelTheme.marked(bandBackdrops, index)) {
                                if (PixelTheme.marked(requireNotNull(neutralBackdrops), index) || mutedBackdrops != null && PixelTheme.marked(mutedBackdrops, index)) {
                                    val mapped = theme.backdropColor(pixels[index])
                                    pixels[index] = if (roughness > 0) PaperTexture.pixel(mapped, x, top + row, roughness) else mapped
                                }
                                continue
                            }
                            if (treatment == PixelTheme.Tile.KEEP || preserveColor && (inkMask[tile] and (1L shl (x and 63))) != 0L) continue
                            val mapped = if (dim) theme.dim(pixels[index]) else theme.color(pixels[index], darkPaper, preserveColor)
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
