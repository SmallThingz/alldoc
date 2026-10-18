package app.smallthingz.alldoc.core

import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class DocumentParserTest {
    private fun parse(content: String, name: String): Document {
        val file = File.createTempFile("alldoc-core-", ".data", File("build/tmp/core-tests").also { it.mkdirs() })
        return try { file.writeText(content); DocumentParser.parse(file, name) } finally { file.delete() }
    }
    @Test fun extensionlessNamesUseNormalizedMimeHints() {
        for ((mime, extension) in listOf(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to "docx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to "xlsx",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation" to "pptx",
            "application/msword" to "doc", "application/vnd.ms-excel" to "xls",
            "application/vnd.ms-powerpoint" to "ppt", "application/vnd.apple.pages" to "pages",
            "application/vnd.apple.numbers" to "numbers", "application/vnd.apple.keynote" to "key",
            "text/markdown" to "md", "application/pdf" to "pdf",
            "application/vnd.oasis.opendocument.text" to "odt")) {
            assertEquals("document.$extension", DocumentFormats.routingName("Shared document", mime))
        }
        assertTrue(DocumentFormats.isPdf("Shared document", " APPLICATION/PDF; version=1.7"))
        assertEquals("report.docx", DocumentFormats.routingName("report.docx", "application/pdf"))
        assertEquals("readme", DocumentFormats.routingName("readme", "application/octet-stream"))
    }
    @Test fun exportsPreserveStructureAndEscaping() {
        val document = Document("xlsx", sections = listOf(Section("Sheet", "sheet", listOf(
            Block("cell", "a\t\"b", 0, 0), Block("cell", "two", 0, 1), Block("cell", "next", 1, 0)))))
        assertEquals("Sheet\na\t\"b\ttwo\nnext", document.toPlainText())
        assertTrue(document.toJson().contains("\"text\":\"a\\t\\\"b\""))
        assertTrue(document.toJson().contains("\"column\":1"))
    }
    @Test fun sparseTableTextRetainsCoordinatesWithoutPadding() {
        val document = Document("xlsx", sections = listOf(Section("Sheet", "sheet", listOf(
            Block("cell", "Name", 0, 0), Block("cell", "Value", 0, 2), Block("cell", "Last", 99, 16383)))))
        assertEquals("Sheet\nR1C1: Name\nR1C3: Value\nR100C16384: Last", document.toPlainText())
    }
    @Test fun utf16BomAndCombinedUnicodeStayIntact() {
        val expected = "क्षि 🇦🇺 é"
        val bytes = byteArrayOf(0xff.toByte(), 0xfe.toByte()) + expected.toByteArray(Charsets.UTF_16LE)
        assertEquals(expected, DocumentParser.decode(bytes, false))
    }
    @Test fun excerptDoesNotSplitUtf8Scalar() {
        val content = "x".repeat(DocumentParser.TEXT_LIMIT - 1) + "😀"
        assertEquals("x".repeat(DocumentParser.TEXT_LIMIT - 1), DocumentParser.decode(content.toByteArray(), true))
    }
    @Test(expected = IOException::class) fun binaryNotReportedAsText() { DocumentParser.decode(byteArrayOf(1, 0, 2), false) }
    @Test fun htmlRemovesActiveContentAndKeepsText() {
        val doc = parse("<html><title>Title</title><script>hidden()</script><body><h1>Visible</h1><p>Text &amp; entities</p></body></html>", "a.html")
        assertEquals("Title\nVisible\nText & entities", doc.toPlainText())
        assertFalse(doc.toPlainText().contains("hidden"))
    }
    @Test fun htmlKeepsBlockBoundariesAndInlineWords() {
        for ((markup, expected) in listOf(
            "<h1>Heading</h1><p>Body</p>" to "Heading\nBody",
            "<p>First<br>Second</p>" to "First\nSecond",
            "<ul><li>One</li><li>Two</li></ul>" to "• One\n• Two",
            "<table><tr><td>Left</td><td>Right</td></tr></table>" to "Left\tRight",
            "<p>Read <b>format</b>ted text.</p>" to "Read formatted text.",
            "<pre>first\n  second</pre>" to "first\n  second")) {
            val doc = parse("<html><body>$markup</body></html>", "page.html")
            assertEquals(markup, expected, doc.toPlainText())
        }
    }
    @Test fun htmlPreservesNestedListOrderAndEmptyTableCells() {
        val doc = parse("<html><body><ol start='7'><li>Parent<ul><li>Child</li></ul></li><li>Next</li></ol><table><tr><td></td><td>Value</td></tr><tr><td>End</td><td>12</td></tr></table></body></html>", "list.html")
        assertEquals("7. Parent\n• Child\n8. Next\n\tValue\nEnd\t12", doc.toPlainText())
        val heading = parse("<html><body><h3>Third level</h3><p>Body</p></body></html>", "head.html").sections.single().blocks.first()
        assertEquals("heading", heading.kind)
        assertEquals(3, heading.level)
        assertTrue(parse("<html><body>" + "<p>x</p>".repeat(4097) + "</body></html>", "many.html").truncated)
    }
    @Test fun htmlPreservesOrderedListValuesAndReversedCounters() {
        for ((markup, expected) in listOf(
            "<ol start='7'><li value='42'>Answer</li><li>Next</li></ol>" to "42. Answer\n43. Next",
            "<ol reversed><li>A</li><li>B</li></ol>" to "2. A\n1. B",
            "<ol reversed><li>A<ul><li>X</li><li>Y</li><li>Z</li></ul></li><li>B</li></ol>" to "2. A\n• X\n• Y\n• Z\n1. B",
            "<ol reversed start='0'><li>A</li><li value='-4'>B</li><li>C</li></ol>" to "0. A\n-4. B\n-5. C",
            "<ol start='-2'><li>A</li><li>B</li></ol>" to "-2. A\n-1. B",
            "<ol start='2147483647'><li>A</li><li>B</li></ol>" to "2147483647. A\n2147483648. B",
            "<ol reversed start='-2147483648'><li>A</li><li>B</li></ol>" to "-2147483648. A\n-2147483649. B",
            "<ul><li value='42'>A</li><li>B</li></ul>" to "• A\n• B")) {
            assertEquals(markup, expected, parse(markup, "list.html").toPlainText())
        }
    }
    @Test fun htmlKeepsDefinitionListBoundariesAndInlineWords() {
        for ((markup, expected) in listOf(
            "<dl><dt>Term</dt><dd>Definition</dd><dt>Other</dt><dd>Meaning</dd></dl>" to "Term\nDefinition\nOther\nMeaning",
            "<details open><summary>Summary</summary>Details</details><fieldset><legend>Group</legend>Value</fieldset>" to "Summary\nDetails\nGroup\nValue",
            "<p>Un<strong>broken</strong> <span>inline</span> text.</p>" to "Unbroken inline text.")) {
            assertEquals(markup, expected, parse(markup, "blocks.html").toPlainText())
        }
    }
    @Test fun fictionBookKeepsTableTextAndSubtitle() {
        val doc = parse("<FictionBook><body><section><subtitle>Observations</subtitle><table><tr><th>Location</th><th>Count</th></tr><tr><td>North <emphasis>cove</emphasis></td><td>12</td></tr></table><p>End</p></section></body></FictionBook>", "table.fb2")
        assertEquals("Observations\nLocation\tCount\nNorth cove\t12\nEnd", doc.toPlainText())
        assertEquals("heading", doc.sections.single().blocks.first().kind)
        assertEquals(4, doc.sections.single().blocks.count { it.kind == "cell" })
        assertEquals(1, doc.sections.single().blocks.last { it.kind == "cell" }.column)
    }
    @Test fun htmlKeepsParagraphsAndNestedTablesInsideCellsDistinct() {
        val doc = parse("<table><tr><td><p>A</p><p>B</p></td><td>C</td></tr><tr><td>Outer<table><tr><td>Inner</td><td>Two</td></tr></table></td><td>Last</td></tr></table>", "tables.html")
        assertEquals("A\nB\tC\nOuter\nInner\tTwo\tLast", doc.toPlainText())
    }
    @Test fun applePreviewPrefersFullDocumentOverSmallThumbnails() {
        val file = File.createTempFile("alldoc-preview-", ".pages", File("build/tmp/core-tests").also { it.mkdirs() })
        try {
            val preference = listOf("QuickLook/Preview.pdf", "preview.jpg", "preview-web.jpg", "QuickLook/Thumbnail.jpg", "preview-micro.jpg")
            for (first in preference.indices) {
                java.util.zip.ZipOutputStream(file.outputStream()).use { output ->
                    for (name in preference.drop(first).reversed()) {
                        output.putNextEntry(java.util.zip.ZipEntry(name)); output.write(name.toByteArray()); output.closeEntry()
                    }
                }
                assertEquals(preference[first], DocumentParser.embeddedPreview(file)?.name)
            }
        } finally { file.delete() }
    }
    @Test fun fictionBookKeepsHeadingsAndParagraphs() {
        val doc = parse("<FictionBook xmlns='http://www.gribuser.ru/xml/fictionbook/2.0'><body><section><title><p>Chapter</p></title><p>Hello <emphasis>world</emphasis></p></section></body></FictionBook>", "book.fb2")
        assertEquals("heading", doc.sections.single().blocks.first().kind)
        assertEquals("Hello world", doc.sections.single().blocks.last().text)
    }
    @Test(expected = Exception::class) fun fictionBookRejectsDoctype() { parse("<!DOCTYPE FictionBook><FictionBook/>", "book.fb2") }
    @Test fun fictionBookHonoursBomWithoutReinterpretingEncodingDeclaration() {
        val text = "<?xml version='1.0' encoding='UTF-16'?><FictionBook><body><section><p>Unicode क्षि</p></section></body></FictionBook>"
        val file = File.createTempFile("alldoc-fb2-", ".fb2", File("build/tmp/core-tests").also { it.mkdirs() })
        try {
            file.writeBytes(byteArrayOf(0xff.toByte(), 0xfe.toByte()) + text.toByteArray(Charsets.UTF_16LE))
            assertEquals("Unicode क्षि", DocumentParser.parse(file).toPlainText())
        } finally { file.delete() }
    }
    @Test fun metadataCapsPreserveUnicodeAndReportOmittedTitles() {
        assertEquals("", "😀".takeScalarSafe(0))
        assertEquals("", "😀".takeScalarSafe(1))
        assertEquals("😀", "😀".takeScalarSafe(2))
        for (limit in listOf(120, 200, 256)) {
            val prefix = "x".repeat(limit - 1)
            assertEquals(prefix, (prefix + "😀 tail").takeScalarSafe(limit))
            val exact = "x".repeat(limit - 2) + "😀"
            assertEquals(exact, (exact + " tail").takeScalarSafe(limit))
        }
        for (prefixLength in listOf(1022, 1023, 1024)) {
            val prefix = "x".repeat(prefixLength)
            val title = prefix + "😀"
            val doc = parse("<html><title>$title</title><body>Body</body></html>", "a.html")
            val expected = if (prefixLength == 1022) title else prefix
            assertEquals(expected, doc.sections.single().title)
            assertEquals(title.length > 1024, doc.truncated)
            assertEquals(doc.toJson(), doc.toJson().toByteArray().toString(Charsets.UTF_8))
        }
    }
    @Test(expected = CancellationException::class) fun cancellationStopsBeforeInput() { DocumentParser.parse(File("missing"), cancel = { true }) }
    @Test fun truncatedExportCannotBeConfusedWithFullText() { assertTrue(Document("text", truncated = true).toPlainText().contains("excerpt", true)) }
}

