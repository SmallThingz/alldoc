package app.smallthingz.alldoc.sources

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.MatrixCursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.os.ParcelFileDescriptor
import android.os.Process
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.smallthingz.alldoc.state.CopySettings
import app.smallthingz.alldoc.state.DocumentMetadata
import app.smallthingz.alldoc.state.DocumentStore
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaSourceIdentityTest {
    /** Only the platform conversion is supplied; the real query, index and admission paths run.
     * The permission predicate can model a persisted grant; actual Files grants need the device gate. */
    private class Fixture(sourceUri: Uri? = null) : AutoCloseable {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "media-identity-" + UUID.randomUUID()).apply { mkdirs() }
        val incoming = File(root, "incoming.md").apply { writeText("Original complete content") }
        private val token = UUID.randomUUID().toString()
        val managed = File(root, "managed-$token.md").apply { writeBytes(incoming.readBytes()) }
        val uri = sourceUri ?: DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Documents/AllDoc/Incoming.md")
        val owned = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY, 41)
        var mapped: Uri? = Uri.parse("content://media/external/images/media/41")
        var volume = MediaStore.VOLUME_EXTERNAL_PRIMARY
        var queryMode = "ok"
        var mappingFailure: Exception? = null
        var persistedAccess = false
        var allowCopies = false
        var cancelDuringMapping: CancellationSignal? = null
        var conversions = 0
        var identityQueries = 0
        var payloadReads = 0
        var inserts = 0
        var deletes = 0
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: Uri) = "text/markdown"
            override fun insert(uri: Uri, values: ContentValues?): Uri {
                check(allowCopies && uri == MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY))
                inserts++
                return owned
            }
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
                check(uri == owned)
                deletes++
                managed.delete()
                return 1
            }
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
                check(uri == owned)
                return 1
            }
            override fun query(request: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): android.database.Cursor? {
                if (projection?.contains(MediaStore.MediaColumns.VOLUME_NAME) == true) {
                    identityQueries++
                    if (queryMode == "null") return null
                    return MatrixCursor(projection).apply {
                        if (queryMode != "empty") addRow(arrayOf<Any?>(
                            if (queryMode == "wrong-id") ContentUris.parseId(request) + 1 else ContentUris.parseId(request), volume))
                        if (queryMode == "multiple") addRow(arrayOf<Any?>(ContentUris.parseId(request), volume))
                    }
                }
                check(request == uri || request == owned)
                return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf("Renamed by provider.md")) }
            }
            override fun openFile(request: Uri, mode: String): ParcelFileDescriptor {
                val file = when (request) { uri -> incoming; owned -> managed; else -> error("Unexpected payload URI $request") }
                if (mode == "w") {
                    check(allowCopies && request == owned)
                    return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE)
                }
                check(mode == "r")
                payloadReads++
                return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            }
        }
        val context = object : ContextWrapper(base) {
            val resolver = ContentResolver.wrap(provider)
            override fun getApplicationContext(): Context = this
            override fun getContentResolver(): ContentResolver = resolver
            override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
            override fun getDatabasePath(name: String): File = File(root, name)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
                SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, handler: DatabaseErrorHandler?): SQLiteDatabase =
                SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory, handler)
        }
        val sources = DocumentSources(context, persistedRead = { persistedAccess && it == uri }) { _, request ->
            check(request == uri)
            conversions++
            mappingFailure?.let { throw it }
            cancelDuringMapping?.cancel()
            mapped
        }
        fun seed(location: Uri = owned): String {
            sources.status(CopySettings())
            val hash = hash(incoming.readText())
            SQLiteDatabase.openDatabase(context.getDatabasePath("imports.sqlite").path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL("INSERT INTO copies(hash,name,mime,bytes,uri,target,token,pending,owner,touched) VALUES(?,?,?,?,?,?,?,?,?,?)",
                    arrayOf<Any?>(hash, "Saved.md", "text/markdown", incoming.length(), location.toString(), "media", token, 0, Process.myPid(), 1L))
            }
            return hash
        }
        fun resetCounts() { conversions = 0; identityQueries = 0; payloadReads = 0 }
        override fun close() {
            // All provider/file/index data belongs to this fixture; no real MediaStore row is touched.
            sources.close()
            root.deleteRecursively()
        }
    }

    @Test fun mappedAliasesNormalizeRekeyAndPreserveGenuineFileOriginals() {
        org.junit.Assume.assumeTrue(android.os.Build.VERSION.SDK_INT >= 29)
        Fixture().use { f ->
            val originalHash = f.seed()
            val physical = Uri.fromFile(f.managed)
            f.sources.open(physical, null, CancellationSignal()).use {
                assertEquals(originalHash, it.hash); assertEquals("Saved.md", it.displayName)
                assertNull("A physical alias is not an independent original", it.sourceUri)
                assertEquals(f.owned, it.importUri)
            }
            val changed = "Edited physical retained bytes " + UUID.randomUUID()
            f.managed.writeText(changed)
            f.sources.open(physical, CopySettings(), CancellationSignal()).use {
                assertEquals(hash(changed), it.hash); assertEquals(changed, it.session.text(CancellationSignal()))
                assertNull(it.sourceUri); assertEquals(f.owned, it.importUri)
                assertEquals(listOf(originalHash), it.evictedHashes)
            }
            assertNull(f.sources.importUri(originalHash))
            assertEquals(f.owned, f.sources.importUri(hash(changed)))
            assertEquals(1, f.sources.status(CopySettings()).count)
            assertEquals(0, f.conversions); assertEquals(0, f.inserts); assertEquals(0, f.deletes)
            assertEquals("The independent original bytes must remain untouched", originalHash, hash(f.incoming.readText()))
            f.sources.clear()
            assertFalse(f.managed.exists()); assertTrue(f.incoming.exists())
            assertEquals(originalHash, hash(f.incoming.readText()))
            assertEquals(0, f.sources.status(CopySettings()).count)
        }
        for (collection in listOf("file", "images/media")) for (directVolume in listOf(null, "external", "external_primary")) Fixture(
            directVolume?.let { Uri.parse("content://media/$it/$collection/41") }
        ).use { f ->
            val conversions = if (directVolume == null) 1 else 0
            val identityQueries = if (f.uri == f.owned) 0 else 1
            f.mapped = Uri.parse("content://media/external/$collection/41")
            val originalHash = f.seed()
            f.sources.open(f.uri, null, CancellationSignal()).use {
                assertEquals(originalHash, it.hash)
                assertEquals("Saved.md", it.displayName)
                assertEquals(f.owned, it.importUri)
                assertNull(it.sourceUri)
                assertNull(it.retentionWarning)
            }
            assertEquals(conversions, f.conversions); assertEquals(identityQueries, f.identityQueries); assertEquals(1, f.payloadReads)
            assertEquals(0, f.inserts); assertEquals(0, f.deletes)
            val changed = "Edited retained bytes " + UUID.randomUUID()
            f.incoming.writeText(changed); f.managed.writeText(changed)
            f.resetCounts()
            val changedHash = hash(changed)
            DocumentStore(f.context).use { store ->
                f.sources.open(f.uri, CopySettings(), CancellationSignal()).use {
                    assertEquals(changedHash, it.hash)
                    assertEquals(changed, it.session.text(CancellationSignal()))
                    assertEquals(f.owned, it.importUri)
                    assertNull(it.sourceUri)
                    assertEquals(listOf(originalHash), it.evictedHashes)
                    store.open(it.hash, DocumentMetadata(it.displayName, it.mimeType, it.sizeBytes,
                        it.sourceUri?.toString(), it.importUri?.toString()))
                }
                assertEquals(conversions, f.conversions); assertEquals(identityQueries, f.identityQueries)
                assertEquals("A proven alias must use the one admission snapshot", 1, f.payloadReads)
                assertEquals(0, f.inserts)
                assertNull(f.sources.importUri(originalHash))
                assertNull(store.get(changedHash)!!.metadata.sourceUri)
                assertEquals(1, f.sources.status(CopySettings()).count)
                val external = File(f.root, "Genuine original.md").apply { writeText(changed) }
                f.resetCounts()
                f.sources.open(Uri.fromFile(external), CopySettings(), CancellationSignal()).use {
                    assertEquals(Uri.fromFile(external), it.sourceUri)
                    store.open(it.hash, DocumentMetadata(it.displayName, it.mimeType, it.sizeBytes,
                        it.sourceUri?.toString(), it.importUri?.toString()))
                }
                assertEquals("A file original must never invoke platform conversion", 0, f.conversions)
                f.sources.clear()
                val receipts = f.sources.pendingEvictions()
                store.clearMatchingImports(receipts.map { it.hash to it.importUri })
                assertNull(store.get(changedHash)!!.metadata.importUri)
                assertEquals(Uri.fromFile(external).toString(), store.get(changedHash)!!.metadata.sourceUri)
                assertTrue(external.exists())
                assertEquals(0, f.sources.status(CopySettings()).count)
            }
        }
    }

    @Test fun mappedUnownedRowsAndOtherVolumesDoNotRekeyEqualContent() {
        org.junit.Assume.assumeTrue(android.os.Build.VERSION.SDK_INT >= 29)
        for (otherVolume in listOf(false, true)) for (direct in listOf(false, true)) Fixture(
            if (direct) Uri.parse("content://media/external/file/${if (otherVolume) 41 else 99}") else null
        ).use { f ->
            val originalHash = f.seed()
            if (otherVolume) f.volume = "0123-4567" else f.mapped = Uri.parse("content://media/external/file/99")
            f.sources.open(f.uri, CopySettings(enabled = false), CancellationSignal()).use {
                assertEquals(originalHash, it.hash)
                assertEquals(f.owned, it.importUri) // Content dedup still finds the different managed item.
                assertNull(it.retentionWarning)
            }
            val changed = "Changed genuine external item " + UUID.randomUUID()
            f.incoming.writeText(changed)
            f.resetCounts()
            f.sources.open(f.uri, CopySettings(enabled = false), CancellationSignal()).use {
                assertEquals(hash(changed), it.hash)
                assertNull("A different mapped item is not a managed origin", it.importUri)
                assertTrue(it.evictedHashes.isEmpty())
            }
            assertEquals(f.owned, f.sources.importUri(originalHash))
            assertEquals(1, f.sources.status(CopySettings()).count)
            assertTrue(f.sources.pendingEvictions().isEmpty())
            assertEquals(1, f.payloadReads)
            assertEquals(if (direct) 0 else 1, f.conversions); assertEquals(1, f.identityQueries)
            assertEquals(0, f.inserts); assertEquals(0, f.deletes)
        }
        // Album/thumbnail IDs and whole collections are not identities in the files table.
        for (path in listOf("external/audio/albums/41", "external/images/thumbnails/41", "external/file", "external/images/media")) Fixture(
            Uri.parse("content://media/$path")
        ).use { f ->
            val originalHash = f.seed()
            f.incoming.writeText("Different non-item bytes")
            f.sources.open(f.uri, CopySettings(enabled = false), CancellationSignal()).use {
                assertNull(it.importUri); assertTrue(it.evictedHashes.isEmpty()); assertNull(it.retentionWarning)
            }
            assertEquals(f.owned, f.sources.importUri(originalHash))
            assertEquals(0, f.conversions); assertEquals(0, f.identityQueries); assertEquals(1, f.payloadReads)
            assertEquals(0, f.inserts); assertEquals(0, f.deletes)
        }
    }

    @Test fun unavailableMappingsStayUnknownWhileNullAndDirectOwnershipRemainDistinct() {
        org.junit.Assume.assumeTrue(android.os.Build.VERSION.SDK_INT >= 29)
        for (failure in listOf("throw", "null", "empty", "wrong-id", "multiple", "aggregate")) Fixture().use { f ->
            f.seed()
            f.incoming.writeText("Different incoming bytes")
            if (failure == "throw") f.mappingFailure = IOException("Provider unavailable")
            else if (failure == "aggregate") f.volume = MediaStore.VOLUME_EXTERNAL
            else f.queryMode = failure
            f.sources.open(f.uri, null, CancellationSignal()).use {
                assertEquals("Different incoming bytes", it.session.text(CancellationSignal()))
                assertNull(it.sourceUri)
                assertEquals("This file may be unavailable later.", it.retentionWarning)
            }
            assertEquals(1, f.payloadReads)
            assertEquals(0, f.inserts); assertEquals(0, f.deletes)
        }
        Fixture().use { f ->
            f.seed(); f.mapped = null
            f.incoming.writeText("No equivalent item")
            f.sources.open(f.uri, null, CancellationSignal()).use { assertNull(it.retentionWarning) }
            assertEquals(0, f.identityQueries)
        }
        Fixture().use { f ->
            f.seed(f.uri); f.mappingFailure = IOException("Must not convert a directly owned row")
            f.sources.open(f.uri, null, CancellationSignal()).use {
                assertEquals(f.uri, it.importUri); assertNull(it.retentionWarning)
            }
            assertEquals(0, f.conversions); assertEquals(1, f.payloadReads)
        }
        // A real persisted grant may still be unpublishable when platform alias mapping fails.
        // Retain admitted bytes once, rather than losing both source and copy references.
        Fixture().use { f ->
            f.persistedAccess = true
            f.allowCopies = true
            f.mappingFailure = IOException("Platform mapping unavailable")
            val body = f.incoming.readText()
            val expectedHash = hash(body)
            DocumentStore(f.context).use { store ->
                repeat(2) {
                    f.sources.open(f.uri, CopySettings(), CancellationSignal()).use { opened ->
                        assertEquals(expectedHash, opened.hash)
                        assertEquals(body, opened.session.text(CancellationSignal()))
                        assertEquals(body.toByteArray().size.toLong(), opened.sizeBytes)
                        assertNull("Unknown ownership must not publish the original grant", opened.sourceUri)
                        assertEquals(f.owned, opened.importUri)
                        store.open(opened.hash, DocumentMetadata(opened.displayName, opened.mimeType, opened.sizeBytes,
                            opened.sourceUri?.toString(), opened.importUri?.toString()))
                    }
                }
                assertEquals("Repeated admission reuses the hash-indexed fallback", 1, f.inserts)
                assertEquals(1, f.sources.status(CopySettings()).count)
                val saved = requireNotNull(store.get(expectedHash))
                assertNull(saved.metadata.sourceUri)
                val location = Uri.parse(requireNotNull(saved.metadata.importUri))
                DocumentSources(f.context) { _, _ -> error("An indexed fallback must not require platform mapping") }.use { fresh ->
                    fresh.open(location, CopySettings(), CancellationSignal()).use { reopened ->
                        assertEquals(expectedHash, reopened.hash)
                        assertEquals(body, reopened.session.text(CancellationSignal()))
                        assertEquals(f.owned, reopened.importUri)
                        assertNull(reopened.sourceUri)
                    }
                }
                assertEquals(1, f.inserts)
                assertEquals(0, f.deletes)
            }
        }
        for (policy in listOf(null, CopySettings(enabled = false), CopySettings(maxBytes = 1))) Fixture().use { f ->
            f.persistedAccess = true
            f.mappingFailure = IOException("Platform mapping unavailable")
            f.sources.open(f.uri, policy, CancellationSignal()).use { opened ->
                assertEquals(f.incoming.readText(), opened.session.text(CancellationSignal()))
                assertNull(opened.sourceUri)
                assertNull(opened.importUri)
                assertTrue("Missing reopen warning for $policy: ${opened.retentionWarning}",
                    opened.retentionWarning.orEmpty().endsWith("This file may be unavailable later."))
            }
            assertEquals(0, f.inserts)
            assertEquals(0, f.deletes)
        }
        for (throwFromProvider in listOf(false, true)) Fixture().use { f ->
            f.seed()
            val signal = CancellationSignal()
            if (throwFromProvider) f.mappingFailure = OperationCanceledException()
            else f.cancelDuringMapping = signal
            try { f.sources.open(f.uri, null, signal).close(); fail("Mapping cancellation was swallowed") }
            catch (_: OperationCanceledException) { }
            assertEquals(0, f.payloadReads)
        }
    }

    companion object {
        private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).hex()
    }
}
