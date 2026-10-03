package org.smallthingz.alldoc

import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.json.JSONObject
import org.smallthingz.alldoc.core.DocumentEngine

data class RecoveredDraft(val id: String, val name: String, val file: File)

/** Upgrade-only quarantine: old positional IDs must never target a new engine's blocks. */
class RecoveredDraftStore(private val directory: File) {
    @Synchronized
    fun preserve(snapshot: RecoveryStore.Snapshot): RecoveredDraft {
        require(directory.isDirectory || directory.mkdirs()) {
            "Recovered draft storage is unavailable"
        }
        val name = snapshot.name.take(512)
        val changes =
            JSONObject()
                .put("document", name)
                .put("mappingVersion", snapshot.mappingVersion)
                .put(
                    "note",
                    "Positional IDs from an earlier alldoc version. Do not apply automatically to newer document blocks.",
                )
                .put("edits", JSONObject(snapshot.edits))
                .toString()
                .toByteArray(Charsets.UTF_8)
        val extension =
            name.substringAfterLast('.', "bin").lowercase().takeIf {
                it.matches(Regex("[a-z0-9]{1,10}"))
            } ?: "bin"
        val draftText = buildString {
            append(
                "Recovered draft: $name\n\nThese edits were preserved during an alldoc upgrade. The included original is unchanged. Match these text edits to the original before applying them in an office editor.\n\n"
            )
            snapshot.edits.forEach { (key, value) -> append("[$key]\n$value\n\n") }
        }
            .toByteArray(Charsets.UTF_8)
        val digest =
            MessageDigest.getInstance("SHA-256")
                .digest(snapshot.original.name.toByteArray() + changes)
        val id = digest.joinToString("") { "%02x".format(it) }
        val target = File(directory, "$id.zip")
        if (!target.isFile) {
            val atomic = AtomicFile(target)
            val output = atomic.startWrite()
            try {
                // Explicitly close ZIP metadata before committing; AtomicFile owns the output FD.
                val zip =
                    ZipOutputStream(
                        object : java.io.FilterOutputStream(output) {
                            override fun close() {
                                flush()
                            }
                        }
                    )
                zip.use {
                    it.putNextEntry(ZipEntry("original.$extension"))
                    snapshot.original.inputStream().use { input ->
                        it.write(DocumentEngine.readBounded(input, DocumentEngine.MAX_FILE))
                    }
                    it.closeEntry()
                    it.putNextEntry(ZipEntry("draft-changes.json"))
                    it.write(changes)
                    it.closeEntry()
                    it.putNextEntry(ZipEntry("draft-changes.txt"))
                    it.write(draftText)
                    it.closeEntry()
                    it.putNextEntry(ZipEntry("manifest.json"))
                    it.write(JSONObject().put("name", name).toString().toByteArray())
                    it.closeEntry()
                }
                output.fd.sync()
                atomic.finishWrite(output)
            } catch (failure: Exception) {
                atomic.failWrite(output)
                throw failure
            }
        }
        // Never accept a stale/corrupt target just because its filename exists. Validate every
        // indispensable entry before the caller is allowed to replace or prune old recovery.
        ZipFile(target).use { zip ->
            check(
                zip.getInputStream(zip.getEntry("draft-changes.json"))
                    .use { DocumentEngine.readBounded(it, DocumentEngine.MAX_FILE) }
                    .contentEquals(changes)
            ) {
                "Recovered draft text could not be verified"
            }
            check(
                zip.getInputStream(zip.getEntry("draft-changes.txt"))
                    .use { DocumentEngine.readBounded(it, DocumentEngine.MAX_FILE) }
                    .contentEquals(draftText)
            ) {
                "Recovered draft text could not be verified"
            }
            val originalDigest = snapshot.original.inputStream().use { digestBounded(it) }
            val archiveDigest =
                zip.getInputStream(zip.getEntry("original.$extension")).use { digestBounded(it) }
            check(originalDigest.contentEquals(archiveDigest)) {
                "Recovered original could not be verified"
            }
            val manifestName =
                zip.getInputStream(zip.getEntry("manifest.json")).use {
                    JSONObject(String(DocumentEngine.readBounded(it, 8192), Charsets.UTF_8))
                        .getString("name")
                }
            check(manifestName == name) { "Recovered draft metadata could not be verified" }
        }
        return RecoveredDraft(id, name, target)
    }

    @Synchronized
    fun list(): List<RecoveredDraft> =
        directory
            .listFiles()
            .orEmpty()
            .filter { it.name.matches(Regex("[a-f0-9]{64}\\.zip")) }
            .sortedByDescending { it.lastModified() }
            .mapNotNull { file ->
                runCatching {
                    val name =
                        ZipFile(file).use { zip ->
                            zip.getInputStream(zip.getEntry("manifest.json")).use {
                                JSONObject(
                                        String(DocumentEngine.readBounded(it, 8192), Charsets.UTF_8)
                                    )
                                    .getString("name")
                            }
                        }
                    RecoveredDraft(file.nameWithoutExtension, name, file)
                }
                    .getOrNull()
            }

    private fun digestBounded(input: java.io.InputStream): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            check(total <= DocumentEngine.MAX_FILE)
            digest.update(buffer, 0, count)
        }
        return digest.digest()
    }

    fun isOwned(draft: RecoveredDraft): Boolean =
        draft.file.parentFile?.canonicalFile == directory.canonicalFile &&
            draft.file.name == "${draft.id}.zip" &&
            draft.id.matches(Regex("[a-f0-9]{64}"))
}
