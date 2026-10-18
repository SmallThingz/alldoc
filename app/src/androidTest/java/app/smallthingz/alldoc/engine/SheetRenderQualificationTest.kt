package app.smallthingz.alldoc.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.CancellationSignal
import android.os.Debug
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.smallthingz.alldoc.theme.BitmapTheme
import app.smallthingz.alldoc.theme.ImageTreatment
import app.smallthingz.alldoc.theme.ReadingMode
import app.smallthingz.alldoc.theme.ThemeSettings
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Timer
import java.util.TimerTask
import kotlin.math.ceil
import kotlin.math.floor
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in real worksheet captures. Never resolves the workbook's print pagination. */
@RunWith(AndroidJUnit4::class)
class SheetRenderQualificationTest {
    @Test fun stagedWorkbookRendersGridChartAndFarCellsWithoutPrintLayout() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val token = arguments.getString("sheetRenderToken")
        assumeTrue("No staged worksheet qualification requested", token != null)
        require(requireNotNull(token).matches(Regex("[a-f0-9]{32}")))
        check(Looper.myLooper() != Looper.getMainLooper())
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir.canonicalFile, "sheet-render/$token")
        require(directory.isDirectory && directory.canonicalFile == directory)
        val input = File(directory, "input.bin")
        val requestFile = File(directory, "request.json")
        require(input.isFile && input.canonicalFile == input && input.length() in 1..INPUT_LIMIT)
        require(requestFile.isFile && requestFile.canonicalFile == requestFile && requestFile.length() in 1..8192)
        val requestBytes = requestFile.inputStream().use { source ->
            val bytes = ByteArray(8193); var count = 0
            while (count < bytes.size) { val read = source.read(bytes, count, bytes.size - count); if (read < 0) break; count += read }
            require(count in 1..8192); bytes.copyOf(count)
        }
        val requestHash = requireNotNull(arguments.getString("sheetRenderRequestSha256"))
        require(requestHash.matches(SHA) && digest(requestBytes) == requestHash)
        val request = JSONObject(String(requestBytes, Charsets.UTF_8))
        require(request.getInt("schema") == 1)
        val sourceHash = request.getString("inputSha256").also { require(it.matches(SHA)) }
        val appHash = request.getString("appSha256").also { require(it.matches(SHA)) }
        val name = request.getString("displayName").also { require(it.length in 1..256 && '/' !in it && '\\' !in it) }
        val width = request.optInt("width", 1080).also { require(it in 144..1440) }
        val height = request.optInt("height", 720).also { require(it in 144..1440) }
        val chartColumn = request.optInt("chartColumn", 13).also { require(it in 0..16383) }
        val chartRow = request.optInt("chartRow", 3).also { require(it in 0..1048575) }
        val pictureRow = request.optInt("pictureRow", 45).also { require(it in 0..1048575) }
        val chartWidth = request.optDouble("chartWidth", 756.0).also { require(it in 32.0..10000.0) }
        val chartHeight = request.optDouble("chartHeight", 416.0).also { require(it in 32.0..10000.0) }
        val pictureWidth = request.optDouble("pictureWidth", 1400.0).also { require(it in 32.0..10000.0) }
        val pictureHeight = request.optDouble("pictureHeight", 800.0).also { require(it in 32.0..10000.0) }
        val timeout = request.optLong("timeoutMs", 90000).also { require(it in 1000L..120000L) }
        val names = listOf("origin", "chart", "chart-zoom", "picture", "far")
        val outputs = listOf("report.json") + names.flatMap { listOf("$it-original.png", "$it-auto.png") }
        require(outputs.none { File(directory, it).exists() || File(directory, "$it.part").exists() })
        val signal = CancellationSignal()
        val timer = Timer("alldoc-sheet-qualification", true)
        timer.schedule(object : TimerTask() { override fun run() { signal.cancel() } }, timeout)
        val samples = JSONArray()
        val report = JSONObject().put("schema", 1).put("token", token).put("request", request)
            .put("requestSha256", requestHash).put("inputSha256", sourceHash).put("samples", samples)
            .put("deviceFingerprint", Build.FINGERPRINT).put("sdk", Build.VERSION.SDK_INT)
            .put("startedElapsedMs", SystemClock.elapsedRealtime()).put("imageTreatment", "AUTO")
            .put("path", "DocumentEngine.open -> OfficeSession workbook/grid -> BitmapTheme(AUTO); no print page APIs")
            .put("qualification", "Artifact/timing capture, not fidelity or UI jank acceptance. Memory samples are app process only; native workbook worker memory is excluded. Input hashing warms the file cache.")
        try {
            check(hash(input, INPUT_LIMIT, signal) == sourceHash)
            check(hash(File(context.applicationInfo.sourceDir), APK_LIMIT, signal) == appHash)
            report.put("appSha256", appHash).put("testApkSha256", hash(File(instrumentation.context.applicationInfo.sourceDir), APK_LIMIT, signal))
                .put("memoryBefore", memory())
            val opened = SystemClock.elapsedRealtimeNanos()
            DocumentEngine.open(context, ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY),
                name, request.optString("mimeType", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"), signal).use { session ->
                report.put("openNanos", SystemClock.elapsedRealtimeNanos() - opened).put("memoryAfterOpen", memory())
                val info = session.info
                report.put("session", JSONObject().put("format", info.format).put("mimeType", info.mimeType)
                    .put("fidelity", info.fidelity).put("warnings", JSONArray(info.warnings.take(16).map { it.take(256) }))
                    .put("initialPrintPageCount", info.pageCount))
                check(info.supportsSheets && info.pageCount == 0) { "Default worksheet open unexpectedly resolved print layout" }
                check(info.fidelity == "VISUAL_BEST_EFFORT" && info.warnings.none { it.startsWith("Original-layout view unavailable:") }) {
                    "Native Office worksheet rendering was replaced by a fallback"
                }
                val started = SystemClock.elapsedRealtimeNanos()
                val book = requireNotNull(session.workbook(signal))
                require(book.sheets.isNotEmpty())
                report.put("workbookNanos", SystemClock.elapsedRealtimeNanos() - started)
                    .put("warnings", JSONArray(book.warnings.take(16)))
                val active = book.sheets.firstOrNull { it.index == book.activeSheet } ?: book.sheets.first()
                val far = book.sheets.maxBy { it.usedRows.toLong() * it.usedColumns }
                val geometries = mutableMapOf<Int, SheetGeometry>()
                for (label in names) {
                    signal.throwIfCanceled()
                    val sheet = if (label == "far") far else active
                    val geometryStart = SystemClock.elapsedRealtimeNanos()
                    val geometry = geometries.getOrPut(sheet.index) { requireNotNull(session.sheetGeometry(sheet.index, signal)) }
                    val geometryNanos = SystemClock.elapsedRealtimeNanos() - geometryStart
                    if (label == "chart" || label == "chart-zoom" || label == "picture") {
                        val row = if (label == "picture") pictureRow else chartRow
                        require(chartColumn < geometry.sheet.columnCount && row < geometry.sheet.rowCount) {
                            "Requested $label anchor is outside reported worksheet geometry"
                        }
                    }
                    val x = when (label) {
                        "chart", "chart-zoom", "picture" -> (geometry.columnEdge(chartColumn) - 24).coerceAtLeast(0.0)
                        "far" -> (geometry.columnEdge(sheet.usedColumns) - width).coerceAtLeast(0.0)
                        else -> 0.0
                    }
                    val y = when (label) {
                        "chart", "chart-zoom" -> (geometry.rowEdge(chartRow) - 24).coerceAtLeast(0.0)
                        "picture" -> (geometry.rowEdge(pictureRow) - 24).coerceAtLeast(0.0)
                        "far" -> (geometry.rowEdge(sheet.usedRows) - height).coerceAtLeast(0.0)
                        else -> 0.0
                    }
                    val scale = if (label == "chart-zoom") 2.0 else 1.0
                    val viewport = SheetViewport(sheet.index, x, y, scale, width, height).checked()
                    val sample = JSONObject().put("label", label).put("sheet", sheet.index).put("sheetName", sheet.name)
                        .put("usedRows", sheet.usedRows).put("usedColumns", sheet.usedColumns)
                        .put("rowCount", geometry.sheet.rowCount).put("columnCount", geometry.sheet.columnCount)
                        .put("sheetWidth", geometry.sheet.width).put("sheetHeight", geometry.sheet.height)
                        .put("x", x).put("y", y).put("scale", scale).put("geometryNanos", geometryNanos)
                    samples.put(sample)
                    val renderStart = SystemClock.elapsedRealtimeNanos()
                    val original = session.renderSheet(viewport, ThemeSettings(ReadingMode.ORIGINAL), signal)
                    try {
                        sample.put("renderNanos", SystemClock.elapsedRealtimeNanos() - renderStart)
                        check(original.width == width && original.height == height && session.info.pageCount == 0)
                        sample.put("original", png(original, File(directory, "$label-original.png"), signal))
                        if (label == "chart" || label == "chart-zoom" || label == "picture") {
                            val picture = label == "picture"
                            val presence = objectPresence(original, viewport, geometry.columnEdge(chartColumn),
                                geometry.rowEdge(if (picture) pictureRow else chartRow),
                                if (picture) pictureWidth else chartWidth, if (picture) pictureHeight else chartHeight, signal)
                            sample.put("objectPresence", presence)
                            check(presence.getInt("chromaticSamples") >= presence.getInt("minimumChromaticSamples")) {
                                "Expected colored $label content is missing from the original viewport"
                            }
                        }
                        val regions = session.sheetRasterRegions(viewport, signal)
                        sample.put("rasterRegionCount", regions?.size ?: JSONObject.NULL)
                        val generation = original.generationId
                        val themeStart = SystemClock.elapsedRealtimeNanos()
                        val automatic = BitmapTheme.apply(original, ThemeSettings(ReadingMode.ADAPTIVE, ImageTreatment.AUTO), signal, regions)
                        try {
                            sample.put("themeNanos", SystemClock.elapsedRealtimeNanos() - themeStart)
                            check(automatic !== original && original.generationId == generation && !original.isRecycled)
                            sample.put("auto", png(automatic, File(directory, "$label-auto.png"), signal))
                        } finally { if (automatic !== original) automatic.recycle() }
                    } finally { original.recycle() }
                    sample.put("memory", memory()).put("printPageCount", session.info.pageCount)
                }
                val warmStart = SystemClock.elapsedRealtimeNanos()
                session.renderSheet(SheetViewport(active.index, 0.0, 0.0, 1.0, width, height), ThemeSettings(ReadingMode.ORIGINAL), signal).recycle()
                report.put("originRevisitNanos", SystemClock.elapsedRealtimeNanos() - warmStart)
                check(session.info.pageCount == 0)
                session.pauseSheets()
            }
            check(hash(input, INPUT_LIMIT, signal) == sourceHash)
            check(requestFile.length() <= 8192 && digest(requestFile.readBytes()) == requestHash)
            signal.throwIfCanceled()
            report.put("status", "rendered").put("printPageCount", 0)
        } catch (failure: Throwable) {
            report.put("status", "failed").put("errorClass", failure.javaClass.name).put("error", failure.message.orEmpty().take(2048))
            throw failure
        } finally {
            timer.cancel()
            report.put("memoryAfterClose", memory()).put("finishedElapsedMs", SystemClock.elapsedRealtime())
            val bytes = report.toString(2).toByteArray(Charsets.UTF_8)
            check(bytes.size <= 65536)
            val temporary = File(directory, "report.json.part")
            try { temporary.writeBytes(bytes); check(temporary.renameTo(File(directory, "report.json"))) }
            finally { temporary.delete() }
        }
    }

    // This fixture-specific presence check rejects empty/grid-only captures; it is not a fidelity oracle.
    private fun objectPresence(bitmap: Bitmap, viewport: SheetViewport, objectX: Double, objectY: Double,
        objectWidth: Double, objectHeight: Double, signal: CancellationSignal): JSONObject {
        val left = ceil((objectX - viewport.x + 16) * viewport.scale).toInt().coerceIn(0, bitmap.width)
        val top = ceil((objectY - viewport.y + 16) * viewport.scale).toInt().coerceIn(0, bitmap.height)
        val right = floor((objectX + objectWidth - viewport.x - 16) * viewport.scale).toInt().coerceIn(0, bitmap.width)
        val bottom = floor((objectY + objectHeight - viewport.y - 16) * viewport.scale).toInt().coerceIn(0, bitmap.height)
        require(right > left && bottom > top) { "Object interior is outside the requested viewport" }
        val row = IntArray(right - left)
        var samples = 0
        var chromatic = 0
        for (y in top until bottom step 4) {
            signal.throwIfCanceled()
            bitmap.getPixels(row, 0, row.size, left, y, row.size, 1)
            for (x in row.indices step 4) {
                val color = row[x]
                val red = color ushr 16 and 255
                val green = color ushr 8 and 255
                val blue = color and 255
                val maximum = maxOf(red, green, blue)
                if (color ushr 24 >= 128 && maximum >= 48 && maximum - minOf(red, green, blue) >= 24) chromatic++
                samples++
            }
        }
        return JSONObject().put("left", left).put("top", top).put("right", right).put("bottom", bottom)
            .put("objectX", objectX).put("objectY", objectY).put("objectWidth", objectWidth).put("objectHeight", objectHeight)
            .put("sampleStep", 4).put("sampleCount", samples).put("chromaticSamples", chromatic)
            .put("minimumChromaticSamples", 8).put("minimumAlpha", 128).put("minimumChannelMaximum", 48)
            .put("minimumChannelSpread", 24)
    }

    private fun memory(): JSONObject {
        val runtime = Runtime.getRuntime()
        val info = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        return JSONObject().put("javaUsedBytes", runtime.totalMemory() - runtime.freeMemory())
            .put("nativeAllocatedBytes", Debug.getNativeHeapAllocatedSize()).put("appPssKiB", info.totalPss)
            .put("appCpuMs", android.os.Process.getElapsedCpuTime())
    }
    private fun png(bitmap: Bitmap, target: File, signal: CancellationSignal): JSONObject {
        val temporary = File(target.parentFile, target.name + ".part")
        try {
            val file = temporary.outputStream()
            object : OutputStream() {
                var bytes = 0L
                override fun write(value: Int) { signal.throwIfCanceled(); check(++bytes <= PNG_LIMIT); file.write(value) }
                override fun write(value: ByteArray, offset: Int, count: Int) { signal.throwIfCanceled(); bytes += count; check(bytes <= PNG_LIMIT); file.write(value, offset, count) }
                override fun close() = file.close()
            }.use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(temporary.path, bounds)
            check(bounds.outWidth == bitmap.width && bounds.outHeight == bitmap.height && bounds.outMimeType == "image/png")
            val result = JSONObject().put("file", target.name).put("bytes", temporary.length()).put("sha256", hash(temporary, PNG_LIMIT, signal))
            signal.throwIfCanceled(); check(temporary.renameTo(target)); return result
        } finally { temporary.delete() }
    }
    private fun hash(file: File, limit: Long, signal: CancellationSignal): String {
        require(file.length() in 1..limit)
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        file.inputStream().use { source ->
            val buffer = ByteArray(32768)
            while (true) { signal.throwIfCanceled(); val count = source.read(buffer); if (count < 0) break
                size += count; require(size <= limit); digest.update(buffer, 0, count) }
        }
        require(size > 0); return hex(digest.digest())
    }
    private fun digest(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    private companion object {
        val SHA = Regex("[a-f0-9]{64}")
        const val INPUT_LIMIT = 128L * 1024 * 1024
        const val APK_LIMIT = 256L * 1024 * 1024
        const val PNG_LIMIT = 12L * 1024 * 1024
    }
}
