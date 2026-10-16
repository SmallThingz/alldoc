package app.smallthingz.alldoc.core

import java.io.IOException
import java.net.URI
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.CancellationToken
import org.intellij.markdown.parser.MarkdownParser
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

enum class MarkdownKind { PARAGRAPH, HEADING, QUOTE, LIST_ITEM, CODE, RULE, TABLE }
data class MarkdownSpan(val start: Int, val end: Int, val bold: Boolean = false,
    val italic: Boolean = false, val strike: Boolean = false, val code: Boolean = false, val link: String? = null)
data class MarkdownText(val text: String, val spans: List<MarkdownSpan> = emptyList())
data class MarkdownBlock(val kind: MarkdownKind, val content: MarkdownText, val start: Int,
    val level: Int = 0, val marker: String = "", val rows: List<List<MarkdownText>> = emptyList(), val language: String = "",
    val markerContext: String = marker)

/** Rendered, frontend-neutral Markdown. Offsets address plainText, never the source markup. */
data class MarkdownDocument(val blocks: List<MarkdownBlock>, val plainText: String,
    val truncated: Boolean = false, val warnings: List<String> = emptyList()) {
    /** Export disclosure is separate from the unprefixed reader/search coordinate space. */
    fun exportText(): String = if (truncated) "[Document excerpt: remaining content omitted.]\n\n$plainText" else plainText

    fun toDocument() = Document("md", "SEMANTIC_LAYOUT", truncated, warnings,
        listOf(Section("", blocks = blocks.map { Block(it.kind.name.lowercase(), it.content.text, level = it.level) })), this)

    fun toJson(): String = buildString {
        append("{\"version\":1,\"format\":\"md\",\"fidelity\":\"SEMANTIC_LAYOUT\",\"continuous\":true,\"pageCount\":1,\"truncated\":")
        append(truncated).append(",\"warnings\":[")
        warnings.forEachIndexed { i, value -> if (i > 0) append(','); jsonString(value) }
        append("],\"sections\":[{\"title\":\"\",\"kind\":\"document\",\"blocks\":[")
        blocks.forEachIndexed { i, block ->
            if (i > 0) append(',')
            append("{\"kind\":"); jsonString(block.kind.name.lowercase())
            append(",\"start\":").append(block.start).append(",\"level\":").append(block.level)
            if (block.marker.isNotEmpty()) { append(",\"marker\":"); jsonString(block.marker) }
            if (block.language.isNotEmpty()) { append(",\"language\":"); jsonString(block.language) }
            append(','); richJson(block.content)
            if (block.rows.isNotEmpty()) {
                append(",\"rows\":[")
                block.rows.forEachIndexed { row, cells ->
                    if (row > 0) append(','); append('[')
                    cells.forEachIndexed { column, cell ->
                        if (column > 0) append(','); append('{'); richJson(cell); append('}')
                    }
                    append(']')
                }
                append(']')
            }
            append('}')
        }
        append("]}]}")
    }

    companion object {
        const val MAX_BLOCK_TEXT = 8192
        private const val MAX_BLOCKS = 4096
        private const val MAX_SPANS = 32768
        private val whitespace = Regex("[ \\t\\r\\n\\u000c]+")

        /** JetBrains owns Markdown grammar; the bounded HTML bridge reuses our existing inert DOM parser. */
        fun parse(text: String, truncated: Boolean = false, cancel: () -> Boolean = { false }): MarkdownDocument {
            fun check() = DocumentParser.check(cancel)
            check()
            val limited = text.takeScalarSafe(DocumentParser.TEXT_LIMIT)
            val source = limited.replace("\r\n", "\n").replace('\r', '\n')
            val flavour = GFMFlavourDescriptor(useSafeLinks = true, absolutizeAnchorLinks = false,
                makeHttpsAutoLinks = true, useTagFilter = false)
            val tree = MarkdownParser(flavour, true, CancellationToken { check() })
                .buildMarkdownTreeFromString(source as CharSequence)
            // Bound recursive generation before entering it, independently of the source byte limit.
            val pending = ArrayDeque<Pair<ASTNode, Int>>()
            pending.add(tree to 0)
            var nodes = 0
            while (pending.isNotEmpty()) {
                check()
                val (node, depth) = pending.removeLast()
                if (++nodes > 100_000 || depth > 64) throw IOException("Markdown structure exceeds its reading limit.")
                node.children.forEach { pending.add(it to depth + 1) }
            }
            var htmlSize = 0
            val renderer = object : HtmlGenerator.DefaultTagRenderer({ _, _, attributes -> attributes }, false) {
                fun bounded(value: CharSequence): CharSequence {
                    check()
                    htmlSize += value.length
                    if (htmlSize > 2 * 1024 * 1024) throw IOException("Markdown structure exceeds its reading limit.")
                    return value
                }
                override fun openTag(node: ASTNode, tagName: CharSequence, vararg attributes: CharSequence?, autoClose: Boolean) =
                    bounded(super.openTag(node, tagName, *attributes, autoClose = autoClose))
                override fun closeTag(tagName: CharSequence) = bounded(super.closeTag(tagName))
                override fun printHtml(html: CharSequence) = bounded(html)
            }
            val html = HtmlGenerator(source, tree, flavour).generateHtml(renderer)
            check()
            val body = Jsoup.parseBodyFragment(html).body()
            check()
            body.select("input[type=checkbox]").forEach { input ->
                input.replaceWith(TextNode(if (input.hasAttr("checked")) "☑ " else "☐ "))
            }
            val active = body.select("script,style,iframe,object,embed,template,noscript,form,input,button,textarea,select,svg,math")
            val omittedActive = active.isNotEmpty()
            active.remove()
            return Builder(truncated || limited.length != text.length, cancel).apply {
                if (omittedActive) warnings.add("Active and unsupported embedded HTML is omitted.")
                visit(body, 0, 0, 0)
            }.finish()
        }

        private fun safeLink(value: String): String? {
            if (value.length > 2048 || value.any { it < ' ' }) return null
            return try { value.takeIf { URI(it).scheme?.lowercase() in setOf("http", "https", "mailto") } }
            catch (_: IllegalArgumentException) { null } catch (_: java.net.URISyntaxException) { null }
        }

        private class Builder(var truncated: Boolean, val cancel: () -> Boolean) {
            val warnings = linkedSetOf<String>()
            val blocks = ArrayList<MarkdownBlock>()
            val plain = StringBuilder()
            var spanCount = 0
            var visited = 0
            fun check(depth: Int = 0) {
                DocumentParser.check(cancel)
                if (++visited > 200_000 || depth > 64) throw IOException("Markdown structure exceeds its reading limit.")
            }
            fun finish(): MarkdownDocument {
                if (truncated) warnings.add("Markdown excerpt: remaining content exceeds its reading limits.")
                return MarkdownDocument(blocks.toList(), plain.toString(), truncated, warnings.toList())
            }
            fun add(kind: MarkdownKind, content: MarkdownText, level: Int = 0, marker: String = "",
                rows: List<List<MarkdownText>> = emptyList(), language: String = "", markerContext: String = marker) {
                if (content.text.isEmpty()) return
                var offset = 0
                while (offset < content.text.length) {
                    check()
                    if (blocks.size >= MAX_BLOCKS || plain.length >= DocumentParser.TEXT_LIMIT) { truncated = true; return }
                    val separator = if (blocks.isEmpty() || offset > 0) "" else "\n\n"
                    val remaining = DocumentParser.TEXT_LIMIT - plain.length - separator.length
                    if (remaining <= 0) { truncated = true; return }
                    var end = minOf(content.text.length, offset + MAX_BLOCK_TEXT, offset + remaining)
                    if (end < content.text.length && end > offset && content.text[end - 1].isHighSurrogate() && content.text[end].isLowSurrogate()) end--
                    if (end == offset) { truncated = true; return }
                    if (end < content.text.length && kind != MarkdownKind.TABLE) {
                        val boundary = content.text.lastIndexOf('\n', end - 1).takeIf { it > offset + MAX_BLOCK_TEXT / 2 }
                            ?: content.text.lastIndexOf(' ', end - 1).takeIf { it > offset + MAX_BLOCK_TEXT / 2 }
                        if (boundary != null) end = boundary + 1
                    }
                    val part = content.slice(offset, end)
                    plain.append(separator)
                    blocks += MarkdownBlock(kind, part, plain.length, level,
                        if (offset == 0) marker else "", if (offset == 0) rows else emptyList(), language, markerContext)
                    plain.append(part.text)
                    offset = end
                }
            }
            fun visit(element: Element, depth: Int, quote: Int, list: Int, marker: String = "", markerContext: String = marker) {
                check(depth)
                when (element.normalName()) {
                    "h1", "h2", "h3", "h4", "h5", "h6" -> add(MarkdownKind.HEADING, inline(element.childNodes()), element.normalName().last().digitToInt())
                    "hr" -> add(MarkdownKind.RULE, MarkdownText("—"))
                    "pre" -> {
                        val code = element.selectFirst("code") ?: element
                        add(MarkdownKind.CODE, MarkdownText(code.wholeText().removeSuffix("\n")),
                            language = code.classNames().firstOrNull { it.startsWith("language-") }?.removePrefix("language-")?.takeScalarSafe(64).orEmpty())
                    }
                    "table" -> table(element)
                    "ul", "ol" -> {
                        var number = element.attr("start").toIntOrNull()?.coerceIn(0, 999_999_999) ?: 1
                        element.children().filter { it.normalName() == "li" }.forEach { item ->
                            visit(item, depth + 1, quote, list + 1, if (element.normalName() == "ol") "${number++}." else "•")
                        }
                    }
                    "blockquote" -> children(element, depth, quote + 1, list, marker, markerContext)
                    "p" -> add(if (list > 0) MarkdownKind.LIST_ITEM else if (quote > 0) MarkdownKind.QUOTE else MarkdownKind.PARAGRAPH,
                        inline(element.childNodes()), if (list > 0) list - 1 else quote, marker, markerContext = markerContext)
                    else -> children(element, depth, quote, list, marker, markerContext)
                }
            }
            fun children(element: Element, depth: Int, quote: Int, list: Int, marker: String, markerContext: String) {
                val buffer = ArrayList<Node>()
                var nextMarker = marker
                fun flush() {
                    if (buffer.isEmpty()) return
                    val content = inline(buffer)
                    if (content.text.isNotEmpty()) {
                        add(if (list > 0) MarkdownKind.LIST_ITEM else if (quote > 0) MarkdownKind.QUOTE else MarkdownKind.PARAGRAPH,
                            content, if (list > 0) list - 1 else quote, nextMarker, markerContext = markerContext)
                        nextMarker = ""
                    }
                    buffer.clear()
                }
                for (node in element.childNodes()) {
                    check(depth)
                    if (node is Element && node.normalName() in blockTags) {
                        flush()
                        visit(node, depth + 1, quote, list, nextMarker, markerContext)
                        if (node.normalName() !in setOf("ul", "ol")) nextMarker = ""
                    } else buffer += node
                }
                flush()
            }
            fun table(element: Element) {
                var rows = ArrayList<List<MarkdownText>>()
                var size = 0
                var header = 1
                fun flush() {
                    if (rows.isEmpty()) return
                    val text = StringBuilder(); val spans = ArrayList<MarkdownSpan>()
                    val widths = IntArray(rows.maxOf { it.size }) { column -> rows.maxOf { it.getOrNull(column)?.text?.length ?: 0 } }
                    val padded = rows.sumOf { row -> row.mapIndexed { c, cell -> if (c == row.lastIndex) cell.text.length else widths[c] + 2 }.sum() } + rows.size - 1 <= MAX_BLOCK_TEXT
                    rows.forEachIndexed { r, row ->
                        if (r > 0) text.append('\n')
                        row.forEachIndexed { c, cell ->
                            val at = text.length; text.append(cell.text)
                            spans.addAll(cell.spans.map { it.copy(start = it.start + at, end = it.end + at) })
                            if (c < row.lastIndex) {
                                if (padded) repeat(widths[c] - cell.text.length + 2) { text.append(' ') }
                                else text.append('\t')
                            }
                        }
                    }
                    add(MarkdownKind.TABLE, MarkdownText(text.toString(), spans), header, rows = rows.toList())
                    rows = ArrayList(); size = 0; header = 0
                }
                for (row in element.select("tr")) {
                    check()
                    val allCells = row.children().filter { it.normalName() in setOf("th", "td") }
                    if (allCells.size > 32) truncated = true
                    val cells = allCells.take(32).map { inline(it.childNodes()) }
                    val rowSize = cells.sumOf { it.text.length } + (cells.size - 1).coerceAtLeast(0)
                    if (rowSize > MAX_BLOCK_TEXT) { truncated = true; flush(); continue }
                    if (rows.size >= 64 || size + rowSize + (if (rows.isEmpty()) 0 else 1) > MAX_BLOCK_TEXT) flush()
                    rows.add(cells); size += rowSize + if (rows.size == 1) 0 else 1
                }
                flush()
            }
            fun inline(nodes: List<Node>): MarkdownText {
                val text = StringBuilder(); val spans = ArrayList<MarkdownSpan>()
                fun append(value: String, style: MarkdownSpan) {
                    val normalized = if (style.code) value else whitespace.replace(value, " ")
                    val tail = text.lastOrNull()
                    val valueText = if (!style.code && (tail == ' ' || tail == '\n') && normalized.startsWith(' ')) normalized.drop(1) else normalized
                    val at = text.length; text.append(valueText)
                    if (valueText.isNotEmpty() && (style.bold || style.italic || style.strike || style.code || style.link != null)) {
                        if (++spanCount > MAX_SPANS) throw IOException("Markdown formatting exceeds its reading limit.")
                        spans += style.copy(start = at, end = text.length)
                    }
                }
                fun walk(node: Node, style: MarkdownSpan, depth: Int) {
                    check(depth)
                    if (node is TextNode) { append(node.wholeText, style); return }
                    if (node !is Element) return
                    val tag = node.normalName()
                    when (tag) {
                        "br" -> { text.append('\n'); return }
                        "img" -> { warnings.add("External and embedded Markdown images are represented by their alternative text."); append(node.attr("alt").ifBlank { "[Image]" }, style); return }
                    }
                    val next = style.copy(bold = style.bold || tag == "strong" || tag == "b",
                        italic = style.italic || tag == "em" || tag == "i",
                        strike = style.strike || tag == "del" || tag == "s" || tag == "strike" || node.hasClass("user-del"),
                        code = style.code || tag == "code", link = if (tag == "a") safeLink(node.attr("href")) else style.link)
                    node.childNodes().forEach { walk(it, next, depth + 1) }
                }
                nodes.forEach { walk(it, MarkdownSpan(0, 0), 0) }
                val value = text.toString()
                val start = value.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) value.length else it }
                val end = value.indexOfLast { !it.isWhitespace() } + 1
                return if (end <= start) MarkdownText("") else MarkdownText(value, spans).slice(start, end)
            }
        }
        private val blockTags = setOf("p", "h1", "h2", "h3", "h4", "h5", "h6", "hr", "pre", "table", "ul", "ol", "li", "blockquote", "div", "section", "article")
    }
}

private fun MarkdownText.slice(start: Int, end: Int) = MarkdownText(text.substring(start, end), spans.mapNotNull {
    val from = maxOf(start, it.start); val to = minOf(end, it.end)
    if (from >= to) null else it.copy(start = from - start, end = to - start)
})

private fun StringBuilder.richJson(content: MarkdownText) {
    append("\"text\":"); jsonString(content.text)
    append(",\"spans\":[")
    content.spans.forEachIndexed { i, span ->
        if (i > 0) append(',')
        append("{\"start\":").append(span.start).append(",\"end\":").append(span.end)
        if (span.bold) append(",\"bold\":true")
        if (span.italic) append(",\"italic\":true")
        if (span.strike) append(",\"strike\":true")
        if (span.code) append(",\"code\":true")
        span.link?.let { append(",\"link\":"); jsonString(it) }
        append('}')
    }
    append(']')
}
