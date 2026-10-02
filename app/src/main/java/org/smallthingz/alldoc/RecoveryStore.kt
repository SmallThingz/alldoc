package org.smallthingz.alldoc

import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject
import org.smallthingz.alldoc.core.DocumentEngine

/** App-private crash recovery. One atomic pointer selects an immutable, fsynced original. */
class RecoveryStore(private val root: File) {
  data class Snapshot(
    val name: String,
    val original: File,
    val edits: Map<String, String>,
    val isPdf: Boolean,
    val sourceUri: String?,
  )

  private val index = AtomicFile(File(root, "session.json"))
  private val revisions = AtomicLong()
  private val originalName = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")

  init {
    require(root.isDirectory || root.mkdirs()) { "Recovery storage is unavailable" }
  }

  /**
   * Reserve before asynchronous dispatch so an older queued writer can never overwrite a newer
   * draft.
   */
  fun reserveRevision(): Long = revisions.incrementAndGet()

  @Synchronized
  fun save(
    name: String,
    source: File,
    edits: Map<String, String>,
    isPdf: Boolean,
    sourceUri: String? = null,
    revision: Long = reserveRevision(),
  ): Boolean {
    if (revision != revisions.get()) return false
    require(
      source.parentFile?.canonicalFile == root.canonicalFile &&
        originalName.matches(source.name) &&
        source.isFile
    )
    require(
      edits.size <= DocumentEngine.MAX_BLOCKS &&
        edits.values.sumOf { it.length.toLong() } <= DocumentEngine.MAX_EDIT_CHARACTERS
    )
    val document =
      JSONObject()
        .put("version", 2)
        .put("name", name)
        .put("original", source.name)
        .put("pdf", isPdf)
        .put("edits", JSONObject(edits))
        .put("sourceUri", sourceUri ?: JSONObject.NULL)
    val bytes = document.toString().toByteArray(Charsets.UTF_8)
    val output = index.startWrite()
    try {
      output.write(bytes)
      output.fd.sync()
      if (revision != revisions.get()) {
        index.failWrite(output)
        return false
      }
      index.finishWrite(output)
      // AtomicFile logs some platform rename failures instead of throwing them.
      check(
        index
          .openRead()
          .use { DocumentEngine.readBounded(it, DocumentEngine.MAX_FILE) }
          .contentEquals(bytes)
      ) {
        "Recovery commit could not be verified"
      }
    } catch (failure: Exception) {
      index.failWrite(output)
      throw failure
    }
    return true
  }

  @Synchronized
  fun read(): Snapshot? {
    // openRead restores AtomicFile's backup, even when the base file disappeared in a crash.
    val input =
      try {
        index.openRead()
      } catch (_: FileNotFoundException) {
        return null
      }
    val json = input.use {
      JSONObject(String(DocumentEngine.readBounded(it, DocumentEngine.MAX_FILE), Charsets.UTF_8))
    }
    val version = json.getInt("version")
    require(version == 1 || version == 2)
    val filename = json.getString("original")
    require(originalName.matches(filename))
    val original = File(root, filename)
    require(original.isFile && original.length() <= DocumentEngine.MAX_FILE)
    val changes = json.getJSONObject("edits")
    require(changes.length() <= DocumentEngine.MAX_BLOCKS)
    val edits = changes.keys().asSequence().associateWith { changes.getString(it) }
    require(edits.values.sumOf { it.length.toLong() } <= DocumentEngine.MAX_EDIT_CHARACTERS)
    val name = json.getString("name")
    return Snapshot(
      name,
      original,
      edits,
      if (version == 2) json.getBoolean("pdf") else name.endsWith(".pdf", true),
      if (json.has("sourceUri") && !json.isNull("sourceUri")) json.getString("sourceUri") else null,
    )
  }

  fun directory(): File = root

  /** Call only after a successful commit; never remove an original referenced by recovery. */
  @Synchronized
  fun prune() {
    val keep = read()?.original ?: return
    root
      .listFiles()
      ?.filter { originalName.matches(it.name) && it != keep }
      ?.forEach { it.delete() }
  }

  @Synchronized
  fun clear() {
    index.delete()
    root.listFiles()?.filter { originalName.matches(it.name) }?.forEach { it.delete() }
  }
}
