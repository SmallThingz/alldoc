package app.smallthingz.alldoc.engine

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.CancellationSignal
import android.os.Debug
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.smallthingz.alldoc.theme.BitmapTheme
import app.smallthingz.alldoc.theme.DocumentColors
import app.smallthingz.alldoc.theme.PixelTheme
import app.smallthingz.alldoc.theme.RasterRegion
import app.smallthingz.alldoc.theme.ReadingMode
import app.smallthingz.alldoc.theme.ThemeSettings
import java.io.File
import java.security.MessageDigest
import java.util.Timer
import java.util.TimerTask
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in checks for the pinned generated fixtures, not a universal document-fidelity oracle. */
@RunWith(AndroidJUnit4::class)
class HeavyPageQualificationTest {
    @Test fun layeredPdfKeepsSharpTextDarkPaperAndPhotos() = qualify(Fixture(
        "layered-48.pdf", "820967cd1f39ef0447d874741361cef94f56a5dfd0dadfa2aa5f776ff7ddb273",
        "application/pdf", 48, true))

    @Test fun layeredPresentationKeepsSharpTextDarkPaperAndPhotos() = qualify(Fixture(
        "layered-96.pptx", "755a8fbfd237b1dd90965defeec7af6af5dcd4f5d988907d34a32fc12eb47a8c",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation", 96, false))

    private data class Fixture(val name: String, val sha256: String, val mime: String, val pages: Int, val pdf: Boolean)
    private data class Area(val left: Double, val top: Double, val right: Double, val bottom: Double) {
        fun bounds(bitmap: Bitmap): IntArray = intArrayOf(
            floor(left * bitmap.width).toInt(), floor(top * bitmap.height).toInt(),
            ceil(right * bitmap.width).toInt(), ceil(bottom * bitmap.height).toInt())
    }

    private fun qualify(fixture: Fixture) {
        val arguments = InstrumentationRegistry.getArguments()
        val token = arguments.getString("heavyToken")
        assumeTrue("No staged heavy-page qualification requested", token != null)
        require(requireNotNull(token).matches(Regex("[a-z0-9][a-z0-9-]{0,47}")))
        check(Looper.myLooper() != Looper.getMainLooper())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir.canonicalFile, "heavy-qualification/" + token)
        val input = File(directory, "input")
        require(directory.isDirectory && directory.canonicalFile == directory)
        require(input.isFile && input.canonicalFile == input && input.length() in 1..8L * 1024 * 1024)
        val appHash = requireNotNull(arguments.getString("heavyAppSha256"))
        require(appHash.matches(Regex("[a-f0-9]{64}")))
        val page = arguments.getString("heavyPage")?.toInt() ?: 0
        require(page in 0 until fixture.pages)
        val timeout = arguments.getString("heavyTimeoutMs")?.toLong() ?: 15_000L
        require(timeout in 1000L..20_000L)
        val profiling = arguments.getString("heavyProfile", "false").also { require(it == "true" || it == "false") } == "true"
        val trace = if (profiling) File(directory, "theme.trace") else null
        for (name in listOf("report.json", "detail.png", "auto.png") + if (profiling) listOf("theme.trace") else emptyList()) {
            require(!File(directory, name).exists() && !File(directory, name + ".part").exists())
        }
        val signal = CancellationSignal()
        val timer = Timer("alldoc-heavy-page-qualification", true)
        val started = SystemClock.elapsedRealtime()
        val report = JSONObject().put("schema", 1).put("token", token).put("fixture", fixture.name)
            .put("inputSha256", fixture.sha256).put("page", page).put("timeoutMs", timeout)
            .put("deviceFingerprint", Build.FINGERPRINT).put("sdk", Build.VERSION.SDK_INT)
            .put("startedElapsedMs", started)
            .put("qualification", "Fixture-specific pixel and native-detail checks. No UI jank or universal fidelity claim. Input hashing warms the file cache; Android PDF native drawing cannot be interrupted mid-call.")
        var tracing = false
        fun finishTrace() {
            if (!tracing) return
            tracing = false
            Debug.stopMethodTracing()
            val file = requireNotNull(trace)
            check(file.isFile && file.length() in 1..16L * 1024 * 1024) { "Missing or oversized theme trace" }
            val prefix = ByteArray(1024)
            val length = file.inputStream().use { it.read(prefix) }
            val header = String(prefix, 0, length.coerceAtLeast(0), Charsets.US_ASCII)
            val overflow = Regex("(?m)^data-file-overflow=(true|false)$").find(header)?.groupValues?.get(1)
            report.put("profile", JSONObject().put("file", file.name).put("bytes", file.length())
                .put("sha256", hash(file, 16L * 1024 * 1024, CancellationSignal()))
                .put("intervalUs", 1000).put("bufferBytes", 8 * 1024 * 1024)
                .put("status", when (overflow) { "false" -> "complete"; "true" -> "truncated"; else -> "unknown" })
                .put("qualification", "ART sampling changes execution cost; use only for hotspot attribution, not baseline timing."))
        }
        timer.schedule(object : TimerTask() { override fun run() { signal.cancel() } }, timeout)
        try {
            check(hash(input, 8L * 1024 * 1024, signal) == fixture.sha256) { "Wrong staged fixture" }
            check(hash(File(context.applicationInfo.sourceDir), 256L * 1024 * 1024, signal) == appHash) { "Wrong installed app" }
            report.put("appSha256", appHash)
                .put("testApkSha256", hash(File(instrumentation.context.applicationInfo.sourceDir), 256L * 1024 * 1024, signal))
            val openStarted = SystemClock.elapsedRealtime()
            DocumentEngine.open(context, ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY),
                fixture.name, fixture.mime, signal).use { session ->
                report.put("openMs", SystemClock.elapsedRealtime() - openStarted)
                check(session.pageCount(signal) == fixture.pages) { "Fixture pages were lost" }
                report.put("fidelity", session.info.fidelity).put("warnings", JSONArray(session.info.warnings))
                if (!fixture.pdf) check(session.info.fidelity == "VISUAL_BEST_EFFORT" &&
                    session.info.warnings.none { it.startsWith("Original-layout view unavailable:") }) {
                    "Native presentation rendering was replaced by a fallback"
                }
                val renderStarted = SystemClock.elapsedRealtime()
                val base = session.renderPage(page, 1080, ThemeSettings(ReadingMode.ORIGINAL), signal)
                try {
                    val detail = session.renderPage(page, 2160, ThemeSettings(ReadingMode.ORIGINAL), signal)
                    try {
                        report.put("renderMs", SystemClock.elapsedRealtime() - renderStarted)
                            .put("baseWidth", base.width).put("baseHeight", base.height)
                            .put("width", detail.width).put("height", detail.height)
                        check(base.width == 1080 && detail.width == 2160 && detail.height > base.height)
                        check(detail.width.toLong() * detail.height <= 8_000_000)
                        val title = if (fixture.pdf) Area(.04, .012, .95, .065) else Area(.022, .02, .94, .13)
                        val regions = session.rasterRegions(page, signal)
                        report.put("rasterRegions", regions?.let { values -> JSONArray(values.map {
                            JSONArray(listOf(it.left, it.top, it.right, it.bottom))
                        }) } ?: JSONObject.NULL)
                        val generation = detail.generationId
                        if (trace != null) {
                            Debug.startMethodTracingSampling(trace.path, 8 * 1024 * 1024, 1000)
                            tracing = true
                        }
                        val themeStarted = SystemClock.elapsedRealtime()
                        val automatic = BitmapTheme.apply(detail, ThemeSettings(ReadingMode.ADAPTIVE), signal, regions)
                        try {
                            report.put("themeMs", SystemClock.elapsedRealtime() - themeStarted)
                            finishTrace()
                            check(automatic !== detail && detail.generationId == generation && !detail.isRecycled)
                            check(automatic.width == detail.width && automatic.height == detail.height)
                            report.put("originalPng", png(detail, File(directory, "detail.png"), signal))
                                .put("autoPng", png(automatic, File(directory, "auto.png"), signal))
                            sharpness(base, detail, title, fixture, signal, report)
                            val counters = JSONObject()
                            report.put("pixels", counters)
                            paperAndInk(detail, automatic, fixture, signal, counters)
                            if (!fixture.pdf) {
                                coloredLabel(detail, automatic, signal, counters)
                                overlappingLabels(detail, automatic, signal, counters)
                            }
                            if (page % (if (fixture.pdf) 4 else 8) == 0)
                                photograph(detail, automatic, regions, fixture, signal, counters)
                        } finally { automatic.recycle() }
                    } finally { detail.recycle() }
                } finally { base.recycle() }
            }
            check(hash(input, 8L * 1024 * 1024, signal) == fixture.sha256) { "Staged fixture changed" }
            signal.throwIfCanceled()
            check(SystemClock.elapsedRealtime() - started <= timeout) { "Heavy document exceeded its deadline" }
            if (profiling) check(report.optJSONObject("profile")?.optString("status") == "complete") { "Theme profile is incomplete" }
            report.put("status", "passed")
        } catch (failure: Throwable) {
            report.put("status", "failed").put("errorClass", failure.javaClass.name)
                .put("error", failure.message.orEmpty().take(2048))
            throw failure
        } finally {
            if (tracing) try { finishTrace() }
            catch (failure: Throwable) { report.put("profileStopError", failure.javaClass.name + ": " + failure.message.orEmpty().take(512)) }
            timer.cancel()
            report.put("elapsedMs", SystemClock.elapsedRealtime() - started)
            val temporary = File(directory, "report.json.part")
            try { temporary.writeText(report.toString(2)); check(temporary.renameTo(File(directory, "report.json"))) }
            finally { temporary.delete() }
        }
    }

