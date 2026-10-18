package app.smallthingz.alldoc.core

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.Charset
import java.util.concurrent.CancellationException
import java.util.zip.ZipFile
import javax.xml.parsers.SAXParserFactory
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeVisitor
import org.xml.sax.Attributes
import org.xml.sax.SAXException
import org.xml.sax.InputSource
import java.io.StringReader
import org.xml.sax.helpers.DefaultHandler

object DocumentParser {
    const val INPUT_LIMIT = 16 * 1024 * 1024
    const val TEXT_LIMIT = 512 * 1024
    private const val BLOCK_LIMIT = 4096

    fun parse(file: File, name: String = file.name, mime: String? = null, cancel: () -> Boolean = { false }): Document {
        check(cancel)
        val detected = DocumentProbe.file(file, cancel)
        val hint = DocumentProbe.openingName(name, mime, detected)
            ?: throw IOException("This file type is not supported.")
        return parseDocument(file, hint, cancel)
    }

    /** The engine already inspected this snapshot; do not scan its archive a second time. */
    internal fun parseDocument(file: File, name: String, cancel: () -> Boolean): Document {
        check(cancel)
        // Internal signature routes only: native validation determines the supported document type.
        if (name == "document.cfb" || name == "document.zip")
            return NativeDocuments.preview(read(file, INPUT_LIMIT, cancel), name, NativeDocuments.Cancellation { cancel() })
        val format = DocumentFormats.find(name) ?: throw IOException("This file type is not supported.")
        if (format.id == "iwork") return iwork(file, cancel)
        if (format.id == "pdf") throw IOException("PDF requires the page reader.")
        if (DocumentFormats.isNative(name))
            return NativeDocuments.preview(read(file, INPUT_LIMIT, cancel), name, NativeDocuments.Cancellation { cancel() })
        val bytes = read(file, TEXT_LIMIT + 4, cancel, excerpt = true)
        val truncated = file.length() > bytes.size || bytes.size > TEXT_LIMIT
        val text = decode(bytes, truncated)
        check(cancel)
        return when (format.id) {
            "markdown" -> MarkdownDocument.parse(text, truncated, cancel).toDocument()
            "html" -> html(text, truncated, cancel)
            "fictionbook" -> fictionBook(text, truncated, cancel)
            else -> throw IOException("This file type is not supported.")
        }
    }

