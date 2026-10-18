package app.smallthingz.alldoc.sources

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.MatrixCursor
import android.database.sqlite.SQLiteDatabase
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.smallthingz.alldoc.core.DocumentProbe
import app.smallthingz.alldoc.state.CopySettings
import app.smallthingz.alldoc.state.LibraryCatalog
import app.smallthingz.alldoc.state.LibraryFile
import app.smallthingz.alldoc.state.LibraryCategory
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryIndexTest {
    private class IsolatedContext(base: Context, val root: File) : ContextWrapper(base) {
        var resolver: ContentResolver? = null
        override fun getContentResolver(): ContentResolver = resolver ?: super.getContentResolver()
        override fun getApplicationContext(): Context = this
        override fun getSystemService(name: String): Any? = if (name == Context.STORAGE_SERVICE) null else super.getSystemService(name)
        override fun checkSelfPermission(permission: String) = android.content.pm.PackageManager.PERMISSION_DENIED
        override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
        override fun getFilesDir() = File(root, "files").apply { mkdirs() }
        override fun getDatabasePath(name: String) = File(root, name)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
            SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, handler: DatabaseErrorHandler?): SQLiteDatabase =
            SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory, handler)
    }
    private inline fun fixture(block: (IsolatedContext, LibraryCatalog) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "library-index-${UUID.randomUUID()}").apply { mkdirs() }.canonicalFile
        val context = IsolatedContext(base, root)
        try { LibraryCatalog(context).use { block(context, it) } } finally { root.deleteRecursively() }
    }
    private fun hash(body: String) = MessageDigest.getInstance("SHA-256").digest(body.toByteArray()).hex()
    private fun metadata(file: File) = LibraryFile(Uri.fromFile(file).toString(), file.name, "text/markdown", file.length(), file.lastModified())

    @Test fun metadataPrecedesHashingAndCancellationNeverPrunesThenRefreshRehashesUnchangedStats() = fixture { context, catalog ->
        val root = File(context.root, "documents").apply { mkdirs() }.canonicalFile
        val file = File(root, "a.md").apply { writeText("first") }
        val other = File(root, "b.md").apply { writeText("second") }
        File(root, "ignored.unsupported-format").writeBytes(byteArrayOf(0, 1, 2, 3, -1))
        val uri = Uri.fromFile(root)
        val oldGeneration = catalog.beginScan(uri.toString())
        val removed = File(root, "removed.md")
        catalog.discover(LibraryFile(Uri.fromFile(removed).toString(), removed.name, "text/markdown", 1, 1), uri.toString(), oldGeneration)
        assertTrue(catalog.finishScan(uri.toString(), oldGeneration))
        val signal = CancellationSignal(); var partial = false
        val index = LibraryIndex(context, catalog)
        try {
            val completed = index.scanRoots(listOf(uri), signal) { progress ->
                if (progress.discovered > 0 && !partial) {
                    partial = true
                    assertEquals(0L, progress.indexed)
                    val discovered = catalog.location(Uri.fromFile(file).toString())
                        ?: catalog.location(Uri.fromFile(other).toString())
                    if (discovered == null) fail("Missing discovered fixture after $progress; locations=${catalog.page(0, 10).map { it.uri }}")
                    assertNull(requireNotNull(discovered).hash)
                    signal.cancel()
                }
            }
            fail("Canceled scan completed: $completed; callback=$partial, cancelled=${signal.isCanceled}")
        } catch (_: OperationCanceledException) { }
        assertTrue(partial); assertNotNull(catalog.location(Uri.fromFile(removed).toString()))
        val complete = index.scanRoots(listOf(uri), CancellationSignal())
        assertTrue(complete.complete); assertEquals(2L, complete.discovered); assertEquals(2L, complete.indexed)
        assertNull(catalog.location(Uri.fromFile(removed).toString())); assertEquals(2, catalog.count())
        assertTrue("New hashes and removed aliases must publish changes", complete.changes > 0)
        val unchanged = index.scanRoots(listOf(uri), CancellationSignal()) { progress ->
            assertEquals("An unchanged scan must not repeatedly refresh the Library", 0L, progress.changes)
            assertFalse(progress.changed)
        }
        assertTrue(unchanged.complete); assertEquals(2L, unchanged.discovered); assertEquals(2L, unchanged.indexed)
        val before = requireNotNull(catalog.location(Uri.fromFile(file).toString()))
        val modified = file.lastModified()
        file.writeText("other"); assertTrue(file.setLastModified(modified))
        assertEquals(before.sizeBytes, file.length()); assertEquals(before.modifiedAt, file.lastModified())
        val rehashed = index.scanRoots(listOf(uri), CancellationSignal())
        assertTrue("Changed bytes must publish even with unchanged metadata", rehashed.changes > 0)
        val changed = requireNotNull(catalog.location(before.uri))
        assertEquals(hash("other"), changed.hash); assertNotEquals(before.hash, changed.hash)
        assertTrue(changed.revision > before.revision)
        assertFalse("Discovery must not create reading history", context.getDatabasePath("documents.db").exists())
        assertFalse("Discovery must not retain copies", context.getDatabasePath("imports.sqlite").exists())
    }

    @androidx.test.filters.SdkSuppress(minSdkVersion = 29)
    @Test fun pendingMetadataPublishesBeforeFurtherUnsupportedFileProbes() = metadataPublication(false)

    @androidx.test.filters.SdkSuppress(minSdkVersion = 29)
    @Test fun pendingMetadataPublishesBeforeFirstProviderOpen() = metadataPublication(true)

    private fun metadataPublication(checkFirstProbe: Boolean) = fixture { context, catalog ->
        val pdf = File(context.root, "known.pdf")
        context.assets.open("examples/office/office-guide.pdf").use { input ->
            pdf.outputStream().use { input.copyTo(it) }
        }
        val ignored = File(context.root, "ignored.bin").apply { writeBytes(byteArrayOf(0, 1, 2, 3, -1)) }
        val tree = DocumentsContract.buildTreeDocumentUri("publication-index", "root")
        val known = DocumentsContract.buildDocumentUriUsingTree(tree, "known")
        var now = 0L
        var published = false
        var visibleDuringDiscovery = false
        var rejectedProbes = 0
        context.resolver = ContentResolver.wrap(object : ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: Uri) = if (DocumentsContract.getDocumentId(uri) == "known") "application/pdf" else "application/octet-stream"
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
            override fun query(request: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?) =
                MatrixCursor(requireNotNull(projection)).apply {
                    assertEquals("children", request.lastPathSegment)
                    for (id in listOf("known", "ignored-0", "ignored-1")) addRow(columnNames.map { when (it) {
                        "document_id" -> id
                        "_display_name" -> if (id == "known") "known.pdf" else "$id.bin"
                        "mime_type" -> if (id == "known") "application/pdf" else "application/octet-stream"
                        "_size" -> if (id == "known") pdf.length() else ignored.length()
                        "last_modified" -> 1L
                        else -> error("Unexpected column: $it")
                    } }.toTypedArray<Any>())
                }
            override fun openFile(request: Uri, mode: String): ParcelFileDescriptor {
                val id = DocumentsContract.getDocumentId(request)
                if (id != "known") {
                    rejectedProbes++
                    if (id == "ignored-0") {
                        if (checkFirstProbe) {
                            assertEquals("The batching deadline has not elapsed", 0L, now)
                            assertTrue("Known metadata must publish before a provider can block",
                                published && catalog.location(known.toString())?.let { it.hash == null } == true)
                        }
                        now = 100L
                    } else visibleDuringDiscovery = published && catalog.location(known.toString())?.let { it.hash == null } == true
                }
                return ParcelFileDescriptor.open(if (id == "known") pdf else ignored, ParcelFileDescriptor.MODE_READ_ONLY)
            }
        })
        val result = LibraryIndex(context, catalog, elapsed = { now }).scanRoots(listOf(tree), CancellationSignal()) {
            if (it.discovered == 1L && it.indexed == 0L) published = true
        }
        assertEquals(2, rejectedProbes)
        assertTrue("Known metadata stayed hidden while rejected entries were probed", visibleDuringDiscovery)
        assertTrue(result.complete); assertEquals(1L, result.discovered); assertEquals(1L, result.indexed)
        assertNotNull(requireNotNull(catalog.location(known.toString())).hash)
    }

    @androidx.test.filters.SdkSuppress(minSdkVersion = 29)
    @Test fun loadingProviderListingKeepsPublishedRowsAndDefersPruning() = incompleteProviderListing(DocumentsContract.EXTRA_LOADING)

    @androidx.test.filters.SdkSuppress(minSdkVersion = 29)
    @Test fun erroredProviderListingKeepsPublishedRowsAndDefersPruning() = incompleteProviderListing(DocumentsContract.EXTRA_ERROR)

    private fun incompleteProviderListing(extra: String) = fixture { context, catalog ->
        val body = "# Partial folder\nA readable document remains visible."
        val file = File(context.root, "listed.md").apply { writeText(body) }
        val tree = DocumentsContract.buildTreeDocumentUri("partial-index", "root")
        val listed = DocumentsContract.buildDocumentUriUsingTree(tree, "listed")
        val omitted = DocumentsContract.buildDocumentUriUsingTree(tree, "omitted")
        val generation = catalog.beginScan(tree.toString())
        val previous = requireNotNull(catalog.discover(LibraryFile(omitted.toString(), "previous.md", "text/markdown", 10, 1),
            tree.toString(), generation)).let { requireNotNull(catalog.resolve(it, hash("previous document"))) }
        assertTrue(catalog.finishScan(tree.toString(), generation))
        var incomplete = true
        var queries = 0
        context.resolver = ContentResolver.wrap(object : ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: Uri) = "text/markdown"
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
            override fun query(request: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?) =
                MatrixCursor(requireNotNull(projection)).apply {
                    assertEquals("children", request.lastPathSegment)
                    queries++
                    addRow(columnNames.map { when (it) {
                        "document_id" -> "listed"
                        "_display_name" -> file.name
                        "mime_type" -> "text/markdown"
                        "_size" -> file.length()
                        "last_modified" -> 1L
                        else -> error("Unexpected column: $it")
                    } }.toTypedArray<Any>())
                    setExtras(Bundle().apply {
                        putBoolean(DocumentsContract.EXTRA_LOADING, incomplete && extra == DocumentsContract.EXTRA_LOADING)
                        putString(DocumentsContract.EXTRA_ERROR, if (incomplete && extra == DocumentsContract.EXTRA_ERROR)
                            "The remote folder is temporarily unavailable." else "")
                    })
                }
            override fun openFile(request: Uri, mode: String): ParcelFileDescriptor {
                assertEquals(listed, request)
                return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            }
        })
        val index = LibraryIndex(context, catalog)
        var publishedBeforeHashing = false
        val partial = index.scanRoots(listOf(tree), CancellationSignal()) { progress ->
            if (progress.discovered == 1L && progress.indexed == 0L) {
                publishedBeforeHashing = true
                assertNull(requireNotNull(catalog.location(listed.toString())).hash)
                assertEquals(previous, catalog.location(omitted.toString()))
            }
        }
        assertTrue("Partial listing metadata must still publish", publishedBeforeHashing)
        assertFalse("A loading or failed provider listing cannot certify a complete scan", partial.complete)
        assertEquals(1L, partial.discovered); assertEquals(1L, partial.indexed); assertEquals(1L, partial.skipped)
        assertEquals(previous, catalog.location(omitted.toString()))
        assertEquals(hash(body), requireNotNull(catalog.location(listed.toString())).hash)
        assertEquals(2, catalog.count()); assertEquals(1, queries)
        // A later explicit complete scan may prune; no polling or automatic retry is introduced.
        incomplete = false
        val refreshed = index.scanRoots(listOf(tree), CancellationSignal())
        assertTrue(refreshed.complete); assertEquals(0L, refreshed.skipped)
        assertEquals(1L, refreshed.discovered); assertEquals(1L, refreshed.indexed)
        assertNull(catalog.location(omitted.toString())); assertEquals(1, catalog.count())
        assertEquals(hash(body), requireNotNull(catalog.location(listed.toString())).hash)
        assertEquals(2, queries)
        assertFalse(context.getDatabasePath("imports.sqlite").exists())
        assertFalse(context.getDatabasePath("documents.db").exists())
    }

    @Test fun documentHashFailureAndLocationLimitsReportIncompleteAndCanRetry() = fixture { context, catalog ->
        val root = File(context.root, "hash-failure").apply { mkdirs() }.canonicalFile
        val changing = File(root, "changing.md").apply { writeText("before") }
        val readable = File(root, "readable.md").apply { writeText("Stable document") }
        File(root, "unsupported.bin").writeBytes(byteArrayOf(0, 1, 2, -1))
        val index = LibraryIndex(context, catalog)
        val uri = Uri.fromFile(root)
        var replaced = false
        val result = index.scanRoots(listOf(uri), CancellationSignal()) { progress ->
            if (!replaced && progress.discovered == 2L && progress.indexed == 0L) {
                // Both signatures were accepted; only full hash admission now fails its size check.
                changing.writeText("Changed after discovery, before hashing")
                replaced = true
            }
        }
        assertTrue(replaced)
        assertFalse("An accepted document without a verified hash leaves discovery incomplete", result.complete)
        assertEquals(2L, result.discovered); assertEquals(1L, result.indexed); assertEquals(1L, result.skipped)
        assertNull(requireNotNull(catalog.location(Uri.fromFile(changing).toString())).hash)
        assertEquals(hash(readable.readText()), requireNotNull(catalog.location(Uri.fromFile(readable).toString())).hash)
        val retry = index.scanRoots(listOf(uri), CancellationSignal())
        assertTrue(retry.complete); assertEquals(2L, retry.indexed); assertEquals(0L, retry.skipped)
        assertEquals(hash(changing.readText()), requireNotNull(catalog.location(Uri.fromFile(changing).toString())).hash)
        var parent = root
        while (Uri.fromFile(parent).toString().length < 3600)
            parent = File(parent, "文".repeat(40)).apply { assertTrue(mkdir()) }
        val omitted = File(parent, "文".repeat(80) + ".md").apply { writeText("Supported document") }
        assertTrue(Uri.fromFile(parent).toString().length <= 4096)
        assertTrue(Uri.fromFile(omitted).toString().length > 4096)
        val missing = File(root, "missing.md")
        catalog.discover(metadata(missing), uri.toString(), catalog.beginScan(uri.toString()))
        val partial = index.scanRoots(listOf(uri), CancellationSignal())
        assertFalse("A skipped document location must not report a complete scan", partial.complete)
        assertEquals(1L, partial.skipped); assertEquals(2L, partial.indexed)
        assertNotNull("An incomplete traversal must not prune previous locations", catalog.location(Uri.fromFile(missing).toString()))
        assertTrue(omitted.delete())
        val recovered = index.scanRoots(listOf(uri), CancellationSignal())
        assertTrue(recovered.complete); assertEquals(0L, recovered.skipped)
        assertNull(catalog.location(Uri.fromFile(missing).toString()))
    }

    @Test fun inaccessibleKnownRootsReportIncompleteWithoutRemovingDocuments() = fixture { context, catalog ->
        val fresh = LibraryIndex(context, catalog).scan(CancellationSignal())
        assertTrue("A fresh Library with no selected storage is not a failed scan", fresh.complete)
        assertEquals(0L, fresh.skipped)
        val root = Uri.parse("content://alldoc-test-missing-${UUID.randomUUID()}.documents/tree/folder")
        catalog.addRoot(root.toString())
        val generation = catalog.beginScan(root.toString())
        val location = "content://alldoc-test/documents/existing"
        catalog.discover(LibraryFile(location, "Saved.md", "text/markdown", 10, 1), root.toString(), generation)
        catalog.finishScan(root.toString(), generation)
        val volume = Uri.fromFile(File(context.root, "unmounted-volume"))
        val volumeGeneration = catalog.beginScan(volume.toString())
        val volumeLocation = "$volume/Existing.pdf"
        catalog.discover(LibraryFile(volumeLocation, "Existing.pdf", "application/pdf", 10, 1), volume.toString(), volumeGeneration)
        catalog.finishScan(volume.toString(), volumeGeneration)
        val progress = mutableListOf<LibraryScanProgress>()
        val result = LibraryIndex(context, catalog).scan(CancellationSignal(), progress::add)
        assertFalse("Unavailable configured roots must not report a complete scan", result.complete)
        assertEquals(2L, result.skipped)
        assertEquals(result, progress.last())
        assertNotNull("Revoked access does not prove that a document was removed", catalog.location(location))
        assertNotNull("Unmounted shared storage keeps its known documents", catalog.location(volumeLocation))
    }

    @Test fun signaturesDiscoverRenamedDocumentsAndArchiveLimitsKeepKnownLocations() = fixture { context, catalog ->
        val root = File(context.root, "signatures").apply { mkdirs() }.canonicalFile
        val pdf = File(root, "renamed.payload")
        val document = PdfDocument()
        try {
            val page = document.startPage(PdfDocument.PageInfo.Builder(300, 400, 1).create())
            page.canvas.drawText("Signature fixture", 20f, 40f, Paint().apply { textSize = 18f })
            document.finishPage(page)
            pdf.outputStream().use(document::writeTo)
        } finally { document.close() }
        val text = File(root, "Readme.md").apply { writeText("नमस्ते · Markdown document") }
        val wordMime = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        fun word(name: String, padding: Int): File = File(root, name).also { file ->
            ZipOutputStream(file.outputStream()).use { zip ->
                fun entry(name: String, bytes: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
                if (padding > 0) entry("padding", ByteArray(padding))
                entry("[Content_Types].xml", ("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                    "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>").toByteArray())
                entry("word/document.xml", ("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
                    "<w:body><w:p><w:r><w:t>Renamed Office document</w:t></w:r></w:p></w:body></w:document>").toByteArray())
            }
        }
        val office = word("office.md", 0)
        val large = word("large.docx", 512 * 1024)
        val previous = word("known-without-extension", 512 * 1024)
        catalog.discover(LibraryFile(Uri.fromFile(previous).toString(), previous.name, wordMime, previous.length(), previous.lastModified()))
        val bogus = File(root, "bogus.pdf").apply { writeBytes(byteArrayOf(0, 1, 2, 3, -1)) }
        val archive = File(root, "archive.zip").also { file -> ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("notes.md")); zip.write("Archive only".toByteArray()); zip.closeEntry()
        } }
        val apk = File(root, "application.apk").also { file -> ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("AndroidManifest.xml")); zip.write(byteArrayOf(0, 1, 2)); zip.closeEntry()
        } }
        val result = LibraryIndex(context, catalog).scanRoots(listOf(Uri.fromFile(root)), CancellationSignal())
        assertTrue(result.complete)
        fun indexed(file: File) = requireNotNull(catalog.location(Uri.fromFile(file).toString()))
        assertEquals("application/pdf", indexed(pdf).mimeType)
        assertEquals("text/markdown", indexed(text).mimeType)
        assertEquals(wordMime, indexed(office).mimeType)
        assertEquals(wordMime, indexed(large).mimeType)
        assertEquals("An inconclusive probe must preserve a known alias", wordMime, indexed(previous).mimeType)
        for (file in listOf(bogus, archive, apk)) assertNull(catalog.location(Uri.fromFile(file).toString()))
        for (file in listOf(pdf, text, office, large, previous)) {
            val expected = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).hex()
            assertEquals(expected, indexed(file).hash)
        }
        DocumentSources(context).use { sources ->
            sources.openIndexed(indexed(pdf), CancellationSignal()).use { assertEquals("pdf", it.session.info.format) }
            sources.openIndexed(indexed(text), CancellationSignal()).use { assertEquals("नमस्ते · Markdown document", it.session.text(CancellationSignal())) }
            sources.openIndexed(indexed(office), CancellationSignal()).use {
                assertEquals("DOCX", it.session.info.format.uppercase())
                assertTrue(it.session.text(CancellationSignal()).contains("Renamed Office document"))
            }
        }
    }

    @Test fun inconclusiveReplacementDoesNotCertifyThePreviousPdfTypeOrHash() = fixture { context, catalog ->
        val root = File(context.root, "replaced").apply { mkdirs() }.canonicalFile
        val file = File(root, "previous.pdf").apply { writeText("%PDF-1.7 previous bytes") }
        val previous = requireNotNull(catalog.discover(LibraryFile(Uri.fromFile(file).toString(), file.name, "application/pdf", file.length(), file.lastModified())))
        catalog.resolve(previous, hash(file.readText()))
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("padding")); zip.write(ByteArray(512 * 1024)); zip.closeEntry()
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>Changed to Office</w:t></w:r></w:p></w:body></w:document>".toByteArray())
            zip.closeEntry()
        }
        File(root, "readable.md").writeText("Still discover this")
        val result = LibraryIndex(context, catalog).scanRoots(listOf(Uri.fromFile(root)), CancellationSignal())
        assertFalse(result.complete)
        assertEquals(1L, result.skipped)
        val changed = requireNotNull(catalog.location(previous.uri))
        assertNull("A bounded inconclusive probe cannot certify the old format against new bytes", changed.hash)
        assertNotNull(catalog.location(Uri.fromFile(File(root, "readable.md")).toString()))
        DocumentSources(context).use { sources -> sources.openIndexed(changed, CancellationSignal()).use {
            assertEquals(MessageDigest.getInstance("SHA-256").digest(file.readBytes()).hex(), it.hash)
            assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document", it.session.info.mimeType)
            assertEquals("DOCX", it.session.info.format.uppercase())
            assertTrue(it.session.text(CancellationSignal()).contains("Changed to Office"))
        } }
    }

    @Test fun indexedPreviewAdmissionVerifiesBytesWithoutRetainingOrMutatingCatalog() = fixture { context, catalog ->
        val file = File(context.root, "preview.md").apply { writeText("first") }
        val discovered = requireNotNull(catalog.discover(metadata(file)))
        val verified = requireNotNull(catalog.resolve(discovered, hash("first")))
        DocumentSources(context).use { sources ->
            sources.openIndexed(verified, CancellationSignal()).use {
                assertEquals(hash("first"), it.hash); assertEquals("first", it.session.text(CancellationSignal()))
                assertNull(it.importUri)
            }
            assertFalse(context.getDatabasePath("imports.sqlite").exists())
            file.writeText("other")
            try { sources.openIndexed(verified, CancellationSignal()).close(); fail("Stale hash admitted") }
            catch (_: LibrarySourceChangedException) { }
            assertEquals(verified, catalog.location(verified.uri))
            sources.openIndexed(verified.copy(hash = null), CancellationSignal()).use {
                assertEquals(hash("other"), it.hash); assertEquals("other", it.session.text(CancellationSignal()))
                assertNull(it.importUri)
            }
            try { sources.openIndexed(verified, CancellationSignal().apply { cancel() }).close(); fail("Canceled preview admitted") }
            catch (_: OperationCanceledException) { }
            val cached = File(context.cacheDir, "expired.md").apply { writeText("cached") }
            val cachedDocument = requireNotNull(catalog.discover(metadata(cached)))
            assertFalse(LibraryInput.isMissingPrivateFile(context, Uri.fromFile(cached)))
            assertTrue(cached.delete())
            assertTrue(LibraryInput.isMissingPrivateFile(context, Uri.fromFile(cached)))
            try { sources.openIndexed(cachedDocument, CancellationSignal()).close(); fail("Missing private cache admitted") }
            catch (_: LibrarySourceMissingException) { }
            assertEquals("Admission must leave catalog ownership to its caller", cachedDocument, catalog.location(cachedDocument.uri))
            try { sources.openIndexed(cachedDocument, CancellationSignal().apply { cancel() }).close(); fail("Missing-source check hid cancellation") }
            catch (_: OperationCanceledException) { }
            assertFalse(LibraryInput.isMissingPrivateFile(context, Uri.fromFile(File(context.root, "outside-cache-missing.md"))))
            assertFalse(LibraryInput.isMissingPrivateFile(context, Uri.parse("content://example.documents/missing")))
            assertFalse(context.getDatabasePath("imports.sqlite").exists())
            assertFalse(context.getDatabasePath("documents.db").exists())
        }
    }

    @Test fun incomingContentReusesOnlyAnIndexedLocalFileWhoseCurrentBytesStillMatch() = fixture { context, catalog ->
        val local = File(context.root, "original.md").apply { writeText("first") }
        val indexed = requireNotNull(catalog.discover(metadata(local)))
        catalog.resolve(indexed, hash("first"))
        repeat(16) { index ->
            val stale = requireNotNull(catalog.discover(LibraryFile("content://expired-grant/$index", "Alias.md", "text/markdown", 5, 0)))
            catalog.resolve(stale, hash("first"))
        }
        DocumentSources(context).use { sources ->
            try {
                TransientDocument("first", "Renamed.md").use { sources.open(it.uri, CopySettings(), CancellationSignal()) }.use {
                    assertEquals(Uri.fromFile(local), it.sourceUri); assertNull(it.importUri)
                    assertEquals(hash("first"), it.hash)
                }
                assertEquals(0, sources.status(CopySettings()).count)
                val modified = local.lastModified()
                local.writeText("other"); assertTrue(local.setLastModified(modified))
                TransientDocument("first", "Again.md").use { sources.open(it.uri, CopySettings(), CancellationSignal()) }.use {
                    assertNull(it.sourceUri); assertNotNull(it.importUri)
                    assertEquals("first", it.session.text(CancellationSignal()))
                }
                assertEquals(1, sources.status(CopySettings()).count)
                assertEquals("other", local.readText())
            } finally { sources.clear() }
        }
    }
    @Test fun excludedTypesSkipDiscoveryAndReturnWhenEnabled() = fixture { context, catalog ->
        val root = File(context.root, "excluded").apply { mkdirs() }
        val slides = File(root, "slides.pptx").also { file ->
            context.assets.open("examples/office/office-guide.pptx").use { input -> file.outputStream().use(input::copyTo) }
        }
        val prose = File(root, "Readme.md").apply { writeText("Document text") }
        val office = File(root, "renamed.pptx")
        context.assets.open("examples/office/office-guide.docx").use { input -> office.outputStream().use(input::copyTo) }
        catalog.setHiddenTypes(setOf(LibraryCategory.PRESENTATIONS))
        val index = LibraryIndex(context, catalog)
        val first = index.scanRoots(listOf(Uri.fromFile(root)), CancellationSignal())
        assertTrue(first.complete); assertEquals(2L, first.discovered); assertEquals(2L, first.indexed)
        assertNull(catalog.location(Uri.fromFile(slides).toString()))
        assertNotNull(catalog.location(Uri.fromFile(prose).toString()))
        assertEquals("Signature classification must override an excluded filename hint", LibraryCategory.DOCUMENTS,
            catalog.location(Uri.fromFile(office).toString())!!.category)
        catalog.setHiddenTypes(emptySet())
        val restored = index.scanRoots(listOf(Uri.fromFile(root)), CancellationSignal())
        assertTrue(restored.complete); assertEquals(3L, restored.indexed)
        assertEquals(LibraryCategory.PRESENTATIONS, catalog.location(Uri.fromFile(slides).toString())!!.category)
        val cached = requireNotNull(catalog.location(Uri.fromFile(office).toString()))
        assertNotNull(cached.hash)
        if (cached.stamp != null) assertEquals("Exercise unchanged-hash admission", cached.stamp, LibraryInput.stamp(Uri.fromFile(office)))
        catalog.setHiddenTypes(setOf(LibraryCategory.DOCUMENTS))
        val hidden = index.scanRoots(listOf(Uri.fromFile(root)), CancellationSignal())
        assertTrue(hidden.complete); assertEquals(1L, hidden.indexed)
        assertNull("A cached signature must obey its actual category, not the allowed filename hint", catalog.location(cached.uri))
        catalog.setHiddenTypes(emptySet())
        assertEquals(3L, index.scanRoots(listOf(Uri.fromFile(root)), CancellationSignal()).indexed)
        assertEquals(LibraryCategory.DOCUMENTS, catalog.location(cached.uri)!!.category)
    }

    @Test fun mutableProvidersClassifyTheHashedBytesAndRespectExclusionsAndArchiveLimits() {
        for (scenario in listOf("image", "unsupported-zip", "inconclusive-pdf", "inconclusive-word")) fixture { context, catalog ->
            val word = scenario == "inconclusive-word"
            val name = if (word) "changing.docx" else "changing.pdf"
            val before = context.assets.open("examples/office/office-guide.${if (word) "docx" else "pdf"}").use { it.readBytes() }
            val after = if (scenario.endsWith("image")) {
                val bitmap = android.graphics.Bitmap.createBitmap(8, 4, android.graphics.Bitmap.Config.ARGB_8888)
                try { ByteArrayOutputStream().also { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }.toByteArray() }
                finally { bitmap.recycle() }
            } else ByteArrayOutputStream().also { bytes ->
                ZipOutputStream(bytes).use { zip ->
                    zip.putNextEntry(ZipEntry("padding.bin"))
                    zip.write(ByteArray(if (scenario.startsWith("inconclusive")) 512 * 1024 else 16))
                    zip.closeEntry()
                    if (scenario.startsWith("inconclusive")) {
                        zip.putNextEntry(ZipEntry("word/document.xml")); zip.write("<document/>".toByteArray()); zip.closeEntry()
                    }
                }
            }.toByteArray()
            val size = maxOf(before.size, after.size)
            val initial = File(context.root, "initial").apply { writeBytes(before.copyOf(size)) }
            val replacement = File(context.root, "replacement").apply { writeBytes(after.copyOf(size)) }
            val tree = DocumentsContract.buildTreeDocumentUri("mutable-index", "root")
            val uri = DocumentsContract.buildDocumentUriUsingTree(tree, "changing")
            var opens = 0
            context.resolver = ContentResolver.wrap(object : ContentProvider() {
                override fun onCreate() = true
                override fun getType(uri: Uri) = "application/pdf"
                override fun insert(uri: Uri, values: ContentValues?): Uri? = null
                override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
                override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
                override fun query(request: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?) =
                    MatrixCursor(requireNotNull(projection)).apply {
                        assertEquals("children", request.lastPathSegment)
                        addRow(columnNames.map { when (it) {
                            "document_id" -> "changing"
                            "_display_name" -> name
                            "mime_type" -> if (word) "application/vnd.openxmlformats-officedocument.wordprocessingml.document" else "application/pdf"
                            "_size" -> size.toLong()
                            "last_modified" -> 50L
                            else -> error("Unexpected column: $it")
                        } }.toTypedArray<Any>())
                    }
                override fun openFile(request: Uri, mode: String): ParcelFileDescriptor {
                    assertEquals(uri, request)
                    return ParcelFileDescriptor.open(if (++opens == 1) initial else replacement, ParcelFileDescriptor.MODE_READ_ONLY)
                }
            })
            val result = LibraryIndex(context, catalog).scanRoots(listOf(tree), CancellationSignal())
            assertEquals("$scenario: one probe plus one hash, without reopening for final type", 2, opens)
            assertEquals(1L, result.discovered)
            val found = catalog.location(uri.toString())
            when (scenario) {
                "inconclusive-word" -> {
                    assertTrue(result.complete); assertEquals(1L, result.indexed)
                    val current = requireNotNull(found)
                    assertEquals(MessageDigest.getInstance("SHA-256").digest(replacement.readBytes()).hex(), current.hash)
                    assertEquals(LibraryCategory.DOCUMENTS, current.category)
                    assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document", current.mimeType)
                }
                "inconclusive-pdf" -> {
                    assertFalse("An incomplete archive probe cannot certify a previous PDF", result.complete)
                    assertEquals(0L, result.indexed); assertEquals(1L, result.skipped)
                    assertNull(requireNotNull(found).hash); assertEquals(-1L, found.sizeBytes)
                }
                else -> { assertTrue(result.complete); assertEquals(0L, result.indexed); assertNull(found) }
            }
            assertFalse(context.getDatabasePath("imports.sqlite").exists())
            assertFalse(context.getDatabasePath("documents.db").exists())
        }
    }

    @Test fun knownOfficeBytesSurviveLostProviderHintsButNeverAuthorizeChangedContainers() {
        for (scenario in listOf("unchanged", "changed-at-hash", "pdf-at-hash", "replaced-row", "replaced-changed-row", "fresh-hint", "changed-hint")) fixture { context, catalog ->
            val original = context.assets.open("examples/office/office-guide.doc").use { it.readBytes() }
            val message = InstrumentationRegistry.getInstrumentation().context.assets.open("admission/unsupported.msg").use { it.readBytes() }
            val pdf = context.assets.open("examples/office/office-guide.pdf").use { it.readBytes() }
            val size = maxOf(original.size, message.size, pdf.size) + 512
            val document = File(context.root, "office").apply { writeBytes(original.copyOf(size)) }
            val replacementBytes = if (scenario == "pdf-at-hash") pdf else message
            val replacement = File(context.root, "replacement").apply { writeBytes(replacementBytes.copyOf(size)) }
            val digest = MessageDigest.getInstance("SHA-256").digest(document.readBytes()).hex()
            val tree = DocumentsContract.buildTreeDocumentUri("known-office", "root")
            val uri = DocumentsContract.buildDocumentUriUsingTree(tree, "same")
            var title = if (scenario == "changed-hint") "Renamed" else "original.doc"
            var mime = "application/msword"
            var opens = 0
            var changed = false
            var rescanning = false
            context.resolver = ContentResolver.wrap(object : ContentProvider() {
                override fun onCreate() = true
                override fun getType(uri: Uri) = mime
                override fun insert(uri: Uri, values: ContentValues?): Uri? = null
                override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
                override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
                override fun query(request: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?) =
                    MatrixCursor(requireNotNull(projection)).apply {
                        assertEquals("children", request.lastPathSegment)
                        addRow(columnNames.map { when (it) {
                            "document_id" -> "same"
                            "_display_name" -> title
                            "mime_type" -> mime
                            "_size" -> size.toLong()
                            "last_modified" -> 50L
                            else -> error("Unexpected column: $it")
                        } }.toTypedArray<Any>())
                    }
                override fun openFile(request: Uri, mode: String): ParcelFileDescriptor {
                    assertEquals(uri, request); opens++
                    if (scenario.startsWith("replaced") && rescanning && opens == 2) {
                        changed = true
                        catalog.discover(LibraryFile(uri.toString(), "Opened meanwhile.doc", "application/msword", size.toLong(), 51))
                    }
                    val file = if (rescanning && (scenario.endsWith("at-hash") && opens == 4 ||
                        scenario == "replaced-changed-row" && opens == 2)) replacement else document
                    return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                }
            })
            val index = LibraryIndex(context, catalog)
            if (scenario != "fresh-hint") {
                assertTrue(index.scanRoots(listOf(tree), CancellationSignal()).complete)
                assertEquals(digest, requireNotNull(catalog.location(uri.toString())).hash)
            }
            title = "Renamed"; mime = if (scenario.endsWith("hint")) "application/msword" else "application/octet-stream"; opens = 0
            rescanning = true
            if (scenario == "changed-hint") {
                // A current provider hint remains valid when a generic-name document changes without a stat update.
                val bytes = document.readBytes(); bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte(); document.writeBytes(bytes)
            }
            val result = index.scanRoots(listOf(tree), CancellationSignal())
            val current = catalog.location(uri.toString())
            when (scenario) {
                "changed-at-hash" -> {
                    assertTrue(result.complete); assertEquals(0L, result.indexed); assertNull(current)
                    assertEquals("Final MIME and hash use the same descriptor", 4, opens)
                }
                "pdf-at-hash" -> {
                    assertTrue(result.complete); assertEquals(1L, result.indexed)
                    assertEquals("application/pdf", requireNotNull(current).mimeType)
                    assertEquals(MessageDigest.getInstance("SHA-256").digest(replacement.readBytes()).hex(), current.hash)
                    assertEquals("Strong final signatures use the same descriptor as their digest", 4, opens)
                }
                "replaced-row", "replaced-changed-row" -> {
                    assertTrue(changed); assertFalse(result.complete); assertEquals(1L, result.skipped)
                    assertEquals("Opened meanwhile.doc", requireNotNull(current).title); assertNull(current.hash)
                }
                else -> {
                    assertTrue(result.complete); assertEquals(1L, result.discovered); assertEquals(1L, result.indexed)
                    assertEquals("Renamed", requireNotNull(current).title); assertEquals(MessageDigest.getInstance("SHA-256").digest(document.readBytes()).hex(), current.hash)
                    if (scenario == "changed-hint") assertNotEquals(digest, current.hash)
                    assertEquals("application/msword", current.mimeType); assertEquals(LibraryCategory.DOCUMENTS, current.category)
                    assertEquals(if (scenario.endsWith("hint")) 3 else 4, opens)
                }
            }
            assertFalse(context.getDatabasePath("imports.sqlite").exists())
            assertFalse(context.getDatabasePath("documents.db").exists())
            if (scenario != "changed-hint") assertArrayEquals(original.copyOf(size), document.readBytes())
            assertArrayEquals(replacementBytes.copyOf(size), replacement.readBytes())
        }
    }

    @Test fun removedStandaloneFormatsAreExcludedWithoutDeletingFiles() = fixture { context, catalog ->
        val root = File(context.root, "unsupported").apply { mkdirs() }
        val rejected = mapOf(
            "notes.txt" to "Ordinary text", "fake.doc" to "Not Word", "graph.dot" to "digraph G { a -> b }",
            "data.json" to "{\"value\":1}", "table.csv" to "name,value\nitem,1", "main.kt" to "fun main() = Unit",
            "mail.eml" to "Subject: Mail\nContent-Type: text/plain\n\nMessage", "event.ics" to "BEGIN:VCALENDAR\nEND:VCALENDAR",
            "certificate.pem" to "-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----",
            "scene.obj" to "v 0 0 0", "picture.svg" to "<svg xmlns=\"http://www.w3.org/2000/svg\"/>")
            .map { (name, text) -> File(root, name).apply { writeText(text) } }
        val renamed = File(root, "actual-document.json")
        context.assets.open("examples/office/office-guide.pdf").use { input -> renamed.outputStream().use(input::copyTo) }
        val result = LibraryIndex(context, catalog).scanRoots(listOf(Uri.fromFile(root)), CancellationSignal())
        assertTrue(result.complete); assertEquals(1L, result.indexed)
        for (file in rejected) {
            assertNull(file.name, catalog.location(Uri.fromFile(file).toString()))
            assertTrue("Exclusion never deletes user files", file.isFile)
        }
        assertEquals("application/pdf", catalog.location(Uri.fromFile(renamed).toString())!!.mimeType)
        assertTrue(renamed.isFile)
    }

    @Test fun savedPreviewPackagesAndBinaryTemplatesKeepTheirOwnGroups() = fixture { context, catalog ->
        val root = File(context.root, "families").apply { mkdirs() }
        val expected = mapOf("dot" to LibraryCategory.DOCUMENTS, "pages" to LibraryCategory.DOCUMENTS,
            "numbers" to LibraryCategory.SPREADSHEETS, "key" to LibraryCategory.PRESENTATIONS)
        val files = expected.mapKeys { (extension, _) ->
            File(root, "office-guide.$extension").also { file ->
                context.assets.open("examples/office/${file.name}").use { input -> file.outputStream().use(input::copyTo) }
            }
        }
        val graph = File(root, "graph.dot").apply { writeText("digraph AllDoc { library -> document; }") }
        // A matching preview path and real signature alone must not identify an unrelated ZIP as iWork.
        val unrelated = File(root, "archive.zip")
        ZipOutputStream(unrelated.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("QuickLook/Preview.pdf")); zip.write("%PDF-1.7\n".toByteArray()); zip.closeEntry()
        }
        val index = LibraryIndex(context, catalog)
        val result = index.scanRoots(listOf(Uri.fromFile(root)), CancellationSignal())
        assertTrue(result.complete); assertEquals(4L, result.indexed)
        for ((file, category) in files) {
            val document = requireNotNull(catalog.location(Uri.fromFile(file).toString()))
            assertEquals(category, document.category)
            assertEquals(MessageDigest.getInstance("SHA-256").digest(file.readBytes()).hex(), document.hash)
        }
        assertNull(catalog.location(Uri.fromFile(graph).toString()))
        assertNull(catalog.location(Uri.fromFile(unrelated).toString()))
        catalog.setHiddenTypes(setOf(LibraryCategory.PRESENTATIONS))
        assertEquals(3L, index.scanRoots(listOf(Uri.fromFile(root)), CancellationSignal()).indexed)
        assertNull(catalog.location(Uri.fromFile(graph).toString()))
        assertNotNull(catalog.location(Uri.fromFile(File(root, "office-guide.dot")).toString()))
        catalog.setHiddenTypes(setOf(LibraryCategory.DOCUMENTS))
        assertEquals(2L, index.scanRoots(listOf(Uri.fromFile(root)), CancellationSignal()).indexed)
        assertNull(catalog.location(Uri.fromFile(File(root, "office-guide.dot")).toString()))
        assertNull(catalog.location(Uri.fromFile(File(root, "office-guide.pages")).toString()))
        assertNotNull(catalog.location(Uri.fromFile(File(root, "office-guide.numbers")).toString()))
        assertNotNull(catalog.location(Uri.fromFile(File(root, "office-guide.key")).toString()))
    }

    @Test fun failedConfigurationReadReleasesTheProcessScanGuard() = fixture { context, catalog ->
        val closed = LibraryCatalog(context, "closed.db").apply { close() }
        try {
            LibraryIndex(context, closed).scanRoots(emptyList(), CancellationSignal())
            fail("A closed catalog must fail the configuration read")
        } catch (failure: IllegalStateException) {
            assertEquals("Library catalog is closed.", failure.message)
        }
        val root = File(context.root, "retry").apply { mkdirs() }
        File(root, "Readable.md").writeText("The next scan must still run.")
        val retried = LibraryIndex(context, catalog).scanRoots(listOf(Uri.fromFile(root)), CancellationSignal())
        assertTrue(retried.complete); assertEquals(1L, retried.indexed)
    }

    @Test fun localStatTokensRejectChangedBytesEvenWhenSizeAndDateWereRestored() = fixture { context, _ ->
        val file = File(context.root, "stat.md").apply { writeText("first") }
        val uri = Uri.fromFile(file)
        val token = LibraryInput.stamp(uri)
        val date = file.lastModified()
        assertEquals(hash("first"), LibraryInput.hash(context, uri, CancellationSignal(), 5, token))
        file.writeText("other"); assertTrue(file.setLastModified(date))
        if (token != null) {
            assertNotEquals(token, LibraryInput.stamp(uri))
            try { LibraryInput.hash(context, uri, CancellationSignal(), 5, token); fail("A stale stat token admitted changed bytes") }
            catch (_: java.io.IOException) { }
        }
        assertEquals(hash("other"), LibraryInput.hash(context, uri, CancellationSignal(), 5))
    }

    @Test(timeout = 5_000) fun localAdmissionNeverWaitsForSpecialFilesOrFollowsSymlinks() = fixture { context, _ ->
        val regular = File(context.root, "regular.md").apply { writeText("hello") }
        LibraryInput.open(context, Uri.fromFile(regular), CancellationSignal()).use { assertEquals(5L, it.statSize) }
        val link = File(context.root, "link.md")
        android.system.Os.symlink(regular.path, link.path)
        val pipe = File(context.root, "pipe.md")
        android.system.Os.mkfifo(pipe.path, 0x180) // Private test fixture, no writer.
        for (file in listOf(link, pipe, context.root)) {
            try { LibraryInput.open(context, Uri.fromFile(file), CancellationSignal()).close(); fail("Special file was admitted") }
            catch (_: java.io.IOException) { }
        }
        try {
            LibraryInput.open(context, Uri.fromFile(regular), CancellationSignal().apply { cancel() }).close()
            fail("Cancelled admission succeeded")
        } catch (_: OperationCanceledException) { }
    }

}
