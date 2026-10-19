package app.smallthingz.alldoc.sources

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.os.Process
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import app.smallthingz.alldoc.core.takeScalarSafe
import app.smallthingz.alldoc.core.DocumentFormats
import app.smallthingz.alldoc.engine.DocumentEngine
import app.smallthingz.alldoc.ipc.DescriptorOutput
import app.smallthingz.alldoc.state.CopySettings
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.Locale

/** Retained payloads have a separate bounded index; document-state pruning never owns these files. */
internal class ImportCopies(private val context: Context) {
    data class Result(val uri: Uri?, val ownSource: Boolean, val evicted: List<String>, val warning: String?,
        val verified: Boolean = false)
    private data class Entry(val hash: String, val name: String, val mime: String, val bytes: Long,
        val uri: String?, val target: String, val token: String, val pending: Int, val owner: Int, val touched: Long)
    private val resolver get() = context.contentResolver
    private val database = object : SQLiteOpenHelper(context, "imports.sqlite", null, 3, DatabaseErrorHandler {
        throw SQLiteException("Saved-copy index is damaged; its files were preserved.")
    }) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE copies(hash TEXT PRIMARY KEY,name TEXT NOT NULL,mime TEXT NOT NULL,bytes INTEGER NOT NULL,uri TEXT,target TEXT NOT NULL,token TEXT NOT NULL,pending INTEGER NOT NULL,owner INTEGER NOT NULL,touched INTEGER NOT NULL,location TEXT)")
            db.execSQL("CREATE UNIQUE INDEX copies_uri ON copies(uri)")
            db.execSQL("CREATE INDEX copies_lru ON copies(touched)")
            db.execSQL("CREATE INDEX copies_pending ON copies(pending)")
            db.execSQL("CREATE INDEX copies_token ON copies(token)")
            db.execSQL("CREATE INDEX copies_location ON copies(location)")
            db.execSQL("CREATE INDEX copies_pending_target ON copies(pending,target)")
            db.execSQL("CREATE TABLE evictions(id INTEGER PRIMARY KEY AUTOINCREMENT,hash TEXT NOT NULL,uri TEXT NOT NULL)")
            db.execSQL("CREATE INDEX evictions_reference ON evictions(hash,uri)")
        }
        override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
            if (old < 2) db.execSQL("CREATE INDEX IF NOT EXISTS evictions_reference ON evictions(hash,uri)")
            if (old < 3) {
                db.execSQL("ALTER TABLE copies ADD COLUMN location TEXT")
                // The streamed key/predicate columns stay unchanged throughout the backfill.
                db.rawQuery("SELECT hash,uri FROM copies WHERE uri IS NOT NULL", null).use { cursor ->
                    while (cursor.moveToNext()) db.update("copies", ContentValues().apply {
                        put("location", documentLocation(Uri.parse(cursor.getString(1))))
                    }, "hash=?", arrayOf(cursor.getString(0)))
                }
                db.execSQL("CREATE INDEX copies_location ON copies(location)")
                db.execSQL("CREATE INDEX IF NOT EXISTS copies_token ON copies(token)")
                db.execSQL("CREATE INDEX IF NOT EXISTS copies_pending_target ON copies(pending,target)")
            }
        }
    }
    private val db get() = database.writableDatabase
    private var evictionCount: Int? = null

    fun close() = synchronized(lock) { database.close() }
    data class SourceMetadata(val name: String?, val mime: String?, val expectedHash: String? = null, val incomplete: Boolean = false) {
        fun matches(hash: String) = expectedHash == null || expectedHash == hash
    }
    private val Entry.saf get() = target != "private" && target != "media"
    private fun Entry.metadata() = SourceMetadata(name.takeIf { pending == 0 }, mime.takeIf { pending == 0 }, hash.takeIf { saf }, saf && pending == 1)
    fun metadata(uri: Uri): SourceMetadata? = synchronized(lock) {
        val location = documentLocation(uri)
        find(if (location == null) "uri=?" else "uri=? OR location=?",
            if (location == null) arrayOf(uri.toString()) else arrayOf(uri.toString(), location))?.let {
            it.metadata()
        }
    }

    /** A SAF location is only a claim: path-based provider IDs can be reused for different bytes. */
    fun claim(uri: Uri, hash: String?, name: String?, signal: CancellationSignal): SourceMetadata? = synchronized(lock) {
        (claimedEntry(uri, hash, name, signal) ?: physicalEntry(uri, hash, name, signal))?.metadata()
            ?: if (uri.scheme == "content" && name.isNullOrBlank() && opaqueInScope(uri))
            throw IOException("Source ownership is uncertain.") else null
    }

    fun ownership(uri: Uri, hash: String?, name: String?, signal: CancellationSignal): Boolean? = synchronized(lock) {
        val entry = claimedEntry(uri, hash, name, signal) ?: physicalEntry(uri, hash, name, signal)
        if (entry != null) if (hash == null || !entry.saf || entry.hash == hash) true else if (entry.pending == 1) null else false
        else if (uri.scheme == "content" && name.isNullOrBlank() && opaqueInScope(uri)) null else false
    }

    /** A scanned file path may be the same inode exposed by our MediaStore/SAF copy URI. */
    private fun physicalEntry(uri: Uri, hash: String?, name: String?, signal: CancellationSignal): Entry? {
        if (uri.scheme != "file") return null
        val possible = candidates(uri, hash, name).filter { entry ->
            when (entry.target) {
                "private" -> File(requireNotNull(uri.path)).canonicalFile.parentFile == privateDirectory().canonicalFile
                // Only the platform media provider can identify a MediaStore file alias.
                "media" -> entry.uri == null || Uri.parse(entry.uri).authority == MediaStore.AUTHORITY
                else -> true
            }
        }
        if (possible.isEmpty()) return null
        val input = try { LibraryInput.open(context, uri, signal) }
        catch (failure: IOException) {
            signal.throwIfCanceled()
            // Recovery may have just renamed the admitted input. Other access failures are uncertain.
            try { android.system.Os.stat(requireNotNull(uri.path)) }
            catch (missing: android.system.ErrnoException) {
                if (missing.errno == android.system.OsConstants.ENOENT) return null
            }
            throw failure
        }
        input.use { source ->
            val actual = android.system.Os.fstat(source.fileDescriptor)
            if (!android.system.OsConstants.S_ISREG(actual.st_mode)) throw IOException("Local source ownership is uncertain.")
            for (entry in possible) {
                signal.throwIfCanceled()
                val recorded = entry.uri?.let(Uri::parse) ?: throw IOException("Saved-copy location is pending.")
                LibraryInput.open(context, recorded, signal).use { owned ->
                    val stat = android.system.Os.fstat(owned.fileDescriptor)
                    if (!android.system.OsConstants.S_ISREG(stat.st_mode)) throw IOException("Saved-copy identity is unavailable.")
                    if (stat.st_ino != 0L && stat.st_ino == actual.st_ino && stat.st_dev == actual.st_dev) return entry
                    // Generic providers may expose a cached regular file, not their stored inode.
                    if (entry.saf) throw IOException("Saved-copy physical ownership is uncertain.")
                }
            }
        }
        return null
    }

    private fun claimedEntry(uri: Uri, hash: String?, name: String?, signal: CancellationSignal): Entry? {
        signal.throwIfCanceled()
        for (entry in candidates(uri, hash, name)) {
            signal.throwIfCanceled()
            if (entry.uri?.let { sameLocation(Uri.parse(it), uri) } == true) return entry
            if (entry.pending > 0 && sameScope(entry.target, uri) &&
                locatePending(entry, signal, uri)?.let { sameLocation(it, uri) } == true) return entry
        }
        return null
    }

    private fun candidates(uri: Uri, hash: String?, name: String?): List<Entry> {
        val tokens = generation.findAll(uri.lastPathSegment.orEmpty().take(MAX_URI) + "\n" + name.orEmpty())
            .map { it.value.lowercase(Locale.ROOT) }.distinct().take(17).toList()
        if (tokens.size > 16) throw IOException("Too many saved-copy generation hints.")
        val terms = mutableListOf("uri=?")
        val args = mutableListOf(uri.toString())
        documentLocation(uri)?.let { terms += "location=?"; args += it }
        if (hash != null) { terms += "hash=?"; args += hash }
        if (tokens.isNotEmpty()) {
            terms += "token IN (" + tokens.joinToString(",") { "?" } + ")"
            args += tokens
        }
        val candidates = db.query("copies", null, terms.joinToString(" OR "), args.toTypedArray(),
            null, null, null, "33").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.entry()) }
        }
        if (candidates.size > 32) throw IOException("Saved-copy identity is ambiguous.")
        return candidates
    }

    private fun documentLocation(uri: Uri): String? = if (uri.scheme != "content") null else runCatching {
        DocumentsContract.buildDocumentUri(requireNotNull(uri.authority), DocumentsContract.getDocumentId(uri)).toString()
    }.getOrNull()

    private fun sameLocation(left: Uri, right: Uri): Boolean = left == right ||
        (left.scheme == "content" && right.scheme == "content" && left.authority == right.authority &&
            runCatching { DocumentsContract.getDocumentId(left) == DocumentsContract.getDocumentId(right) }.getOrDefault(false))

    private fun sameScope(target: String, uri: Uri): Boolean = when (target) {
        "private" -> uri.scheme == "file" && File(uri.path.orEmpty()).parentFile == privateDirectory()
        "media" -> Build.VERSION.SDK_INT >= 29 && uri.scheme == "content" && uri.authority == mediaCollection().authority
        else -> uri.scheme == "content" && uri.authority == Uri.parse(target).authority &&
            (!DocumentsContract.isTreeUri(uri) || DocumentsContract.getTreeDocumentId(uri) ==
                DocumentsContract.getTreeDocumentId(Uri.parse(target)))
    }

    /** The exceptional opaque-name path only asks the index; it never enumerates provider folders. */
    private fun opaqueInScope(uri: Uri): Boolean {
        val scope: String
        val args: Array<String>
        when {
            uri.authority == "media" -> { scope = "target=?"; args = arrayOf("media") }
            DocumentsContract.isTreeUri(uri) -> {
                scope = "target=?"
                args = arrayOf(DocumentsContract.buildTreeDocumentUri(requireNotNull(uri.authority),
                    DocumentsContract.getTreeDocumentId(uri)).toString())
            }
            else -> {
                val prefix = "content://" + uri.authority + "/"
                scope = "target>=? AND target<?"
                args = arrayOf(prefix, prefix.dropLast(1) + "0")
            }
        }
        return db.rawQuery("SELECT 1 FROM copies INDEXED BY copies_pending_target WHERE pending IN (1,2,3,4) AND " +
            scope + " LIMIT 1", args).use { it.moveToFirst() }
    }

    fun importUri(hash: String, signal: CancellationSignal? = null): Uri? = synchronized(lock) {
        evictionCount = null
        require(hash.matches(Regex("[a-f0-9]{64}")))
        if (signal != null) recover(signal, hash)
        find("hash=? AND pending=0", arrayOf(hash))?.uri?.let(Uri::parse)
    }

    fun hasPendingEviction(hash: String, importUri: String): Boolean = synchronized(lock) {
        db.rawQuery("SELECT 1 FROM evictions WHERE hash=? AND uri=? LIMIT 1", arrayOf(hash, importUri)).use { it.moveToFirst() }
    }

    fun pendingEvictions(): List<CopyEviction> = synchronized(lock) {
        db.rawQuery("SELECT id,hash,uri FROM evictions ORDER BY id LIMIT 20000", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(CopyEviction(cursor.getLong(0), cursor.getString(1), cursor.getString(2))) }
        }
    }

    fun acknowledgeEvictions(ids: List<Long>) = synchronized(lock) {
        require(ids.size <= 20000)
        db.beginTransaction()
        try { ids.forEach { db.delete("evictions", "id=?", arrayOf(it.toString())) }; db.setTransactionSuccessful() }
        finally { db.endTransaction() }
    }

    fun retain(snapshot: File, hash: String, name: String, mime: String?, source: Uri?, recoverable: Boolean,
        settings: CopySettings, signal: CancellationSignal): Result {
        val evicted = mutableListOf<String>()
        val warnings = linkedSetOf<String>()
        while (true) {
            try {
                return synchronized(lock) {
                    retainLocked(snapshot, hash, name, mime, source, recoverable, settings, signal, evicted)
                }.let { result ->
                    result.warning?.let(warnings::add)
                    result.copy(warning = warnings.takeIf { it.isNotEmpty() }?.joinToString(" "))
                }
            } catch (room: RoomNeeded) {
                room.warning?.let(warnings::add)
                val ready = try { makeRoom(settings, room.bytes, evicted, room.adding, signal) }
                catch (failure: Exception) {
                    signal.throwIfCanceled()
                    Log.w("AllDoc", "Cannot free saved-copy storage", failure)
                    false
                }
                if (!ready) return synchronized(lock) {
                    val retained = find("hash=? AND pending=0", arrayOf(hash))?.uri
                        ?.takeIf { it == room.verifiedUri }?.let(Uri::parse)
                    warnings += if (retained != null) "Saved-copy cleanup is pending."
                        else "Could not save a copy. Saved-copy cleanup is pending."
                    Result(retained, room.ownSource, evicted, warnings.joinToString(" "), verified = retained != null)
                }
            }
        }
    }

    private class RoomNeeded(val bytes: Long, val adding: Boolean, val ownSource: Boolean,
        val verifiedUri: String?, val warning: String?) : Exception()

    private fun retainLocked(snapshot: File, hash: String, name: String, mime: String?, source: Uri?, recoverable: Boolean,
        settings: CopySettings, signal: CancellationSignal, evicted: MutableList<String>): Result {
        validate(settings)
        evictionCount = null
        signal.throwIfCanceled()
        var warning = recover(signal)
        var existing = find("hash=?", arrayOf(hash))
        var origin = source?.let { uri ->
            (claimedEntry(uri, hash, name, signal) ?: physicalEntry(uri, hash, name, signal))?.takeIf { it.pending == 0 }
        }
        var ownSource = origin != null
        val sourceMime = mime?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
            ?.takeIf { DocumentFormats.routingName("", it).isNotEmpty() }
        try {
            signal.throwIfCanceled()
            if (origin != null && origin.hash != hash && origin.saf) {
                // A generic SAF URI may now name an unrelated file. Forget only our stale claim.
                forget(origin); evicted += origin.hash
                origin = null; ownSource = false
                warning = "A saved copy changed; its file was preserved."
            }
            if (origin != null && origin.hash != hash) {
                // Stable private/MediaStore identities retain ownership when their contents are edited.
                if (existing != null) { remove(checkNotNull(existing), signal); evicted += hash }
                db.beginTransaction()
                try {
                    invalidate(origin)
                    deleteRow(origin.hash)
                    existing = origin.copy(hash = hash, bytes = snapshot.length(), touched = now())
                    save(checkNotNull(existing)); db.setTransactionSuccessful()
                } finally { db.endTransaction() }
                evicted += origin.hash
            }
            if (existing?.pending == 0) {
                val entry = checkNotNull(existing)
                val sameSource = source != null && entry.uri?.let { sameLocation(Uri.parse(it), source) } == true
                if (sameSource || matches(entry, hash, signal)) {
                    val updated = rename(entry.copy(mime = sourceMime ?: entry.mime), logicalName(name)).copy(touched = now())
                    save(updated)
                    requireRoom(settings, 0, false, ownSource, updated.uri, warning)
                    val retained = find("hash=? AND pending=0", arrayOf(hash))?.uri?.let(Uri::parse)
                    return Result(retained, ownSource, evicted, warning, verified = retained != null)
                }
                // This owned retained copy no longer matches its recorded identity.
                if (entry.saf) forget(entry) else remove(entry, signal)
                evicted += hash
                existing = null
                warning = "A saved copy changed."
            }
            if (existing != null) return Result(null, ownSource, evicted, "A saved copy needs cleanup.")
            requireRoom(settings, 0, false, ownSource, warning = warning)
            if (!settings.enabled || recoverable || ownSource)
                return Result(null, ownSource, evicted, warning)
            if (snapshot.length() > settings.maxBytes)
                return Result(null, false, evicted, "File exceeds the saved-copy limit.")
            requireRoom(settings, snapshot.length(), true, ownSource, warning = warning)
            val target = settings.treeUri ?: if (Build.VERSION.SDK_INT >= 29) "media" else "private"
            val entry = Entry(hash, logicalName(name), sourceMime ?: mime ?: "application/octet-stream", snapshot.length(), null,
                target, UUID.randomUUID().toString(), 1, Process.myPid(), now())
            val saved = try { create(entry, snapshot, signal) }
            catch (failure: Exception) {
                signal.throwIfCanceled()
                if (target != "media" || find("hash=?", arrayOf(hash)) != null) throw failure
                Log.w("AllDoc", "Cannot retain copy in Documents/AllDoc; using app storage", failure)
                warning = "Copy saved in app storage."
                create(entry.copy(target = "private", token = UUID.randomUUID().toString()), snapshot, signal)
            }
            return Result(Uri.parse(saved.uri), false, evicted, warning, verified = true)
        } catch (room: RoomNeeded) { throw room
        } catch (failure: Exception) {
            signal.throwIfCanceled()
            Log.w("AllDoc", "Cannot retain saved copy", failure)
            return Result(find("hash=? AND pending=0", arrayOf(hash))?.uri?.let(Uri::parse), ownSource, evicted,
                "Could not save a copy.")
        }
    }

    /** Observe current ownership without replaying recovery or deleting any retained payload. */
    fun observeStatus(settings: CopySettings): CopyStatus = synchronized(lock) {
        validate(settings)
        val usage = usage()
        CopyStatus(usage.first, usage.second, if (settings.treeUri != null) "Chosen folder"
            else if (Build.VERSION.SDK_INT >= 29) "Documents/AllDoc" else "Private app storage")
    }

    fun status(settings: CopySettings): CopyStatus {
        var warning = synchronized(lock) { validate(settings); evictionCount = null; recover() }
        val evicted = mutableListOf<String>()
        var failed = false
        try { if (settings.enabled) makeRoom(settings, 0, evicted, false) } catch (failure: Exception) {
            failed = true
            Log.w("AllDoc", "Cannot trim saved-copy storage", failure)
            warning = "Could not free saved-copy storage."
        }
        val usage = synchronized(lock) { usage() }
        return CopyStatus(usage.first, usage.second, if (settings.treeUri != null) "Chosen folder"
            else if (Build.VERSION.SDK_INT >= 29) "Documents/AllDoc" else "Private app storage", warning, evicted,
            trimPending = !failed && settings.enabled && !fits(settings, usage.first, usage.second, 0, false))
    }

    fun clear(after: String? = null): CopyChange {
        var warning = synchronized(lock) { evictionCount = null; recover() }
        val evicted = mutableListOf<String>()
        var failed = false
        val hashes = synchronized(lock) {
            db.rawQuery("SELECT hash FROM copies WHERE hash>? ORDER BY hash LIMIT 256", arrayOf(after.orEmpty())).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
        }
        var index = 0
        while (index < hashes.size) synchronized(lock) {
            evictionCount = null // Another caller may have changed the journal between durable units.
            val entry = find("hash=?", arrayOf(hashes[index]))
            if (entry == null) { index++; return@synchronized }
            try {
                if (missingPrivate(entry)) {
                    val pending = db.rawQuery("SELECT COUNT(*) FROM evictions", null).use { it.moveToFirst(); it.getInt(0) }
                    evictionCount = pending
                    val limit = minOf(MISSING_BATCH, hashes.size - index, 20000 - pending)
                    if (limit <= 0) throw IOException("Saved-copy history needs synchronization.")
                    val missing = arrayListOf(entry)
                    for (offset in 1 until limit) {
                        val next = find("hash=?", arrayOf(hashes[index + offset])) ?: break
                        if (!missingPrivate(next)) break
                        missing += next
                    }
                    forgetMissing(missing)
                    missing.forEach { evicted += it.hash }
                    index += missing.size
                } else {
                    remove(entry); evicted += entry.hash; index++
                }
            } catch (failure: Exception) {
                if (!failed) Log.w("AllDoc", "Cannot remove saved copies", failure)
                failed = true
                warning = "Some saved copies could not be removed."
                index++
            }
        }
        return CopyChange(evicted, warning, hashes.lastOrNull().takeIf { hashes.size == 256 })
    }

    private fun validate(settings: CopySettings) {
        settings.validate()
        require(settings.treeUri == null || settings.treeUri.length <= 4096)
    }
    private fun usage(): Pair<Long, Int> = db.rawQuery("SELECT COALESCE(SUM(bytes),0),COUNT(*) FROM copies", null).use {
        it.moveToFirst(); it.getLong(0) to it.getInt(1)
    }
    private fun fits(settings: CopySettings, bytes: Long, count: Int, size: Long, adding: Boolean) =
        size <= settings.maxBytes && bytes <= settings.maxBytes - size &&
            count + (if (adding) 1 else 0) <= settings.maxCount

    private fun requireRoom(settings: CopySettings, size: Long, adding: Boolean, ownSource: Boolean,
        verifiedUri: String? = null, warning: String? = null) {
        // Off stops retaining new files; existing copies stay available.
        if (!settings.enabled) return
        val usage = usage()
        if (!fits(settings, usage.first, usage.second, size, adding)) throw RoomNeeded(size, adding, ownSource, verifiedUri, warning)
    }

    /** At most 32 absent private entries per lock acquisition, 256 deletions per reconciliation. */
    private fun makeRoom(settings: CopySettings, size: Long, evicted: MutableList<String>, adding: Boolean,
        signal: CancellationSignal = CancellationSignal()): Boolean {
        var observedRevision = -1L
        var bytes = 0L
        var count = 0
        while (true) {
            signal.throwIfCanceled()
            val finished = synchronized(lock) {
                // Recount only after another operation changed the index, not once per deleted row.
                if (observedRevision != mutationRevision) {
                    val usage = usage(); bytes = usage.first; count = usage.second
                }
                if (fits(settings, bytes, count, size, adding)) true
                else if (evicted.size >= 256) false
                else {
                    evictionCount = null // Other instances may reconcile receipts between durable units.
                    val pending = db.rawQuery("SELECT COUNT(*) FROM evictions", null).use { it.moveToFirst(); it.getInt(0) }
                    evictionCount = pending
                    if (pending >= 20000) false
                    else {
                        val limit = minOf(MISSING_BATCH, 256 - evicted.size, 20000 - pending)
                        val missing = ArrayList<Entry>(limit)
                        var oldest: Entry? = null
                        var remainingBytes = bytes
                        var remainingCount = count
                        db.query("copies", null, null, null, null, null, "touched", limit.toString()).use { cursor ->
                            while (cursor.moveToNext()) {
                                signal.throwIfCanceled()
                                val entry = cursor.entry()
                                if (oldest == null) oldest = entry
                                if (!missingPrivate(entry)) break
                                missing += entry; remainingBytes -= entry.bytes; remainingCount--
                                if (fits(settings, remainingBytes, remainingCount, size, adding)) break
                            }
                        }
                        if (missing.isEmpty()) {
                            val entry = oldest ?: throw IOException("Saved-copy storage is full.")
                            remove(entry, signal); evicted += entry.hash; bytes -= entry.bytes; count--
                        } else {
                            forgetMissing(missing, signal)
                            missing.forEach { evicted += it.hash }
                            bytes = remainingBytes; count = remainingCount
                        }
                        observedRevision = mutationRevision
                        null
                    }
                }
            }
            if (finished != null) return finished
        }
    }
    private fun find(where: String, args: Array<String>): Entry? = db.query("copies", null, where, args, null, null, "touched", "1").use {
        if (it.moveToFirst()) it.entry() else null
    }
    private fun Cursor.entry() = Entry(getString(getColumnIndexOrThrow("hash")), getString(getColumnIndexOrThrow("name")),
        getString(getColumnIndexOrThrow("mime")), getLong(getColumnIndexOrThrow("bytes")), getString(getColumnIndexOrThrow("uri")),
        getString(getColumnIndexOrThrow("target")), getString(getColumnIndexOrThrow("token")), getInt(getColumnIndexOrThrow("pending")),
        getInt(getColumnIndexOrThrow("owner")), getLong(getColumnIndexOrThrow("touched")))
    private fun save(entry: Entry) {
        val values = ContentValues().apply {
            put("hash", entry.hash); put("name", entry.name); put("mime", entry.mime); put("bytes", entry.bytes)
            put("uri", entry.uri); put("location", entry.uri?.let { documentLocation(Uri.parse(it)) }); put("target", entry.target); put("token", entry.token); put("pending", entry.pending)
            put("owner", entry.owner); put("touched", entry.touched)
        }
        db.insertWithOnConflict("copies", null, values, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) }
        mutationRevision++
    }
    private fun deleteRow(hash: String) { db.delete("copies", "hash=?", arrayOf(hash)); mutationRevision++ }
    private fun invalidate(entry: Entry) {
        if (entry.uri == null || entry.pending == 1) return
        val count = evictionCount ?: db.rawQuery("SELECT COUNT(*) FROM evictions", null).use { it.moveToFirst(); it.getInt(0) }
        if (count >= 20000) throw IOException("Saved-copy history needs synchronization.")
        db.execSQL("INSERT INTO evictions(hash,uri) VALUES(?,?)", arrayOf<Any?>(entry.hash, entry.uri))
        evictionCount = count + 1
    }
    private fun missingPrivate(entry: Entry): Boolean {
        if (entry.pending != 0 || entry.target != "private") return false
        val uri = entry.uri?.let(Uri::parse) ?: return false
        if (uri.scheme != "file") return false
        return try {
            val file = File(uri.path ?: return false)
            if (file.canonicalFile.parentFile != privateDirectory().canonicalFile) false
            else try { Os.lstat(file.path); false }
            catch (failure: ErrnoException) { failure.errno == OsConstants.ENOENT }
        } catch (_: IOException) { false }
        catch (_: SecurityException) { false }
    }
    /** The shared lock covers fresh ownership/absence checks and this bounded atomic index update. */
    private fun forgetMissing(entries: List<Entry>, signal: CancellationSignal = CancellationSignal()) {
        db.beginTransaction()
        try {
            for (entry in entries) {
                signal.throwIfCanceled()
                invalidate(entry); deleteRow(entry.hash)
            }
            signal.throwIfCanceled()
            db.setTransactionSuccessful()
        } finally {
            try { db.endTransaction() } finally { evictionCount = null }
        }
    }
    private fun forget(entry: Entry) {
        db.beginTransaction()
        try { invalidate(entry); deleteRow(entry.hash); db.setTransactionSuccessful() }
        finally { db.endTransaction() }
    }
    // 0 published; 1 creating; 2 deleting; 3 renaming; 4 deleting an interrupted rename.
    private fun remove(entry: Entry, signal: CancellationSignal = CancellationSignal(), activeCreation: Boolean = false) {
        signal.throwIfCanceled()
        if (entry.pending != 1 && entry.pending != 2 && entry.pending != 4) {
            db.beginTransaction()
            try { invalidate(entry); save(entry.copy(pending = if (entry.pending == 3) 4 else 2)); db.setTransactionSuccessful() } finally { db.endTransaction() }
        }
        val original = entry.uri?.let(Uri::parse)
        val located = if (entry.pending != 0 || original == null) locatePending(entry, signal) else null
        val candidates = listOfNotNull(located, original).distinct()
        // SAF IDs and interrupted operations may identify replacement files. Verify every candidate
        // before deleting any; only the active create attempt still owns its incomplete output.
        val owned = if (entry.pending in 2..4 || entry.saf && !activeCreation) candidates.filter { uri ->
            val verified = try { matches(entry.copy(uri = uri.toString()), entry.hash, signal) }
                catch (failure: java.io.FileNotFoundException) {
                    // Providers also use FNF for unreadable documents. Only an empty item query proves absence.
                    if (entry.saf && resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                            null, null, null, signal)?.use { val exists = it.moveToFirst(); signal.throwIfCanceled(); !exists } != true)
                        throw IOException("Saved copy could not be opened; its file was preserved.", failure)
                    null
                }
            if (verified == false && entry.pending == 1)
                throw IOException("An incomplete saved copy could not be verified; its file was preserved.")
            verified == true
        } else candidates
        owned.forEach { signal.throwIfCanceled(); delete(it, entry.target, signal) }
        deleteRow(entry.hash)
    }

    /** Journal precedes provider creation; recovery can locate a creation interrupted before URI commit. */
    private fun create(entry: Entry, source: File, signal: CancellationSignal): Entry {
        save(entry)
        var current = entry
        try {
            val uri = when (entry.target) {
                "private" -> {
                    val folder = privateDirectory(); check(folder.isDirectory || folder.mkdirs())
                    Uri.fromFile(File(folder, temporaryName(entry)))
                }
                "media" -> {
                    check(Build.VERSION.SDK_INT >= 29)
                    resolver.insert(mediaCollection(), ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, temporaryName(entry)); put(MediaStore.MediaColumns.MIME_TYPE, entry.mime)
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "Documents/AllDoc/"); put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }) ?: throw IOException("Cannot create the saved copy.")
                }
                else -> {
                    val tree = Uri.parse(entry.target)
                    check(resolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission }) { "Folder access expired." }
                    DocumentsContract.createDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree,
                        DocumentsContract.getTreeDocumentId(tree)), entry.mime, temporaryName(entry))
                        ?: throw IOException("Cannot create the saved copy.")
                }
            }
            current = entry.copy(uri = uri.toString()); save(current)
            require(uri.toString().length <= MAX_URI) { "Saved-copy reference is too long." }
            val destination = if (uri.scheme == "file") ParcelFileDescriptor.open(File(requireNotNull(uri.path)),
                ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_WRITE_ONLY)
                else resolver.openFileDescriptor(uri, "w", signal) ?: throw IOException("Cannot write the saved copy.")
            destination.use { fd ->
                DescriptorOutput(fd, signal, MAX_INPUT, 30_000).use { output ->
                    source.inputStream().use { input ->
                        val buffer = ByteArray(32768)
                        while (true) { signal.throwIfCanceled(); val count = input.read(buffer); if (count < 0) break; output.write(buffer, 0, count) }
                    }
                }
                fd.checkError()
            }
            signal.throwIfCanceled()
            current = rename(current, entry.name)
            if (entry.target == "media") {
                check(resolver.update(Uri.parse(current.uri), ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1)
            }
            current = current.copy(pending = 0); save(current)
            return current
        } catch (failure: Throwable) {
            try { remove(current, activeCreation = true) } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    private fun rename(entry: Entry, name: String, actual: Uri? = null): Entry {
        if (entry.pending == 0 && entry.name == name) return entry
        val uri = actual ?: Uri.parse(checkNotNull(entry.uri))
        if (entry.pending == 0) save(entry.copy(name = name, pending = 3))
        val renamed = when (entry.target) {
            "private" -> {
                val target = File(privateDirectory(), fileName(entry, name))
                val original = File(requireNotNull(uri.path))
                if (original != target && (target.exists() || !original.renameTo(target))) throw IOException("Could not rename the saved copy.")
                Uri.fromFile(target)
            }
            "media" -> {
                check(resolver.update(uri, ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName(entry, name)); put(MediaStore.MediaColumns.MIME_TYPE, entry.mime)
                }, null, null) == 1)
                uri
            }
            else -> DocumentsContract.renameDocument(resolver, uri, fileName(entry, name)) ?: throw IOException("Could not rename the saved copy.")
        }
        val result = entry.copy(uri = renamed.toString(), name = name)
        require(renamed.toString().length <= MAX_URI) { "Saved-copy reference is too long." }
        db.beginTransaction()
        try {
            if (entry.pending != 1 && entry.uri != result.uri) invalidate(entry)
            save(result); db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return result
    }

    private fun matches(entry: Entry, hash: String, signal: CancellationSignal): Boolean {
        val uri = entry.uri?.let(Uri::parse) ?: throw IOException("Saved-copy location is unavailable.")
        val digest = MessageDigest.getInstance("SHA-256")
        val fd = if (uri.scheme == "file") ParcelFileDescriptor.open(File(requireNotNull(uri.path)), ParcelFileDescriptor.MODE_READ_ONLY)
            else resolver.openFileDescriptor(uri, "r", signal) ?: throw IOException("Saved copy is temporarily unavailable.")
        val length = fd.use {
            val prefix = ByteArray(1)
            val seekable = try { android.system.Os.pread(it.fileDescriptor, prefix, 0, 1, 0); true } catch (_: android.system.ErrnoException) { false }
            DocumentEngine.copyInput(it, null, MAX_INPUT, seekable, signal, digest)
        }
        // Only a completed read can establish a mismatch; provider failures preserve ownership.
        return length == entry.bytes && digest.digest().hex() == hash
    }

    private fun recover(signal: CancellationSignal = CancellationSignal(), hash: String? = null): String? {
        signal.throwIfCanceled()
        var warning: String? = null
        val started = android.os.SystemClock.elapsedRealtime()
        var checkedRename = false
        var failed = false
        // Snapshot keys before mutations: a refillable CursorWindow cannot safely track deleted rows.
        val hashes = db.rawQuery(
            if (hash == null) "SELECT hash FROM copies INDEXED BY copies_pending WHERE pending>0 ORDER BY touched LIMIT 65"
            else "SELECT hash FROM copies WHERE hash=? AND pending>0 LIMIT 1", hash?.let { arrayOf(it) }).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        if (hashes.size > 64) warning = "Saved-copy cleanup is pending."
        for (hash in hashes.take(64)) {
            signal.throwIfCanceled()
            if (android.os.SystemClock.elapsedRealtime() - started >= 1000) {
                warning = "Saved-copy cleanup is pending."; break
            }
            val entry = find("hash=? AND pending>0", arrayOf(hash)) ?: continue
            // A status call examines at most one interrupted rename; opening passes its cancellation signal.
            if (entry.pending == 3 || entry.pending == 4) {
                if (checkedRename) { warning = "Saved-copy cleanup is pending."; continue }
                checkedRename = true
            }
            try {
                if (entry.pending == 3) {
                    val original = entry.uri?.let(Uri::parse)
                    val located = locatePending(entry, signal)
                    val actual = listOfNotNull(located, original).distinct().firstOrNull {
                        matches(entry.copy(uri = it.toString()), entry.hash, signal)
                    } ?: throw IOException("Renamed copy content could not be verified.")
                    save(rename(entry, entry.name, actual).copy(pending = 0))
                } else remove(entry, signal)
            } catch (failure: Exception) {
                signal.throwIfCanceled()
                db.execSQL("UPDATE copies SET touched=? WHERE hash=?", arrayOf<Any?>(now(), entry.hash))
                if (!failed) Log.w("AllDoc", "Cannot recover interrupted saved copies", failure)
                failed = true
                warning = "Could not recover a saved copy."
            }
        }
        return warning
    }

    private fun locatePending(entry: Entry, signal: CancellationSignal, wanted: Uri? = null): Uri? = when (entry.target) {
        "private" -> listOf(temporaryName(entry), fileName(entry, entry.name)).map { File(privateDirectory(), it) }
            .firstOrNull { it.exists() && (wanted == null || Uri.fromFile(it) == wanted) }?.let(Uri::fromFile)
        "media" -> if (Build.VERSION.SDK_INT < 29) null else resolver.query(mediaCollection(), arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=?",
            arrayOf(temporaryName(entry), "Documents/AllDoc/", context.packageName), null, signal).let { cursor ->
                if (cursor == null) throw IOException("Saved-copy location could not be checked.")
                cursor.use {
                    var count = 0
                    while (it.moveToNext()) {
                        signal.throwIfCanceled()
                        if (++count > 20000) throw IOException("Too many matching saved-copy locations.")
                        val found = ContentUris.withAppendedId(mediaCollection(), it.getLong(0))
                        if (wanted == null || sameLocation(found, wanted)) return@use found
                    }
                    null
                }
            }
        else -> {
            val tree = Uri.parse(entry.target)
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null, signal).let { cursor ->
                if (cursor == null) throw IOException("Saved-copy location could not be checked.")
                cursor.use {
                    var count = 0
                    while (it.moveToNext()) {
                        signal.throwIfCanceled()
                        if (++count > 20000) throw IOException("Folder is too large to recover the interrupted copy.")
                        if (it.getString(1) == temporaryName(entry) || it.getString(1) == fileName(entry, entry.name)) {
                            val found = DocumentsContract.buildDocumentUriUsingTree(tree, it.getString(0))
                            if (wanted == null || sameLocation(found, wanted)) return@use found
                        }
                    }
                    signal.throwIfCanceled()
                    val extras = it.extras
                    if (extras.getBoolean(DocumentsContract.EXTRA_LOADING, false) ||
                        !extras.getString(DocumentsContract.EXTRA_ERROR).isNullOrEmpty())
                        throw IOException("Saved-copy folder listing is incomplete.")
                    null
                }
            }
        }
    }
    private fun delete(uri: Uri, target: String, signal: CancellationSignal) {
        if (uri.scheme == "file") {
            val file = File(requireNotNull(uri.path))
            require(file.canonicalFile.parentFile == privateDirectory().canonicalFile)
            if (file.exists() && !file.delete()) throw IOException("Cannot remove a saved copy.")
        } else {
            val deleted = try {
                if (target == "media") resolver.delete(uri, null, null) > 0
                else DocumentsContract.deleteDocument(resolver, uri)
            } catch (_: java.io.FileNotFoundException) { false }
            if (!deleted) {
                val column = if (target == "media") MediaStore.MediaColumns._ID else DocumentsContract.Document.COLUMN_DOCUMENT_ID
                val exists = resolver.query(uri, arrayOf(column), null, null, null, signal)?.use { it.moveToFirst() }
                    ?: throw IOException("Could not verify removal of a saved copy.")
                if (exists) throw IOException("Cannot remove a saved copy.")
            }
        }
    }
    private fun privateDirectory() = File(context.filesDir, "imports")
    private fun mediaCollection(): Uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    private fun temporaryName(entry: Entry) = physicalName(entry.name, ".alldoc-${entry.token}-", "")
    private fun fileName(entry: Entry, name: String) = physicalName(name, "", "-${entry.token}")
    private fun physicalName(displayName: String, prefix: String, suffix: String): String {
        val name = displayName.map { if (it < ' ' || it in "\\/:*?\"<>|") '_' else it }.joinToString("").trim().trim('.').ifBlank { "Document" }
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val extension = name.substring(dot).takeUtf8(32)
        val budget = 255 - prefix.toByteArray().size - suffix.toByteArray().size - extension.toByteArray().size
        return prefix + name.substring(0, dot).takeUtf8(budget) + suffix + extension
    }
    private fun String.takeUtf8(limit: Int): String {
        var end = 0; var bytes = 0
        while (end < length) {
            val point = codePointAt(end)
            val count = when { point < 0x80 -> 1; point < 0x800 -> 2; point < 0x10000 -> 3; else -> 4 }
            if (bytes + count > limit) break
            bytes += count; end += Character.charCount(point)
        }
        return substring(0, end)
    }
    private fun logicalName(name: String) = name.takeScalarSafe(256).ifBlank { "Document" }
    private fun now() = System.currentTimeMillis()
    companion object {
        private val lock = Any()
        private var mutationRevision = 0L // Protected by lock; conservative across separate copy indexes.
        private val generation = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        private const val MISSING_BATCH = 32
        private const val MAX_URI = 4096
        private const val MAX_INPUT = 128L * 1024 * 1024
    }
}