    internal fun check(cancel: () -> Boolean) {
        if (Thread.currentThread().isInterrupted || cancel()) throw CancellationException("Document read cancelled")
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private fun read(file: File, limit: Int, cancel: () -> Boolean, excerpt: Boolean = false): ByteArray {
        if (!excerpt && file.length() > limit) throw IOException("Document exceeds the ${limit / (1024 * 1024)} MiB input limit.")
        return file.inputStream().use { input ->
            val output = java.io.ByteArrayOutputStream(minOf(file.length(), limit.toLong()).toInt().coerceAtLeast(0))
            val buffer = ByteArray(16 * 1024)
            while (output.size() < limit) {
                check(cancel)
                val count = input.read(buffer, 0, minOf(buffer.size, limit - output.size()))
                if (count < 0) break
                output.write(buffer, 0, count)
            }
            check(cancel)
            if (!excerpt && input.read() != -1) throw IOException("Document exceeds its input limit.")
            output.toByteArray()
        }
    }

    internal fun decode(bytes: ByteArray, truncated: Boolean): String {
        val (encoding, skip) = when {
            bytes.startsWith(byteArrayOf(0xff.toByte(), 0xfe.toByte(), 0, 0)) -> "UTF-32LE" to 4
            bytes.startsWith(byteArrayOf(0, 0, 0xfe.toByte(), 0xff.toByte())) -> "UTF-32BE" to 4
            bytes.startsWith(byteArrayOf(0xff.toByte(), 0xfe.toByte())) -> "UTF-16LE" to 2
            bytes.startsWith(byteArrayOf(0xfe.toByte(), 0xff.toByte())) -> "UTF-16BE" to 2
            bytes.startsWith(byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte())) -> "UTF-8" to 3
            else -> "UTF-8" to 0
        }
        var end = minOf(bytes.size, TEXT_LIMIT)
        if (truncated) {
            if (encoding.startsWith("UTF-32")) end -= (end - skip) % 4
            else if (encoding.startsWith("UTF-16")) {
                end -= (end - skip) % 2
                if (end >= skip + 2) {
                    val a = bytes[end - 2].toInt() and 255; val b = bytes[end - 1].toInt() and 255
                    val last = if (encoding == "UTF-16LE") a or (b shl 8) else (a shl 8) or b
                    if (last in 0xd800..0xdbff) end -= 2
                }
            } else {
                // Retain only complete UTF-8 scalars at an excerpt boundary.
                var start = end
                while (start > skip && bytes[start - 1].toInt() and 0xc0 == 0x80) start--
                if (start > skip) {
                    val lead = bytes[start - 1].toInt() and 255
                    val width = when { lead < 0x80 -> 1; lead in 0xc2..0xdf -> 2; lead in 0xe0..0xef -> 3; lead in 0xf0..0xf4 -> 4; else -> 1 }
                    if (end - start + 1 < width) end = start - 1
                }
            }
        }
        val value = try {
            Charset.forName(encoding).newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes, skip, (end - skip).coerceAtLeast(0))).toString()
        } catch (error: java.nio.charset.CharacterCodingException) { throw IOException("This file is binary or uses an unsupported text encoding.", error) }
        if (value.any { it == '\u0000' } || value.count { it < ' ' && it !in "\n\r\t\u000c" } > value.length / 50)
            throw IOException("This file is binary; no document reader is available for it.")
        return value
    }

    private fun html(text: String, truncated: Boolean, cancel: () -> Boolean): Document {
        val document = Jsoup.parse(text)
        check(cancel)
        document.select("script,style,noscript,template").remove()
        val blocks = ArrayList<Block>()
        val buffer = StringBuilder()
        var remaining = TEXT_LIMIT
        var omitted = truncated
        var level = 0
        var pre = 0
        var cells = 0
        var rowDepth = 0
        val columns = ArrayDeque<Int>()
        data class ListCounter(var next: Long, val step: Long)
        val lists = ArrayDeque<ListCounter?>()
        fun flush() {
            val value = if (pre > 0) buffer.toString().trimEnd()
                else if (rowDepth > 0) buffer.toString().trimEnd(' ', '\r', '\n') else buffer.toString().trim()
            buffer.setLength(0)
            if (value.isBlank()) return
            if (blocks.size == BLOCK_LIMIT || remaining == 0) { omitted = true; return }
            val part = value.takeScalarSafe(remaining)
            omitted = omitted || part.length != value.length
            remaining -= part.length
            blocks += Block(if (level > 0) "heading" else "paragraph", part, level = level)
        }
        fun boundary() {
            if (cells == 0) flush()
            else if (buffer.isNotEmpty() && buffer.last() !in "\n\t") buffer.append('\n')
        }
        val tableContainers = setOf("table", "thead", "tbody", "tfoot", "tr")
        val joinedContainers = setOf("table", "thead", "tbody", "tfoot", "td", "th")
        document.body().traverse(object : NodeVisitor {
            override fun head(node: Node, depth: Int) {
                check(cancel)
                if (node is TextNode) {
                    val value = node.wholeText
                    if (node.parent()?.nodeName() in tableContainers && value.isBlank()) return
                    if (pre > 0) buffer.append(value)
                    else for (char in value) {
                        if (char in " \t\r\n\u000c") {
                            if (buffer.isNotEmpty() && buffer.last() !in " \n\t") buffer.append(' ')
                        } else buffer.append(char)
                    }
                    return
                }
                if (node !is Element) return
                val tag = node.normalName()
                if ((node.isBlock || tag == "summary" || tag == "legend") && tag !in joinedContainers) boundary()
                when (tag) {
                    "pre" -> pre++
                    "br" -> buffer.append('\n')
                    "ol" -> {
                        val reversed = node.hasAttr("reversed")
                        val start = node.attr("start").trim().toIntOrNull()?.toLong()
                            ?: if (reversed) node.children().count { it.normalName() == "li" }.toLong() else 1L
                        lists.addLast(ListCounter(start, if (reversed) -1L else 1L))
                    }
                    "ul" -> lists.addLast(null)
                    "li" -> {
                        val counter = lists.lastOrNull()
                        if (counter == null) buffer.append("• ") else {
                            val number = node.attr("value").trim().toIntOrNull()?.toLong() ?: counter.next
                            buffer.append("$number. ")
                            counter.next = number + counter.step
                        }
                    }
                    "tr" -> { rowDepth++; columns.addLast(0) }
                    "td", "th" -> {
                        val column = if (columns.isEmpty()) 0 else columns.removeLast()
                        if (column > 0) buffer.append('\t')
                        columns.addLast(column + 1)
                        cells++
                    }
                    "h1", "h2", "h3", "h4", "h5", "h6" -> level = tag[1] - '0'
                }
            }
            override fun tail(node: Node, depth: Int) {
                if (node !is Element) return
                val tag = node.normalName()
                if ((node.isBlock || tag == "summary" || tag == "legend") && tag !in joinedContainers) boundary()
                when (tag) {
                    "pre" -> pre--
                    "td", "th" -> {
                        while (buffer.isNotEmpty() && buffer.last() in " \r\n") buffer.setLength(buffer.length - 1)
                        cells--
                    }
                    "tr" -> { rowDepth--; if (columns.isNotEmpty()) columns.removeLast() }
                    "ol", "ul" -> if (lists.isNotEmpty()) lists.removeLast()
                    "h1", "h2", "h3", "h4", "h5", "h6" -> level = 0
                }
            }
        })
        flush()
        val originalTitle = document.title()
        val title = originalTitle.takeScalarSafe(1024)
        return Document("html", truncated = omitted || title != originalTitle,
            warnings = listOf("Text reading order; images, styling and external resources are omitted.") +
                if (title != originalTitle) listOf("Document title was shortened.") else emptyList(),
            sections = listOf(Section(title, blocks = blocks)))
    }

    private fun fictionBook(text: String, excerpt: Boolean, cancel: () -> Boolean): Document {
        if (excerpt) throw IOException("FictionBook exceeds the 512 KiB XML input limit.")
        if (text.contains("<!DOCTYPE") || text.contains("<!ENTITY")) throw IOException("FictionBook document types and entities are unsupported.")
        val blocks = ArrayList<Block>()
        val body = StringBuilder()
        var depth = 0; var capture = 0; var title = false; var events = 0; var truncated = false
        var row = -1; var column = -1; var capturedKind = "paragraph"
        val capturedTags = setOf("p", "v", "subtitle", "td", "th")
        val factory = SAXParserFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        val parser = factory.newSAXParser()
        parser.parse(InputSource(StringReader(text)), object : DefaultHandler() {
            override fun startElement(uri: String?, localName: String, qName: String?, attributes: Attributes?) {
                check(cancel)
                if (depth == 0 && localName != "FictionBook") throw SAXException("Not a FictionBook document")
                if (++depth > 64 || ++events > 100_000) throw SAXException("XML structure limit")
                if (localName == "title") title = true
                if (localName == "tr") { row++; column = -1 }
                if (localName == "td" || localName == "th") column++
                if (localName in capturedTags) {
                    capture++
                    if (capture == 1) {
                        body.setLength(0)
                        capturedKind = if (localName == "td" || localName == "th") "cell"
                            else if (title || localName == "subtitle") "heading" else "paragraph"
                    }
                }
            }
            override fun characters(chars: CharArray, start: Int, length: Int) { if (capture > 0 && body.length < TEXT_LIMIT) body.append(chars, start, minOf(length, TEXT_LIMIT - body.length)) }
            override fun endElement(uri: String?, localName: String, qName: String?) {
                if (localName in capturedTags && --capture == 0) {
                    if (blocks.size < BLOCK_LIMIT) blocks += Block(capturedKind, body.toString(),
                        row = if (capturedKind == "cell") row.coerceAtLeast(0) else -1,
                        column = if (capturedKind == "cell") column.coerceAtLeast(0) else -1,
                        level = if (capturedKind == "heading") 1 else 0) else truncated = true
                }
                if (localName == "title") title = false
                depth--
            }
        })
        return Document("fb2", truncated = truncated, sections = listOf(Section("", blocks = blocks)))
    }

    private fun iwork(file: File, cancel: () -> Boolean): Document {
        if (file.length() > INPUT_LIMIT) throw IOException("iWork preview input exceeds 16 MiB.")
        val entries = ZipFile(file).use { zip ->
            check(cancel)
            if (zip.size() > 4096) throw IOException("iWork package has too many entries.")
            zip.entries().asSequence().filter { it.name in previewNames }.map { it.name }.toList()
        }
        return Document("iwork", "EMBEDDED_PREVIEW_ONLY", warnings = listOf(
            if (entries.isEmpty()) "This iWork package has no supported embedded preview. Export it as PDF or Office format."
            else "Only the saved embedded preview is available; iWork text and structure are not decoded."),
            sections = listOf(Section("Saved previews", blocks = entries.map { Block("metadata", it) })))
    }

    private val previewNames = setOf("QuickLook/Preview.pdf", "preview.jpg", "preview-web.jpg", "QuickLook/Thumbnail.jpg", "preview-micro.jpg")
    data class EmbeddedPreview(val name: String, val mime: String, val bytes: ByteArray)
    fun embeddedPreview(file: File, cancel: () -> Boolean = { false }): EmbeddedPreview? {
        if (file.length() > INPUT_LIMIT) throw IOException("iWork preview input exceeds 16 MiB.")
        return ZipFile(file).use { zip ->
            if (zip.size() > 4096) throw IOException("iWork package has too many entries.")
            val entry = previewNames.firstNotNullOfOrNull { zip.getEntry(it) } ?: return null
            if (entry.size < 0 || entry.size > INPUT_LIMIT) throw IOException("Embedded preview exceeds input limits.")
            val output = java.io.ByteArrayOutputStream()
            val checksum = java.util.zip.CRC32()
            zip.getInputStream(entry).use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    check(cancel)
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > INPUT_LIMIT) throw IOException("Embedded preview exceeds input limits.")
                    checksum.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                }
            }
            if (output.size().toLong() != entry.size || entry.crc != checksum.value) throw IOException("Corrupted embedded preview.")
            EmbeddedPreview(entry.name, if (entry.name.endsWith(".pdf")) "application/pdf" else "image/jpeg", output.toByteArray())
        }
    }
}
