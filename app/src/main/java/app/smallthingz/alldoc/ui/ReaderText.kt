package app.smallthingz.alldoc.ui

import java.text.BreakIterator
import java.text.StringCharacterIterator
import java.util.Locale

internal const val MAX_TEXT_CHUNK = 8192

internal fun splitText(text: String, checkCancellation: () -> Unit = {}): List<String> {
    checkCancellation()
    val chunks = ArrayList<String>()
    val boundaries = BreakIterator.getCharacterInstance(Locale.ROOT)
    var start = 0
    while (start < text.length) {
        checkCancellation()
        var end = minOf(start + 2400, text.length)
        if (end < text.length) {
            var limit = start + minOf(MAX_TEXT_CHUNK, text.length - start)
            if (limit < text.length && text[limit - 1].isHighSurrogate() && text[limit].isLowSurrogate()) limit--
            // Bound ICU as well as layout. Exceptional clusters may break here visually;
            // the backing text, scalar pairs and accumulated source offsets stay intact.
            boundaries.setText(StringCharacterIterator(text, start, limit, start))
            var newline = end
            while (newline >= start && text[newline] != '\n') newline--
            if (newline >= start) end = newline + 1
            if (!boundaries.isBoundary(end)) {
                val boundary = boundaries.preceding(end)
                end = if (boundary > start) boundary else boundaries.following(end).let {
                    if (it == BreakIterator.DONE) limit else it
                }
            }
            if (end > start && end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
        }
        chunks.add(text.substring(start, end))
        start = end
    }
    return chunks
}

// The next LazyColumn item already starts a new visual line. Keep the source newline
// in chunk offsets, but do not render an additional empty line at that boundary.
internal fun visibleTextChunk(chunk: String, last: Boolean): String =
    if (!last && chunk.endsWith('\n')) chunk.dropLast(1).removeSuffix("\r") else chunk

internal fun searchOffsets(text: String, query: String, checkCancellation: () -> Unit = {}): IntArray {
    checkCancellation()
    if (query.isEmpty() || query.length > text.length) return IntArray(0)
    var blank = true
    for (index in query.indices) {
        if (index % 1024 == 0) checkCancellation()
        if (!query[index].isWhitespace()) { blank = false; break }
    }
    if (blank) return IntArray(0)
    val result = IntArray(minOf(10000, text.length / query.length))
    var count = 0
    var start = 0
    var candidates = 0
    while (start <= text.length - query.length && count < result.size) {
        if (candidates++ % 1024 == 0) checkCancellation()
        var matched = 0
        while (matched < query.length) {
            if (matched > 0) checkCancellation()
            var length = minOf(1024, query.length - matched)
            val end = matched + length
            if (end < query.length && query[end - 1].isHighSurrogate() && query[end].isLowSurrogate()) length--
            if (!text.regionMatches(start + matched, query, matched, length, ignoreCase = true)) break
            matched += length
        }
        if (matched == query.length) {
            result[count++] = start
            start += query.length
        } else start++
    }
    checkCancellation()
    return result.copyOf(count)
}

internal fun chunkIndex(starts: IntArray, offset: Int): Int {
    val index = starts.binarySearch(offset)
    return if (index >= 0) index else (-index - 2).coerceAtLeast(0)
}

internal val NoTextMatches = IntArray(0)

/** A request owns both its input and offsets; old work cannot navigate a newer query. */
internal class TextSearchRequest(val text: String, val query: String) {
    fun evaluate(checkCancellation: () -> Unit = {}) = TextSearchResult(this, searchOffsets(text, query, checkCancellation))
}

internal class TextSearchResult(private val request: TextSearchRequest, private val offsets: IntArray) {
    fun forRequest(current: TextSearchRequest): IntArray = if (current === request) offsets else NoTextMatches
}
