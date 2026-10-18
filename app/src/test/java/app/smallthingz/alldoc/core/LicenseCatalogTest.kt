package app.smallthingz.alldoc.core

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class LicenseCatalogTest {
    @Test fun groupsNativeDependenciesWithoutLosingTheirDifferentLicensesOrAttribution() {
        val catalog = licenseCatalog(mapOf(
            "pdf-notices.txt" to "===== adler2-2.0.1-LICENSE-MIT =====\nPermission from author.\n\n===== adler2-2.0.1-LICENSE-APACHE =====\nApache conditions.",
            "document-notices.txt" to "Unresolved engine provenance.\n\ndoc-adler2-2.0.1-LICENSE-MIT.txt\n${"=".repeat(72)}\nPermission from author.",
            "render-notices.txt" to "Office provenance.\n\nnative/render/notices/adler2-2.0.1-LICENSE-MIT\nnative/render/notices/other-1.2.3-LICENSE-MIT\n\nPermission from author."))
        val adler = catalog.single { it.name == "adler2" }
        assertEquals("2.0.1", adler.version)
        assertEquals(setOf("MIT", "Apache-2.0"), adler.notices.map { it.title }.toSet())
        assertEquals(2, adler.notices.size)
        assertEquals("Permission from author.", catalog.single { it.name == "other" }.notices.single().text)
        assertEquals("Unresolved engine provenance.", catalog.single { it.name == "Document engine" }.notices.single().text)
    }

    @Test fun resolvesApplicationCoordinatesAndRetainsUnmappedUpstreamNotices() {
        val catalog = licenseCatalog(mapOf("application-notices.txt" to """
            g:sample:1.0 — MIT
            other:sample:2.0 — Apache-2.0
            ${"=".repeat(72)}
            Notice SHA-256: hash
            Sources: g:sample:1.0!META-INF/LICENSE

            Complete MIT text.
            ${"=".repeat(72)}
            Notice SHA-256: another
            Sources: https://example.org/LICENSE

            Shared upstream text.
        """.trimIndent()))
        assertEquals(3, catalog.size)
        assertEquals("Complete MIT text.", catalog.single { it.id == "g:sample:1.0" }.notices.single().text)
        assertTrue(catalog.single { it.id == "application" }.notices.any { "Shared upstream text." in it.text })
        assertEquals("2.0", catalog.single { it.id == "other:sample:2.0" }.version)
    }

    @Test fun bundledCatalogKeepsEveryLegalLineAndIndexesActualDependencies() {
        val directory = listOf(File("src/main/assets"), File("app/src/main/assets")).first { it.isDirectory }
        val assets = directory.listFiles()!!.filter { it.name.endsWith("notices.txt") }.associate { it.name to it.readText() }
        val catalog = licenseCatalog(assets)
        assertTrue(catalog.size > 150)
        assertEquals(catalog.size, catalog.map { it.id }.distinct().size)
        for (name in listOf("DeckCraft", "GridCraft", "WordCraft", "adler2", "jsoup", "activity-compose"))
            assertTrue(name, catalog.any { it.name == name })
        val retained = catalog.flatMap { it.notices }.flatMap { it.text.lineSequence().map(String::trim).toList() }.toSet()
        for ((asset, text) in assets) for (line in text.lineSequence()) {
            val legal = line.trim()
            if (legal.length > 30 && !legal.startsWith("=====") && !legal.startsWith("native/") &&
                !legal.startsWith("doc-") && !legal.startsWith("Sources:") && !legal.startsWith("Notice SHA-256:"))
                assertTrue("$asset: $legal", legal in retained)
        }
    }
}
