package app.smallthingz.alldoc.sources

import android.net.Uri
import app.smallthingz.alldoc.engine.DocumentSession
import java.io.Closeable

/** Format fallback for a saved source URI; admission must verify its complete content hash first. */
data class SavedFormat(val hash: String, val displayName: String, val mimeType: String?)

/** SHA-256 identifies the exact snapshot parsed by session. Closing releases that snapshot. */
data class PreparedDocument(val hash: String, val sizeBytes: Long, val displayName: String,
    val mimeType: String?, val session: DocumentSession, val sourceUri: Uri?, val importUri: Uri?,
    val evictedHashes: List<String> = emptyList(), val retentionWarning: String? = null,
    val importVerified: Boolean = false) : Closeable {
    override fun close() = session.close()
}

/** Reconcile this bounded trim batch before continuing while trimPending is true. */
data class CopyStatus(val bytes: Long, val count: Int, val location: String, val warning: String? = null,
    val evictedHashes: List<String> = emptyList(), val trimPending: Boolean = false)
/** A bounded deletion batch. Reconcile its receipts before requesting the continuation. */
data class CopyChange(val evictedHashes: List<String>, val warning: String? = null, val continuation: String? = null)

data class CopyEviction(val id: Long, val hash: String, val importUri: String)

/** A completed snapshot proved that a previously indexed hash no longer names these bytes. */
class LibrarySourceChangedException : java.io.IOException("This document has changed. Refresh the Library.")

/** A readable app-private cache proved that this indexed local source no longer exists. */
class LibrarySourceMissingException : java.io.IOException("This cached document is no longer available.")
