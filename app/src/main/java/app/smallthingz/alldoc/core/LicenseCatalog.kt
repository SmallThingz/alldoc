package app.smallthingz.alldoc.core

internal data class LicenseNotice(val title: String, val text: String)
internal data class LicenseEntry(val id: String, val name: String, val version: String, val notices: List<LicenseNotice>) {
    val summary: String get() = (listOf(version) + notices.map { it.title }.distinct())
        .filter { it.isNotBlank() }.joinToString(" · ")
}

/** Index the bundled notices; their complete license and attribution text stays available offline. */
internal fun licenseCatalog(assets: Map<String, String>): List<LicenseEntry> {
    val entries = linkedMapOf<String, LicenseEntry>()
    fun add(id: String, name: String, version: String, label: String, text: String) {
        if (text.isBlank()) return
        val notice = LicenseNotice(label, text.trim())
        val previous = entries[id]
        entries[id] = if (previous == null) LicenseEntry(id, name, version, listOf(notice))
            else previous.copy(notices = (previous.notices + notice).distinct())
    }
    fun native(source: String, text: String) {
        val file = source.substringAfterLast('/').removePrefix("doc-")
        val crate = Regex("^(.+?)-(\\d+\\.\\d+[^-]*)-(.+)$").matchEntire(file)
        val name = crate?.groupValues?.get(1) ?: when {
            file.startsWith("storytold-deckcraft-") -> "DeckCraft"
            file.startsWith("storytold-gridcraft-") -> "GridCraft"
            file.startsWith("storytold-wordcraft-") -> "WordCraft"
            source == "native/render/office-fonts/LICENSE" -> "Office fonts adapter"
            else -> file.substringBeforeLast('.')
        }
        val version = crate?.groupValues?.get(2).orEmpty()
        val suffix = (crate?.groupValues?.get(3) ?: file).uppercase()
        val label = when {
            "ATTRIBUTION" in suffix -> "Attribution"
            "COPYRIGHT" in suffix || "NOTICE" in suffix || "DECLARATION" in suffix -> "Notices"
            "APACHE" in suffix -> "Apache-2.0"
            "0BSD" in suffix -> "0BSD"
            "BSD" in suffix -> "BSD"
            "MIT" in suffix -> "MIT"
            "UNICODE" in suffix -> "Unicode"
            "OFL" in suffix -> "OFL-1.1"
            "ZLIB" in suffix -> "Zlib"
            else -> "License"
        }
        add("native:$name:$version", name, version, label, text)
    }
    fun sections(text: String, pattern: Regex, prefix: (String) -> Unit, section: (MatchResult, String) -> Unit) {
        val matches = pattern.findAll(text).toList()
        prefix(text.substring(0, matches.firstOrNull()?.range?.first ?: text.length))
        matches.forEachIndexed { index, match ->
            section(match, text.substring(match.range.last + 1, matches.getOrNull(index + 1)?.range?.first ?: text.length))
        }
    }
    for ((asset, text) in assets) when (asset) {
        "pdf-notices.txt" -> sections(text, Regex("(?m)^===== (.+) =====\\r?$"),
            { add("pdf", "PDF engine", "", "Notices", it) }) { heading, body -> native(heading.groupValues[1], body) }
        "document-notices.txt" -> sections(text, Regex("(?m)^(doc-[^\\r\\n]+)\\r?\\n={72}\\r?$"),
            { add("documents", "Document engine", "", "Provenance", it) }) { heading, body -> native(heading.groupValues[1], body) }
        "render-notices.txt" -> sections(text, Regex("(?m)^(?:native/[^\\r\\n]+\\r?\\n)+(?=\\r?\\n)"),
            { add("office", "Office renderer", "", "Notices", it) }) { heading, body ->
            heading.value.lineSequence().filter { it.isNotBlank() }.forEach { native(it, body) }
        }
        "application-notices.txt" -> {
            val blocks = text.split(Regex("(?m)^={72}\\r?$"))
            val inventory = Regex("(?m)^([^\\s:]+:[^\\s:]+:[^\\s]+) — ([^\\r\\n]+)$")
                .findAll(blocks.first()).associate { it.groupValues[1] to it.groupValues[2] }
            add("application", "Application dependencies", "", "Inventory", blocks.first())
            for (block in blocks.drop(1)) {
                val source = block.lineSequence().firstOrNull { it.startsWith("Sources: ") }?.removePrefix("Sources: ").orEmpty()
                val body = block.lineSequence().dropWhile { it.isBlank() || it.startsWith("Notice SHA-256:") || it.startsWith("Sources:") }.joinToString("\n")
                val coordinates = source.split(", ").map { it.substringBefore('!') }.filter { it in inventory }.distinct()
                if (coordinates.isEmpty()) add("application", "Application dependencies", "", "Notices", block)
                for (coordinate in coordinates) {
                    val parts = coordinate.split(':')
                    add(coordinate, parts[1], parts[2], inventory.getValue(coordinate), body)
                }
            }
            // Some declared dependencies share a generic upstream notice rather than an archive-local one.
            for ((coordinate, license) in inventory) if (coordinate !in entries) {
                val parts = coordinate.split(':')
                add(coordinate, parts[1], parts[2], license, "$coordinate — $license\n\nSee Application dependencies for the bundled upstream notices.")
            }
        }
        "reverb-noise-license.txt" -> add("reverb-noise", "Reverb noise texture", "", "GPL-3.0", text)
        "alldoc-notices.txt" -> add("alldoc", "AllDoc", "", "License and notices", text)
        else -> add(asset, asset.removeSuffix(".txt").replace('-', ' '), "", "Notices", text)
    }
    return entries.values.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
}
