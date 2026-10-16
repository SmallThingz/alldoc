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
        return Paint().apply { shader = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT) }.also {
            cachedPaint = it
            cachedRoughness = amount
        }
    }

    /** Call before semantic text is drawn. The Canvas is still in output-pixel coordinates. */
    fun background(canvas: Canvas, width: Int, height: Int, settings: ThemeSettings) {
        if (settings.mode == ReadingMode.SEPIA && settings.paperRoughness > 0)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint(settings.paperRoughness))
    }
}
