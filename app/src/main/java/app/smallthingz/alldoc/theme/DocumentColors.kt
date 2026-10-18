package app.smallthingz.alldoc.theme

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Document treatment; independent of the surrounding app theme. */
// Persisted codes 0–4 and the API name ADAPTIVE remain unchanged.
enum class ReadingMode { ORIGINAL, ADAPTIVE, SEPIA, HIGH_CONTRAST, INVERT, ADAPTIVE_LIGHT, SYSTEM }

/** Codes 0/1 retain the old persisted preserve-images Boolean without a schema rewrite. */
enum class ImageTreatment(val code: Int) {
    AUTO(2), KEEP(1), DIM(3), RECOLOR(0);

    companion object {
        fun fromLegacy(preserve: Boolean) = if (preserve) KEEP else RECOLOR
        fun fromCode(code: Int) = entries.firstOrNull { it.code == code } ?: AUTO
    }
}

data class ThemeSettings(
    val mode: ReadingMode = ReadingMode.SYSTEM,
    val imageTreatment: ImageTreatment = ImageTreatment.AUTO,
    val paperRoughness: Int = 0,
) {
    /** The public IPC Boolean keeps its historical meaning. */
    constructor(mode: ReadingMode = ReadingMode.SYSTEM, preserveImages: Boolean) :
        this(mode, ImageTreatment.fromLegacy(preserveImages))

    fun normalized(): ThemeSettings = if (paperRoughness in 0..100) this else copy(paperRoughness = paperRoughness.coerceIn(0, 100))

    val preserveImages: Boolean get() = imageTreatment != ImageTreatment.RECOLOR
    fun resolved(dark: Boolean): ThemeSettings = if (mode == ReadingMode.SYSTEM)
        copy(mode = if (dark) ReadingMode.ADAPTIVE else ReadingMode.ADAPTIVE_LIGHT) else this
}

data class ReadingPalette(val background: Int, val foreground: Int, val secondary: Int, val link: Int)

