package app.smallthingz.alldoc.state

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryCategoryTest {
    @Test fun familySignaturesPreserveCompatibleNamesWithoutClassifyingMisleadingExtensions() {
        val compound = "application/x-ole-storage"
        for ((extension, category) in mapOf("doc" to LibraryCategory.DOCUMENTS, "dot" to LibraryCategory.DOCUMENTS,
            "xls" to LibraryCategory.SPREADSHEETS, "ppt" to LibraryCategory.PRESENTATIONS,
            "pps" to LibraryCategory.PRESENTATIONS, "pot" to LibraryCategory.PRESENTATIONS))
            assertEquals(category, LibraryCategory.of("document.$extension", compound))
        for (extension in listOf("pdf", "kt", "svg", "txt"))
            assertEquals(LibraryCategory.OTHER, LibraryCategory.of("renamed.$extension", compound))
        assertEquals(LibraryCategory.OTHER, LibraryCategory.of("graph.dot", "text/plain"))
        for ((extension, category) in mapOf("pages" to LibraryCategory.DOCUMENTS,
            "numbers" to LibraryCategory.SPREADSHEETS, "key" to LibraryCategory.PRESENTATIONS))
            assertEquals(category, LibraryCategory.of("document.$extension", "application/vnd.apple.pages"))
        assertEquals(LibraryCategory.DOCUMENTS, LibraryCategory.of("renamed.kt", "application/vnd.apple.pages"))
        assertEquals(LibraryCategory.SPREADSHEETS, LibraryCategory.of("renamed.pages", "application/vnd.apple.numbers"))
    }
}
