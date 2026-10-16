package app.smallthingz.alldoc.sources

import android.content.Context
import android.content.ContentUris
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import android.os.OperationCanceledException
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.system.Os
import android.util.Log
import app.smallthingz.alldoc.core.DocumentFormats
import app.smallthingz.alldoc.core.DocumentParser
import app.smallthingz.alldoc.core.takeScalarSafe
import app.smallthingz.alldoc.engine.DocumentEngine
import app.smallthingz.alldoc.engine.DocumentSession
import app.smallthingz.alldoc.state.CopySettings
import app.smallthingz.alldoc.state.LibraryCatalog
import app.smallthingz.alldoc.state.LibraryDocument
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** UI source admission. I/O belongs on a worker; null copy settings leave retained payloads untouched. */
class DocumentSources internal constructor(context: Context, private val persistedRead: ((Uri) -> Boolean)? = null,
    private val findMediaUri: (Context, Uri) -> Uri?) : java.io.Closeable {
    constructor(context: Context) : this(context, findMediaUri = { application, uri ->
        if (Build.VERSION.SDK_INT >= 29) MediaStore.getMediaUri(application, uri) else null
    })
    private val context = context.applicationContext
    private val resolver get() = context.contentResolver
    private val copyIndex = lazy { ImportCopies(this.context) }
    private val copies get() = copyIndex.value
    private val libraryIndex = lazy { LibraryCatalog(this.context) }

    fun open(uri: Uri, settings: CopySettings?, signal: CancellationSignal, password: String? = null,
        savedFormat: SavedFormat? = null, mimeHint: String? = null): PreparedDocument {
        worker(); signal.throwIfCanceled()
        require(uri.scheme == "content" || uri.scheme == "file") { "Choose a local document." }
        var ownershipKnown = true
        val direct = try { copies.metadata(uri) } catch (failure: Exception) {
            Log.w("AllDoc", "Cannot read saved-copy metadata", failure)
            ownershipKnown = false; null
        }
        // A direct/canonical owned row needs no cross-provider conversion. Otherwise only the
        // platform's equivalent-item mapping may identify a MediaStore copy behind a Files URI.
        val retentionSource = if (direct == null && ownershipKnown) try { mediaSource(uri, signal) ?: uri }
            catch (cancelled: OperationCanceledException) { throw cancelled }
            catch (failure: Exception) {
                signal.throwIfCanceled()
                Log.w("AllDoc", "Cannot resolve equivalent document source", failure)
                ownershipKnown = false; uri
            }
        else uri
        val saved = if (retentionSource == uri) direct else try { copies.metadata(retentionSource) }
            catch (failure: Exception) {
                Log.w("AllDoc", "Cannot read equivalent saved-copy metadata", failure)
                ownershipKnown = false; null
            }
        val providerMime = if (uri.scheme == "content" && (saved?.expectedHash != null || usefulMime(saved?.mime) == null))
            optionalMetadata(signal) { resolver.getType(uri)?.take(128) } else null
        val providerName = if (uri.scheme == "content") optionalMetadata(signal) {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null, signal)?.use {
                val column = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0 && it.moveToFirst() && !it.isNull(column)) it.getString(column).takeScalarSafe(256) else null
            }
        } else null
        fun sourceName(metadata: ImportCopies.SourceMetadata?) = metadata?.name?.takeIf { it.isNotBlank() }
            ?: providerName?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeScalarSafe(256)?.ifBlank { "Document" } ?: "Document"
        fun sourceMime(metadata: ImportCopies.SourceMetadata?, name: String): String? {
            val reported = usefulMime(metadata?.mime) ?: providerMime ?: metadata?.mime
            return usefulMime(reported) ?: usefulMime(mimeHint)?.takeIf { DocumentFormats.routingName(name, it) != name } ?: reported
        }
        fun claim(hash: String?): ImportCopies.SourceMetadata? {
            if (!ownershipKnown) return null
            return try { copies.claim(retentionSource, hash, providerName, signal) }
            catch (cancelled: OperationCanceledException) { throw cancelled }
            catch (failure: Exception) {
                signal.throwIfCanceled()
                Log.w("AllDoc", "Cannot verify saved-copy ownership", failure)
                ownershipKnown = false; null
            }
        }
        // Keep the pre-open claim if another recovery pass removes its row while the FD is read.
        var claimed = saved ?: claim(null)
        val initial = saved?.takeIf { it.expectedHash == null }
        val name = sourceName(initial)
        val mime = sourceMime(initial, name)
        var snapshot: File? = null
        try {
            val admitted = admitSnapshot(uri, name, mime, signal)
            snapshot = admitted.first
            val hash = admitted.second
            if (claimed == null) claimed = claim(hash)
            val verified = claimed?.takeIf { it.matches(hash) }
            if (verified == null && claimed?.incomplete == true) ownershipKnown = false
            val readName = sourceName(verified)
            return finish(requireNotNull(snapshot), hash, readName, sourceMime(verified, readName), uri, recoverable(uri), settings, signal, password,
                verified != null, ownershipKnown, verified?.name?.isNotBlank() == true || providerName?.isNotBlank() == true,
                savedFormat, retentionSource)
        } catch (failure: Throwable) { snapshot?.delete(); throw failure }
    }

    /** Library/preview admission has no retention, catalog, or reading-state side effects. */
    fun openIndexed(document: LibraryDocument, signal: CancellationSignal, password: String? = null): PreparedDocument {
        worker(); signal.throwIfCanceled()
        val uri = Uri.parse(document.uri)
        require(document.uri.length <= 4096 && uri.scheme in setOf("content", "file")) { "Choose a local document." }
        val missing = LibraryInput.isMissingPrivateFile(context, uri)
        signal.throwIfCanceled()
        if (missing) throw LibrarySourceMissingException()
        val name = document.title.takeScalarSafe(256).ifBlank { "Document" }
        val mime = document.mimeType?.take(128)
        val (file, hash) = admitSnapshot(uri, name, mime, signal)
        var session: DocumentSession? = null
        try {
            if (document.hash != null && document.hash != hash) throw LibrarySourceChangedException()
            val bytes = file.length()
            session = DocumentEngine.openSnapshot(context, file, name, mime, signal, password)
            signal.throwIfCanceled()
            return PreparedDocument(hash, bytes, name, mime, session, uri, null)
        } catch (failure: Throwable) {
            try { session?.close() } finally { file.delete() }
            throw failure
        }
    }

    private fun admitSnapshot(uri: Uri, name: String, mime: String?, signal: CancellationSignal): Pair<File, String> {
        val input = LibraryInput.open(context, uri, signal)
        var file: File? = null
        try {
            val hash = input.use { fd ->
                file = DocumentEngine.newSnapshot(context)
                val digest = MessageDigest.getInstance("SHA-256")
                val prefix = ByteArray(8)
                val seekable = try { Os.pread(fd.fileDescriptor, prefix, 0, prefix.size, 0); true }
                    catch (_: android.system.ErrnoException) { false }
                val pdf = DocumentFormats.isPdf(name, mime) || prefix.copyOf(5).contentEquals("%PDF-".toByteArray())
                DocumentEngine.copyInput(fd, requireNotNull(file), if (pdf) DocumentEngine.PDF_INPUT_LIMIT else DocumentParser.INPUT_LIMIT.toLong(),
                    seekable, signal, digest, pdfHeaderLimit = DocumentEngine.PDF_INPUT_LIMIT)
                digest.digest().hex()
            }
            return requireNotNull(file) to hash
        } catch (failure: Throwable) { file?.delete(); throw failure }
    }

    private fun finish(snapshot: File, hash: String, name: String, mime: String?, original: Uri?, recoverable: Boolean,
        settings: CopySettings?, signal: CancellationSignal, password: String?, knownImport: Boolean = false,
        ownershipKnown: Boolean = true, nameKnown: Boolean = true, savedFormat: SavedFormat? = null,
        retentionSource: Uri? = original): PreparedDocument {
        // A saved format describes these bytes only; changing the payload rejects both hints.
        val verified = savedFormat?.takeIf { it.hash == hash }
        val readName = if (nameKnown) name else verified?.displayName?.takeScalarSafe(256)?.takeIf { it.isNotBlank() } ?: name
        val readMime = usefulMime(mime) ?: usefulMime(verified?.mimeType) ?: mime
        val local = if (ownershipKnown && recoverable && !knownImport) null else reusableLocal(hash, snapshot.length(), original, signal)?.let { Uri.parse(it.uri) }
        var session: DocumentSession? = null
        try {
            session = DocumentEngine.openSnapshot(context, snapshot, readName, readMime, signal, password)
            val retained = try {
                if (settings == null) ImportCopies.Result(copies.importUri(hash), knownImport, emptyList(), null)
                // A granted content URI is not a durable source when ownership prevents publishing it.
                else copies.retain(snapshot, hash, readName, readMime, retentionSource,
                    local != null || recoverable && (ownershipKnown || original?.scheme == "file"), settings, signal)
            }
            catch (failure: Exception) {
                signal.throwIfCanceled()
                Log.w("AllDoc", "Saved-copy storage unavailable", failure)
                ImportCopies.Result(null, false, emptyList(), "Could not save a copy.")
            }
            signal.throwIfCanceled()
            val warning = listOfNotNull(retained.warning, if (!ownershipKnown && retained.uri == null && local == null)
                "This file may be unavailable later." else null).joinToString(" ").ifEmpty { null }
            return PreparedDocument(hash, snapshot.length(), readName, readMime, session,
                local ?: original.takeIf { ownershipKnown && recoverable && !knownImport && !retained.ownSource && it.toString().length <= 4096 }, retained.uri, retained.evicted, warning, importVerified = retained.verified)
        } catch (failure: Throwable) { session?.close(); throw failure }
    }

    /** Indexed identity is a hint until the current local bytes are fully re-read. */
    internal fun reusableLocal(hash: String, bytes: Long?, original: Uri?, signal: CancellationSignal): LibraryDocument? {
        return try {
            // Prefer the already admitted persistent original; no second read is needed for it.
            if (original != null && original.scheme == "file") return null
            for (value in libraryIndex.value.locationsForHash(hash, 16)) {
                signal.throwIfCanceled()
                val uri = Uri.parse(value)
                if (uri == original || value.length > 4096 || !recoverable(uri)) continue
                try {
                    // Retained copies are separately owned and must not become an original reference.
                    val mapped = mediaSource(uri, signal) ?: uri
                    if (copies.ownership(mapped, hash, null, signal) != false) continue
                    if (LibraryInput.hash(context, uri, signal, bytes).equals(hash, ignoreCase = true)) {
                        libraryIndex.value.location(value)?.takeIf { it.hash?.equals(hash, ignoreCase = true) == true }?.let { return it }
                    }
                } catch (cancelled: OperationCanceledException) { throw cancelled }
                catch (_: Exception) { signal.throwIfCanceled() }
            }
            null
        } catch (cancelled: OperationCanceledException) { throw cancelled }
        catch (failure: Exception) {
            signal.throwIfCanceled()
            Log.w("AllDoc", "Library source lookup unavailable", failure)
            null
        }
    }

    /** At most one platform conversion and one item query; names/equal bytes do not prove ownership. */
    private fun mediaSource(uri: Uri, signal: CancellationSignal): Uri? {
        if (Build.VERSION.SDK_INT < 29 || uri.scheme != "content") return null
        signal.throwIfCanceled()
        val mapped = when (uri.authority) {
            MediaStore.AUTHORITY -> {
                val parts = uri.pathSegments
                val item = when (parts.size) {
                    3 -> parts[1] == "file" || parts[1] == "downloads"
                    4 -> parts[1] in MEDIA_COLLECTIONS && parts[2] == "media"
                    else -> false
                }
                if (!item) return null
                uri
            }
            in MEDIA_DOCUMENT_AUTHORITIES -> findMediaUri(context, uri)
            else -> return null
        }
        signal.throwIfCanceled()
        if (mapped == null) return null // The platform found no equivalent item, rather than failing.
        if (mapped.scheme != "content" || mapped.authority != MediaStore.AUTHORITY)
            throw IOException("The platform returned an invalid media reference.")
        val item = ContentUris.parseId(mapped)
        return resolver.query(mapped, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.VOLUME_NAME),
            null, null, null, signal)?.use { cursor ->
            signal.throwIfCanceled()
            if (!cursor.moveToFirst()) throw IOException("The mapped media item is unavailable.")
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            if (cursor.isNull(idColumn)) throw IOException("The mapped media item has no identity.")
            val id = cursor.getLong(idColumn)
            val volume = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.VOLUME_NAME))
            if (id < 0 || id != item || volume.isNullOrBlank() || volume.length > 64 ||
                volume == MediaStore.VOLUME_EXTERNAL || cursor.moveToNext())
                throw IOException("The mapped media identity is incomplete.")
            // Files and media-specific collections share an item ID only within this actual volume.
            MediaStore.Files.getContentUri(volume, id)
        } ?: throw IOException("The mapped media identity is unavailable.")
    }

    private fun usefulMime(value: String?): String? = value?.takeIf { it.length <= 128 }
        ?.substringBefore(';')?.trim()?.lowercase(java.util.Locale.ROOT)
        ?.takeIf { DocumentFormats.routingName("", it).isNotEmpty() }

    private inline fun <T> optionalMetadata(signal: CancellationSignal, read: () -> T): T? = try {
        signal.throwIfCanceled()
        read().also { signal.throwIfCanceled() }
    } catch (failure: OperationCanceledException) { throw failure }
    catch (_: Exception) { signal.throwIfCanceled(); null }

    private fun recoverable(uri: Uri): Boolean {
        if (uri.scheme == "file") return true
        if (uri.toString().length > 4096) return false
        persistedRead?.let { return it(uri) }
        return resolver.persistedUriPermissions.any { permission ->
            permission.isReadPermission && (permission.uri == uri || runCatching {
                if (!DocumentsContract.isTreeUri(permission.uri)) false else {
                    val root = DocumentsContract.buildDocumentUriUsingTree(permission.uri, DocumentsContract.getTreeDocumentId(permission.uri))
                    root == uri || DocumentsContract.isChildDocument(resolver, root, uri)
                }
            }.getOrDefault(false))
        }
    }

    fun importUri(hash: String): Uri? { worker(); return copies.importUri(hash) }
    fun importUri(hash: String, settings: CopySettings?, signal: CancellationSignal): Uri? {
        worker(); signal.throwIfCanceled()
        return copies.importUri(hash, signal.takeIf { settings != null })
    }
    fun status(settings: CopySettings): CopyStatus { worker(); return copies.status(settings) }
    fun clear(): CopyChange { worker(); return copies.clear() }
    fun pendingEvictions(): List<CopyEviction> { worker(); return copies.pendingEvictions() }
    fun hasPendingEviction(hash: String, importUri: String): Boolean { worker(); return copies.hasPendingEviction(hash, importUri) }
    fun acknowledgeEvictions(ids: List<Long>) { worker(); copies.acknowledgeEvictions(ids) }
    override fun close() {
        worker()
        try { if (copyIndex.isInitialized()) copies.close() }
        finally { if (libraryIndex.isInitialized()) libraryIndex.value.close() }
    }
    private fun worker() { check(Looper.myLooper() != Looper.getMainLooper()) { "Document I/O belongs on a worker." } }
    companion object {
        private val MEDIA_COLLECTIONS = setOf("images", "video", "audio")
        private val MEDIA_DOCUMENT_AUTHORITIES = setOf("com.android.externalstorage.documents", "com.android.providers.media.documents")
    }
}

internal fun ByteArray.hex(): String = toHexString()
