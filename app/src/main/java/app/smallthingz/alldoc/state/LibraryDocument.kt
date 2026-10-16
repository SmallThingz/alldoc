package app.smallthingz.alldoc.state

import app.smallthingz.alldoc.core.DocumentFormats
import app.smallthingz.alldoc.core.DocumentProbe
import java.nio.ByteBuffer
import java.util.Locale

/** Stable codes are persisted; group order is deliberately independent of filename order. */
enum class LibraryCategory(val code: Int, val label: String) {
    DOCUMENTS(0, "Documents"), PRESENTATIONS(1, "Presentations"), SPREADSHEETS(2, "Spreadsheets"),
    BOOKS(3, "Books"), TEXT(4, "Text"), CODE(5, "Code"), IMAGES(6, "Images"), MAIL(7, "Mail"), OTHER(8, "Other");
    val key: String get() = name.lowercase(Locale.ROOT)
    companion object {
        val available: List<LibraryCategory> = listOf(DOCUMENTS, PRESENTATIONS, SPREADSHEETS, BOOKS)
        private val categories = buildMap {
            for (format in DocumentFormats.all) {
                val category = when (format.id) {
                    "presentation" -> PRESENTATIONS
                    "spreadsheet" -> SPREADSHEETS
                    "epub", "fictionbook" -> BOOKS
                    else -> DOCUMENTS
                }
                format.extensions.forEach { put(it, category) }
            }
            for ((category, extensions) in listOf(
                PRESENTATIONS to "odp otp fodp key", SPREADSHEETS to "ods ots fods numbers",
            )) extensions.split(' ').forEach { put(it, category) }
        }
        fun of(name: String, mime: String?): LibraryCategory {
            val type = mime?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT).orEmpty()
            val routed = if (type.isEmpty() || type == "application/octet-stream") name.takeIf(DocumentFormats::supportedName) ?: return OTHER
                else DocumentProbe.documentName(name, mime, DocumentProbe.Identification(type)) ?: return OTHER
            return categories[DocumentFormats.extension(routed)] ?: OTHER
        }
        fun fromCode(code: Int): LibraryCategory = entries.firstOrNull { it.code == code } ?: OTHER
    }
}

data class LibraryQuery(val text: String = "", val category: LibraryCategory? = null,
    val excluded: Set<LibraryCategory> = emptySet())

/** Local stat identity includes ctime; a provider's advertised date/size is never a hash cache key. */
data class LibraryStamp(val device: Long, val inode: Long, val bytes: Long,
    val modifiedSeconds: Long, val modifiedNanos: Long, val changedSeconds: Long, val changedNanos: Long) {
    internal fun encode(): ByteArray = ByteBuffer.allocate(56).apply {
        putLong(device); putLong(inode); putLong(bytes); putLong(modifiedSeconds); putLong(modifiedNanos)
        putLong(changedSeconds); putLong(changedNanos)
    }.array()
    companion object {
        internal fun decode(value: ByteArray?): LibraryStamp? = value?.takeIf { it.size == 56 }?.let {
            ByteBuffer.wrap(it).run { LibraryStamp(long, long, long, long, long, long, long) }
        }
    }
}

/** A missing hash denotes a candidate, not verified content. Several locations can share one hash. */
data class LibraryDocument(
    val id: Long, val hash: String?, val title: String, val mimeType: String?, val sizeBytes: Long,
    val uri: String, val modifiedAt: Long, val revision: Long,
    val category: LibraryCategory = LibraryCategory.of(title, mimeType),
    val stamp: LibraryStamp? = null,
)

data class LibraryFile(val uri: String, val title: String, val mimeType: String?, val sizeBytes: Long,
    val modifiedAt: Long, val stamp: LibraryStamp? = null)

/** Bounded rows from one catalog generation, including an empty page. */
data class LibraryPage(val generation: Long, val rows: List<LibraryDocument>)
