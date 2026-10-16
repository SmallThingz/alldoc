package app.smallthingz.alldoc.ui

import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import java.io.File
import java.util.UUID

internal fun writeReaderPdf(file: File, pages: Int = 2, marker: String = "AllDoc PDF fixture") {
    val nonce = UUID.randomUUID().toString()
    val document = PdfDocument()
    try {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 18f }
        repeat(pages) { index ->
            val page = document.startPage(PdfDocument.PageInfo.Builder(600, 840, index).create())
            page.canvas.drawColor(Color.WHITE)
            page.canvas.drawText("$marker ${index + 1}", 36f, 64f, paint)
            page.canvas.drawText("Readable PDF content", 36f, 100f, paint)
            page.canvas.drawText(nonce, 36f, 136f, paint)
            document.finishPage(page)
        }
        file.outputStream().use(document::writeTo)
    } finally { document.close() }
}
