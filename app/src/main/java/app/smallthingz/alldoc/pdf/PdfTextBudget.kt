package app.smallthingz.alldoc.pdf

/** A PDF excerpt stops at the first omitted scalar, including across text blocks. */
internal class PdfTextBudget(limit: Int) {
    var remaining = limit
        private set

    fun take(text: String): String {
        var end = minOf(text.length, remaining)
        if (end in 1 until text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
        remaining = if (end < text.length) 0 else remaining - end
        return text.substring(0, end)
    }
}