    private fun titlePaper(fixture: Fixture) = if (fixture.pdf) 0xfffafaf7.toInt() else 0xfff7f8f4.toInt()
    // The pinned PDF leaves nonstroking alpha .66 active: .12/.12/.13 over .98/.98/.97.
    private fun titleInk(fixture: Fixture) = if (fixture.pdf) 0xff69696a.toInt() else Color.BLACK

    private data class TitleDetail(val ink: Int, val differences: Int, val nativeEdge: Int, val scaledEdge: Int) {
        fun accepts(minimumEdge: Int) = ink > 100 && differences > 100 &&
            nativeEdge >= minimumEdge && nativeEdge >= scaledEdge + 12
        fun json() = JSONObject().put("inkPixels", ink).put("differentTitlePixels", differences)
            .put("nativeMaxEdge", nativeEdge).put("enlargedMaxEdge", scaledEdge)
    }

    private fun titleDetail(actual: Bitmap, enlarged: Bitmap, inkColor: Int, signal: CancellationSignal): TitleDetail {
        val source = IntArray(actual.width)
        val scaled = IntArray(actual.width)
        var differences = 0
        var ink = 0
        var nativeEdge = 0
        var scaledEdge = 0
        for (y in 0 until actual.height) {
            signal.throwIfCanceled()
            actual.getPixels(source, 0, actual.width, 0, y, actual.width, 1)
            enlarged.getPixels(scaled, 0, actual.width, 0, y, actual.width, 1)
            for (x in source.indices) {
                if (source[x] != scaled[x]) differences++
                if (near(source[x], inkColor, 3)) ink++
                if (x > 0) {
                    nativeEdge = maxOf(nativeEdge, abs(Color.red(source[x]) - Color.red(source[x - 1])))
                    scaledEdge = maxOf(scaledEdge, abs(Color.red(scaled[x]) - Color.red(scaled[x - 1])))
                }
            }
        }
        return TitleDetail(ink, differences, nativeEdge, scaledEdge)
    }

