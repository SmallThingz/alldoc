package org.smallthingz.alldoc

import android.app.Application
import java.io.File
import java.util.zip.ZipFile
import org.json.JSONObject
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecoveryMigrationTest {
    private lateinit var app: Application

    @Before
    fun clean() {
        app = RuntimeEnvironment.getApplication()
        File(app.filesDir, "recovery").deleteRecursively()
        File(app.filesDir, "recovered-drafts").deleteRecursively()
    }

    private fun oldSnapshot(): Pair<RecoveryStore, ByteArray> {
        val root = File(app.filesDir, "recovery").apply { mkdirs() }
        val original = File("../fixtures/recovery-v0.1.1-budget.xlsx").readBytes()
        File(root, "12345678-1234-1234-1234-123456789abc").writeBytes(original)
        File(root, "session.json")
            .writeBytes(File("../fixtures/recovery-v0.1.1-budget.json").readBytes())
        return RecoveryStore(root) to original
    }

    @Test
    fun actual011DraftIsQuarantinedAndNewEngineNeverAppliesItsPositionalId() {
        val (store, original) = oldSnapshot()
        Assert.assertEquals(1, store.read()!!.mappingVersion)
        val model = DocumentModel(app)
        val deadline = System.nanoTime() + 10_000_000_000L
        while (model.busy && System.nanoTime() < deadline) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        Assert.assertFalse(model.busy)
        Assert.assertEquals("budget.xlsx", model.name)
        Assert.assertTrue(model.edits.isEmpty())
        Assert.assertEquals(1, model.recoveredDrafts.size)
        Assert.assertEquals(RecoveryStore.CURRENT_MAPPING, store.read()!!.mappingVersion)
        val archive = model.recoveredDrafts.single()
        ZipFile(archive.file).use { zip ->
            Assert.assertArrayEquals(
                original,
                zip.getInputStream(zip.getEntry("original.xlsx")).readBytes(),
            )
            val recovered =
                JSONObject(
                    zip.getInputStream(zip.getEntry("draft-changes.json"))
                        .bufferedReader()
                        .readText()
                )
            Assert.assertTrue(
                recovered
                    .getJSONObject("edits")
                    .toString()
                    .contains("Recycled paper from the 0.1.1 draft")
            )
        }
        store.clear()
        Assert.assertTrue(archive.file.isFile)
        Assert.assertEquals(
            1,
            RecoveredDraftStore(File(app.filesDir, "recovered-drafts")).list().size,
        )
    }

    @Test
    fun newlyRejectedOfficeInputIsArchivedBeforeTheNewParserRuns() {
        val (store, _) = oldSnapshot()
        val source = store.read()!!.original
        source.writeBytes(File("../fixtures/recovery-v0.1.1-external-workbook.xlsx").readBytes())
        val model = DocumentModel(app)
        val deadline = System.nanoTime() + 10_000_000_000L
        while (model.busy && System.nanoTime() < deadline) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        Assert.assertFalse(model.busy)
        Assert.assertNull(model.session)
        Assert.assertEquals(1, model.recoveredDrafts.size)
        val archive = model.recoveredDrafts.single()
        ZipFile(archive.file).use { zip ->
            Assert.assertArrayEquals(
                source.readBytes(),
                zip.getInputStream(zip.getEntry("original.xlsx")).readBytes(),
            )
        }
        store.clear()
        Assert.assertTrue(archive.file.isFile)
    }

    @Test
    fun corruptExistingArchiveNeverAuthorizesReplacingRecovery() {
        val (store, _) = oldSnapshot()
        val archives = RecoveredDraftStore(File(app.filesDir, "recovered-drafts"))
        val saved = store.read()!!
        val archive = archives.preserve(saved)
        archive.file.writeText("corrupted archive")
        Assert.assertThrows(Exception::class.java) { archives.preserve(saved) }
        Assert.assertEquals(1, store.read()!!.mappingVersion)
        Assert.assertTrue(store.read()!!.original.isFile)
        Assert.assertTrue(store.read()!!.edits.isNotEmpty())
    }

    @Test
    fun repeatedQuarantineDoesNotCreateDuplicateArchives() {
        val (store, _) = oldSnapshot()
        val archives = RecoveredDraftStore(File(app.filesDir, "recovered-drafts"))
        val first = archives.preserve(store.read()!!)
        Assert.assertEquals(first.id, archives.preserve(store.read()!!).id)
        Assert.assertEquals(1, archives.list().size)
    }
}
