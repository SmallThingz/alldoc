package app.smallthingz.alldoc.engine

import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.smallthingz.alldoc.core.DocumentParser
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StreamingInputTest {
    @Test fun fragmentedPdfPrefixPreservesBytesAndHash() = fragmented(cancel = false)
    @Test fun incompletePrefixRemainsCancellable() = fragmented(cancel = true)

    private fun fragmented(cancel: Boolean) {
        val file = File.createTempFile("prefix-", ".bin", InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
        val pipe = ParcelFileDescriptor.createReliablePipe()
        val flags = Os.fcntlInt(pipe[0].fileDescriptor, OsConstants.F_GETFL, 0)
        val signal = CancellationSignal()
        val parts = if (cancel) listOf("%") else listOf("%", "PDF", "-")
        val consumed = parts.map { CountDownLatch(1) }
        val boundaries = parts.runningFold(0) { size, part -> size + part.length }.drop(1)
        var received = 0
        val delegate = MessageDigest.getInstance("SHA-256")
        val digest = object : MessageDigest("SHA-256") {
            override fun engineUpdate(input: Byte) = engineUpdate(byteArrayOf(input), 0, 1)
            override fun engineUpdate(input: ByteArray, offset: Int, length: Int) {
                delegate.update(input, offset, length)
                received += length
                boundaries.indexOf(received).takeIf { it >= 0 }?.let { consumed[it].countDown() }
            }
            override fun engineDigest(): ByteArray = delegate.digest()
            override fun engineReset() = delegate.reset()
        }
        val suffix = "One complete stream".toByteArray()
        val worker = Executors.newSingleThreadExecutor()
        val result = worker.submit<Throwable?> {
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { output ->
                    for ((index, part) in parts.withIndex()) {
                        output.write(part.toByteArray())
                        check(consumed[index].await(3, TimeUnit.SECONDS)) { "The previous fragment was not read" }
                    }
                    if (cancel) signal.cancel() else output.write(suffix)
                }
                null
            } catch (failure: Throwable) { failure }
        }
        try {
            if (cancel) {
                try {
                    DocumentEngine.copyInput(pipe[0], file, 8, false, signal, digest, pdfHeaderLimit = 64)
                    fail("Incomplete header ignored cancellation")
                } catch (_: OperationCanceledException) { }
            } else {
                val expected = "%PDF-".toByteArray() + suffix
                assertEquals(expected.size.toLong(), DocumentEngine.copyInput(pipe[0], file, 8, false, signal, digest, pdfHeaderLimit = 64))
                assertArrayEquals(expected, file.readBytes())
                assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(expected), digest.digest())
            }
            assertNull(result.get(3, TimeUnit.SECONDS))
            assertEquals(flags, Os.fcntlInt(pipe[0].fileDescriptor, OsConstants.F_GETFL, 0))
        } finally {
            signal.cancel(); pipe[0].close(); worker.shutdown()
            try { assertTrue(worker.awaitTermination(3, TimeUnit.SECONDS)) }
            finally { pipe[1].close(); file.delete() }
        }
    }

    @Test fun fixedLimitsAndNoninitialPdfMagicNeverExpandTheCap() {
        for ((text, allowHeader) in listOf("%PDF-Too much content" to false, "x%PDF-Too much content" to true)) {
            val pipe = ParcelFileDescriptor.createReliablePipe()
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(text.toByteArray()) }
                try {
                    DocumentEngine.copyInput(pipe[0], null, 8, false, CancellationSignal(), pdfHeaderLimit = if (allowHeader) 64 else 8)
                    fail("Input exceeded its fixed or non-PDF cap")
                } catch (_: IOException) { }
            } finally { pipe.forEach { it.close() } }
        }
    }
}

internal object StreamingInputFixture {
    /** Valid one-page PDF with an unused large stream; no large fixture is committed. */
    fun pdf(file: File) {
        file.outputStream().use { output ->
            var position = 0L
            val offsets = LongArray(6)
            fun write(text: String) {
                val bytes = text.toByteArray(Charsets.US_ASCII)
                output.write(bytes); position += bytes.size
            }
            fun obj(index: Int, body: String) {
                offsets[index] = position
                write("$index 0 obj\n$body\nendobj\n")
            }
            write("%PDF-1.4\n")
            obj(1, "<< /Type /Catalog /Pages 2 0 R >>")
            obj(2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
            obj(3, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 300] /Resources << >> /Contents 4 0 R >>")
            val drawing = "q 0 0 0 rg 20 20 50 50 re f Q\n"
            obj(4, "<< /Length ${drawing.length} >>\nstream\n${drawing}endstream")
            var remaining = DocumentParser.INPUT_LIMIT + 1024
            offsets[5] = position
            write("5 0 obj\n<< /Length $remaining >>\nstream\n")
            val bytes = ByteArray(32768)
            while (remaining > 0) {
                val count = minOf(bytes.size, remaining)
                output.write(bytes, 0, count); position += count; remaining -= count
            }
            write("\nendstream\nendobj\n")
            val xref = position
            write("xref\n0 6\n0000000000 65535 f \n")
            for (index in 1..5) write("${offsets[index].toString().padStart(10, '0')} 00000 n \n")
            write("trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        }
    }

    fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val bytes = ByteArray(32768)
            while (true) {
                val count = input.read(bytes)
                if (count < 0) break
                digest.update(bytes, 0, count)
            }
        }
        return digest.digest().toHexString()
    }

    fun <T> pipe(file: File, complete: Boolean = true, action: (ParcelFileDescriptor) -> T): T {
        val pipe = ParcelFileDescriptor.createReliablePipe()
        val worker = Executors.newSingleThreadExecutor()
        val result = worker.submit<Throwable?> {
            try {
                file.inputStream().use { input ->
                    ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { output -> input.copyTo(output) }
                }
                null
            } catch (failure: Throwable) { failure }
        }
        try {
            val value = action(pipe[0])
            if (complete) assertNull(result.get(5, TimeUnit.SECONDS))
            return value
        } finally {
            pipe[0].close(); worker.shutdown()
            try { assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS)) }
            finally { pipe[1].close() }
        }
    }
}
