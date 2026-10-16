package app.smallthingz.alldoc.engine

import android.os.CancellationSignal
import android.os.SystemClock
import android.util.Xml
import app.smallthingz.alldoc.core.jsonString
import app.smallthingz.alldoc.core.takeScalarSafe
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.zip.CRC32
import java.util.zip.ZipFile

/** A page is zero-based and present only when it maps to the rendered document. */
data class DocumentNote(val text: String, val kind: String = "comment", val author: String = "",
    val location: String = "", val page: Int? = null)

data class DocumentNotes(val entries: List<DocumentNote> = emptyList(), val truncated: Boolean = false,
    val warnings: List<String> = emptyList()) {
    fun appendTo(json: String): String {
        if (entries.isEmpty() && !truncated && warnings.isEmpty()) return json
        fun whitespace(char: Char) = char == ' ' || char == '\t' || char == '\r' || char == '\n'
        val first = json.indexOfFirst { !whitespace(it) }
        val last = json.indexOfLast { !whitespace(it) }
        require(first >= 0 && json[first] == '{' && json[last] == '}')
        var content = first + 1
        while (content < last && whitespace(json[content])) content++
        return buildString(json.length + 256) {
            append(json, 0, last)
            if (content < last) append(',')
            append("\"notes\":[")
            entries.forEachIndexed { index, note ->
                if (index > 0) append(',')
                append("{\"kind\":"); jsonString(note.kind); append(",\"text\":"); jsonString(note.text)
                if (note.author.isNotEmpty()) { append(",\"author\":"); jsonString(note.author) }
                if (note.location.isNotEmpty()) { append(",\"location\":"); jsonString(note.location) }
                note.page?.let { append(",\"page\":").append(it) }; append('}')
            }
            append("],\"notesTruncated\":").append(truncated).append(",\"notesWarnings\":[")
            warnings.forEachIndexed { index, warning -> if (index > 0) append(','); jsonString(warning) }
            append("]}"); append(json, last + 1, json.length)
        }
    }

    fun appendToText(text: String): String {
        if (entries.isEmpty() && !truncated && warnings.isEmpty()) return text
        return buildString {
            append(text); append("\n\nNotes\n")
            for (note in entries) {
                val label = listOf(note.location, note.author).filter(String::isNotBlank).joinToString(" · ")
                if (label.isNotEmpty()) appendLine(label)
                appendLine(note.text); appendLine()
            }
            if (truncated) appendLine("[Remaining notes omitted.]")
            warnings.forEach { append('[').append(it).appendLine(']') }
        }.trimEnd()
    }
}

/** Keeps annotation I/O lazy; thumbnails and opening a document do not parse notes. */
internal fun withDocumentNotes(delegate: DocumentSession, file: File): DocumentSession {
    if (!OfficeNotes.supports(delegate.info.format)) return delegate
    return object : DocumentSession by delegate {
        override val info = delegate.info.copy(supportsNotes = true)
        private var cached: DocumentNotes? = null
        private var closed = false
        @Synchronized override fun notes(signal: CancellationSignal): DocumentNotes {
            signal.throwIfCanceled(); check(!closed) { "Document is closed" }
            cached?.let { return it }
            val parsed = OfficeNotes.read(file, info.format, signal)
            val result = if (info.reflowable) parsed.copy(entries = parsed.entries.map { it.copy(page = null) }) else parsed
            signal.throwIfCanceled()
            return result.also { cached = it }
        }
        override fun bodyText(signal: CancellationSignal) = delegate.bodyText(signal)
        override fun text(signal: CancellationSignal) = notesForExport(signal).appendToText(delegate.text(signal))
        override fun json(signal: CancellationSignal) = notesForExport(signal).appendTo(delegate.json(signal))
        @Synchronized override fun close() { closed = true; cached = null; delegate.close() }
    }
}

