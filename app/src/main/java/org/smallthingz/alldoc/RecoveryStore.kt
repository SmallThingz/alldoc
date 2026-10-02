package org.smallthingz.alldoc

import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

/** App-private crash recovery. One atomic pointer selects an immutable original and its edits. */
class RecoveryStore(private val root: File) {
    data class Snapshot(val name: String, val original: File, val edits: Map<String, String>)
    private val index = AtomicFile(File(root, "session.json"))
    init { root.mkdirs() }
    @Synchronized fun save(name: String, source: File, edits: Map<String, String>) {
        val document = JSONObject().put("version", 1).put("name", name).put("original", source.name).put("edits", JSONObject(edits))
        val output = index.startWrite()
        try { output.write(document.toString().toByteArray(Charsets.UTF_8)); index.finishWrite(output) }
        catch (failure: Exception) { index.failWrite(output); throw failure }
    }
    @Synchronized fun read(): Snapshot? {
        if (!index.baseFile.exists()) return null
        val json = index.openRead().use { JSONObject(String(it.readBytes(), Charsets.UTF_8)) }
        require(json.getInt("version") == 1)
        val filename = json.getString("original")
        require(filename.matches(Regex("[a-f0-9-]+")))
        val original = File(root, filename)
        require(original.isFile)
        val changes = json.getJSONObject("edits")
        val edits = changes.keys().asSequence().associateWith { changes.getString(it) }
        return Snapshot(json.getString("name"), original, edits)
    }
    fun directory(): File = root
    @Synchronized fun clear() { index.delete(); root.listFiles()?.forEach { it.delete() } }
}
