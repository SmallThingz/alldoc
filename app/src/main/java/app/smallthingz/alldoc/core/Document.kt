package app.smallthingz.alldoc.core

/** Frontend-neutral read result. Fidelity and truncation travel with every exported result. */
data class Document(
    val format: String,
    val fidelity: String = "TEXT_STRUCTURE",
    val truncated: Boolean = false,
    val warnings: List<String> = emptyList(),
    val sections: List<Section> = emptyList(),
    val markdown: MarkdownDocument? = null,
) {
    fun toPlainText(): String = markdown?.exportText() ?: buildString {
        if (truncated) appendLine("[Document excerpt: remaining content omitted.]\n")
        sections.forEachIndexed { index, section ->
            if (index > 0) append('\n')
            if (section.title.isNotBlank()) appendLine(section.title)
            val sparse = section.hasSparseCells()
            var row = -1
            section.blocks.forEach { block ->
                if (block.kind == "cell" && sparse) {
                    append("R").append(block.row.toLong() + 1).append("C").append(block.column.toLong() + 1)
                        .append(": ").appendLine(block.text)
                } else if (block.kind == "cell") {
                    if (row == block.row) append('\t') else if (row >= 0) append('\n')
                    append(block.text)
                    row = block.row
                } else {
                    if (row >= 0) { append('\n'); row = -1 }
                    appendLine(block.text)
                }
            }
            if (row >= 0) append('\n')
        }
    }.trimEnd()

    fun toJson(): String = markdown?.toJson() ?: buildString {
        append("{\"version\":1,\"format\":"); jsonString(format)
        append(",\"fidelity\":"); jsonString(fidelity)
        append(",\"truncated\":").append(truncated)
        append(",\"warnings\":[")
        warnings.forEachIndexed { i, value -> if (i > 0) append(','); jsonString(value) }
        append("],\"sections\":[")
        sections.forEachIndexed { i, section ->
            if (i > 0) append(',')
            append("{\"title\":"); jsonString(section.title)
            append(",\"kind\":"); jsonString(section.kind)
            append(",\"blocks\":[")
            section.blocks.forEachIndexed { j, block ->
                if (j > 0) append(',')
                append("{\"kind\":"); jsonString(block.kind)
                append(",\"text\":"); jsonString(block.text)
                if (block.row >= 0) append(",\"row\":").append(block.row)
                if (block.column >= 0) append(",\"column\":").append(block.column)
                if (block.level > 0) append(",\"level\":").append(block.level)
                append('}')
            }
            append("]}")
        }
        append("]}")
    }
}

data class Section(val title: String, val kind: String = "document", val blocks: List<Block> = emptyList())
data class Block(val kind: String, val text: String, val row: Int = -1, val column: Int = -1, val level: Int = 0)

internal fun StringBuilder.jsonString(value: String) {
    append('"')
    value.forEach { char ->
        when (char) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (char < ' ') append("\\u").append(char.code.toString(16).padStart(4, '0')) else append(char)
        }
    }
    append('"')
}

private fun Section.hasSparseCells(): Boolean {
    var row = -1
    var column = -1
    for (block in blocks) {
        if (block.kind != "cell") continue
        if (row < 0) {
            if (block.row != 0 || block.column != 0) return true
        } else if (block.row == row) {
            if (block.column != column + 1) return true
        } else if (block.row != row + 1 || block.column != 0) return true
        row = block.row
        column = block.column
    }
    return false
}

/** Cap UTF-16 storage without splitting a supplementary Unicode scalar. */
internal fun String.takeScalarSafe(limit: Int): String {
    require(limit >= 0)
    if (length <= limit) return this
    val end = if (limit > 0 && this[limit - 1].isHighSurrogate() && this[limit].isLowSurrogate()) limit - 1 else limit
    return substring(0, end)
}