internal fun DocumentSession.notesForExport(signal: CancellationSignal): DocumentNotes = try { notes(signal) } catch (error: Exception) {
    signal.throwIfCanceled()
    if (error is android.os.OperationCanceledException || error is java.util.concurrent.CancellationException ||
        error is InterruptedException || Thread.currentThread().isInterrupted) throw error
    DocumentNotes(warnings = listOf("Notes could not be read."))
}

/** OOXML relationships and ODF annotations only; no extraction to disk or external resource access. */
internal object OfficeNotes {
    private val formats = setOf("DOCX", "XLSX", "PPTX", "ODT", "ODS", "ODP", "ODG", "FODT", "FODS", "FODP", "FODG")
    fun supports(format: String) = format.uppercase(java.util.Locale.ROOT) in formats

    fun read(file: File, format: String, signal: CancellationSignal): DocumentNotes {
        signal.throwIfCanceled()
        val reader = Reader(signal)
        try {
            val zipped = file.inputStream().use { it.read() == 'P'.code && it.read() == 'K'.code }
            if (zipped) ZipFile(file).use { zip ->
                val seen = HashSet<String>()
                val members = zip.entries()
                while (members.hasMoreElements()) {
                    reader.check()
                    val name = members.nextElement().name
                    if (seen.size == 4096 || name.length > 1024) throw NoteLimit()
                    if (!seen.add(name)) throw IOException("Duplicate document part")
                }
                reader.zip = zip
                when (format.uppercase()) {
                    "DOCX" -> reader.word()
                    "XLSX" -> reader.spreadsheet()
                    "PPTX" -> reader.presentation()
                    else -> reader.odf(reader.part("content.xml"), format)
                }
            } else if (format.uppercase().startsWith("OD") || format.uppercase().startsWith("FOD")) {
                if (file.length() > PART_BYTES) throw NoteLimit()
                reader.odf(file.inputStream().use { reader.bytes(it) }, format)
            }
        } catch (_: NoteLimit) {
            reader.truncated = true
        } catch (error: XmlPullParserException) {
            throw IOException("Document notes contain malformed XML", error)
        }
        signal.throwIfCanceled()
        return DocumentNotes(reader.notes, reader.truncated)
    }

