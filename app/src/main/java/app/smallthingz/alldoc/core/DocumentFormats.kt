package app.smallthingz.alldoc.core

import java.util.Locale

/** Capabilities describe this build, not promises about every feature in a file format. */
object DocumentFormats {
    data class Format(val id: String, val extensions: Set<String>, val support: String)
    private fun format(id: String, extensions: String, support: String = "text_structure") =
        Format(id, extensions.split(' ').toSet(), support)
    val all: List<Format> = listOf(
        format("pdf", "pdf", "pages; text extraction"),
        format("word", "docx docm dotx dotm doc dot"),
        format("spreadsheet", "xlsx xlsm xltx xltm xls"),
        format("presentation", "pptx pptm ppsx ppsm potx potm ppt pps pot"),
        format("opendocument", "odt ott odm oth ods ots odp otp odg otg fodt fods fodp fodg"),
        format("rtf", "rtf"), format("epub", "epub"), format("fictionbook", "fb2"),
        format("html", "html htm xhtml"), format("markdown", "md markdown", "rendered_document"),
        format("iwork", "pages numbers key", "embedded_preview_only"),
    )
    private val byExtension = all.flatMap { f -> f.extensions.map { it to f } }.toMap()
    fun extension(name: String): String = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    fun find(name: String): Format? = byExtension[extension(name)]
    private val mimeHints = mapOf(
        "application/pdf" to "pdf",
        "text/markdown" to "md",
        "application/msword" to "doc",
        "application/vnd.ms-excel" to "xls",
        "application/vnd.ms-powerpoint" to "ppt",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to "docx",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.template" to "dotx",
        "application/vnd.ms-word.document.macroenabled.12" to "docm",
        "application/vnd.ms-word.template.macroenabled.12" to "dotm",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to "xlsx",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.template" to "xltx",
        "application/vnd.ms-excel.sheet.macroenabled.12" to "xlsm",
        "application/vnd.ms-excel.template.macroenabled.12" to "xltm",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation" to "pptx",
        "application/vnd.openxmlformats-officedocument.presentationml.slideshow" to "ppsx",
        "application/vnd.openxmlformats-officedocument.presentationml.template" to "potx",
        "application/vnd.ms-powerpoint.presentation.macroenabled.12" to "pptm",
        "application/vnd.ms-powerpoint.slideshow.macroenabled.12" to "ppsm",
        "application/vnd.ms-powerpoint.template.macroenabled.12" to "potm",
        "application/vnd.oasis.opendocument.text" to "odt",
        "application/vnd.oasis.opendocument.text-template" to "ott",
        "application/vnd.oasis.opendocument.text-master" to "odm",
        "application/vnd.oasis.opendocument.text-web" to "oth",
        "application/vnd.oasis.opendocument.spreadsheet" to "ods",
        "application/vnd.oasis.opendocument.spreadsheet-template" to "ots",
        "application/vnd.oasis.opendocument.presentation" to "odp",
        "application/vnd.oasis.opendocument.presentation-template" to "otp",
        "application/vnd.oasis.opendocument.graphics" to "odg",
        "application/vnd.oasis.opendocument.graphics-template" to "otg",
        "application/epub+zip" to "epub",
        "text/html" to "html",
        "application/xhtml+xml" to "xhtml",
        "application/rtf" to "rtf",
        "text/rtf" to "rtf",
        "application/vnd.apple.pages" to "pages",
        "application/vnd.apple.numbers" to "numbers",
        "application/vnd.apple.keynote" to "key",
        "application/x-iwork-pages-sffpages" to "pages",
        "application/x-iwork-numbers-sffnumbers" to "numbers",
        "application/x-iwork-keynote-sffkey" to "key",
        "application/x-fictionbook+xml" to "fb2",
        "application/vnd.oasis.opendocument.text-flat-xml" to "fodt",
        "application/vnd.oasis.opendocument.spreadsheet-flat-xml" to "fods",
        "application/vnd.oasis.opendocument.presentation-flat-xml" to "fodp",
        "application/vnd.oasis.opendocument.graphics-flat-xml" to "fodg",
    )

    private val mimeByExtension = buildMap {
        for ((mime, ext) in mimeHints) putIfAbsent(ext, mime)
        for ((alias, original) in listOf("dot" to "doc", "pps" to "ppt", "pot" to "ppt", "htm" to "html", "markdown" to "md"))
            put(alias, getValue(original))
    }
    internal fun mimeType(name: String): String? = mimeByExtension[extension(name)]
    fun routingName(name: String, mime: String?): String = if (supportedName(name)) name else
        mimeHints[mime?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)]?.let { "document.$it" } ?: name
    fun supportedName(name: String): Boolean = find(name) != null
    // Some providers retain the IANA spelling of macroEnabled in otherwise lowercase MIME types.
    val intentMimeTypes: List<String> = (mimeHints.keys + mimeHints.keys.filter { "macroenabled" in it }.map { it.replace("macroenabled", "macroEnabled") }).toList()
    val pickerMimeTypes: Array<String> get() = (intentMimeTypes + listOf("text/plain", "application/xml", "text/xml", "application/octet-stream")).toTypedArray()
    fun isNative(name: String): Boolean = when (find(name)?.id) {
        "word", "spreadsheet", "presentation", "opendocument", "rtf", "epub" -> true
        else -> false
    }
    fun isPdf(name: String, mime: String?): Boolean = extension(name) == "pdf" || mime?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT) == "application/pdf"
}
