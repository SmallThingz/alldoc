package app.smallthingz.alldoc.ui

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class ReaderTextTest {
    @Test fun chunkingPreservesContentAndSurrogatePairs() {
        val text = "x".repeat(2399) + "😀" + "é\n".repeat(1800) + "End"
        val chunks = splitText(text)
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.none { it.last().isHighSurrogate() || it.first().isLowSurrogate() })
    }
    @Test fun chunkingKeepsCombiningMarksWithTheirBase() {
        val text = "x".repeat(2399) + "e\u0301rest"
        val chunks = splitText(text)
        assertEquals(text, chunks.joinToString(""))
        assertEquals(2399, chunks[0].length)
        assertTrue(chunks[1].startsWith("e\u0301"))
    }
    @Test fun oversizedGraphemeDoesNotSplitOrStallChunking() {
        val cluster = "a" + "\u0301".repeat(5000)
        val text = cluster + " end"
        val chunks = splitText(text)
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.first().startsWith(cluster))
        assertTrue(chunks.none(String::isEmpty))
    }
    @Test fun malformedSurrogatesBeforeLargeClustersStillMakeProgressWithoutLosingSource() {
        for (prefix in listOf("\uD800", "Lead\uDBFF", "\uDC00")) {
            val text = prefix + "a" + "\u0301".repeat(5000)
            var checks = 0
            val chunks = splitText(text) {
                if (++checks > 16) throw CancellationException("Chunking failed to make forward progress")
            }
            assertEquals(text, chunks.joinToString(""))
            assertTrue(chunks.all { it.length in 1..MAX_TEXT_CHUNK })
        }
    }
    @Test fun excessiveClustersKeepBoundedLayoutUnitsAndOriginalSearchOffsets() {
        for (cluster in listOf("a" + "\u0301".repeat(MAX_TEXT_CHUNK * 2),
            "a" + "\uDB40\uDD00".repeat(MAX_TEXT_CHUNK))) {
            val text = "Lead\n" + cluster + " 😀 tail"
            val chunks = splitText(text)
            assertEquals(text, chunks.joinToString(""))
            assertTrue(chunks.all { it.length in 1..MAX_TEXT_CHUNK })
            assertTrue(chunks.none { it.last().isHighSurrogate() || it.first().isLowSurrogate() })
            var offset = 0
            val starts = IntArray(chunks.size) { index -> offset.also { offset += chunks[index].length } }
            val matches = searchOffsets(text, "tail")
            assertArrayEquals(intArrayOf(text.indexOf("tail")), matches)
            val index = chunkIndex(starts, matches.single())
            assertEquals("tail", chunks[index].substring(matches.single() - starts[index]))
        }
    }
    @Test fun internalChunkBreakDoesNotAddABlankLineOrRemoveAuthoredBlankLines() {
        assertEquals("first", visibleTextChunk("first\n", false))
        assertEquals("first", visibleTextChunk("first\r\n", false))
        assertEquals("first\n", visibleTextChunk("first\n\n", false))
        assertEquals("first\r\n", visibleTextChunk("first\r\n\r\n", false))
        assertEquals("first\n", visibleTextChunk("first\n", true))
        assertEquals("first", visibleTextChunk("first", false))
    }
    @Test fun longUnbrokenTextKeepsOffsetsAndChunkingCanBeCancelled() {
        val text = "start\n" + "x".repeat(512 * 1024)
        val chunks = splitText(text)
        assertEquals(text, chunks.joinToString(""))
        assertEquals("start\n", chunks.first())
        assertTrue(chunks.drop(1).all { it.length in 1..2400 })
        var checks = 0
        assertThrows(CancellationException::class.java) {
            splitText(text) { if (++checks == 4) throw CancellationException("Stopped") }
        }
        assertEquals(4, checks)
    }

    @Test fun searchFindsTextAcrossChunksAndLocatesItsBlock() {
        val text = "x".repeat(2395) + "Across boundary" + "x".repeat(2600)
        val chunks = splitText(text)
        val starts = IntArray(chunks.size)
        for (index in 1 until chunks.size) starts[index] = starts[index - 1] + chunks[index - 1].length
        val matches = searchOffsets(text, "ACROSS BOUNDARY")
        assertArrayEquals(intArrayOf(2395), matches)
        assertEquals(0, chunkIndex(starts, matches[0]))
        assertEquals(1, chunkIndex(starts, starts[1]))
    }
    @Test fun searchBoundsResultsAndTreatsEmptyInputConsistently() {
        assertEquals(0, searchOffsets("text", "").size)
        assertEquals(0, searchOffsets("", "text").size)
        assertEquals(10000, searchOffsets("ab".repeat(15000), "AB").size)
        assertArrayEquals(intArrayOf(0, 4), searchOffsets("caféCAFÉ", "café"))
    }
}