    private const val PART_BYTES = 2 * 1024 * 1024
    private const val TEXT_LIMIT = 512 * 1024
    private const val WORD = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val DRAWING = "http://schemas.openxmlformats.org/drawingml/2006/main"
    private const val PRESENTATION = "http://schemas.openxmlformats.org/presentationml/2006/main"
    private const val SPREADSHEET = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private const val OFFICE = "urn:oasis:names:tc:opendocument:xmlns:office:1.0"
    private const val TEXT = "urn:oasis:names:tc:opendocument:xmlns:text:1.0"
    private const val ODF_PRESENTATION = "urn:oasis:names:tc:opendocument:xmlns:presentation:1.0"
    private const val TABLE = "urn:oasis:names:tc:opendocument:xmlns:table:1.0"
    private const val ODF_DRAWING = "urn:oasis:names:tc:opendocument:xmlns:drawing:1.0"
    private const val DC = "http://purl.org/dc/elements/1.1/"
    private class NoteLimit : IOException()
    private data class Relation(val id: String, val target: String, val type: String)
    private class Reader(val signal: CancellationSignal) {
        lateinit var zip: ZipFile
        val notes = ArrayList<DocumentNote>()
        var truncated = false
        private var characters = 0
        private var inflated = 0
        private var events = 0
        private val deadline = SystemClock.uptimeMillis() + 10_000
        fun check() { signal.throwIfCanceled(); if (SystemClock.uptimeMillis() > deadline) throw NoteLimit() }
        fun bytes(input: java.io.InputStream): ByteArray {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                check()
                val count = input.read(buffer)
                if (count < 0) break
                inflated += count
                if (output.size() + count > PART_BYTES || inflated > 16 * 1024 * 1024) throw NoteLimit()
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }
        fun part(name: String): ByteArray {
            val entry = zip.getEntry(name) ?: throw IOException("Missing document notes part")
            if (entry.size > PART_BYTES) throw NoteLimit()
            val data = zip.getInputStream(entry).use(::bytes)
            if (entry.size >= 0 && data.size.toLong() != entry.size || entry.crc >= 0 && CRC32().apply { update(data) }.value != entry.crc)
                throw IOException("Damaged document notes part")
            return data
        }
        fun xml(data: ByteArray, visit: (XmlPullParser, Int) -> Unit) {
            val parser = Xml.newPullParser().apply { setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true); setInput(ByteArrayInputStream(data), null) }
            while (true) {
                if (++events > 200_000) throw NoteLimit()
                if (events % 64 == 0) check()
                val event = parser.nextToken()
                if (event == XmlPullParser.END_DOCUMENT) return
                if (parser.depth > 64) throw NoteLimit()
                if (event == XmlPullParser.DOCDECL || event == XmlPullParser.ENTITY_REF && parser.text == null)
                    throw IOException("Document notes cannot contain entities or a DTD")
                visit(parser, event)
            }
        }
        fun add(text: String, kind: String = "comment", author: String = "", location: String = "", page: Int? = null) {
            val clean = text.trim()
            if (clean.isEmpty()) return
            if (notes.size == 2048 || characters == TEXT_LIMIT) throw NoteLimit()
            val value = clean.takeScalarSafe(minOf(16 * 1024, TEXT_LIMIT - characters))
            if (value.isEmpty()) throw NoteLimit()
            truncated = truncated || value.length < clean.length
            characters += value.length
            notes.add(DocumentNote(value, kind, author.takeScalarSafe(128), location.takeScalarSafe(256), page))
        }
        private fun append(text: StringBuilder, value: String) {
            val part = value.takeScalarSafe((16 * 1024 - text.length).coerceAtLeast(0))
            text.append(part)
            if (part.length < value.length && value.isNotBlank()) truncated = true
        }
        private fun XmlPullParser.attribute(name: String): String = (0 until attributeCount).firstOrNull { getAttributeName(it) == name }?.let { getAttributeValue(it) }.orEmpty()
        private fun XmlPullParser.ns(namespace: String): Boolean = this.namespace == namespace || this.namespace == when (namespace) {
            WORD -> "http://purl.oclc.org/ooxml/wordprocessingml/main"
            DRAWING -> "http://purl.oclc.org/ooxml/drawingml/main"
            PRESENTATION -> "http://purl.oclc.org/ooxml/presentationml/main"
            SPREADSHEET -> "http://purl.oclc.org/ooxml/spreadsheetml/main"
            else -> namespace
        }
        private fun textEvent(event: Int) = event == XmlPullParser.TEXT || event == XmlPullParser.CDSECT || event == XmlPullParser.ENTITY_REF
        private fun path(base: String, target: String): String {
            val uri = try { URI(target) } catch (error: Exception) { throw IOException("Invalid document relationship", error) }
            if (uri.isAbsolute || uri.rawAuthority != null || uri.rawQuery != null || uri.rawFragment != null) throw IOException("External document notes are unsupported")
            val targetPath = uri.path ?: throw IOException("Invalid document relationship")
            if (targetPath.length > 1024 || targetPath.any { it < ' ' || it == '\\' || it == ':' }) throw IOException("Invalid document relationship")
            val parts = if (targetPath.startsWith('/')) ArrayList<String>() else ArrayList(base.substringBeforeLast('/', "").split('/').filter(String::isNotEmpty))
            for (part in targetPath.split('/')) when (part) {
                "", "." -> Unit
                ".." -> if (parts.isEmpty()) throw IOException("Document relationship escapes package") else parts.removeAt(parts.lastIndex)
                else -> parts.add(part)
            }
            if (parts.isEmpty()) throw IOException("Empty document relationship")
            return parts.joinToString("/")
        }
        private fun relations(base: String): List<Relation> {
            val name = base.substringBeforeLast('/', "") + "/_rels/" + base.substringAfterLast('/') + ".rels"
            if (zip.getEntry(name) == null) return emptyList()
            val result = ArrayList<Relation>()
            val ids = HashSet<String>()
            xml(part(name)) { p, event ->
                if (event == XmlPullParser.START_TAG && p.name == "Relationship" && (p.namespace == "http://schemas.openxmlformats.org/package/2006/relationships" || p.namespace == "http://purl.oclc.org/ooxml/package/relationships")) {
                    if (result.size == 4096) throw NoteLimit()
                    val id = p.attribute("Id")
                    if (id.isEmpty() || id.length > 128 || !ids.add(id)) throw IOException("Invalid relationship ID")
                    if (p.attribute("TargetMode") != "External") result.add(Relation(id, path(base, p.attribute("Target")), p.attribute("Type").substringAfterLast('/')))
                }
            }
            return result
        }
        fun word() {
            val relationships = relations("word/document.xml")
            val parts = relationships.filter { it.type == "comments" }
            if (parts.isEmpty()) return
            val ids = HashSet<String>()
            xml(part("word/document.xml")) { p, event ->
                if (event == XmlPullParser.START_TAG && p.ns(WORD) && (p.name == "commentReference" || p.name == "commentRangeStart")) {
                    if (ids.size == 4096) throw NoteLimit()
                    ids.add(p.attribute("id"))
                }
            }
            wordReplies(relationships, ids)
            for (relationship in parts) comments(relationship.target, emptyMap(), "", null, ids)
        }
        private fun wordReplies(relationships: List<Relation>, ids: MutableSet<String>) {
            val replies = HashMap<String, MutableList<String>>()
            val children = HashSet<String>()
            fun paragraph(value: String): String {
                if (value.length != 8 || value.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' })
                    throw IOException("Invalid comment paragraph ID")
                return value.uppercase(java.util.Locale.ROOT)
            }
            for (relationship in relationships.filter { it.type == "commentsExtended" }) xml(part(relationship.target)) { p, event ->
                if (event == XmlPullParser.START_TAG && p.name == "commentEx" && p.namespace == "http://schemas.microsoft.com/office/word/2012/wordml") {
                    val parent = p.getAttributeValue(p.namespace, "paraIdParent").orEmpty()
                    if (parent.isNotEmpty()) {
                        if (children.size == 4096) throw NoteLimit()
                        val child = paragraph(p.getAttributeValue(p.namespace, "paraId").orEmpty())
                        if (!children.add(child)) throw IOException("Duplicate comment reply")
                        replies.getOrPut(paragraph(parent)) { ArrayList() }.add(child)
                    }
                }
            }
            if (replies.isEmpty()) return
            val comments = HashMap<String, String>()
            for (relationship in relationships.filter { it.type == "comments" }) {
                var active = 0
                var id = ""
                var last = ""
                xml(part(relationship.target)) { p, event ->
                    if (event == XmlPullParser.START_TAG) when {
                        p.ns(WORD) && p.name == "comment" -> {
                            if (active > 0) throw IOException("Nested document comments")
                            active = p.depth; id = p.getAttributeValue(p.namespace, "id").orEmpty(); last = ""
                            if (id.isEmpty() || id.length > 128) throw IOException("Invalid comment ID")
                        }
                        active > 0 && p.ns(WORD) && p.name == "p" -> last = p.getAttributeValue("http://schemas.microsoft.com/office/word/2010/wordml", "paraId").orEmpty()
                    }
                    if (event == XmlPullParser.END_TAG && active > 0 && p.depth == active) {
                        if (last.isNotEmpty()) {
                            if (comments.size == 4096) throw NoteLimit()
                            if (comments.put(paragraph(last), id) != null) throw IOException("Duplicate comment paragraph")
                        }
                        active = 0
                    }
                }
            }
            val pending = ArrayDeque<String>()
            for ((paragraph, id) in comments) if (id in ids) pending.addLast(paragraph)
            val visited = HashSet<String>()
            while (pending.isNotEmpty()) {
                check()
                val paragraph = pending.removeFirst()
                if (!visited.add(paragraph)) continue
                val id = comments[paragraph] ?: continue
                if (ids.size == 4096 && id !in ids) throw NoteLimit()
                ids.add(id)
                replies[paragraph]?.let(pending::addAll)
            }
        }
        private fun authors(path: String): Map<String, String> {
            val authors = LinkedHashMap<String, String>()
            xml(part(path)) { p, event ->
                if (event == XmlPullParser.START_TAG && (p.name == "cmAuthor" || p.name == "author" || p.name == "person")) {
                    if (authors.size == 4096) throw NoteLimit()
                    val id = p.attribute("id")
                    val name = p.attribute("name").ifEmpty { p.attribute("displayName") }
                    if (id.isNotEmpty()) authors[id] = name.takeScalarSafe(128)
                }
            }
            return authors
        }
        private fun combinedAuthors(relationships: List<Relation>): Map<String, String> {
            val result = LinkedHashMap<String, String>()
            for (part in relationships.filter { (it.type == "commentAuthors" || it.type == "authors" || it.type == "person") })
                for ((id, name) in authors(part.target)) {
                    if (result.size == 4096) throw NoteLimit()
                    result[id] = name
                }
            return result
        }
        fun presentation() {
            val relationships = relations("ppt/presentation.xml")
            val byId = relationships.associateBy { it.id }
            val authors = combinedAuthors(relationships)
            val slides = ArrayList<String>()
            xml(part("ppt/presentation.xml")) { p, event ->
                if (event == XmlPullParser.START_TAG && p.ns(PRESENTATION) && p.name == "sldId") {
                    if (slides.size == 4096) throw NoteLimit()
                    val id = (0 until p.attributeCount).firstOrNull { p.getAttributeName(it) == "id" && p.getAttributeNamespace(it).endsWith("/relationships") }?.let(p::getAttributeValue)
                    val slide = byId[id]?.takeIf { it.type == "slide" } ?: throw IOException("Missing slide relationship")
                    slides.add(slide.target)
                }
            }
            slides.forEachIndexed { page, slide ->
                for (relationship in relations(slide)) when (relationship.type) {
                    "notesSlide" -> speakerNotes(relationship.target, page)
                    "comments", "comment" -> comments(relationship.target, authors, "Slide ${page + 1}", page)
                }
            }
        }
        private fun speakerNotes(path: String, page: Int) {
            var shape = 0
            var placeholder = ""
            var capture = false
            val text = StringBuilder()
            xml(part(path)) { p, event ->
                if (event == XmlPullParser.START_TAG) when {
                    p.ns(PRESENTATION) && p.name == "sp" -> { shape = p.depth; placeholder = ""; text.clear() }
                    shape > 0 && p.ns(PRESENTATION) && p.name == "ph" -> placeholder = p.attribute("type")
                    shape > 0 && p.ns(DRAWING) && p.name == "t" -> capture = true
                    shape > 0 && p.ns(DRAWING) && p.name == "br" -> append(text, "\n")
                }
                if (capture && textEvent(event)) append(text, p.text.orEmpty())
                if (event == XmlPullParser.END_TAG) when {
                    p.ns(DRAWING) && p.name == "t" -> capture = false
                    shape > 0 && p.ns(DRAWING) && p.name == "p" -> append(text, "\n")
                    p.depth == shape && p.ns(PRESENTATION) && p.name == "sp" -> {
                        if (placeholder.isEmpty() || placeholder == "body") add(text.toString(), "speaker-note", location = "Slide ${page + 1}", page = page)
                        shape = 0
                    }
                }
            }
        }
        fun spreadsheet() {
            val relationships = relations("xl/workbook.xml")
            val people = combinedAuthors(relationships)
            val byId = relationships.associateBy { it.id }
            val sheets = ArrayList<Pair<String, String>>()
            xml(part("xl/workbook.xml")) { p, event ->
                if (event == XmlPullParser.START_TAG && p.ns(SPREADSHEET) && p.name == "sheet") {
                    if (sheets.size == 4096) throw NoteLimit()
                    val id = p.attribute("id")
                    val sheet = byId[id]?.takeIf { it.type == "worksheet" } ?: return@xml
                    sheets.add(p.attribute("name").takeScalarSafe(128) to sheet.target)
                }
            }
            for ((name, path) in sheets) {
                val relationships = relations(path)
                val threaded = relationships.filter { it.type == "threadedComment" || it.type == "threadedComments" }
                val first = notes.size
                for (relationship in threaded) comments(relationship.target, people, name, null)
                val duplicates = notes.subList(first, notes.size).groupBy { it.location }
                for (relationship in relationships.filter { it.type == "comments" })
                    comments(relationship.target, people, name, null, duplicates = duplicates)
            }
        }
        private fun comments(path: String, authors: Map<String, String>, location: String, page: Int?, allowed: Set<String>? = null,
            duplicates: Map<String, List<DocumentNote>> = emptyMap()) {
            val localAuthors = ArrayList<String>()
            var authorText = false
            var active = 0
            var author = ""
            var target = location
            var include = true
            var capture = false
            val text = StringBuilder()
            val name = StringBuilder()
            xml(part(path)) { p, event ->
                if (event == XmlPullParser.START_TAG) when {
                    active == 0 && p.name == "author" && p.ns(SPREADSHEET) -> { name.clear(); authorText = true }
                    (p.name == "comment" || p.name == "threadedComment" || p.name == "cm") -> {
                        if (active > 0) throw IOException("Nested document comments")
                        active = p.depth; text.clear()
                        include = allowed == null || p.attribute("id") in allowed
                        val id = p.attribute("authorId").ifEmpty { p.attribute("personId") }
                        author = p.attribute("author").ifEmpty { authors[id] ?: id.toIntOrNull()?.let(localAuthors::getOrNull).orEmpty() }
                        target = p.attribute("ref").let { if (it.isEmpty()) location else "$location!${it.takeScalarSafe(32)}" }
                    }
                    active > 0 && (p.name == "t" || p.name == "text") -> capture = true
                    active > 0 && (p.name == "br" || p.name == "cr") -> append(text, "\n")
                    active > 0 && p.name == "tab" -> append(text, "\t")
                }
                if (textEvent(event)) {
                    if (capture) append(text, p.text.orEmpty())
                    if (authorText && name.length < 128) name.append(p.text.orEmpty().takeScalarSafe(128 - name.length))
                }
                if (event == XmlPullParser.END_TAG) when {
                    authorText && p.name == "author" -> { if (localAuthors.size == 4096) throw NoteLimit(); localAuthors.add(name.toString()); authorText = false }
                    active > 0 && (p.name == "t" || p.name == "text") -> capture = false
                    active > 0 && p.name == "p" -> append(text, "\n")
                    p.depth == active && (p.name == "comment" || p.name == "threadedComment" || p.name == "cm") -> {
                        val value = text.toString().trim()
                        val existing = duplicates[target].orEmpty()
                        val duplicate = existing.any { it.text == value && it.author == author } ||
                            existing.isNotEmpty() && value.startsWith("[Threaded comment]") && existing.all { value.contains(it.text) }
                        if (include && !duplicate) add(value, author = author, location = target, page = page)
                        active = 0; capture = false
                    }
                }
            }
        }
        fun odf(data: ByteArray, format: String) {
            var page = -1
            var nextPage = 0
            var location = "Document"
            var sheet = ""
            var row = 0
            var column = 0
            var columnRepeat = 1
            var rowRepeat = 1
            var active = 0
            var speaker = false
            var paragraph = 0
            var creator = false
            val text = StringBuilder()
            val author = StringBuilder()
            val slides = format.uppercase() in setOf("ODP", "FODP", "ODG", "FODG")
            data class Scope(val depth: Int, val cell: Boolean, val page: Int, val location: String,
                val sheet: String, val row: Int, val column: Int, val rowRepeat: Int, val columnRepeat: Int)
            val scopes = ArrayDeque<Scope>()
            fun enter(depth: Int, cell: Boolean = false) {
                scopes.addLast(Scope(depth, cell, page, location, sheet, row, column, rowRepeat, columnRepeat))
            }
            fun number(p: XmlPullParser, key: String, max: Int) = p.attribute(key).ifEmpty { "1" }.toIntOrNull()?.takeIf { it in 1..max } ?: throw IOException("Invalid ODF repetition")
            xml(data) { p, event ->
                if (event == XmlPullParser.START_TAG) when {
                    active == 0 && p.ns(ODF_DRAWING) && p.name == "page" -> {
                        enter(p.depth); page = nextPage++; location = p.attribute("name").ifEmpty { "Slide ${page + 1}" }
                    }
                    active == 0 && p.ns(TABLE) && p.name == "table" -> {
                        enter(p.depth); sheet = p.attribute("name"); row = 0; column = 0; rowRepeat = 1; columnRepeat = 1
                        if (sheet.isNotEmpty()) location = sheet
                    }
                    active == 0 && p.ns(TABLE) && p.name == "table-row" -> { rowRepeat = number(p, "number-rows-repeated", 1048576); column = 0 }
                    active == 0 && p.ns(TABLE) && (p.name == "table-cell" || p.name == "covered-table-cell") -> {
                        enter(p.depth, cell = true)
                        columnRepeat = number(p, "number-columns-repeated", 16384)
                        if (sheet.isNotEmpty()) location = "$sheet!R${row + 1}C${column + 1}"
                    }
                    p.ns(OFFICE) && p.name == "annotation" || p.ns(ODF_PRESENTATION) && p.name == "notes" -> {
                        if (active > 0) throw IOException("Nested document annotations")
                        active = p.depth; speaker = p.name == "notes"; text.clear(); author.clear()
                    }
                    active > 0 && p.ns(DC) && p.name == "creator" -> creator = true
                    active > 0 && p.ns(TEXT) && (p.name == "p" || p.name == "h") -> paragraph = p.depth
                    paragraph > 0 && p.ns(TEXT) && p.name == "s" -> append(text, " ".repeat(number(p, "c", 16384)))
                    paragraph > 0 && p.ns(TEXT) && p.name == "tab" -> append(text, "\t")
                    paragraph > 0 && p.ns(TEXT) && p.name == "line-break" -> append(text, "\n")
                }
                if (textEvent(event)) {
                    if (paragraph > 0) append(text, p.text.orEmpty())
                    if (creator && author.length < 128) author.append(p.text.orEmpty().takeScalarSafe(128 - author.length))
                }
                if (event == XmlPullParser.END_TAG) when {
                    creator && p.ns(DC) && p.name == "creator" -> creator = false
                    paragraph > 0 && p.depth == paragraph -> { append(text, "\n"); paragraph = 0 }
                    active > 0 && p.depth == active -> {
                        add(text.toString(), if (speaker) "speaker-note" else "comment", author.toString(), location, page.takeIf { slides && it >= 0 })
                        active = 0
                    }
                    active == 0 && p.ns(TABLE) && p.name == "table-row" -> row = (row.toLong() + rowRepeat).coerceAtMost(1048576).toInt()
                }
                if (event == XmlPullParser.END_TAG && active == 0 && scopes.lastOrNull()?.depth == p.depth) {
                    val advance = columnRepeat
                    val scope = scopes.removeLast()
                    page = scope.page; location = scope.location; sheet = scope.sheet; row = scope.row
                    column = if (scope.cell) (scope.column.toLong() + advance).coerceAtMost(16384).toInt() else scope.column
                    rowRepeat = scope.rowRepeat; columnRepeat = scope.columnRepeat
                }
            }
        }
    }
}
