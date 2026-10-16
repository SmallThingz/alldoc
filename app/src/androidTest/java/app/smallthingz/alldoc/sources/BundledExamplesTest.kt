package app.smallthingz.alldoc.sources

import android.content.ContextWrapper
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.smallthingz.alldoc.core.DocumentFormats
import app.smallthingz.alldoc.engine.DocumentEngine
import app.smallthingz.alldoc.theme.ReadingMode
import app.smallthingz.alldoc.theme.ThemeSettings
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.Locale
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BundledExamplesTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun shippedExamplesCoverEveryNamedFormatAndMatchTheirManifest() {
        val examples = ExampleDocuments.list(context)
        val extensions = DocumentFormats.all.flatMap { it.extensions }
        assertEquals(46, extensions.size)
        assertEquals(extensions.groupingBy { it }.eachCount(), examples.groupingBy { it.extension }.eachCount())
        assertEquals(examples.size, examples.map { it.id }.toSet().size)
        assertEquals(examples.size, examples.map { it.name }.toSet().size)
        assertTrue("Bundled examples must remain small", examples.sumOf { it.bytes } <= 4L * 1024 * 1024)
        for (example in examples) {
            assertEquals(example.extension, DocumentFormats.extension(example.name))
            assertTrue(example.title.isNotBlank())
            assertTrue(example.category.isNotBlank())
            val bytes = context.assets.open(example.asset).use { it.readBytes() }
            assertEquals(example.name, example.bytes, bytes.size.toLong())
            assertEquals(example.name, example.sha256, sha256(bytes))
        }
    }

    @Test(timeout = 600_000) fun everyBundledExampleOpensAsReadablePagesThroughTheRealEngine() {
        val failures = ArrayList<String>()
        val dotFormats = HashSet<String>()
        val directory = File(context.cacheDir, "example-matrix-${UUID.randomUUID()}")
        val isolated = object : ContextWrapper(context) { override fun getFilesDir() = directory }
        try { for (example in ExampleDocuments.list(isolated)) {
            try {
                val signal = CancellationSignal()
                val uri = ExampleDocuments.materialize(isolated, example, signal)
                assertEquals("file", uri.scheme)
                val file = File(requireNotNull(uri.path))
                assertEquals(example.name, file.name)
                assertEquals(example.sha256, sha256(file.readBytes()))
                val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                DocumentEngine.open(context, fd, example.name, example.mime, signal).use { session ->
                    assertEquals(example.name, session.info.title)
                    if (example.extension == "dot") dotFormats += session.info.format.lowercase(Locale.ROOT)
                    val pages = session.pageCount(signal)
                    assertTrue("Invalid page count: $pages", pages in 1..128)
                    assertTrue(session.info.fidelity.isNotBlank())
                    assertTrue(JSONObject(session.json(signal)).has("format"))
                    if (session.info.textAvailable) {
                        assertTrue("Authored content missing", session.text(signal).contains("AllDoc", ignoreCase = true))
                    } else assertEquals("Only a saved visual preview may omit text", "EMBEDDED_PREVIEW_ONLY", session.info.fidelity)
                    if (example.extension in setOf("md", "markdown")) {
                        assertEquals(1, pages); assertTrue(session.info.continuous)
                    }
                    for (page in setOf(0, pages - 1)) {
                        val aspect = session.pageAspect(page, signal)
                        assertTrue("Invalid page aspect: $aspect", aspect.isFinite() && aspect > 0f)
                        val image = session.renderPage(page, 192, ThemeSettings(ReadingMode.ORIGINAL), signal)
                        try {
                            assertTrue("Invalid rendered size", image.width in 1..192 && image.height in 1..2400)
                            val pixels = IntArray(image.width * image.height)
                            image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
                            assertTrue("Blank page $page", pixels.any { it != pixels[0] })
                        } finally { image.recycle() }
                    }
                    assertEquals(pages, session.pageCount(signal))
                }
                assertEquals("Opening modified the example", example.sha256, sha256(file.readBytes()))
                assertEquals("Cached example moved", uri, ExampleDocuments.materialize(isolated, example, signal))
            } catch (failure: Exception) {
                failures += "${example.name}: ${failure.javaClass.simpleName}: ${failure.message}"
            } catch (failure: AssertionError) {
                failures += "${example.name}: ${failure.message}"
            }
        } } finally { directory.deleteRecursively() }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
        assertEquals("Only binary Word templates remain supported as .dot", setOf("doc"), dotFormats)
    }

    @Test fun compoundExamplesOpenDespiteImageOrPdfNamesAndRejectBrokenContainers() {
        val examples = ExampleDocuments.list(context)
        val file = File.createTempFile("example-compound-", ".bin", context.cacheDir)
        try {
            for (extension in listOf("doc", "xls", "ppt")) {
                val example = examples.first { it.extension == extension }
                val source = context.assets.open(example.asset).use { it.readBytes() }
                file.writeBytes(source)
                for ((name, mime) in listOf("renamed.jpg" to null, "renamed.pdf" to "application/pdf",
                        "renamed.svg" to "image/svg+xml", "renamed" to null, "renamed.jpg" to "application/msword")) {
                    val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                    DocumentEngine.open(context, fd, name, mime, CancellationSignal()).use { session ->
                        assertEquals(name, session.info.title)
                        assertEquals(extension.uppercase(Locale.ROOT), session.info.format)
                        assertTrue(session.text(CancellationSignal()).contains("AllDoc"))
                        assertEquals(DocumentFormats.mimeType(example.name), session.info.mimeType)
                    }
                    assertFalse("Engine did not close its adopted descriptor", fd.fileDescriptor.valid())
                    assertArrayEquals("Opening changed the source", source, file.readBytes())
                }
            }
            val mail = InstrumentationRegistry.getInstrumentation().context.assets.open("admission/unsupported.msg")
                .use { it.readBytes() }
            assertEquals("22fde591b106677c593b41d3bc5a81d23cad2286d955c1ee27de1b11fea72cc1", sha256(mail))
            for (source in listOf(mail, byteArrayOf(0xd0.toByte(), 0xcf.toByte(), 0x11, 0xe0.toByte(),
                    0xa1.toByte(), 0xb1.toByte(), 0x1a, 0xe1.toByte()))) {
                file.writeBytes(source)
                for ((name, mime) in listOf("broken.jpg" to "image/jpeg", "mail.msg" to "application/vnd.ms-outlook",
                        "renamed.doc" to "application/msword")) {
                    val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                    assertThrows(IOException::class.java) {
                        DocumentEngine.open(context, fd, name, mime, CancellationSignal()).use {
                            fail("A compound signature or unsupported mail container is not a supported document")
                        }
                    }
                    assertFalse("Rejected descriptor stayed open", fd.fileDescriptor.valid())
                    assertArrayEquals(source, file.readBytes())
                }
            }
        } finally { file.delete() }
    }

    @Test fun exampleCopiesAreLazyVerifiedRecoverableAndCancellationSafe() {
        val directory = File(context.cacheDir, "example-storage-${UUID.randomUUID()}")
        val isolated = object : ContextWrapper(context) { override fun getFilesDir() = directory }
        try {
            val example = ExampleDocuments.list(isolated).first { it.extension == "md" }
            assertFalse("Browsing copied examples", directory.exists())
            assertThrows(OperationCanceledException::class.java) {
                ExampleDocuments.materialize(isolated, example, CancellationSignal().apply { cancel() })
            }
            assertFalse("Cancelled open copied an example", directory.exists())
            val original = context.assets.open(example.asset).use { it.readBytes() }
            val uri = ExampleDocuments.materialize(isolated, example, CancellationSignal())
            val file = File(requireNotNull(uri.path))
            assertArrayEquals(original, file.readBytes())
            for (bad in listOf(original.copyOf().apply { this[0] = (this[0].toInt() xor 1).toByte() }, byteArrayOf(1))) {
                file.writeBytes(bad)
                val stale = File(file.parentFile, ".partial-abandoned.tmp").apply { writeText("Interrupted copy") }
                assertEquals(uri, ExampleDocuments.materialize(isolated, example, CancellationSignal()))
                assertArrayEquals("Corrupt cached example survived", original, file.readBytes())
                assertFalse("Abandoned copy survived", stale.exists())
            }
            assertThrows(IllegalArgumentException::class.java) {
                ExampleDocuments.materialize(isolated, example.copy(sha256 = "0".repeat(64)), CancellationSignal())
            }
            assertArrayEquals(original, file.readBytes())
            assertEquals(listOf(file.name), requireNotNull(file.parentFile?.list()).toList())
        } finally { directory.deleteRecursively() }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
