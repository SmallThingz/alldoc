package app.smallthingz.alldoc.render

import java.io.*
import java.util.concurrent.*

/** Mandatory native limit launcher; child and scratch belong to this call. */
internal object RenderProcess {
    class Module(executable: File, launcher: File, cache: File) {
        val executable = executable.absoluteFile
        val launcher = launcher.absoluteFile
        val cache = cache.absoluteFile
        fun available(): Boolean = executable.isFile && executable.canExecute() &&
            launcher.isFile && launcher.canExecute()
    }
    private val SLOT = Semaphore(1, true)

    @Throws(IOException::class)
    fun run(module: Module?, input: ByteArray?, inputFile: File?, kind: Int, page: Int, side: Int, poll: DocRenderCore.Check?): ByteArray {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        var slot = false
        var dir: File? = null
        var child: Process? = null
        var readers: ExecutorService? = null
        var stdout: Future<Int>? = null
        var stderr: Future<Int>? = null
        try {
            acquireRenderSlot(SLOT) { check(poll, deadline) }
            slot = true
            check(poll, deadline)
            if (module == null || !module.available()) throw IOException("Visual renderer module unavailable")
            dir = prepareRenderScratch(module.cache)
            val source = inputFile ?: File(dir, "input")
            val receipt = File(dir, "receipt")
            if (input != null) FileOutputStream(source).use { stream ->
                var at = 0
                while (at < input.size) {
                    check(poll, deadline)
                    stream.write(input, at, minOf(8192, input.size - at))
                    at += 8192
                }
            }
            check(poll, deadline)
            val builder = ProcessBuilder(module.launcher.path, android.os.Process.myPid().toString(), module.executable.path,
                kind.toString(), source.absolutePath, receipt.absolutePath, side.toString(), page.toString()).directory(dir)
            builder.environment()["LC_ALL"] = "C.UTF-8"
            builder.environment()["RAYON_NUM_THREADS"] = "2"
            child = builder.start()
            child.outputStream.close()
            val owned = child
            readers = Executors.newFixedThreadPool(2) { r -> Thread(r, "alldoc-visual-pipe").apply { isDaemon = true } }
            stdout = readers.submit(Callable { drain(owned.inputStream) })
            stderr = readers.submit(Callable { drain(owned.errorStream) })
            while (!child.waitFor(20, TimeUnit.MILLISECONDS)) {
                check(poll, deadline)
                completed(stdout)
                completed(stderr)
                if (receipt.length() > DocRenderCore.OUTPUT_LIMIT + 8224L) throw IOException("Visual receipt exceeds limit")
            }
            check(poll, deadline)
            finished(stdout, poll, deadline)
            finished(stderr, poll, deadline)
            val exit = child.exitValue()
            if (exit == 125) throw IOException("Native process memory limit unavailable")
            if (exit != 0 && exit != 1) throw IOException("Visual renderer failed safely (exit " + exit + ")")
            val length = receipt.length()
            if (length < 32 || length > DocRenderCore.OUTPUT_LIMIT + 8224L)
                throw IOException("Visual receipt missing or exceeds limit")
            val wire = ByteArray(length.toInt())
            FileInputStream(receipt).use { stream ->
                var at = 0
                while (at < wire.size) {
                    check(poll, deadline)
                    val n = stream.read(wire, at, minOf(8192, wire.size - at))
                    if (n < 0) throw IOException("Truncated visual receipt")
                    at += n
                }
                if (stream.read() != -1) throw IOException("Visual receipt changed")
            }
            check(poll, deadline)
            return wire
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("Visual preview cancelled")
        } finally {
            child?.let {
                it.destroy()
                if (it.isAlive) it.destroyForcibly()
                close(it.inputStream)
                close(it.errorStream)
                close(it.outputStream)
                try { it.waitFor(1, TimeUnit.SECONDS) }
                catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            }
            stdout?.cancel(true)
            stderr?.cancel(true)
            readers?.shutdownNow()
            dir?.deleteRecursively()
            if (slot) SLOT.release()
        }
    }
    private fun drain(stream: InputStream): Int {
        val buf = ByteArray(4096)
        var total = 0
        while (true) {
            val n = stream.read(buf)
            if (n == -1) break
            total += n
            if (total > 16384) throw IOException("Visual diagnostics exceed limit")
        }
        return total
    }
    private fun completed(future: Future<Int>) {
        if (future.isDone) try { future.get() }
        catch (_: ExecutionException) { throw IOException("Visual diagnostics exceeded limit or failed") }
    }
    private fun finished(future: Future<Int>, poll: DocRenderCore.Check?, deadline: Long) {
        while (true) {
            check(poll, deadline)
            try { future.get(20, TimeUnit.MILLISECONDS); return }
            catch (_: TimeoutException) { }
            catch (_: ExecutionException) { throw IOException("Visual diagnostics exceeded limit or failed") }
        }
    }
    private fun check(poll: DocRenderCore.Check?, deadline: Long) {
        if (Thread.currentThread().isInterrupted || poll?.cancelled() == true)
            throw InterruptedIOException("Visual preview cancelled")
        if (System.nanoTime() >= deadline) throw IOException("Visual preview time limit")
    }
    private fun close(closeable: Closeable) { try { closeable.close() } catch (_: IOException) { } }
}
