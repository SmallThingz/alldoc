package app.smallthingz.alldoc.core

import org.junit.Assert.*
import org.junit.Test

class DocumentFormatsTest {
    @Test fun registryContainsOnlyTheDocumentFamiliesAndUniqueExtensions() {
        val extensions = DocumentFormats.all.flatMap { it.extensions }
        assertEquals(46, extensions.size)
        assertEquals(extensions.size, extensions.toSet().size)
        assertEquals("word", DocumentFormats.find("template.dot")?.id)
        assertEquals("markdown", DocumentFormats.find("README.MARKDOWN")?.id)
        assertEquals("fictionbook", DocumentFormats.find("book.fb2")?.id)
        for (extension in listOf("txt", "json", "xml", "csv", "kt", "svg", "png", "psd", "tiff", "obj", "stl", "eml", "msg", "ics", "vcf", "pem"))
            assertFalse(extension, DocumentFormats.supportedName("file.$extension"))
        assertTrue(DocumentFormats.pickerMimeTypes.contains("text/markdown"))
        assertTrue("Plain MIME can carry a named Markdown file", DocumentFormats.pickerMimeTypes.contains("text/plain"))
        assertTrue(DocumentFormats.intentMimeTypes.contains("application/vnd.ms-word.document.macroEnabled.12"))
        assertFalse(DocumentFormats.intentMimeTypes.contains("text/plain"))
        assertEquals("application/msword", DocumentFormats.mimeType("template.dot"))
        assertEquals("text/markdown", DocumentFormats.mimeType("README.markdown"))
        assertFalse(DocumentFormats.pickerMimeTypes.any { '*' in it || it.startsWith("image/") })
    }
}