    private fun sharpness(base: Bitmap, detail: Bitmap, area: Area, fixture: Fixture,
        signal: CancellationSignal, report: JSONObject) {
        fun crop(bitmap: Bitmap): Bitmap {
            val p = area.bounds(bitmap)
            return Bitmap.createBitmap(bitmap, p[0], p[1], p[2] - p[0], p[3] - p[1])
        }
        val small = crop(base)
        try {
            val actual = crop(detail)
            try {
                val enlarged = Bitmap.createScaledBitmap(small, actual.width, actual.height, true)
                try {
                    // Require two thirds of the authored composite contrast, not opaque black ink.
                    // A 2x filtered edge spreads its contrast over multiple output pixels.
                    val contrast = abs(Color.red(titlePaper(fixture)) - Color.red(titleInk(fixture)))
                    val minimumEdge = (contrast * 2 + 2) / 3
                    val native = titleDetail(actual, enlarged, titleInk(fixture), signal)
                    // Feed the actual filtered title raster through the same metric and verdict.
                    val control = titleDetail(enlarged, enlarged, titleInk(fixture), signal)
                    report.put("detail", native.json().put("minimumNativeEdge", minimumEdge)
                        .put("expectedTitleInk", titleInk(fixture)).put("expectedTitlePaper", titlePaper(fixture))
                        .put("filteredControl", control.json().put("accepted", control.accepts(minimumEdge))
                            .put("belowNativeEdgeFloor", control.nativeEdge < minimumEdge)))
                    check(control.nativeEdge < minimumEdge && !control.accepts(minimumEdge)) {
                        "Filtered enlargement met the native edge floor: " + control.nativeEdge + ", required=" + minimumEdge
                    }
                    check(native.ink > 100 && native.differences > 100) { "Title detail was missing or merely enlarged" }
                    check(native.accepts(minimumEdge)) {
                        "Title edges did not sharpen: native=" + native.nativeEdge + ", enlarged=" + native.scaledEdge +
                            ", required=" + minimumEdge
                    }
                } finally { enlarged.recycle() }
            } finally { actual.recycle() }
        } finally { small.recycle() }
    }

