package app.smallthingz.alldoc.ui

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.CancellationSignal
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.smallthingz.alldoc.sources.LibrarySourceChangedException
import app.smallthingz.alldoc.state.DocumentMetadata
import app.smallthingz.alldoc.state.DocumentState
import app.smallthingz.alldoc.state.LibraryDocument
import app.smallthingz.alldoc.state.StoredDocument
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecentPreviewSourcesTest {
    @Test fun changedSourceUsesMatchingSavedCopyWithoutUpdatingHistoryOrLibrary() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "recent-source-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getDatabasePath(name: String) = File(root, "databases/$name")
        }
        fun pdf(name: String, color: Int) = File(root, name).also { file ->
            val document = PdfDocument()
            try {
                val page = document.startPage(PdfDocument.PageInfo.Builder(300, 400, 1).create())
                page.canvas.drawColor(Color.WHITE)
                page.canvas.drawRect(45f, 60f, 255f, 340f, Paint().apply { this.color = color })
                document.finishPage(page)
                file.outputStream().use(document::writeTo)
            } finally { document.close() }
        }
        try {
            val changed = pdf("source.pdf", Color.RED)
            val saved = pdf("retained.pdf", Color.BLUE)
            val original = saved.readBytes()
            val changedBytes = changed.readBytes()
            val hash = MessageDigest.getInstance("SHA-256").digest(original).toHexString()
            val metadata = DocumentMetadata("Report.pdf", "application/pdf", original.size.toLong(),
                Uri.fromFile(changed).toString(), Uri.fromFile(saved).toString())
            val stored = StoredDocument(hash, metadata, DocumentState(), 12)
            val wrong = LibraryDocument(0, hash, stored.title, metadata.mimeType, metadata.sizeBytes,
                requireNotNull(metadata.sourceUri), 0, 0)
            assertThrows(LibrarySourceChangedException::class.java) {
                LibraryPreviews.render(context, wrong, CancellationSignal(), 144, 192)
            }
            val recovered = requireNotNull(DocumentPreviews.recoverCover(context, stored, CancellationSignal()))
            val expected = try {
                LibraryPreviews.render(context, wrong.copy(uri = requireNotNull(metadata.importUri)), CancellationSignal(), 144, 192)
            } catch (failure: Throwable) { recovered.image.recycle(); throw failure }
            try {
                assertTrue("The changed source must not be cached under the saved document hash", recovered.image.sameAs(expected.image))
                val center = recovered.image.getPixel(recovered.image.width / 2, recovered.image.height / 2)
                assertTrue("Fixture must contain the saved blue page", Color.blue(center) > Color.red(center) + 100)
                assertArrayEquals(original, saved.readBytes())
                assertArrayEquals(changedBytes, changed.readBytes())
                assertFalse("Preview recovery must not open or change reading-state/catalog databases", File(root, "databases").exists())
            } finally { recovered.image.recycle(); expected.image.recycle() }
        } finally { root.deleteRecursively() }
    }
}
