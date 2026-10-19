package app.smallthingz.alldoc.theme

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import kotlin.math.abs

/** Static paper grain; one 64px shader is retained, independent of document/page count. */
internal object PaperTexture {
    private const val PAPER = 0xffF4ECD8.toInt()
    private var cachedRoughness = -1
    private var cachedPaint: Paint? = null

    /** Touch only recolored paper, never transparent pixels, text or preserved image tiles. */
    fun pixel(color: Int, x: Int, y: Int, roughness: Int): Int {
        val amount = roughness.coerceIn(0, 100)
        if (amount == 0 || color ushr 24 != 255) return color
        val r = color ushr 16 and 255
        val g = color ushr 8 and 255
        val b = color and 255
        if (abs(r - 244) > 8 || abs(g - 236) > 8 || abs(b - 216) > 8) return color
        var hash = (x and 63) * 73856093 xor ((y and 63) * 19349663)
        hash = (hash xor (hash ushr 13)) * 1274126177
        val grain = (((hash ushr 24) and 255) - 128) * amount / 1280
        return -0x1000000 or ((r + grain).coerceIn(0, 255) shl 16) or
            ((g + grain).coerceIn(0, 255) shl 8) or (b + grain).coerceIn(0, 255)
    }

    /** Prepare off the UI thread; callers must not mutate the shared paint or shader. */
    @Synchronized internal fun paint(roughness: Int): Paint {
        val amount = roughness.coerceIn(0, 100)
        if (cachedRoughness == amount) return checkNotNull(cachedPaint)
        val pixels = IntArray(64 * 64) { pixel(PAPER, it and 63, it ushr 6, amount) }
        val tile = Bitmap.createBitmap(pixels, 64, 64, Bitmap.Config.ARGB_8888)
        return Paint().apply {
            isFilterBitmap = false // Keep canonical grain texels stable when a detail region is magnified.
            shader = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        }.also {
            cachedPaint = it
            cachedRoughness = amount
        }
    }

    /** Map output pixels to canonical page coordinates so grain does not restart in a detail tile. */
    fun background(canvas: Canvas, width: Int, height: Int, settings: ThemeSettings,
        originX: Float = 0f, originY: Float = 0f, scaleX: Float = 1f, scaleY: Float = 1f) {
        require(originX.isFinite() && originY.isFinite() && scaleX.isFinite() && scaleY.isFinite() && scaleX > 0f && scaleY > 0f)
        if (settings.mode == ReadingMode.SEPIA && settings.paperRoughness > 0) {
            val saved = canvas.save()
            try {
                canvas.translate(-originX / scaleX, -originY / scaleY)
                canvas.scale(1f / scaleX, 1f / scaleY)
                canvas.drawRect(originX, originY, originX + width * scaleX, originY + height * scaleY, paint(settings.paperRoughness))
            } finally { canvas.restoreToCount(saved) }
        }
    }
}
