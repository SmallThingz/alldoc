package app.smallthingz.alldoc.engine

import android.graphics.Bitmap
import android.os.CancellationSignal
import app.smallthingz.alldoc.core.MarkdownDocument
import app.smallthingz.alldoc.theme.RasterRegion
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
    val supportsSheets: Boolean = false,
)

data class DocumentOutlineEntry(val title: String, val page: Int, val level: Int = 0)

/** Blocking operations: callers must use a worker and retain the session until completion. */
interface DocumentSession : Closeable {
    val info: DocumentInfo
    /** Text/JSON fidelity may differ from the original-layout page renderer. No extraction is performed. */
    val textInfo: DocumentInfo get() = info
    fun workbook(signal: CancellationSignal): WorkbookInfo? { signal.throwIfCanceled(); return null }
    fun sheetGeometry(index: Int, signal: CancellationSignal): SheetGeometry? { signal.throwIfCanceled(); return null }
    fun renderSheet(viewport: SheetViewport, settings: ThemeSettings, signal: CancellationSignal): Bitmap =
        throw UnsupportedOperationException("Worksheet view unavailable")
    fun sheetRasterRegions(viewport: SheetViewport, signal: CancellationSignal): List<RasterRegion>? {
        signal.throwIfCanceled(); return null
    }
    /** Worker-only; release optional resident state before export or when the reader is hidden. */
    fun pauseSheets() = Unit
    /** Resolve print pagination on a worker; worksheet opening may deliberately leave pageCount at zero. */
    fun printInfo(signal: CancellationSignal): DocumentInfo {
        val count = pageCount(signal)
        return info.copy(pageCount = count)
    }
    fun pageCount(signal: CancellationSignal): Int { signal.throwIfCanceled(); return info.pageCount }
    /** Height / width without rendering; formats without cheap geometry use a paper-shaped placeholder. */
    fun pageAspect(index: Int, signal: CancellationSignal): Float { signal.throwIfCanceled(); return 1.4142135f }
    /** Complete normalized raster enclosures, at most RasterRegion.LIMIT; null if unknown or unsupported.
     * Query on a worker after rendering when the format derives hints from its display list. */
    fun rasterRegions(index: Int, signal: CancellationSignal): List<RasterRegion>? { signal.throwIfCanceled(); return null }
    fun outline(signal: CancellationSignal): List<DocumentOutlineEntry> { signal.throwIfCanceled(); return emptyList() }
    fun notes(signal: CancellationSignal): DocumentNotes { signal.throwIfCanceled(); return DocumentNotes() }
    fun bodyText(signal: CancellationSignal): String = text(signal)
    fun markdown(signal: CancellationSignal): MarkdownDocument? { signal.throwIfCanceled(); return null }
    fun text(signal: CancellationSignal): String
    fun json(signal: CancellationSignal): String
    fun renderPage(index: Int, width: Int, settings: ThemeSettings, signal: CancellationSignal): Bitmap
    /** Optional original-color top viewport for a continuous document; never a cropped export. */
    fun renderCover(index: Int, width: Int, height: Int, signal: CancellationSignal): Bitmap? { signal.throwIfCanceled(); return null }
    fun renderPrintPage(index: Int, width: Int, signal: CancellationSignal): Bitmap =
        renderPage(index, width, ThemeSettings(ReadingMode.ORIGINAL), signal)
}
