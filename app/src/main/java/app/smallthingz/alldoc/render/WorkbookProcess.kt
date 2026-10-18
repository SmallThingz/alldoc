package app.smallthingz.alldoc.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.CancellationSignal
import app.smallthingz.alldoc.engine.SheetGeometry
import app.smallthingz.alldoc.engine.SheetInfo
import app.smallthingz.alldoc.engine.SheetViewport
import app.smallthingz.alldoc.engine.WorkbookInfo
import app.smallthingz.alldoc.theme.RasterRegion
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** One resident workbook per process; every request shares the ordinary visual-render permit. */
internal class WorkbookProcess(private val module: RenderProcess.Module, private val snapshot: File) : Closeable {
    private var closed = false
    private var info: WorkbookInfo? = null
    fun workbook(signal: CancellationSignal): WorkbookInfo = request(signal) { info ?: error("Workbook metadata missing") }
    fun sheet(index: Int, signal: CancellationSignal): SheetGeometry = request(signal) { worker ->
        val frame = worker.exchange(JSONObject().put("op", "sheet").put("sheet", index), 2, signal)
        val sheet = WorkbookWire.sheet(frame.json)
        require(sheet.index == index && info!!.sheets.any { it.index == index }) { "Worksheet changed" }
        SheetGeometry(sheet, WorkbookWire.edges(frame.json, "rowEdges", sheet.rowCount, sheet.height),
            WorkbookWire.edges(frame.json, "columnEdges", sheet.columnCount, sheet.width))
    }
    data class Image(val bitmap: Bitmap, val regions: List<RasterRegion>?)
    fun render(viewport: SheetViewport, signal: CancellationSignal): Image = request(signal) { worker ->
        viewport.checked()
        val frame = worker.exchange(JSONObject().put("op", "render").put("sheet", viewport.sheet)
            .put("x", viewport.x).put("y", viewport.y).put("scale", viewport.scale)
            .put("width", viewport.width).put("height", viewport.height), 3, signal)
        val value = frame.json
        check(value.getInt("sheet") == viewport.sheet && WorkbookWire.sameCoordinate(value.getDouble("x"), viewport.x) &&
            WorkbookWire.sameCoordinate(value.getDouble("y"), viewport.y) && WorkbookWire.sameCoordinate(value.getDouble("scale"), viewport.scale) &&
            value.getInt("width") == viewport.width && value.getInt("height") == viewport.height) { "Worksheet viewport changed" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(frame.png, 0, frame.png.size, bounds)
        check(bounds.outWidth == viewport.width && bounds.outHeight == viewport.height) { "Invalid worksheet raster" }
        signal.throwIfCanceled()
        val image = BitmapFactory.decodeByteArray(frame.png, 0, frame.png.size) ?: throw IOException("Cannot decode worksheet")
        try {
            signal.throwIfCanceled()
            Image(image, WorkbookWire.regions(value))
        } catch (failure: Throwable) { image.recycle(); throw failure }
    }
    fun pause() = synchronized(monitor) { if (active?.owner === this) stop() }
    override fun close() = synchronized(monitor) { closed = true; if (active?.owner === this) stop() }

    private fun <T> request(signal: CancellationSignal, action: (Worker) -> T): T {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        try { acquireRenderSlot(RenderProcess.SLOT) { check(signal, deadline) } }
        catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("Worksheet cancelled")
        }
        try { return synchronized(monitor) {
            check(!closed) { "Workbook is closed" }
            try {
                if (active?.owner !== this) {
                    stop()
                    active = Worker(this, signal, deadline)
                    info = WorkbookWire.workbook(requireNotNull(active).startup.json)
                }
                check(signal, deadline)
                action(requireNotNull(active).also { it.deadline = deadline })
            } catch (failure: Throwable) { stop(); throw failure }
        } } finally { RenderProcess.SLOT.release() }
    }

