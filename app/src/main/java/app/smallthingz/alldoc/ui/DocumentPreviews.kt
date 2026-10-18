package app.smallthingz.alldoc.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.CancellationSignal
import android.os.OperationCanceledException
import app.smallthingz.alldoc.state.StoredDocument
import app.smallthingz.alldoc.state.LibraryDocument
import app.smallthingz.alldoc.state.LibraryCatalog
import app.smallthingz.alldoc.sources.DocumentSources
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import app.smallthingz.alldoc.core.takeScalarSafe
import app.smallthingz.alldoc.core.DocumentFormats
import app.smallthingz.alldoc.engine.DocumentSession
import app.smallthingz.alldoc.engine.SheetViewport
import app.smallthingz.alldoc.theme.BitmapTheme
import app.smallthingz.alldoc.theme.RasterRegion
import app.smallthingz.alldoc.theme.ReadingMode
import app.smallthingz.alldoc.theme.ThemeSettings
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.math.min
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/** Bounded original covers, with visible-only recovery when the disposable cache is missing. */
internal class DocumentPreviews(private val directory: () -> File,
    private val executor: Executor = worker, private val memory: PreviewMemory? = null,
    private val recover: ((StoredDocument, CancellationSignal) -> Cached?)? = null,
    private val publish: (String, Bitmap, Long) -> Unit) : Closeable {
    constructor(context: Context, publish: (String, Bitmap, Long) -> Unit) :
        this(cacheDirectory(context, "previews-v8", "previews-v1", "previews-v2", "previews-v3", "previews-v4", "previews-v5", "previews-v6", "previews-v7"), memory = PreviewMemory.forApplication(context),
            recover = { document, signal -> recoverCover(context.applicationContext, document, signal) }, publish = publish)

    private data class Save(val hash: String, val image: Bitmap?, val text: String?, val regions: List<RasterRegion>? = null)
    private val lock = Any()
    private val loads = LinkedHashSet<String>()
    private val visible = LinkedHashMap<String, StoredDocument>()
    private val attempted = HashSet<String>()
    private var recovering: Pair<StoredDocument, CancellationSignal>? = null
    private var pending: Save? = null
    private var active: String? = null
    private var scheduled = false
    private var clearing = false
    private var closed = false
    private var revision = 0L
    private var prepared = false
    private var appearance = ThemeSettings(ReadingMode.ORIGINAL)

    fun setAppearance(value: ThemeSettings): Boolean {
        val cancelled = synchronized(lock) {
            if (closed || appearance == value) return false
            appearance = value
            revision++
            recovering?.first?.hash?.let(attempted::remove)
            active?.let { if (loads.size < MAX_LOADS) loads.add(it) }
            if (pending != null || loads.isNotEmpty()) schedule()
            recovering?.second
        }
        cancelled?.cancel()
        return true
    }

    /** A retained/offscreen Home may keep its pixels, but must not reopen documents. */
    fun setVisible(documents: List<StoredDocument>) {
        val cancelled = synchronized(lock) {
            if (closed) return
            val next = documents.asSequence().filter { validHash(it.hash) }.distinctBy { it.hash }.take(6).associateBy { it.hash }
            if (visible == next) { if (loads.isNotEmpty()) schedule(); return }
            val changed = next.filter { (hash, document) -> visible[hash] != document }.keys
            attempted.retainAll(next.keys)
            attempted.removeAll(changed)
            visible.clear(); visible.putAll(next)
            changed.forEach { if (loads.size < MAX_LOADS) loads.add(it) }
            if (loads.isNotEmpty()) schedule()
            recovering?.takeIf { next[it.first.hash] != it.first }?.second
        }
        cancelled?.cancel()
    }

    fun cached(hash: String): Bitmap? = synchronized(lock) {
        if (closed || !validHash(hash)) null else memory?.get(memoryKey(hash, appearance))?.takeIf { image ->
            image.width >= (visible[hash]?.let { minimumCoverWidth(it.title, it.metadata.mimeType, WIDTH) } ?: 1)
        }
    }

    fun request(hash: String) = synchronized(lock) {
        if (!closed && validHash(hash) && active != hash && (hash in loads || loads.size < MAX_LOADS)) {
            loads.add(hash)
            schedule()
        }
    }
    /** Call off-main with a native thumbnail. The owned copy outlives the source bitmap. */
    fun savePage(hash: String, image: Bitmap, paper: Int, regions: List<RasterRegion>? = null) {
        if (synchronized(lock) { closed || !validHash(hash) }) return
        require(image.width <= WIDTH && image.height <= HEIGHT)
        val snapshot = RasterRegion.snapshot(regions)
        save(Save(hash, page(image, paper), null, snapshot))
    }
    fun saveText(hash: String, text: String) = save(Save(hash, null, text.takeScalarSafe(768)))
    private fun save(value: Save) = synchronized(lock) {
        if (!closed && validHash(value.hash)) {
            pending?.image?.recycle()
            pending = value // One owned thumbnail; rapid page changes replace old work.
            memory?.clear("recent:${value.hash}")
            loads.remove(value.hash)
            schedule()
        } else value.image?.recycle()
    }
    fun clear() {
        val cancelled = synchronized(lock) {
            if (closed) return
            revision++
            pending?.image?.recycle()
            pending = null
            loads.clear(); visible.clear(); attempted.clear()
            clearing = true
            memory?.clear("recent:")
            schedule()
            recovering?.second
        }
        cancelled?.cancel()
    }
    fun isCurrent(value: Long) = synchronized(lock) { !closed && revision == value }
    override fun close() {
        val cancelled = synchronized(lock) {
            closed = true; revision++; pending?.image?.recycle(); pending = null
            loads.clear(); visible.clear(); attempted.clear()
            recovering?.second
        }
        cancelled?.cancel()
    }
    private fun schedule() {
        if (!scheduled) {
            scheduled = true
            try { executor.execute(::drain) }
            catch (failure: Throwable) { scheduled = false; throw failure }
        }
    }

    private fun drain() {
        while (true) {
            val save: Save?
            val hash: String?
            val clear: Boolean
            val generation: Long
            val memoryGeneration: Long
            val treatment: ThemeSettings
            val minimumWidth: Int
            synchronized(lock) {
                if (closed && !clearing || !clearing && pending == null && loads.isEmpty()) { scheduled = false; active = null; return }
                generation = revision
                treatment = appearance
                clear = clearing
                clearing = false
                save = if (clear) null else pending.also { pending = null }
                hash = if (clear) null else save?.hash ?: loads.first().also { loads.remove(it) }
                loads.remove(hash)
                active = hash
                minimumWidth = visible[hash]?.let { minimumCoverWidth(it.title, it.metadata.mimeType, WIDTH) } ?: 1
                // Another owner may have read the old disk cover after this save was queued.
                if (clear) memory?.clear("recent:") else if (save != null) memory?.clear("recent:$hash")
                memoryGeneration = memory?.generation() ?: 0
            }
            var image: Bitmap? = save?.image
            var regions = save?.regions
            var recovery: Pair<StoredDocument, CancellationSignal>? = null
            try {
                if (hash != null && save == null) {
                    val warm = memory?.get(memoryKey(hash, treatment))
                    if (warm != null && warm.width >= minimumWidth) {
                        if (isCurrent(generation)) publish(hash, warm, generation)
                        continue
                    }
                }
                val folder = directory()
                check(folder.isDirectory || folder.mkdirs())
                if (!prepared || clear) {
                    folder.listFiles()?.forEach { file ->
                        if (file.name.startsWith(".pending-") || clear && file.name.matches(CACHE_FILE_NAME)) file.delete()
                    }
                    trim(folder)
                    prepared = true
                }
                if (hash != null) {
                    val file = File(folder, "$hash.png")
                    // Keep an existing page preview when a user later opens its text view.
                    if (image == null) readCached(file, minimumWidth = minimumWidth)?.let { cached ->
                        image = cached.image
                        regions = cached.regions
                    }
                    if (image == null && save != null) {
                        image = text(save.text.orEmpty())
                        regions = emptyList()
                    }
                    if (image == null && recover != null) {
                        recovery = synchronized(lock) {
                            visible[hash]?.takeIf { isCurrent(generation) && attempted.add(hash) }?.let {
                                (it to CancellationSignal()).also { work -> recovering = work }
                            }
                        }
                        recovery?.let { (document, signal) ->
                            val deadline = LibraryPreviews.cancelAfterTimeout(signal)
                            try {
                                recover.invoke(document, signal)?.let { loaded -> image = loaded.image; regions = loaded.regions }
                                signal.throwIfCanceled()
                            } finally { deadline.cancel(false) }
                        }
                    }
                    if (image != null && isCurrent(generation) && (save != null && (save.image != null || !file.isFile) || recovery != null)) {
                        recovery?.second?.throwIfCanceled()
                        write(folder, file, requireNotNull(image), regions, valid = recovery?.let { work -> {
                            synchronized(lock) { isCurrent(generation) && !work.second.isCanceled && visible[hash] == work.first }
                        } })
                    }
                    if (image != null && isCurrent(generation)) {
                        val original = requireNotNull(image)
                        image = BitmapTheme.apply(original, treatment, CancellationSignal(), regions)
                        if (image !== original) original.recycle()
                    }
                    val delivered = synchronized(lock) {
                        if (!isCurrent(generation) || recovery?.let { it.second.isCanceled || visible[hash] != it.first } == true) null else image?.also {
                            memory?.put(memoryKey(hash, treatment), it, memoryGeneration)
                            image = null // Shared bitmaps may still be held by Compose after LRU eviction.
                        }
                    }
                    if (delivered != null) publish(hash, delivered, generation)
                }
            } catch (_: OutOfMemoryError) {
                // Drop this optional preview and keep the worker available for later requests.
            } catch (_: Exception) {
                // Previews are optional. Reading the document must not depend on cache availability.
            } finally {
                image?.recycle()
                synchronized(lock) { active = null; if (recovering === recovery) recovering = null }
            }
        }
    }

    companion object {
        /** Each candidate is rehashed before parsing; neither history nor retained copies are changed. */
        internal fun recoverCover(context: Context, document: StoredDocument, signal: CancellationSignal): Cached? {
            val tried = HashSet<String>()
            fun load(uri: String?): Cached? {
                if (uri == null || !tried.add(uri)) return null
                signal.throwIfCanceled()
                return try {
                    val metadata = document.metadata
                    val input = LibraryDocument(0, document.hash, document.title, metadata.mimeType, metadata.sizeBytes, uri, 0, 0)
                    val result = LibraryPreviews.render(context, input, signal, WIDTH, HEIGHT)
                    Cached(result.image, result.regions)
                } catch (cancelled: OperationCanceledException) { throw cancelled }
                catch (_: Exception) { signal.throwIfCanceled(); null }
            }
            load(document.metadata.sourceUri)?.let { return it }
            load(document.metadata.importUri)?.let { return it }
            signal.throwIfCanceled()
            val retained = try { DocumentSources(context).use { it.importUri(document.hash)?.toString() } }
                catch (cancelled: OperationCanceledException) { throw cancelled }
                catch (_: Exception) { signal.throwIfCanceled(); null }
            load(retained)?.let { return it }
            signal.throwIfCanceled()
            return LibraryCatalog(context).use { catalog ->
                for (uri in catalog.locationsForHash(document.hash, 16)) load(uri)?.let { return@use it }
                null
            }
        }

        private fun memoryKey(hash: String, appearance: ThemeSettings) =
            PreviewMemory.Key("recent:$hash", WIDTH, HEIGHT, appearance)

        internal fun cacheDirectory(context: Context, name: String, vararg obsolete: String): () -> File {
            val application = context.applicationContext
            var folder: File? = null
            return {
                folder ?: application.cacheDir.let { root ->
                    obsolete.forEach { File(root, it).deleteRecursively() }
                    File(root, name).also { folder = it }
                }
            }
        }
        internal data class Cached(val image: Bitmap, val regions: List<RasterRegion>?)

        /** Metadata is bound to the exact original PNG, including partial-write recovery. */
        internal fun minimumCoverWidth(name: String, mime: String?, width: Int): Int =
            if ((DocumentFormats.find(DocumentFormats.routingName("", mime)) ?: DocumentFormats.find(name))?.id == "markdown") width else 1

        internal fun readCached(file: File, width: Int = WIDTH, height: Int = HEIGHT, minimumWidth: Int = 1): Cached? {
            val metadata = regionsFile(file)
            try {
                val encodedMetadata = readBounded(metadata, MAX_REGION_BYTES) ?: error("Missing preview metadata")
                val input = DataInputStream(ByteArrayInputStream(encodedMetadata))
                check(input.readInt() == REGION_MAGIC)
                val count = input.readInt()
                check(count in -1..RasterRegion.LIMIT)
                val digest = ByteArray(32).also(input::readFully)
                val regions = if (count < 0) null else {
                    val values = List(count) { RasterRegion(input.readFloat(), input.readFloat(), input.readFloat(), input.readFloat()) }
                    requireNotNull(RasterRegion.snapshot(values))
                }
                check(input.available() == 0)
                val encoded = readBounded(file, MAX_FILE_BYTES.toInt()) ?: error("Missing preview")
                check(MessageDigest.isEqual(digest, MessageDigest.getInstance("SHA-256").digest(encoded)))
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(encoded, 0, encoded.size, bounds)
                check(bounds.outWidth in minimumWidth..width && bounds.outHeight in 1..height)
                val image = requireNotNull(BitmapFactory.decodeByteArray(encoded, 0, encoded.size,
                    BitmapFactory.Options().apply { inScaled = false }))
                file.setLastModified(System.currentTimeMillis())
                return Cached(image, regions)
            } catch (_: Exception) {
                file.delete()
                metadata.delete()
                return null
            }
        }

        private fun readBounded(file: File, limit: Int): ByteArray? {
            val size = file.length()
            if (!file.isFile || size !in 1..limit.toLong()) return null
            return file.inputStream().use { input ->
                val bytes = ByteArray(size.toInt())
                DataInputStream(input).readFully(bytes)
                check(input.read() == -1)
                bytes
            }
        }

        internal fun regionsFile(file: File) = File(file.parentFile, "${file.nameWithoutExtension}.regions")

        internal fun write(folder: File, file: File, image: Bitmap, regions: List<RasterRegion>? = null,
            valid: (() -> Boolean)? = null) {
            val snapshot = RasterRegion.snapshot(regions)
            val temporary = File.createTempFile(".pending-", ".png", folder)
            var metadataTemporary: File? = null
            var replaced = false
            var committed = false
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                DigestOutputStream(temporary.outputStream(), digest).use {
                    check(image.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
                check(temporary.length() in 1..MAX_FILE_BYTES)
                metadataTemporary = File.createTempFile(".pending-", ".regions", folder)
                DataOutputStream(metadataTemporary.outputStream()).use { output ->
                    output.writeInt(REGION_MAGIC)
                    output.writeInt(snapshot?.size ?: -1)
                    output.write(digest.digest())
                    snapshot?.forEach { region ->
                        output.writeFloat(region.left); output.writeFloat(region.top)
                        output.writeFloat(region.right); output.writeFloat(region.bottom)
                    }
                }
                if (valid != null) {
                    check(valid())
                    // Recovery only fills a miss. A cover already restored by another queued save wins.
                    if (file.exists() || regionsFile(file).exists()) return
                }
                check(temporary.renameTo(file))
                replaced = true
                check(metadataTemporary.renameTo(regionsFile(file)))
                if (valid != null) check(valid())
                committed = true
                trim(folder)
            } finally {
                temporary.delete()
                metadataTemporary?.delete()
                if (replaced && !committed) { file.delete(); regionsFile(file).delete() }
            }
        }
        internal fun trim(folder: File) {
            val entries = folder.listFiles() ?: return
            entries.filter { it.isFile && it.extension == "regions" && it.name.matches(CACHE_FILE_NAME) }
                .forEach { if (!File(folder, "${it.nameWithoutExtension}.png").isFile) it.delete() }
            val files = entries.filter { it.isFile && it.name.matches(FILE_NAME) }.sortedByDescending { it.lastModified() }
            var bytes = 0L
            var count = 0
            files.forEach { file ->
                val metadata = regionsFile(file)
                val size = file.length() + metadata.length()
                if (!metadata.isFile || count >= MAX_FILES || bytes + size > MAX_BYTES) {
                    file.delete(); metadata.delete()
                } else { count++; bytes += size }
            }
        }
        /** Render at cover size so a filtered resize cannot change paper/ink classification. */
        internal fun nativePage(session: DocumentSession, index: Int, signal: CancellationSignal,
            width: Int = WIDTH, height: Int = HEIGHT): Bitmap {
            require(width > 0 && height > 0)
            signal.throwIfCanceled()
            if (session.info.continuous) session.renderCover(index, width, height, signal)?.let { image ->
                try {
                    signal.throwIfCanceled()
                    check(image.width == width && image.height in 1..height) { "Preview exceeds its size limit." }
                    return image
                } catch (failure: Throwable) { image.recycle(); throw failure }
            }
            var aspect = session.pageAspect(index, signal)
            require(aspect.isFinite() && aspect > 0f)
            // Some formats use a placeholder until their first page has rendered.
            for (attempt in 0..1) {
                signal.throwIfCanceled()
                val requested = min(width, (height / aspect).toInt()).coerceAtLeast(1)
                val image = session.renderPage(index, requested, ThemeSettings(ReadingMode.ORIGINAL), signal)
                try {
                    signal.throwIfCanceled()
                    if (image.width <= width && image.height <= height) return image
                    aspect = image.height.toFloat() / image.width
                } catch (failure: Throwable) { image.recycle(); throw failure }
                image.recycle()
                if (requested == 1) break
            }
            error("Preview exceeds its size limit.")
        }

        /** Grid covers do not resolve workbook print pages or borrow their raster masks. */
        internal fun nativeSheet(session: DocumentSession, signal: CancellationSignal,
            width: Int = WIDTH, height: Int = HEIGHT, viewport: SheetViewport? = null): Cached {
            require(width in 1..512 && height in 1..512)
            signal.throwIfCanceled()
            val request = if (viewport != null) {
                viewport.checked()
                val fit = min(1.0, min(width.toDouble() / viewport.width, height.toDouble() / viewport.height))
                // At minimum zoom, preserve the viewed area before reducing its bounded raster.
                val factor = max(fit, .0625 / viewport.scale)
                viewport.copy(scale = (viewport.scale * factor).coerceIn(.0625, 32.0),
                    width = ceil(viewport.width * factor).toInt().coerceAtMost(viewport.width),
                    height = ceil(viewport.height * factor).toInt().coerceAtMost(viewport.height))
            } else {
                val workbook = requireNotNull(session.workbook(signal))
                val sheet = requireNotNull(workbook.sheets.firstOrNull { it.index == workbook.activeSheet } ?: workbook.sheets.firstOrNull())
                val geometry = requireNotNull(session.sheetGeometry(sheet.index, signal))
                require(geometry.sheet.index == sheet.index)
                SheetViewport(sheet.index, 0.0, 0.0, min(width / 480.0, height / 640.0).coerceIn(.0625, 1.0), width, height)
            }.checked()
            var image = session.renderSheet(request, ThemeSettings(ReadingMode.ORIGINAL), signal)
            try {
                signal.throwIfCanceled()
                check(image.width == request.width && image.height == request.height) { "Preview exceeds its size limit." }
                val regions = RasterRegion.snapshot(session.sheetRasterRegions(request, signal))
                signal.throwIfCanceled()
                if (image.width > width || image.height > height) {
                    val source = image
                    image = page(source, android.graphics.Color.TRANSPARENT, width, height)
                    source.recycle()
                }
                signal.throwIfCanceled()
                return Cached(image, regions)
            } catch (failure: Throwable) { image.recycle(); throw failure }
        }

        internal fun page(source: Bitmap, paper: Int, width: Int = WIDTH, height: Int = HEIGHT): Bitmap {
            check(!source.isRecycled)
            val scale = min(1f, min(width.toFloat() / source.width, height.toFloat() / source.height))
            val image = Bitmap.createBitmap((source.width * scale).roundToInt().coerceAtLeast(1),
                (source.height * scale).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            try {
                image.eraseColor(paper)
                Canvas(image).drawBitmap(source, null, RectF(0f, 0f, image.width.toFloat(), image.height.toFloat()),
                    Paint(Paint.FILTER_BITMAP_FLAG))
                return image
            } catch (failure: Throwable) { image.recycle(); throw failure }
        }
        internal fun text(value: String, width: Int = WIDTH, height: Int = HEIGHT): Bitmap {
            val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                image.eraseColor(PAPER)
                val padding = 10f * width / WIDTH
                val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f * width / WIDTH; color = 0xff202124.toInt() }
                val layout = StaticLayout.Builder.obtain(value, 0, value.length, paint, width - (2 * padding).roundToInt())
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).setMaxLines(12).build()
                val canvas = Canvas(image)
                canvas.clipRect(padding, padding, width - padding, height - padding)
                canvas.translate(padding, padding)
                layout.draw(canvas)
                return image
            } catch (failure: Throwable) { image.recycle(); throw failure }
        }
        internal const val WIDTH = 144
        internal const val HEIGHT = 192
        internal const val MAX_FILES = 64
        internal const val MAX_BYTES = 8L * 1024 * 1024
        private const val MAX_FILE_BYTES = 256L * 1024
        private const val MAX_LOADS = 64
        private const val REGION_MAGIC = 0x41525031 // ARP1
        private const val MAX_REGION_BYTES = 40 + 16 * RasterRegion.LIMIT
        internal const val PAPER = 0xffF7F8FA.toInt()
        internal val FILE_NAME = Regex("[a-f0-9]{64}\\.png")
        internal val CACHE_FILE_NAME = Regex("[a-f0-9]{64}\\.(png|regions)")
        internal fun validHash(value: String) = value.length == 64 && value.all { it in 'a'..'f' || it in '0'..'9' }
        private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "alldoc-previews").apply { isDaemon = true } }
    }
}