object DocumentColors {
    // WCAG 2.2 relative luminance: linearized sRGB, weighted for human vision.
    // Shared 256-entry table keeps exponentiation out of raster loops.
    private val linear = DoubleArray(256) { value ->
        val c = value / 255.0
        if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    internal fun luminance(color: Int): Double = linear[color ushr 16 and 255] * 0.2126 +
        linear[color ushr 8 and 255] * 0.7152 + linear[color and 255] * 0.0722

    internal fun contrast(a: Int, b: Int): Double {
        val x = luminance(a); val y = luminance(b)
        return (max(x, y) + 0.05) / (min(x, y) + 0.05)
    }

    /** Adjust identified ink, never an unclassified pixel or a photograph. */
    internal fun contrastingInk(ink: Int, paper: Int, minimum: Double = 4.5): Int {
        if (contrast(ink, paper) >= minimum) return ink
        val black = 0xff000000.toInt()
        val white = -1
        val preferred = if (luminance(ink) < luminance(paper)) black else white
        val target = if (contrast(preferred, paper) >= minimum) preferred else if (preferred == black) white else black
        var low = 0
        var high = 255
        while (low < high) {
            val amount = (low + high) ushr 1
            if (contrast(blend(ink, target, amount), paper) >= minimum) high = amount else low = amount + 1
        }
        return blend(ink, target, high)
    }

    /** One bounded palette per identified ink family; no contrast calculations in raster loops. */
    internal fun inkRamp(ink: Int, paper: Int): IntArray? {
        if (contrast(ink, paper) >= 4.5) return null
        // inkCoverage accepts at most three channel levels of quantization error.
        val floor = DoubleArray(256) { amount ->
            val color = blend(paper, ink, amount)
            fun neighbor(delta: Int): Int {
                var result = color and -0x1000000
                for (shift in 16 downTo 0 step 8)
                    result = result or (((color ushr shift and 255) + delta).coerceIn(0, 255) shl shift)
                return result
            }
            if (amount == 0) 1.0 else max(contrast(neighbor(-3), paper), contrast(neighbor(3), paper))
        }
        val mapped = contrastingInk(ink, paper, max(4.5, floor.max()))
        val colors = IntArray(256) { blend(paper, mapped, it) }
        val contrasts = DoubleArray(256) { contrast(colors[it], paper) }
        var coverage = 0
        for (amount in colors.indices) {
            coverage = max(coverage, amount)
            while (coverage < 255 && contrasts[coverage] < floor[amount]) coverage++
            colors[amount] = colors[coverage]
        }
        return colors
    }

    internal fun blend(paper: Int, ink: Int, amount: Int): Int {
        var result = paper and -0x1000000
        for (shift in 16 downTo 0 step 8) {
            val background = paper ushr shift and 255
            val foreground = ink ushr shift and 255
            result = result or ((background * (255 - amount) + foreground * amount + 127) / 255 shl shift)
        }
        return result
    }

    /** Coverage of one opaque antialiased ink color on a known flat background, or -1. */
    internal fun inkCoverage(value: Int, paper: Int, ink: Int): Int {
        if (value == paper) return 0
        if (value == ink) return 255
        var axis = 0
        var distance = 0
        for (shift in 16 downTo 0 step 8) {
            val difference = (ink ushr shift and 255) - (paper ushr shift and 255)
            if (abs(difference) > abs(distance)) { axis = shift; distance = difference }
        }
        if (distance == 0) return if (value == paper) 0 else -1
        val denominator = abs(distance)
        val delta = ((value ushr axis and 255) - (paper ushr axis and 255)) * 255
        val numerator = if (distance < 0) -delta else delta
        val amount = Math.floorDiv(numerator * 2 + denominator, denominator * 2)
        if (amount !in 0..255) return -1
        val expected = blend(paper, ink, amount)
        for (shift in 16 downTo 0 step 8)
            if (abs((value ushr shift and 255) - (expected ushr shift and 255)) > 3) return -1
        return amount
    }

    fun palette(settings: ThemeSettings): ReadingPalette = when (settings.mode) {
        ReadingMode.ORIGINAL -> ReadingPalette(0xffFFFFFF.toInt(), 0xff202124.toInt(), 0xff5F6368.toInt(), 0xff005AC1.toInt())
        ReadingMode.ADAPTIVE_LIGHT, ReadingMode.SYSTEM -> ReadingPalette(0xffF7F8FA.toInt(), 0xff20242C.toInt(), 0xff545C69.toInt(), 0xff005AC1.toInt())
        ReadingMode.SEPIA -> ReadingPalette(0xffF4ECD8.toInt(), 0xff332719.toInt(), 0xff67583F.toInt(), 0xff174E82.toInt())
        ReadingMode.ADAPTIVE -> ReadingPalette(0xff12161C.toInt(), 0xffE8EAF0.toInt(), 0xffACB5C4.toInt(), 0xff92C5FF.toInt())
        ReadingMode.HIGH_CONTRAST, ReadingMode.INVERT -> ReadingPalette(0xff000000.toInt(), 0xffFFFFFF.toInt(), 0xffC7C7C7.toInt(), 0xff8FCEFF.toInt())
    }
}

/** Pure color kernel shared by the reader and exported page images. */
internal class PixelTheme(private val settings: ThemeSettings, private val transparentCanvas: Boolean = false) {
    private val palette = DocumentColors.palette(settings)
    private val swatches = IntArray(SWATCH_LIMIT * 3)
    private val neutral = if (settings.mode == ReadingMode.ORIGINAL || settings.mode == ReadingMode.INVERT)
        IntArray(0) else IntArray(256) { value ->
        val amount = value / 255.0
        var color = 0
        for (shift in 16 downTo 0 step 8) {
            val ink = palette.foreground ushr shift and 255
            val paper = palette.background ushr shift and 255
            color = color or ((ink + (paper - ink) * amount).roundToInt() shl shift)
        }
        color
    }

    fun color(argb: Int, darkPaper: Boolean = false, preserveColor: Boolean = false): Int {
        if (settings.mode == ReadingMode.ORIGINAL) return argb
        if (settings.mode == ReadingMode.INVERT) return argb xor 0x00ffffff
        val alpha = argb and -0x1000000
        if (alpha == 0) return argb
        val r = argb ushr 16 and 255
        val g = argb ushr 8 and 255
        val b = argb and 255
        if (preserveColor && max(r, max(g, b)) - min(r, min(g, b)) > 40) return argb
        val lightness = (54 * r + 183 * g + 19 * b) ushr 8
        val base = neutral[if (darkPaper) 255 - lightness else lightness]
        // Retain some hue in diagrams/colored text instead of making every mark gray.
        fun channel(shift: Int, original: Int): Int =
            ((base ushr shift and 255) + (original - lightness) / 2).coerceIn(0, 255)
        return alpha or (channel(16, r) shl 16) or (channel(8, g) shl 8) or channel(0, b)
    }

