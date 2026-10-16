package app.smallthingz.alldoc.ui

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.CancellationSignal
import android.os.Process
import app.smallthingz.alldoc.core.takeScalarSafe
import app.smallthingz.alldoc.sources.DocumentSources
import app.smallthingz.alldoc.sources.LibraryInput
import app.smallthingz.alldoc.sources.LibrarySourceChangedException
import app.smallthingz.alldoc.sources.LibrarySourceMissingException
import app.smallthingz.alldoc.state.LibraryCategory
import app.smallthingz.alldoc.state.LibraryDocument
import app.smallthingz.alldoc.theme.BitmapTheme
import app.smallthingz.alldoc.theme.ReadingMode
import app.smallthingz.alldoc.theme.ThemeSettings
import java.io.Closeable
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Optional, visible-only covers. Never opens the reader or changes its history/retained copies. */
internal class LibraryPreviews(
    private val directory: () -> File,
    private val load: (LibraryDocument, CancellationSignal) -> Preview,
    private val executor: Executor = worker,
    private val onSourceChanged: (LibraryDocument) -> Unit = {},
    private val onSourceMissing: (LibraryDocument) -> Unit = {},
    private val validate: (LibraryDocument) -> Unit = {},
    private val publish: (LibraryDocument, Bitmap, Long) -> Unit,
) : Closeable {
    data class Preview(val hash: String, val image: Bitmap, val mimeType: String?)
    private data class Work(val document: LibraryDocument, val signal: CancellationSignal, val generation: Long, val appearance: ThemeSettings)

    constructor(context: Context, onSourceChanged: (LibraryDocument) -> Unit = {},
        onSourceMissing: (LibraryDocument) -> Unit = {}, publish: (LibraryDocument, Bitmap, Long) -> Unit) : this(
        DocumentPreviews.cacheDirectory(context, "library-previews-v4", "library-previews-v1", "library-previews-v2", "library-previews-v3"),
        { document, signal -> render(context.applicationContext, document, signal) }, onSourceChanged = onSourceChanged,
        onSourceMissing = onSourceMissing, validate = { document ->
            if (LibraryInput.isMissingPrivateFile(context.applicationContext, Uri.parse(document.uri)))
                throw LibrarySourceMissingException()
        }, publish = publish)

    private val lock = Any()
    private val visible = LinkedHashMap<Long, LibraryDocument>()
    private val attempted = HashMap<Long, Long>()
    private var running: Work? = null
    private var scheduled = false
    private var clearing = false
    private var prepared = false
    private var closed = false
    private var generation = 0L
    private var appearance = ThemeSettings(ReadingMode.ORIGINAL)

    /** Disk covers stay original; only the bounded visible window is color-treated. */
    fun setAppearance(value: ThemeSettings) {
        val cancel = synchronized(lock) {
            if (closed || appearance == value) return
            appearance = value
            generation++
            attempted.clear()
            if (visible.isNotEmpty()) schedule()
            running?.signal
        }
        cancel?.cancel()
    }

    /** Supply only actual viewport items; one job is selected at a time, without per-item executor tasks. */
    fun setVisible(documents: List<LibraryDocument>, active: Boolean = true) {
        val cancel = synchronized(lock) {
            if (closed) return
            visible.clear()
            if (active) for (document in documents) {
                if (document.hash == null || DocumentPreviews.validHash(document.hash)) visible.putIfAbsent(document.id, document)
            }
            attempted.entries.removeAll { (id, revision) -> visible[id]?.revision != revision }
            if (!active) generation++
            val previous = running?.takeIf { visible[it.document.id]?.revision != it.document.revision }
            if (clearing || visible.values.any { attempted[it.id] != it.revision }) schedule()
            previous?.signal
        }
        cancel?.cancel()
    }

    fun isCurrent(id: Long, revision: Long, value: Long): Boolean = synchronized(lock) {
        !closed && generation == value && visible[id]?.revision == revision
    }

    fun clear() {
        val cancel = synchronized(lock) {
            if (closed) return
            generation++
            attempted.clear()
            clearing = true
            schedule()
            running?.signal
        }
        cancel?.cancel()
    }

    override fun close() {
        val cancel = synchronized(lock) {
            closed = true
            generation++
            visible.clear()
            attempted.clear()
            running?.signal
        }
        cancel?.cancel()
    }

    private fun schedule() {
        if (!scheduled) {
            scheduled = true
            try { executor.execute(::drain) }
            catch (_: OutOfMemoryError) { scheduled = false } // A later visible request can retry.
            catch (failure: Throwable) { scheduled = false; throw failure }
        }
    }

    private fun drain() {
        while (true) {
            val clear: Boolean
            val work: Work?
            synchronized(lock) {
                clear = clearing
                clearing = false
                val next = if (clear || closed) null else visible.values.firstOrNull { attempted[it.id] != it.revision }
                if (!clear && next == null) { scheduled = false; running = null; return }
                work = next?.let {
                    attempted[it.id] = it.revision
                    Work(it, CancellationSignal(), generation, appearance)
                }
                running = work
            }
            var image: Bitmap? = null
            var deadline: java.util.concurrent.Future<*>? = null
            try {
                if (work != null) deadline = timeouts.schedule({ work.signal.cancel() }, TIMEOUT_SECONDS, TimeUnit.SECONDS)
                val folder = try {
                    directory().also {
                        check(it.isDirectory || it.mkdirs())
                        if (!prepared || clear) {
                            it.listFiles()?.forEach { file ->
                                if (file.name.startsWith(".pending-") || clear && file.name.matches(DocumentPreviews.FILE_NAME)) file.delete()
                            }
                            DocumentPreviews.trim(it)
                            prepared = true
                        }
                    }
                } catch (_: Exception) { null } // Unavailable cache storage must not prevent a visible preview.
                if (work != null) {
                    val document = work.document
                    work.signal.throwIfCanceled()
                    validate(document)
                    work.signal.throwIfCanceled()
                    image = if (folder != null && document.hash != null)
                        DocumentPreviews.read(cacheFile(folder, document.hash, document.mimeType), WIDTH, HEIGHT) else null
                    var hash = document.hash
                    var mime = document.mimeType
                    if (image == null) {
                        val loaded = load(document, work.signal)
                        try {
                            work.signal.throwIfCanceled()
                            check(DocumentPreviews.validHash(loaded.hash) && (hash == null || hash == loaded.hash))
                            hash = loaded.hash
                            mime = loaded.mimeType
                            image = DocumentPreviews.page(loaded.image, android.graphics.Color.TRANSPARENT, WIDTH, HEIGHT)
                        } finally { loaded.image.recycle() }
                        if (folder != null && current(work)) try {
                            DocumentPreviews.write(folder, cacheFile(folder, requireNotNull(hash), mime), requireNotNull(image))
                        } catch (_: Exception) { /* The in-memory preview remains useful. */ }
                    }
                    if (current(work)) {
                        val original = requireNotNull(image)
                        image = BitmapTheme.apply(original, work.appearance, work.signal)
                        if (image !== original) original.recycle()
                    }
                    if (current(work)) {
                        publish(document.copy(hash = requireNotNull(hash), mimeType = mime,
                            category = LibraryCategory.of(document.title, mime)), requireNotNull(image), work.generation)
                        image = null // Compose may retain a delivered bitmap after its map entry is evicted.
                    }
                }
            } catch (_: LibrarySourceChangedException) {
                if (work != null && current(work)) try { onSourceChanged(work.document) }
                catch (_: OutOfMemoryError) { /* Optional notification; keep the worker usable. */ }
                catch (_: Exception) { /* The owner may have closed before notification. */ }
            } catch (_: LibrarySourceMissingException) {
                if (work != null && current(work)) try { onSourceMissing(work.document) }
                catch (_: OutOfMemoryError) { /* Optional notification; keep the worker usable. */ }
                catch (_: Exception) { /* The owner may have closed before notification. */ }
            } catch (_: OutOfMemoryError) {
                // A failed optional cover must not terminate this worker or block the next item.
            } catch (_: Exception) {
                // Unsupported, protected, changed or unavailable documents keep their format fallback.
            } finally {
                deadline?.cancel(false)
                image?.recycle()
                synchronized(lock) { if (running === work) running = null }
            }
        }
    }

    private fun current(work: Work) = !work.signal.isCanceled &&
        isCurrent(work.document.id, work.document.revision, work.generation)

    companion object {
        internal const val WIDTH = 240
        internal const val HEIGHT = 320
        private const val TIMEOUT_SECONDS = 15L
        private val worker = Executors.newSingleThreadExecutor { task -> Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            task.run()
        }, "alldoc-library-previews").apply { isDaemon = true } }
        private val timeouts = ScheduledThreadPoolExecutor(1) { task ->
            Thread(task, "alldoc-preview-deadline").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }

        // Include the parsed type: a cover cannot confirm an old alias's provisional MIME.
        private fun cacheFile(folder: File, hash: String, mime: String?): File {
            val key = MessageDigest.getInstance("SHA-256").digest("$hash:${mime.orEmpty()}".toByteArray()).toHexString()
            return File(folder, "$key.png")
        }

        private fun render(context: Context, document: LibraryDocument, signal: CancellationSignal): Preview {
            var image: Bitmap? = null
            var mime: String? = null
            try {
                val hash = DocumentSources(context).use { sources ->
                    sources.openIndexed(document, signal).use { prepared ->
                        val session = prepared.session
                        mime = session.info.mimeType
                        image = if (session.info.reflowable && !session.info.continuous)
                            DocumentPreviews.text(session.bodyText(signal).takeScalarSafe(768), WIDTH, HEIGHT)
                        else DocumentPreviews.nativePage(session, 0, signal, WIDTH, HEIGHT)
                        signal.throwIfCanceled()
                        prepared.hash
                    }
                }
                return Preview(hash, requireNotNull(image), mime).also { image = null }
            } finally { image?.recycle() }
        }
    }
}
