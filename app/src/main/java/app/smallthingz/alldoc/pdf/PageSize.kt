package app.smallthingz.alldoc.pdf

import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sqrt

/** Render dimensions are bounded independently of document-supplied page geometry. */
internal data class PageSize(val width: Int, val height: Int) {
    companion object {
        const val MAX_WIDTH = 4096
        const val MAX_EDGE = 8192
        const val MAX_PIXELS = 8_000_000

        fun fit(pageWidth: Int, pageHeight: Int, requestedWidth: Int): PageSize {
            require(pageWidth > 0 && pageHeight > 0) { "Invalid PDF page dimensions" }
            require(requestedWidth in 1..MAX_WIDTH) { "Preview width must be 1–$MAX_WIDTH" }
            val scale = min(
                requestedWidth.toDouble() / pageWidth,
                min(MAX_EDGE.toDouble() / pageHeight, sqrt(MAX_PIXELS.toDouble() / pageWidth / pageHeight)),
            )
            return PageSize(
                floor(pageWidth * scale).toInt().coerceAtLeast(1),
                floor(pageHeight * scale).toInt().coerceAtLeast(1),
            )
        }
    }
}
