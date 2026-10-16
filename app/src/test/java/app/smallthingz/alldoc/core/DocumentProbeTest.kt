package app.smallthingz.alldoc.core

import java.io.ByteArrayOutputStream
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class DocumentProbeTest {
    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip -> entries.forEach { (name, data) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry()
        } }
    }.toByteArray()

    @Test fun contentOverridesMisleadingNamesAndKeepsUsefulTextHints() {
        val pdf = DocumentProbe.mime("%PDF-1.7\n".toByteArray())
        assertEquals("application/pdf", pdf)
        assertEquals("document.pdf", DocumentProbe.routingName("renamed.txt", "text/plain", pdf))
        assertEquals("text/plain", DocumentProbe.mime("नमस्ते · Document without an extension".toByteArray()))
        assertNull(DocumentProbe.documentName("not-pdf.pdf", "application/pdf", DocumentProbe.Identification("text/plain")))
        assertEquals("README.md", DocumentProbe.routingName("README.md", null, "text/plain"))
        assertNull(DocumentProbe.documentName("shared", "text/csv", DocumentProbe.Identification("text/plain")))
        assertEquals("drawing.fodg", DocumentProbe.routingName("drawing.fodg", null, "application/xml"))
        assertNull(DocumentProbe.mime(byteArrayOf(0, 1, 2, 3, 0, -1)))
        assertNull(DocumentProbe.mime("Binary\u0000content".toByteArray()))
        assertEquals("image/png", DocumentProbe.mime(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)))
        assertEquals("image/webp", DocumentProbe.mime("RIFF0000WEBP".toByteArray()))
    }

    @Test fun compoundSignaturesOverrideConflictingHintsWithoutConfusingGraphviz() {
        val signature = byteArrayOf(0xd0.toByte(), 0xcf.toByte(), 0x11, 0xe0.toByte(), 0xa1.toByte(), 0xb1.toByte(), 0x1a, 0xe1.toByte())
        val detected = DocumentProbe.mime(signature)
        assertEquals("application/x-ole-storage", detected)
        for (extension in listOf("jpg", "pdf", "svg", "docx", "txt")) {
            assertEquals("document.cfb", DocumentProbe.routingName("renamed.$extension", null, detected))
            assertEquals("document.cfb", DocumentProbe.routingName("renamed.$extension", "image/png", detected))
            assertEquals("document.doc", DocumentProbe.routingName("renamed.$extension", "application/msword", detected))
        }
        for (extension in listOf("doc", "dot", "xls", "ppt", "pps", "pot")) {
            val name = "original.$extension"
            assertEquals(name, DocumentProbe.routingName(name, "image/png", detected))
        }
        val graphviz = DocumentProbe.mime("digraph AllDoc { library -> document; }".toByteArray())
        assertEquals("text/plain", graphviz)
        assertNull(DocumentProbe.documentName("graph.dot", "application/msword", DocumentProbe.Identification(graphviz)))
    }

    @Test fun unclassifiedCompoundOpeningNeverAdvertisesOrIndexesGenericContainers() {
        val detected = DocumentProbe.Identification("application/x-ole-storage")
        for (name in listOf("renamed.jpg", "renamed.pdf", "renamed.svg", "renamed", "mail.msg")) {
            assertEquals("document.cfb", DocumentProbe.openingName(name, null, detected))
            assertNull(DocumentProbe.documentName(name, null, detected))
            assertFalse(DocumentProbe.supportedRecord(name, detected.mime))
        }
        assertFalse(DocumentFormats.supportedName("document.cfb"))
        assertEquals("original.doc", DocumentProbe.openingName("original.doc", null, detected))
        assertNull(DocumentProbe.openingName("image.png", "image/png", DocumentProbe.Identification("image/png")))
        assertNull(DocumentProbe.openingName("unknown.cfb", null, DocumentProbe.Identification(null)))
    }

    @Test fun admissionRequiresAnActualDocumentRatherThanGenericReadableBytes() {
        val plain = DocumentProbe.identify("Ordinary text and code".toByteArray())
        for (name in listOf("notes.txt", "script.kt", "mesh.obj", "mesh.stl", "data.json", "word.doc", "template.dot", "archive.zip", "image.svg", "mail.eml"))
            assertNull(name, DocumentProbe.documentName(name, null, plain))
        for (name in listOf("readme.md", "readme.markdown", "page.html", "book.fb2"))
            assertEquals(name, DocumentProbe.documentName(name, null, plain))
        assertEquals("document.md", DocumentProbe.documentName("shared", "text/markdown", plain))
        val der = DocumentProbe.identify(byteArrayOf(0x30, 0x82.toByte(), 0, 4, 0x30, 2, 0, 0))
        assertNull(DocumentProbe.documentName("certificate.md", "text/markdown", der))
        assertEquals("numbered.md", DocumentProbe.documentName("numbered.md", null,
            DocumentProbe.identify("0. Leading-number Markdown".toByteArray())))
        assertEquals("document.pdf", DocumentProbe.documentName("renamed.obj", "text/plain", DocumentProbe.identify("%PDF-1.7".toByteArray())))
        for (mime in listOf("image/png", "image/svg+xml", "image/vnd.adobe.photoshop", "text/calendar", "text/vcard", "application/pkix-cert", "message/rfc822"))
            assertNull(mime, DocumentProbe.documentName("readme.md", "text/markdown", DocumentProbe.Identification(mime)))
        assertNull(DocumentProbe.documentName("readme.md", null, DocumentProbe.Identification(null)))
        val flat = DocumentProbe.identify("<office:document xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\"/>".toByteArray())
        assertEquals("application/xml", flat.mime)
        assertEquals("drawing.fodg", DocumentProbe.documentName("drawing.fodg", null, flat))
        assertNull(DocumentProbe.documentName("data.xml", "application/xml", flat))
        val uncertain = DocumentProbe.Identification(null, true)
        assertEquals("document.docx", DocumentProbe.documentName("file.docx", null, uncertain))
        assertNull(DocumentProbe.documentName("file.png", "image/png", uncertain))
        assertNull(DocumentProbe.documentName("file.zip", null, uncertain))
    }

    @Test fun oldProviderHintsDoNotErasePotentiallyValidDocumentHistory() {
        for (mime in listOf(null, "", "text/plain", "Application/Xml; charset=UTF-8", "text/xml", "application/octet-stream")) {
            assertTrue(DocumentProbe.supportedHistory("Report.pdf", mime))
            assertTrue(DocumentProbe.supportedHistory("Book.epub", mime))
            assertFalse(DocumentProbe.supportedHistory("Notes.txt", mime))
        }
        assertFalse("Verified catalog MIME remains strict", DocumentProbe.supportedRecord("Report.pdf", "text/plain"))
        assertNull("Opening still verifies actual bytes", DocumentProbe.documentName("Report.pdf", "application/pdf", DocumentProbe.Identification("text/plain")))
        assertFalse(DocumentProbe.supportedHistory("Report.pdf", "image/png"))
        assertFalse(DocumentProbe.supportedHistory("Report.pdf", "message/rfc822"))
        assertTrue(DocumentProbe.supportedHistory("Renamed.txt", "application/pdf"))
    }

    @Test fun archiveProbeRecognizesDocumentsWithoutAdmittingArbitraryZipOrApk() {
        val word = zip("[Content_Types].xml" to "<Types><Override PartName=\"/word/document.xml\"/></Types>".toByteArray(),
            "word/document.xml" to "<document/>".toByteArray())
        assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document", DocumentProbe.mime(word))
        assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            DocumentProbe.mime(zip("xl/workbook.xml" to "<workbook/>".toByteArray())))
        assertEquals("application/vnd.openxmlformats-officedocument.presentationml.presentation",
            DocumentProbe.mime(zip("ppt/presentation.xml" to "<presentation/>".toByteArray())))
        assertEquals("application/vnd.oasis.opendocument.text",
            DocumentProbe.mime(zip("mimetype" to "application/vnd.oasis.opendocument.text".toByteArray())))
        assertEquals("application/epub+zip", DocumentProbe.mime(zip("META-INF/container.xml" to "<container/>".toByteArray())))
        assertNull(DocumentProbe.mime(zip("notes.txt" to "Plain archive".toByteArray())))
        assertNull(DocumentProbe.mime(zip("AndroidManifest.xml" to byteArrayOf(0, 1, 2), "classes.dex" to ByteArray(40))))
        assertNull(DocumentProbe.mime(zip("word/not-a-document.bin" to ByteArray(40))))
        assertNull(DocumentProbe.mime(zip("../word/document.xml" to "<document/>".toByteArray())))
    }

    @Test fun savedPreviewSignaturesPreserveOnlyCompatibleArchiveHints() {
        val pdf = "%PDF-1.7\n".toByteArray()
        val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte(), 0)
        for (name in listOf("QuickLook/Preview.pdf", "QuickLook/Thumbnail.jpg", "preview.jpg", "preview-micro.jpg", "preview-web.jpg")) {
            val detected = DocumentProbe.identify(zip(name to if (name.endsWith(".pdf")) pdf else jpeg))
            assertNull("A saved image does not certify an iWork package", detected.mime)
            assertTrue("A recognized saved preview retains a compatible hint", detected.inconclusive)
        }
        for (extension in listOf("pages", "numbers", "key"))
            assertEquals(DocumentFormats.mimeType("guide.$extension"), DocumentProbe.archiveHint("guide.$extension", null))
        assertNull(DocumentProbe.archiveHint("unrelated.zip", null))
        assertNull(DocumentProbe.archiveHint("unrelated.pdf", "application/pdf"))
        for ((name, bytes) in listOf("Preview.pdf" to pdf, "QuickLook/Preview.pdf" to "not PDF".toByteArray(),
            "preview.jpg" to pdf, "QuickLook/Preview.pdf" to jpeg)) {
            val detected = DocumentProbe.identify(zip(name to bytes))
            assertNull(detected.mime); assertFalse(detected.inconclusive)
        }
    }

    @Test fun embeddedOfficeTypesDoNotOverrideTheOuterPackage() {
        val formats = listOf(
            Triple("word/document.xml", "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            Triple("xl/workbook.xml", "xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            Triple("ppt/presentation.xml", "pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
        )
        for ((path, extension, mime) in formats) for ((_, embedded, embeddedMime) in formats) {
            if (extension == embedded) continue
            val types = """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                <Default Extension="$embedded" ContentType="$embeddedMime"/>
                <Override PartName = '/$path' ContentType="$mime.main+xml"/>
                </Types>""".toByteArray()
            assertEquals("$extension containing $embedded", mime, DocumentProbe.mime(zip(
                "[Content_Types].xml" to types, path to "<document/>".toByteArray(),
            )))
        }
        // An embedded-format declaration alone does not identify an Office document.
        val unrelated = """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
            <Default Extension="xlsx" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"/>
            <Override PartName="/notes/xl/workbook.xml" ContentType="application/xml"/>
            </Types>""".toByteArray()
        assertNull(DocumentProbe.mime(zip("[Content_Types].xml" to unrelated, "notes.txt" to "Notes".toByteArray())))
    }

    @Test fun probingBoundsInputExpansionAndObservesCancellation() {
        val source = "text ".repeat(5000).toByteArray()
        var position = 0
        assertEquals("text/plain", DocumentProbe.read({ bytes, offset, count ->
            val size = minOf(count, source.size - position)
            source.copyInto(bytes, offset, position, position + size); position += size; size
        }, { false }).mime)
        assertEquals(8192, position)
        val archive = zip("padding" to ByteArray(2 * 1024 * 1024), "word/document.xml" to "<document/>".toByteArray())
        val detected = DocumentProbe.identify(archive)
        assertTrue("A later document cannot bypass the expanded-data ceiling", detected.inconclusive)
        for (name in listOf("known.pdf", "shared", "archive.zip", "mail.eml", "image.png")) {
            assertNull("Scanning cannot certify an inconclusive archive", DocumentProbe.documentName(name, null, detected))
            assertEquals("document.zip", DocumentProbe.openingName(name, null, detected))
        }
        assertFalse(DocumentFormats.supportedName("document.zip"))
        assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document", DocumentProbe.archiveHint("known.docx", null))
        assertNull(DocumentProbe.archiveHint("known.pdf", null))
        val many = zip(*(1..33).map { "padding$it" to ByteArray(0) }.toTypedArray())
        assertTrue(DocumentProbe.identify(many).inconclusive)
        var checks = 0
        assertThrows(CancellationException::class.java) { DocumentProbe.mime(archive, cancel = { ++checks > 3 }) }
        assertTrue(checks <= 4)
        assertEquals("text/plain", DocumentProbe.mime(byteArrayOf(65, 0xf0.toByte(), 0x9f.toByte()), truncated = true))
    }
}