    /** Conservative paper/ink heuristic, not image-object detection or OCR. */
    fun tile(pixels: IntArray, stride: Int, left: Int, top: Int, width: Int, height: Int, backdrops: LongArray? = null, knownBackdrops: LongArray? = null): Tile =
        classify(pixels, stride, left, top, width, height, backdrops, knownBackdrops, false)

    private fun classify(pixels: IntArray, stride: Int, left: Int, top: Int, width: Int, height: Int,
        backdrops: LongArray?, knownBackdrops: LongArray?, full: Boolean): Tile {
        val light = settings.mode == ReadingMode.ADAPTIVE_LIGHT || settings.mode == ReadingMode.SYSTEM
        if (settings.mode == ReadingMode.INVERT) return Tile.RECOLOR
        var allKnown = knownBackdrops != null
        var samples = 0
        var transparent = 0
        var dark = 0
        var paper = 0
        var middle = 0
        var colorful = 0
        var neutralDark = 0
        var whitePaper = 0
        var neutralMiddle = 0
        var neutralInk = 0
        var flatNeutralInk = 0
        var flatInkColors = 0
        var colors = 0
        var chromaticColors = 0
        var flatSamples = 0
        var lightBackdrop = 0
        var tintedBackdrop = 0
        var darkBackdrop = 0
        val stepX = if (full) 1 else max(1, width / 16)
        val stepY = if (full) 1 else max(1, height / 16)
        for (y in top until top + height step stepY) {
            for (x in left until left + width step stepX) {
                val index = y * stride + x
                // Keep established masked-region statistics; confirmation is for unsegmented raster tiles.
                if (full && knownBackdrops != null && marked(knownBackdrops, index)) return keptTile()
                if (allKnown && !marked(requireNotNull(knownBackdrops), index)) allKnown = false
                if (backdrops != null && marked(backdrops, index)) continue
                val color = pixels[index]
                val alpha = color ushr 24
                if (alpha < 230) transparent++
                if (alpha == 0) continue
                val r = color ushr 16 and 255
                val g = color ushr 8 and 255
                val b = color and 255
                val lightness = (54 * r + 183 * g + 19 * b) ushr 8
                val chroma = max(r, max(g, b)) - min(r, min(g, b))
                samples++
                if (settings.preserveImages) {
                    val rgb = color and 0x00ffffff
                    colors = addSwatch(rgb, 0, colors)
                    if (chroma > 40) {
                        val automatic = settings.imageTreatment == ImageTreatment.AUTO
                        chromaticColors = addSwatch(if (automatic) rgb and 0x00c0c0c0 else rgb, SWATCH_LIMIT, chromaticColors)
                        if (automatic && x > 0 && x + 1 < stride &&
                            pixels[y * stride + x - 1] == color && pixels[y * stride + x + 1] == color) flatSamples++
                    }
                }
                if (settings.imageTreatment == ImageTreatment.AUTO && chroma <= 96) {
                    if (lightness >= 160) {
                        lightBackdrop++
                        if (lightness < 245 || chroma > 6) tintedBackdrop++
                    }
                    if (lightness < 96) darkBackdrop++
                    if (lightness >= 245 && chroma <= 6) whitePaper++
                    if (lightness < 245 && chroma <= 6) {
                        neutralInk++
                        if (x > 0 && x + 1 < stride && pixels[index - 1] == color && pixels[index + 1] == color) {
                            flatNeutralInk++
                            flatInkColors = addSwatch(color and 0x00ffffff, SWATCH_LIMIT * 2, flatInkColors)
                        }
                    }
                    if (lightness in 48..209 && chroma <= 40) neutralMiddle++
                }
                if (lightness >= 210) paper++
                if (lightness < 48) dark++
                if (lightness in 48..209) middle++
                if (chroma > 40) colorful++ else if (lightness < 48) neutralDark++
            }
        }
        if (allKnown || samples == 0 && backdrops != null) return Tile.DIAGRAM
        // Low-color opaque backgrounds can carry ink; photographs and transparent foreground art
        // stay on the conservative path. Flattened pages cannot expose actual image/text layers.
        if (settings.imageTreatment == ImageTreatment.AUTO && !transparentCanvas && transparent == 0 &&
            samples > 0 && colors <= SWATCH_LIMIT) {
            if (lightBackdrop * 100 >= samples * 80 && tintedBackdrop * 100 >= samples * 60) return Tile.RECOLOR
            if (light && darkBackdrop * 100 >= samples * 80) return Tile.DARK_PAPER
        }
        // Gray glyph cores are flat even when antialiasing exceeds the exact swatch limit.
        // Confirm neutral ink on substantial near-white paper, without loosening chromatic-photo
        // gates or treating textured neutral detail as a flat ink palette. Known backdrop
        // contexts retain their established masked-remainder policy.
        if (settings.imageTreatment == ImageTreatment.AUTO && !transparentCanvas && transparent == 0 &&
            knownBackdrops == null && samples > 0 && whitePaper * 100 >= samples * 55 && neutralInk + whitePaper == samples &&
            neutralInk > 0 && flatNeutralInk * 2 >= neutralInk && flatInkColors in 1..4) return Tile.RECOLOR
        // Flat colored diagrams share tiles with paper; keeping the whole tile leaves square halos.
        val diagram = settings.preserveImages && !transparentCanvas && transparent == 0 && colorful > 0 &&
            paper > 0 && neutralDark * 2 < samples
        // Multicolor emoji and colored text can exceed the flat-diagram swatch limit.
        // On mostly white paper, keep their chroma without keeping a rectangular paper tile.
        // Neutral midtones still distinguish photographic detail from paper and dark ink.
        if (settings.imageTreatment == ImageTreatment.AUTO && diagram && neutralMiddle * 100 <= samples * 10 &&
            (whitePaper * 100 >= samples * 60 ||
                // Flat shapes and their glyph antialiasing share a small coarse palette.
                // Require mostly flat neighbors too, so textured photographs stay preserved.
                whitePaper > 0 && chromaticColors <= SWATCH_LIMIT && flatSamples * 5 >= colorful * 3)) return Tile.DIAGRAM
        if (diagram && colors <= SWATCH_LIMIT) return Tile.DIAGRAM
        val transparentInk = transparentCanvas || transparent * 10 >= samples
        if (samples > 0 && colorful * 100 < samples * 18 && middle * 100 < samples * 18 &&
            ((light && !transparentInk && dark * 100 >= samples * 60) || (transparentInk && paper * 100 >= samples * 70)))
            return Tile.DARK_PAPER
        if (!settings.preserveImages) return Tile.RECOLOR
        if (samples > 0 && transparentInk && dark * 100 >= samples * 70 && colorful * 100 < samples * 18) return Tile.RECOLOR
        if (samples == 0 || paper * 100 < samples * 45 || middle * 100 > samples * 28 || colorful * 100 > samples * 18) {
            // A regular sample grid can align with scanned text strokes. Confirm ambiguous
            // light-paper tiles once using the same rules, without relaxing photo thresholds.
            if (!full && settings.imageTreatment == ImageTreatment.AUTO && !transparentCanvas && transparent == 0 &&
                samples > 0 && (paper * 100 >= samples * 45 && colorful * 100 <= samples * 18 ||
                    diagram && whitePaper * 100 >= samples * 60))
                return classify(pixels, stride, left, top, width, height, backdrops, knownBackdrops, true)
            return keptTile()
        }
        // After the photo gates, AUTO keeps chroma in sparse colored ink as well as flat shapes.
        // Explicit image policies retain their existing chromatic palette decision.
        return if (diagram && (settings.imageTreatment == ImageTreatment.AUTO || chromaticColors <= SWATCH_LIMIT))
            Tile.DIAGRAM else Tile.RECOLOR
    }

