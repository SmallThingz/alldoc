package app.smallthingz.alldoc.state

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SheetStateTest {
    @Test fun worksheetPositionSurvivesAliasesAndDatabaseReopeningWithoutFloatLoss() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "sheet-${UUID.randomUUID()}.db"
        val hash = "a".repeat(64)
        val position = SheetPosition(7, 1_900_000.125, 29_360_128.03125, .25f)
        try {
            DocumentStore(context, name).use { store ->
                store.open(hash, DocumentMetadata("First.xlsx", null, 4, "file:///first.xlsx"))
                assertTrue(store.saveState(hash, DocumentState(page = 9, sheetPosition = position)))
            }
            DocumentStore(context, name).use { store ->
                val alias = store.open(hash, DocumentMetadata("Renamed.xlsx", null, 4, "file:///renamed.xlsx"))
                assertEquals(position, alias.state.sheetPosition)
                assertEquals(9, alias.state.page)
                assertEquals(1L, store.count())
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun versionFiveMigratesWithoutDiscardingOldReadingState() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "sheet-migration-${UUID.randomUUID()}.db"
        val hash = "b".repeat(64)
        try {
            SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { database ->
                createVersionFive(database)
                database.execSQL("INSERT INTO documents VALUES(X'$hash','Saved.pdf','application/pdf',1,NULL,NULL,1,9,2,.3,.4,1,0,0,0,0,0,1,.5,1,1,0)")
                database.version = 5
            }
            DocumentStore(context, name).use { store ->
                val state = requireNotNull(store.get(hash)).state
                assertEquals(9, state.page)
                assertEquals(2f, state.viewport.zoom, 0f)
                assertTrue(state.horizontalPages); assertTrue(state.notesVisible); assertTrue(state.hideMargins)
                assertEquals(SheetPosition(), state.sheetPosition)
                assertTrue(store.saveState(hash, state.copy(sheetPosition = SheetPosition(7, 91.125, 9_000_000.03125, 3f))))
            }
            DocumentStore(context, name).use { store ->
                assertEquals(9_000_000.03125, store.get(hash)!!.state.sheetPosition.y, 0.0)
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun invalidSavedPoseCannotReachTheRenderer() {
        val value = SheetPosition(Int.MAX_VALUE, Double.NaN, Double.POSITIVE_INFINITY, Float.NaN).normalized()
        assertEquals(SheetPosition(1023), value)
        assertEquals(.25f, SheetPosition(zoom = -1f).normalized().zoom, 0f)
        assertEquals(1e9, SheetPosition(x = Double.MAX_VALUE).normalized().x, 0.0)
    }

    private fun createVersionFive(database: SQLiteDatabase) {
database.execSQL("CREATE TABLE ledger (id INTEGER PRIMARY KEY CHECK(id=1), entries INTEGER NOT NULL, stamp INTEGER NOT NULL)")
            database.execSQL("INSERT INTO ledger VALUES(1,0,0)")
            database.execSQL("""CREATE TABLE documents (
                hash BLOB PRIMARY KEY CHECK(length(hash)=32), title TEXT NOT NULL, mime TEXT, size INTEGER NOT NULL CHECK(size>=0),
                source_uri TEXT, import_uri TEXT, opened INTEGER NOT NULL,
                page INTEGER NOT NULL, zoom REAL NOT NULL, cx REAL NOT NULL, cy REAL NOT NULL, rotation INTEGER NOT NULL,
                text_offset INTEGER NOT NULL, text_fraction REAL NOT NULL, view INTEGER NOT NULL,
                appearance INTEGER NOT NULL, preserve_images INTEGER NOT NULL, hide_margins INTEGER NOT NULL DEFAULT 0,
                page_fraction REAL NOT NULL DEFAULT 0, horizontal_pages INTEGER NOT NULL DEFAULT 0, notes_visible INTEGER NOT NULL DEFAULT 0,
                paper_roughness INTEGER NOT NULL DEFAULT 0) WITHOUT ROWID""")
            database.execSQL("CREATE INDEX documents_recency ON documents(opened DESC,hash DESC)")
            database.execSQL("CREATE TRIGGER document_insert AFTER INSERT ON documents BEGIN UPDATE ledger SET entries=entries+1 WHERE id=1; END")
            database.execSQL("CREATE TRIGGER document_delete AFTER DELETE ON documents BEGIN UPDATE ledger SET entries=entries-1 WHERE id=1; END")
            database.execSQL("CREATE TABLE settings (id INTEGER PRIMARY KEY CHECK(id=1), enabled INTEGER NOT NULL, bytes INTEGER NOT NULL, count INTEGER NOT NULL, tree TEXT)")
            database.execSQL("INSERT INTO settings VALUES(1,1,268435456,1000,NULL)")
    }
}