    private fun paperAndInk(source: Bitmap, output: Bitmap, fixture: Fixture, signal: CancellationSignal, counts: JSONObject) {
        val theme = PixelTheme(ThemeSettings(ReadingMode.ADAPTIVE))
        var paper = 0
        var ink = 0
        var lightPaper = 0
        var weakInk = 0
        val regions = if (fixture.pdf) (0 until 18).map { row ->
            val bottom = 120 + row * 39
            Area(41.0 / 720, (960.0 - bottom - 22) / 960, 659.0 / 720, (960.0 - bottom - 1) / 960)
        } else listOf(Area(.004, .03, .012, .97), Area(.022, .02, .94, .13))
        val authoredPaper = titlePaper(fixture)
        val themedPaper = theme.color(authoredPaper)
        for (area in regions) pixels(source, output, area, signal) { original, themed ->
            if (!fixture.pdf && near(original, authoredPaper, 3)) {
                if (DocumentColors.luminance(themed) >= .10) lightPaper++
                paper++
            } else if (if (fixture.pdf) compositeRowInk(original) else near(original, Color.BLACK, 3)) {
                if (DocumentColors.contrast(themed, themedPaper) < 4.5) weakInk++
                ink++
            }
        }
        if (fixture.pdf) {
            // White row fills also inherit alpha .66. Probe their text-free left strip,
            // which is geometrically inside each box and before text begins at x=45.
            for (row in 0 until 18) {
                val bottom = 120 + row * 39
                val strip = Area(41.0 / 720, (960.0 - bottom - 22) / 960, 44.0 / 720, (960.0 - bottom - 1) / 960)
                pixels(source, output, strip, signal) { original, themed ->
                    check(minOf(Color.red(original), Color.green(original), Color.blue(original)) >= 165) {
                        "Authored translucent paper strip was not rendered"
                    }
                    if (DocumentColors.luminance(themed) >= .10) lightPaper++
                    paper++
                }
            }
            pixels(source, output, Area(.04, .012, .95, .065), signal) { original, themed ->
                if (near(original, authoredPaper, 3)) {
                    if (DocumentColors.luminance(themed) >= .10) lightPaper++
                    paper++
                } else if (near(original, titleInk(fixture), 3) && DocumentColors.contrast(themed, themedPaper) < 4.5) weakInk++
            }
        }
        counts.put("paperPixels", paper).put("inkPixels", ink)
            .put("lightPaperPixels", lightPaper).put("weakInkPixels", weakInk)
        check(paper > 1000 && ink > 100) { "Expected authored paper/ink was not rendered" }
        check(lightPaper == 0) { "Light paper or a white text-box halo survived dark mode" }
        check(weakInk == 0) { "Authored lettering lost readable contrast" }
    }