    /**
     * Preserve short ink/antialias runs bracketed by the same flat chromatic backdrop.
     * Compute from the original row before adapting any tile. One bracket needs four flat
     * pixels; its mate needs two, so thin margins work without treating single AA edges as
     * backdrops. Separate same-color objects
     * less than 64 pixels apart remain ambiguous in this raster-only heuristic.
     */
    internal fun backdropInkMask(pixels: IntArray, rowStart: Int, width: Int, mask: LongArray, scanLeft: Int = 0, scanRight: Int = width, backdrops: LongArray? = null) {
        mask.fill(0L)
        if (settings.imageTreatment != ImageTreatment.AUTO || transparentCanvas) return
        var previousColor = 0
        var previousEnd = -1
        // A target ink gap needs at most 64 pixels plus four flat anchor pixels.
        val limit = min(width, scanRight + 68)
        var start = max(0, scanLeft - 68)
        while (start < limit) {
            if (backdrops != null && marked(backdrops, rowStart + start)) {
                // Known plates preserve their own ink; never bridge exterior paper through them.
                do { start++ } while (start < limit && marked(backdrops, rowStart + start))
                previousColor = 0
                previousEnd = start
                continue
            }
            val value = pixels[rowStart + start]
            var end = start + 1
            while (end < limit && pixels[rowStart + end] == value && (backdrops == null || !marked(backdrops, rowStart + end))) end++
            val r = value ushr 16 and 255
            val g = value ushr 8 and 255
            val b = value and 255
            if (end - start >= 2 && value ushr 24 >= 230 && max(r, max(g, b)) - min(r, min(g, b)) > 40) {
                val strong = end - start >= 4
                var gap = if (previousEnd >= 0 && previousColor == value && start - previousEnd <= 64) previousEnd else -1
                if (gap < 0 && strong) {
                    // A narrow margin may precede several glyphs. Search only this gap,
                    // never across a different strong backdrop; gaps are disjoint per row.
                    var probe = max(max(0, start - 66), previousEnd)
                    while (probe + 1 < start) {
                        if (pixels[rowStart + probe] == value && pixels[rowStart + probe + 1] == value) {
                            var weakEnd = probe + 2
                            while (weakEnd < start && pixels[rowStart + weakEnd] == value) weakEnd++
                            if (start - weakEnd <= 64) { gap = weakEnd; break }
                            probe = weakEnd
                        } else probe++
                    }
                }
                if (gap >= 0) for (x in gap until start) mask[x ushr 6] = mask[x ushr 6] or (1L shl (x and 63))
                if (strong) { previousColor = value; previousEnd = end }
            }
            start = end
        }
    }

