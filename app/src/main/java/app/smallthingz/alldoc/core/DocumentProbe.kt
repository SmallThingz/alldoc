package app.smallthingz.alldoc.core

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.URLConnection
import java.util.zip.ZipInputStream

/** Bounded content identification, not a parse or a promise that the whole document is valid. */
internal object DocumentProbe {
    data class Identification(val mime: String?, val inconclusive: Boolean = false)
    private val unsupported = Identification(null)
    private val incomplete = Identification(null, true)
    private const val PREFIX = 8192
    private const val ZIP_PREFIX = 64 * 1024
    private const val ZIP_EXPANDED = 128 * 1024
    private val ZIP = byteArrayOf(0x50, 0x4b, 3, 4)
    private val OLE = byteArrayOf(0xd0.toByte(), 0xcf.toByte(), 0x11, 0xe0.toByte(), 0xa1.toByte(), 0xb1.toByte(), 0x1a, 0xe1.toByte())
    private val textFormats = setOf("markdown", "html", "fictionbook")
    private val previewNames = setOf("QuickLook/Preview.pdf", "QuickLook/Thumbnail.jpg", "preview.jpg", "preview-micro.jpg", "preview-web.jpg")
    private val archives = setOf("word", "spreadsheet", "presentation", "opendocument", "epub", "iwork")
    private val compoundExtensions = setOf("doc", "dot", "xls", "ppt", "pps", "pot")
    private val officePart = Regex("""\bPartName\s*=\s*(['"])/(word/document\.xml|xl/workbook\.xml|ppt/presentation\.xml)\1""")
    private val officeMime = mapOf(
        "word/document.xml" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xl/workbook.xml" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "ppt/presentation.xml" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    )

    fun file(file: File, cancel: () -> Boolean): Identification = file.inputStream().use { input ->
        read({ bytes, offset, count -> input.read(bytes, offset, count) }, cancel)
    }

    /** Readers must bound blocking I/O themselves; cancellation is checked between every read. */
    fun read(read: (ByteArray, Int, Int) -> Int, cancel: () -> Boolean): Identification {
        var bytes = ByteArray(PREFIX)
        var used = 0
        while (true) {
            DocumentParser.check(cancel)
            if (used == bytes.size) {
                if (bytes.size == PREFIX && bytes.starts(ZIP)) bytes = bytes.copyOf(ZIP_PREFIX) else break
            }
            val count = read(bytes, used, bytes.size - used)
            if (count <= 0) break
            used += count
        }
        DocumentParser.check(cancel)
        return identify(bytes.copyOf(used), used == bytes.size, cancel)
    }

    fun mime(bytes: ByteArray, truncated: Boolean = false, cancel: () -> Boolean = { false }): String? =
        identify(bytes, truncated, cancel).mime

    fun identify(bytes: ByteArray, truncated: Boolean = false, cancel: () -> Boolean = { false }): Identification {
        DocumentParser.check(cancel)
        return if (bytes.starts(ZIP)) zip(bytes, truncated, cancel) else Identification(content(bytes, truncated))
    }

    private fun content(bytes: ByteArray, truncated: Boolean): String? {
        if (bytes.isEmpty()) return "text/plain"
        if (bytes.starts(OLE)) return "application/x-ole-storage"
        if (bytes.starts("%PDF-")) return "application/pdf"
        if (bytes.starts("{\\rtf")) return "application/rtf"
        if (bytes.starts("8BPS")) return "image/vnd.adobe.photoshop"
        if (bytes.starts("BM")) return "image/bmp"
        if (bytes.starts(byteArrayOf(0x49, 0x49, 42, 0)) || bytes.starts(byteArrayOf(0x4d, 0x4d, 0, 42))) return "image/tiff"
        if (bytes.size >= 12 && bytes.starts("RIFF") && bytes.starts("WEBP", 8)) return "image/webp"
        if (bytes.size >= 12 && bytes.starts("ftyp", 4)) {
            val brand = String(bytes, 8, 4, Charsets.US_ASCII)
            return when (brand) {
                "avif", "avis" -> "image/avif"
                "heic", "heix", "hevc", "hevx", "mif1", "msf1" -> "image/heif"
                else -> null
            }
        }
        URLConnection.guessContentTypeFromStream(ByteArrayInputStream(bytes))?.takeIf { it.startsWith("image/") }?.let { return it }
        val text = try { DocumentParser.decode(bytes, truncated).trimStart() } catch (_: IOException) { return null }
        val head = text.take(2048)
        return when {
            head.startsWith("<!DOCTYPE html", true) || head.startsWith("<html", true) -> "text/html"
            head.startsWith("BEGIN:VCALENDAR", true) -> "text/calendar"
            head.startsWith("BEGIN:VCARD", true) -> "text/vcard"
            head.startsWith("-----BEGIN CERTIFICATE-----") -> "application/x-pem-file"
            head.startsWith("<svg") || head.startsWith("<?xml") && head.contains("<svg") -> "image/svg+xml"
            head.startsWith("<FictionBook") || head.startsWith("<?xml") && head.contains("<FictionBook") -> "application/x-fictionbook+xml"
            head.startsWith("<?xml") || head.startsWith("<") && head.contains("urn:oasis:names:tc:opendocument:xmlns:office:1.0") -> "application/xml"
            else -> "text/plain"
        }
    }

    /** Strong content signatures override misleading extensions; plain text retains its useful syntax hint. */
    fun routingName(name: String, suppliedMime: String?, detectedMime: String?): String {
        val hint = DocumentFormats.routingName(name, suppliedMime)
        val kind = DocumentFormats.find(hint)?.id
        return when {
            detectedMime == "application/x-ole-storage" -> hint.takeIf { DocumentFormats.extension(it) in compoundExtensions }
                ?: DocumentFormats.routingName("", suppliedMime).takeIf { DocumentFormats.extension(it) in compoundExtensions }
                ?: "document.cfb" // The native parser validates the streams; a signature alone is not support.
            detectedMime == null -> hint
            detectedMime == "text/plain" && kind in textFormats -> hint
            detectedMime == "application/xml" && (kind in textFormats || kind == "opendocument") -> hint
            detectedMime == "application/vnd.apple.pages" && kind == "iwork" -> hint
            else -> DocumentFormats.routingName("", detectedMime).ifEmpty { name }
        }
    }

    /** Public document admission. Image signatures remain useful for rejecting misleading names. */
    fun documentName(name: String, suppliedMime: String?, detected: Identification): String? {
        if (detected.inconclusive) return archiveHint(name, suppliedMime)?.let {
            DocumentFormats.routingName("", it).takeIf(DocumentFormats::supportedName)
        }
        val mime = detected.mime ?: return null
        val hint = DocumentFormats.routingName(name, suppliedMime)
        val format = DocumentFormats.find(hint)
        return when (mime) {
            "text/plain" -> hint.takeIf { format?.id in textFormats }
            "application/xml", "text/xml" -> hint.takeIf {
                format?.id in textFormats || DocumentFormats.extension(it) in flatDocuments
            }
            "application/x-ole-storage" -> routingName(name, suppliedMime, mime)
                .takeIf { DocumentFormats.extension(it) in compoundExtensions }
            else -> routingName(name, suppliedMime, mime).takeIf {
                !mime.startsWith("image/") && DocumentFormats.supportedName(it) &&
                    DocumentFormats.routingName("", mime).isNotEmpty()
            }
        }
    }
    /** Explicit opens can validate containers beyond the cheap scan; neither route advertises a format. */
    internal fun openingName(name: String, suppliedMime: String?, detected: Identification): String? =
        documentName(name, suppliedMime, detected) ?: when {
            detected.inconclusive -> "document.zip" // Only a ZIP signature can produce an inconclusive probe.
            detected.mime == "application/x-ole-storage" -> "document.cfb"
            else -> null
        }

    private val flatDocuments = setOf("fodt", "fods", "fodp", "fodg")

    /** Metadata-only filtering for persisted candidates; bytes are still checked before opening. */
    fun supportedRecord(name: String, mime: String?): Boolean {
        val type = mime?.substringBefore(';')?.trim()?.lowercase(java.util.Locale.ROOT).orEmpty()
        return if (type.isEmpty() || type == "application/octet-stream") DocumentFormats.supportedName(name)
            else documentName(name, type, Identification(type)) != null
    }

    /** Older reading history stored provider MIME hints rather than verified content types. */
    fun supportedHistory(name: String, mime: String?): Boolean {
        val type = mime?.substringBefore(';')?.trim()?.lowercase(java.util.Locale.ROOT).orEmpty()
        return when (type) {
            "", "text/plain", "application/xml", "text/xml", "application/octet-stream" -> DocumentFormats.supportedName(name)
            else -> supportedRecord(name, type)
        }
    }

    /** An inconclusive archive may keep a compatible filename/provider hint, but never becomes PDF/image/text. */
    fun archiveHint(name: String, mime: String?): String? {
        val hint = name.takeIf { DocumentFormats.find(it)?.id in archives } ?: DocumentFormats.routingName("", mime)
        return DocumentFormats.mimeType(hint).takeIf { DocumentFormats.find(hint)?.id in archives }
    }

    private fun zip(bytes: ByteArray, truncated: Boolean, cancel: () -> Boolean): Identification {
        var expanded = 0
        var savedPreview = false
        return try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                val buffer = ByteArray(4096)
                repeat(32) {
                    DocumentParser.check(cancel)
                    // A saved preview preserves a compatible package hint; it never identifies an arbitrary ZIP as iWork.
                    val entry = zip.nextEntry ?: return if (truncated || savedPreview) incomplete else unsupported
                    val name = entry.name
                    if (name.length > 1024 || name.startsWith('/') || name.split('/').any { it == ".." }) return unsupported
                    officeMime[name]?.let { return Identification(it) }
                    if (name == "META-INF/container.xml") return Identification("application/epub+zip")
                    if (name == "Index/Document.iwa") return Identification("application/vnd.apple.pages")
                    var size = 0
                    val preview = if (name in previewNames) ByteArray(5) else null
                    val metadata = if (name == "mimetype" || name == "[Content_Types].xml") StringBuilder() else null
                    while (true) {
                        DocumentParser.check(cancel)
                        if (expanded == ZIP_EXPANDED) return incomplete
                        val count = zip.read(buffer, 0, minOf(buffer.size, ZIP_EXPANDED - expanded))
                        if (count <= 0) break
                        if (preview != null && size < preview.size)
                            buffer.copyInto(preview, size, 0, minOf(count, preview.size - size))
                        expanded += count; size += count
                        if (metadata != null && size <= 32 * 1024) metadata.append(String(buffer, 0, count, Charsets.UTF_8))
                    }
                    if (preview != null && if (name.endsWith(".pdf")) size >= 5 && preview.starts("%PDF-")
                        else size >= 3 && preview.starts(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte()))) savedPreview = true
                    val content = metadata?.toString().orEmpty().trim()
                    if (name == "mimetype" && (content == "application/epub+zip" ||
                            content.startsWith("application/vnd.oasis.opendocument.")) && DocumentFormats.routingName("", content).isNotEmpty())
                        return Identification(content)
                    if (name == "[Content_Types].xml") officePart.find(content)?.groupValues?.get(2)?.let {
                        return Identification(officeMime.getValue(it))
                    }
                }
                incomplete
            }
        } catch (_: IOException) { if (truncated) incomplete else unsupported }
        catch (_: IllegalArgumentException) { unsupported }
    }

    private fun ByteArray.starts(prefix: String, offset: Int = 0) = size >= offset + prefix.length &&
        prefix.indices.all { this[offset + it].toInt() and 255 == prefix[it].code }
    private fun ByteArray.starts(prefix: ByteArray) = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
}
