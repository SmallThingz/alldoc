package app.smallthingz.alldoc.theme

import kotlin.math.max
import kotlin.math.min

/** Only for small ink components already isolated from a proven scanned page's images. */
internal class ScanInk(private val theme: PixelTheme, settings: ThemeSettings) {
    // PaperTexture adds at most this many channel levels; only sepia uses grain.
    private val grain = if (settings.mode == ReadingMode.SEPIA) settings.paperRoughness.coerceIn(0, 100) / 10 else 0
    private val white = theme.color(-1)
    private val shade = theme.color(0xfff5f5f5.toInt())
    private val lowPaper = DocumentColors.luminance(paperBound(false))
    private val highPaper = DocumentColors.luminance(paperBound(true))
    private val target = if (contrast(0xff000000.toInt()) >= contrast(-1)) 0xff000000.toInt() else -1
    private val maximum = contrast(target)
    private val colors = IntArray(CACHE_BYTES / 4)

    // Accepted source paper has lightness245..255 and channel spread≤6. The kernel's
    // channel residual is therefore at most3, around its neutral245..255 endpoints.
    private fun paperBound(upper: Boolean): Int {
        var result = 0xff000000.toInt()
        for (shift in 16 downTo 0 step 8) {
            val a = white ushr shift and 255
            val b = shade ushr shift and 255
            val value = if (upper) max(a, b) + 3 + grain else min(a, b) - 3 - grain
            result = result or (value.coerceIn(0, 255) shl shift)
        }
        return result
    }

    private fun contrast(color: Int): Double {
        val r = color ushr 16 and 255; val g = color ushr 8 and 255; val b = color and 255
        val textured = grain > 0 && kotlin.math.abs(r - 244) <= 8 &&
            kotlin.math.abs(g - 236) <= 8 && kotlin.math.abs(b - 216) <= 8
        fun shifted(delta: Int): Int = 0xff000000.toInt() or
            ((r + delta).coerceIn(0, 255) shl 16) or ((g + delta).coerceIn(0, 255) shl 8) or (b + delta).coerceIn(0, 255)
        val lowInk = DocumentColors.luminance(if (textured) shifted(-grain) else color)
        val highInk = if (textured) DocumentColors.luminance(shifted(grain)) else lowInk
        return when {
            highInk < lowPaper -> (lowPaper + .05) / (highInk + .05)
            lowInk > highPaper -> (lowInk + .05) / (highPaper + .05)
            else -> 1.0
        }
    }

    fun color(source: Int): Int {
        val red = source ushr 16 and 255
        val green = source ushr 8 and 255
        val blue = source and 255
        val chroma = max(red, max(green, blue)) - min(red, min(green, blue))
        val lightness = (54 * red + 183 * green + 19 * blue) ushr 8
        // Keep neutral AA, detected paper and its near-white fringe unchanged.
        if (source ushr 24 != 255 || chroma == 0 || chroma <= 6 && lightness >= 245) return theme.color(source)
        val slot = ((source * 0x9e3779b9.toInt()) ushr 24) * 2
        if (colors[slot] == source) return colors[slot + 1]
        val mapped = theme.color(source)
        // Every accepted source-paper channel is≥239. Bound both luminance ends,
        // including pale ink brighter than off-white paper. Some source contrast
        // cannot fit the chosen reading palette; cap at its best feasible endpoint.
        val required = min(maximum, max(DocumentColors.contrast(source, -1),
            DocumentColors.contrast(source, 0xffefefef.toInt())))
        var low = 0
        var high = if (contrast(mapped) >= required) 0 else 255
        while (low < high) {
            val amount = (low + high) ushr 1
            if (contrast(DocumentColors.blend(mapped, target, amount)) >= required) high = amount else low = amount + 1
        }
        val result = DocumentColors.blend(mapped, target, high)
        colors[slot] = source
        colors[slot + 1] = result
        return result
    }

    companion object { const val CACHE_BYTES = 256 * 2 * 4 }
}
