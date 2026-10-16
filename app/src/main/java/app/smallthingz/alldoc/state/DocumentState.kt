package app.smallthingz.alldoc.state

import app.smallthingz.alldoc.theme.ReadingMode
import app.smallthingz.alldoc.theme.ThemeSettings

/** Center coordinates are fractions of the unrotated document; rotation counts quarter turns. */
data class ViewportState(val zoom: Float = 1f, val centerX: Float = .5f, val centerY: Float = .5f, val rotation: Int = 0) {
    fun normalized() = copy(zoom = zoom.finite(1f).coerceIn(1f, 8f),
        centerX = centerX.finite(.5f).coerceIn(0f, 1f), centerY = centerY.finite(.5f).coerceIn(0f, 1f),
        rotation = ((rotation % 4) + 4) % 4)
}

data class TextPosition(val offset: Int = 0, val fraction: Float = 0f) {
    fun normalized() = copy(offset = offset.coerceAtLeast(0), fraction = fraction.finite(0f).coerceIn(0f, 1f))
}

enum class DocumentViewMode { PAGES, TEXT }

data class DocumentState(
    val page: Int = 0,
    val viewport: ViewportState = ViewportState(),
    val textPosition: TextPosition = TextPosition(),
    val mode: DocumentViewMode = DocumentViewMode.PAGES,
    val appearance: ThemeSettings = ThemeSettings(ReadingMode.SYSTEM),
    val hideMargins: Boolean = false,
    /** -1 marks a legacy zoomed page awaiting one-time viewport-to-scroll restoration. */
    val pageFraction: Float = 0f,
    val horizontalPages: Boolean = false,
    val notesVisible: Boolean = false,
) {
    fun normalized() = copy(page = page.coerceAtLeast(0), viewport = viewport.normalized(),
        textPosition = textPosition.normalized(), appearance = appearance.normalized(), pageFraction = if (pageFraction == -1f) -1f else pageFraction.finite(0f).coerceIn(0f, 1f))
}

/** Null source fields mean no new recoverable reference; they never erase an older reference. */
data class DocumentMetadata(val title: String, val mimeType: String?, val sizeBytes: Long,
    val sourceUri: String? = null, val importUri: String? = null)

data class StoredDocument(val hash: String, val metadata: DocumentMetadata, val state: DocumentState, val lastOpenedAt: Long) {
    val title: String get() = metadata.title
}

/** Enabled means retaining otherwise unrecoverable inputs, not copying every opened file. */
data class CopySettings(val enabled: Boolean = true, val maxBytes: Long = 256L * 1024 * 1024,
    val maxCount: Int = 1000, val treeUri: String? = null)

private fun Float.finite(fallback: Float) = if (isFinite()) this else fallback
