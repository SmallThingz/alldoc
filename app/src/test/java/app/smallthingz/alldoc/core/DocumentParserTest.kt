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
        assertEquals("Title\nVisible Text & entities", doc.toPlainText())
        assertFalse(doc.toPlainText().contains("hidden"))
    }
    @Test fun htmlKeepsBlockBoundariesAndInlineWords() {
        for ((markup, expected) in listOf(
            "<h1>Heading</h1><p>Body</p>" to "Heading Body",
            "<p>First<br>Second</p>" to "First Second",
            "<ul><li>One</li><li>Two</li></ul>" to "One Two",
            "<table><tr><td>Left</td><td>Right</td></tr></table>" to "Left Right",
            "<p>Read <b>format</b>ted text.</p>" to "Read formatted text.",
            "<pre>first\n  second</pre>" to "first\n  second")) {
            val doc = parse("<html><body>$markup</body></html>", "page.html")
            assertEquals(markup, expected, doc.sections.single().blocks.single().text)
            assertEquals(markup, expected, doc.toPlainText())
        }
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
