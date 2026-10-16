package app.smallthingz.alldoc.engine

import android.graphics.Bitmap
import android.os.CancellationSignal
import app.smallthingz.alldoc.core.MarkdownDocument
import app.smallthingz.alldoc.theme.ReadingMode
import app.smallthingz.alldoc.theme.ThemeSettings
import java.io.Closeable

data class DocumentInfo(
    val title: String,
    val mimeType: String,
    val format: String,
    val pageCount: Int,
    val textAvailable: Boolean,
    val fidelity: String,
    val warnings: List<String> = emptyList(),
    val reflowable: Boolean = false,
    val supportsMargins: Boolean = false,
    val supportsOutline: Boolean = false,
    val supportsNotes: Boolean = false,
    /** One semantic document, displayed as a virtualized flow rather than artificial pages. */
    val continuous: Boolean = false,
)

data class DocumentOutlineEntry(val title: String, val page: Int, val level: Int = 0)

/** Blocking operations: callers must use a worker and retain the session until completion. */
interface DocumentSession : Closeable {
    val info: DocumentInfo
    fun pageCount(signal: CancellationSignal): Int { signal.throwIfCanceled(); return info.pageCount }
    /** Height / width without rendering; formats without cheap geometry use a paper-shaped placeholder. */
    fun pageAspect(index: Int, signal: CancellationSignal): Float { signal.throwIfCanceled(); return 1.4142135f }
    fun outline(signal: CancellationSignal): List<DocumentOutlineEntry> { signal.throwIfCanceled(); return emptyList() }
    fun notes(signal: CancellationSignal): DocumentNotes { signal.throwIfCanceled(); return DocumentNotes() }
    fun bodyText(signal: CancellationSignal): String = text(signal)
    fun markdown(signal: CancellationSignal): MarkdownDocument? { signal.throwIfCanceled(); return null }
    fun text(signal: CancellationSignal): String
    fun json(signal: CancellationSignal): String
    fun renderPage(index: Int, width: Int, settings: ThemeSettings, signal: CancellationSignal): Bitmap
    fun renderPrintPage(index: Int, width: Int, signal: CancellationSignal): Bitmap =
        renderPage(index, width, ThemeSettings(ReadingMode.ORIGINAL), signal)
}
