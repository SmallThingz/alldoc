package app.smallthingz.alldoc.render

import app.smallthingz.alldoc.theme.RasterRegion
import app.smallthingz.alldoc.engine.PageRegion
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.Locale

/** Worker-only visual adapter. It never edits sources or navigates the UI. */
object DocRenderCore {
    const val INPUT_LIMIT = 16 * 1024 * 1024
    const val OUTPUT_LIMIT = 16 * 1024 * 1024
    const val PAGE_LIMIT = 1024
    const val SIDE_LIMIT = 2800
    const val PIXEL_LIMIT = 8_000_000
    const val RECEIPT_OVERHEAD = 8192 + 52 + 64 * 16
    fun interface Check { fun cancelled(): Boolean }
    class ViewState(
        @JvmField val page: Int, @JvmField val generation: Long,
        @JvmField val sourceKey: String, @JvmField val readOnly: Boolean = true
    ) {
        init { require(page >= 0 && sourceKey.length <= 8192) { "Invalid render state" } }
    }
    class RenderedPage internal constructor(
        @JvmField val format: Int, @JvmField val width: Int, @JvmField val height: Int,
        @JvmField val page: Int, @JvmField val pageCount: Int,
        @JvmField val note: String, private val bytes: ByteArray, @JvmField val state: ViewState,
        @JvmField val rasterRegions: List<RasterRegion>? = null
    ) {
        @JvmField val fidelity = "VISUAL_BEST_EFFORT"
        fun copyBytes(): ByteArray = bytes.clone()
        /** Internal readers share this immutable payload; public callers get an isolated copy. */
        internal fun cachedBytes(): ByteArray = bytes
        fun encodedBytes(): Int = bytes.size
        companion object { const val PNG = 1; const val PDF = 2 }
    }
    @Volatile private var module: RenderProcess.Module? = null
    private val cache = RenderCache()
    @JvmStatic fun configure(executable: File, launcher: File, privateCache: File) {
        module = RenderProcess.Module(executable, launcher, privateCache)
    }
    @JvmStatic fun configure(context: android.content.Context) {
        val native = File(context.applicationInfo.nativeLibraryDir)
        val directory = cache.prepare(context.cacheDir, android.os.Process.myPid()) { File("/proc/$it").exists() }
        configure(File(native, "liballrender.so"), File(native, "liballrun.so"), directory)
    }
    internal fun workbook(snapshot: File): WorkbookProcess =
        WorkbookProcess(module ?: throw IOException("Worksheet renderer unavailable"), snapshot)
    @JvmStatic fun isAvailable(): Boolean = module?.available() == true
    private fun extension(name: String?): String {
        if (name == null) return ""
        val at = name.lastIndexOf('.')
        return if (at < 0) "" else name.substring(at + 1).lowercase(Locale.ROOT)
    }
    @JvmStatic fun kind(name: String?): Int = when (extension(name)) {
        "docx", "docm", "dotx", "dotm" -> 1
        "pptx", "pptm", "ppsx", "ppsm", "potx", "potm" -> 2
        "xlsx", "xlsm", "xltx", "xltm" -> 3
        else -> 0
    }
    @JvmStatic fun supportsName(name: String?): Boolean = kind(name) != 0

    @JvmStatic @Throws(IOException::class)
    fun render(ownedSnapshot: ByteArray?, name: String?, state: ViewState?, maxSide: Int, check: Check?): RenderedPage {
        if (ownedSnapshot == null || ownedSnapshot.isEmpty() || ownedSnapshot.size > INPUT_LIMIT)
            throw IOException("Visual input supports 1 byte through 16 MiB")
        requireNotNull(state) { "state" }
        if (maxSide < 128 || maxSide > SIDE_LIMIT) throw IOException("Visual output side supports 128 through 2800")
        val kind = kind(name)
        if (kind == 0) throw IOException("No visual renderer for this format")
        val poll = Check { Thread.currentThread().isInterrupted || check?.cancelled() == true }
        if (poll.cancelled()) throw InterruptedIOException("Visual preview cancelled")
        val installed = module
        if (installed == null || !installed.available()) throw IOException("Visual renderer module is unavailable")
        val immutable = ownedSnapshot.clone()
        val wire = RenderProcess.run(installed, immutable, null, kind, state.page, maxSide, poll)
        if (poll.cancelled()) throw InterruptedIOException("Visual preview cancelled")
        return decode(wire, state)
    }

    /** The caller owns this immutable private snapshot until rendering completes. */
    @JvmStatic @Throws(IOException::class)
    fun renderFile(snapshot: File, name: String, state: ViewState, maxSide: Int, check: Check?): RenderedPage {
        if (!snapshot.isFile || snapshot.length() !in 1..INPUT_LIMIT.toLong())
            throw IOException("Visual input supports 1 byte through 16 MiB")
        if (maxSide !in 128..SIDE_LIMIT) throw IOException("Visual output side supports 128 through 2800")
        val kind = kind(name)
        if (kind == 0) throw IOException("No visual renderer for this format")
        val poll = Check { Thread.currentThread().isInterrupted || check?.cancelled() == true }
        if (poll.cancelled()) throw InterruptedIOException("Visual preview cancelled")
        val installed = module
        if (installed == null || !installed.available()) throw IOException("Visual renderer module is unavailable")
        val wire = RenderProcess.run(installed, null, snapshot, kind, state.page, maxSide, poll)
        if (poll.cancelled()) throw InterruptedIOException("Visual preview cancelled")
        return decode(wire, state)
    }

