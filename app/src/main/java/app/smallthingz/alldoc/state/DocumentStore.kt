package app.smallthingz.alldoc.state

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteOpenHelper
import android.os.Looper
import app.smallthingz.alldoc.core.DocumentFormats
import app.smallthingz.alldoc.core.DocumentProbe
import app.smallthingz.alldoc.core.takeScalarSafe
import app.smallthingz.alldoc.theme.ImageTreatment
import app.smallthingz.alldoc.theme.ReadingMode
import app.smallthingz.alldoc.theme.ThemeSettings
import java.io.Closeable
import java.util.Locale

/** Blocking persistence. Call from one ordered IO worker, never from UI/Binder callbacks. */
class DocumentStore(context: Context, name: String = "documents.db", private val maxEntries: Int = MAX_ENTRIES,
    private val clock: () -> Long = System::currentTimeMillis) : Closeable {
    init { require(maxEntries in 1..MAX_ENTRIES) }
    private val helper = object : SQLiteOpenHelper(context.applicationContext, name, null, 5,
        DatabaseErrorHandler { throw SQLiteDatabaseCorruptException("Saved document state is damaged; the database was preserved.") }) {
        override fun onConfigure(db: SQLiteDatabase) {
            db.rawQuery("PRAGMA journal_mode=TRUNCATE", null).use { check(it.moveToFirst()) }
            db.execSQL("PRAGMA synchronous=FULL")
            db.rawQuery("PRAGMA journal_size_limit=0", null).use { check(it.moveToFirst()) }
            val vacuum = scalar(db, "PRAGMA auto_vacuum")
            if (vacuum != 2L) {
                db.execSQL("PRAGMA auto_vacuum=INCREMENTAL")
                // Android can create android_metadata before onCreate; pointer maps need a rebuild.
                if (vacuum == 0L) db.execSQL("VACUUM")
                check(scalar(db, "PRAGMA auto_vacuum") == 2L) { "Cannot configure bounded state-database cleanup" }
            }
        }
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE ledger (id INTEGER PRIMARY KEY CHECK(id=1), entries INTEGER NOT NULL, stamp INTEGER NOT NULL)")
            db.execSQL("INSERT INTO ledger VALUES(1,0,0)")
            db.execSQL("""CREATE TABLE documents (
                hash BLOB PRIMARY KEY CHECK(length(hash)=32), title TEXT NOT NULL, mime TEXT, size INTEGER NOT NULL CHECK(size>=0),
                source_uri TEXT, import_uri TEXT, opened INTEGER NOT NULL,
                page INTEGER NOT NULL, zoom REAL NOT NULL, cx REAL NOT NULL, cy REAL NOT NULL, rotation INTEGER NOT NULL,
                text_offset INTEGER NOT NULL, text_fraction REAL NOT NULL, view INTEGER NOT NULL,
                appearance INTEGER NOT NULL, preserve_images INTEGER NOT NULL, hide_margins INTEGER NOT NULL DEFAULT 0,
                page_fraction REAL NOT NULL DEFAULT 0, horizontal_pages INTEGER NOT NULL DEFAULT 0, notes_visible INTEGER NOT NULL DEFAULT 0,
                paper_roughness INTEGER NOT NULL DEFAULT 0) WITHOUT ROWID""")
            db.execSQL("CREATE INDEX documents_recency ON documents(opened DESC,hash DESC)")
            db.execSQL("CREATE TRIGGER document_insert AFTER INSERT ON documents BEGIN UPDATE ledger SET entries=entries+1 WHERE id=1; END")
            db.execSQL("CREATE TRIGGER document_delete AFTER DELETE ON documents BEGIN UPDATE ledger SET entries=entries-1 WHERE id=1; END")
            db.execSQL("CREATE TABLE settings (id INTEGER PRIMARY KEY CHECK(id=1), enabled INTEGER NOT NULL, bytes INTEGER NOT NULL, count INTEGER NOT NULL, tree TEXT)")
            db.execSQL("INSERT INTO settings VALUES(1,1,268435456,1000,NULL)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) db.execSQL("ALTER TABLE documents ADD COLUMN hide_margins INTEGER NOT NULL DEFAULT 0")
            if (oldVersion < 3) {
                db.execSQL("ALTER TABLE documents ADD COLUMN page_fraction REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE documents ADD COLUMN horizontal_pages INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE documents SET page_fraction=-1 WHERE view=0 AND zoom>1")
            }
            if (oldVersion < 4) db.execSQL("ALTER TABLE documents ADD COLUMN notes_visible INTEGER NOT NULL DEFAULT 0")
            if (oldVersion < 5) db.execSQL("ALTER TABLE documents ADD COLUMN paper_roughness INTEGER NOT NULL DEFAULT 0")
        }
        override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Refusing destructive document-state downgrade")
    }.apply { setWriteAheadLoggingEnabled(false) }

    /** Opens update only metadata/recency. Existing reading preferences always win over initialState. */
    @Synchronized fun open(hash: String, metadata: DocumentMetadata, initialState: DocumentState = DocumentState()): StoredDocument {
        val key = key(hash); val meta = metadata.checked()
        return transaction { db ->
            val now = tick(db)
            val values = stateValues(initialState.normalized()).apply {
                put("hash", key.bytes); put("title", meta.title); put("mime", meta.mimeType); put("size", meta.sizeBytes)
                put("source_uri", meta.sourceUri); put("import_uri", meta.importUri); put("opened", now)
            }
            db.insertWithOnConflict("documents", null, values, SQLiteDatabase.CONFLICT_IGNORE)
            val update = ContentValues().apply {
                put("title", meta.title); put("size", meta.sizeBytes); put("opened", now)
                if (meta.mimeType != null) put("mime", meta.mimeType)
                if (meta.sourceUri != null) put("source_uri", meta.sourceUri)
                if (meta.importUri != null) put("import_uri", meta.importUri)
            }
            db.update("documents", update, key.where, null)
            val extra = scalar(db, "SELECT entries FROM ledger WHERE id=1") - maxEntries
            if (extra > 0) {
                db.execSQL("DELETE FROM documents WHERE hash IN (SELECT hash FROM documents ORDER BY opened ASC,hash ASC LIMIT ?)", arrayOf(extra))
                db.rawQuery("PRAGMA incremental_vacuum(32)", null).use { while (it.moveToNext()) { /* consume bounded vacuum steps */ } }
            }
            requireNotNull(read(db, key))
        }
    }

    @Synchronized fun get(hash: String): StoredDocument? = read(database(), key(hash))

    /** Never recreates an evicted row or overwrites its metadata. Identical states do not write. */
    @Synchronized fun saveState(hash: String, state: DocumentState): Boolean {
        val key = key(hash); val value = state.normalized()
        return transaction { db ->
            val old = read(db, key) ?: return@transaction false
            if (old.state != value) db.update("documents", stateValues(value).apply { put("opened", tick(db)) }, key.where, null)
            true
        }
    }

    @Synchronized fun rename(hash: String, title: String): Boolean {
        val key = key(hash); val name = title.checkedTitle()
        return transaction { db -> db.update("documents", ContentValues().apply { put("title", name) }, key.where, null) > 0 }
    }

    /** A bounded page of most recently used documents; never materializes the million-row catalog. */
    @Synchronized fun recent(limit: Int = 50): List<StoredDocument> {
        require(limit in 1..200)
        return database().rawQuery("SELECT * FROM documents ORDER BY opened DESC,hash DESC LIMIT ?", arrayOf(limit.toString())).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.document()) }
        }
    }

    /** Filter before the result limit; old unsupported history keeps its saved state. */
    @Synchronized fun recentDocuments(limit: Int = 50): List<StoredDocument> {
        require(limit in 1..200)
        val db = database()
        val (candidates, arguments) = recentDocumentCandidates
        var opened: Long? = null
        var hash = ""
        return buildList(limit) {
            while (true) {
                val anchor = if (opened == null) "" else " AND (opened,hash) < (?,X'$hash')"
                val args = if (opened == null) arguments else arguments + arrayOf(opened.toString())
                val exhausted = db.rawQuery("SELECT * FROM documents WHERE $candidates$anchor ORDER BY opened DESC,hash DESC LIMIT 128", args).use { cursor ->
                    var count = 0
                    while (cursor.moveToNext()) {
                        count++
                        if (DocumentProbe.supportedHistory(cursor.getString(1), cursor.nullString(2))) {
                            add(cursor.document())
                            if (size == limit) return@buildList
                        }
                    }
                    if (count > 0) {
                        check(cursor.moveToLast())
                        opened = cursor.getLong(6)
                        hash = cursor.getBlob(0).hex()
                    }
                    count < 128
                }
                if (exhausted) break
            }
        }
    }

    /** A delayed eviction cannot erase the reference to a newer retained copy of the same bytes. */
    @Synchronized fun clearImport(hash: String, expectedUri: String): Boolean =
        clearMatchingImports(listOf(hash to expectedUri)) > 0
    @Synchronized fun clearMatchingImports(expected: List<Pair<String, String>>): Int {
        require(expected.size <= 1000) { "Clear imported references in bounded batches" }
        val keys = expected.map { (hash, uri) -> checkedUri(uri); key(hash) to uri }
        if (keys.isEmpty()) return 0
        return transaction { db ->
            val clear = ContentValues().apply { putNull("import_uri") }
            keys.sumOf { (key, uri) -> db.update("documents", clear, "${key.where} AND import_uri=?", arrayOf(uri)) }
        }
    }
    @Synchronized fun clearImport(hash: String) { clearImports(listOf(hash)) }
    @Synchronized fun clearImports(hashes: Collection<String>) {
        require(hashes.size <= 1000) { "Clear imported references in bounded batches" }
        val keys = hashes.map(::key)
        transaction { db -> keys.forEach { db.update("documents", ContentValues().apply { putNull("import_uri") }, it.where, null) } }
    }

    @Synchronized fun copySettings(): CopySettings = database().rawQuery("SELECT enabled,bytes,count,tree FROM settings WHERE id=1", null).use {
        check(it.moveToFirst()); CopySettings(it.getInt(0) != 0, it.getLong(1), it.getInt(2), it.nullString(3))
    }
    @Synchronized fun saveCopySettings(value: CopySettings) {
        require(value.maxBytes in 1..MAX_COPY_BYTES && value.maxCount in 1..MAX_COPIES)
        checkedUri(value.treeUri)
        transaction { db -> db.execSQL("UPDATE settings SET enabled=?,bytes=?,count=?,tree=? WHERE id=1",
            arrayOf<Any?>(if (value.enabled) 1 else 0, value.maxBytes, value.maxCount, value.treeUri)) }
    }

    @Synchronized fun count(): Long = scalar(database(), "SELECT entries FROM ledger WHERE id=1")
    @Synchronized override fun close() { worker(); helper.close() }

    private fun database(): SQLiteDatabase { worker(); return helper.writableDatabase }
    private inline fun <T> transaction(action: (SQLiteDatabase) -> T): T {
        val db = database(); db.beginTransaction()
        try { val result = action(db); db.setTransactionSuccessful(); return result } finally { db.endTransaction() }
    }
    private fun tick(db: SQLiteDatabase): Long {
        val old = scalar(db, "SELECT stamp FROM ledger WHERE id=1")
        check(old < Long.MAX_VALUE) { "Document recency counter exhausted" }
        val value = maxOf(clock().coerceAtLeast(0), old + 1)
        db.execSQL("UPDATE ledger SET stamp=? WHERE id=1", arrayOf(value)); return value
    }
    private fun read(db: SQLiteDatabase, key: Key): StoredDocument? = db.rawQuery("SELECT * FROM documents WHERE ${key.where}", null).use {
        if (it.moveToFirst()) it.document() else null
    }
    private fun Cursor.document() = StoredDocument(getBlob(0).hex(),
        DocumentMetadata(getString(1), nullString(2), getLong(3), nullString(4), nullString(5)),
        DocumentState(getInt(7), ViewportState(getFloat(8), getFloat(9), getFloat(10), getInt(11)),
            TextPosition(getInt(12), getFloat(13)), if (getInt(14) == 1) DocumentViewMode.TEXT else DocumentViewMode.PAGES,
            ThemeSettings(readingMode(getInt(15)), ImageTreatment.fromCode(getInt(16)), getInt(21)), getInt(17) != 0, getFloat(18), getInt(19) != 0, getInt(20) != 0).normalized(), getLong(6))

    private data class Key(val hex: String, val bytes: ByteArray) { val where get() = "hash=X'$hex'" }
    private fun key(value: String): Key {
        require(value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) { "Expected a full SHA-256 content hash" }
        val hex = value.lowercase(Locale.ROOT)
        return Key(hex, ByteArray(32) { ((hex[it * 2].digitToInt(16) shl 4) or hex[it * 2 + 1].digitToInt(16)).toByte() })
    }
    private fun ByteArray.hex() = buildString(size * 2) {
        for (byte in this@hex) { val n = byte.toInt() and 255; append("0123456789abcdef"[n ushr 4]); append("0123456789abcdef"[n and 15]) }
    }
    private fun DocumentMetadata.checked(): DocumentMetadata {
        require(sizeBytes >= 0); require(mimeType == null || mimeType.length <= 128)
        checkedUri(sourceUri); checkedUri(importUri)
        return copy(title = title.checkedTitle())
    }
    private fun String.checkedTitle(): String = takeScalarSafe(256).also { require(it.isNotBlank() && '\u0000' !in it) }
    private fun checkedUri(value: String?) { require(value == null || value.length in 1..4096 && '\u0000' !in value) { "Document reference exceeds its metadata limit" } }
    private fun worker() { check(Looper.myLooper() != Looper.getMainLooper()) { "Document persistence must run off the main thread" } }
    private fun Cursor.nullString(index: Int): String? = if (isNull(index)) null else getString(index)
    private fun scalar(db: SQLiteDatabase, sql: String): Long = db.rawQuery(sql, null).use { check(it.moveToFirst()); it.getLong(0) }
    private fun stateValues(value: DocumentState) = ContentValues().apply {
        put("paper_roughness", value.appearance.paperRoughness)
        put("hide_margins", if (value.hideMargins) 1 else 0); put("page_fraction", value.pageFraction)
        put("horizontal_pages", if (value.horizontalPages) 1 else 0); put("notes_visible", if (value.notesVisible) 1 else 0)
        put("page", value.page); put("zoom", value.viewport.zoom); put("cx", value.viewport.centerX); put("cy", value.viewport.centerY)
        put("rotation", value.viewport.rotation); put("text_offset", value.textPosition.offset); put("text_fraction", value.textPosition.fraction)
        put("view", if (value.mode == DocumentViewMode.TEXT) 1 else 0); put("appearance", readingCode(value.appearance.mode)); put("preserve_images", value.appearance.imageTreatment.code)
    }
    private fun readingCode(value: ReadingMode) = when (value) { ReadingMode.ORIGINAL -> 0; ReadingMode.ADAPTIVE -> 1; ReadingMode.SEPIA -> 2; ReadingMode.HIGH_CONTRAST -> 3; ReadingMode.INVERT -> 4; ReadingMode.ADAPTIVE_LIGHT -> 5; ReadingMode.SYSTEM -> 6 }
    private fun readingMode(value: Int) = when (value) { 1 -> ReadingMode.ADAPTIVE; 2 -> ReadingMode.SEPIA; 3 -> ReadingMode.HIGH_CONTRAST; 4 -> ReadingMode.INVERT; 5 -> ReadingMode.ADAPTIVE_LIGHT; 6 -> ReadingMode.SYSTEM; else -> ReadingMode.ORIGINAL }
    companion object {
        // Conservative SQL candidates avoid loading removed formats. The central predicate
        // remains authoritative, including Kotlin's Unicode case/whitespace handling.
        private val recentDocumentCandidates by lazy {
            val mime = DocumentFormats.intentMimeTypes.map { "%${it.lowercase(Locale.ROOT)}%" }.distinct()
            val names = DocumentFormats.all.flatMap { it.extensions }.map { "%.$it" }
            val terms = List(mime.size) { "mime LIKE ?" } + List(names.size) { "title LIKE ?" } +
                listOf("title GLOB '*[^ -~]*'", "mime GLOB '*[^ -~]*'")
            terms.joinToString(" OR ", "(", ")") to (mime + names).toTypedArray()
        }
        const val MAX_ENTRIES = 1_000_000
        const val MAX_COPIES = 10_000
        const val MAX_COPY_BYTES = 8L * 1024 * 1024 * 1024
    }
}
