package app.smallthingz.alldoc.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.CancellationSignal
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
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Timer
import java.util.TimerTask
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Optional, headless artifact capture for explicitly staged documents; not a visual pass oracle. */
@RunWith(AndroidJUnit4::class)
class AutoRenderQualificationTest {
    @Test fun stagedDocumentRendersOriginalAndActualAuto() = capture()
    @Test fun stagedWordFirstPageRetainsNativeLayoutAndCompleteText() = capture(WordPage.FIRST)
    @Test fun stagedWordMiddlePageRetainsNativeLayoutAndCompleteText() = capture(WordPage.MIDDLE)
    @Test fun stagedWordLastPageRetainsNativeLayoutAndCompleteText() = capture(WordPage.LAST)

    private fun capture(wordPage: WordPage? = null) {
        val arguments = InstrumentationRegistry.getArguments()
        val token = arguments.getString("autoRenderToken")
        assumeTrue("No staged AUTO qualification requested", token != null)
        require(requireNotNull(token).matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")))
        check(Looper.myLooper() != Looper.getMainLooper())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val base = File(context.cacheDir.canonicalFile, "auto-render")
        val directory = File(base, token)
        require(base.isDirectory && base.canonicalFile == base)
        require(directory.isDirectory && directory.canonicalFile == directory)
        val input = File(directory, "input.bin")
        val requestFile = File(directory, "request.json")
        require(input.isFile && input.canonicalFile == input && input.length() in 1..MAX_INPUT)
        require(requestFile.isFile && requestFile.canonicalFile == requestFile && requestFile.length() in 1..8192)
        val expectedRequest = requireNotNull(arguments.getString("autoRenderRequestSha256"))
        require(expectedRequest.matches(SHA256))
        val requestBytes = readRequest(requestFile)
        require(requestBytes.size <= 8192 && digest(requestBytes) == expectedRequest)
        val request = JSONObject(String(requestBytes, Charsets.UTF_8))
        require(request.getInt("schema") == 1)
        val expectedInput = request.getString("inputSha256")
        val expectedApp = request.getString("appSha256")
        require(expectedInput.matches(SHA256) && expectedApp.matches(SHA256))
        val name = request.getString("displayName")
        val mime = request.getString("mimeType")
        require(name.isNotBlank() && name.length <= 256 && !name.contains('/') && !name.contains('\\'))
        require(mime.isNotBlank() && mime.length <= 128)
        val requestedPage = request.getInt("page")
        val width = request.getInt("width")
        require(requestedPage >= 0 && width in 1..4096)
        if (wordPage != null) {
            require(expectedInput == WORD_SHA && name == "long-72.docx")
            require(mime == "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
            require(requestedPage == 0 && width == 1080)
        }
        val mode = ReadingMode.valueOf(request.optString("mode", "ADAPTIVE"))
        require(mode in setOf(ReadingMode.ADAPTIVE, ReadingMode.ADAPTIVE_LIGHT, ReadingMode.HIGH_CONTRAST, ReadingMode.SEPIA))
        val maximumTimeout = if (wordPage == null) 120_000L else 15_000L
        val timeoutMs = request.optLong("timeoutMs", maximumTimeout)
        require(timeoutMs in 1_000L..maximumTimeout)
        for (namePart in listOf("original.png", "auto.png", "report.json", "original.png.part", "auto.png.part", "report.json.part")) {
            val output = File(directory, namePart)
            check(output.canonicalFile == output && !output.exists()) { "Use a fresh capture token" }
        }
        val signal = CancellationSignal()
        val timer = Timer("alldoc-auto-qualification", true)
        timer.schedule(object : TimerTask() { override fun run() { signal.cancel() } }, timeoutMs)
        val report = JSONObject().put("schema", 1).put("token", token).put("requestSha256", expectedRequest)
            .put("request", request).put("startedElapsedMs", SystemClock.elapsedRealtime())
            .put("packageName", context.packageName).put("sdk", Build.VERSION.SDK_INT)
            .put("deviceFingerprint", Build.FINGERPRINT).put("imageTreatment", "AUTO")
            .put("path", "DocumentEngine.renderPage(ORIGINAL) -> source raster hints -> BitmapTheme(AUTO)")
            .put("qualification", "Artifact capture only; visual and pixel acceptance are separate")
        try {
            val app = context.packageManager.getPackageInfo(context.packageName, 0)
            report.put("versionCode", app.longVersionCode).put("versionName", app.versionName)
            val appHash = hash(File(context.applicationInfo.sourceDir), 256L * 1024 * 1024, signal)
            check(appHash == expectedApp) { "Installed app identity differs from request" }
            report.put("appSha256", appHash)
                .put("testApkSha256", hash(File(instrumentation.context.applicationInfo.sourceDir), 256L * 1024 * 1024, signal))
            check(hash(input, MAX_INPUT, signal) == expectedInput) { "Staged input identity differs from request" }
            report.put("inputSha256", expectedInput).put("inputBytes", input.length())
            val opened = SystemClock.elapsedRealtimeNanos()
            DocumentEngine.open(context, ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY),
                name, mime, signal).use { session ->
                report.put("openNanos", SystemClock.elapsedRealtimeNanos() - opened)
                val count = session.pageCount(signal)
                val page = when (wordPage) {
                    WordPage.FIRST -> 0
                    WordPage.MIDDLE -> count / 2
                    WordPage.LAST -> count - 1
                    null -> requestedPage
                }
                require(page in 0 until count) { "Requested page is outside the document" }
                val info = session.info
                report.put("selectedPage", page)
                report.put("document", JSONObject().put("mimeType", info.mimeType).put("format", info.format)
                    .put("fidelity", info.fidelity).put("pageCount", count).put("continuous", info.continuous)
                    .put("warningCount", info.warnings.size)
                    .put("warnings", JSONArray(info.warnings.take(16).map { it.take(256) })))
                if (wordPage != null) {
                    report.put("wordSample", wordPage.name).put("trailingBlankPagePossible", wordPage == WordPage.LAST)
                    check(count >= 3 && !info.continuous && !info.reflowable)
                    check(info.fidelity == "VISUAL_BEST_EFFORT" &&
                        info.warnings.none { it.startsWith("Original-layout view unavailable:") }) {
                        "Heavy DOCX used a semantic fallback instead of native page layout"
                    }
                    report.put("semanticMarkers", wordMarkers(session, signal))
                }
                val renderStarted = SystemClock.elapsedRealtimeNanos()
                val original = session.renderPage(page, width, ThemeSettings(ReadingMode.ORIGINAL), signal)
                try {
                    signal.throwIfCanceled()
                    check(original.width in 1..8192 && original.height in 1..8192 &&
                        original.width.toLong() * original.height <= MAX_PIXELS) { "Rendered page exceeds capture bounds" }
                    report.put("renderNanos", SystemClock.elapsedRealtimeNanos() - renderStarted)
                        .put("width", original.width).put("height", original.height)
                    report.put("original", png(original, File(directory, "original.png"), signal))
                    if (wordPage != null) {
                        val ink = inkSamples(original, signal)
                        report.put("originalInk", ink)
                        // The source ends with an explicit page break; its last page may legitimately be blank.
                        if (wordPage != WordPage.LAST) check(ink.getInt("darkSamples") >= 16) {
                            "Expected DOCX body content is missing from the selected page"
                        }
                    }
                    val generation = original.generationId
                    val themeStarted = SystemClock.elapsedRealtimeNanos()
                    val regions = session.rasterRegions(page, signal)
                    report.put("rasterRegions", regions?.let { values -> JSONArray(values.map { value ->
                        JSONArray(listOf(value.left, value.top, value.right, value.bottom))
                    }) } ?: JSONObject.NULL)
                    val automatic = BitmapTheme.apply(original, ThemeSettings(mode, ImageTreatment.AUTO), signal, regions)
                    try {
                        report.put("themeNanos", SystemClock.elapsedRealtimeNanos() - themeStarted)
                        check(automatic !== original && !original.isRecycled && original.generationId == generation)
                        check(automatic.width == original.width && automatic.height == original.height)
                        report.put("auto", png(automatic, File(directory, "auto.png"), signal))
                    } finally { if (automatic !== original) automatic.recycle() }
                } finally { original.recycle() }
            }
            check(hash(input, MAX_INPUT, signal) == expectedInput) { "Staged input changed during capture" }
            check(digest(readRequest(requestFile)) == expectedRequest) { "Capture request changed during capture" }
            signal.throwIfCanceled()
            report.put("status", "rendered")
        } catch (failure: Throwable) {
            report.put("status", "failed").put("errorClass", failure.javaClass.name)
                .put("error", failure.message.orEmpty().take(2048))
            throw failure
        } finally {
            timer.cancel()
            report.put("finishedElapsedMs", SystemClock.elapsedRealtime())
            val bytes = report.toString(2).toByteArray(Charsets.UTF_8)
            check(bytes.size <= 64 * 1024)
            val temporary = File(directory, "report.json.part")
            try {
                temporary.writeBytes(bytes)
                check(temporary.renameTo(File(directory, "report.json")))
            } finally { temporary.delete() }
        }
    }

    private enum class WordPage { FIRST, MIDDLE, LAST }

    private fun wordMarkers(session: DocumentSession, signal: CancellationSignal): JSONObject {
        val json = session.json(signal)
        check(json.length <= 1_048_576) { "Heavy DOCX semantic JSON exceeded the capture bound" }
        val document = JSONObject(json)
        check(!document.getBoolean("truncated")) { "Heavy DOCX semantic content was truncated" }
        val heading = Regex("^Section ([0-9]+)$")
        val paragraph = Regex("^([0-9]+)\\.([0-9]+) ")
        var title = false
        var section = 0
        var item = 0
        var paragraphs = 0
        val sections = document.getJSONArray("sections")
        for (i in 0 until sections.length()) {
            val blocks = sections.getJSONObject(i).getJSONArray("blocks")
            for (j in 0 until blocks.length()) {
                signal.throwIfCanceled()
                val text = blocks.getJSONObject(j).getString("text")
                if (text == "Long document with tables and illustrations") title = true
                heading.matchEntire(text)?.let { match ->
                    check(section == 0 || item == 8) { "DOCX section $section retained $item of 8 paragraphs" }
                    check(match.groupValues[1].toInt() == section + 1) { "DOCX section ordering changed after section $section: $text" }
                    section++; item = 0
                }
                paragraph.find(text)?.let { match ->
                    check(match.groupValues[1].toInt() == section && match.groupValues[2].toInt() == item + 1) {
                        "DOCX paragraph marker changed after $section.$item: ${text.take(24)}"
                    }
                    item++; paragraphs++
                }
            }
        }
        check(title && section == 72 && item == 8 && paragraphs == 576) { "Heavy DOCX markers incomplete: title=$title, section=$section, item=$item, paragraphs=$paragraphs" }
        return JSONObject().put("title", title).put("sections", section).put("paragraphs", paragraphs)
            .put("lastParagraph", "72.8").put("truncated", false)
    }

    private fun inkSamples(bitmap: Bitmap, signal: CancellationSignal): JSONObject {
        val row = IntArray(bitmap.width)
        var samples = 0
        var dark = 0
        for (y in 0 until bitmap.height step 4) {
            signal.throwIfCanceled()
            bitmap.getPixels(row, 0, row.size, 0, y, row.size, 1)
            for (x in row.indices step 4) {
                val color = row[x]
                if (color ushr 24 >= 128 && maxOf(color ushr 16 and 255, color ushr 8 and 255, color and 255) < 160) dark++
                samples++
            }
        }
        return JSONObject().put("sampleStep", 4).put("sampleCount", samples).put("darkSamples", dark)
    }

    private fun png(bitmap: Bitmap, destination: File, signal: CancellationSignal): JSONObject {
        val temporary = File(destination.parentFile, destination.name + ".part")
        try {
            LimitedOutput(temporary.outputStream(), signal).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "PNG encoding failed" }
            }
            signal.throwIfCanceled()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(temporary.path, bounds)
            check(bounds.outWidth == bitmap.width && bounds.outHeight == bitmap.height && bounds.outMimeType == "image/png")
            val result = JSONObject().put("file", destination.name).put("bytes", temporary.length())
                .put("sha256", hash(temporary, MAX_PNG, signal))
            check(temporary.renameTo(destination))
            return result
        } finally { temporary.delete() }
    }

    private fun hash(file: File, limit: Long, signal: CancellationSignal): String {
        require(file.length() in 1..limit)
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        file.inputStream().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                signal.throwIfCanceled()
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                require(total <= limit)
                digest.update(buffer, 0, read)
            }
        }
        require(total > 0)
        return hex(digest.digest())
    }

    private fun readRequest(file: File): ByteArray = file.inputStream().use { input ->
        val bytes = ByteArray(8193)
        var size = 0
        while (size < bytes.size) {
            val read = input.read(bytes, size, bytes.size - size)
            if (read < 0) break
            size += read
        }
        require(size in 1..8192) { "Capture request exceeds its bounded size" }
        bytes.copyOf(size)
    }

    private fun digest(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }

    private class LimitedOutput(private val output: OutputStream, private val signal: CancellationSignal) : OutputStream() {
        private var written = 0L
        override fun write(value: Int) { signal.throwIfCanceled(); reserve(1); output.write(value) }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            signal.throwIfCanceled(); reserve(length); output.write(bytes, offset, length)
        }
        private fun reserve(count: Int) {
            written += count
            if (written > MAX_PNG) throw IOException("PNG capture exceeds its bounded output limit")
        }
        override fun flush() = output.flush()
        override fun close() = output.close()
    }

    private companion object {
        val SHA256 = Regex("[0-9a-f]{64}")
        const val WORD_SHA = "f18f6bee490f824473b09733e5fdbac54a07745fd8dca1f50d04ea72831f6b57"
        const val MAX_INPUT = 128L * 1024 * 1024
        const val MAX_PIXELS = 8_000_000L
        const val MAX_PNG = 40L * 1024 * 1024
    }
}
