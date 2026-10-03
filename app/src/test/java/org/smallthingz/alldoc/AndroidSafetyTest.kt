package org.smallthingz.alldoc

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModelStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import org.smallthingz.alldoc.core.DocumentEngine

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidSafetyTest {
  private lateinit var app: Application
  private lateinit var root: File
  private val stores = mutableListOf<ViewModelStore>()

  @Before
  fun setup() {
    app = RuntimeEnvironment.getApplication()
    File(app.filesDir, "recovery").deleteRecursively()
    File(app.filesDir, "recovered-drafts").deleteRecursively()
    root = File(app.cacheDir, "safety-${UUID.randomUUID()}").apply { mkdirs() }
  }

  @After
  fun cleanup() {
    stores.forEach { it.clear() }
    shadowOf(Looper.getMainLooper()).idle()
    root.deleteRecursively()
  }

  @Test
  fun androidXmlFactoryCanImportAndExportOfficeText() {
    val bytes = archive(mapOf("word/document.xml" to document("Original")))
    val session = DocumentEngine.open("example.docx", bytes)
    val updated = session.export(mapOf("b0" to "A & B < C"))
    assertEquals("A & B < C", DocumentEngine.open("example.docx", updated).blocks[0].original)
    assertArrayEquals(bytes, session.export(emptyMap()))
  }

  @Test
  fun androidSaxRejectsDoctypeAndHiddenDeepXml() {
    assertThrows(Exception::class.java) {
      DocumentEngine.open(
        "x.docx",
        archive(mapOf("word/document.xml" to "<!DOCTYPE a [<!ENTITY x 'bad'>]>${document("&x;")}")),
      )
    }
    assertThrows(Exception::class.java) {
      DocumentEngine.open(
        "x.docx",
        archive(
          mapOf(
            "word/document.xml" to document("ok"),
            "word/styles.xml" to "<a>".repeat(150) + "</a>".repeat(150),
          )
        ),
      )
    }
  }

  @Test
  fun lateOlderCheckpointCannotOverwriteNewerDraft() {
    val recovery = RecoveryStore(File(root, "recovery"))
    val source =
      File(recovery.directory(), UUID.randomUUID().toString()).apply { writeText("original") }
    val older = recovery.reserveRevision()
    val newer = recovery.reserveRevision()
    assertTrue(recovery.save("a.txt", source, mapOf("text" to "newer"), false, revision = newer))
    assertFalse(recovery.save("a.txt", source, mapOf("text" to "older"), false, revision = older))
    assertEquals("newer", recovery.read()!!.edits["text"])
  }

  @Test
  fun recoveryRetainsMimeIdentifiedPdfAndOriginalUri() {
    val recovery = RecoveryStore(File(root, "recovery"))
    val source =
      File(recovery.directory(), UUID.randomUUID().toString()).apply { writeText("original") }
    recovery.save("Document without extension", source, emptyMap(), true, "content://test/original")
    val saved = recovery.read()!!
    assertTrue(saved.isPdf)
    assertEquals("content://test/original", saved.sourceUri)
    assertEquals(source, saved.original)
  }

  @Test
  fun interruptedAtomicWriteRestoresBackupWhenBaseIsMissing() {
    val recovery = RecoveryStore(File(root, "recovery"))
    val source =
      File(recovery.directory(), UUID.randomUUID().toString()).apply { writeText("original") }
    recovery.save("a.txt", source, mapOf("text" to "draft"), false)
    val index = File(recovery.directory(), "session.json")
    assertTrue(index.renameTo(File(recovery.directory(), "session.json.bak")))
    assertEquals("draft", recovery.read()!!.edits["text"])
    assertTrue(index.isFile)
  }

  @Test
  fun rejectedRecoverySavePreservesPreviousOriginalAndEdits() {
    val recovery = RecoveryStore(File(root, "recovery"))
    val source =
      File(recovery.directory(), UUID.randomUUID().toString()).apply { writeText("original") }
    recovery.save("a.txt", source, mapOf("text" to "draft"), false)
    val outside = File(root, UUID.randomUUID().toString()).apply { writeText("outside") }
    assertThrows(IllegalArgumentException::class.java) {
      recovery.save("b.txt", outside, emptyMap(), false)
    }
    assertEquals(source, recovery.read()!!.original)
    assertEquals("draft", recovery.read()!!.edits["text"])
    assertTrue(source.isFile)
  }

  @Test
  fun recoveryPrunesOrphansOnlyAfterCommittedPointer() {
    val recovery = RecoveryStore(File(root, "recovery"))
    val first = File(recovery.directory(), UUID.randomUUID().toString()).apply { writeText("one") }
    val second = File(recovery.directory(), UUID.randomUUID().toString()).apply { writeText("two") }
    recovery.save("a.txt", first, emptyMap(), false)
    recovery.prune()
    assertTrue(first.isFile)
    assertFalse(second.exists())
  }

  @Test
  fun rapidEditsRecoverOnlyLatestSnapshot() {
    val provider = installProvider("a.txt", "Original")
    val model = model()
    model.open(provider.uri)
    await { !model.busy }
    repeat(40) { model.edit("text", "Revision $it") }
    val recovery = RecoveryStore(File(app.filesDir, "recovery"))
    await {
      runCatching { recovery.read()?.edits?.get("text") == "Revision 39" }.getOrDefault(false)
    }
    assertEquals("Revision 39", model.edits["text"])
    assertEquals("Original", provider.source.readText())
  }

  @Test
  fun failedImportLeavesDraftAndOriginalIntact() {
    val provider = installProvider("a.txt", "Original")
    val model = model()
    model.open(provider.uri)
    await { !model.busy }
    model.edit("text", "Draft")
    provider.displayName = "broken.docx"
    model.open(provider.uri)
    await { !model.busy }
    assertEquals("a.txt", model.name)
    assertEquals("Draft", model.edits["text"])
    assertNotNull(model.error)
    assertEquals("Original", provider.source.readText())
    val recovery = RecoveryStore(File(app.filesDir, "recovery"))
    assertEquals("a.txt", recovery.read()!!.name)
    assertEquals("Draft", recovery.read()!!.edits["text"])
  }

  @Test
  fun failedExportKeepsEditsAndRepeatedImportPrunesOldCopies() {
    val provider = installProvider("a.txt", "Original")
    val model = model()
    model.open(provider.uri)
    await { !model.busy }
    model.edit("text", "Draft")
    model.export(Uri.parse("content://alldoc.safety/fail"))
    await { !model.busy }
    assertEquals("Draft", model.edits["text"])
    assertNotNull(model.error)
    repeat(3) {
      model.open(provider.uri)
      await { !model.busy }
    }
    assertEquals(
      1,
      File(app.filesDir, "recovery").listFiles()!!.count { it.name.matches(Regex("[a-f0-9-]{36}")) },
    )
    assertEquals("Original", provider.source.readText())
  }

  @Test
  fun reopeningRecoveryStillBlocksOriginalExportUri() {
    val provider = installProvider("a.txt", "Original")
    val first = model()
    first.open(provider.uri)
    await { !first.busy }
    val second = model()
    assertEquals(provider.uri, second.sourceUri)
    second.export(provider.uri)
    assertTrue(second.error!!.contains("different destination"))
    assertEquals("Original", provider.source.readText())
  }

  private fun model(): DocumentModel {
    val model = DocumentModel(app)
    stores += ViewModelStore().apply { put("safety", model) }
    await { !model.busy }
    return model
  }

  private fun installProvider(name: String, content: String): TestProvider {
    val source = File(root, UUID.randomUUID().toString()).apply { writeText(content) }
    return TestProvider(source, name).also {
      it.attachInfo(app, ProviderInfo().apply { authority = "alldoc.safety" })
      ShadowContentResolver.registerProviderInternal("alldoc.safety", it)
    }
  }

  private fun await(predicate: () -> Boolean) {
    val deadline = System.nanoTime() + 10_000_000_000L
    while (!predicate() && System.nanoTime() < deadline) {
      shadowOf(Looper.getMainLooper()).idle()
      Thread.sleep(10)
    }
    shadowOf(Looper.getMainLooper()).idle()
    assertTrue("Background operation did not finish", predicate())
  }

  private fun archive(parts: Map<String, String>): ByteArray =
    ByteArrayOutputStream().use { output ->
      ZipOutputStream(output).use { zip ->
        parts.forEach { (name, contents) ->
          zip.putNextEntry(ZipEntry(name))
          zip.write(contents.toByteArray())
          zip.closeEntry()
        }
      }
      output.toByteArray()
    }

  private fun document(text: String) =
    "<w:document xmlns:w='http://schemas.openxmlformats.org/wordprocessingml/2006/main'><w:body><w:p><w:r><w:t>$text</w:t></w:r></w:p></w:body></w:document>"

  private class TestProvider(val source: File, var displayName: String) : ContentProvider() {
    val uri: Uri = Uri.parse("content://alldoc.safety/original")

    override fun onCreate() = true

    override fun query(
      uri: Uri,
      projection: Array<out String>?,
      selection: String?,
      selectionArgs: Array<out String>?,
      sortOrder: String?,
    ): Cursor =
      MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf(displayName)) }

    override fun getType(uri: Uri) = "application/octet-stream"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
      if (uri.lastPathSegment == "fail")
        throw FileNotFoundException("Simulated destination failure")
      return ParcelFileDescriptor.open(source, ParcelFileDescriptor.parseMode(mode))
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0

    override fun update(
      uri: Uri,
      values: ContentValues?,
      selection: String?,
      selectionArgs: Array<out String>?,
    ) = 0
  }
}
