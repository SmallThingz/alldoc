package app.smallthingz.alldoc.core

import java.io.IOException
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class MarkdownDocumentTest {
    @Test fun richSyntaxProducesSemanticBlocksAndSafeStyledLinks() {
        val source = """
            # AllDoc **reader**

            A *continuous* document with ~~old~~ text, `inline code`, and [a link][guide].

            > Quoted **advice**.

            3. First item
            4. Second item

            - [x] Done
            - [ ] Pending

            ```kotlin
            val answer = 42
            ```

            | Name | Result |
            | --- | --- |
            | **AllDoc** | Ready |

            [guide]: https://example.org/guide
        """.trimIndent()
        val result = MarkdownDocument.parse(source)
        assertEquals(MarkdownKind.HEADING, result.blocks.first().kind)
        assertEquals("AllDoc reader", result.blocks.first().content.text)
        assertTrue(result.blocks.any { it.kind == MarkdownKind.QUOTE && it.content.text == "Quoted advice." })
        assertEquals(listOf("3.", "4."), result.blocks.filter { it.marker.endsWith('.') }.map { it.marker })
        assertTrue(result.plainText.contains("☑ Done"))
        assertTrue(result.plainText.contains("☐ Pending"))
        assertTrue(result.blocks.any { it.kind == MarkdownKind.CODE && it.language == "kotlin" && it.content.text == "val answer = 42" })
        val table = result.blocks.single { it.kind == MarkdownKind.TABLE }
        assertEquals("AllDoc", table.rows[1][0].text)
        assertTrue(table.rows[1][0].spans.single().bold)
        val spans = result.blocks.flatMap { it.content.spans }
        assertTrue(spans.any { it.bold }); assertTrue(spans.any { it.italic })
        assertTrue(spans.any { it.strike }); assertTrue(spans.any { it.code })
        assertTrue(spans.any { it.link == "https://example.org/guide" })
        assertFalse(result.plainText.contains("**")); assertFalse(result.plainText.contains("[guide]"))
        assertEquals(result.plainText, result.toDocument().toPlainText())
        assertTrue(result.toJson().contains("\"continuous\":true,\"pageCount\":1"))
        assertTrue(result.toJson().contains("\"link\":\"https://example.org/guide\""))
        assertOffsets(result)
    }

    @Test fun escapesUnicodeAndLineBreaksSurviveWithoutExecutingHtml() {
        val result = MarkdownDocument.parse("\\*literal\\* &amp; café 😀 A\u0301\r\nsoft  \r\nhard\r\n\r\n<script>do_not_show()</script>\n\n" +
            "[bad](javascript:alert) [local](file:///secret) [good](mailto:reader@example.org) ![Diagram](https://example.org/x.png)")
        assertTrue(result.plainText.contains("*literal* & café 😀 A\u0301 soft\nhard"))
        assertFalse(result.plainText.contains("do_not_show"))
        assertTrue(result.plainText.contains("Diagram"))
        val links = result.blocks.flatMap { it.content.spans }.mapNotNull { it.link }
        assertEquals(listOf("mailto:reader@example.org"), links)
        assertTrue(result.warnings.any { it.contains("alternative text") })
        assertTrue(result.warnings.any { it.contains("HTML") })
        assertOffsets(result)
    }

    @Test fun longStyledParagraphsAndTablesStayBoundedAndKeepTheirContent() {
        val paragraph = "word 😀 ".repeat(4000).trimEnd()
        val rows = (1..100).joinToString("\n") { "| row$it | ${"value ".repeat(25)} |" }
        val result = MarkdownDocument.parse("**$paragraph**\n\n| Item | Value |\n|---|---|\n$rows")
        assertFalse(result.truncated)
        assertTrue(result.blocks.size > 4)
        assertTrue(result.blocks.all { it.content.text.length <= MarkdownDocument.MAX_BLOCK_TEXT })
        assertEquals(paragraph.trimEnd(), result.blocks.filter { it.kind != MarkdownKind.TABLE }.joinToString("") { it.content.text })
        val tableRows = result.blocks.filter { it.kind == MarkdownKind.TABLE }.flatMap { it.rows }
        assertEquals(101, tableRows.size)
        assertEquals("row100", tableRows.last()[0].text)
        assertTrue(result.blocks.filter { it.kind != MarkdownKind.TABLE }.all { block -> block.content.spans.any { it.bold } })
        assertOffsets(result)
    }

    @Test fun exceptionalRowsAndOversizedInputAreExplicitExcerpts() {
        val row = "x".repeat(9000)
        val table = MarkdownDocument.parse("| Heading |\n|---|\n| $row |")
        assertTrue(table.truncated)
        assertTrue(table.warnings.any { it.contains("excerpt") })
        assertTrue(table.toDocument().toPlainText().startsWith("[Document excerpt: remaining content omitted.]\n\n"))
        assertFalse(table.plainText.startsWith("[Document excerpt:"))
        val input = MarkdownDocument.parse("x".repeat(DocumentParser.TEXT_LIMIT + 20))
        assertTrue(input.truncated)
        assertTrue(input.plainText.length <= DocumentParser.TEXT_LIMIT)
        assertEquals("[Document excerpt: remaining content omitted.]\n\n" + input.plainText, input.toDocument().toPlainText())
        assertOffsets(input)
    }

    @Test fun looseListParagraphsKeepTheirRoleAndDepthAfterNestedItems() {
        for ((first, second, markers) in listOf(
            Triple("1.", "2.", listOf("1.", "", "•", "", "2.")),
            Triple("100000000.", "100000001.", listOf("100000000.", "", "•", "", "100000001.")),
            Triple("-", "-", listOf("•", "", "•", "", "•")),
        )) {
            val indent = " ".repeat(first.length + 1)
            val result = MarkdownDocument.parse("$first First\n\n" +
                "${indent}Continued with **emphasis**.\n\n" +
                "$indent- Nested\n\n" +
                "${indent}After nested.\n\n$second Next")
            assertFalse(result.truncated)
            assertEquals(listOf("First", "Continued with emphasis.", "Nested", "After nested.", "Next"),
                result.blocks.map { it.content.text })
            assertTrue("Continuation paragraphs remain list items", result.blocks.all { it.kind == MarkdownKind.LIST_ITEM })
            assertEquals(markers, result.blocks.map { it.marker })
            assertEquals(listOf(markers.first(), markers.first(), "•", markers.first(), markers.last()),
                result.blocks.map { it.markerContext })
            assertEquals(listOf(0, 0, 1, 0, 0), result.blocks.map { it.level })
            assertTrue(result.blocks[1].content.spans.single().bold)
            assertEquals(result.plainText, result.exportText())
            assertOffsets(result)
        }
    }

    @Test fun chunkedListItemsKeepTheirMarkerContextWithoutRepeatingExportedLabels() {
        val content = "word ".repeat(4000).trimEnd()
        val result = MarkdownDocument.parse("100000000. **$content**\n\n           After")
        val chunks = result.blocks.dropLast(1)
        assertTrue("the fixture crosses the bounded block size", chunks.size >= 3)
        assertTrue(result.blocks.all { it.kind == MarkdownKind.LIST_ITEM && it.markerContext == "100000000." })
        assertEquals("100000000.", result.blocks.first().marker)
        assertTrue(result.blocks.drop(1).all { it.marker.isEmpty() })
        assertEquals(content, chunks.joinToString("") { it.content.text })
        assertTrue(chunks.all { it.content.spans.any { span -> span.bold } })
        assertEquals("$content\n\nAfter", result.plainText)
        assertEquals(result.plainText, result.exportText())
        assertEquals(1, Regex("\"marker\":").findAll(result.toJson()).count())
        assertFalse(result.toJson().contains("markerContext"))
        assertOffsets(result)
    }

    @Test fun cancellationAndStructuralLimitsAreEnforced() {
        try { MarkdownDocument.parse("# Stop", cancel = { true }); fail("Expected cancellation") }
        catch (_: CancellationException) { }
        var checks = 0
        try { MarkdownDocument.parse("word ".repeat(10000), cancel = { ++checks > 10 }); fail("Expected parser cancellation") }
        catch (_: CancellationException) { assertTrue(checks > 10) }
        try { MarkdownDocument.parse("<div>".repeat(80) + "deep" + "</div>".repeat(80)); fail("Expected depth limit") }
        catch (_: IOException) { }
    }

    private fun assertOffsets(document: MarkdownDocument) {
        for (block in document.blocks) {
            assertEquals(block.content.text, document.plainText.substring(block.start, block.start + block.content.text.length))
            for (span in block.content.spans) {
                assertTrue(span.start >= 0 && span.end > span.start && span.end <= block.content.text.length)
                assertFalse(span.start > 0 && block.content.text[span.start].isLowSurrogate() && block.content.text[span.start - 1].isHighSurrogate())
            }
        }
    }
}
