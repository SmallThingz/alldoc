package app.smallthingz.alldoc.sources

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.CancellationSignal
import android.os.Looper
import android.os.OperationCanceledException
import android.os.SystemClock
import android.provider.DocumentsContract
import app.smallthingz.alldoc.core.DocumentProbe
import app.smallthingz.alldoc.core.DocumentFormats
import app.smallthingz.alldoc.state.LibraryCategory
import app.smallthingz.alldoc.state.LibraryDocument
import app.smallthingz.alldoc.core.takeScalarSafe
import app.smallthingz.alldoc.state.LibraryCatalog
import app.smallthingz.alldoc.state.LibraryFile
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.atomic.AtomicBoolean

/** Metadata is visible before hashing. A partial/cancelled traversal never prunes old locations. */
internal data class LibraryScanProgress(val discovered: Long = 0, val indexed: Long = 0,
    val skipped: Long = 0, val changed: Boolean = false, val complete: Boolean = false, val changes: Long = 0)

/** One finite worker call; the caller owns its cancellation and scheduling priority. */
internal class LibraryIndex(context: Context, private val catalog: LibraryCatalog,
    private val elapsed: () -> Long = SystemClock::elapsedRealtime) {
    private val context = context.applicationContext

    fun scan(signal: CancellationSignal, onProgress: (LibraryScanProgress) -> Unit = {}): LibraryScanProgress {
        worker(); signal.throwIfCanceled()
        val granted = context.contentResolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri }.toSet()
        val configured = catalog.roots().map(Uri::parse)
        val available = configured.filter { DocumentsContract.isTreeUri(it) && it in granted }
        val roots = (LibraryAccess.roots(context).map { Uri.fromFile(it) } + available).toSet()
        val denied = catalog.knownRoots().count { Uri.parse(it) !in roots }.toLong()
        fun status(progress: LibraryScanProgress) = if (denied == 0L) progress else
            progress.copy(skipped = progress.skipped + denied, complete = false)
        return status(scanRoots(roots.toList(), signal) { onProgress(status(it)) })
    }

    internal fun scanRoots(roots: List<Uri>, signal: CancellationSignal,
        onProgress: (LibraryScanProgress) -> Unit = {}): LibraryScanProgress {
        worker(); signal.throwIfCanceled()
        val directory = File(context.cacheDir, "library-scan")
        val database = File(directory, "directories.sqlite")
        check(scanning.compareAndSet(false, true)) { "A Library scan is already running." }
        try {
            var discoveredCount = 0L
            var indexedCount = 0L
            var skippedCount = 0L
            var changes = 0L
            var complete = true
            val excluded = catalog.hiddenTypes()
            fun changed() { changes++ }
            fun snapshot(done: Boolean = false) = LibraryScanProgress(
                discoveredCount, indexedCount, skippedCount, changes != 0L, done, changes)
            val scans = ArrayList<Pair<String, Long>>()
            check(directory.isDirectory || directory.mkdirs()) { "Cannot prepare Library discovery." }
            SQLiteDatabase.deleteDatabase(database) // Only this process's single, disposable frontier.
            for (root in roots) {
                signal.throwIfCanceled()
                val rootName = root.toString()
                if (rootName.length > MAX_URI) { complete = false; continue }
                val generation = catalog.beginScan(rootName)
                scans.add(rootName to generation)
                var rootComplete = true
                val hints = ArrayList<LibraryFile>(BATCH)
                val identified = ArrayList<LibraryFile>(BATCH)
                var published = elapsed()
                fun publish() {
                    if (hints.isEmpty() && identified.isEmpty()) return
                    signal.throwIfCanceled()
                    if (hints.isNotEmpty()) discoveredCount += catalog.discoverBatch(hints, rootName, generation,
                        provisional = true, onChanged = ::changed).size
                    if (identified.isNotEmpty()) discoveredCount += catalog.discoverBatch(identified, rootName, generation,
                        onChanged = ::changed).size
                    hints.clear(); identified.clear()
                    published = elapsed()
                    onProgress(snapshot())
                }
                fun discovered(file: LibraryFile) {
                    // Rejected entries must not hold an already-discovered document until the folder ends.
                    if ((hints.isNotEmpty() || identified.isNotEmpty()) && elapsed() - published >= 75) publish()
                    if (file.uri.length > MAX_URI) { rootComplete = false; skippedCount++; return }
                    // Known names appear immediately; signatures and hashes are verified after discovery.
                    val known = DocumentFormats.supportedName(file.title)
                    val hint = if (known) file.copy(mimeType = DocumentFormats.mimeType(file.title) ?: file.mimeType) else file
                    // A renamed document can still belong to an enabled group.
                    val provisional = known && LibraryCategory.of(hint.title, hint.mimeType) !in excluded
                    val candidate = if (provisional) hint else {
                        // Provider open/read may block before the next per-entry deadline check.
                        publish()
                        val detected = try { LibraryInput.mime(context, Uri.parse(file.uri), signal) }
                        catch (cancelled: OperationCanceledException) { throw cancelled }
                        catch (_: Exception) { signal.throwIfCanceled(); rootComplete = false; skippedCount++; return }
                        val mime = documentMime(file.title, file.mimeType, detected)
                            ?: if (detected.inconclusive) catalog.location(file.uri)?.mimeType?.let {
                                documentMime(file.title, it, detected)
                            } else null
                        if (mime == null) {
                            // A provider can lose its filename/MIME hints without changing known Office bytes.
                            if (detected.mime == "application/x-ole-storage") catalog.location(file.uri)?.let { previous ->
                                val inherited = documentMime(file.title, previous.mimeType, detected)
                                if (previous.hash != null && inherited != null && LibraryCategory.of(file.title, inherited) !in excluded) {
                                    try {
                                        val fingerprint = LibraryInput.fingerprint(context, Uri.parse(file.uri), signal,
                                            file.sizeBytes.takeIf { it >= 0 })
                                        if (fingerprint.hash == previous.hash) {
                                            val refreshed = catalog.rediscoverKnown(previous, file, fingerprint.hash, rootName, generation)
                                            if (refreshed == null) { rootComplete = false; skippedCount++ }
                                            else { discoveredCount++; if (refreshed != previous) changed() }
                                        } else if (catalog.removeLocation(previous)) changed()
                                        else { rootComplete = false; skippedCount++ }
                                    } catch (cancelled: OperationCanceledException) { throw cancelled }
                                    catch (_: Exception) { signal.throwIfCanceled(); rootComplete = false; skippedCount++ }
                                    return
                                }
                            }
                            if (detected.inconclusive) catalog.location(file.uri)?.let { previous ->
                                if (previous.hash != null && catalog.invalidate(previous)) changed()
                                rootComplete = false; skippedCount++
                            }
                            return
                        }
                        file.copy(mimeType = mime)
                    }
                    if (LibraryCategory.of(candidate.title, candidate.mimeType) in excluded) return
                    val batch = if (provisional) hints else identified
                    batch.add(candidate)
                    if (batch.size == BATCH || elapsed() - published >= 75) publish()
                }
                try {
                    Frontier(database).use { frontier ->
                        val initial = when (root.scheme) {
                            "file" -> Uri.fromFile(File(requireNotNull(root.path)).canonicalFile)
                            "content" -> DocumentsContract.buildDocumentUriUsingTree(root, DocumentsContract.getTreeDocumentId(root))
                            else -> throw IOException("Unsupported Library folder.")
                        }
                        frontier.add(initial, 0)
                        while (true) {
                            signal.throwIfCanceled()
                            val next = frontier.next() ?: break
                            try {
                                if (root.scheme == "file") files(root, next.uri, signal, { child -> frontier.add(child, next.depth + 1) }, ::discovered) {
                                    rootComplete = false; skippedCount++
                                }
                                else documents(root, next.uri, signal, { child -> frontier.add(child, next.depth + 1) }, ::discovered)
                            } catch (cancelled: OperationCanceledException) { throw cancelled }
                            catch (_: Exception) {
                                signal.throwIfCanceled(); rootComplete = false
                                skippedCount++
                            }
                            frontier.done(next.id)
                            publish()
                        }
                    }
                } catch (cancelled: OperationCanceledException) { throw cancelled }
                catch (_: Exception) {
                    signal.throwIfCanceled(); rootComplete = false
                    skippedCount++
                }
                publish()
                signal.throwIfCanceled()
                if (rootComplete) {
                    catalog.finishScan(rootName, generation, signal, ::changed)
                } else complete = false
                SQLiteDatabase.deleteDatabase(database)
                onProgress(snapshot())
            }
            // URI keysets bound both verification passes. All cheap signatures precede full-file I/O.
            var notified = elapsed()
            for (hashing in listOf(false, true)) for ((root, generation) in scans) {
                var after = ""
                while (true) {
                    signal.throwIfCanceled()
                    val pending = catalog.scanPending(root, generation, after, BATCH, verifiedOnly = hashing)
                    if (pending.isEmpty()) break
                    val verified = ArrayList<Pair<LibraryDocument, String?>>(if (hashing) 0 else pending.size)
                    for (document in pending) {
                        signal.throwIfCanceled()
                        after = document.uri
                        val uri = Uri.parse(document.uri)
                        try {
                            val reusable = document.hash != null && document.stamp != null && LibraryInput.stamp(uri) == document.stamp
                            if (reusable && (!DocumentProbe.supportedRecord(document.title, document.mimeType) ||
                                LibraryCategory.of(document.title, document.mimeType) in excluded)) {
                                if (catalog.removeLocation(document)) changed()
                            } else if (reusable) {
                                if (hashing) indexedCount++ else verified.add(document to document.mimeType)
                            } else if (!hashing) {
                                val detected = LibraryInput.mime(context, uri, signal)
                                val mime = documentMime(document.title, document.mimeType, detected)
                                if (mime == null || LibraryCategory.of(document.title, mime) in excluded) {
                                    if (detected.inconclusive && mime == null) {
                                        if (catalog.invalidate(document)) changed()
                                        complete = false; skippedCount++
                                    } else if (catalog.removeLocation(document)) changed()
                                } else verified.add(document to mime)
                            } else {
                                val size = document.sizeBytes.takeIf { it >= 0 }
                                val fingerprint = if (document.stamp == null) LibraryInput.fingerprint(context, uri, signal, size)
                                    else LibraryInput.Fingerprint(LibraryInput.hash(context, uri, signal, size, document.stamp), null)
                                val detected = fingerprint.detected
                                val expected = if (document.hash != null && detected?.mime == "application/x-ole-storage")
                                    catalog.expectedScanHash(document, root, generation) else null
                                val inheritedChanged = expected != null && fingerprint.hash != expected
                                val mime = if (inheritedChanged) null else if (detected == null) document.mimeType
                                    else documentMime(document.title, document.mimeType, detected)
                                if (mime == null || LibraryCategory.of(document.title, mime) in excluded) {
                                    if (detected?.inconclusive == true && mime == null) {
                                        if (catalog.invalidate(document)) changed()
                                        complete = false; skippedCount++
                                    } else if (catalog.removeLocation(document)) changed()
                                } else {
                                    val resolved = catalog.resolve(document, fingerprint.hash, root, generation, mime)
                                    if (resolved != null) {
                                        indexedCount++
                                        if (resolved != document) changed()
                                    }
                                }
                            }
                        } catch (cancelled: OperationCanceledException) { throw cancelled }
                        catch (_: Exception) {
                            signal.throwIfCanceled()
                            if (document.hash != null && catalog.invalidate(document)) changed()
                            complete = false; skippedCount++
                        }
                        val now = elapsed()
                        if (now - notified >= 250) { onProgress(snapshot()); notified = now }
                    }
                    if (!hashing && verified.isNotEmpty()) catalog.verifyBatch(verified, root, generation, ::changed)
                }
            }
            return snapshot(complete).also(onProgress)
        } finally {
            try { SQLiteDatabase.deleteDatabase(database) } finally { scanning.set(false) }
        }
    }

    private fun documentMime(name: String, supplied: String?, detected: DocumentProbe.Identification): String? =
        DocumentProbe.documentName(name, supplied, detected)?.let { DocumentFormats.mimeType(it) ?: detected.mime ?: supplied }

    private fun files(root: Uri, parent: Uri, signal: CancellationSignal, directory: (Uri) -> Unit,
        file: (LibraryFile) -> Unit, unavailable: () -> Unit) {
        val base = File(requireNotNull(root.path)).canonicalFile.toPath()
        val folder = File(requireNotNull(parent.path)).canonicalFile.toPath()
        if (!folder.startsWith(base)) throw IOException("The folder moved outside the granted root.")
        Files.newDirectoryStream(folder).use { children ->
            for (child in children) {
                signal.throwIfCanceled()
                val attributes = try { Files.readAttributes(child, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS) }
                    catch (_: IOException) { signal.throwIfCanceled(); unavailable(); continue }
                    catch (_: SecurityException) { signal.throwIfCanceled(); unavailable(); continue }
                if (attributes.isSymbolicLink) continue
                val path = child.toFile().canonicalFile.toPath()
                if (!path.startsWith(base)) continue
                // Android excludes other apps' private shared-storage areas from all-files access.
                val relative = base.relativize(path).toString().replace(File.separatorChar, '/')
                if (relative == "Android/data" || relative == "Android/obb") continue
                val value = path.toFile()
                if (attributes.isDirectory) directory(Uri.fromFile(value))
                else if (attributes.isRegularFile) file(LibraryFile(Uri.fromFile(value).toString(), value.name.takeScalarSafe(256), null,
                    attributes.size(), attributes.lastModifiedTime().toMillis().coerceAtLeast(0), LibraryInput.stamp(Uri.fromFile(value))))
            }
        }
    }

    private fun documents(root: Uri, parent: Uri, signal: CancellationSignal, directory: (Uri) -> Unit, file: (LibraryFile) -> Unit) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(root, DocumentsContract.getDocumentId(parent))
        context.contentResolver.query(children, COLUMNS, null, null, null, signal)?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val name = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mime = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val size = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            val modified = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            while (cursor.moveToNext()) {
                signal.throwIfCanceled()
                val documentId = cursor.getString(id) ?: throw IOException("A Library item has no identity.")
                if (documentId.length > MAX_URI) throw IOException("A Library item identity is too long.")
                val uri = DocumentsContract.buildDocumentUriUsingTree(root, documentId)
                val type = cursor.getString(mime)?.take(128)
                if (type == DocumentsContract.Document.MIME_TYPE_DIR) directory(uri)
                else file(LibraryFile(uri.toString(), cursor.getString(name)?.takeScalarSafe(256)?.ifBlank { "Document" } ?: "Document",
                    type, if (cursor.isNull(size)) -1 else cursor.getLong(size).coerceAtLeast(0),
                    if (cursor.isNull(modified)) 0 else cursor.getLong(modified).coerceAtLeast(0)))
            }
            signal.throwIfCanceled()
            // Async providers may return partial rows without throwing; those rows cannot prove deletion.
            val extras = cursor.extras
            if (extras.getBoolean(DocumentsContract.EXTRA_LOADING, false) ||
                !extras.getString(DocumentsContract.EXTRA_ERROR).isNullOrEmpty())
                throw IOException("The folder listing is incomplete.")
        } ?: throw IOException("The folder listing is unavailable.")
    }

    private class Frontier(path: File) : java.io.Closeable {
        private val db = SQLiteDatabase.openOrCreateDatabase(path, null)
        private var count = 0
        init { try {
            db.rawQuery("PRAGMA journal_mode=OFF", null).use { check(it.moveToFirst() && it.getString(0) == "off") }
            // Disposable metadata only; never reused after a failed scan.
            db.execSQL("PRAGMA synchronous=OFF")
            db.setMaximumSize(64L * 1024 * 1024)
            db.execSQL("CREATE TABLE IF NOT EXISTS directories(id INTEGER PRIMARY KEY,uri TEXT UNIQUE NOT NULL,depth INTEGER NOT NULL,done INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("CREATE INDEX IF NOT EXISTS directories_pending ON directories(done,id)")
        } catch (failure: Throwable) { db.close(); throw failure } }
        data class Entry(val id: Long, val uri: Uri, val depth: Int)
        fun add(uri: Uri, depth: Int) {
            require(depth <= 64 && uri.toString().length <= MAX_URI && count < 100_000) { "The folder tree exceeds discovery limits." }
            val inserted = db.insertWithOnConflict("directories", null, ContentValues().apply {
                put("uri", uri.toString()); put("depth", depth)
            }, SQLiteDatabase.CONFLICT_IGNORE)
            if (inserted != -1L) count++
        }
        fun next(): Entry? = db.rawQuery("SELECT id,uri,depth FROM directories WHERE done=0 ORDER BY id LIMIT 1", null).use {
            if (it.moveToFirst()) Entry(it.getLong(0), Uri.parse(it.getString(1)), it.getInt(2)) else null
        }
        fun done(id: Long) { db.execSQL("UPDATE directories SET done=1 WHERE id=?", arrayOf(id)) }
        override fun close() = db.close()
    }

    private fun worker() { check(Looper.myLooper() != Looper.getMainLooper()) { "Library discovery belongs on a worker." } }
    companion object {
        private const val BATCH = 128
        private const val MAX_URI = 4096
        private val scanning = AtomicBoolean()
        private val COLUMNS = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
    }
}
