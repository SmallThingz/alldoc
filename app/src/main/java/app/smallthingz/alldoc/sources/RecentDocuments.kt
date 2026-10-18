package app.smallthingz.alldoc.sources

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.Environment
import android.os.OperationCanceledException
import android.os.Process
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import app.smallthingz.alldoc.state.LibraryCatalog
import app.smallthingz.alldoc.state.StoredDocument
import java.io.File

/** History is hash-bound; only its visible locations are disposable. Never read document payloads. */
internal object RecentDocuments {
    fun visible(context: Context, documents: List<StoredDocument>, signal: CancellationSignal,
        retained: (String) -> Uri?): List<StoredDocument> {
        require(documents.size <= 200)
        val roots = (listOf(context.dataDir, context.filesDir, context.cacheDir) + LibraryAccess.roots(context))
            .mapNotNull { runCatching { it.canonicalFile }.getOrNull() }.distinct()
        val checked = HashMap<String, Boolean>()
        return LibraryCatalog(context).use { catalog -> documents.filter { saved ->
            if (signal.isCanceled) true else try {
                keep(saved, { retained(it)?.toString() }, { catalog.locationsForHash(it, 17) }) { location ->
                    checked.getOrPut(location) { missing(context, Uri.parse(location), roots, signal) }
                }
            } catch (_: OperationCanceledException) { true }
            catch (_: Exception) { true } // Provider/volume/permission failure does not prove deletion.
        } }
    }

    internal fun keep(saved: StoredDocument, retained: (String) -> String?, aliases: (String) -> List<String>,
        missing: (String) -> Boolean): Boolean {
        if (listOfNotNull(saved.metadata.sourceUri, saved.metadata.importUri).distinct().any { !missing(it) }) return true
        retained(saved.hash)?.let { if (!missing(it)) return true }
        val indexed = aliases(saved.hash)
        // Do not hide a hash when the bounded lookup may have omitted another usable location.
        return indexed.size == 17 || indexed.any { !missing(it) }
    }

    private fun missing(context: Context, uri: Uri, roots: List<File>, signal: CancellationSignal): Boolean {
        signal.throwIfCanceled()
        if (uri.scheme == "file") {
            val file = File(uri.path ?: return false).canonicalFile
            val root = roots.firstOrNull { file != it && file.toPath().startsWith(it.toPath()) } ?: return false
            if (!Os.access(root.path, OsConstants.R_OK or OsConstants.X_OK)) return false
            return try { !OsConstants.S_ISREG(Os.stat(file.path).st_mode) }
            catch (failure: ErrnoException) { failure.errno == OsConstants.ENOENT || failure.errno == OsConstants.ENOTDIR }
        }
        if (uri.scheme != "content" || !localVolumeMounted(context, uri)) return false
        if (context.checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION) !=
            PackageManager.PERMISSION_GRANTED && !LibraryAccess.hasAccess(context)) return false
        val column = if (uri.authority == MediaStore.AUTHORITY) MediaStore.MediaColumns._ID
            else DocumentsContract.Document.COLUMN_DOCUMENT_ID
        val columns = if (uri.authority == MediaStore.AUTHORITY) arrayOf(column)
            else arrayOf(column, DocumentsContract.Document.COLUMN_MIME_TYPE)
        return context.contentResolver.query(uri, columns, null, null, null, signal)?.use { cursor ->
            signal.throwIfCanceled()
            missing(cursor)
        } ?: false
    }

    internal fun missing(cursor: Cursor): Boolean {
        if (!cursor.moveToFirst()) return true
        val mime = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
        return mime >= 0 && cursor.getString(mime) == DocumentsContract.Document.MIME_TYPE_DIR
    }

    private fun localVolumeMounted(context: Context, uri: Uri): Boolean {
        fun mounted(state: String) = state == Environment.MEDIA_MOUNTED || state == Environment.MEDIA_MOUNTED_READ_ONLY
        return when (uri.authority) {
            MediaStore.AUTHORITY -> {
                val volume = uri.pathSegments.firstOrNull() ?: return false
                if (volume == MediaStore.VOLUME_INTERNAL) true
                else if (Build.VERSION.SDK_INT >= 29) {
                    val volumes = MediaStore.getExternalVolumeNames(context)
                    volume in volumes || volume == MediaStore.VOLUME_EXTERNAL && volumes.isNotEmpty()
                } else volume == "external" && mounted(Environment.getExternalStorageState())
            }
            "com.android.externalstorage.documents" -> {
                val volume = DocumentsContract.getDocumentId(uri).substringBefore(':')
                context.getSystemService(StorageManager::class.java)?.storageVolumes.orEmpty().any {
                    (it.isPrimary && volume == "primary" || it.uuid.equals(volume, ignoreCase = true)) && mounted(it.state)
                }
            }
            // Downloads may be pending remote transfers; other providers may be offline.
            else -> false
        }
    }
}
