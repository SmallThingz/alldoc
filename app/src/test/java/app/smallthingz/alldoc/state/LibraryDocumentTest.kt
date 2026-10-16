package app.smallthingz.alldoc.state

import org.junit.Assert.*
import org.junit.Test

class LibraryDocumentTest {
    @Test fun documentCategoriesUseResolvedContentAndKeepStableStorageCodes() {
        assertEquals(listOf(0, 1, 2, 3), LibraryCategory.available.map { it.code })
        for ((name, mime, expected) in listOf(
            Triple("readme.md", "text/plain", LibraryCategory.DOCUMENTS),
            Triple("readme.md", "Application/Octet-Stream; charset=UTF-8", LibraryCategory.DOCUMENTS),
            Triple("site.html", "text/html", LibraryCategory.DOCUMENTS),
            Triple("renamed.txt", "application/pdf", LibraryCategory.DOCUMENTS),
            Triple("template.dot", "application/x-ole-storage", LibraryCategory.DOCUMENTS),
            Triple("deck.key", "application/vnd.apple.pages", LibraryCategory.PRESENTATIONS),
            Triple("sheet.numbers", "application/vnd.apple.pages", LibraryCategory.SPREADSHEETS),
            Triple("book.fb2", "text/plain", LibraryCategory.BOOKS),
            Triple("code.dot", "text/plain", LibraryCategory.OTHER),
            Triple("renamed.pdf", "image/png", LibraryCategory.OTHER),
            Triple("mail.eml", "text/plain", LibraryCategory.OTHER),
        )) assertEquals(name, expected, LibraryCategory.of(name, mime))
        assertEquals(LibraryCategory.IMAGES, LibraryCategory.fromCode(6))
        assertFalse(LibraryCategory.IMAGES in LibraryCategory.available)
    }
}
