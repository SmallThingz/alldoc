package app.smallthingz.alldoc.state

import android.os.CancellationSignal
import android.os.OperationCanceledException
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryCatalogTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun hash(value: Int) = value.toString(16).padStart(64, '0')
    private fun file(name: String, bytes: Long = 12) = LibraryFile("content://catalog/$name", name, "text/markdown", bytes, 50)
    private inline fun database(limit: Int = 32, block: (LibraryCatalog, String) -> Unit) {
        val name = "catalog-${UUID.randomUUID()}.db"
        try { LibraryCatalog(context, name, limit).use { block(it, name) } }
        finally { context.deleteDatabase(name) }
    }

    @Test fun sameReadMimeAndHashResolveAtomicallyWithoutChangingOtherAliases() = database { catalog, _ ->
        val first = requireNotNull(catalog.discover(file("First.pdf").copy(mimeType = "application/pdf")))
        val original = requireNotNull(catalog.resolve(first, hash(1)))
        val alias = requireNotNull(catalog.discover(file("Alias.pdf").copy(mimeType = "application/pdf")))
        val merged = requireNotNull(catalog.resolve(alias, hash(1)))
        val changed = requireNotNull(catalog.resolve(merged, hash(2), mime = "application/epub+zip"))
        assertNotEquals(original.id, changed.id)
        assertEquals("application/epub+zip", changed.mimeType); assertEquals(LibraryCategory.BOOKS, changed.category)
        assertEquals(original, catalog.location(original.uri))
        assertEquals(listOf(original.uri), catalog.locationsForHash(hash(1)))
        assertNull("A stale token cannot attach MIME or digest to the replacement", catalog.resolve(merged, hash(3), mime = "text/plain"))
        assertEquals(changed, catalog.location(changed.uri))
        val corrected = requireNotNull(catalog.resolve(original, hash(1), mime = "application/epub+zip"))
        assertEquals(original.id, corrected.id); assertEquals(original.hash, corrected.hash)
        assertTrue(corrected.revision > original.revision)
        assertEquals(LibraryCategory.BOOKS, corrected.category)
        assertNull(catalog.resolve(original, hash(4), mime = "application/pdf"))
        val other = requireNotNull(catalog.discover(file("Other.pdf").copy(mimeType = "application/pdf")))
        val joined = requireNotNull(catalog.resolve(other, hash(2), mime = "application/epub+zip"))
        assertEquals(changed.id, joined.id); assertEquals("application/epub+zip", joined.mimeType)
        assertTrue("MIME changes advance even an unhashed location's token", joined.revision > other.revision)
        assertEquals(setOf(changed.uri, joined.uri), catalog.locationsForHash(hash(2)).toSet())
    }

    @Test fun knownRediscoveryRequiresTheOriginalDigestRevisionAndScan() = database { catalog, _ ->
        val root = "content://catalog/tree"
        val metadata = file("Original.doc").copy(mimeType = "application/msword")
        val generation = catalog.beginScan(root)
        val original = requireNotNull(catalog.resolve(requireNotNull(catalog.discover(metadata, root, generation)), hash(1)))
        val renamed = metadata.copy(title = "Renamed", mimeType = "application/octet-stream")
        assertNull(catalog.rediscoverKnown(original, renamed, hash(2), root, generation))
        val refreshed = requireNotNull(catalog.rediscoverKnown(original, renamed, hash(1), root, generation))
        assertEquals(original.id, refreshed.id); assertEquals(original.hash, refreshed.hash)
        assertEquals("Renamed", refreshed.title); assertEquals("application/msword", refreshed.mimeType)
        assertTrue(refreshed.revision > original.revision)
        assertTrue(catalog.verified(refreshed, root, generation))
        assertEquals(hash(1), catalog.expectedScanHash(refreshed, root, generation))
        assertNull(catalog.rediscoverKnown(original, metadata, hash(1), root, generation))
        assertEquals(listOf(refreshed), catalog.scanPending(root, generation, verifiedOnly = true))
        val ordinary = requireNotNull(catalog.discover(renamed.copy(mimeType = "application/msword"), root, generation))
        assertTrue(catalog.verified(ordinary, root, generation))
        assertNull(catalog.expectedScanHash(ordinary, root, generation))
        catalog.beginScan(root)
        assertNull(catalog.rediscoverKnown(refreshed, metadata, hash(1), root, generation))
        assertEquals(refreshed, catalog.location(original.uri))
    }

    private fun legacyMembership(db: android.database.sqlite.SQLiteDatabase) {
        db.execSQL("ALTER TABLE membership RENAME TO membership_new")
        db.execSQL("CREATE TABLE membership(root TEXT NOT NULL REFERENCES roots(uri) ON DELETE CASCADE," +
            "uri TEXT NOT NULL REFERENCES locations(uri) ON DELETE CASCADE,generation INTEGER NOT NULL," +
            "checked INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(root,uri)) WITHOUT ROWID")
        db.execSQL("INSERT INTO membership SELECT root,uri,generation,checked FROM membership_new")
        db.execSQL("DROP TABLE membership_new")
        db.execSQL("CREATE INDEX library_membership_uri ON membership(uri)")
        db.execSQL("CREATE INDEX library_membership_generation ON membership(root,generation,uri)")
    }

    @Test fun versionSixAddsScanExpectationsWithoutChangingExistingState() = database { catalog, name ->
        val root = "content://catalog/tree"
        catalog.addRoot(root)
        catalog.setHiddenTypes(setOf(LibraryCategory.BOOKS))
        val generation = catalog.beginScan(root)
        val document = requireNotNull(catalog.resolve(requireNotNull(catalog.discover(file("Retained.md"), root, generation)), hash(3)))
        assertTrue(catalog.verified(document, root, generation))
        val page = catalog.pageSnapshot(0, 10)
        catalog.close()
        context.openOrCreateDatabase(name, 0, null).use { db ->
            legacyMembership(db)
            db.version = 6
        }
        LibraryCatalog(context, name).use { migrated ->
            assertEquals(page, migrated.pageSnapshot(0, 10)); assertEquals(listOf(root), migrated.roots())
            assertEquals(setOf(LibraryCategory.BOOKS), migrated.hiddenTypes())
            assertEquals(listOf(document), migrated.scanPending(root, generation, verifiedOnly = true))
            assertNull(migrated.expectedScanHash(document, root, generation))
            assertEquals(generation + 1, migrated.beginScan(root))
        }
    }

    @Test fun aliasesMergeButChangedLocationsAndLateDigestsKeepTheirOwnIdentity() = database { catalog, name ->
        val empty = catalog.pageSnapshot(0, 200)
        assertTrue(empty.rows.isEmpty())
        val first = requireNotNull(catalog.discover(file("First.md")))
        val discoveredGeneration = catalog.pageSnapshot(0, 200).generation
        assertNotEquals(empty.generation, discoveredGeneration)
        val verified = requireNotNull(catalog.resolve(first, hash(1)))
        assertEquals(first.id, verified.id); assertEquals(first.revision, verified.revision)
        val verifiedGeneration = catalog.pageSnapshot(0, 200).generation
        assertNotEquals("Resolving metadata must invalidate unverified page content", discoveredGeneration, verifiedGeneration)
        assertEquals(verified, catalog.discover(file("First.md")))
        assertEquals("Unchanged metadata must preserve page generation", verifiedGeneration, catalog.pageSnapshot(0, 200).generation)
        val alias = requireNotNull(catalog.discover(file("Alias.md")))
        val beforeMerge = catalog.pageSnapshot(0, 200)
        assertEquals(2, beforeMerge.rows.size)
        val merged = requireNotNull(catalog.resolve(alias, hash(1).uppercase()))
        assertEquals(verified.id, merged.id); assertEquals(1, catalog.count())
        val afterMerge = catalog.pageSnapshot(0, 200)
        assertNotEquals("Merging an unknown alias shifts later offsets", beforeMerge.generation, afterMerge.generation)
        assertEquals(listOf(verified.id), afterMerge.rows.map { it.id })
        assertEquals(setOf(first.uri, alias.uri), catalog.locationsForHash(hash(1)).toSet())

        val replacement = requireNotNull(catalog.discover(file("Alias.md", 13)))
        assertNotEquals(merged.id, replacement.id); assertNull(replacement.hash)
        val replacementGeneration = catalog.pageSnapshot(0, 200).generation
        assertNotEquals(afterMerge.generation, replacementGeneration)
        assertNull("An old digest must not replace new metadata", catalog.resolve(merged, hash(9)))
        assertEquals("Rejected stale digests cannot churn paging", replacementGeneration, catalog.pageSnapshot(0, 200).generation)
        val changed = requireNotNull(catalog.resolve(replacement, hash(2)))
        assertEquals(listOf(first.uri), catalog.locationsForHash(hash(1)))
        assertEquals(listOf(alias.uri), catalog.locationsForHash(hash(2)))
        assertTrue(catalog.invalidate(changed))
        val pending = requireNotNull(catalog.location(alias.uri))
        assertEquals(changed.id, pending.id); assertNull(pending.hash)
        assertEquals(-1L, pending.sizeBytes); assertTrue(pending.revision > changed.revision)
        val pendingGeneration = catalog.pageSnapshot(0, 200).generation
        assertFalse(catalog.invalidate(changed)); assertNull(catalog.resolve(changed, hash(2)))
        assertEquals(pendingGeneration, catalog.pageSnapshot(0, 200).generation)
        assertEquals(hash(1), catalog.location(first.uri)!!.hash)
        assertEquals(listOf(pending.id), catalog.pending().map { it.id })
        LibraryCatalog(context, name).use { reopened ->
            assertEquals(2, reopened.count()); assertEquals(pending, reopened.location(alias.uri))
            assertEquals("The snapshot epoch is shared by catalog connections", pendingGeneration, reopened.pageSnapshot(0, 200).generation)
        }
        val replacedPending = requireNotNull(catalog.discover(file("Alias.md", 14)))
        assertFalse("A missing-source callback cannot delete a newer revision", catalog.removeLocation(pending))
        assertEquals(replacedPending, catalog.location(alias.uri))
        val beforeRemoval = catalog.pageSnapshot(0, 200).generation
        assertTrue(catalog.removeLocation(replacedPending))
        assertNotEquals("Removing a row must invalidate existing offset pages", beforeRemoval, catalog.pageSnapshot(0, 200).generation)
        assertEquals(1, catalog.count()); assertEquals(verified, catalog.location(first.uri))
        val unresolved = requireNotNull(catalog.discover(file("Race.md")))
        val winner = requireNotNull(catalog.resolve(unresolved.copy(hash = hash(3)), hash(3)))
        assertNull("A late differing digest must not replace the first resolved bytes", catalog.resolve(unresolved, hash(4)))
        assertNull(catalog.resolve(unresolved.copy(hash = hash(4)), hash(4)))
        assertEquals(winner, catalog.resolve(unresolved, hash(3)))
        assertEquals(hash(3), catalog.location(unresolved.uri)!!.hash)
        val newest = requireNotNull(catalog.resolve(winner, hash(4)))
        assertFalse("Evicting an old hash must not remove newer bytes at the same URI", catalog.removeLocation(newest.uri, hash(3)))
        assertEquals(newest, catalog.location(newest.uri))
        val unverifiedReplacement = requireNotNull(catalog.discover(file("Race.md", 13)))
        assertNull(unverifiedReplacement.hash)
        assertTrue(unverifiedReplacement.revision > newest.revision)
        assertFalse("An old eviction receipt cannot remove newer unresolved bytes", catalog.removeLocation(newest.uri, hash(4)))
        assertEquals(unverifiedReplacement, catalog.location(newest.uri))
        val verifiedReplacement = requireNotNull(catalog.resolve(unverifiedReplacement, hash(5)))
        assertTrue(catalog.removeLocation(verifiedReplacement.uri, hash(5)))
        val beforeRename = catalog.pageSnapshot(0, 200)
        val renamed = requireNotNull(catalog.discover(file("First.md").copy(title = "Last.md")))
        val afterRename = catalog.pageSnapshot(0, 200)
        assertEquals(first.id, renamed.id)
        assertNotEquals("Renaming a title changes the ordered page snapshot", beforeRename.generation, afterRename.generation)
        assertEquals(listOf("Last.md"), afterRename.rows.map { it.title })
    }

    @Test fun scanGenerationsPruneOnlyCompletedMembershipAndPreserveOtherRootsAndPins() = database { catalog, _ ->
        val a = "content://root/a"; val b = "content://root/b"
        catalog.addRoot(a); catalog.addRoot(b)
        val a1 = catalog.beginScan(a)
        var changes = 0
        val initial = catalog.discoverBatch(listOf(file("kept"), file("shared"), file("pinned")), a, a1,
            onChanged = { changes++ })
        assertEquals("One committed metadata batch has one notification", 1, changes)
        val initialGeneration = catalog.pageSnapshot(0, 200).generation
        catalog.discoverBatch(listOf(file("kept"), file("shared"), file("pinned")), a, a1,
            onChanged = { changes++ })
        assertEquals("Unchanged metadata does not notify", 1, changes)
        assertEquals(initialGeneration, catalog.pageSnapshot(0, 200).generation)
        val previouslyVerified = requireNotNull(catalog.resolve(initial.first(), hash(1), a, a1))
        val b1 = catalog.beginScan(b)
        catalog.discover(file("shared"), b, b1)
        catalog.discover(file("pinned"))
        val beforeNoOpFinish = catalog.pageSnapshot(0, 200).generation
        assertTrue(catalog.finishScan(a, a1, onChanged = { changes++ }))
        assertTrue(catalog.finishScan(b, b1, onChanged = { changes++ }))
        assertEquals("A completed scan without pruning does not notify", 1, changes)
        assertEquals("Membership-only completion must not churn pages", beforeNoOpFinish, catalog.pageSnapshot(0, 200).generation)
        val superseded = catalog.beginScan(a)
        val current = catalog.beginScan(a)
        val currentGeneration = catalog.pageSnapshot(0, 200).generation
        assertTrue(catalog.discoverBatch(listOf(file("stale")), a, superseded).isEmpty())
        assertNull(catalog.resolve(initial.first(), hash(1), a, superseded))
        assertFalse(catalog.finishScan(a, superseded))
        catalog.discoverBatch(listOf(file("kept")), a, current, onChanged = { changes++ })
        assertEquals("A new scan generation alone does not change document metadata", 1, changes)
        assertEquals("Superseded and unchanged batches must retain the current snapshot", currentGeneration, catalog.pageSnapshot(0, 200).generation)
        val refresh = catalog.scanPending(a, current).single()
        assertEquals(hash(1), refresh.hash)
        val refreshed = requireNotNull(catalog.resolve(refresh, hash(2), a, current))
        assertEquals(hash(2), refreshed.hash); assertTrue(refreshed.revision > previouslyVerified.revision)
        val beforePrune = catalog.pageSnapshot(0, 200).generation
        assertTrue(catalog.finishScan(a, current, onChanged = { changes++ }))
        assertEquals("Removing a missing location notifies once after commit", 2, changes)
        assertNotEquals("Completed pruning shifts offset boundaries", beforePrune, catalog.pageSnapshot(0, 200).generation)
        assertEquals(2, catalog.count())
        assertNull("A completed scan removes a missing alias even when it was opened before", catalog.location(file("pinned").uri))
        catalog.discover(file("kept")) // Still present, and now independently opened.
        val b2 = catalog.beginScan(b)
        try { catalog.finishScan(b, b2, CancellationSignal().apply { cancel() }); fail("Cancelled pruning succeeded") }
        catch (_: OperationCanceledException) { }
        assertNotNull(catalog.location(file("shared").uri))
        assertTrue(catalog.finishScan(b, b2))
        assertNull(catalog.location(file("shared").uri)); assertNotNull(catalog.location(file("kept").uri))
        assertTrue(catalog.removeRoot(a)); assertNotNull("Removing a folder preserves an independently opened present file", catalog.location(file("kept").uri))
        assertEquals(1, catalog.count()); assertEquals(listOf(b), catalog.roots())
    }

    @Test fun locationLimitAndStablePagesStayCorrectAfterAliasMergesAndRemoval() = database(3) { catalog, _ ->
        val beta = requireNotNull(catalog.discover(file("Beta")))
        catalog.resolve(beta, hash(1))
        val alias = requireNotNull(catalog.discover(file("Alias")))
        catalog.resolve(alias, hash(1))
        val alpha = requireNotNull(catalog.discover(file("alpha")))
        assertEquals(2, catalog.count())
        val fullSnapshot = catalog.pageSnapshot(0, 200)
        try { catalog.discover(file("Overflow")); fail("Location cap ignored after alias merge") }
        catch (_: IllegalStateException) { }
        assertNull(catalog.location(file("Overflow").uri)); assertEquals(2, catalog.count())
        var changes = 0
        try {
            catalog.discoverBatch(listOf(file("alpha", 13), file("Overflow")), onChanged = { changes++ })
            fail("A partially admitted batch exceeded the location cap")
        } catch (_: IllegalStateException) { }
        assertEquals("Rolled-back metadata must not trigger paging refresh", 0, changes)
        assertEquals(alpha, catalog.location(alpha.uri)); assertNull(catalog.location(file("Overflow").uri))
        assertEquals("A rollback must restore both epoch and row content", fullSnapshot, catalog.pageSnapshot(0, 200))
        val pages = catalog.page(0, 1) + catalog.page(1, 1)
        assertEquals(listOf(alpha.id, beta.id), pages.map { it.id })
        assertTrue(catalog.page(2, 1).isEmpty())
        val emptyTail = catalog.pageSnapshot(2, 1)
        assertTrue(emptyTail.rows.isEmpty()); assertEquals(fullSnapshot.generation, emptyTail.generation)
        assertTrue(catalog.removeLocation(alias.uri))
        assertNotNull(catalog.discover(file("Overflow"))); assertEquals(3, catalog.count())
        for ((offset, limit) in listOf(-1 to 1, 0 to 0, 0 to 201)) {
            try { catalog.page(offset, limit); fail("Invalid page bounds accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun groupedFuzzyPagesStayBoundedAndFiltersPersist() = database(1024) { catalog, name ->
        val files = (0 until 768).map { number ->
            val title = if (number % 17 == 0) "Résumé annual ${number.toString().padStart(4, '0')}.md"
                else "Document ${number.toString().padStart(4, '0')}.md"
            file(title).copy(uri = "content://catalog/$number")
        } + listOf(file("Deck.pptx").copy(mimeType = null), file("Book.epub").copy(mimeType = "application/epub+zip"),
            file("Budget.xlsx").copy(mimeType = null), file("PDF.pdf").copy(mimeType = "application/pdf"))
        files.chunked(128).forEach { catalog.discoverBatch(it) }
        val grouped = catalog.pageSnapshot(768, 20).rows
        assertEquals(listOf(LibraryCategory.DOCUMENTS, LibraryCategory.PRESENTATIONS, LibraryCategory.SPREADSHEETS, LibraryCategory.BOOKS),
            grouped.map { it.category })
        val query = LibraryQuery("resume anl", LibraryCategory.DOCUMENTS)
        val all = catalog.pageSnapshot(0, 200, query).rows
        assertEquals(46, all.size)
        assertEquals(all, (0 until all.size step 7).flatMap { catalog.pageSnapshot(it, 7, query).rows })
        assertEquals(all.drop(3).take(5), catalog.pageSnapshot(3, 5, query).rows)
        assertTrue(catalog.pageSnapshot(0, 20, query.copy(excluded = setOf(LibraryCategory.DOCUMENTS))).rows.isEmpty())
        val first = catalog.pageSnapshot(0, 1).generation
        catalog.setHiddenTypes(setOf(LibraryCategory.BOOKS, LibraryCategory.PRESENTATIONS))
        assertNotEquals(first, catalog.pageSnapshot(0, 1).generation)
        LibraryCatalog(context, name).use { reopened ->
            assertEquals(setOf(LibraryCategory.BOOKS, LibraryCategory.PRESENTATIONS), reopened.hiddenTypes())
        }
        var checks = 0
        try {
            catalog.pageSnapshot(0, 20, LibraryQuery("not-present")) {
                if (++checks == 5) throw OperationCanceledException()
            }
            fail("A superseded query continued through the whole index")
        } catch (_: OperationCanceledException) { }
        assertEquals(5, checks)
        checks = 0
        try {
            catalog.pageSnapshot(0, 20, LibraryQuery("another-query")) {
                if (++checks == 2) catalog.discover(file("New.md"))
            }
            fail("Mixed catalog generations were returned")
        } catch (_: ConcurrentModificationException) { }
    }

    @Test fun validatedMembershipCannotBeReusedAcrossDiscoveryRevisions() = database { catalog, _ ->
        val root = "content://root/validation"
        val generation = catalog.beginScan(root)
        val first = requireNotNull(catalog.discover(file("Candidate.pdf"), root, generation))
        assertTrue(catalog.scanPending(root, generation, verifiedOnly = true).isEmpty())
        assertTrue(catalog.verified(first, root, generation))
        assertEquals(listOf(first), catalog.scanPending(root, generation, verifiedOnly = true))
        val replaced = requireNotNull(catalog.discover(file("Candidate.pdf", 13), root, generation))
        assertFalse(catalog.verified(first, root, generation))
        assertTrue(catalog.scanPending(root, generation, verifiedOnly = true).isEmpty())
        assertTrue(catalog.verified(replaced, root, generation))
        assertTrue(catalog.invalidate(replaced))
        assertTrue("Cover invalidation must revoke the old probe token", catalog.scanPending(root, generation, verifiedOnly = true).isEmpty())
        val refreshed = requireNotNull(catalog.discover(file("Candidate.pdf", 13), root, generation))
        assertTrue(catalog.verified(refreshed, root, generation))
        catalog.discover(file("Candidate.pdf", 14))
        assertTrue("Independent discovery must revoke the old probe token", catalog.scanPending(root, generation, verifiedOnly = true).isEmpty())
        val current = requireNotNull(catalog.location(refreshed.uri))
        assertEquals(1, catalog.verifyBatch(listOf(current to "application/pdf"), root, generation))
        val corrected = catalog.scanPending(root, generation, verifiedOnly = true).single()
        assertTrue(corrected.revision > current.revision)
        assertEquals("application/pdf", corrected.mimeType)
        val next = catalog.beginScan(root)
        catalog.discover(file("Candidate.pdf", 14), root, next)
        assertTrue(catalog.scanPending(root, next, verifiedOnly = true).isEmpty())
        assertFalse(catalog.verified(replaced, root, generation))
    }

    @Test fun unchangedScansDoNotInvalidatePagesAndScanIdsStayUnique() = database { catalog, _ ->
        val a = "content://root/generation-a"; val b = "content://root/generation-b"
        catalog.addRoot(a); catalog.addRoot(b)
        var generation = catalog.beginScan(a)
        val metadata = file("Shared.md")
        val document = requireNotNull(catalog.discover(metadata, a, generation))
        catalog.resolve(document, hash(1), a, generation)
        val before = catalog.pageSnapshot(0, 20)
        repeat(3) {
            val previous = generation
            generation = catalog.beginScan(a)
            assertEquals(previous + 1, generation)
            assertEquals("Beginning a scan must not invalidate unchanged pages", before, catalog.pageSnapshot(0, 20))
            assertNull(catalog.resolve(document, hash(1), a, previous))
            catalog.discover(metadata, a, generation)
            assertTrue(catalog.finishScan(a, generation))
            assertEquals(before, catalog.pageSnapshot(0, 20))
        }
        val other = catalog.beginScan(b)
        assertTrue(other > generation)
        catalog.discover(metadata, b, other)
        assertTrue(catalog.finishScan(b, other))
        assertTrue(catalog.removeRoot(a))
        assertFalse(catalog.finishScan(a, generation))
        assertEquals("Removing one membership leaves the shared document page unchanged", before, catalog.pageSnapshot(0, 20))
        assertTrue(catalog.removeRoot(b)); assertEquals(0, catalog.count())
        assertNotEquals(before.generation, catalog.pageSnapshot(0, 20).generation)
    }

    @Test fun removedAndReaddedRootsRejectOldDiscoveryAndResolutionTokens() = database { catalog, _ ->
        val root = "content://root/readded"
        catalog.addRoot(root)
        assertTrue(catalog.discoverBatch(listOf(file("Unstarted.md")), root, 0).isEmpty())
        val old = catalog.beginScan(root)
        val previous = requireNotNull(catalog.discover(file("Shared.md"), root, old))
        assertTrue(catalog.removeRoot(root)); assertEquals(0, catalog.count())
        catalog.addRoot(root)
        assertTrue(catalog.discoverBatch(listOf(file("Stale.md")), root, old).isEmpty())
        val current = catalog.beginScan(root)
        assertTrue(current > old)
        val replacement = requireNotNull(catalog.discover(file("Shared.md", 13), root, current))
        assertTrue(catalog.discoverBatch(listOf(file("Shared.md", 12)), root, old).isEmpty())
        assertNull(catalog.resolve(previous, hash(1), root, old))
        assertFalse(catalog.finishScan(root, old))
        assertEquals(replacement, catalog.location(replacement.uri))
    }

    @Test fun overflowingScanCounterFailsWithoutWrappingOrChangingConfiguration() = database { catalog, name ->
        val root = "content://root/overflow"
        catalog.addRoot(root)
        val before = catalog.pageSnapshot(0, 20)
        context.openOrCreateDatabase(name, 0, null).use { db ->
            db.execSQL("UPDATE configuration SET scan=? WHERE id=1", arrayOf<Any?>(Long.MAX_VALUE))
        }
        for (remove in listOf(false, true)) {
            try {
                if (remove) catalog.removeRoot(root) else catalog.beginScan(root)
                fail("The scan counter wrapped")
            } catch (_: IllegalStateException) { }
            assertEquals(listOf(root), catalog.roots())
            assertEquals(before, catalog.pageSnapshot(0, 20))
        }
    }

    @Test fun versionTwoMigrationSeedsTheCounterAboveEveryPreviouslyIssuedScan() {
        val name = "catalog-v2-${UUID.randomUUID()}.db"
        val root = "content://old/root"
        try {
            LibraryCatalog(context, name).use { catalog -> catalog.addRoot(root); catalog.setHiddenTypes(setOf(LibraryCategory.BOOKS)) }
            context.openOrCreateDatabase(name, 0, null).use { db ->
                db.execSQL("ALTER TABLE configuration RENAME TO newer_configuration")
                db.execSQL("CREATE TABLE configuration(id INTEGER PRIMARY KEY CHECK(id=1),hidden INTEGER NOT NULL)")
                db.execSQL("INSERT INTO configuration SELECT id,hidden FROM newer_configuration")
                db.execSQL("DROP TABLE newer_configuration")
                db.execSQL("UPDATE roots SET generation=17")
                db.execSQL("UPDATE ledger SET revision=9")
                legacyMembership(db)
                db.version = 2
            }
            LibraryCatalog(context, name).use { migrated ->
                val before = migrated.pageSnapshot(0, 20)
                assertEquals(setOf(LibraryCategory.BOOKS), migrated.hiddenTypes())
                assertEquals(18L, migrated.beginScan(root))
                assertEquals(before, migrated.pageSnapshot(0, 20))
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun versionOneMigrationPreservesHashesLocationsAndConfiguredRoots() {
        val name = "catalog-migration-${UUID.randomUUID()}.db"
        try {
            context.openOrCreateDatabase(name, 0, null).use { db ->
                db.execSQL("CREATE TABLE ledger(id INTEGER PRIMARY KEY,documents INTEGER NOT NULL,locations INTEGER NOT NULL,revision INTEGER NOT NULL)")
                db.execSQL("INSERT INTO ledger VALUES(1,1,1,9)")
                db.execSQL("CREATE TABLE documents(id INTEGER PRIMARY KEY AUTOINCREMENT,hash TEXT UNIQUE,uri TEXT NOT NULL,title TEXT NOT NULL COLLATE NOCASE)")
                db.execSQL("CREATE INDEX library_title ON documents(title COLLATE NOCASE,id)")
                db.execSQL("CREATE TABLE locations(uri TEXT PRIMARY KEY,document INTEGER NOT NULL REFERENCES documents(id),title TEXT NOT NULL,mime TEXT,bytes INTEGER NOT NULL,modified INTEGER NOT NULL,revision INTEGER NOT NULL,pinned INTEGER NOT NULL) WITHOUT ROWID")
                db.execSQL("CREATE TABLE roots(uri TEXT PRIMARY KEY,generation INTEGER NOT NULL,configured INTEGER NOT NULL) WITHOUT ROWID")
                db.execSQL("CREATE TABLE membership(root TEXT NOT NULL,uri TEXT NOT NULL,generation INTEGER NOT NULL,PRIMARY KEY(root,uri)) WITHOUT ROWID")
                db.execSQL("INSERT INTO documents VALUES(1,?,'content://old/slide','Talk.pptx')", arrayOf(hash(1)))
                db.execSQL("INSERT INTO locations VALUES('content://old/slide',1,'Talk.pptx','application/vnd.openxmlformats-officedocument.presentationml.presentation',12,20,9,0)")
                db.execSQL("INSERT INTO roots VALUES('content://old/root',9,1)")
                db.execSQL("INSERT INTO membership VALUES('content://old/root','content://old/slide',9)")
                db.version = 1
            }
            LibraryCatalog(context, name).use { migrated ->
                val row = migrated.pageSnapshot(0, 20).rows.single()
                assertEquals(hash(1), row.hash); assertEquals(LibraryCategory.PRESENTATIONS, row.category)
                assertEquals("content://old/slide", row.uri); assertNull(row.stamp)
                assertEquals(listOf("content://old/root"), migrated.roots()); assertTrue(migrated.hiddenTypes().isEmpty())
                assertTrue(migrated.scanPending("content://old/root", 9, verifiedOnly = true).isEmpty())
                assertEquals(1, migrated.count())
                val before = migrated.pageSnapshot(0, 20)
                assertEquals(10L, migrated.beginScan("content://old/root"))
                assertEquals(before, migrated.pageSnapshot(0, 20))
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun olderMigrationsRemoveUnsupportedLocationsAndRepairAliasesWithoutChangingScanTokens() = listOf(3, 4, 5).forEach { version ->
        val name = "catalog-v$version-${UUID.randomUUID()}.db"
        val root = "content://v3/root"
        val alias = "content://v3/alias-numbers"
        val retainedAlias = "content://v3/retained-markdown"
        val removed = setOf(2, 4, 128, 130, 258, 259, 261)
        val physical = java.io.File(context.cacheDir, "retained-${UUID.randomUUID()}.png").apply { writeText("Original source bytes") }
        val stamp = LibraryStamp(1, 2, 12, 20, 30, 40, 50)
        val fixtures = mapOf(
            1 to Triple("Template.dot", "application/x-ole-storage", LibraryCategory.DOCUMENTS),
            2 to Triple("Invitation.eml", "text/plain", LibraryCategory.MAIL),
            3 to Triple("Disguised.txt", "application/pdf", LibraryCategory.DOCUMENTS),
            4 to Triple("Plain.doc", "text/plain", LibraryCategory.DOCUMENTS),
            5 to Triple("Image.png", "image/png", LibraryCategory.IMAGES),
            6 to Triple("Notes.md", "text/plain", LibraryCategory.DOCUMENTS),
            128 to Triple("Invitation.mime", "text/plain", LibraryCategory.MAIL),
            130 to Triple("Archive.mht", "text/plain", LibraryCategory.MAIL),
            258 to Triple("Text.msg", "text/plain", LibraryCategory.TEXT),
            259 to Triple("Archive.mhtml", "text/plain", LibraryCategory.MAIL),
            129 to Triple("Talk.key", "application/vnd.apple.pages", LibraryCategory.PRESENTATIONS),
            260 to Triple("Budget.numbers", "application/vnd.apple.pages", LibraryCategory.SPREADSHEETS),
            261 to Triple("Diagram.dot", "text/plain", LibraryCategory.CODE),
        )
        try {
            context.openOrCreateDatabase(name, 0, null).use { db ->
                db.beginTransaction()
                try {
                    // Existing v3/v4/v5 layout, including category codes, counters and alias membership.
                    db.execSQL("CREATE TABLE ledger(id INTEGER PRIMARY KEY CHECK(id=1),documents INTEGER NOT NULL,locations INTEGER NOT NULL,revision INTEGER NOT NULL)")
                    db.execSQL("INSERT INTO ledger VALUES(1,0,0,500)")
                    db.execSQL("CREATE TABLE documents(id INTEGER PRIMARY KEY AUTOINCREMENT,hash TEXT UNIQUE,uri TEXT NOT NULL,title TEXT NOT NULL COLLATE NOCASE,category INTEGER NOT NULL)")
                    db.execSQL("CREATE INDEX library_title ON documents(category,title COLLATE NOCASE,id)")
                    db.execSQL("CREATE TABLE locations(uri TEXT PRIMARY KEY,document INTEGER NOT NULL REFERENCES documents(id) ON DELETE CASCADE,title TEXT NOT NULL,mime TEXT,bytes INTEGER NOT NULL CHECK(bytes>=-1),modified INTEGER NOT NULL,revision INTEGER NOT NULL,pinned INTEGER NOT NULL,stamp BLOB) WITHOUT ROWID")
                    db.execSQL("CREATE INDEX library_locations ON locations(document,uri)")
                    db.execSQL("CREATE TABLE roots(uri TEXT PRIMARY KEY,generation INTEGER NOT NULL,configured INTEGER NOT NULL) WITHOUT ROWID")
                    db.execSQL("CREATE TABLE membership(root TEXT NOT NULL REFERENCES roots(uri) ON DELETE CASCADE,uri TEXT NOT NULL REFERENCES locations(uri) ON DELETE CASCADE,generation INTEGER NOT NULL,checked INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(root,uri)) WITHOUT ROWID")
                    db.execSQL("CREATE INDEX library_membership_uri ON membership(uri)")
                    db.execSQL("CREATE INDEX library_membership_generation ON membership(root,generation,uri)")
                    db.execSQL("CREATE TABLE configuration(id INTEGER PRIMARY KEY CHECK(id=1),hidden INTEGER NOT NULL,scan INTEGER NOT NULL)")
                    db.execSQL("INSERT INTO configuration VALUES(1,?,1000)", arrayOf((1 shl LibraryCategory.CODE.code) or (1 shl LibraryCategory.BOOKS.code)))
                    db.execSQL("INSERT INTO roots VALUES(?,17,1)", arrayOf(root))
                    for (table in listOf("documents", "locations")) {
                        db.execSQL("CREATE TRIGGER ${table}_insert AFTER INSERT ON $table BEGIN UPDATE ledger SET $table=$table+1 WHERE id=1; END")
                        db.execSQL("CREATE TRIGGER ${table}_delete AFTER DELETE ON $table BEGIN UPDATE ledger SET $table=$table-1 WHERE id=1; END")
                    }
                    for (id in 1..261) {
                        val item = fixtures[id] ?: Triple("Text-$id.md", "text/plain", LibraryCategory.TEXT)
                        val old = when (id) {
                            1 -> if (version == 3) LibraryCategory.CODE else item.third
                            129, 260 -> if (version == 3) LibraryCategory.DOCUMENTS else item.third
                            2, 128, 130, 259 -> LibraryCategory.TEXT
                            else -> item.third
                        }
                        val uri = if (id == 5) physical.toURI().toString() else "content://v3/$id"
                        db.execSQL("INSERT INTO documents VALUES(?,?,?,?,?)", arrayOf<Any?>(id, hash(id), uri, item.first, old.code))
                        db.execSQL("INSERT INTO locations VALUES(?,?,?,?,12,50,?,0,?)", arrayOf<Any?>(uri, id, item.first, item.second, 100 + id, if (id == 1) stamp.encode() else null))
                        db.execSQL("INSERT INTO membership VALUES(?, ?,17,?)", arrayOf<Any?>(root, uri, 100 + id))
                    }
                    db.execSQL("INSERT INTO locations VALUES(?,129,'Alias.numbers','application/vnd.apple.pages',12,50,999,1,NULL)", arrayOf(alias))
                    db.execSQL("INSERT INTO membership VALUES(?,?,17,999)", arrayOf(root, alias))
                    db.execSQL("INSERT INTO locations VALUES(?,5,'Recovered.md','text/markdown',12,50,998,1,NULL)", arrayOf(retainedAlias))
                    db.execSQL("INSERT INTO membership VALUES(?,?,17,998)", arrayOf(root, retainedAlias))
                    db.version = version
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
            }
            var repairedGeneration = 0L
            LibraryCatalog(context, name).use { migrated ->
                assertEquals(261 - removed.size, migrated.count())
                assertEquals(listOf(root), migrated.roots())
                assertEquals(setOf(LibraryCategory.BOOKS), migrated.hiddenTypes())
                for ((id, item) in fixtures) {
                    if (id in removed) {
                        assertNull(migrated.get(id.toLong()))
                        assertNull(migrated.location("content://v3/$id"))
                        assertTrue(migrated.locationsForHash(hash(id)).isEmpty())
                    } else if (id == 5) {
                        val replacement = requireNotNull(migrated.get(id.toLong()))
                        assertEquals(retainedAlias, replacement.uri)
                        assertEquals("Recovered.md", replacement.title)
                        assertEquals(LibraryCategory.DOCUMENTS, replacement.category)
                        assertEquals(hash(id), replacement.hash)
                        assertEquals(998L, replacement.revision)
                        assertEquals(listOf(retainedAlias), migrated.locationsForHash(hash(id)))
                        assertNull(migrated.location(physical.toURI().toString()))
                    } else {
                        val document = requireNotNull(migrated.get(id.toLong()))
                        assertEquals(item.first, document.title); assertEquals(item.second, document.mimeType)
                        assertEquals(item.third, document.category); assertEquals(hash(id), document.hash)
                        assertEquals((100 + id).toLong(), document.revision)
                    }
                }
                for (id in listOf(127, 131, 257)) {
                    val document = requireNotNull(migrated.get(id.toLong()))
                    assertEquals("Text-$id.md", document.title)
                    assertEquals(LibraryCategory.DOCUMENTS, document.category)
                    assertEquals(hash(id), document.hash)
                }
                assertTrue(migrated.pageSnapshot(0, 20, LibraryQuery(category = LibraryCategory.MAIL)).rows.isEmpty())
                assertEquals("Original source bytes", physical.readText())
                assertEquals(stamp, migrated.get(1)!!.stamp)
                assertEquals(setOf("content://v3/129", alias), migrated.locationsForHash(hash(129)).toSet())
                assertEquals("An alias name cannot replace the canonical document category", LibraryCategory.PRESENTATIONS, migrated.location(alias)!!.category)
                assertEquals("content://v3/129", migrated.find(hash(129))!!.uri)
                assertTrue(migrated.scanPending(root, 17, verifiedOnly = true).any { it.id == 1L })
                repairedGeneration = migrated.pageSnapshot(0, 20).generation
                assertEquals("One repair invalidates pages once", 501L, repairedGeneration)
                assertEquals("The global scan counter must not regress to the page ledger", 1001L, migrated.beginScan(root))
                assertEquals(repairedGeneration, migrated.pageSnapshot(0, 20).generation)
            }
            LibraryCatalog(context, name).use { reopened ->
                assertEquals(repairedGeneration, reopened.pageSnapshot(0, 20).generation)
                val current = reopened.get(129)!!
                reopened.discover(LibraryFile(current.uri, current.title, current.mimeType, current.sizeBytes, current.modifiedAt, current.stamp))
                assertEquals("Unchanged discovery must not repeat category repair", repairedGeneration, reopened.pageSnapshot(0, 20).generation)
            }
        } finally { context.deleteDatabase(name); physical.delete() }
    }

    @Test fun provisionalMimeHintsDoNotChurnUnchangedUntrustedSourcesOrHideNewSignatures() = database { catalog, _ ->
        val root = "content://root/hints"
        val generation = catalog.beginScan(root)
        val metadata = file("Source.md").copy(mimeType = "text/plain")
        val original = requireNotNull(catalog.discover(metadata, root, generation))
        val hashed = requireNotNull(catalog.resolve(original, hash(1), root, generation))
        assertNull(hashed.stamp) // Provider timestamps cannot authorize hash reuse.
        val before = catalog.pageSnapshot(0, 20).generation
        var changes = 0
        val candidate = catalog.discoverBatch(listOf(metadata.copy(mimeType = null)), root, generation,
            provisional = true, onChanged = { changes++ }).single()
        assertEquals(hashed, candidate)
        assertEquals(before, catalog.pageSnapshot(0, 20).generation); assertEquals(0, changes)
        assertEquals(listOf(candidate), catalog.scanPending(root, generation))
        assertTrue("Provisional metadata cannot admit hashing without a fresh probe", catalog.scanPending(root, generation, verifiedOnly = true).isEmpty())
        assertEquals(1, catalog.verifyBatch(listOf(candidate to "application/pdf"), root, generation, onChanged = { changes++ }))
        val corrected = catalog.scanPending(root, generation, verifiedOnly = true).single()
        assertEquals("application/pdf", corrected.mimeType); assertEquals(LibraryCategory.DOCUMENTS, corrected.category)
        assertNull(corrected.hash); assertEquals(1, changes)
        catalog.resolve(corrected, hash(2), root, generation)
        val changed = catalog.discoverBatch(listOf(metadata.copy(sizeBytes = 13, mimeType = null)), root, generation,
            provisional = true).single()
        assertNull(changed.hash); assertNull(changed.mimeType)
        val actual = catalog.discoverBatch(listOf(metadata.copy(sizeBytes = 13, mimeType = "text/plain")), root, generation).single()
        assertEquals("text/plain", actual.mimeType); assertEquals(LibraryCategory.DOCUMENTS, actual.category)
    }

}
