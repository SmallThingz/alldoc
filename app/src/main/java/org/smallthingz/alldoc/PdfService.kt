package org.smallthingz.alldoc

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor

/** Parsing runs in an Android isolated UID, without app storage or network permissions. */
class PdfService : Service() {
    private var renderer: PdfRenderer? = null
    private val binder = object : IPdfRenderer.Stub() {
        @Synchronized override fun open(file: ParcelFileDescriptor): Int {
            renderer?.close()
            renderer = PdfRenderer(file)
            return renderer!!.pageCount
        }
        @Synchronized override fun render(index: Int, requestedWidth: Int): ParcelFileDescriptor {
            val current = renderer ?: error("No PDF open")
            require(index in 0 until current.pageCount)
            val page = current.openPage(index)
            val width = requestedWidth.coerceIn(320, 1440)
            val height = (width.toLong() * page.height / page.width.coerceAtLeast(1)).coerceIn(1, 2400).toInt()
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try { bitmap.eraseColor(Color.WHITE); page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
            catch (e: Exception) { bitmap.recycle(); throw e }
            finally { page.close() }
            val pipes = ParcelFileDescriptor.createPipe()
            Thread {
                try { ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                catch (_: Exception) {} finally { bitmap.recycle() }
            }.start()
            return pipes[0]
        }
        @Synchronized override fun close() { renderer?.close(); renderer = null }
    }
    override fun onBind(intent: Intent) = binder
    override fun onDestroy() { binder.close(); super.onDestroy() }
}
