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
  private val binder =
    object : IPdfRenderer.Stub() {
      @Synchronized
      override fun open(file: ParcelFileDescriptor): Int {
        // Construct and validate first. A corrupt replacement must not destroy the open PDF.
        val next =
          try {
            PdfRenderer(file)
          } catch (failure: Exception) {
            file.close()
            throw failure
          }
        try {
          require(next.pageCount > 0) { "PDF has no pages" }
        } catch (failure: Exception) {
          next.close()
          throw failure
        }
        val previous = renderer
        renderer = next
        runCatching { previous?.close() }
        return next.pageCount
      }

      @Synchronized
      override fun render(index: Int, requestedWidth: Int): ParcelFileDescriptor {
        val current = renderer ?: error("No PDF open")
        require(index in 0 until current.pageCount)
        val bitmap =
          current.openPage(index).use { page ->
            val width = requestedWidth.coerceIn(320, 1440)
            val height =
              (width.toLong() * page.height / page.width.coerceAtLeast(1)).coerceIn(1, 2400).toInt()
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
              try {
                it.eraseColor(Color.WHITE)
                page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
              } catch (failure: Exception) {
                it.recycle()
                throw failure
              }
            }
          }
        val pipes =
          try {
            ParcelFileDescriptor.createPipe()
          } catch (failure: Exception) {
            bitmap.recycle()
            throw failure
          }
        Thread {
          try {
            ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use {
              bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
          } catch (_: Exception) {} finally {
            bitmap.recycle()
          }
        }
          .apply {
            name = "alldoc-pdf-page"
            isDaemon = true
          }
          .start()
        return pipes[0]
      }

      @Synchronized
      override fun close() {
        val previous = renderer
        renderer = null
        previous?.close()
      }
    }

  override fun onBind(intent: Intent) = binder

  override fun onDestroy() {
    binder.close()
    super.onDestroy()
  }
}