    private fun coloredLabel(source: Bitmap, output: Bitmap, signal: CancellationSignal, counts: JSONObject) {
        // Unoccluded whole Layer 10 glyphs and fill, left of the photograph and above Layer 28.
        val area = Area(920.0 / 2160, 610.0 / 1215, 1080.0 / 2160, 660.0 / 1215)
        val paper = 0xff86d07d.toInt()
        val theme = PixelTheme(ThemeSettings(ReadingMode.ADAPTIVE))
        val mappedPaper = theme.color(paper)
        val ramp = requireNotNull(DocumentColors.inkRamp(theme.color(Color.WHITE), mappedPaper))
        var ink = 0
        var edges = 0
        var fill = 0
        var weak = 0
        var inconsistent = 0
        var reversed = 0
        var changedFill = 0
        var minimumContrast = 21.0
        pixels(source, output, area, signal) { original, themed ->
            val coverage = DocumentColors.inkCoverage(original, paper, Color.WHITE)
            if (coverage > 0) {
                if (themed != ramp[coverage]) inconsistent++
                if (DocumentColors.luminance(themed) < DocumentColors.luminance(mappedPaper)) reversed++
                if (coverage == 255) {
                    val contrast = DocumentColors.contrast(themed, mappedPaper)
                    minimumContrast = minOf(minimumContrast, contrast)
                    if (contrast < 4.5) weak++
                    ink++
                } else edges++
            } else if (original == paper) { if (themed != mappedPaper) changedFill++; fill++ }
        }
        counts.put("coloredLabelInkPixels", ink).put("coloredLabelEdgePixels", edges).put("coloredLabelFillPixels", fill)
            .put("weakColoredLabelPixels", weak).put("changedColoredLabelFillPixels", changedFill)
            .put("inconsistentColoredLabelPixels", inconsistent).put("reversedColoredLabelPixels", reversed)
            .put("minimumColoredLabelContrast", minimumContrast)
            .put("authoredColoredLabelContrast", DocumentColors.contrast(Color.WHITE, paper))
        check(ink > 100 && edges > 500 && fill > 1000) { "Actual whole colored Layer 10 label was not rendered" }
        check(changedFill == 0) { "Colored label correction changed the existing fill mapping" }
        check(weak == 0) { "Colored Layer 10 lettering did not reach readable contrast" }
        check(inconsistent == 0 && reversed == 0) { "Colored Layer 10 glyphs contain inconsistent or dark antialiased fragments" }
    }

    private fun overlappingLabels(source: Bitmap, output: Bitmap, signal: CancellationSignal, counts: JSONObject) {
        val theme = PixelTheme(ThemeSettings(ReadingMode.ADAPTIVE))
        val paper = 0xff52cc7d.toInt()
        val mappedPaper = theme.color(paper)
        val ramp = requireNotNull(DocumentColors.inkRamp(theme.color(Color.WHITE), mappedPaper))
        var cores = 0
        var edges = 0
        var fills = 0
        var inconsistent = 0
        var changedFill = 0
        pixels(source, output, Area(538.0 / 2160, 552.0 / 1215, 638.0 / 2160, 632.0 / 1215), signal) { original, themed ->
            val coverage = DocumentColors.inkCoverage(original, paper, Color.WHITE)
            if (coverage > 0) {
                if (themed != ramp[coverage]) inconsistent++
                if (coverage == 255) cores++ else edges++
            } else if (original == paper) { fills++; if (themed != mappedPaper) changedFill++ }
        }
        val paper9 = 0xff7bbf78.toInt()
        val ramp9 = requireNotNull(DocumentColors.inkRamp(theme.color(Color.WHITE), theme.color(paper9)))
        var cores9 = 0
        var inconsistent9 = 0
        // Layer 9 contains authored soft shadows; only its actual white cores share this flat-fill oracle.
        pixels(source, output, Area(653.0 / 2160, 426.0 / 1215, 781.0 / 2160, 471.0 / 1215), signal) { original, themed ->
            if (original == Color.WHITE) { cores9++; if (themed != ramp9[255]) inconsistent9++ }
        }
        counts.put("overlappedLabelCorePixels", cores).put("overlappedLabelEdgePixels", edges)
            .put("overlappedLabelFillPixels", fills).put("inconsistentOverlappedLabelPixels", inconsistent)
            .put("changedOverlappedLabelFillPixels", changedFill).put("shadowedLabelWhiteCorePixels", cores9)
            .put("inconsistentShadowedLabelWhiteCorePixels", inconsistent9)
        check(cores > 100 && edges > 500 && fills > 1000 && cores9 > 100) { "Actual overlapping labels were not rendered" }
        check(inconsistent == 0 && inconsistent9 == 0) { "Chart-overlapped white lettering has mixed polarity" }
        check(changedFill == 0) { "Chart-overlapped lettering changed its fill" }
    }

