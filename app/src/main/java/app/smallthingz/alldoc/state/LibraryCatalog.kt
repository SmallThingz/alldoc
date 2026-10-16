package app.smallthingz.alldoc.state

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteOpenHelper
import android.os.CancellationSignal
import android.os.Looper
import app.smallthingz.alldoc.core.takeScalarSafe
import app.smallthingz.alldoc.core.NativeFuzzy
import app.smallthingz.alldoc.core.DocumentProbe
import java.io.Closeable
import java.util.Locale

/** Disposable location index, separate from hash-bound reading history. All calls require a worker. */
class LibraryCatalog(context: Context, name: String = "library.db", private val maxEntries: Int = MAX_ENTRIES) : Closeable {
    init { require(maxEntries in 1..MAX_ENTRIES) }
    private var closed = false
    private val helper = object : SQLiteOpenHelper(context.applicationContext, name, null, 7,
        DatabaseErrorHandler { throw SQLiteDatabaseCorruptException("The library index is damaged.") }) {
        override fun onConfigure(db: SQLiteDatabase) {
            db.setForeignKeyConstraintsEnabled(true)
            db.rawQuery("PRAGMA journal_size_limit=1048576", null).use { check(it.moveToFirst()) }
            db.rawQuery("PRAGMA wal_autocheckpoint=256", null).use { check(it.moveToFirst()) }
        }
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE ledger (id INTEGER PRIMARY KEY CHECK(id=1), documents INTEGER NOT NULL, locations INTEGER NOT NULL, revision INTEGER NOT NULL)")
            db.execSQL("INSERT INTO ledger VALUES(1,0,0,0)")
            db.execSQL("CREATE TABLE documents (id INTEGER PRIMARY KEY AUTOINCREMENT, hash TEXT UNIQUE, uri TEXT NOT NULL, title TEXT NOT NULL COLLATE NOCASE, category INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX library_title ON documents(category,title COLLATE NOCASE,id)")
            db.execSQL("""CREATE TABLE locations (uri TEXT PRIMARY KEY, document INTEGER NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
                title TEXT NOT NULL, mime TEXT, bytes INTEGER NOT NULL CHECK(bytes>=-1), modified INTEGER NOT NULL,
                revision INTEGER NOT NULL, pinned INTEGER NOT NULL, stamp BLOB) WITHOUT ROWID""")
            db.execSQL("CREATE INDEX library_locations ON locations(document,uri)")
            db.execSQL("CREATE TABLE roots (uri TEXT PRIMARY KEY, generation INTEGER NOT NULL, configured INTEGER NOT NULL) WITHOUT ROWID")
            db.execSQL("""CREATE TABLE membership (root TEXT NOT NULL REFERENCES roots(uri) ON DELETE CASCADE,
                uri TEXT NOT NULL REFERENCES locations(uri) ON DELETE CASCADE, generation INTEGER NOT NULL, checked INTEGER NOT NULL DEFAULT 0, expected_hash TEXT,
                PRIMARY KEY(root,uri)) WITHOUT ROWID""")
            db.execSQL("CREATE INDEX library_membership_uri ON membership(uri)")
            db.execSQL("CREATE INDEX library_membership_generation ON membership(root,generation,uri)")
            db.execSQL("CREATE TABLE configuration(id INTEGER PRIMARY KEY CHECK(id=1), hidden INTEGER NOT NULL, scan INTEGER NOT NULL)")
            db.execSQL("INSERT INTO configuration VALUES(1,0,0)")
            for (table in listOf("documents", "locations")) {
                db.execSQL("CREATE TRIGGER ${table}_insert AFTER INSERT ON $table BEGIN UPDATE ledger SET $table=$table+1 WHERE id=1; END")
                db.execSQL("CREATE TRIGGER ${table}_delete AFTER DELETE ON $table BEGIN UPDATE ledger SET $table=$table-1 WHERE id=1; END")
            }
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            check(oldVersion in 1..6 && newVersion == 7)
            db.execSQL("ALTER TABLE membership ADD COLUMN expected_hash TEXT")
            if (oldVersion == 6) return
            if (oldVersion == 1) {
                db.execSQL("ALTER TABLE documents ADD COLUMN category INTEGER NOT NULL DEFAULT 8")
                db.execSQL("ALTER TABLE locations ADD COLUMN stamp BLOB")
                db.execSQL("ALTER TABLE membership ADD COLUMN checked INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE TABLE configuration(id INTEGER PRIMARY KEY CHECK(id=1), hidden INTEGER NOT NULL, scan INTEGER NOT NULL)")
                db.execSQL("INSERT INTO configuration VALUES(1,0,0)")
                db.execSQL("DROP INDEX library_title")
            } else if (oldVersion == 2) db.execSQL("ALTER TABLE configuration ADD COLUMN scan INTEGER NOT NULL DEFAULT 0")
            // v1/v2 issued scan IDs from the page ledger; v3 already owns an independent counter.
            if (oldVersion < 3) db.execSQL("UPDATE configuration SET scan=max(0,(SELECT revision FROM ledger WHERE id=1)," +
                "(SELECT coalesce(max(generation),0) FROM roots)) WHERE id=1")
            var changed = false
            var afterUri = ""
            while (true) {
                val batch = rows(db, "$LOCATION_SELECT WHERE l.uri>? ORDER BY l.uri LIMIT 128", afterUri)
                if (batch.isEmpty()) break
                val affected = HashSet<Long>()
                for (item in batch) {
                    if (!DocumentProbe.supportedRecord(item.title, item.mimeType)) {
                        db.delete("locations", "uri=?", arrayOf(item.uri))
                        affected.add(item.id)
                        changed = true
                    }
                    afterUri = item.uri
                }
                affected.forEach { repair(db, it) }
            }
            // Only the disposable index changes; reading history and physical files remain intact.
            db.execSQL("UPDATE configuration SET hidden=hidden&15 WHERE id=1")
            var after = 0L
            while (true) {
                val batch = rows(db, "$SELECT WHERE d.id>? ORDER BY d.id LIMIT 128", after.toString())
                if (batch.isEmpty()) break
                for (item in batch) {
                    val category = LibraryCategory.of(item.title, item.mimeType)
                    if (item.category != category) {
                        db.execSQL("UPDATE documents SET category=? WHERE id=?", arrayOf<Any?>(category.code, item.id))
                        changed = true
                    }
                    after = item.id
                }
            }
            if (oldVersion == 1) db.execSQL("CREATE INDEX library_title ON documents(category,title COLLATE NOCASE,id)")
            if (changed) nextRevision(db)
        }
        override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Refusing destructive library downgrade")
    }.apply { setWriteAheadLoggingEnabled(true) }

    fun page(offset: Int, limit: Int): List<LibraryDocument> = pageSnapshot(offset, limit).rows
    fun pageSnapshot(offset: Int, limit: Int): LibraryPage = pageSnapshot(offset, limit, LibraryQuery())

    /** Indexed groups remain stable while fuzzy matching filters bounded batches off the UI thread. */
    fun pageSnapshot(offset: Int, limit: Int, query: LibraryQuery, checkCancelled: () -> Unit = {}): LibraryPage {
        require(offset >= 0 && offset <= MAX_ENTRIES && limit in 1..200)
        require(query.text.length <= 128)
        worker(); checkCancelled()
        val checked = query.copy(text = query.text.trim(), excluded = query.excluded.toSet())
        val generation = synchronized(this) { scalar(database(), "SELECT revision FROM ledger WHERE id=1") }
        val filter = buildList {
            add("d.category IN (0,1,2,3)")
            checked.category?.let { add("d.category=${it.code}") }
            if (checked.excluded.isNotEmpty()) add("d.category NOT IN (${checked.excluded.joinToString { it.code.toString() }})")
        }.joinToString(" AND ").ifEmpty { "1" }
        if (checked.text.isEmpty()) return synchronized(this) {
            checkCancelled()
            val result = rows(database(), "$SELECT WHERE $filter ORDER BY $ORDER LIMIT ? OFFSET ?", limit.toString(), offset.toString())
            verifyGeneration(generation)
            LibraryPage(generation, result)
        }
        // Search never holds the catalog monitor while matching, so discovery can keep publishing.
        return synchronized(searchLock) {
            checkCancelled()
            if (search?.query != checked || search?.generation != generation) search = Search(checked, generation)
            val state = requireNotNull(search)
            var position = state.positions.lastOrNull { it.offset <= offset } ?: Position(0, null)
            var matched = position.offset
            val result = ArrayList<LibraryDocument>(limit)
            while (result.size < limit) {
                checkCancelled()
                val last = position.last
                val batch = synchronized(this) {
                    verifyGeneration(generation)
                    val key = if (last == null) "" else " AND (d.category,d.title COLLATE NOCASE,d.id) > (?,?,?)"
                    val args = if (last == null) emptyArray() else arrayOf(last.category.code.toString(), last.title, last.id.toString())
                    rows(database(), "$SELECT WHERE $filter$key ORDER BY $ORDER LIMIT 128", *args)
                }
                if (batch.isEmpty()) break
                val matches = NativeFuzzy.matches(checked.text, batch.map { it.title }, checkCancelled)
                for (i in batch.indices) {
                    val item = batch[i]
                    if (matches[i]) {
                        if (matched >= offset) result.add(item)
                        matched++
                    }
                    position = Position(matched, Key(item.category, item.title, item.id))
                    if (result.size == limit) break
                }
            }
            synchronized(this) { verifyGeneration(generation) }
            // Fixed-size checkpoints avoid rescanning from row zero for normal forward paging.
            state.positions.removeAll { it.offset == position.offset }
            state.positions.add(position)
            state.positions.sortBy { it.offset }
            if (state.positions.size > 128) state.positions.removeAt(0)
            LibraryPage(generation, result)
        }
    }
    private data class Key(val category: LibraryCategory, val title: String, val id: Long)
    private data class Position(val offset: Int, val last: Key?)
    private data class Search(val query: LibraryQuery, val generation: Long, val positions: MutableList<Position> = ArrayList())
    private val searchLock = Any()
    private var search: Search? = null
    private fun verifyGeneration(expected: Long) {
        if (scalar(database(), "SELECT revision FROM ledger WHERE id=1") != expected)
            throw ConcurrentModificationException("Library changed while reading a page.")
    }
    @Synchronized fun hiddenTypes(): Set<LibraryCategory> {
        val mask = scalar(database(), "SELECT hidden FROM configuration WHERE id=1").toInt()
        return LibraryCategory.available.filterTo(linkedSetOf()) { mask and (1 shl it.code) != 0 }
    }
    @Synchronized fun setHiddenTypes(categories: Set<LibraryCategory>) = transaction { db ->
        val mask = categories.filter { it in LibraryCategory.available }.fold(0) { value, category -> value or (1 shl category.code) }
        if (scalar(db, "SELECT hidden FROM configuration WHERE id=1") != mask.toLong()) {
            db.execSQL("UPDATE configuration SET hidden=? WHERE id=1", arrayOf(mask)); nextRevision(db)
        }
        Unit
    }
    @Synchronized fun count(): Int = scalar(database(), "SELECT documents FROM ledger WHERE id=1").toInt()
    @Synchronized fun get(id: Long): LibraryDocument? = rows(database(), "$SELECT WHERE d.id=?", id.toString()).firstOrNull()
    @Synchronized fun find(hash: String): LibraryDocument? = rows(database(), "$SELECT WHERE d.hash=?", checkedHash(hash)).firstOrNull()
    @Synchronized fun location(uri: String): LibraryDocument? = location(database(), uri.checkedUri())
    @Synchronized fun locationsForHash(hash: String, limit: Int = 16): List<String> {
        require(limit in 1..128)
        val db = database()
        val digest = checkedHash(hash)
        val select = "SELECT l.uri FROM locations l JOIN documents d ON d.id=l.document WHERE d.hash=?"
        // Prefer local files before spending the bounded admission window on provider aliases.
        val files = strings(db, "$select AND l.uri GLOB 'file:*' ORDER BY l.uri LIMIT ?", digest, limit.toString())
        return if (files.size == limit) files else files + strings(db,
            "$select AND l.uri NOT GLOB 'file:*' ORDER BY l.uri LIMIT ?", digest, (limit - files.size).toString())
    }
    @Synchronized fun pending(afterId: Long = 0, limit: Int = 128): List<LibraryDocument> {
        require(afterId >= 0 && limit in 1..128)
        return rows(database(), "$SELECT WHERE d.hash IS NULL AND d.id>? ORDER BY d.id LIMIT ?", afterId.toString(), limit.toString())
    }
    @Synchronized fun scanPending(root: String, generation: Long, afterUri: String = "", limit: Int = 128, verifiedOnly: Boolean = false): List<LibraryDocument> {
        require(limit in 1..128)
        val db = database()
        if (!currentScan(db, root, generation)) return emptyList()
        return rows(db, "$LOCATION_SELECT JOIN membership m ON m.uri=l.uri WHERE m.root=? AND m.generation=? AND l.uri>?${if (verifiedOnly) " AND m.checked=l.revision" else ""} ORDER BY l.uri LIMIT ?",
            root, generation.toString(), afterUri, limit.toString())
    }

    /** Probes commit together; a late result cannot replace a newer discovery or grant hash admission. */
    @Synchronized fun verifyBatch(documents: List<Pair<LibraryDocument, String?>>, root: String, generation: Long,
        onChanged: () -> Unit = {}): Int {
        require(documents.size <= 128)
        var changed = false
        val count = transaction { db ->
            if (!currentScan(db, root, generation)) return@transaction 0
            var accepted = 0
            for ((discovered, mime) in documents) {
                require(mime == null || mime.length <= 128 && '\u0000' !in mime)
                val current = location(db, discovered.uri) ?: continue
                if (current.id != discovered.id || current.revision != discovered.revision) continue
                val matched = db.update("membership", ContentValues().apply { put("checked", current.revision) },
                    "root=? AND uri=? AND generation=?", arrayOf(root, current.uri, generation.toString()))
                if (matched == 0) continue
                if (current.mimeType != mime) {
                    changed = true
                    val id = if (hasOtherLocation(db, current.id, current.uri)) newDocument(db, current.uri, current.title, mime = mime)
                    else {
                        db.execSQL("UPDATE documents SET hash=NULL,category=? WHERE id=?",
                            arrayOf<Any?>(LibraryCategory.of(current.title, mime).code, current.id))
                        current.id
                    }
                    val revision = nextRevision(db)
                    db.execSQL("UPDATE locations SET document=?,mime=?,revision=? WHERE uri=?",
                        arrayOf<Any?>(id, mime, revision, current.uri))
                    db.execSQL("UPDATE membership SET checked=? WHERE root=? AND uri=? AND generation=?",
                        arrayOf<Any?>(revision, root, current.uri, generation))
                    if (id != current.id) repair(db, current.id)
                }
                accepted++
            }
            accepted
        }
        if (changed) onChanged()
        return count
    }
    fun verified(document: LibraryDocument, root: String, generation: Long): Boolean =
        verifyBatch(listOf(document to document.mimeType), root, generation) == 1

    fun discover(file: LibraryFile, root: String? = null, generation: Long? = null): LibraryDocument? =
        discoverBatch(listOf(file), root, generation).firstOrNull()

    /** Returns no rows for a superseded scan; each transaction admits at most 128 locations. */
    @Synchronized fun discoverBatch(files: List<LibraryFile>, root: String? = null, generation: Long? = null,
        provisional: Boolean = false, onChanged: () -> Unit = {}): List<LibraryDocument> {
        require(files.size <= 128 && (root == null) == (generation == null))
        val checked = files.map { it.checked() }.filter { DocumentProbe.supportedRecord(it.title, it.mimeType) }
        var metadataChanged = false
        val rows = transaction { db ->
            if (root != null && !currentScan(db, root, requireNotNull(generation))) return@transaction emptyList()
            checked.map { supplied ->
                val previous = location(db, supplied.uri)
                val file = if (provisional && root != null && previous != null && supplied.title == previous.title &&
                    supplied.sizeBytes == previous.sizeBytes && supplied.modifiedAt == previous.modifiedAt && supplied.stamp == previous.stamp)
                    supplied.copy(mimeType = previous.mimeType) else supplied
                val changed = previous == null || previous.title != file.title || previous.mimeType != file.mimeType ||
                    previous.sizeBytes != file.sizeBytes || previous.modifiedAt != file.modifiedAt || previous.stamp != file.stamp
                if (changed) {
                    metadataChanged = true
                    if (previous == null) check(scalar(db, "SELECT locations FROM ledger WHERE id=1") < maxEntries) { "Library limit reached." }
                    val id = if (previous != null && !hasOtherLocation(db, previous.id, previous.uri)) {
                        db.execSQL("UPDATE documents SET hash=NULL,title=?,category=? WHERE id=?", arrayOf<Any?>(file.title, LibraryCategory.of(file.title, file.mimeType).code, previous.id))
                        previous.id
                    } else newDocument(db, file.uri, file.title, mime = file.mimeType)
                    val values = ContentValues().apply {
                        put("document", id); put("title", file.title); put("mime", file.mimeType); put("bytes", file.sizeBytes)
                        put("modified", file.modifiedAt); put("revision", nextRevision(db)); put("stamp", file.stamp?.encode())
                    }
                    if (previous == null) {
                        values.put("uri", file.uri); values.put("pinned", if (root == null) 1 else 0)
                        db.insertOrThrow("locations", null, values)
                    } else db.update("locations", values, "uri=?", arrayOf(file.uri))
                    if (previous != null && previous.id != id) repair(db, previous.id)
                }
                if (root == null) db.execSQL("UPDATE locations SET pinned=1 WHERE uri=?", arrayOf<Any?>(file.uri))
                else db.execSQL("INSERT OR REPLACE INTO membership(root,uri,generation) VALUES(?,?,?)", arrayOf<Any?>(root, file.uri, generation))
                requireNotNull(location(db, file.uri))
            }
        }
        if (metadataChanged) onChanged()
        return rows
    }

    /** Reuse a subtype only after the same bytes were read again; metadata alone is not proof. */
    @Synchronized fun rediscoverKnown(previous: LibraryDocument, metadata: LibraryFile, hash: String,
        root: String, generation: Long): LibraryDocument? {
        val file = metadata.copy(mimeType = previous.mimeType).checked()
        val digest = checkedHash(hash)
        require(file.uri == previous.uri)
        return transaction { db ->
            if (previous.hash != digest || !currentScan(db, root, generation)) return@transaction null
            val current = location(db, file.uri) ?: return@transaction null
            if (current.id != previous.id || current.revision != previous.revision || current.hash != digest)
                return@transaction null
            if (current.title != file.title || current.sizeBytes != file.sizeBytes ||
                current.modifiedAt != file.modifiedAt || current.stamp != file.stamp) {
                db.update("locations", ContentValues().apply {
                    put("title", file.title); put("bytes", file.sizeBytes); put("modified", file.modifiedAt)
                    put("stamp", file.stamp?.encode()); put("revision", nextRevision(db))
                }, "uri=?", arrayOf(file.uri))
                repair(db, current.id)
            }
            db.execSQL("INSERT OR REPLACE INTO membership(root,uri,generation,expected_hash) VALUES(?,?,?,?)",
                arrayOf<Any?>(root, file.uri, generation, digest))
            requireNotNull(location(db, file.uri))
        }
    }

    @Synchronized fun expectedScanHash(document: LibraryDocument, root: String, generation: Long): String? {
        val db = database()
        if (!currentScan(db, root, generation)) return null
        return db.rawQuery("SELECT m.expected_hash FROM membership m JOIN locations l ON l.uri=m.uri " +
            "WHERE m.root=? AND m.generation=? AND m.uri=? AND l.document=? AND l.revision=? AND m.checked=l.revision",
            arrayOf(root, generation.toString(), document.uri, document.id.toString(), document.revision.toString())).use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }

    /** The discovery token prevents late digests from attaching to replaced files or newer scans. */
    @Synchronized fun resolve(discovered: LibraryDocument, hash: String, root: String? = null, generation: Long? = null,
        mime: String? = discovered.mimeType): LibraryDocument? {
        require((root == null) == (generation == null))
        require(mime == null || mime.length <= 128 && '\u0000' !in mime)
        val digest = checkedHash(hash)
        return transaction { db ->
            if (root != null && (!currentScan(db, root, requireNotNull(generation)) ||
                scalar(db, "SELECT count(*) FROM membership WHERE root=? AND uri=? AND generation=?", root, discovered.uri, generation.toString()) == 0L))
                return@transaction null
            val current = location(db, discovered.uri) ?: return@transaction null
            if (current.id != discovered.id || current.revision != discovered.revision) return@transaction null
            if (current.hash != null && current.hash != discovered.hash && current.hash != digest) return@transaction null
            if (current.hash == digest && current.mimeType == mime) return@transaction current
            val revision = nextRevision(db)
            val existing = rows(db, "$SELECT WHERE d.hash=?", digest).firstOrNull()
            val id = existing?.id ?: if (current.hash == null || !hasOtherLocation(db, current.id, current.uri)) {
                db.execSQL("UPDATE documents SET hash=? WHERE id=?", arrayOf<Any?>(digest, current.id)); current.id
            } else newDocument(db, current.uri, current.title, digest, mime)
            val values = ContentValues().apply {
                put("document", id); put("mime", mime)
                if (current.hash != null || current.mimeType != mime) put("revision", revision)
            }
            db.update("locations", values, "uri=?", arrayOf(current.uri))
            if (id != current.id) repair(db, current.id)
            if (current.mimeType != mime) repair(db, id)
            requireNotNull(location(db, current.uri))
        }
    }

    @Synchronized fun invalidate(document: LibraryDocument): Boolean = transaction { db ->
        val current = location(db, document.uri) ?: return@transaction false
        if (current.id != document.id || current.revision != document.revision) return@transaction false
        val id = if (hasOtherLocation(db, current.id, current.uri)) newDocument(db, current.uri, current.title, mime = current.mimeType)
        else { db.execSQL("UPDATE documents SET hash=NULL WHERE id=?", arrayOf<Any?>(current.id)); current.id }
        db.execSQL("UPDATE locations SET document=?,bytes=-1,modified=0,stamp=NULL,revision=? WHERE uri=?",
            arrayOf<Any?>(id, nextRevision(db), current.uri))
        if (id != current.id) repair(db, current.id)
        true
    }
    @Synchronized fun removeLocation(uri: String, expectedHash: String? = null): Boolean = transaction { db ->
        val digest = expectedHash?.let(::checkedHash)
        val current = location(db, uri.checkedUri()) ?: return@transaction false
        if (digest != null && current.hash != digest) return@transaction false
        remove(db, uri, current.id); true
    }
    @Synchronized fun removeLocation(document: LibraryDocument): Boolean = transaction { db ->
        val current = location(db, document.uri.checkedUri()) ?: return@transaction false
        if (current.id != document.id || current.revision != document.revision) return@transaction false
        remove(db, current.uri, current.id); true
    }
    @Synchronized fun roots(): List<String> = strings(database(), "SELECT uri FROM roots WHERE configured=1 ORDER BY uri")
    @Synchronized fun knownRoots(): List<String> = strings(database(),
        "SELECT uri FROM roots r WHERE configured=1 OR EXISTS(SELECT 1 FROM membership m WHERE m.root=r.uri) ORDER BY uri")
    @Synchronized fun addRoot(root: String) = transaction { db ->
        ensureRoot(db, root.checkedUri())
        db.execSQL("UPDATE roots SET configured=1 WHERE uri=?", arrayOf<Any?>(root))
    }
    @Synchronized fun beginScan(root: String): Long = transaction { db ->
        ensureRoot(db, root.checkedUri())
        nextScan(db, root)
    }

    /** Call only after a complete traversal. Locations still indexed by other roots survive. */
    fun finishScan(root: String, generation: Long, signal: CancellationSignal? = null, onChanged: () -> Unit = {}): Boolean =
        prune(root, generation, false, signal, onChanged)
    fun removeRoot(root: String, signal: CancellationSignal? = null): Boolean {
        val generation = synchronized(this) {
            transaction { db ->
                if (scalar(db, "SELECT count(*) FROM roots WHERE uri=?", root.checkedUri()) == 0L) return@transaction null
                val next = nextScan(db, root)
                db.execSQL("UPDATE roots SET configured=0 WHERE uri=?", arrayOf<Any?>(root))
                next
            }
        } ?: return false
        return prune(root, generation, true, signal)
    }
    private fun prune(root: String, generation: Long, removeRoot: Boolean, signal: CancellationSignal?, onChanged: () -> Unit = {}): Boolean {
        val orphan = "SELECT count(*) FROM locations WHERE uri=?" + (if (removeRoot) " AND pinned=0" else "") +
            " AND NOT EXISTS(SELECT 1 FROM membership WHERE membership.uri=locations.uri)"
        while (true) {
            signal?.throwIfCanceled()
            var changed = false
            val remaining = synchronized(this) {
                transaction { db ->
                    if (!currentScan(db, root, generation)) return@transaction -1
                    val uris = strings(db, "SELECT uri FROM membership WHERE root=? AND generation<? ORDER BY generation,uri LIMIT 128", root, generation.toString())
                    for (uri in uris) {
                        db.delete("membership", "root=? AND uri=?", arrayOf(root, uri))
                        if (scalar(db, orphan, uri) != 0L)
                            location(db, uri)?.let { remove(db, uri, it.id); changed = true }
                    }
                    if (uris.isEmpty() && removeRoot) db.delete("roots", "uri=? AND generation=?", arrayOf(root, generation.toString()))
                    uris.size
                }
            }
            if (changed) onChanged()
            if (remaining <= 0) return remaining == 0
        }
    }

    @Synchronized override fun close() { worker(); closed = true; helper.close() }
    private fun database(): SQLiteDatabase { worker(); check(!closed) { "Library catalog is closed." }; return helper.writableDatabase }
    private inline fun <T> transaction(action: (SQLiteDatabase) -> T): T {
        val db = database(); db.beginTransaction()
        try { val result = action(db); db.setTransactionSuccessful(); return result } finally { db.endTransaction() }
    }
    private fun newDocument(db: SQLiteDatabase, uri: String, title: String, hash: String? = null, mime: String? = null) =
        db.insertOrThrow("documents", null, ContentValues().apply { put("hash", hash); put("uri", uri); put("title", title); put("category", LibraryCategory.of(title, mime).code) })
    private fun hasOtherLocation(db: SQLiteDatabase, id: Long, uri: String) =
        scalar(db, "SELECT EXISTS(SELECT 1 FROM locations WHERE document=? AND uri<>? LIMIT 1)", id.toString(), uri) != 0L
    private fun remove(db: SQLiteDatabase, uri: String, id: Long) {
        if (db.delete("locations", "uri=?", arrayOf(uri)) > 0) { nextRevision(db); repair(db, id) }
    }
    private fun repair(db: SQLiteDatabase, id: Long) {
        val current = rows(db, "$SELECT WHERE d.id=? AND l.document=d.id", id.toString()).firstOrNull()
        if (current != null) { db.execSQL("UPDATE documents SET title=?,category=? WHERE id=?", arrayOf<Any?>(current.title, LibraryCategory.of(current.title, current.mimeType).code, id)); return }
        val replacement = rows(db, "$LOCATION_SELECT WHERE d.id=? ORDER BY l.uri LIMIT 1", id.toString()).firstOrNull()
        if (replacement == null) db.delete("documents", "id=?", arrayOf(id.toString()))
        else db.execSQL("UPDATE documents SET uri=?,title=?,category=? WHERE id=?", arrayOf<Any?>(replacement.uri, replacement.title, LibraryCategory.of(replacement.title, replacement.mimeType).code, id))
    }
    private fun ensureRoot(db: SQLiteDatabase, root: String) {
        if (scalar(db, "SELECT count(*) FROM roots WHERE uri=?", root) == 0L) {
            check(scalar(db, "SELECT count(*) FROM roots") < 256) { "Library folder limit reached." }
            db.execSQL("INSERT INTO roots(uri,generation,configured) VALUES(?,0,0)", arrayOf<Any?>(root))
        }
    }
    private fun nextScan(db: SQLiteDatabase, root: String): Long {
        val previous = scalar(db, "SELECT scan FROM configuration WHERE id=1")
        check(previous < Long.MAX_VALUE) { "Library scan generation limit reached." }
        val generation = previous + 1
        db.execSQL("UPDATE configuration SET scan=? WHERE id=1", arrayOf(generation))
        db.execSQL("UPDATE roots SET generation=? WHERE uri=?", arrayOf<Any?>(generation, root))
        return generation
    }
    private fun currentScan(db: SQLiteDatabase, root: String, generation: Long): Boolean =
        generation > 0 && scalar(db, "SELECT count(*) FROM roots WHERE uri=? AND generation=?", root, generation.toString()) != 0L
    private fun nextRevision(db: SQLiteDatabase): Long {
        val previous = scalar(db, "SELECT revision FROM ledger WHERE id=1")
        check(previous < Long.MAX_VALUE) { "Library revision limit reached." }
        db.execSQL("UPDATE ledger SET revision=revision+1 WHERE id=1")
        return previous + 1
    }
    private fun location(db: SQLiteDatabase, uri: String) = rows(db, "$LOCATION_SELECT WHERE l.uri=?", uri).firstOrNull()
    private fun rows(db: SQLiteDatabase, sql: String, vararg args: String): List<LibraryDocument> =
        db.rawQuery(sql, args).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.document()) } }
    private fun strings(db: SQLiteDatabase, sql: String, vararg args: String): List<String> =
        db.rawQuery(sql, args).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
    private fun scalar(db: SQLiteDatabase, sql: String, vararg args: String): Long =
        db.rawQuery(sql, args).use { check(it.moveToFirst()); it.getLong(0) }
    private fun Cursor.document(offset: Int = 0) = LibraryDocument(getLong(offset), getString(offset + 1), getString(offset + 2),
        getString(offset + 3), getLong(offset + 4), getString(offset + 5), getLong(offset + 6), getLong(offset + 7), LibraryCategory.fromCode(getInt(offset + 8)), LibraryStamp.decode(getBlob(offset + 9)))
    private fun LibraryFile.checked() = copy(uri = uri.checkedUri(), title = title.takeScalarSafe(256), modifiedAt = modifiedAt.coerceAtLeast(0)).also {
        require(it.title.isNotBlank() && '\u0000' !in it.title && it.sizeBytes >= -1 && it.modifiedAt >= 0)
        require(it.mimeType == null || it.mimeType.length <= 128 && '\u0000' !in it.mimeType)
    }
    private fun String.checkedUri() = also { require(length in 1..4096 && '\u0000' !in this) { "Invalid library location." } }
    private fun checkedHash(value: String): String {
        require(value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' })
        return value.lowercase(Locale.ROOT)
    }
    private fun worker() { check(Looper.myLooper() != Looper.getMainLooper()) { "Library persistence must run off the main thread" } }
    companion object {
        const val MAX_ENTRIES = 1_000_000
        private const val ORDER = "d.category,d.title COLLATE NOCASE,d.id"
        private const val COLUMNS = "d.id,d.hash,l.title,l.mime,l.bytes,l.uri,l.modified,l.revision,d.category,l.stamp"
        private const val SELECT = "SELECT $COLUMNS FROM documents d JOIN locations l ON l.uri=d.uri AND l.document=d.id"
        private const val LOCATION_SELECT = "SELECT $COLUMNS FROM locations l JOIN documents d ON d.id=l.document"
    }
}
