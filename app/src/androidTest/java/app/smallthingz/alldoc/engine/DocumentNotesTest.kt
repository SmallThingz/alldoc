package app.smallthingz.alldoc.engine

import android.graphics.Bitmap
import android.os.CancellationSignal
import android.os.OperationCanceledException
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.smallthingz.alldoc.theme.ThemeSettings
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class DocumentNotesTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val rel = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private val p = "http://schemas.openxmlformats.org/presentationml/2006/main"
    private val a = "http://schemas.openxmlformats.org/drawingml/2006/main"
    private val w = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private val s = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private fun relationships(vararg entries: Triple<String, String, String>) =
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" + entries.joinToString("") {
            "<Relationship Id=\"${it.first}\" Type=\"$rel/${it.second}\" Target=\"${it.third}\"/>"
        } + "</Relationships>"
    private fun <T> archive(parts: Map<String, String>, block: (File) -> T): T {
        val file = File(context.cacheDir, "notes-${UUID.randomUUID()}.zip")
        try {
            ZipOutputStream(file.outputStream()).use { zip -> parts.forEach { (name, xml) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(xml.toByteArray()); zip.closeEntry()
            } }
            return block(file)
        } finally { file.delete() }
    }

    @Test fun presentationNotesFollowSlideRelationshipsAndExcludePlaceholderText() = archive(mapOf(
        "ppt/presentation.xml" to "<p:presentation xmlns:p=\"$p\" xmlns:r=\"$rel\"><p:sldIdLst><p:sldId id=\"9\" r:id=\"second\"/><p:sldId id=\"4\" r:id=\"first\"/></p:sldIdLst></p:presentation>",
        "ppt/_rels/presentation.xml.rels" to relationships(Triple("first", "slide", "slides/slide1.xml"), Triple("second", "slide", "slides/slide2.xml"), Triple("authors", "commentAuthors", "commentAuthors.xml")),
        "ppt/commentAuthors.xml" to "<p:cmAuthorLst xmlns:p=\"$p\"><p:cmAuthor id=\"3\" name=\"Ava\"/></p:cmAuthorLst>",
        "ppt/slides/_rels/slide2.xml.rels" to relationships(Triple("notes", "notesSlide", "../notesSlides/note9.xml"), Triple("comments", "comments", "../comments/comment2.xml")),
        "ppt/notesSlides/note9.xml" to "<p:notes xmlns:p=\"$p\" xmlns:a=\"$a\"><p:cSld><p:spTree><p:sp><p:nvSpPr><p:nvPr><p:ph type=\"sldNum\"/></p:nvPr></p:nvSpPr><a:p><a:r><a:t>99</a:t></a:r></a:p></p:sp><p:sp><p:nvSpPr><p:nvPr><p:ph type=\"body\"/></p:nvPr></p:nvSpPr><a:p><a:r><a:t>Discuss A &amp; B &#x1F600;</a:t></a:r></a:p><a:p><a:r><a:t>Then questions.</a:t></a:r></a:p></p:sp></p:spTree></p:cSld></p:notes>",
        "ppt/comments/comment2.xml" to "<p:cmLst xmlns:p=\"$p\"><p:cm authorId=\"3\"><p:text>Check figure.</p:text></p:cm></p:cmLst>",
        "ppt/notesSlides/orphan.xml" to "<p:notes xmlns:p=\"$p\">Not linked.</p:notes>"
    )) { file ->
        val notes = OfficeNotes.read(file, "PPTX", CancellationSignal())
        assertFalse(notes.truncated)
        assertEquals(2, notes.entries.size)
        assertEquals("Discuss A & B 😀\nThen questions.", notes.entries[0].text)
        assertEquals("speaker-note", notes.entries[0].kind)
        assertEquals(0, notes.entries[0].page)
        assertEquals("Ava", notes.entries[1].author)
        assertEquals(0, notes.entries[1].page)
        assertFalse(notes.appendToText("Body").contains("99"))
        val json = JSONObject(notes.appendTo("{\"sections\":[]}"))
        assertEquals(0, json.getJSONArray("notes").getJSONObject(0).getInt("page"))
        assertEquals(0, json.getJSONArray("sections").length())
        for (body in listOf("{}", " { \n }\t\r\n", "{\"sections\":[]} \n")) {
            val exported = JSONObject(notes.appendTo(body))
            assertEquals(2, exported.getJSONArray("notes").length())
            assertEquals("Discuss A & B 😀\nThen questions.", exported.getJSONArray("notes").getJSONObject(0).getString("text"))
            if (body.contains("sections")) assertEquals(0, exported.getJSONArray("sections").length())
        }
    }

    @Test fun wordExportsOnlyReferencedCommentsAndDoesNotInventPageNumbers(): Unit = archive(mapOf(
        "word/document.xml" to "<w:document xmlns:w=\"$w\"><w:body><w:p><w:commentRangeStart w:id=\"7\"/><w:r><w:t>Visible.</w:t></w:r></w:p></w:body></w:document>",
        "word/_rels/document.xml.rels" to relationships(Triple("comments", "comments", "comments.xml")),
        "word/comments.xml" to "<w:comments xmlns:w=\"$w\"><w:comment w:id=\"7\" w:author=\"Jo\"><w:p><w:r><w:t>Revise this.</w:t></w:r></w:p></w:comment><w:comment w:id=\"8\"><w:p><w:r><w:t>Unreferenced.</w:t></w:r></w:p></w:comment></w:comments>"
    )) { file ->
        val note = OfficeNotes.read(file, "DOCX", CancellationSignal()).entries.single()
        assertEquals("Revise this.", note.text)
        assertEquals("Jo", note.author)
        assertNull(note.page)
        var closed = false
        val delegate = object : DocumentSession {
            override val info = DocumentInfo("Report", "application/docx", "DOCX", 1, true, "TEXT_STRUCTURE", reflowable = true)
            override fun text(signal: CancellationSignal) = "Visible."
            override fun json(signal: CancellationSignal) = "{\"sections\":[]}"
            override fun renderPage(index: Int, width: Int, settings: ThemeSettings, signal: CancellationSignal): Bitmap = error("No rendering needed")
            override fun close() { closed = true }
        }
        val session = withDocumentNotes(delegate, file)
        assertTrue(session.info.supportsNotes)
        assertEquals("Visible.", session.bodyText(CancellationSignal()))
        assertEquals(note, session.notes(CancellationSignal()).entries.single())
        assertTrue(session.text(CancellationSignal()).contains("Notes\nJo\nRevise this."))
        session.close()
        assertTrue(closed)
        assertThrows(IllegalStateException::class.java) { session.notes(CancellationSignal()) }
    }

    @Test fun wordRepliesFollowFinalParagraphLinksWithoutAdmittingOrphansOrCycling() {
        val w14 = "http://schemas.microsoft.com/office/word/2010/wordml"
        val w15 = "http://schemas.microsoft.com/office/word/2012/wordml"
        fun comment(id: Int, paragraph: String, text: String) =
            "<w:comment w:id=\"$id\"><w:p w14:paraId=\"$paragraph\"><w:r><w:t>$text</w:t></w:r></w:p></w:comment>"
        fun reply(paragraph: String, parent: String) = "<w15:commentEx w15:paraId=\"$paragraph\" w15:paraIdParent=\"$parent\"/>"
        val parts = mapOf(
            "word/document.xml" to "<w:document xmlns:w=\"$w\"><w:body><w:p><w:commentRangeStart w:id=\"7\"/></w:p></w:body></w:document>",
            "word/_rels/document.xml.rels" to relationships(Triple("comments", "comments", "comments.xml"), Triple("threads", "commentsExtended", "commentsExtended.xml")),
            "word/comments.xml" to "<w:comments xmlns:w=\"$w\" xmlns:w14=\"$w14\">" +
                "<w:comment w:id=\"7\"><w:p w14:paraId=\"11111111\"><w:r><w:t>Opening.</w:t></w:r></w:p><w:p w14:paraId=\"22222222\"><w:r><w:t>Root.</w:t></w:r></w:p></w:comment>" +
                comment(9, "44444444", "Nested reply.") + comment(8, "33333333", "First reply.") +
                comment(10, "AAAAAAAA", "Orphan.") + comment(11, "BBBBBBBB", "Cycle one.") + comment(12, "CCCCCCCC", "Cycle two.") +
                "<w:comment w:id=\"13\"><w:p xmlns:other=\"urn:unrelated\" other:paraId=\"EEEEEEEE\"><w:r><w:t>Wrong namespace.</w:t></w:r></w:p></w:comment></w:comments>",
            "word/commentsExtended.xml" to "<w15:commentsEx xmlns:w15=\"$w15\">" +
                reply("44444444", "33333333") + reply("33333333", "22222222") +
                reply("AAAAAAAA", "DDDDDDDD") + reply("BBBBBBBB", "CCCCCCCC") + reply("CCCCCCCC", "BBBBBBBB") +
                reply("EEEEEEEE", "22222222") + "</w15:commentsEx>"
        )
        archive(parts) { file ->
            val notes = OfficeNotes.read(file, "DOCX", CancellationSignal())
            assertFalse(notes.truncated)
            assertEquals(listOf("Opening.\nRoot.", "Nested reply.", "First reply."), notes.entries.map { it.text })
            assertTrue(notes.entries.all { it.page == null })
            assertEquals(3, JSONObject(notes.appendTo("{}")).getJSONArray("notes").length())
        }
        val cycle = parts.getValue("word/commentsExtended.xml").replace("</w15:commentsEx>", reply("22222222", "44444444") + "</w15:commentsEx>")
        archive(parts + ("word/commentsExtended.xml" to cycle)) { file ->
            assertEquals(3, OfficeNotes.read(file, "DOCX", CancellationSignal()).entries.size)
        }
    }

    @Test fun spreadsheetSeparatesSheetAndCellAndPrefersThreadedComments() = archive(mapOf(
        "xl/workbook.xml" to "<workbook xmlns=\"$s\" xmlns:r=\"$rel\"><sheets><sheet name=\"Budget\" r:id=\"one\"/><sheet name=\"Forecast\" r:id=\"two\"/></sheets></workbook>",
        "xl/_rels/workbook.xml.rels" to relationships(Triple("one", "worksheet", "worksheets/sheet1.xml"), Triple("two", "worksheet", "worksheets/sheet2.xml"), Triple("person", "person", "persons/person.xml")),
        "xl/persons/person.xml" to "<personList xmlns=\"http://schemas.microsoft.com/office/spreadsheetml/2018/threadedcomments\"><person id=\"person-1\" displayName=\"Mina\"/></personList>",
        "xl/worksheets/_rels/sheet1.xml.rels" to relationships(Triple("comments", "comments", "../comments1.xml")),
        "xl/worksheets/_rels/sheet2.xml.rels" to relationships(Triple("comments", "comments", "../comments2.xml"), Triple("threaded", "threadedComment", "../threadedComments/threaded.xml")),
        "xl/comments1.xml" to "<comments xmlns=\"$s\"><authors><author>Lee</author></authors><commentList><comment ref=\"C4\" authorId=\"0\"><text><r><t>Confirm costs.</t></r></text></comment></commentList></comments>",
        "xl/comments2.xml" to "<comments xmlns=\"$s\"><commentList><comment ref=\"A1\"><text><t>[Threaded comment]&#10;Discuss forecast.</t></text></comment><comment ref=\"C2\"><text><t>Legacy-only note.</t></text></comment></commentList></comments>",
        "xl/threadedComments/threaded.xml" to "<ThreadedComments xmlns=\"http://schemas.microsoft.com/office/spreadsheetml/2018/threadedcomments\"><threadedComment ref=\"A1\" personId=\"person-1\"><text>Discuss forecast.</text></threadedComment></ThreadedComments>"
    )) { file ->
        val notes = OfficeNotes.read(file, "XLSX", CancellationSignal()).entries
        assertEquals(3, notes.size)
        assertEquals("Budget!C4", notes[0].location)
        assertEquals("Lee", notes[0].author)
        assertEquals("Forecast!A1", notes[1].location)
        assertEquals("Mina", notes[1].author)
        assertEquals("Discuss forecast.", notes[1].text)
        assertEquals("Forecast!C2", notes[2].location)
        assertEquals("Legacy-only note.", notes[2].text)
        assertTrue(notes.all { it.page == null })
    }

    @Test fun odfAnnotationsAndSpeakerNotesKeepOwnershipAndBoundLongNotes() {
        val xml = """<office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0" xmlns:presentation="urn:oasis:names:tc:opendocument:xmlns:presentation:1.0" xmlns:dc="http://purl.org/dc/elements/1.1/"><office:body><office:presentation><draw:page draw:name="Results"><text:p>Body<office:annotation><dc:creator>Sam</dc:creator><text:p>Comment.</text:p></office:annotation></text:p><presentation:notes><text:p>${"Note ".repeat(4000)}</text:p></presentation:notes></draw:page></office:presentation></office:body></office:document-content>"""
        archive(mapOf("content.xml" to xml)) { file ->
            val notes = OfficeNotes.read(file, "ODP", CancellationSignal())
            assertEquals(2, notes.entries.size)
            assertEquals("Comment.", notes.entries[0].text)
            assertEquals("Sam", notes.entries[0].author)
            assertEquals("Results", notes.entries[0].location)
            assertEquals(0, notes.entries[1].page)
            assertEquals("speaker-note", notes.entries[1].kind)
            assertTrue(notes.truncated)
            assertTrue(notes.entries[1].text.length <= 16 * 1024)
        }
        fun annotation(value: String) = "<office:annotation><text:p>$value</text:p></office:annotation>"
        val chunk = "x".repeat(16 * 1024)
        val boundary = xml.substringBefore("<office:body>") + "<office:body><office:text>" + buildString {
            repeat(31) { append(annotation(chunk)) }
            append(annotation(chunk.dropLast(1)))
            append(annotation("😀"))
        } + "</office:text></office:body></office:document-content>"
        archive(mapOf("content.xml" to boundary)) { source ->
            val notes = OfficeNotes.read(source, "ODT", CancellationSignal())
            assertEquals(32, notes.entries.size)
            assertEquals(512 * 1024 - 1, notes.entries.sumOf { it.text.length })
            assertTrue(notes.entries.all { it.text.isNotEmpty() })
            assertEquals(chunk.dropLast(1), notes.entries.last().text)
            assertTrue(notes.truncated)
            assertEquals(32, JSONObject(notes.appendTo("{}")).getJSONArray("notes").length())
        }
        val table = """<table:table table:name="Outer"><table:table-row><table:table-cell>${annotation("Outer cell")}<table:table table:name="Inner"><table:table-row><table:table-cell>${annotation("Inner cell")}</table:table-cell></table:table-row></table:table>${annotation("After inner")}</table:table-cell><table:table-cell>${annotation("Next cell")}</table:table-cell></table:table-row></table:table>${annotation("After table")}"""
        val scoped = xml.replace("<office:body>", "<office:body xmlns:table=\"urn:oasis:names:tc:opendocument:xmlns:table:1.0\">")
            .replace("<text:p>Body", table + "<text:p>Body")
            .replace("</draw:page>", "</draw:page>" + annotation("After page") + "<draw:page draw:name=\"Next\">" + annotation("Next page") + "</draw:page>")
        archive(mapOf("content.xml" to scoped)) { source ->
            val locations = OfficeNotes.read(source, "ODP", CancellationSignal()).entries.associateBy { it.text }
            assertEquals("Outer!R1C1", locations.getValue("Outer cell").location)
            assertEquals("Inner!R1C1", locations.getValue("Inner cell").location)
            assertEquals("Outer!R1C1", locations.getValue("After inner").location)
            assertEquals("Outer!R1C2", locations.getValue("Next cell").location)
            assertEquals("Results", locations.getValue("After table").location)
            assertEquals("Document", locations.getValue("After page").location)
            assertNull(locations.getValue("After page").page)
            assertEquals(1, locations.getValue("Next page").page)
        }
        val file = File(context.cacheDir, "notes-${UUID.randomUUID()}.fodp")
        try {
            file.writeText(xml)
            assertEquals(2, OfficeNotes.read(file, "FODP", CancellationSignal()).entries.size)
        } finally { file.delete() }
    }

    @Test fun maliciousRelationshipsEntitiesAndCancellationDoNotReadExternalContent() {
        for (target in listOf("../../../secret.xml", "https://example.invalid/comments.xml", "%2e%2e/%2e%2e/secret.xml"))
            archive(mapOf("word/_rels/document.xml.rels" to relationships(Triple("comments", "comments", target)))) { file ->
                assertThrows(IOException::class.java) { OfficeNotes.read(file, "DOCX", CancellationSignal()) }
            }
        archive(mapOf("word/_rels/document.xml.rels" to "<!DOCTYPE x [<!ENTITY content SYSTEM 'file:///proc/self/status'>]><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">&content;</Relationships>")) { file ->
            assertThrows(IOException::class.java) { OfficeNotes.read(file, "DOCX", CancellationSignal()) }
            assertThrows(OperationCanceledException::class.java) { OfficeNotes.read(file, "DOCX", CancellationSignal().also { it.cancel() }) }
        }
    }
}