class NativeDocumentTest {
    private fun native(bytes: ByteArray, name: String): Document {
        org.junit.Assume.assumeTrue(NativeDocuments.isAvailable())
        return NativeDocuments.preview(bytes, name, null)
    }
    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val bytes = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(bytes).use { output ->
            entries.forEach { (name, text) -> output.putNextEntry(java.util.zip.ZipEntry(name)); output.write(text.toByteArray()); output.closeEntry() }
        }
        return bytes.toByteArray()
    }
    @Test fun wordBodyAndUnicodeAreReadThroughJni() {
        val bytes = zip("word/document.xml" to "<w:document xmlns:w='http://schemas.openxmlformats.org/wordprocessingml/2006/main'><w:body><w:p><w:r><w:t>Hello क्षि 😀</w:t></w:r></w:p></w:body></w:document>")
        assertTrue(native(bytes, "sample.docx").toPlainText().contains("Hello क्षि 😀"))
    }
    @Test fun openDocumentKeepsParagraphs() {
        val bytes = zip("content.xml" to "<office:document-content xmlns:office='urn:oasis:names:tc:opendocument:xmlns:office:1.0' xmlns:text='urn:oasis:names:tc:opendocument:xmlns:text:1.0'><office:body><office:text><text:p>Open document</text:p></office:text></office:body></office:document-content>")
        assertTrue(native(bytes, "sample.odt").toPlainText().contains("Open document"))
    }
    @Test fun openDocumentDrawingExposesPageText() {
        val bytes = zip("content.xml" to "<office:document-content xmlns:office='urn:oasis:names:tc:opendocument:xmlns:office:1.0' xmlns:draw='urn:oasis:names:tc:opendocument:xmlns:drawing:1.0' xmlns:text='urn:oasis:names:tc:opendocument:xmlns:text:1.0'><office:body><office:drawing><draw:page draw:name='Page'><text:p>Diagram label</text:p></draw:page></office:drawing></office:body></office:document-content>")
        val doc = native(bytes, "sample.odg")
        assertEquals("ODG", doc.format)
        assertTrue(doc.toPlainText().contains("Diagram label"))
    }
    @Test fun flatOpenDocumentAllowsLeadingXmlWhitespace() {
        val bodies = listOf(
            "fodt" to "<office:text><text:p>Words</text:p></office:text>",
            "fods" to "<office:spreadsheet><table:table><table:table-row><table:table-cell office:value-type='string'><text:p>Words</text:p></table:table-cell></table:table-row></table:table></office:spreadsheet>",
            "fodp" to "<office:presentation><draw:page><text:p>Words</text:p></draw:page></office:presentation>",
            "fodg" to "<office:drawing><draw:page><text:p>Words</text:p></draw:page></office:drawing>")
        for ((extension, body) in bodies) {
            val xml = "<office:document xmlns:office='urn:oasis:names:tc:opendocument:xmlns:office:1.0' xmlns:text='urn:oasis:names:tc:opendocument:xmlns:text:1.0' xmlns:table='urn:oasis:names:tc:opendocument:xmlns:table:1.0' xmlns:draw='urn:oasis:names:tc:opendocument:xmlns:drawing:1.0'><office:body>$body</office:body></office:document>"
            for (prefix in listOf("", "\n \t\r", "\uFEFF", "\uFEFF\n ")) {
                val document = native((prefix + xml).toByteArray(), "sample.$extension")
                assertTrue(document.toPlainText().contains("Words"))
                assertFalse(document.truncated)
            }
        }
    }
    @Test fun wordBlockLimitMarksOmittedEmptyParagraphs() {
        val paragraph = "<w:p><w:r><w:t>Words</w:t></w:r></w:p>"
        for ((tail, truncated) in listOf("" to false, "<w:p/>" to true, paragraph to true)) {
            val xml = "<w:document xmlns:w='http://schemas.openxmlformats.org/wordprocessingml/2006/main'><w:body>" +
                paragraph.repeat(4096) + tail + "</w:body></w:document>"
            val document = native(zip("word/document.xml" to xml), "sample.docx")
            assertEquals(4096, document.sections.single().blocks.size)
            assertEquals(truncated, document.truncated)
        }
    }
    @Test fun rtfUnicodeWorks() { assertTrue(native("{\\rtf1\\ansi Hello \\u233?}".toByteArray(), "sample.rtf").toPlainText().contains("Hello é")) }
    @Test fun malformedInputIsAnError() {
        org.junit.Assume.assumeTrue(NativeDocuments.isAvailable())
        try { native(byteArrayOf(0x50, 0x4b, 0x03, 0x04), "broken.docx"); fail("Malformed archive succeeded") }
        catch (expected: NativeDocuments.PreviewException) { assertEquals("MALFORMED", expected.code) }
    }
    @Test fun unsafePackageNamesAreRejected() {
        org.junit.Assume.assumeTrue(NativeDocuments.isAvailable())
        try { native(zip("../word/document.xml" to "text"), "broken.docx"); fail("Unsafe package succeeded") }
        catch (expected: NativeDocuments.PreviewException) { assertTrue(expected.code in setOf("MALFORMED", "UNSUPPORTED")) }
    }
}
