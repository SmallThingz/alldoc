package app.smallthingz.alldoc.sources

import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LibraryAccessTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun sharedRootsDeduplicateAliasesAndKeepReadableVolumesAfterAnUnavailableEntry() {
        val primary = temporary.newFolder("primary")
        val child = File(primary, "child").apply { mkdir() }
        val secondary = temporary.newFolder("secondary")
        val ordinaryFile = temporary.newFile("document.md").apply { writeText("keep") }
        val unavailable = object : File(temporary.root, "unavailable") {
            override fun getCanonicalFile(): File = throw IOException("Volume unavailable")
        }
        val roots = LibraryAccess.readableRoots(listOf(primary, File(child, ".."), ordinaryFile,
            File(temporary.root, "missing"), unavailable, secondary, File(secondary, ".")))
        assertEquals(listOf(primary.canonicalFile, secondary.canonicalFile), roots)
        assertEquals("Root discovery must not alter source files", "keep", ordinaryFile.readText())
        assertTrue(child.isDirectory)
    }
}
