package app.smallthingz.alldoc.pdf

import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.ErrnoException
import android.system.OsConstants
import androidx.test.platform.app.InstrumentationRegistry
import app.smallthingz.alldoc.engine.DocumentEngine
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class NativePdfInputTest {
    @Test fun trustedSnapshotsUseSupportedInputRouteWithoutTakingOwnership() = fixture { directory ->
        val source = File(directory, "source.pdf").apply { writeBytes(pdfOutlineFixture(notes = true)) }
        val original = source.readBytes()
        val input = File(directory, "input.pdf")
        val probe = File(directory, "link-capability.pdf")
        val linkFailure = try {
            Os.link(source.absolutePath, probe.absolutePath)
            null
        } catch (failure: ErrnoException) {
            // Ordinary app storage may prohibit hard links. Unexpected I/O failures still fail the test.
            assertTrue("Unexpected link failure: $failure", failure.errno in setOf(
                OsConstants.EACCES, OsConstants.EPERM, OsConstants.EOPNOTSUPP, OsConstants.ENOSYS))
            failure
        } catch (failure: SecurityException) { failure }
        val capability = org.json.JSONObject().put("version", 1).put("supported", linkFailure == null)
            .put("cause", linkFailure?.javaClass?.simpleName ?: "supported")
            .put("errno", (linkFailure as? ErrnoException)?.errno ?: org.json.JSONObject.NULL)
            .put("detail", linkFailure?.message.orEmpty().take(300))
        val diagnostic = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "pdf-input-capability.json")
        diagnostic.writeText(capability.toString())
        android.util.Log.i("AllDocPdfInputTest", capability.toString())
        if (linkFailure == null) {
            assertEquals(Os.stat(source.path).st_dev, Os.stat(probe.path).st_dev)
            assertEquals(Os.stat(source.path).st_ino, Os.stat(probe.path).st_ino)
            assertTrue(probe.delete())
        } else assertFalse("A rejected link must not leave an input", probe.exists())
        ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            NativePdfExports.prepareInput(fd, input, source) { }
            val sourceStat = Os.fstat(fd.fileDescriptor)
            val inputStat = Os.stat(input.path)
            assertEquals(sourceStat.st_dev, inputStat.st_dev)
            if (linkFailure == null) assertEquals("A supported link must avoid the copy", sourceStat.st_ino, inputStat.st_ino)
            else assertNotEquals("The bounded copy must be independent when links are unavailable", sourceStat.st_ino, inputStat.st_ino)
            capability.put("preparedRoute", if (sourceStat.st_ino == inputStat.st_ino) "hard-link" else "bounded-copy")
            diagnostic.writeText(capability.toString())
            assertTrue(source.exists())
            assertTrue(source.delete())
            assertArrayEquals(original, input.readBytes())
            assertTrue(input.delete())
            assertTrue(fd.fileDescriptor.valid())
            val prefix = ByteArray(5)
            assertEquals(5, Os.pread(fd.fileDescriptor, prefix, 0, 5, 0))
            assertEquals("%PDF-", prefix.toString(Charsets.US_ASCII))
        }
    }

    @Test fun missingMismatchedAndExternalSourcesUseTheDescriptorCopy() = fixture { directory ->
        val source = File(directory, "source.pdf").apply { writeBytes(pdfOutlineFixture()) }
        val original = source.readBytes()
        val unrelated = File(directory, "other.pdf").apply { writeText("Different input") }
        ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            for (snapshot in listOf(null, File(directory, "missing.pdf"), unrelated)) {
                val input = File(directory, "input.pdf")
                NativePdfExports.prepareInput(fd, input, snapshot) { }
                assertArrayEquals(original, input.readBytes())
                assertNotEquals(Os.fstat(fd.fileDescriptor).st_ino, Os.stat(input.path).st_ino)
                assertTrue(input.delete())
            }
            assertTrue(source.delete())
            val input = File(directory, "input.pdf")
            NativePdfExports.prepareInput(fd, input, source) { }
            assertArrayEquals(original, input.readBytes())
            assertEquals("Different input", unrelated.readText())
            assertTrue(fd.fileDescriptor.valid())
        }
    }

    @Test fun cancellationAndSizeLimitsRemovePartialInputsOnBothRoutes() = fixture { directory ->
        val source = File(directory, "source.pdf").apply { writeBytes(pdfOutlineFixture()) }
        ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            for (snapshot in listOf(null, source)) {
                val input = File(directory, "input.pdf")
                assertThrows(OperationCanceledException::class.java) {
                    NativePdfExports.prepareInput(fd, input, snapshot) {
                        if (input.exists()) throw OperationCanceledException()
                    }
                }
                assertFalse(input.exists())
                assertTrue(source.exists())
                assertTrue(fd.fileDescriptor.valid())
            }
        }
        RandomAccessFile(source, "rw").use { it.setLength(128L * 1024 * 1024 + 1) }
        ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            for (snapshot in listOf(null, source)) {
                val input = File(directory, "input.pdf")
                val failure = assertThrows(IOException::class.java) {
                    NativePdfExports.prepareInput(fd, input, snapshot) { }
                }
                assertTrue(failure.message.orEmpty().contains("128 MiB"))
                assertFalse(input.exists())
                assertTrue(source.exists())
            }
        }
    }

    @Test fun ownedSnapshotMetadataKeepsItsSourceUntilTheSessionClosesAndCleansScratch() = fixture { directory ->
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(directory, "source.pdf").apply { writeBytes(pdfOutlineFixture(notes = true)) }
        val scratch = File(context.cacheDir, "pdf-exports-${android.os.Process.myPid()}")
        PdfDocument.configure(context)
        val before = scratch.list().orEmpty().toSet()
        DocumentEngine.openSnapshot(context, source, "notes.pdf", "application/pdf", CancellationSignal()).use { session ->
            assertEquals(listOf("Repeated", "Child", "Repeated"), session.outline(CancellationSignal()).map { it.title })
            assertEquals(listOf("Check हिन्दी 😀", "Keep this passage"), session.notes(CancellationSignal()).entries.map { it.text })
            assertTrue(source.exists())
            assertTrue("Metadata must not leave new scratch directories", (scratch.list().orEmpty().toSet() - before).isEmpty())
            assertThrows(OperationCanceledException::class.java) { session.notes(CancellationSignal().apply { cancel() }) }
        }
        assertFalse(source.exists())
        assertTrue("Metadata must not leave new scratch directories", (scratch.list().orEmpty().toSet() - before).isEmpty())
    }

    private fun fixture(test: (File) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "pdf-input-test-").toFile()
        try { test(directory) } finally { directory.deleteRecursively() }
    }
}