    internal fun backdropColor(argb: Int): Int =
        if (settings.mode == ReadingMode.ADAPTIVE || settings.mode == ReadingMode.HIGH_CONTRAST) argb
        else color(argb, darkPaper = true)

    /** Dimming keeps hue ordering and alpha; it never inverts photographic detail. */
    fun dim(argb: Int): Int {
        if (argb ushr 24 == 0) return argb
        val amount = if (settings.mode == ReadingMode.ADAPTIVE || settings.mode == ReadingMode.HIGH_CONTRAST) 192 else 224
        fun channel(shift: Int) = (((argb ushr shift and 255) * amount + 128) ushr 8) shl shift
        return (argb and -0x1000000) or channel(16) or channel(8) or channel(0)
    }

    private fun keptTile() = if (settings.imageTreatment == ImageTreatment.DIM) Tile.DIM else Tile.KEEP

    private fun addSwatch(rgb: Int, offset: Int, count: Int): Int {
        if (count > SWATCH_LIMIT) return count
        for (index in 0 until count) if (swatches[offset + index] == rgb) return count
        if (count < SWATCH_LIMIT) swatches[offset + count] = rgb
        return count + 1
    }

    fun preserveTile(pixels: IntArray, stride: Int, left: Int, top: Int, width: Int, height: Int) =
        tile(pixels, stride, left, top, width, height) == Tile.KEEP

    enum class Tile { RECOLOR, DARK_PAPER, DIAGRAM, KEEP, DIM }
    internal companion object {
        private const val SWATCH_LIMIT = 8
        fun marked(mask: LongArray, index: Int) = mask[index ushr 6] and (1L shl (index and 63)) != 0L
    }
}
