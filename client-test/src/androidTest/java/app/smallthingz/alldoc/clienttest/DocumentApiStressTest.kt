package app.smallthingz.alldoc.clienttest

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.smallthingz.alldoc.api.AllDocClient
import app.smallthingz.alldoc.api.IAllDocCallback
import app.smallthingz.alldoc.api.IAllDocService
import app.smallthingz.alldoc.api.IAllDocSession
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.CRC32

/** Opt-in, offscreen release-API workload. Staging and outer device watchdog belong to the caller. */
@RunWith(AndroidJUnit4::class)
class DocumentApiStressTest {
    @Test fun profileStagedDocument() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("stress") == "true")
        val token = requireNotNull(args.getString("token")).also { require(it.matches(Regex("[a-f0-9]{32}"))) }
        val expected = requireNotNull(args.getString("sha256")).also { require(it.matches(Regex("[a-f0-9]{64}"))) }
        val name = String(Base64.decode(requireNotNull(args.getString("name64")), Base64.NO_WRAP), Charsets.UTF_8)
        val mime = args.getString("mime", "application/octet-stream")
        require(name.isNotBlank() && name.length <= 512 && mime.length <= 128)
        val theme = args.getString("theme", "original").also {
            require(it in setOf("original", "adaptive", "adaptive_light", "system", "sepia", "high_contrast", "invert"))
        }
        val rounds = args.getString("rounds", "2").toInt().also { require(it in 1..3) }
        val width = args.getString("width", "1080").toInt().also { require(it in 144..2048) }
        val timeout = args.getString("timeoutSeconds", "65").toLong().also { require(it in 5..90) }
        val budget = args.getString("budgetSeconds", "600").toLong().also { require(it in 5..1200) }
        val deadline = SystemClock.elapsedRealtimeNanos() + TimeUnit.SECONDS.toNanos(budget)
        val context = instrumentation.targetContext
        val directory = File(context.filesDir, "stress-$token")
        val source = File(directory, "input")
        val output = File(directory, "report.json")
        require(source.isFile && source.length() in 1..128L * 1024 * 1024 && !output.exists())
        val hash = MessageDigest.getInstance("SHA-256")
        source.inputStream().use { DigestInputStream(it, hash).use { stream ->
            val buffer = ByteArray(65536)
            while (stream.read(buffer) >= 0) { check(SystemClock.elapsedRealtimeNanos() < deadline) { "Fixture time budget reached while hashing" } }
        } }
        require(hex(hash.digest()) == expected) { "Staged document hash differs" }
        val report = JSONObject().put("schema", 1).put("name", name).put("sourceSha256", expected)
            .put("sourceBytes", source.length()).put("device", Build.MODEL).put("androidApi", Build.VERSION.SDK_INT)
            .put("requestedRounds", rounds).put("readerWidth", width).put("theme", theme).put("apiPreserveImages", true).put("imageTreatment", "legacy-keep").put("phaseTimeoutSeconds", timeout)
            .put("budgetSeconds", budget).put("completed", false).put("allOperationsSucceeded", false)
            .put("notes", "Elapsed time is Android elapsedRealtimeNanos. Open measures the public API path: a seekable PDF can open directly without the UI import/hash/copy path; other formats may snapshot and parse; PNG includes render/encode/pipe transfer and client streaming CRC/SHA validation. Client CPU/heap/ART counters exclude the AllDoc service. Source hash verification warms its file cache; first-open is not OS-cold. Reopen closes the session, not the app process. Cancellation may race completed rendering; outcomes are reported without assuming cancellation won. Public preserveImages=true uses legacy KEEP, not the reader default AUTO image classifier. App PSS/CPU/thermal/load require external sampling.")
        val samples = JSONArray(); report.put("samples", samples)
        fun save() { output.writeText(report.toString(2)) }
        val worker = Executors.newSingleThreadExecutor { Thread(it, "alldoc-stress-read").apply { isDaemon = true } }
        val stopping = AtomicBoolean()
        val current = AtomicReference<IAllDocSession>()
        val descriptor = AtomicReference<ParcelFileDescriptor>()
        var requestId = 0L
        var bound = false
        val connected = CountDownLatch(1)
        var service: IAllDocService? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                service = IAllDocService.Stub.asInterface(binder); connected.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) { }
        }
        fun measure(label: String, operation: () -> JSONObject): JSONObject {
            check(SystemClock.elapsedRealtimeNanos() < deadline) { "Fixture time budget reached" }
            val sample = JSONObject().put("phase", label)
            val timing = AtomicReference<JSONObject>()
            val waited = SystemClock.elapsedRealtimeNanos()
            val future = worker.submit<JSONObject> {
                val allocated = Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull()
                val cpu = Debug.threadCpuTimeNanos()
                val start = SystemClock.elapsedRealtimeNanos()
                try { operation() } finally {
                    val stats = JSONObject().put("elapsedNs", SystemClock.elapsedRealtimeNanos() - start)
                        .put("clientThreadCpuNs", Debug.threadCpuTimeNanos() - cpu)
                    Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull()?.let { after ->
                        allocated?.let { stats.put("clientProcessAllocatedBytesDelta", after - it) }
                    }
                    timing.set(stats)
                }
            }
            try {
                val left = TimeUnit.NANOSECONDS.toMillis(deadline - SystemClock.elapsedRealtimeNanos()).coerceAtLeast(1)
                sample.put("result", future.get(minOf(timeout * 1000, left), TimeUnit.MILLISECONDS)).put("outcome", "ok")
            } catch (error: Exception) {
                val cause = if (error is ExecutionException) error.cause ?: error else error
                if (error is TimeoutException) {
                    runCatching { current.get()?.cancel(requestId) }
                    runCatching { descriptor.getAndSet(null)?.close() }
                    future.cancel(true)
                }
                sample.put("outcome", if (error is TimeoutException) "timeout" else "error")
                    .put("errorType", cause.javaClass.simpleName).put("error", cause.message.orEmpty().take(300))
                if (error is TimeoutException) throw error
            } finally {
                timing.get()?.let { stats -> stats.keys().forEach { key -> sample.put(key, stats.get(key)) } }
                sample.put("observedWaitNs", SystemClock.elapsedRealtimeNanos() - waited)
                samples.put(sample); save()
            }
            return sample
        }
        fun render(label: String, page: Int, requestedWidth: Int = width) = measure(label) {
            val id = ++requestId
            val pipe = checkNotNull(current.get()).read(id, "png", page, requestedWidth, theme, true)
            descriptor.set(pipe)
            try { png(pipe, requestedWidth).put("page", page).put("requestedWidth", requestedWidth) }
            finally { descriptor.compareAndSet(pipe, null) }
        }
        try {
            report.put("clientMemoryBefore", memory()); save()
            val bindStart = SystemClock.elapsedRealtimeNanos()
            bound = context.bindService(Intent().setComponent(ComponentName(AllDocClient.PACKAGE, AllDocClient.SERVICE)),
                connection, Context.BIND_AUTO_CREATE)
            val bindBudget = TimeUnit.NANOSECONDS.toMillis(deadline - SystemClock.elapsedRealtimeNanos()).coerceAtLeast(1)
            check(bound && connected.await(minOf(10_000L, bindBudget), TimeUnit.MILLISECONDS)) { "AllDoc did not connect within the fixture budget" }
            report.put("bindElapsedNs", SystemClock.elapsedRealtimeNanos() - bindStart)
            check(checkNotNull(service).version() == AllDocClient.VERSION)
            repeat(rounds) { round ->
                val open = measure(if (round == 0) "first-session-open" else "session-reopen-$round") {
                    val ready = CountDownLatch(1)
                    val state = AtomicReference<Bundle>()
                    val callback = object : IAllDocCallback.Stub() {
                        override fun onState(value: Bundle) {
                            if (value.getString("state") != "opening") { state.set(value); ready.countDown() }
                        }
                    }
                    val session = ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use {
                        checkNotNull(service).open(it, name, mime, "", callback)
                    }
                    current.set(session)
                    if (stopping.get()) { current.compareAndSet(session, null); session.close(); throw IOException("Measurement stopped") }
                    check(ready.await(timeout, TimeUnit.SECONDS)) { "Document open did not settle" }
                    val status = checkNotNull(state.get())
                    check(status.getString("state") == "ready") { status.getString("error") ?: "Document did not become ready" }
                    JSONObject().put("pageCount", status.getInt("pageCount"))
                        .put("continuous", status.getBoolean("continuous"))
                        .put("mimeType", status.getString("mimeType")).put("format", status.getString("format"))
                        .put("fidelity", status.getString("fidelity"))
                        .put("warnings", JSONArray(status.getStringArrayList("warnings") ?: emptyList<String>()))
                }
                if (open.getString("outcome") != "ok") {
                    current.getAndSet(null)?.close()
                    return@repeat
                }
                val pages = open.getJSONObject("result").getInt("pageCount")
                check(pages > 0)
                render("round-$round-first-page", 0)
                render("round-$round-first-page-warm", 0)
                render("round-$round-preview-width", 0, 144)
                render("round-$round-reader-width-after-preview", 0)
                val distinct = linkedSetOf(pages / 2, pages - 1, pages / 3).filter { it != 0 }
                distinct.forEach { render("round-$round-selected-page-$it", it) }
                render("round-$round-first-page-revisit", 0)
                // One request at a time respects the public API's two-request bound.
                repeat(3) { burst ->
                    measure("round-$round-cancel-$burst") {
                        val id = ++requestId
                        val session = checkNotNull(current.get())
                        val pipe = session.read(id, "png", (pages - 1 - burst).coerceAtLeast(0), width, theme, true)
                        descriptor.set(pipe)
                        try {
                            val before = SystemClock.elapsedRealtimeNanos()
                            session.cancel(id)
                            val returned = SystemClock.elapsedRealtimeNanos()
                            val result = try { png(pipe, width).put("terminal", "completed-race") }
                            catch (error: IOException) { JSONObject().put("terminal", "stream-error").put("error", error.message.orEmpty().take(300)) }
                            result.put("cancelCallNs", returned - before)
                                .put("cancelToTerminalNs", SystemClock.elapsedRealtimeNanos() - before)
                        } finally { runCatching { pipe.close() }; descriptor.compareAndSet(pipe, null) }
                    }
                }
                render("round-$round-after-cancellation", 0)
                measure("round-$round-close") { current.getAndSet(null)?.close(); JSONObject() }
                report.put("completedRounds", round + 1); save()
            }
            report.put("completed", true)
            report.put("allOperationsSucceeded", (0 until samples.length()).all { samples.getJSONObject(it).getString("outcome") == "ok" })
        } catch (error: Exception) {
            report.put("fatalErrorType", error.javaClass.simpleName).put("fatalError", error.message.orEmpty().take(300))
            throw error
        } finally {
            stopping.set(true)
            runCatching { current.getAndSet(null)?.close() }
            runCatching { descriptor.getAndSet(null)?.close() }
            if (bound) context.unbindService(connection)
            worker.shutdownNow()
            report.put("readerWorkerStopped", worker.awaitTermination(5, TimeUnit.SECONDS))
            report.put("clientMemoryAfter", memory()); save()
            instrumentation.sendStatus(0, Bundle().apply { putString("alldoc_stress_report", "files/stress-$token/report.json") })
        }
    }

    private fun memory(): JSONObject {
        val runtime = Runtime.getRuntime()
        return JSONObject().put("javaUsedBytes", runtime.totalMemory() - runtime.freeMemory())
            .put("javaCommittedBytes", runtime.totalMemory()).put("nativeAllocatedBytes", Debug.getNativeHeapAllocatedSize())
            .put("processCpuMs", android.os.Process.getElapsedCpuTime())
    }

    /** Stream and validate the complete PNG without retaining or decoding a second page bitmap. */
    private fun png(pipe: ParcelFileDescriptor, width: Int): JSONObject {
        val digest = MessageDigest.getInstance("SHA-256")
        DataInputStream(DigestInputStream(ParcelFileDescriptor.AutoCloseInputStream(pipe), digest)).use { input ->
            val signature = ByteArray(8); input.readFully(signature)
            check(signature.contentEquals(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))) { "Not a PNG" }
            var bytes = 8L
            var dimensions: Pair<Int, Int>? = null
            var imageData = false
            val buffer = ByteArray(65536)
            while (true) {
                val length = input.readInt()
                require(length in 0..32 * 1024 * 1024 && bytes + length + 12 <= 32L * 1024 * 1024) { "PNG exceeds measurement limit" }
                val type = ByteArray(4); input.readFully(type)
                val kind = String(type, Charsets.US_ASCII)
                val crc = CRC32().apply { update(type) }
                var left = length
                if (kind == "IHDR") {
                    check(bytes == 8L && length == 13)
                    input.readFully(buffer, 0, 13); crc.update(buffer, 0, 13); left = 0
                    val header = java.nio.ByteBuffer.wrap(buffer)
                    dimensions = header.int to header.int
                    check(dimensions.first in 1..width && dimensions.second in 1..8192 && dimensions.first.toLong() * dimensions.second <= 8_000_000)
                }
                while (left > 0) {
                    val count = minOf(left, buffer.size)
                    input.readFully(buffer, 0, count); crc.update(buffer, 0, count); left -= count
                }
                check(input.readInt().toLong() and 0xffffffffL == crc.value) { "PNG checksum differs" }
                bytes += length + 12
                if (kind == "IDAT" && length > 0) imageData = true
                if (kind == "IEND") {
                    check(length == 0 && dimensions != null && imageData && input.read() == -1) { "Incomplete PNG" }
                    return JSONObject().put("bytes", bytes).put("width", dimensions.first).put("height", dimensions.second)
                        .put("sha256", hex(digest.digest()))
                }
            }
        }
    }
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
}
