package app.smallthingz.alldoc.pdf

import android.content.Context
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.system.Os
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/** Read PDF contents and notes, or text on older Android, in a disposable, memory-limited native child. */
internal object NativePdfExports {
    private data class Module(val executable: File, val launcher: File, val cache: File)
    @Volatile private var module: Module? = null
    private val slot = Semaphore(1, true)
    private const val MAX_INPUT = 128L * 1024 * 1024
    private const val MAX_OUTPUT = 16L * 1024 * 1024

    @Synchronized fun configure(context: Context) {
        if (module != null) return
        val app = context.applicationContext
        val libs = File(app.applicationInfo.nativeLibraryDir)
        val cache = File(app.cacheDir, "pdf-exports-${android.os.Process.myPid()}")
        app.cacheDir.listFiles()?.filter { it.name.matches(Regex("pdf-exports-[0-9]+")) }?.forEach {
            if (it == cache || !File("/proc/${it.name.substringAfterLast('-')}").exists()) it.deleteRecursively()
        }
        if (!cache.isDirectory && !cache.mkdirs()) throw IOException("Cannot prepare private PDF storage")
        module = Module(File(libs, "liballpdf.so"), File(libs, "liballrun.so"), cache)
    }

    fun available(): Boolean = module?.let { it.executable.canExecute() && it.launcher.canExecute() } == true

    fun json(fd: ParcelFileDescriptor, signal: CancellationSignal): String = run(fd, signal)

    fun outline(fd: ParcelFileDescriptor, password: String?, signal: CancellationSignal): String = run(fd, signal, password, "outline")

    fun notes(fd: ParcelFileDescriptor, password: String?, signal: CancellationSignal): String = run(fd, signal, password, "notes")

    private fun run(fd: ParcelFileDescriptor, signal: CancellationSignal, password: String? = null, mode: String? = null): String {
        val config = module?.takeIf { available() } ?: throw PdfTextUnavailableException()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        fun check() {
            signal.throwIfCanceled()
            if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("PDF text export cancelled")
            if (System.nanoTime() >= deadline) throw IOException("PDF text export exceeded its time limit")
        }
        var acquired = false
        var directory: File? = null
        var child: Process? = null
        val readers = Executors.newFixedThreadPool(2) { task -> Thread(task, "alldoc-pdf-pipe").apply { isDaemon = true } }
        try {
            while (!slot.tryAcquire().also { acquired = it }) { check(); Thread.sleep(10) }
            check()
            if (!config.cache.isDirectory && !config.cache.mkdirs()) throw IOException("Cannot prepare private PDF storage")
            directory = Files.createTempDirectory(config.cache.toPath(), "pdf-export-").toFile()
            val input = File(directory, "input.pdf")
            val output = File(directory, "result.json")
            input.outputStream().use { destination ->
                val buffer = ByteArray(64 * 1024)
                var offset = 0L
                while (true) {
                    check()
                    val count = Os.pread(fd.fileDescriptor, buffer, 0, buffer.size, offset)
                    if (count <= 0) break
                    offset += count
                    if (offset > MAX_INPUT) throw IOException("PDF exceeds the 128 MiB text export limit")
                    destination.write(buffer, 0, count)
                }
            }
            check()
            val command = mutableListOf(config.launcher.absolutePath, android.os.Process.myPid().toString(), config.executable.absolutePath,
                input.absolutePath, output.absolutePath)
            if (mode != null) command += mode
            val secret = if (mode != null) password.orEmpty().toByteArray(Charsets.UTF_8) else ByteArray(0)
            try {
                if (secret.size > 4096) throw IOException("PDF password exceeds the contents limit")
                child = ProcessBuilder(command).directory(directory).start()
                child.outputStream.use { it.write(secret) }
            } finally { secret.fill(0) }
            val process = checkNotNull(child)
            val stdout = readers.submit(Callable { drain(process.inputStream) })
            val stderr = readers.submit(Callable { drain(process.errorStream) })
            while (!process.waitFor(20, TimeUnit.MILLISECONDS)) {
                check()
                if (stdout.isDone) stdout.get()
                if (stderr.isDone) stderr.get()
                if (output.length() > MAX_OUTPUT) throw IOException("PDF text export exceeds its output limit")
            }
            check()
            stdout.get(1, TimeUnit.SECONDS)
            stderr.get(1, TimeUnit.SECONDS)
            if (process.exitValue() == 125) throw IOException("PDF native memory limit unavailable")
            if (process.exitValue() != 0) throw IOException(when (mode) { "outline" -> "PDF contents could not be read."; "notes" -> "PDF notes could not be read."; else -> "PDF text extraction failed safely" })
            val length = output.length()
            if (length !in 2..MAX_OUTPUT) throw IOException("PDF text export is empty or exceeds its output limit")
            val bytes = output.inputStream().use { stream ->
                val result = ByteArray(length.toInt())
                var at = 0
                while (at < result.size) {
                    check()
                    val n = stream.read(result, at, minOf(64 * 1024, result.size - at))
                    if (n < 0) throw IOException("PDF text export is incomplete")
                    at += n
                }
                result
            }
            check()
            return bytes.toString(Charsets.UTF_8)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw java.io.InterruptedIOException("PDF text export cancelled")
        } finally {
            child?.let { process ->
                process.destroy()
                if (process.isAlive) process.destroyForcibly()
                runCatching { process.inputStream.close() }
                runCatching { process.errorStream.close() }
                runCatching { process.outputStream.close() }
                try { process.waitFor(1, TimeUnit.SECONDS) }
                catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            }
            readers.shutdownNow()
            directory?.listFiles()?.forEach { it.delete() }
            directory?.delete()
            if (acquired) slot.release()
        }
    }

    private fun drain(input: InputStream) {
        val buffer = ByteArray(4096)
        var total = 0
        while (true) {
            val n = input.read(buffer)
            if (n < 0) return
            total += n
            if (total > 16384) throw IOException("PDF diagnostics exceed limit")
        }
    }
}