    private class Worker(val owner: WorkbookProcess, signal: CancellationSignal, var deadline: Long) : Closeable {
        private val directory = prepareRenderScratch(owner.module.cache)
        private val readers = Executors.newFixedThreadPool(2) { task -> Thread(task, "alldoc-sheet-pipe").apply { isDaemon = true } }
        private var child: Process? = null
        private var stderr: Future<Int>? = null
        private var nextId = 1
        val startup: WorkbookWire.Frame
        init {
            try {
                if (!owner.module.available() || !owner.snapshot.isFile || owner.snapshot.length() !in 1..DocRenderCore.INPUT_LIMIT.toLong())
                    throw IOException("Worksheet renderer unavailable")
                val module = owner.module
                val builder = ProcessBuilder(module.launcher.path, android.os.Process.myPid().toString(), module.executable.path,
                    "--sheets", owner.snapshot.absolutePath).directory(directory)
                builder.environment()["LC_ALL"] = "C.UTF-8"
                builder.environment()["RAYON_NUM_THREADS"] = "2"
                child = builder.start()
                stderr = readers.submit(Callable {
                    val bytes = ByteArray(4096); var total = 0
                    while (true) {
                        val count = requireNotNull(child).errorStream.read(bytes)
                        if (count < 0) break
                        total += count
                        if (total > 16_384) throw IOException("Worksheet diagnostics exceed limit")
                    }
                    total
                })
                startup = receive(0, 1, signal)
            } catch (failure: Throwable) { close(); throw failure }
        }
        fun exchange(value: JSONObject, kind: Int, signal: CancellationSignal): WorkbookWire.Frame {
            check(signal, deadline)
            check(nextId < Int.MAX_VALUE) { "Worksheet session exhausted" }
            val id = nextId++
            val bytes = value.put("id", id).toString().toByteArray(Charsets.UTF_8)
            check(bytes.size <= 1024) { "Worksheet request too large" }
            requireNotNull(child).outputStream.apply { write(bytes); write('\n'.code); flush() }
            return receive(id, kind, signal)
        }
        private fun receive(id: Int, kind: Int, signal: CancellationSignal): WorkbookWire.Frame {
            val pending = readers.submit(Callable { WorkbookWire.read(requireNotNull(child).inputStream) })
            try {
                while (true) {
                    check(signal, deadline)
                    stderr?.takeIf { it.isDone }?.get()
                    try {
                        val frame = pending.get(20, TimeUnit.MILLISECONDS)
                        check(frame.id == id) { "Worksheet response changed" }
                        if (frame.kind == 0) throw IOException(frame.json.optString("message", "Worksheet unavailable").take(256))
                        check(frame.kind == kind) { "Invalid worksheet response" }
                        return frame
                    } catch (_: TimeoutException) { }
                }
            } catch (failure: ExecutionException) {
                val cause = failure.cause
                if (cause is Error) throw cause
                throw IOException("Worksheet response unavailable", cause ?: failure)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("Worksheet cancelled")
            } finally { pending.cancel(true) }
        }
        override fun close() {
            child?.let { process ->
                process.destroy()
                if (process.isAlive) process.destroyForcibly()
                for (stream in listOf(process.inputStream, process.errorStream, process.outputStream)) runCatching { stream.close() }
                try { process.waitFor(1, TimeUnit.SECONDS) }
                catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            }
            stderr?.cancel(true)
            readers.shutdownNow()
            directory.deleteRecursively()
        }
    }
    companion object {
        private val monitor = Any()
        private var active: Worker? = null
        /** Called after acquiring RenderProcess.SLOT, so print work can reclaim the native heap. */
        internal fun pauseActive() = synchronized(monitor) { stop() }
        private fun stop() { active?.close(); active = null }
        private fun check(signal: CancellationSignal, deadline: Long) {
            signal.throwIfCanceled()
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Worksheet cancelled")
            if (System.nanoTime() >= deadline) throw IOException("Worksheet rendering timed out")
        }
    }
}

