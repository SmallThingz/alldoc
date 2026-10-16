package org.oacp.android

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import org.json.JSONArray
import org.json.JSONObject
import android.util.Log
import java.io.FileNotFoundException
import java.io.IOException

/**
 * ContentProvider that exposes OACP metadata for assistant discovery.
 *
 * Auto-registered via manifest merger at authority `${applicationId}.oacp`.
 * Serves three endpoint families:
 *   - `/manifest`             → `assets/oacp.json` (application/json)
 *   - `/context`              → `assets/OACP.md`   (text/markdown)
 *   - `/entities/<entityType>` → dynamic entity data via [OacpEntitySource]
 *
 * The provider performs `__APPLICATION_ID__` substitution in oacp.json
 * so the same asset file works across debug/release build variants.
 *
 * To serve entities, register an [OacpEntitySource] via [setEntitySource]
 * in your Application's `onCreate()`.
 *
 * This provider is read-only. All write operations throw.
 */
public class OacpProvider : ContentProvider() {

    private lateinit var uriMatcher: UriMatcher

    override fun onCreate(): Boolean {
        val authority = ctx().packageName + Oacp.PROVIDER_AUTHORITY_SUFFIX
        uriMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(authority, Oacp.PROVIDER_PATH_MANIFEST, CODE_MANIFEST)
            addURI(authority, Oacp.PROVIDER_PATH_CONTEXT, CODE_CONTEXT)
            addURI(authority, "${Oacp.PROVIDER_PATH_ENTITIES}/*", CODE_ENTITIES)
        }
        return true
    }

    override fun getType(uri: Uri): String? = when (uriMatcher.match(uri)) {
        CODE_MANIFEST -> "application/json"
        CODE_CONTEXT -> "text/markdown"
        CODE_ENTITIES -> "application/json"
        else -> null
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (mode != "r") {
            throw FileNotFoundException("OACP provider is read-only")
        }

        return when (uriMatcher.match(uri)) {
            CODE_MANIFEST -> {
                val context = ctx()
                openManifestWithSubstitution(context, ASSET_MANIFEST)
            }
            CODE_CONTEXT -> {
                val context = ctx()
                openAssetDirect(context, ASSET_CONTEXT)
            }
            CODE_ENTITIES -> {
                val entityType = uri.lastPathSegment
                    ?: throw FileNotFoundException("Missing entity type in URI: $uri")
                val query = uri.getQueryParameter("q")
                openEntities(entityType, query)
            }
            else -> throw FileNotFoundException("Unknown OACP URI: $uri")
        }
    }

    private fun openManifestWithSubstitution(
        ctx: android.content.Context,
        assetPath: String
    ): ParcelFileDescriptor {
        val raw = ctx.assets.open(assetPath).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val substituted = raw.replace(Oacp.APPLICATION_ID_PLACEHOLDER, ctx.packageName)

        return pipeBytes(substituted.toByteArray(), "application/json")
    }

    private fun openAssetDirect(
        ctx: android.content.Context,
        assetPath: String
    ): ParcelFileDescriptor {
        val bytes = ctx.assets.open(assetPath).use { it.readBytes() }
        return pipeBytes(bytes, "text/markdown")
    }

    private fun openEntities(entityType: String, query: String?): ParcelFileDescriptor {
        val source = entitySource
            ?: throw FileNotFoundException("No OacpEntitySource registered")

        val entities = source.queryEntities(ctx(), entityType, query)

        val json = JSONObject().apply {
            put("entityType", entityType)
            put("items", JSONArray().apply {
                for (entity in entities) {
                    put(JSONObject().apply {
                        put("id", entity.id)
                        put("displayName", entity.displayName)
                        if (entity.aliases.isNotEmpty()) {
                            put("aliases", JSONArray(entity.aliases))
                        }
                        if (entity.description != null) {
                            put("description", entity.description)
                        }
                        if (entity.keywords.isNotEmpty()) {
                            put("keywords", JSONArray(entity.keywords))
                        }
                        if (entity.metadata.isNotEmpty()) {
                            try {
                                put("metadata", JSONObject(entity.metadata as Map<*, *>))
                            } catch (e: Exception) {
                                Log.w(TAG, "Skipping non-serializable metadata for entity ${entity.id}", e)
                            }
                        }
                    })
                }
            })
        }

        return pipeBytes(json.toString().toByteArray(), "application/json")
    }

    private fun pipeBytes(data: ByteArray, mimeType: String): ParcelFileDescriptor {
        return openPipeHelper(
            Uri.EMPTY, mimeType, null, data
        ) { output, _, _, _, bytes ->
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(output).use { stream ->
                    stream.write(bytes as ByteArray)
                }
            } catch (_: IOException) {
                // Pipe closed by reader — normal when caller reads partial content
            }
        }
    }

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor {
        val pfd = openFile(uri, mode)
            ?: throw FileNotFoundException("No content at $uri")
        return AssetFileDescriptor(pfd, 0, AssetFileDescriptor.UNKNOWN_LENGTH)
    }

    private fun ctx(): android.content.Context =
        context ?: throw IllegalStateException("OacpProvider not yet attached to a context")

    // Read-only provider — all write operations are unsupported.

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        throw UnsupportedOperationException("OACP provider is read-only")
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int {
        throw UnsupportedOperationException("OACP provider is read-only")
    }

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int {
        throw UnsupportedOperationException("OACP provider is read-only")
    }

    public companion object {
        private const val TAG: String = "OacpProvider"
        private const val CODE_MANIFEST: Int = 1
        private const val CODE_CONTEXT: Int = 2
        private const val CODE_ENTITIES: Int = 3
        private const val ASSET_MANIFEST: String = "oacp.json"
        private const val ASSET_CONTEXT: String = "OACP.md"

        @Volatile
        private var entitySource: OacpEntitySource? = null

        /**
         * Registers an [OacpEntitySource] for serving dynamic entity data.
         *
         * Call this in your Application's `onCreate()` before any entity
         * queries arrive. Only one source is supported — subsequent calls
         * replace the previous source.
         */
        @JvmStatic
        public fun setEntitySource(source: OacpEntitySource?) {
            entitySource = source
        }
    }
}
