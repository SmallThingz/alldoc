package app.smallthingz.alldoc.state

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.smallthingz.alldoc.theme.ImageTreatment
import app.smallthingz.alldoc.theme.ReadingMode
import app.smallthingz.alldoc.theme.ThemeSettings
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DocumentStoreTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun hash(value: Int) = value.toString(16).padStart(64, '0')
    private fun metadata(title: String = "Original", source: String? = null, imported: String? = null) =
        DocumentMetadata(title, "text/markdown", 123, source, imported)
    private inline fun database(test: (String) -> Unit) {
        val name = "store-test-${UUID.randomUUID()}.db"
        try { test(name) } finally { context.deleteDatabase(name) }
    }

    @Test fun sameContentMergesMetadataWithoutResettingStateAndSurvivesReopen() = database { name ->
        val state = DocumentState(19, ViewportState(3f, .2f, .8f, 3), TextPosition(4800, .25f),
            DocumentViewMode.TEXT, ThemeSettings(ReadingMode.SEPIA, false))
        DocumentStore(context, name).use { store ->
            store.open(hash(10), metadata(source = "content://documents/original", imported = "content://copies/retained"))
            assertTrue(store.saveState(hash(10), state))
            val alias = store.open(hash(10).uppercase(), metadata("Renamed"), DocumentState(page = 2))
            assertEquals(state, alias.state)
            assertEquals("Renamed", alias.title)
            assertEquals("content://documents/original", alias.metadata.sourceUri)
            assertEquals("content://copies/retained", alias.metadata.importUri)
            val moved = store.open(hash(10), metadata("Second location", source = "content://documents/second"))
            assertEquals(state, moved.state)
            assertEquals("content://documents/second", moved.metadata.sourceUri)
            assertTrue(store.rename(hash(10), "Chosen name"))
            assertTrue(store.saveState(hash(10), state.copy(page = 20)))
            assertEquals("Chosen name", store.get(hash(10))!!.title)
            assertEquals(1L, store.count())
        }
        DocumentStore(context, name).use { store ->
            val saved = store.get(hash(10))!!
            assertEquals(state.copy(page = 20), saved.state)
            assertEquals("Chosen name", saved.title)
            assertEquals("content://documents/second", saved.metadata.sourceUri)
        }
    }

    @Test fun boundedLruUsesActivityAndRejectsLateWritesToEvictedRows() = database { name ->
        DocumentStore(context, name, maxEntries = 2, clock = { 100L }).use { store ->
            store.open(hash(1), metadata("One")); store.open(hash(2), metadata("Two"))
            store.saveState(hash(1), DocumentState(page = 3))
            val unchanged = store.get(hash(1))!!.lastOpenedAt
            store.saveState(hash(1), DocumentState(page = 3))
            assertEquals(unchanged, store.get(hash(1))!!.lastOpenedAt)
            store.open(hash(3), metadata("Three"))
            assertNull(store.get(hash(2)))
            assertFalse(store.saveState(hash(2), DocumentState(page = 99)))
            assertEquals(listOf(hash(3), hash(1)), store.recent().map { it.hash })
            assertEquals(2L, store.count())
            assertEquals(3, store.get(hash(1))!!.state.page)
        }
        DocumentStore(context, name, maxEntries = 2).use { assertEquals(2L, it.count()) }
    }

    @Test fun copyReferenceInvalidationPreservesOriginalAndReadingPreferences() = database { name ->
        DocumentStore(context, name).use { store ->
            store.open(hash(1), metadata(source = "content://original/1", imported = "content://copy/1"))
            store.saveState(hash(1), DocumentState(page = 7))
            store.open(hash(1), metadata(imported = "content://copy/replacement"))
            assertFalse(store.clearImport(hash(1), "content://copy/1"))
            assertEquals("content://copy/replacement", store.get(hash(1))!!.metadata.importUri)
            assertEquals(1, store.clearMatchingImports(listOf(hash(1) to "content://copy/replacement", hash(2) to "content://copy/missing")))
            val saved = store.open(hash(1), metadata("Alias"))
            assertNull(saved.metadata.importUri)
            assertEquals("content://original/1", saved.metadata.sourceUri)
            assertEquals(7, saved.state.page)
            store.clearImports(listOf(hash(1), hash(2)))
            assertEquals(1L, store.count())
        }
    }

    @Test fun settingsAndNormalizedViewportPersistWithCompactKeysAndNoWal() = database { name ->
        val settings = CopySettings(false, 64L * 1024 * 1024, 7, "content://documents/tree/alldoc")
        DocumentStore(context, name).use { store ->
            assertEquals(CopySettings(), store.copySettings())
            store.saveCopySettings(settings)
            store.open(hash(1), metadata(), DocumentState(-1, ViewportState(Float.NaN, Float.NEGATIVE_INFINITY, 4f, -1), TextPosition(-9, Float.NaN), pageFraction = Float.NaN))
            assertEquals(DocumentState(viewport = ViewportState(1f, .5f, 1f, 3)), store.get(hash(1))!!.state)
            for ((fraction, expected) in listOf(2f to 1f, -2f to 0f, -1f to -1f, Float.POSITIVE_INFINITY to 0f)) {
                store.saveState(hash(1), DocumentState(pageFraction = fraction))
                assertEquals(expected, store.get(hash(1))!!.state.pageFraction, 0f)
            }
            assertThrows(IllegalArgumentException::class.java) { store.open("short", metadata()) }
            assertThrows(IllegalArgumentException::class.java) { store.saveCopySettings(settings.copy(maxCount = 0)) }
            assertThrows(IllegalArgumentException::class.java) { store.recent(1_000_000) }
        }
        DocumentStore(context, name).use { assertEquals(settings, it.copySettings()) }
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT typeof(hash),length(hash) FROM documents", null).use {
                assertTrue(it.moveToFirst()); assertEquals("blob", it.getString(0)); assertEquals(32, it.getInt(1))
            }
            db.rawQuery("PRAGMA journal_mode", null).use { assertTrue(it.moveToFirst()); assertNotEquals("wal", it.getString(0)) }
            db.rawQuery("PRAGMA auto_vacuum", null).use { assertTrue(it.moveToFirst()); assertEquals(2, it.getInt(0)) }
        }
        assertFalse(context.getDatabasePath("$name-wal").exists())
    }

    @Test fun versionOneUpgradePreservesRowsAndNewAppearanceAndMarginPreferencesRoundTrip() = database { name ->
        legacyDatabase(name, 1)
        val legacy = DocumentState(7, ViewportState(2.5f, .2f, .8f, 3), TextPosition(400, .25f),
            DocumentViewMode.TEXT, ThemeSettings(ReadingMode.SEPIA, false))
        DocumentStore(context, name).use { store ->
            val saved = requireNotNull(store.get(hash(1)))
            assertEquals(legacy, saved.state)
            assertEquals("content://original/1", saved.metadata.sourceUri)
            assertEquals(1L, store.count())
            for (mode in ReadingMode.entries) {
                store.open(hash(mode.ordinal + 2), metadata(), legacy.copy(appearance = ThemeSettings(mode), hideMargins = true))
            }
        }
        DocumentStore(context, name).use { store ->
            assertEquals(legacy, store.get(hash(1))!!.state)
            for (mode in ReadingMode.entries) {
                val restored = store.get(hash(mode.ordinal + 2))!!.state
                assertEquals(ThemeSettings(mode), restored.appearance)
                assertTrue(restored.hideMargins)
                assertEquals(legacy.viewport, restored.viewport)
                assertEquals(legacy.textPosition, restored.textPosition)
            }
            assertEquals(1L + ReadingMode.entries.size, store.count())
        }
    }

    @Test fun versionTwoZoomedPageKeepsItsFocalAnchorUntilContinuousLayoutResolvesIt() = database { name ->
        legacyDatabase(name, 2)
        val legacy = DocumentState(7, ViewportState(2.5f, .2f, .8f, 3), TextPosition(400, .25f),
            DocumentViewMode.PAGES, ThemeSettings(ReadingMode.SEPIA, ImageTreatment.KEEP), pageFraction = -1f)
        val resolved = legacy.copy(pageFraction = .375f, horizontalPages = true)
        DocumentStore(context, name).use { store ->
            assertEquals("Upgrade must preserve the old focal coordinates until layout is known", legacy, store.get(hash(1))!!.state)
            assertEquals("content://original/1", store.get(hash(1))!!.metadata.sourceUri)
            assertEquals(1L, store.count())
            assertTrue(store.saveState(hash(1), resolved))
        }
        DocumentStore(context, name).use { store ->
            assertEquals("Resolved offset must replace the one-time migration marker", resolved, store.get(hash(1))!!.state)
        }
    }

    @Test fun versionThreeUpgradeDefaultsNotesHiddenAndPreservesPresentationAnchor() = database { name ->
        legacyDatabase(name, 3)
        val legacy = DocumentState(page = 7, viewport = ViewportState(2.5f, .2f, .8f, 3),
            textPosition = TextPosition(400, .25f), appearance = ThemeSettings(ReadingMode.SEPIA, ImageTreatment.KEEP),
            pageFraction = .375f, horizontalPages = true)
        DocumentStore(context, name).use { store ->
            assertEquals(legacy, store.get(hash(1))!!.state)
            assertTrue(store.saveState(hash(1), legacy.copy(notesVisible = true)))
            store.open(hash(2), metadata("Different bytes"))
            val alias = store.open(hash(1), metadata("Duplicate source", source = "content://alias/1"))
            assertEquals(legacy.copy(notesVisible = true), alias.state)
            assertFalse(store.get(hash(2))!!.state.notesVisible)
            assertFalse(store.get(hash(2))!!.state.horizontalPages)
        }
        DocumentStore(context, name).use { store ->
            assertEquals(legacy.copy(notesVisible = true), store.get(hash(1))!!.state)
            assertEquals(DocumentState(), store.get(hash(2))!!.state)
            assertEquals(2L, store.count())
        }
    }

    @Test fun versionFourUpgradePreservesStateAndRoughnessFollowsHash() = database { name ->
        legacyDatabase(name, 4)
        val legacy = DocumentState(page = 7, viewport = ViewportState(2.5f, .2f, .8f, 3),
            textPosition = TextPosition(400, .25f), appearance = ThemeSettings(ReadingMode.SEPIA, ImageTreatment.KEEP),
            pageFraction = .375f, horizontalPages = true, notesVisible = true)
        DocumentStore(context, name).use { store ->
            assertEquals(legacy, store.get(hash(1))!!.state)
            store.saveState(hash(1), legacy.copy(appearance = legacy.appearance.copy(paperRoughness = 67)))
            store.open(hash(2), metadata("Other"))
            val alias = store.open(hash(1), metadata("Renamed", source = "content://alias/1"))
            assertEquals(67, alias.state.appearance.paperRoughness)
            assertEquals(0, store.get(hash(2))!!.state.appearance.paperRoughness)
        }
        DocumentStore(context, name).use { store ->
            assertEquals(legacy.copy(appearance = legacy.appearance.copy(paperRoughness = 67)), store.get(hash(1))!!.state)
            for ((requested, expected) in listOf(-20 to 0, 200 to 100)) {
                store.saveState(hash(1), legacy.copy(appearance = legacy.appearance.copy(paperRoughness = requested)))
                assertEquals(expected, store.get(hash(1))!!.state.appearance.paperRoughness)
            }
        }
    }

    private fun legacyDatabase(name: String, version: Int) {
        val path = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            db.execSQL("CREATE TABLE ledger (id INTEGER PRIMARY KEY CHECK(id=1), entries INTEGER NOT NULL, stamp INTEGER NOT NULL)")
            db.execSQL("INSERT INTO ledger VALUES(1,0,17)")
            db.execSQL("""CREATE TABLE documents (
                hash BLOB PRIMARY KEY CHECK(length(hash)=32), title TEXT NOT NULL, mime TEXT, size INTEGER NOT NULL CHECK(size>=0),
                source_uri TEXT, import_uri TEXT, opened INTEGER NOT NULL,
                page INTEGER NOT NULL, zoom REAL NOT NULL, cx REAL NOT NULL, cy REAL NOT NULL, rotation INTEGER NOT NULL,
                text_offset INTEGER NOT NULL, text_fraction REAL NOT NULL, view INTEGER NOT NULL,
                appearance INTEGER NOT NULL, preserve_images INTEGER NOT NULL) WITHOUT ROWID""")
            db.execSQL("CREATE INDEX documents_recency ON documents(opened DESC,hash DESC)")
            db.execSQL("CREATE TRIGGER document_insert AFTER INSERT ON documents BEGIN UPDATE ledger SET entries=entries+1 WHERE id=1; END")
            db.execSQL("CREATE TRIGGER document_delete AFTER DELETE ON documents BEGIN UPDATE ledger SET entries=entries-1 WHERE id=1; END")
            db.execSQL("CREATE TABLE settings (id INTEGER PRIMARY KEY CHECK(id=1), enabled INTEGER NOT NULL, bytes INTEGER NOT NULL, count INTEGER NOT NULL, tree TEXT)")
            db.execSQL("INSERT INTO settings VALUES(1,1,268435456,1000,NULL)")
            db.execSQL("INSERT INTO documents VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", arrayOf<Any?>(
                hash(1).hexToByteArray(), "Legacy", "application/pdf", 123, "content://original/1", null,
                17, 7, 2.5f, .2f, .8f, 3, 400, .25f, 1, 2, 0))
            if (version >= 2) {
                db.execSQL("ALTER TABLE documents ADD COLUMN hide_margins INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE documents SET view=0,preserve_images=1")
            }
            if (version >= 3) {
                db.execSQL("ALTER TABLE documents ADD COLUMN page_fraction REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE documents ADD COLUMN horizontal_pages INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE documents SET page_fraction=.375,horizontal_pages=1")
            }
            if (version >= 4) db.execSQL("ALTER TABLE documents ADD COLUMN notes_visible INTEGER NOT NULL DEFAULT 1")
            db.version = version
        }
    }

    @Test fun imageTreatmentsRoundTripWithoutChangingReadingPosition() = database { name ->
        val viewport = ViewportState(2f, .3f, .6f, 1)
        DocumentStore(context, name).use { store ->
            for (treatment in ImageTreatment.entries) {
                store.open(hash(treatment.code + 1), metadata(), DocumentState(page = 4, viewport = viewport,
                    appearance = ThemeSettings(ReadingMode.SYSTEM, treatment), pageFraction = .375f, horizontalPages = true))
            }
        }
        DocumentStore(context, name).use { store ->
            for (treatment in ImageTreatment.entries) {
                val state = store.get(hash(treatment.code + 1))!!.state
                assertEquals(ThemeSettings(ReadingMode.SYSTEM, treatment), state.appearance)
                assertEquals(viewport, state.viewport)
                assertEquals(4, state.page)
                assertEquals(.375f, state.pageFraction, 0f)
                assertTrue(state.horizontalPages)
            }
        }
    }

    @Test fun mainThreadCannotOpenTheDatabase() = database { name ->
        val blocked = AtomicBoolean(false)
        DocumentStore(context, name).use { store ->
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                try { store.get(hash(1)) } catch (_: IllegalStateException) { blocked.set(true) }
            }
            assertTrue(blocked.get())
            assertFalse(context.getDatabasePath(name).exists())
        }
    }
}
