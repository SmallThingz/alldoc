package org.smallthingz.alldoc

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

data class RecentDocument(
  val uri: String,
  val name: String,
  val openedAt: Long,
  val persistentRead: Boolean,
)

/** Metadata only. Originals remain with their document provider; no broad storage permission. */
class RecentDocuments(private val context: Context) {
  private val preferences = context.getSharedPreferences("recent-documents", Context.MODE_PRIVATE)

  @Synchronized
  fun read(): List<RecentDocument> = runCatching {
    val array = JSONArray(preferences.getString("items", "[]"))
    (0 until array.length().coerceAtMost(12)).map { index ->
      val item = array.getJSONObject(index)
      RecentDocument(
        item.getString("uri"),
        item.getString("name"),
        item.getLong("time"),
        item.optBoolean("persistent"),
      )
    }
  }
    .getOrDefault(emptyList())

  @Synchronized
  fun record(uri: Uri, name: String): List<RecentDocument> {
    require(uri.toString().length <= 4096) { "Document URI is too long for recent history" }
    val persistent = runCatching {
      context.contentResolver.takePersistableUriPermission(
        uri,
        Intent.FLAG_GRANT_READ_URI_PERMISSION,
      )
      true
    }.getOrDefault(false)
    val current =
      RecentDocument(uri.toString(), name.take(512), System.currentTimeMillis(), persistent)
    val previous = read()
    val items = (listOf(current) + previous.filter { it.uri != current.uri }).take(12)
    val json = JSONArray()
    items.forEach {
      json.put(
        JSONObject()
          .put("uri", it.uri)
          .put("name", it.name)
          .put("time", it.openedAt)
          .put("persistent", it.persistentRead)
      )
    }
    check(preferences.edit().putString("items", json.toString()).commit()) {
      "Recent file history could not be saved"
    }
    previous
      .filter { old -> old.persistentRead && items.none { it.uri == old.uri } }
      .forEach { old ->
        runCatching {
          context.contentResolver.releasePersistableUriPermission(
            Uri.parse(old.uri),
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
          )
        }
      }
    return items
  }
}
