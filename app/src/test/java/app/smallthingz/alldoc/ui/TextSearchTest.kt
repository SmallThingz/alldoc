package app.smallthingz.alldoc.ui

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class TextSearchTest {
    @Test fun changingToAMissingQueryCannotReuseEarlierNavigationOffsets() {
        val old = TextSearchRequest("one two one", "one")
        val result = old.evaluate()
        assertArrayEquals(intArrayOf(0, 8), result.forRequest(old))
        val current = TextSearchRequest(old.text, "absent")
        assertEquals(0, result.forRequest(current).size)
        assertEquals(0, current.evaluate().forRequest(current).size)
    }

    @Test fun changingDocumentsRejectsResultsEvenWhenTheQueryIsTheSame() {
        val old = TextSearchRequest("match at the start", "match")
        val result = old.evaluate()
        val current = TextSearchRequest("new match position", "match")
        assertEquals(0, result.forRequest(current).size)
        assertArrayEquals(intArrayOf(4), current.evaluate().forRequest(current))
    }

    @Test fun missingQueriesStopDuringTheScanWhenSuperseded() {
        val request = TextSearchRequest("a".repeat(512 * 1024), "a".repeat(127) + "b")
        var checks = 0
        assertThrows(CancellationException::class.java) {
            request.evaluate { if (++checks == 5) throw CancellationException("Superseded") }
        }
        assertEquals(5, checks)
    }

    @Test fun veryLongQueriesRemainCancellableWhileMatchingOrCheckingWhitespace() {
        for (query in listOf("a".repeat(512 * 1024), " ".repeat(512 * 1024))) {
            var checks = 0
            assertThrows(CancellationException::class.java) {
                searchOffsets(query, query) { if (++checks == 5) throw CancellationException("Closed") }
            }
            assertEquals(5, checks)
        }
    }

    @Test fun boundedMatchingPreservesUnicodeOffsetsAndNonoverlappingResults() {
        val lower = "a".repeat(1023) + "\uD801\uDC28" + "é"
        val upper = "A".repeat(1023) + "\uD801\uDC00" + "É"
        val text = "prefix" + lower + lower + "suffix"
        val expected = buildList {
            var offset = text.indexOf(upper, ignoreCase = true)
            while (offset >= 0) {
                add(offset)
                offset = text.indexOf(upper, offset + upper.length, ignoreCase = true)
            }
        }.toIntArray()
        assertArrayEquals(expected, searchOffsets(text, upper))
        assertArrayEquals(intArrayOf(0, 2), searchOffsets("aaaaa", "aa"))
        assertArrayEquals(intArrayOf(1023), searchOffsets("x".repeat(1023) + "match", "MATCH"))
        assertEquals(10000, searchOffsets("a".repeat(20000), "a").size)
    }

}
