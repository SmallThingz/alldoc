package app.smallthingz.alldoc.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.CancellationSignal
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import app.smallthingz.alldoc.core.takeScalarSafe
import app.smallthingz.alldoc.engine.DocumentSession
import app.smallthingz.alldoc.theme.BitmapTheme
import app.smallthingz.alldoc.theme.ReadingMode
import app.smallthingz.alldoc.theme.ThemeSettings
import java.io.Closeable
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.math.min
import kotlin.math.roundToInt

/** Small, disposable previews of content already opened by the reader. Never owns a session. */
internal class DocumentPreviews(private val directory: () -> File,
    private val executor: Executor = worker, private val publish: (String, Bitmap, Long) -> Unit) : Closeable {
    constructor(context: Context, publish: (String, Bitmap, Long) -> Unit) :
        this(cacheDirectory(context, "previews-v5", "previews-v1", "previews-v2", "previews-v3", "previews-v4"), publish = publish)

    private data class Save(val hash: String, val image: Bitmap?, val text: String?)
    private val lock = Any()
    private val loads = LinkedHashSet<String>()
    private var pending: Save? = null
    private var active: String? = null
    private var scheduled = false
    private var clearing = false
    private var closed = false
    private var revision = 0L
    private var prepared = false
    private var appearance = ThemeSettings(ReadingMode.ORIGINAL)

    fun setAppearance(value: ThemeSettings): Boolean = synchronized(lock) {
        if (closed || appearance == value) return false
        appearance = value
        revision++
        active?.let { if (loads.size < MAX_LOADS) loads.add(it) }
        if (pending != null || loads.isNotEmpty()) schedule()
        true
    }

    fun request(hash: String) = synchronized(lock) {
        if (!closed && validHash(hash) && active != hash && (hash in loads || loads.size < MAX_LOADS)) {
            loads.add(hash)
            schedule()
        }
    }
    /** Call off-main with a native thumbnail. The owned copy outlives the source bitmap. */
    fun savePage(hash: String, image: Bitmap, paper: Int) {
        if (synchronized(lock) { closed || !validHash(hash) }) return
        require(image.width <= WIDTH && image.height <= HEIGHT)
        save(Save(hash, page(image, paper), null))
    }
    fun saveText(hash: String, text: String) = save(Save(hash, null, text.takeScalarSafe(768)))
    private fun save(value: Save) = synchronized(lock) {
        if (!closed && validHash(value.hash)) {
            pending?.image?.recycle()
            pending = value // One owned thumbnail; rapid page changes replace old work.
            loads.remove(value.hash)
            schedule()
        } else value.image?.recycle()
    }
    fun clear() = synchronized(lock) {
        if (!closed) {
            revision++
            pending?.image?.recycle()
            pending = null
            loads.clear()
            clearing = true
            schedule()
        }
    }
    fun isCurrent(value: Long) = synchronized(lock) { !closed && revision == value }
    override fun close() = synchronized(lock) { closed = true; revision++; pending?.image?.recycle(); pending = null; loads.clear() }
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
            val treatment: ThemeSettings
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
            }
            var image: Bitmap? = save?.image
            try {
                val folder = directory()
                check(folder.isDirectory || folder.mkdirs())
                if (!prepared || clear) {
                    folder.listFiles()?.forEach { file ->
                        if (file.name.startsWith(".pending-") || clear && file.name.matches(FILE_NAME)) file.delete()
                    }
                    trim(folder)
                    prepared = true
                }
                if (hash != null) {
                    val file = File(folder, "$hash.png")
                    // Keep an existing page preview when a user later opens its text view.
                    if (image == null) image = read(file)
                    if (image == null && save != null) image = text(save.text.orEmpty())
                    if (image != null && save != null && (save.image != null || !file.isFile))
                        write(folder, file, requireNotNull(image))
                    if (image != null && isCurrent(generation)) {
                        val original = requireNotNull(image)
                        image = BitmapTheme.apply(original, treatment, CancellationSignal())
                        if (image !== original) original.recycle()
                    }
                    if (image != null && isCurrent(generation)) {
                        publish(hash, requireNotNull(image), generation)
                        image = null // Published bitmaps may still be held by Compose after LRU eviction.
                    }
                }
            } catch (_: OutOfMemoryError) {
                // Drop this optional preview and keep the worker available for later requests.
            } catch (_: Exception) {
                // Previews are optional. Reading the document must not depend on cache availability.
            } finally {
                image?.recycle()
                synchronized(lock) { active = null }
            }
        }
    }

    companion object {
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
        internal fun read(file: File, width: Int = WIDTH, height: Int = HEIGHT): Bitmap? {
            if (!file.isFile) return null
            if (file.length() !in 1..MAX_FILE_BYTES) { file.delete(); return null }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            if (bounds.outWidth !in 1..width || bounds.outHeight !in 1..height) { file.delete(); return null }
            val image = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inScaled = false })
            if (image == null) file.delete() else file.setLastModified(System.currentTimeMillis())
            return image
        }
        internal fun write(folder: File, file: File, image: Bitmap) {
            val temporary = File.createTempFile(".pending-", ".png", folder)
            try {
                temporary.outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                check(temporary.length() <= MAX_FILE_BYTES)
                check(temporary.renameTo(file))
                trim(folder)
            } finally { temporary.delete() }
        }
        internal fun trim(folder: File) {
            val files = folder.listFiles()?.filter { it.isFile && it.name.matches(FILE_NAME) }
                ?.sortedByDescending { it.lastModified() } ?: return
            var bytes = 0L
            files.forEachIndexed { index, file ->
                bytes += file.length()
                if (index >= MAX_FILES || bytes > MAX_BYTES) file.delete()
            }
        }
        /** Render at cover size so a filtered resize cannot change paper/ink classification. */
        internal fun nativePage(session: DocumentSession, index: Int, signal: CancellationSignal,
            width: Int = WIDTH, height: Int = HEIGHT): Bitmap {
            require(width > 0 && height > 0)
            signal.throwIfCanceled()
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
        internal const val PAPER = 0xffF7F8FA.toInt()
        internal val FILE_NAME = Regex("[a-f0-9]{64}\\.png")
        internal fun validHash(value: String) = value.length == 64 && value.all { it in 'a'..'f' || it in '0'..'9' }
        private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "alldoc-previews").apply { isDaemon = true } }
    }
}