    /** Render original document geometry directly into a bounded source-region raster. */
    @JvmStatic @Throws(IOException::class)
    fun renderRegionFile(snapshot: File, name: String, state: ViewState, region: PageRegion, check: Check?): RenderedPage {
        if (!snapshot.isFile || snapshot.length() !in 1..INPUT_LIMIT.toLong())
            throw IOException("Visual input supports 1 byte through 16 MiB")
        val kind = kind(name)
        if (kind == 0) throw IOException("No visual renderer for this format")
        val poll = Check { Thread.currentThread().isInterrupted || check?.cancelled() == true }
        if (poll.cancelled()) throw InterruptedIOException("Visual preview cancelled")
        val installed = module
        if (installed == null || !installed.available()) throw IOException("Visual renderer module is unavailable")
        val wire = RenderProcess.run(installed, null, snapshot, kind, state.page, 0, poll, region)
        if (poll.cancelled()) throw InterruptedIOException("Visual preview cancelled")
        return decodeRegion(wire, state, region)
    }

    @JvmStatic @Throws(IOException::class)
    fun decode(wire: ByteArray?, state: ViewState): RenderedPage = decode(wire, state, null)

    @JvmStatic @Throws(IOException::class)
    fun decodeRegion(wire: ByteArray?, state: ViewState, region: PageRegion): RenderedPage = decode(wire, state, region)

    private fun decode(wire: ByteArray?, state: ViewState, region: PageRegion?): RenderedPage {
        if (wire == null || wire.size < 32 || wire.size > OUTPUT_LIMIT + RECEIPT_OVERHEAD)
            throw IOException("Invalid visual renderer receipt")
        if (wire[0] != 'Z'.code.toByte() || wire[1] != 'F'.code.toByte() ||
            wire[2] != 'R'.code.toByte() || wire[3] !in byteArrayOf('1'.code.toByte(), '2'.code.toByte(), '3'.code.toByte()))
            throw IOException("Unsupported visual receipt version")
        val regionReceipt = wire[3] == '3'.code.toByte()
        if (regionReceipt != (region != null)) throw IOException("Visual receipt does not match the requested region")
        val hasRegions = wire[3] != '1'.code.toByte()
        if ((hasRegions && wire.size < 36) || (regionReceipt && wire.size < 52))
            throw IOException("Invalid visual renderer receipt")
        val b = ByteBuffer.wrap(wire).order(ByteOrder.LITTLE_ENDIAN)
        b.position(4)
        val format = b.int
        val w = b.int
        val h = b.int
        val page = b.int
        val pages = b.int
        val noteLen = b.int
        val dataLen = b.int
        val count = if (hasRegions) b.int else -1
        if (regionReceipt) {
            val requested = requireNotNull(region)
            if (b.float != requested.left || b.float != requested.top || b.float != requested.right || b.float != requested.bottom)
                throw IOException("Visual receipt region changed")
        }
        if (count !in -1..64) throw IOException("Invalid visual raster-region count")
        val regionBytes = count.coerceAtLeast(0) * 16
        if (noteLen < 0 || noteLen > 8192 || dataLen < 0 || dataLen > OUTPUT_LIMIT ||
            noteLen.toLong() + dataLen + regionBytes != b.remaining().toLong())
            throw IOException("Invalid visual receipt lengths")
        var validRegions = count >= 0
        val regions = ArrayList<RasterRegion>(count.coerceAtLeast(0))
        repeat(count.coerceAtLeast(0)) {
            val l = b.float; val t = b.float; val r = b.float; val bottom = b.float
            if (!l.isFinite() || !t.isFinite() || !r.isFinite() || !bottom.isFinite() ||
                l < 0f || t < 0f || r > 1f || bottom > 1f || r <= l || bottom <= t) validRegions = false
            else regions.add(RasterRegion(l, t, r, bottom))
        }
        val rasterRegions = if (validRegions) java.util.Collections.unmodifiableList(regions) else null
        val note = ByteArray(noteLen)
        b.get(note)
        val message = String(note, StandardCharsets.UTF_8)
        if (format == 0) {
            if (message == "CANCELLED") throw InterruptedIOException("Visual preview cancelled")
            throw IOException(message)
        }
        val sideLimit = if (regionReceipt) PageRegion.MAX_EDGE else SIDE_LIMIT
        if ((format != 1 && format != 2) || w < 1 || h < 1 || w > sideLimit || h > sideLimit ||
            w.toLong() * h > PIXEL_LIMIT || pages < 1 || pages > PAGE_LIMIT || page < 0 ||
            page >= pages || page != state.page || dataLen < 8) throw IOException("Invalid visual page metadata")
        if (region != null && (format != RenderedPage.PNG || w != region.width || h != region.height))
            throw IOException("Visual receipt dimensions differ from the requested region")
        val data = ByteArray(dataLen)
        b.get(data)
        if (format == 1) {
            if (dataLen < 24 || data[0] != 137.toByte() || data[1] != 'P'.code.toByte() ||
                data[2] != 'N'.code.toByte() || data[3] != 'G'.code.toByte())
                throw IOException("Visual PNG data is invalid")
            val png = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
            if (png.getInt(16) != w || png.getInt(20) != h)
                throw IOException("Visual PNG dimensions differ from receipt")
        } else if (data[0] != '%'.code.toByte() || data[1] != 'P'.code.toByte() ||
            data[2] != 'D'.code.toByte() || data[3] != 'F'.code.toByte() || data[4] != '-'.code.toByte())
            throw IOException("Visual PDF data is invalid")
        return RenderedPage(format, w, h, page, pages, message, data, state, rasterRegions)
    }
}