    private fun compositeRowInk(color: Int): Boolean {
        // .66 * authored ink + .34 * (.66 * white + .34 * arbitrary underlying RGB).
        // Channel bounds include three levels of raster quantization; paper is always >=168.
        return Color.red(color) in 63..98 && Color.green(color) in 73..108 && Color.blue(color) in 96..132
    }

    private fun photograph(source: Bitmap, output: Bitmap, regions: List<RasterRegion>?, fixture: Fixture,
        signal: CancellationSignal, counts: JSONObject) {
        // ReportLab fits the 1400x800 image within its 240x75 box; PPTX retains its aspect.
        val area = if (fixture.pdf) Area(.67, .90, .83, .96) else Area(.57, .53, .88, .82)
        counts.put("photoFootprintAvailable", regions != null)
        if (regions != null || !fixture.pdf) check(regions?.any { it.left <= area.left && it.top <= area.top &&
            it.right >= area.right && it.bottom >= area.bottom } == true) { "Source photo footprint is absent or misplaced" }
        var total = 0
        var colorful = 0
        var changed = 0
        val colors = HashSet<Int>()
        pixels(source, output, area, signal) { original, themed ->
            if (original != themed) changed++
            if (colors.size < 64) colors.add(original)
            if (maxOf(Color.red(original), Color.green(original), Color.blue(original)) -
                minOf(Color.red(original), Color.green(original), Color.blue(original)) > 40) colorful++
            total++
        }
        counts.put("photoPixels", total).put("changedPhotoPixels", changed)
            .put("photoColors", colors.size).put("colorfulPhotoPixels", colorful)
        check(total > 1000 && colorful > 100 && colors.size >= 12) { "Photo check did not exercise actual image content" }
        check(changed == 0) { "Source photograph was recolored" }
    }

    private inline fun pixels(source: Bitmap, output: Bitmap, area: Area, signal: CancellationSignal,
        inspect: (Int, Int) -> Unit) {
        val p = area.bounds(source)
        val width = p[2] - p[0]
        val before = IntArray(width)
        val after = IntArray(width)
        for (y in p[1] until p[3]) {
            signal.throwIfCanceled()
            source.getPixels(before, 0, width, p[0], y, width, 1)
            output.getPixels(after, 0, width, p[0], y, width, 1)
            for (x in before.indices) inspect(before[x], after[x])
        }
    }

    private fun near(a: Int, b: Int, tolerance: Int): Boolean = (0..2).all {
        abs((a ushr (it * 8) and 255) - (b ushr (it * 8) and 255)) <= tolerance
    }

    private fun png(bitmap: Bitmap, destination: File, signal: CancellationSignal): JSONObject {
        signal.throwIfCanceled()
        val temporary = File(destination.parentFile, destination.name + ".part")
        try {
            temporary.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            signal.throwIfCanceled()
            check(temporary.length() in 1..40L * 1024 * 1024)
            val result = JSONObject().put("file", destination.name).put("bytes", temporary.length())
                .put("sha256", hash(temporary, 40L * 1024 * 1024, signal))
            check(temporary.renameTo(destination))
            return result
        } finally { temporary.delete() }
    }

    private fun hash(file: File, limit: Long, signal: CancellationSignal): String {
        require(file.length() in 1..limit)
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        file.inputStream().use { input ->
            val bytes = ByteArray(32 * 1024)
            while (true) {
                signal.throwIfCanceled()
                val read = input.read(bytes)
                if (read < 0) break
                count += read
                check(count <= limit)
                digest.update(bytes, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
