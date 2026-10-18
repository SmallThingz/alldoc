package app.smallthingz.alldoc.oacp

import android.os.CancellationSignal
import app.smallthingz.alldoc.engine.DocumentSession
import app.smallthingz.alldoc.core.takeScalarSafe
import app.smallthingz.alldoc.engine.SheetInfo
import app.smallthingz.alldoc.engine.SheetViewport
import app.smallthingz.alldoc.theme.ImageTreatment
import app.smallthingz.alldoc.theme.ReadingMode
import app.smallthingz.alldoc.theme.ThemeSettings
import java.util.Locale
import org.oacp.android.Oacp

internal data class CapabilitySheetRequest(val index: Int?, val x: Double, val y: Double, val scale: Double,
    val height: Int, val mode: ReadingMode, val images: ImageTreatment) {
    fun resolve(session: DocumentSession, width: Int, signal: CancellationSignal, systemDark: Boolean): CapabilitySheet {
        signal.throwIfCanceled()
        if (!session.info.supportsSheets)
            throw CapabilityFailure(Oacp.ERROR_UNSUPPORTED_STATE, "Worksheet view is unavailable for this document.")
        val book = session.workbook(signal)
            ?: throw CapabilityFailure(Oacp.ERROR_UNSUPPORTED_STATE, "Worksheet view is unavailable for this document.")
        val selected = if (index != null) book.sheets.firstOrNull { it.index == index }
            else book.sheets.firstOrNull { it.index == book.activeSheet } ?: book.sheets.firstOrNull()
        val sheet = selected ?: throw CapabilityFailure(Oacp.ERROR_INVALID_PARAMETERS, "Worksheet not found.")
        signal.throwIfCanceled()
        return CapabilitySheet(sheet, SheetViewport(sheet.index, x, y, scale, width, height).checked(),
            ThemeSettings(mode, images).resolved(systemDark), book.warnings)
    }
}

internal data class CapabilitySheet(val info: SheetInfo, val viewport: SheetViewport, val settings: ThemeSettings,
    val warnings: List<String>) {
    fun metadata(): Map<String, Any> = mapOf(
        "view" to "worksheet", "paginationKnown" to false,
        "sheet" to mapOf("index" to info.index, "name" to info.name.takeScalarSafe(256), "rowCount" to info.rowCount,
            "columnCount" to info.columnCount, "usedRows" to info.usedRows, "usedColumns" to info.usedColumns,
            "width" to info.width, "height" to info.height),
        "viewport" to mapOf("x" to viewport.x, "y" to viewport.y, "scale" to viewport.scale,
            "width" to viewport.width, "height" to viewport.height),
        "theme" to settings.mode.name.lowercase(Locale.ROOT), "imageTreatment" to if (settings.imageTreatment == ImageTreatment.RECOLOR) "all" else settings.imageTreatment.name.lowercase(Locale.ROOT))
}