internal object WorkbookWire {
    fun sameCoordinate(actual: Double, expected: Double) = actual.isFinite() &&
        kotlin.math.abs(actual - expected) <= maxOf(Math.ulp(expected) * 4, 1e-12)
    data class Frame(val kind: Int, val id: Int, val json: JSONObject, val png: ByteArray)
    fun read(stream: java.io.InputStream): Frame {
        val input = DataInputStream(stream)
        val header = ByteArray(20).also(input::readFully)
        check(header.copyOfRange(0, 4).contentEquals("ZSG1".toByteArray())) { "Invalid worksheet receipt" }
        val values = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).apply { position(4) }
        val kind = values.int; val id = values.int; val jsonLength = values.int; val pngLength = values.int
        check(kind in 0..3 && id >= 0 && jsonLength in 2..512 * 1024 && pngLength in 0..DocRenderCore.OUTPUT_LIMIT &&
            (kind == 3 || pngLength == 0)) { "Worksheet receipt exceeds limits" }
        val bytes = ByteArray(jsonLength).also(input::readFully)
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        return Frame(kind, id, JSONObject(text), ByteArray(pngLength).also(input::readFully))
    }
    fun workbook(value: JSONObject): WorkbookInfo {
        check(value.getInt("version") == 1)
        val values = value.getJSONArray("sheets")
        check(values.length() in 1..1024)
        val sheets = List(values.length()) { sheet(values.getJSONObject(it)) }
        check(sheets.map { it.index }.toSet().size == sheets.size)
        val active = value.getInt("activeSheet")
        check(sheets.any { it.index == active })
        val warnings = value.optJSONArray("warnings") ?: JSONArray()
        check(warnings.length() <= 64)
        return WorkbookInfo(sheets, active, List(warnings.length()) { warnings.getString(it).also { text -> check(text.length <= 2048) } })
    }
    fun sheet(value: JSONObject): SheetInfo {
        val result = SheetInfo(value.getInt("index"), value.getString("name"), value.getInt("rowCount"),
            value.getInt("columnCount"), value.getInt("usedRows"), value.getInt("usedColumns"),
            value.getDouble("width"), value.getDouble("height"), value.getBoolean("showGridlines"),
            value.getBoolean("showHeadings"), value.getBoolean("rightToLeft"), value.getInt("freezeRows"), value.getInt("freezeColumns"))
        check(result.index in 0..1023 && result.name.length <= 512 && result.rowCount in 1..16384 && result.columnCount in 1..512 &&
            result.usedRows in 0..result.rowCount && result.usedColumns in 0..result.columnCount && result.width.isFinite() && result.height.isFinite() &&
            result.width in 0.0..1e9 && result.height in 0.0..1e9 && result.freezeRows in 0..result.rowCount && result.freezeColumns in 0..result.columnCount)
        return result
    }
    fun edges(value: JSONObject, key: String, count: Int, extent: Double): DoubleArray {
        val array = value.getJSONArray(key)
        check(array.length() == count + 1)
        var previous = 0.0
        return DoubleArray(array.length()) { index ->
            array.getDouble(index).also {
                check(it.isFinite() && it >= previous && it <= 1e9 && (index != 0 || it == 0.0))
                previous = it
            }
        }.also { check(kotlin.math.abs(it.last() - extent) <= .01) }
    }
    fun regions(value: JSONObject): List<RasterRegion>? {
        val array = value.optJSONArray("rasterRegions") ?: return null
        if (array.length() > RasterRegion.LIMIT) return null
        val result = ArrayList<RasterRegion>(array.length())
        for (index in 0 until array.length()) {
            val row = array.optJSONArray(index) ?: return null
            if (row.length() != 4) return null
            val l = row.optDouble(0).toFloat(); val t = row.optDouble(1).toFloat()
            val r = row.optDouble(2).toFloat(); val b = row.optDouble(3).toFloat()
            if (!l.isFinite() || !t.isFinite() || !r.isFinite() || !b.isFinite() || l < 0 || t < 0 || r > 1 || b > 1 || r <= l || b <= t) return null
            result += RasterRegion(l, t, r, b)
        }
        return result
    }
}
