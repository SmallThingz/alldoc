package app.smallthingz.alldoc.sources

import android.content.Context
import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.database.MatrixCursor
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.os.Process
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.smallthingz.alldoc.engine.DocumentEngine
import app.smallthingz.alldoc.engine.StreamingInputFixture
import app.smallthingz.alldoc.state.CopySettings
import app.smallthingz.alldoc.theme.ReadingMode
import app.smallthingz.alldoc.theme.ThemeSettings
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DocumentSourcesTest {
    private class TestContext(base: Context, val root: File, private val testResolver: ContentResolver? = null) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getContentResolver(): ContentResolver = testResolver ?: super.getContentResolver()
        override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
        override fun getDatabasePath(name: String): File = File(root, name)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
            SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, handler: DatabaseErrorHandler?): SQLiteDatabase =
            SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory, handler)
    }
    private fun fixture(work: (TestContext, DocumentSources) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(base.cacheDir, "source-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = TestContext(base, folder)
        val sources = DocumentSources(context)
        try { work(context, sources) }
        finally {
            runCatching { sources.clear() }
            sources.close(); folder.deleteRecursively()
        }
    }
    private fun open(sources: DocumentSources, body: String, title: String = "Shared.md", settings: CopySettings? = CopySettings(),
        signal: CancellationSignal = CancellationSignal()) = TransientDocument(body, title).use { sources.open(it.uri, settings, signal) }
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).hex()
    private fun writeBluePdf(file: File) {
        val document = android.graphics.pdf.PdfDocument()
        try {
            val page = document.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(8, 4, 1).create())
            page.canvas.drawColor(android.graphics.Color.BLUE)
            document.finishPage(page)
            file.outputStream().use(document::writeTo)
        } finally { document.close() }
    }

    @Test fun fullIdentityDeduplicatesAndRenamesEvenFromRecoverableFile() = fixture { context, sources ->
        val body = "Shared source café\nComplete bytes"
        val first = open(sources, body, "First.md").use {
            assertEquals(hash(body), it.hash); assertNotNull(it.importUri)
            assertTrue("A completed retained snapshot carries its actual hash proof", it.importVerified)
            it.importUri!!
        }
        open(sources, body, "First.md", null, CancellationSignal()).use {
            assertEquals(first, it.importUri)
            assertFalse("A cached read-only lookup does not verify the retained bytes", it.importVerified)
            assertEquals(body.replace("\n", " "), it.session.text(CancellationSignal()))
        }
        open(sources, body, "Second.md").use {
            assertEquals(hash(body), it.hash); assertNotNull(it.importUri)
            assertTrue("A successfully verified reused copy carries the current hash proof", it.importVerified)
        }
        assertEquals(1, sources.status(CopySettings()).count)
        val external = File(context.root, "Third.md").apply { writeText(body) }
        var latest: Uri? = null
        sources.open(Uri.fromFile(external), CopySettings(), CancellationSignal()).use {
            assertEquals(hash(body), it.hash); assertEquals(Uri.fromFile(external), it.sourceUri)
            latest = it.importUri; assertNotNull(latest)
            assertEquals(body.replace("\n", " "), it.session.text(CancellationSignal()))
        }
        assertEquals(1, sources.status(CopySettings()).count)
        sources.open(requireNotNull(latest), CopySettings(), CancellationSignal()).use {
            assertEquals("Third.md", it.displayName)
            assertEquals(hash(body), it.hash)
            assertNull("An import is not an original source", it.sourceUri)
        }
        assertEquals(1, sources.status(CopySettings()).count)
        assertNotNull(first)
    }

    @Test fun journaledUrisRemainManagedBeforeNullPolicyOrRecovery() {
        for (pending in 1..4) for (role in listOf("recorded", "temporary", "final")) fixture { context, sources ->
            sources.status(CopySettings())
            val body = "Pending $pending $role " + UUID.randomUUID()
            val file = pendingFile(context, body, pending, role)
            val uri = Uri.fromFile(file)
            sources.open(uri, null, CancellationSignal()).use {
                assertEquals(body.replace("\n", " "), it.session.text(CancellationSignal()))
                assertNull("Pending $pending $role is owned even when repair is disabled", it.sourceUri)
                assertNull(it.importUri)
                assertEquals("Pending format metadata is not authoritative", file.name, it.displayName)
                assertNull(it.mimeType)
            }
            assertTrue("Null policy must not recover or delete the file", file.exists())
            SQLiteDatabase.openDatabase(context.getDatabasePath("imports.sqlite").path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("SELECT pending FROM copies WHERE hash=?", arrayOf(hash(body))).use {
                    assertTrue(it.moveToFirst()); assertEquals(pending, it.getInt(0))
                }
            }
            val original = File(context.root, file.name).apply { writeText(body) }
            sources.open(Uri.fromFile(original), null, CancellationSignal()).use {
                assertEquals("Equal bytes and a copied generation name cannot establish ownership outside its target",
                    Uri.fromFile(original), it.sourceUri)
            }
            // Incomplete creation and changed pending files must not borrow the journal's intended digest.
            val changed = "Partial or changed " + body
            file.writeText(changed)
            sources.open(uri, null, CancellationSignal()).use {
                assertNotEquals(hash(body), it.hash)
                assertEquals(changed, it.session.text(CancellationSignal()))
                assertNull("Ownership is independent of payload identity: $pending $role", it.sourceUri)
                assertNull(it.importUri)
            }
            assertTrue(file.exists())
            file.writeText(body)
            sources.open(uri, CopySettings(), CancellationSignal()).use {
                assertEquals(body.replace("\n", " "), it.session.text(CancellationSignal()))
                assertNull("Recovery cannot publish its deleted/renamed input as an original", it.sourceUri)
                if (pending == 3) {
                    assertNull("Recovery must not mistake its renamed input for a storage failure", it.retentionWarning)
                    assertNotNull("Recovered rename $role must return its retained URI", it.importUri)
                    assertTrue("Recovered rename $role must carry verified retained bytes", it.importVerified)
                    assertEquals(body, File(requireNotNull(it.importUri?.path)).readText())
                    assertEquals(it.importUri, sources.importUri(hash(body)))
                } else assertNull(it.importUri)
            }
            assertTrue(original.exists())
            assertEquals(if (pending == 3) 1 else 0, sources.status(CopySettings()).count)
            if (pending != 3) assertFalse(file.exists())
        }
    }

    @Test fun alternatePendingUriIsManagedEvenWhenRecoverySkipsItsRow() = fixture { context, sources ->
        sources.status(CopySettings())
        val body = "Bounded recovery target " + UUID.randomUUID()
        val file = pendingFile(context, body, 2, "final", touched = 1000L)
        SQLiteDatabase.openDatabase(context.getDatabasePath("imports.sqlite").path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            repeat(65) { index ->
                db.execSQL("INSERT INTO copies(hash,name,mime,bytes,uri,target,token,pending,owner,touched) VALUES(?,?,?,?,?,?,?,?,?,?)", arrayOf<Any?>(hash("Earlier $index"),
                    "Earlier.md", "text/markdown", 0L, null, "private", UUID.randomUUID().toString(), 1, Process.myPid(), index.toLong()))
            }
        }
        sources.open(Uri.fromFile(file), CopySettings(), CancellationSignal()).use {
            assertEquals(body.replace("\n", " "), it.session.text(CancellationSignal()))
            assertNull("An unprocessed generated location is still managed", it.sourceUri)
            assertNull(it.importUri)
        }
        assertTrue("The bounded recovery pass must not reach the later target", file.exists())
        SQLiteDatabase.openDatabase(context.getDatabasePath("imports.sqlite").path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT pending FROM copies WHERE hash=?", arrayOf(hash(body))).use {
                assertTrue(it.moveToFirst()); assertEquals(2, it.getInt(0))
            }
        }
    }

    @Test fun journalLocationsHandleProviderTransitionsAliasesAndOpaqueNames() {
        org.junit.Assume.assumeTrue(android.os.Build.VERSION.SDK_INT >= 29)
        for (transition in listOf("publish", "delete", "opaque", "opaquePublished", "alias")) {
            val base = InstrumentationRegistry.getInstrumentation().targetContext
            val folder = File(base.cacheDir, "source-transition-" + UUID.randomUUID()).apply { mkdirs() }
            val token = UUID.randomUUID().toString()
            val tree = android.provider.DocumentsContract.buildTreeDocumentUri("journal-fixture", "folder")
            val uri = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, "actual")
            val alias = android.provider.DocumentsContract.buildDocumentUri("journal-fixture", "actual")
            val body = "Saved complete body"
            val input = File(folder, "payload.md").apply { writeText(body) }
            val display = "Saved-" + token + ".md"
            var initialized = false
            var childQueries = 0
            var reads = 0
            lateinit var context: TestContext
            fun update(sql: String, args: Array<Any?> = emptyArray()) {
                SQLiteDatabase.openDatabase(context.getDatabasePath("imports.sqlite").path, null, SQLiteDatabase.OPEN_READWRITE).use {
                    it.execSQL(sql, args)
                }
            }
            val provider = object : ContentProvider() {
                override fun onCreate() = true
                override fun getType(uri: Uri) = "text/markdown"
                override fun insert(uri: Uri, values: ContentValues?): Uri? = null
                override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
                override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
                override fun query(request: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?) =
                    MatrixCursor(projection ?: arrayOf("_display_name")).apply {
                        val children = request.lastPathSegment == "children"
                        if (children) childQueries++
                        if (!children && initialized && transition == "publish") {
                            update("UPDATE copies SET uri=?,location=?,pending=0", arrayOf<Any?>(uri.toString(), alias.toString()))
                        }
                        if (!transition.startsWith("opaque") || children) addRow(columnNames.map { column -> when (column) {
                            "document_id" -> "actual"
                            "_display_name" -> if (transition == "alias") "Renamed by another app.md" else display
                            else -> null
                        } }.toTypedArray())
                    }
                override fun openFile(request: Uri, mode: String): ParcelFileDescriptor {
                    check(request == uri || request == alias)
                    reads++
                    val fd = ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY)
                    if (transition == "delete") {
                        assertTrue("Ownership must be checked before the input is opened", childQueries > 0)
                        update("DELETE FROM copies")
                        assertTrue(input.delete())
                    }
                    return fd
                }
                @Suppress("DEPRECATION")
                override fun call(method: String, arg: String?, extras: android.os.Bundle?): android.os.Bundle {
                    check(method == "android:renameDocument")
                    return android.os.Bundle().apply { putParcelable("uri", uri) }
                }
            }
            context = TestContext(base, folder, ContentResolver.wrap(provider))
            val sources = DocumentSources(context)
            val copies = ImportCopies(context)
            try {
                sources.status(CopySettings())
                update("INSERT INTO copies(hash,name,mime,bytes,uri,target,token,pending,owner,touched) VALUES(?,?,?,?,?,?,?,?,?,?)", arrayOf<Any?>(hash(body), "Saved.md", "text/markdown",
                    input.length(), if (transition == "alias" || transition == "opaquePublished") uri.toString() else null, tree.toString(), token,
                    if (transition == "alias" || transition == "opaquePublished") 0 else 1, Process.myPid(), 1L))
                if (transition == "alias" || transition == "opaquePublished")
                    update("UPDATE copies SET location=?", arrayOf<Any?>(alias.toString()))
                initialized = true
                val signal = CancellationSignal()
                if (transition.startsWith("opaque")) {
                    val incoming = if (transition == "opaquePublished") alias else uri
                    val text = if (transition == "opaquePublished") "Changed bytes with unavailable name" else body
                    input.writeText(text)
                    if (transition == "opaquePublished") assertEquals(true, copies.ownership(incoming, null, null, signal))
                    else assertNull(copies.ownership(incoming, null, null, signal))
                    assertEquals("An unrelated provider is not made uncertain by this journal", false,
                        copies.ownership(Uri.parse("content://different-provider/opaque"), null, null, signal))
                    sources.open(incoming, null, signal).use {
                        if (transition == "opaquePublished") assertNull(it.retentionWarning)
                        else assertEquals("This file may be unavailable later.", it.retentionWarning)
                        assertEquals(text, it.session.text(signal))
                        assertNull(it.sourceUri)
                    }
                    if (transition == "opaquePublished") {
                        assertEquals("Unknown ownership does not discard an existing recoverable reference", uri, sources.importUri(hash(body)))
                    }
                    assertEquals("Opaque metadata must not enumerate provider folders", 0, childQueries)
                } else {
                    assertEquals(true, copies.ownership(if (transition == "alias") alias else uri, null,
                        if (transition == "alias") "Renamed by another app.md" else display, signal))
                    if (transition == "alias") {
                        input.writeText("Changed retained body")
                        sources.open(alias, CopySettings(enabled = false), signal).use {
                            assertEquals(hash("Changed retained body"), it.hash)
                            assertNull(it.importUri)
                            assertNull(it.sourceUri)
                            assertEquals("Changed retained body", it.session.text(signal))
                        }
                        assertNull(sources.importUri(hash(body)))
                        assertEquals(0, sources.status(CopySettings()).count)
                        assertTrue(sources.hasPendingEviction(hash(body), uri.toString()))
                        assertEquals("A SAF replacement uses the admitted digest without another read", 1, reads)
                    } else {
                        childQueries = 0
                        sources.open(uri, null, signal).use {
                            assertEquals(body, it.session.text(signal))
                            assertEquals(if (transition == "publish") uri else null, it.importUri)
                        }
                        if (transition == "publish") assertEquals(true, copies.ownership(alias, hash(body), display, signal))
                        else assertEquals(false, copies.ownership(uri, hash(body), display, signal))
                        assertEquals("Admission performs one payload read", 1, reads)
                    }
                }
            } finally { copies.close(); sources.close(); folder.deleteRecursively() }
        }
    }

    private fun pendingFile(context: TestContext, body: String, pending: Int, role: String, touched: Long = 1L): File {
        val token = UUID.randomUUID().toString()
        val folder = File(context.filesDir, "imports").apply { mkdirs() }
        val recorded = File(folder, "Recorded-$token.md")
        val file = when (role) {
            "recorded" -> recorded
            "temporary" -> File(folder, ".alldoc-$token-Saved.md")
            else -> File(folder, "Saved-$token.md")
        }.apply { writeText(body) }
        val recordedUri = if (pending == 1 && role != "recorded") null else Uri.fromFile(recorded).toString()
        SQLiteDatabase.openDatabase(context.getDatabasePath("imports.sqlite").path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("INSERT INTO copies(hash,name,mime,bytes,uri,target,token,pending,owner,touched) VALUES(?,?,?,?,?,?,?,?,?,?)", arrayOf<Any?>(hash(body), "Saved.md", "text/markdown",
                file.length(), recordedUri, "private", token, pending, Process.myPid(), touched))
            if (pending == 2 || pending == 4) db.execSQL("INSERT INTO evictions(hash,uri) VALUES(?,?)", arrayOf(hash(body), recordedUri))
        }
        return file
    }

    @Test fun changedTailUsesDifferentHashAndFileSourcesAreNotRetained() = fixture { context, sources ->
        val file = File(context.root, "same.md")
        val prefix = "z".repeat(65536)
        file.writeText(prefix + "first")
        val first = sources.open(Uri.fromFile(file), CopySettings(), CancellationSignal()).use { it.hash }
        file.writeText(prefix + "second")
        sources.open(Uri.fromFile(file), CopySettings(), CancellationSignal()).use {
            assertNotEquals(first, it.hash); assertEquals(hash(prefix + "second"), it.hash); assertNull(it.importUri)
        }
        assertEquals(0, sources.status(CopySettings()).count)
    }

    @Test fun quotasAndGenerationReceiptsSurviveReimportAndDelayedAcknowledgment() = fixture { _, sources ->
        val budget = CopySettings(maxCount = 1, maxBytes = 1024)
        val first = open(sources, "first", settings = budget).use { it.hash to it.importUri.toString() }
        open(sources, "second", settings = budget).close()
        val old = sources.pendingEvictions().single { it.hash == first.first }
        assertEquals(first.second, old.importUri)
        val replacement = open(sources, "first", settings = budget).use { it.importUri.toString() }
        assertNotEquals(first.second, replacement)
        open(sources, "third", settings = budget).close()
        sources.acknowledgeEvictions(listOf(old.id))
        assertTrue(sources.pendingEvictions().any { it.hash == first.first && it.importUri == replacement && it.id != old.id })
        val before = sources.status(budget)
        assertEquals(1, before.count); assertTrue(before.bytes <= 1024)
        val reduced = sources.status(budget.copy(maxBytes = 1))
        assertEquals(0, reduced.count); assertEquals(0L, reduced.bytes)
    }

    @Test fun evictionLookupMigrationPreservesCopiesAndExactReceiptIdentity() {
        for (version in 1..2) fixture { context, sources ->
        val token = UUID.randomUUID().toString()
        val retained = File(context.filesDir, "imports/Saved-$token.md").apply { parentFile!!.mkdirs(); writeText("saved") }
        val identity = hash("saved")
        val currentUri = Uri.fromFile(retained)
        val oldUri = Uri.fromFile(File(retained.parentFile, "Old-$token.md")).toString()
        val tree = android.provider.DocumentsContract.buildTreeDocumentUri("migration-fixture", "folder/root")
        val documentId = "folder/Latin café?line#1"
        val legacyUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, documentId)
            .buildUpon().appendQueryParameter("view", "text").fragment("preview").build()
        val canonical = android.provider.DocumentsContract.buildDocumentUri("migration-fixture", documentId)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("imports.sqlite"), null).use { db ->
            db.execSQL("CREATE TABLE copies(hash TEXT PRIMARY KEY,name TEXT NOT NULL,mime TEXT NOT NULL,bytes INTEGER NOT NULL,uri TEXT,target TEXT NOT NULL,token TEXT NOT NULL,pending INTEGER NOT NULL,owner INTEGER NOT NULL,touched INTEGER NOT NULL)")
            db.execSQL("CREATE UNIQUE INDEX copies_uri ON copies(uri)")
            db.execSQL("CREATE INDEX copies_lru ON copies(touched)")
            db.execSQL("CREATE INDEX copies_pending ON copies(pending)")
            db.execSQL("CREATE TABLE evictions(id INTEGER PRIMARY KEY AUTOINCREMENT,hash TEXT NOT NULL,uri TEXT NOT NULL)")
            db.execSQL("INSERT INTO copies(hash,name,mime,bytes,uri,target,token,pending,owner,touched) VALUES(?,?,?,?,?,?,?,?,?,?)", arrayOf<Any?>(identity, "Saved.md", "text/markdown", retained.length(),
                currentUri.toString(), "private", token, 0, Process.myPid(), 1L))
            db.execSQL("INSERT INTO copies(hash,name,mime,bytes,uri,target,token,pending,owner,touched) VALUES(?,?,?,?,?,?,?,?,?,?)",
                arrayOf<Any?>(hash("legacy alias"), "Alias.md", "text/markdown", 0L, legacyUri.toString(),
                    tree.toString(), UUID.randomUUID().toString(), 0, 1, 0L))
            db.execSQL("INSERT INTO evictions VALUES(?,?,?)", arrayOf<Any?>(42L, identity, oldUri))
            if (version == 2) db.execSQL("CREATE INDEX evictions_reference ON evictions(hash,uri)")
            db.version = version
        }
        assertTrue(sources.hasPendingEviction(identity, oldUri))
        assertFalse(sources.hasPendingEviction(hash("different"), oldUri))
        assertFalse(sources.hasPendingEviction(identity, currentUri.toString()))
        assertEquals(currentUri, sources.importUri(identity))
        assertEquals("saved", retained.readText())
        val receipt = sources.pendingEvictions().single()
        assertEquals(42L, receipt.id); assertEquals(identity, receipt.hash); assertEquals(oldUri, receipt.importUri)
        SQLiteDatabase.openDatabase(context.getDatabasePath("imports.sqlite").path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            assertEquals(3, db.version)
            db.rawQuery("SELECT location FROM copies WHERE hash=?", arrayOf(hash("legacy alias"))).use {
                assertTrue(it.moveToFirst()); assertEquals(canonical.toString(), it.getString(0))
            }
            db.rawQuery("EXPLAIN QUERY PLAN SELECT 1 FROM copies WHERE location=?", arrayOf(canonical.toString())).use {
                assertTrue(it.moveToFirst()); assertTrue(it.getString(3).contains("copies_location"))
            }
            db.rawQuery("EXPLAIN QUERY PLAN SELECT 1 FROM copies WHERE token=?", arrayOf(token)).use {
                assertTrue(it.moveToFirst()); assertTrue(it.getString(3).contains("copies_token"))
            }
            db.rawQuery("EXPLAIN QUERY PLAN SELECT 1 FROM copies WHERE pending IN (1,2,3,4) AND target=? LIMIT 1", arrayOf("private")).use {
                assertTrue(it.moveToFirst()); assertTrue(it.getString(3).contains("copies_pending_target"))
            }
            db.rawQuery("EXPLAIN QUERY PLAN SELECT 1 FROM evictions WHERE hash=? AND uri=? LIMIT 1", arrayOf(identity, oldUri)).use {
                assertTrue(it.moveToFirst()); assertTrue(it.getString(3).contains("evictions_reference"))
            }
            db.execSQL("INSERT INTO evictions(hash,uri) VALUES(?,?)", arrayOf(identity, currentUri.toString()))
        }
        val canonicalCopies = ImportCopies(context)
        try {
            assertEquals(false, canonicalCopies.ownership(canonical, hash("edited bytes"), "Renamed.md", CancellationSignal()))
            assertEquals("Alias.md", canonicalCopies.metadata(canonical)?.name)
        } finally { canonicalCopies.close() }
        val replacement = sources.pendingEvictions().last()
        assertTrue(replacement.id > receipt.id)
        sources.acknowledgeEvictions(listOf(receipt.id))
        assertFalse(sources.hasPendingEviction(identity, oldUri))
        assertTrue(sources.hasPendingEviction(identity, currentUri.toString()))
        }
    }

    @Test fun offPreservesExistingCopiesAndDoesNotRetainNewInputs() = fixture { _, sources ->
        val enabled = CopySettings(maxBytes = 1024, maxCount = 1_000_000)
        val bodies = listOf("Retained first", "Retained second")
        val retained = bodies.map { body -> open(sources, body, settings = enabled).use { requireNotNull(it.importUri) } }
        val off = CopySettings(enabled = false, maxBytes = 0, maxCount = 1)
        val before = sources.status(off)
        assertEquals(2, before.count)
        assertTrue(before.bytes > 0)
        assertTrue(before.evictedHashes.isEmpty())
        open(sources, "New input while retention is off", settings = off).use {
            assertNull(it.importUri)
            assertEquals("New input while retention is off", it.session.text(CancellationSignal()))
        }
        for ((index, body) in bodies.withIndex()) {
            assertEquals(retained[index], sources.importUri(hash(body)))
            open(sources, body, settings = off).use {
                assertEquals(retained[index], it.importUri)
                assertTrue(it.importVerified)
            }
            sources.open(retained[index], off, CancellationSignal()).use {
                assertEquals(body, it.session.text(CancellationSignal()))
            }
        }
        assertEquals(before.bytes, sources.status(off).bytes)
        assertEquals(2, sources.status(off).count)
        assertTrue(sources.pendingEvictions().isEmpty())
    }

    @Test fun unlimitedRetainsFiniteInputsAndAChangedFiniteCapStillTrims() = fixture { _, sources ->
        val unlimited = CopySettings(maxBytes = Long.MAX_VALUE, maxCount = 1_000_000)
        val bodies = listOf("Unlimited first", "Unlimited second")
        val retained = bodies.map { body -> open(sources, body, settings = unlimited).use {
            assertTrue(it.importVerified)
            requireNotNull(it.importUri)
        } }
        val status = sources.status(unlimited)
        assertEquals(2, status.count)
        assertEquals(bodies.sumOf { it.toByteArray().size.toLong() }, status.bytes)
        assertNull(status.warning)
        bodies.forEachIndexed { index, body -> assertEquals(retained[index], sources.importUri(hash(body))) }
        assertTrue(sources.pendingEvictions().isEmpty())
        val trimmed = sources.status(unlimited.copy(maxBytes = 1))
        assertEquals(0, trimmed.count)
        assertEquals(0L, trimmed.bytes)
        assertEquals(bodies.map(::hash).toSet(), trimmed.evictedHashes.toSet())
    }

    private fun seedMissingCopies(context: TestContext, count: Int, bytes: Long): File {
        val directory = File(context.filesDir, "imports").apply { mkdirs() }
        SQLiteDatabase.openDatabase(context.getDatabasePath("imports.sqlite").path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.beginTransaction()
            try {
                db.compileStatement("INSERT INTO copies(hash,name,mime,bytes,uri,target,token,pending,owner,touched) VALUES(?,?,?,?,?,?,?,?,?,?)").use { insert ->
                    for (index in 1..count) {
                        insert.clearBindings()
                        insert.bindString(1, index.toString(16).padStart(64, '0'))
                        insert.bindString(2, "Saved.md"); insert.bindString(3, "text/markdown"); insert.bindLong(4, bytes)
                        insert.bindString(5, Uri.fromFile(File(directory, "missing-$index.md")).toString())
                        insert.bindString(6, "private"); insert.bindString(7, UUID.randomUUID().toString())
                        insert.bindLong(8, 0); insert.bindLong(9, Process.myPid().toLong()); insert.bindLong(10, index.toLong())
                        insert.executeInsert()
                    }
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
        return directory
    }

    @Test fun clearBatchesTraverseBeyondTheReceiptLimitAndPreserveReadingPositions() = fixture { context, sources ->
        val unlimited = CopySettings(maxBytes = Long.MAX_VALUE, maxCount = 1_000_000)
        sources.status(unlimited)
        val count = 20_001
        val directory = seedMissingCopies(context, count, 0)
        assertEquals(count, sources.status(unlimited).count)
        app.smallthingz.alldoc.state.DocumentStore(context, "clear-state.db").use { store ->
            val identities = listOf(1, count).map { it.toString(16).padStart(64, '0') }
            for ((position, index) in listOf(1, count).withIndex()) {
                store.open(identities[position], app.smallthingz.alldoc.state.DocumentMetadata("Saved.md", "text/markdown", 0,
                    importUri = Uri.fromFile(File(directory, "missing-$index.md")).toString()))
                store.saveState(identities[position], app.smallthingz.alldoc.state.DocumentState(page = 7))
            }
            var after: String? = null
            var removed = 0
            do {
                val cleared = sources.clear(after)
                assertNull(cleared.warning)
                assertTrue(cleared.evictedHashes.size <= 256)
                removed += cleared.evictedHashes.size
                val receipts = sources.pendingEvictions()
                assertTrue("Every batch keeps the durable receipt journal bounded", receipts.size <= 256)
                store.clearMatchingImports(receipts.map { it.hash to it.importUri })
                sources.acknowledgeEvictions(receipts.map { it.id })
                cleared.continuation?.let { assertTrue(it > after.orEmpty()) }
                after = cleared.continuation
            } while (after != null)
            assertEquals(count, removed)
            for (identity in identities) {
                assertNull(store.get(identity)!!.metadata.importUri)
                assertEquals(7, store.get(identity)!!.state.page)
            }
        }
        assertEquals(0, sources.status(unlimited).count)
        assertTrue(sources.pendingEvictions().isEmpty())
    }

    @Test fun finiteTrimAndImportRoomMakingStayBoundedBeyondTheReceiptLimit() = fixture { context, sources ->
        val unlimited = CopySettings(maxBytes = Long.MAX_VALUE, maxCount = 1_000_000)
        sources.status(unlimited)
        val count = 20_001
        seedMissingCopies(context, count, 1024)
        val finite = unlimited.copy(maxBytes = 1024)
        val body = "A new temporary document"
        open(sources, body, settings = finite).use {
            assertNull("An unfinished capacity batch must not claim a new retained copy", it.importUri)
            assertFalse(it.importVerified)
            assertTrue(it.retentionWarning.orEmpty().contains("cleanup is pending"))
            assertEquals(body, it.session.text(CancellationSignal()))
            assertEquals(256, it.evictedHashes.size)
        }
        var removed = 0
        fun acknowledgeBatch(expected: Int) {
            val receipts = sources.pendingEvictions()
            assertEquals(expected, receipts.size)
            assertTrue("No pass may fill the 20,000-receipt journal", receipts.size <= 256)
            removed += receipts.size
            sources.acknowledgeEvictions(receipts.map { it.id })
        }
        acknowledgeBatch(256)
        var batches = 0
        do {
            val status = sources.status(finite)
            assertNull(status.warning)
            assertTrue(status.evictedHashes.size <= 256)
            acknowledgeBatch(status.evictedHashes.size)
            assertEquals(count - removed, status.count)
            assertEquals(status.count * 1024L, status.bytes)
            assertEquals(status.bytes > finite.maxBytes, status.trimPending)
            assertTrue("The continuation must make bounded forward progress", ++batches <= 80)
        } while (status.trimPending)
        assertEquals(count - 1, removed)
        assertTrue(sources.pendingEvictions().isEmpty())
        open(sources, body, settings = finite).use {
            assertNotNull(it.importUri)
            assertTrue(it.importVerified)
            assertNull(it.retentionWarning)
        }
        val complete = sources.status(finite)
        assertEquals(1, complete.count)
        assertEquals(body.toByteArray().size.toLong(), complete.bytes)
        assertFalse(complete.trimPending)
    }

    @Test fun missingPrivateTrimRollsBackReceiptsAndRowsTogetherBeforeRetry() = fixture { context, sources ->
        val unlimited = CopySettings(maxBytes = Long.MAX_VALUE, maxCount = 1_000_000)
        sources.status(unlimited)
        val count = 64
        seedMissingCopies(context, count, 1024)
        val second = 2.toString(16).padStart(64, '0')
        SQLiteDatabase.openDatabase(context.getDatabasePath("imports.sqlite").path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("CREATE TRIGGER fail_missing_delete BEFORE DELETE ON copies WHEN OLD.hash='$second' BEGIN SELECT RAISE(ABORT,'injected deletion failure'); END")
        }
        val finite = unlimited.copy(maxBytes = 1)
        val failed = sources.status(finite)
        assertNotNull(failed.warning)
        assertEquals("A failed atomic batch must retain every indexed copy", count, failed.count)
        assertEquals(count * 1024L, failed.bytes)
        assertTrue(failed.evictedHashes.isEmpty())
        assertTrue("Rolled-back removals must not leave durable eviction receipts", sources.pendingEvictions().isEmpty())
        SQLiteDatabase.openDatabase(context.getDatabasePath("imports.sqlite").path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("DROP TRIGGER fail_missing_delete")
        }
        val retried = sources.status(finite)
        assertNull(retried.warning)
        assertEquals(0, retried.count)
        assertEquals(0L, retried.bytes)
        assertEquals(count, retried.evictedHashes.size)
        assertFalse(retried.trimPending)
        val receipts = sources.pendingEvictions()
        assertEquals(count, receipts.size)
        assertEquals(retried.evictedHashes.toSet(), receipts.map { it.hash }.toSet())
    }

    @Test fun usageObservationNeitherTrimsNorReplaysPendingDeletion() = fixture { context, sources ->
        val body = "A retained copy remains available"
        val retained = open(sources, body).use { requireNotNull(it.importUri) }
        val pending = pendingFile(context, "Pending deletion still owns its bytes", 2, "recorded")
        val receipts = sources.pendingEvictions()
        assertEquals(1, receipts.size)
        val observed = sources.observeStatus(CopySettings(maxBytes = 1))
        assertEquals(2, observed.count)
        assertEquals(body.toByteArray().size + pending.length(), observed.bytes)
        assertTrue(observed.evictedHashes.isEmpty())
        assertFalse(observed.trimPending)
        assertTrue(pending.isFile)
        assertEquals(retained, sources.importUri(hash(body)))
        assertEquals(receipts, sources.pendingEvictions())
    }

    @Test fun disabledCopyAndCancellationLeaveNoRetainedPayload() = fixture { _, sources ->
        open(sources, "No retained copy", settings = CopySettings(enabled = false)).use { assertNull(it.importUri) }
        val cancelled = CancellationSignal().apply { cancel() }
        try {
            open(sources, "Cancelled content", "Cancel.md", CopySettings(), cancelled).close()
            fail("Cancelled admission succeeded")
        } catch (_: OperationCanceledException) { }
        assertEquals(0, sources.status(CopySettings()).count)
    }

    @Test fun privateInterruptedCreateAndRenameRecoverExactGeneration() = fixture { context, sources ->
        sources.status(CopySettings())
        val directory = File(context.filesDir, "imports").apply { mkdirs() }
        val incompleteToken = UUID.randomUUID().toString()
        val incomplete = File(directory, ".alldoc-$incompleteToken-Incomplete.md").apply { writeText("partial") }
        val renameToken = UUID.randomUUID().toString()
        val old = File(directory, "Old-$renameToken.md")
        val renamed = File(directory, "New-$renameToken.md").apply { writeText("renamed") }
        val renamedHash = hash("renamed")
        SQLiteDatabase.openDatabase(context.getDatabasePath("imports.sqlite").path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            fun insert(h: String, title: String, bytes: Long, uri: String, token: String, pending: Int) = db.execSQL(
                "INSERT INTO copies(hash,name,mime,bytes,uri,target,token,pending,owner,touched) VALUES(?,?,?,?,?,?,?,?,?,?)", arrayOf<Any?>(h, title, "text/markdown", bytes, uri, "private", token, pending, 1, 1L))
            insert(hash("partial"), "Incomplete.md", incomplete.length(), Uri.fromFile(incomplete).toString(), incompleteToken, 1)
            insert(renamedHash, "New.md", renamed.length(), Uri.fromFile(old).toString(), renameToken, 3)
        }
        // Cold resume runs before background status recovery; unknown policy must not mutate the journal.
        assertNull(sources.importUri(renamedHash, null, CancellationSignal()))
        val cancelled = CancellationSignal().apply { cancel() }
        try {
            sources.importUri(renamedHash, CopySettings(), cancelled)
            fail("Cancelled recovery succeeded")
        } catch (_: OperationCanceledException) { }
        assertNull(sources.importUri(renamedHash))
        assertEquals(Uri.fromFile(renamed), sources.importUri(renamedHash, CopySettings(), CancellationSignal()))
        assertTrue("Targeted recovery must leave unrelated pending copies for their own cleanup", incomplete.exists())
        val status = sources.status(CopySettings())
        assertFalse(incomplete.exists()); assertTrue(renamed.exists()); assertEquals(1, status.count)
        assertEquals(Uri.fromFile(renamed), sources.importUri(renamedHash))
        assertTrue(sources.pendingEvictions().any { it.hash == renamedHash && it.importUri == Uri.fromFile(old).toString() })
    }

    @Test fun privateRenamingDoesNotOverwriteAnotherGeneration() = fixture { context, sources ->
        sources.status(CopySettings())
        val directory = File(context.filesDir, "imports").apply { mkdirs() }
        val token = UUID.randomUUID().toString()
        val original = File(directory, "Old-$token.md").apply { writeText("original") }
        val collision = File(directory, "New-$token.md").apply { writeText("preserved collision") }
        val originalHash = hash("original")
        SQLiteDatabase.openDatabase(context.getDatabasePath("imports.sqlite").path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("INSERT INTO copies(hash,name,mime,bytes,uri,target,token,pending,owner,touched) VALUES(?,?,?,?,?,?,?,?,?,?)", arrayOf<Any?>(originalHash, "Old.md", "text/markdown", original.length(),
                Uri.fromFile(original).toString(), "private", token, 0, Process.myPid(), 1L))
        }
        open(sources, "original", "New.md").use { assertNotNull(it.retentionWarning) }
        assertEquals("preserved collision", collision.readText())
        assertEquals("original", original.readText())
        sources.clear()
        assertEquals("preserved collision", collision.readText())
        assertFalse(original.exists())
        assertNull(sources.importUri(originalHash))
        // Repeat the interrupted-deletion state after the durable journal was written but before removal.
        original.writeText("original")
        SQLiteDatabase.openDatabase(context.getDatabasePath("imports.sqlite").path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("INSERT INTO copies(hash,name,mime,bytes,uri,target,token,pending,owner,touched) VALUES(?,?,?,?,?,?,?,?,?,?)", arrayOf<Any?>(originalHash, "New.md", "text/markdown", original.length(),
                Uri.fromFile(original).toString(), "private", token, 4, 1, 1L))
        }
        sources.status(CopySettings())
        assertEquals("preserved collision", collision.readText())
        assertFalse(original.exists()); assertNull(sources.importUri(originalHash))
        collision.delete() // This object belongs to the fixture, never to the retained-copy index.
    }

    @Test fun safCleanupVerifiesEveryCandidateBeforeDeletingAnything() = safCleanupCases()

    @Test fun loadingRecoveryListingKeepsUnknownCreationUntilCompleteLookup() =
        safCleanupCases(android.provider.DocumentsContract.EXTRA_LOADING)

    @Test fun erroredRecoveryListingKeepsUnknownCreationUntilCompleteLookup() =
        safCleanupCases(android.provider.DocumentsContract.EXTRA_ERROR)

    private fun safCleanupCases(incomplete: String? = null) {
        org.junit.Assume.assumeTrue(android.os.Build.VERSION.SDK_INT >= 29)
        val body = "owned bytes"
        val replacement = "replacement bytes"
        data class Case(val pending: Int, val old: String?, val newer: String?, val unreadable: String? = null,
            val notFound: Boolean = false, val deletionFailure: String? = null, val lostUri: Boolean = false, val media: Boolean = false)
        val cases = if (incomplete != null) listOf(
            // Missing rows cannot discard a creation journal; a positive match remains usable.
            Case(1, body, null, lostUri = true), Case(1, null, null, lostUri = true), Case(1, body, null)
        ) else listOf(Case(3, replacement, replacement), Case(4, replacement, body),
            Case(4, body, replacement), Case(4, null, body), Case(4, body, null), Case(4, null, null),
            Case(4, body, body, "old"), Case(4, body, body, "new"),
            Case(2, replacement, null), Case(2, body, null), Case(2, null, null),
            Case(0, replacement, null), Case(0, body, null), Case(0, null, null), Case(0, body, null, "old"),
            Case(1, replacement, null), Case(1, body, null), Case(1, null, null), Case(1, body, null, "old"),
            Case(0, body, null, "old", true), Case(1, body, null, "old", true), Case(2, body, null, "old", true),
            Case(0, body, null, deletionFailure = "present"), Case(0, body, null, deletionFailure = "absent"),
            Case(0, body, null, deletionFailure = "null"), Case(0, body, null, deletionFailure = "error"),
            Case(1, body, null, lostUri = true), Case(1, null, null, lostUri = true),
            Case(1, body, null, lostUri = true, media = true), Case(1, null, null, lostUri = true, media = true))
        for (case in cases) {
            val base = InstrumentationRegistry.getInstrumentation().targetContext
            val folder = File(base.cacheDir, "source-saf-cleanup-${UUID.randomUUID()}").apply { mkdirs() }
            val token = UUID.randomUUID().toString()
            val tree = android.provider.DocumentsContract.buildTreeDocumentUri("alldoc.synthetic", "root")
            val mediaCollection = android.provider.MediaStore.Files.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val original = if (case.media) android.content.ContentUris.withAppendedId(mediaCollection, 1L)
                else android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, "old")
            val contents = mapOf("old" to case.old, "new" to case.newer)
            val files = contents.mapNotNull { (id, text) ->
                text?.let { id to File(folder, "$id.md").apply { writeText(it) } }
            }.toMap().toMutableMap()
            val deleted = mutableSetOf<String>()
            var unreadable = case.unreadable
            var deletionFailure = case.deletionFailure
            var deletionAttempted = false
            var absenceQueries = 0
            var locatorUnavailable = case.lostUri
            fun itemId(uri: Uri) = if (case.media) {
                if (android.content.ContentUris.parseId(uri) == 1L) "old" else "new"
            } else android.provider.DocumentsContract.getDocumentId(uri)
            val provider = object : ContentProvider() {
                override fun onCreate() = true
                override fun getType(uri: Uri) = "text/markdown"
                override fun insert(uri: Uri, values: ContentValues?): Uri? = null
                override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
                override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
                    check(case.media)
                    return files.remove(itemId(uri))?.let { check(it.delete()); deleted += itemId(uri); 1 } ?: 0
                }
                override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): android.database.Cursor? {
                    val collection = if (case.media) uri == mediaCollection else uri.lastPathSegment == "children"
                    if (collection && locatorUnavailable && incomplete == null) return null
                    val partial = collection && incomplete != null && (locatorUnavailable || !case.lostUri)
                    if (!collection && deletionAttempted) {
                        absenceQueries++
                        if (deletionFailure == "null") return null
                        if (deletionFailure == "error") throw java.io.FileNotFoundException("Synthetic item query unavailable")
                    }
                    return MatrixCursor(projection ?: arrayOf("document_id", "_display_name")).apply {
                        val ids = if (partial && case.lostUri) emptyList() else if (collection) files.keys
                            else files.keys.filter { it == itemId(uri) }
                        for (id in ids) addRow(columnNames.map { column -> when (column) {
                            "_id" -> if (id == "old") 1L else 2L
                            "document_id" -> id
                            "_display_name" -> if (case.lostUri) ".alldoc-$token-Old.md"
                                else "${if (id == "old") "Old" else "New"}-$token.md"
                            else -> null
                        } }.toTypedArray<Any?>())
                        if (incomplete != null) setExtras(android.os.Bundle().apply {
                            putBoolean(android.provider.DocumentsContract.EXTRA_LOADING,
                                partial && incomplete == android.provider.DocumentsContract.EXTRA_LOADING)
                            putString(android.provider.DocumentsContract.EXTRA_ERROR,
                                if (partial && incomplete == android.provider.DocumentsContract.EXTRA_ERROR) "Provider unavailable" else "")
                        })
                    }
                }
                override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
                    val id = itemId(uri)
                    if (id == unreadable) {
                        if (case.notFound) throw java.io.FileNotFoundException("Synthetic document temporarily unreadable")
                        throw IOException("Synthetic provider temporarily unavailable")
                    }
                    return ParcelFileDescriptor.open(files[id] ?: throw java.io.FileNotFoundException(id), ParcelFileDescriptor.MODE_READ_ONLY)
                }
                @Suppress("DEPRECATION")
                override fun call(method: String, arg: String?, extras: android.os.Bundle?): android.os.Bundle {
                    check(method == "android:deleteDocument")
                    val uri = requireNotNull(extras?.getParcelable<Uri>("uri"))
                    val id = itemId(uri)
                    if (deletionFailure != null) {
                        deletionAttempted = true
                        if (deletionFailure == "absent") files.remove(id)?.let { check(it.delete()); deleted += id }
                        throw java.io.FileNotFoundException("Synthetic deletion did not report success")
                    }
                    files.remove(id)?.let { check(it.delete()); deleted += id }
                    return android.os.Bundle()
                }
            }
            val context = TestContext(base, folder, ContentResolver.wrap(provider))
            val sources = DocumentSources(context)
            try {
                sources.status(CopySettings())
                SQLiteDatabase.openDatabase(context.getDatabasePath("imports.sqlite").path, null, SQLiteDatabase.OPEN_READWRITE).use {
                    it.execSQL("INSERT INTO copies(hash,name,mime,bytes,uri,target,token,pending,owner,touched) VALUES(?,?,?,?,?,?,?,?,?,?)", arrayOf<Any?>(hash(body),
                        if (case.pending in 0..2) "Old.md" else "New.md", "text/markdown", body.length.toLong(),
                        if (case.lostUri) null else original.toString(), if (case.media) "media" else tree.toString(), token, case.pending, 1, 1L))
                }
                val cleared = sources.clear()
                if (case.deletionFailure != null) {
                    assertEquals("One exact-item query must follow a failed deletion: $case", 1, absenceQueries)
                    if (case.deletionFailure == "absent") assertNull(cleared.warning)
                    else {
                        assertNotNull("An uncertain deletion must be reported: $case", cleared.warning)
                        assertTrue(deleted.isEmpty()); assertEquals(body, files.getValue("old").readText())
                        val usage = sources.status(CopySettings(maxBytes = 1))
                        assertNotNull(usage.warning)
                        assertEquals(1, usage.count); assertEquals(body.length.toLong(), usage.bytes)
                        assertTrue(deleted.isEmpty()); assertEquals(body, files.getValue("old").readText())
                        deletionFailure = null
                        sources.clear()
                    }
                }
                if (case.lostUri) {
                    assertNotNull("Unavailable lookup must preserve interrupted creation: $case", cleared.warning)
                    val usage = sources.status(CopySettings(maxBytes = 1))
                    assertNotNull(usage.warning)
                    assertEquals(1, usage.count); assertEquals(body.length.toLong(), usage.bytes)
                    assertTrue(deleted.isEmpty())
                    assertEquals(case.old, files["old"]?.readText())
                    SQLiteDatabase.openDatabase(context.getDatabasePath("imports.sqlite").path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                        db.rawQuery("SELECT pending,uri FROM copies WHERE hash=?", arrayOf(hash(body))).use {
                            assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)); assertTrue(it.isNull(1))
                        }
                    }
                    locatorUnavailable = false
                    assertNull("Successful lookup must allow retry: $case", sources.clear().warning)
                }
                if (case.pending == 1 && case.old == replacement) {
                    assertNotNull(cleared.warning)
                    assertTrue(deleted.isEmpty())
                    val usage = sources.status(CopySettings())
                    assertEquals(1, usage.count); assertEquals(body.length.toLong(), usage.bytes)
                    sources.open(original, null, CancellationSignal()).use {
                        assertEquals(replacement, it.session.text(CancellationSignal()))
                        assertNull(it.sourceUri); assertNotNull(it.retentionWarning)
                    }
                    assertEquals(replacement, files["old"]?.readText())
                    assertTrue(deleted.isEmpty())
                    files.getValue("old").writeText(body)
                    sources.clear()
                    assertEquals(setOf("old"), deleted)
                    assertEquals(0, sources.status(CopySettings()).count)
                    continue
                }
                if (unreadable != null) {
                    assertNotNull(cleared.warning)
                    assertTrue("Verification must finish before any candidate is deleted: $case", deleted.isEmpty())
                    assertEquals(1, sources.status(CopySettings()).count)
                    assertTrue(deleted.isEmpty())
                    unreadable = null
                    sources.clear()
                }
                assertEquals("Only verified owned content may be removed: $case",
                    contents.filterValues { it == body }.keys, deleted)
                for ((id, text) in contents) if (text == replacement) assertEquals(replacement, files[id]?.readText())
                assertEquals(0, sources.status(CopySettings()).count)
            } finally { sources.close(); folder.deleteRecursively() }
        }
    }

    @Test fun reusedSafLocationRejectsOldFormatAndNeverMutatesReplacement() {
        org.junit.Assume.assumeTrue(android.os.Build.VERSION.SDK_INT >= 29)
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(base.cacheDir, "source-saf-replacement-${UUID.randomUUID()}").apply { mkdirs() }
        val tree = android.provider.DocumentsContract.buildTreeDocumentUri("replacement-fixture", "root")
        val uri = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, "old-path")
        val input = File(folder, "payload")
        var mime: String? = "application/pdf"
        var reads = 0
        var writes = 0
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: Uri) = mime
            override fun insert(uri: Uri, values: ContentValues?): Uri? { writes++; error("Unexpected insert") }
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int { writes++; error("Unexpected delete") }
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int { writes++; error("Unexpected update") }
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?) =
                MatrixCursor(arrayOf("_display_name")).apply { addRow(arrayOf("Replacement")) }
            override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
                assertEquals("r", mode); reads++
                return ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY)
            }
            @Suppress("DEPRECATION")
            override fun call(method: String, arg: String?, extras: android.os.Bundle?): android.os.Bundle {
                writes++; error("Unexpected provider mutation: $method")
            }
        }
        val context = TestContext(base, folder, ContentResolver.wrap(provider))
        val sources = DocumentSources(context)
        val body = "Owned text A"
        val identity = hash(body)
        try {
            sources.status(CopySettings())
            writeBluePdf(input)
            val replacement = input.readBytes()
            for (knownPolicy in listOf(false, true)) for (providerType in listOf("application/pdf", "application/octet-stream")) {
                mime = providerType; reads = 0
                SQLiteDatabase.openDatabase(context.getDatabasePath("imports.sqlite").path, null, SQLiteDatabase.OPEN_READWRITE).use {
                    it.execSQL("DELETE FROM evictions")
                    it.execSQL("INSERT OR REPLACE INTO copies(hash,name,mime,bytes,uri,target,token,pending,owner,touched) VALUES(?,?,?,?,?,?,?,?,?,?)",
                        arrayOf<Any?>(identity, "Old.md", "text/markdown", body.length.toLong(), uri.toString(), tree.toString(), UUID.randomUUID().toString(), 0, 1, 1L))
                }
                sources.open(uri, if (knownPolicy) CopySettings(enabled = false) else null, CancellationSignal(), mimeHint = "application/pdf").use {
                    assertEquals("Replacement", it.displayName); assertEquals("application/pdf", it.mimeType)
                    assertEquals("pdf", it.session.info.format); assertNull(it.importUri)
                    val page = it.session.renderPage(0, 64, ThemeSettings(ReadingMode.ORIGINAL), CancellationSignal())
                    try { assertEquals(64, page.width); assertEquals(32, page.height); assertEquals(android.graphics.Color.BLUE, page.getPixel(0, 0)) }
                    finally { page.recycle() }
                    if (knownPolicy) assertTrue(it.retentionWarning.orEmpty().contains("file was preserved"))
                }
                assertEquals(1, reads); assertEquals(0, writes); assertArrayEquals(replacement, input.readBytes())
                assertEquals(if (knownPolicy) null else uri, sources.importUri(identity))
                assertEquals(knownPolicy, sources.hasPendingEviction(identity, uri.toString()))
            }
            // Unchanged bytes still admit their indexed title/type after the same single read.
            input.writeText(body); reads = 0
            SQLiteDatabase.openDatabase(context.getDatabasePath("imports.sqlite").path, null, SQLiteDatabase.OPEN_READWRITE).use {
                it.execSQL("INSERT INTO copies(hash,name,mime,bytes,uri,target,token,pending,owner,touched) VALUES(?,?,?,?,?,?,?,?,?,?)",
                    arrayOf<Any?>(identity, "Old.md", "text/markdown", body.length.toLong(), uri.toString(), tree.toString(), UUID.randomUUID().toString(), 0, 1, 1L))
            }
            sources.open(uri, null, CancellationSignal()).use {
                assertEquals("Old.md", it.displayName); assertEquals("text/markdown", it.mimeType)
                assertEquals(body.replace("\n", " "), it.session.text(CancellationSignal())); assertEquals(uri, it.importUri)
            }
            assertEquals(1, reads); assertEquals(0, writes)
        } finally { sources.close(); folder.deleteRecursively() }
    }

    @Test fun unicodeRetainedNamesFitFilesystemByteLimit() = fixture { context, sources ->
        sources.status(CopySettings())
        val directory = File(context.filesDir, "imports").apply { mkdirs() }
        val token = UUID.randomUUID().toString()
        val original = File(directory, "Old-$token.md").apply { writeText("unicode name") }
        val identity = hash("unicode name")
        SQLiteDatabase.openDatabase(context.getDatabasePath("imports.sqlite").path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("INSERT INTO copies(hash,name,mime,bytes,uri,target,token,pending,owner,touched) VALUES(?,?,?,?,?,?,?,?,?,?)", arrayOf<Any?>(identity, "Old.md", "text/markdown", original.length(),
                Uri.fromFile(original).toString(), "private", token, 0, Process.myPid(), 1L))
        }
        open(sources, "unicode name", "界".repeat(140) + ".md").use {
            assertNull(it.retentionWarning)
            val retained = File(requireNotNull(it.importUri?.path))
            assertEquals("unicode name", retained.readText())
            assertTrue(retained.name.toByteArray(Charsets.UTF_8).size <= 255)
            assertTrue(retained.name.endsWith(".md")); assertTrue(retained.name.contains(token))
            sources.open(requireNotNull(it.importUri), CopySettings(), CancellationSignal()).use { reopened ->
                assertEquals("界".repeat(140) + ".md", reopened.displayName)
            }
        }
    }

    @Test fun unavailablePolicyDoesNotCreateRenameOrTrimCopies() = fixture { context, sources ->
        val first = open(sources, "existing", "Original.md").use { requireNotNull(it.importUri) }
        // The state database can fail after the user configured a budget above the defaults.
        SQLiteDatabase.openDatabase(context.getDatabasePath("imports.sqlite").path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("UPDATE copies SET bytes=?", arrayOf<Any?>(512L * 1024 * 1024))
        }
        open(sources, "existing", "New name.md", null, CancellationSignal()).use { assertEquals(first, it.importUri) }
        open(sources, "new content", "New.md", null, CancellationSignal()).use { assertNull(it.importUri) }
        sources.open(first, null, CancellationSignal()).use { assertEquals("Original.md", it.displayName) }
        assertTrue(sources.pendingEvictions().isEmpty())
        val status = sources.status(CopySettings(maxBytes = 1024L * 1024 * 1024))
        assertEquals(1, status.count); assertEquals(512L * 1024 * 1024, status.bytes)
    }

    @Test fun unnamedPdfStreamsUsePdfLimitWithoutChangingIdentity() = fixture { context, sources ->
        val file = File(context.cacheDir, "reader-pdfpipe.pdf")
        val uri = Uri.parse("content://app.smallthingz.alldoc.test.reader-fixtures/pdfpipe")
        try {
            StreamingInputFixture.pdf(file)
            val expectedHash = StreamingInputFixture.hash(file)
            sources.open(uri, null, CancellationSignal()).use {
                assertEquals("pdfpipe", it.displayName)
                assertNull(it.mimeType)
                assertEquals(file.length(), it.sizeBytes)
                assertEquals(expectedHash, it.hash)
                assertEquals("pdf", it.session.info.format)
                assertEquals(1, it.session.info.pageCount)
                val page = it.session.renderPage(0, 64, ThemeSettings(ReadingMode.ORIGINAL), CancellationSignal())
                try { assertEquals(64, page.width); assertTrue(page.height > 0) } finally { page.recycle() }
            }
            StreamingInputFixture.pipe(file) { fd ->
                DocumentEngine.open(context, fd, "opaque", null, CancellationSignal()).use {
                    assertEquals("pdf", it.info.format); assertEquals(1, it.info.pageCount)
                    val page = it.renderPage(0, 64, ThemeSettings(ReadingMode.ORIGINAL), CancellationSignal())
                    try { assertEquals(64, page.width); assertTrue(page.height > 0) } finally { page.recycle() }
                }
                assertFalse(fd.fileDescriptor.valid())
            }
            java.io.RandomAccessFile(file, "rw").use { it.writeBytes("PLAIN") }
            try {
                sources.open(uri, null, CancellationSignal()).close()
                fail("Generic UI stream exceeded its 16 MiB cap")
            } catch (failure: IOException) { assertTrue(failure.message.orEmpty().contains("16 MiB")) }
            StreamingInputFixture.pipe(file, complete = false) { fd ->
                try {
                    DocumentEngine.open(context, fd, "opaque", null, CancellationSignal()).close()
                    fail("Generic IPC stream exceeded its 16 MiB cap")
                } catch (failure: IOException) { assertTrue(failure.message.orEmpty().contains("16 MiB")) }
                assertFalse(fd.fileDescriptor.valid())
            }
        } finally { file.delete() }
    }

    @Test fun optionalProviderMetadataFailureFallsBackWithoutSwallowingCancellation() = fixture { context, sources ->
        val uri = Uri.parse("content://app.smallthingz.alldoc.test.reader-fixtures/metadata.md")
        sources.open(uri, null, CancellationSignal()).use {
            assertEquals("metadata.md", it.displayName)
            assertNull(it.mimeType)
            assertTrue(it.session.text(CancellationSignal()).contains("AllDoc fixture metadata.md"))
            assertNull(it.importUri)
        }
        try {
            sources.open(uri.buildUpon().path("metadata-cancel.md").build(), null, CancellationSignal()).close()
            fail("Provider cancellation was swallowed")
        } catch (_: OperationCanceledException) { }
        assertEquals(0, sources.status(CopySettings()).count)
        File(context.cacheDir, "reader-metadata.md.md").delete()
    }

    @Test fun unverifiableRetainedCopySurvivesRecoverableOpenAndStillAllowsExplicitCleanup() {
        org.junit.Assume.assumeTrue(android.os.Build.VERSION.SDK_INT >= 29)
        var deletes = 0
        var cancelOnRead: CancellationSignal? = null
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: Uri) = "text/markdown"
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int { deletes++; return 1 }
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?) =
                MatrixCursor(projection ?: arrayOf("_id")).apply { addRow(arrayOf<Any>(1)) }
            override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
                cancelOnRead?.let { it.cancel(); it.throwIfCanceled() }
                throw IOException("Provider temporarily offline")
            }
        }
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(base.cacheDir, "source-verify-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = TestContext(base, folder, ContentResolver.wrap(provider))
        val sources = DocumentSources(context)
        val retained = Uri.parse("content://owned-copy/1")
        val body = "Same complete bytes"
        val identity = hash(body)
        val incoming = File(folder, "Recovered.md").apply { writeText(body) }
        try {
            sources.status(CopySettings())
            SQLiteDatabase.openDatabase(context.getDatabasePath("imports.sqlite").path, null, SQLiteDatabase.OPEN_READWRITE).use {
                it.execSQL("INSERT INTO copies(hash,name,mime,bytes,uri,target,token,pending,owner,touched) VALUES(?,?,?,?,?,?,?,?,?,?)", arrayOf<Any?>(identity, "Saved.md", "text/markdown", incoming.length(),
                    retained.toString(), "media", UUID.randomUUID().toString(), 0, Process.myPid(), 1L))
            }
            for (enabled in listOf(true, false)) sources.open(Uri.fromFile(incoming), CopySettings(enabled = enabled), CancellationSignal()).use {
                assertEquals(retained, it.importUri)
                assertEquals(Uri.fromFile(incoming), it.sourceUri)
                assertEquals(body.replace("\n", " "), it.session.text(CancellationSignal()))
                assertNotNull(it.retentionWarning)
                assertFalse("An unavailable retained copy is a fallback reference, not verified bytes", it.importVerified)
            }
            val cancelled = CancellationSignal()
            cancelOnRead = cancelled
            try {
                sources.open(Uri.fromFile(incoming), CopySettings(), cancelled).close()
                fail("Verification cancellation was swallowed")
            } catch (_: OperationCanceledException) { }
            cancelOnRead = null
            assertEquals(0, deletes)
            assertEquals(retained, sources.importUri(identity))
            assertTrue(sources.pendingEvictions().isEmpty())
            assertEquals(1, sources.status(CopySettings()).count)
            assertEquals(listOf(identity), sources.clear().evictedHashes)
            assertEquals(1, deletes)
            assertNull(sources.importUri(identity))
        } finally { sources.clear(); sources.close(); folder.deleteRecursively() }
    }

    @Test fun zeroRowMediaDeletePreservesQuotaUntilAbsenceIsVerified() {
        org.junit.Assume.assumeTrue(android.os.Build.VERSION.SDK_INT >= 29)
        var exists = true
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: Uri) = "text/markdown"
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?) =
                MatrixCursor(projection ?: arrayOf("_id")).apply { if (exists) addRow(arrayOf<Any>(1)) }
        }
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(base.cacheDir, "source-delete-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = TestContext(base, folder, ContentResolver.wrap(provider))
        val sources = DocumentSources(context)
        try {
            sources.status(CopySettings())
            SQLiteDatabase.openDatabase(context.getDatabasePath("imports.sqlite").path, null, SQLiteDatabase.OPEN_READWRITE).use {
                it.execSQL("INSERT INTO copies(hash,name,mime,bytes,uri,target,token,pending,owner,touched) VALUES(?,?,?,?,?,?,?,?,?,?)", arrayOf<Any?>(hash("data"), "Data.md", "text/markdown", 4L,
                    "content://owned-copy/1", "media", UUID.randomUUID().toString(), 0, Process.myPid(), 1L))
            }
            val blocked = sources.status(CopySettings(maxBytes = 1))
            assertEquals(1, blocked.count); assertEquals(4L, blocked.bytes); assertNotNull(blocked.warning)
            exists = false
            val removed = sources.status(CopySettings(maxBytes = 1))
            assertEquals(0, removed.count); assertEquals(0L, removed.bytes)
        } finally { exists = false; sources.clear(); sources.close(); folder.deleteRecursively() }
    }

    @Test fun retainedMimeFollowsUsefulMetadataAndSurvivesExtensionlessReopen() {
        org.junit.Assume.assumeTrue(android.os.Build.VERSION.SDK_INT >= 29)
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(base.cacheDir, "source-mime-test-${UUID.randomUUID()}").apply { mkdirs() }
        val input = File(folder, "input.pdf")
        val source = Uri.parse("content://mime-fixture/incoming")
        var name = "Report.pdf"
        var mime: String? = "application/octet-stream"
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: Uri) = mime
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null // Force scoped private fallback.
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): android.database.Cursor? =
                when (uri) {
                    source -> MatrixCursor(arrayOf(android.provider.OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf(name)) }
                    android.provider.MediaStore.Files.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY) ->
                        MatrixCursor(requireNotNull(projection)) // Successful empty lookup proves the refused create left no copy.
                    else -> null
                }
            override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
                check(uri == source && mode == "r")
                return ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY)
            }
        }
        val context = TestContext(base, folder, ContentResolver.wrap(provider))
        val sources = DocumentSources(context)
        try {
            writeBluePdf(input)
            // Sender MIME fills missing or generic metadata; the PDF signature determines rendering.
            for (reported in listOf(null, "application/octet-stream")) {
                name = "Report"; mime = reported
                sources.open(source, null, CancellationSignal(), mimeHint = "application/pdf").use {
                    assertEquals("application/pdf", it.mimeType); assertEquals("pdf", it.session.info.format)
                }
            }
            // Oversized hints are ignored; a valid PDF remains readable by its signature.
            sources.open(source, null, CancellationSignal(), mimeHint = "application/pdf;" + "x".repeat(128)).use {
                assertEquals("application/octet-stream", it.mimeType)
                assertEquals("application/pdf", it.session.info.mimeType)
                assertEquals("pdf", it.session.info.format)
            }
            name = "Report.pdf"; mime = "application/octet-stream"
            sources.open(source, null, CancellationSignal(), mimeHint = "application/epub+zip").use {
                assertEquals("pdf", it.session.info.format)
            }
            val identity = sources.open(source, CopySettings(), CancellationSignal()).use {
                assertEquals("pdf", it.session.info.format)
                assertEquals("file", requireNotNull(it.importUri).scheme)
                it.hash
            }
            name = "Report"; mime = " APPLICATION/PDF ; ignored=parameter"
            val retained = sources.open(source, CopySettings(), CancellationSignal()).use {
                assertEquals(identity, it.hash); assertEquals("pdf", it.session.info.format)
                requireNotNull(it.importUri)
            }
            sources.close()
            DocumentSources(context).use { reopened ->
                reopened.open(retained, null, CancellationSignal()).use {
                    assertEquals("Report", it.displayName); assertEquals("application/pdf", it.mimeType)
                    assertEquals(identity, it.hash); assertEquals("pdf", it.session.info.format)
                    val page = it.session.renderPage(0, 64, ThemeSettings(ReadingMode.ORIGINAL), CancellationSignal())
                    try { assertEquals(64, page.width); assertEquals(32, page.height); assertEquals(android.graphics.Color.BLUE, page.getPixel(0, 0)) }
                    finally { page.recycle() }
                }
                for (fallback in listOf(null, "application/octet-stream")) {
                    name = if (fallback == null) "No mime.pdf" else "Generic mime.pdf"; mime = fallback
                    reopened.open(source, CopySettings(), CancellationSignal()).close()
                    SQLiteDatabase.openDatabase(context.getDatabasePath("imports.sqlite").path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                        db.rawQuery("SELECT mime FROM copies WHERE hash=?", arrayOf(identity)).use {
                            assertTrue(it.moveToFirst()); assertEquals("application/pdf", it.getString(0))
                        }
                    }
                }
                name = "Fresh provider.pdf"; mime = "application/pdf"
                reopened.open(source, null, CancellationSignal(), savedFormat = SavedFormat(identity, "Stale.epub", "application/epub+zip"), mimeHint = "text/markdown").use {
                    assertEquals("Fresh provider.pdf", it.displayName)
                    assertEquals("application/pdf", it.mimeType)
                }
                name = "Report"; mime = null
                reopened.open(source, null, CancellationSignal(), savedFormat = SavedFormat(identity, "Report", "application/pdf")).use {
                    assertEquals("pdf", it.session.info.format)
                }
                input.writeText("<!doctype html><html><body>Changed document</body></html>")
                reopened.open(source, null, CancellationSignal(), savedFormat = SavedFormat(identity, "Report", "application/pdf")).use {
                    assertNotEquals(identity, it.hash)
                    assertNull("Saved MIME belongs to the previous hash", it.mimeType)
                    assertEquals("html", it.session.info.format)
                    assertEquals("text/html", it.session.info.mimeType)
                    assertEquals("Changed document", it.session.bodyText(CancellationSignal()))
                }
                assertEquals(1, reopened.status(CopySettings()).count)
                reopened.clear()
            }
        } finally { runCatching { sources.clear() }; sources.close(); folder.deleteRecursively() }
    }


}
