package app.smallthingz.alldoc.ipc

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.os.Binder
import android.os.Bundle
import android.os.CancellationSignal
import android.os.IBinder
import android.os.ParcelFileDescriptor
import app.smallthingz.alldoc.api.AllDocClient
import app.smallthingz.alldoc.api.IAllDocCallback
import app.smallthingz.alldoc.api.IAllDocService
import app.smallthingz.alldoc.api.IAllDocSession
import app.smallthingz.alldoc.core.takeScalarSafe
import app.smallthingz.alldoc.engine.DocumentEngine
import app.smallthingz.alldoc.engine.DocumentSession
import app.smallthingz.alldoc.engine.SheetViewport
import app.smallthingz.alldoc.theme.ImageTreatment
import app.smallthingz.alldoc.theme.ReadingMode
import app.smallthingz.alldoc.theme.ThemeSettings
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Source access is a descriptor capability. No path or URI is opened on a caller's behalf. */
class DocumentService : Service() {
    private val sessions = HashSet<Session>()
    @Volatile private var stopping = false
    private val workers = ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, ArrayBlockingQueue(8),
        { task -> Thread(task, "alldoc-document").apply { isDaemon = true } })
    private val closers = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue(8),
        { task -> Thread(task, "alldoc-close").apply { isDaemon = true } })
    private val timer = ScheduledThreadPoolExecutor(1).apply { removeOnCancelPolicy = true }
    private val service = object : IAllDocService.Stub() {
        override fun version() = AllDocClient.VERSION
        override fun open(input: ParcelFileDescriptor?, name: String?, mime: String?, password: String?,
                          callback: IAllDocCallback?): IAllDocSession =
            openSession(input, name, mime, password, callback, false)
        override fun openForPreview(input: ParcelFileDescriptor?, name: String?, mime: String?, password: String?,
                                    callback: IAllDocCallback?): IAllDocSession =
            openSession(input, name, mime, password, callback, true)
    }

    private fun openSession(input: ParcelFileDescriptor?, name: String?, mime: String?, password: String?,
                            callback: IAllDocCallback?, preview: Boolean): IAllDocSession = memorySafe {
        requireNotNull(input) { "Source descriptor required" }
        var adopted = false
        try {
            requireNotNull(name); requireNotNull(mime); requireNotNull(password); requireNotNull(callback)
            require(name.length <= 512 && mime.length <= 128 && password.length <= 256) { "Input metadata is too long" }
            val owner = Binder.getCallingUid()
            val session = synchronized(sessions) {
                check(!stopping) { "Document service is stopping" }
                check(sessions.size < 8 && sessions.count { it.owner == owner && !it.closed } < 2) { "Too many open documents" }
                Session(owner, callback).also { sessions.add(it) }
            }
            try {
                memorySafe {
                    callback.asBinder().linkToDeath(session, 0)
                    session.start(input, name, mime, password, preview)
                }
                adopted = true
                session
            } catch (error: Exception) {
                session.finish()
                throw error
            }
        } finally { if (!adopted) input.close() }
    }

    override fun onBind(intent: Intent): IBinder = service
    override fun onDestroy() {
        stopping = true
        synchronized(sessions) { sessions.toList() }.forEach { it.finish() }
        workers.shutdown()
        closers.shutdown()
        timer.shutdownNow()
        super.onDestroy()
    }

    private inner class Job {
        val signal = CancellationSignal()
    }

    private inline fun <T> memorySafe(work: () -> T): T = try { work() }
        catch (_: OutOfMemoryError) { throw IllegalStateException("Not enough memory to read this document") }

    private fun submit(job: Job, work: () -> Unit) = memorySafe {
        val deadline = timer.schedule({ job.signal.cancel() }, 60, TimeUnit.SECONDS)
        // Cancelled queued jobs still run their ownership cleanup.
        try { memorySafe { workers.execute { try { work() } finally { deadline.cancel(false) } } } }
        catch (error: RuntimeException) { deadline.cancel(false); job.signal.cancel(); throw error }
    }

    private inner class Session(val owner: Int, private val callback: IAllDocCallback) :
        IAllDocSession.Stub(), IBinder.DeathRecipient {
        private val access = ReentrantLock()
        private val stateLock = Any()
        private val jobs = HashMap<Long, Job>()
        private val opening = Job()
        private var document: DocumentSession? = null
        @Volatile var closed = false
            private set
        private var state = Bundle().apply { putString("state", "opening"); putInt("version", AllDocClient.VERSION) }

        private fun checkOwner() {
            if (Binder.getCallingUid() != owner) throw SecurityException("Document belongs to another caller")
        }
        override fun status(): Bundle { checkOwner(); return synchronized(stateLock) { Bundle(state) } }
        private fun publish(value: Bundle) {
            synchronized(stateLock) { if (closed) return; state = value }
            try { callback.onState(Bundle(value)) } catch (_: Exception) { finish() }
        }

        fun start(input: ParcelFileDescriptor, name: String, mime: String, password: String, preview: Boolean) {
            submit(opening) {
                access.withLock {
                    try {
                        memorySafe {
                            opening.signal.throwIfCanceled()
                            document = DocumentEngine.open(this@DocumentService, input, name, mime.ifBlank { null },
                                opening.signal, password.ifEmpty { null })
                            opening.signal.throwIfCanceled()
                            publish(documentState(checkNotNull(document), preview, opening.signal))
                        }
                    } catch (error: Exception) {
                        runCatching { input.close() }
                        runCatching { release() }
                        publish(Bundle().apply {
                            putInt("version", AllDocClient.VERSION); putString("state", "error")
                            putString("error", error.message?.takeScalarSafe(240) ?: "Document could not be opened")
                        })
                    } finally { if (closed) release() }
                }
            }
        }

        override fun read(requestId: Long, format: String, page: Int, width: Int,
                          theme: String, preserveImages: Boolean): ParcelFileDescriptor = memorySafe {
            checkOwner()
            output(requestId, format, page, width, settings(theme, ImageTreatment.fromLegacy(preserveImages)))
        }

        override fun readSheet(requestId: Long, sheet: Int, x: Double, y: Double, scale: Double,
                               width: Int, height: Int, theme: String, imageTreatment: String): ParcelFileDescriptor = memorySafe {
            checkOwner()
            require(width in 64..2048 && height in 64..2048) { "Invalid worksheet image size" }
            require(scale <= 32.0) { "Invalid worksheet scale" }
            require(imageTreatment.length <= 8) { "Unknown image treatment" }
            val viewport = SheetViewport(sheet, x, y, scale, width, height).checked()
            val images = when (imageTreatment.lowercase(Locale.ROOT)) {
                "auto" -> ImageTreatment.AUTO
                "keep" -> ImageTreatment.KEEP
                "all" -> ImageTreatment.RECOLOR
                else -> throw IllegalArgumentException("Unknown image treatment")
            }
            output(requestId, "png", 0, width, settings(theme, images), viewport)
        }

        private fun settings(theme: String, images: ImageTreatment): ThemeSettings {
            require(theme.length <= 20) { "Unknown theme" }
            return ThemeSettings(ReadingMode.valueOf(theme.uppercase(Locale.ROOT)), imageTreatment = images).resolved(
                resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES)
        }

        private fun output(requestId: Long, format: String, page: Int, width: Int, settings: ThemeSettings,
                           viewport: SheetViewport? = null): ParcelFileDescriptor {
            require(requestId > 0 && format in setOf("text", "json", "png")) { "Unknown output request" }
            require(page >= 0 && width in 64..2048) { "Invalid page or width" }
            val job = Job()
            synchronized(stateLock) {
                check(!closed && state.getString("state") == "ready") { "Document is not ready" }
                check(jobs.size < 2 && requestId !in jobs) { "Too many requests or duplicate request id" }
                jobs[requestId] = job
            }
            val pipe = try { memorySafe { ParcelFileDescriptor.createReliablePipe() } }
                catch (error: Exception) { synchronized(stateLock) { jobs.remove(requestId, job) }; throw error }
            return try {
                submit(job) {
                    try {
                        memorySafe {
                            try {
                                DescriptorOutput(pipe[1], job.signal).use { output ->
                                    when (format) {
                                        "text", "json" -> {
                                            val text = access.withLock {
                                                job.signal.throwIfCanceled()
                                                val current = checkNotNull(document) { "Document is closed" }
                                                if (format == "text") current.text(job.signal) else current.json(job.signal)
                                            }
                                            output.bufferedWriter(Charsets.UTF_8).use { it.write(text) }
                                        }
                                        else -> {
                                            val bitmap = access.withLock {
                                                job.signal.throwIfCanceled()
                                                val current = checkNotNull(document) { "Document is closed" }
                                                if (viewport == null) current.renderPage(page, width, settings, job.signal)
                                                else current.renderSheet(viewport, settings, job.signal)
                                            }
                                            try {
                                                job.signal.throwIfCanceled()
                                                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                                                    throw IOException("Page encoding failed")
                                            } finally { bitmap.recycle() }
                                        }
                                    }
                                    job.signal.throwIfCanceled()
                                }
                            } finally {
                                // EOF permits immediate ID reuse; retire only this completed request first.
                                synchronized(stateLock) { jobs.remove(requestId, job) }
                            }
                            pipe[1].close()
                        }
                    } catch (error: Exception) {
                        try { pipe[1].closeWithError(error.message?.takeScalarSafe(200) ?: "Document request failed") }
                        catch (_: IOException) { }
                    } finally {
                        synchronized(stateLock) { jobs.remove(requestId, job) }
                        if (closed) access.withLock { release() }
                    }
                }
                pipe[0]
            } catch (error: Exception) {
                synchronized(stateLock) { jobs.remove(requestId, job) }
                pipe.forEach { it.close() }
                throw error
            }
        }

        override fun cancel(requestId: Long) {
            checkOwner()
            synchronized(stateLock) { jobs[requestId] }?.signal?.cancel()
        }
        override fun close() { checkOwner(); finish() }
        override fun binderDied() = finish()

        fun finish() {
            synchronized(stateLock) {
                if (closed) return
                closed = true
                state = Bundle().apply { putString("state", "closed"); putInt("version", AllDocClient.VERSION) }
                opening.signal.cancel()
                jobs.values.forEach { it.signal.cancel() }
                // Publish closure and enqueue it atomically with respect to onDestroy's finish pass.
                // Keep admission occupied until document resources are released.
                closers.execute {
                    try { access.withLock { release() } }
                    finally { synchronized(sessions) { sessions.remove(this) } }
                }
            }
            try { callback.asBinder().unlinkToDeath(this, 0) } catch (_: NoSuchElementException) { }
        }
        private fun release() {
            val previous = document
            document = null
            try { previous?.close() } catch (_: Exception) { } catch (_: OutOfMemoryError) { }
        }
    }

}

/** Worker-only metadata; an unpaginated workbook never claims to contain zero print pages. */
internal fun documentState(session: DocumentSession, preview: Boolean, signal: CancellationSignal): Bundle {
    signal.throwIfCanceled()
    val workbook = if (preview && session.info.supportsSheets) checkNotNull(session.workbook(signal)) else null
    val info = if (workbook == null) session.printInfo(signal) else session.info
    return Bundle().apply {
        putInt("version", AllDocClient.VERSION); putString("state", "ready")
        putString("title", info.title.takeScalarSafe(512)); putString("mimeType", info.mimeType)
        putString("format", info.format); putBoolean("reflowable", info.reflowable)
        putBoolean("continuous", info.continuous); putBoolean("supportsSheets", info.supportsSheets)
        putBoolean("textAvailable", info.textAvailable); putString("fidelity", info.fidelity)
        putStringArrayList("warnings", ArrayList((info.warnings + workbook?.warnings.orEmpty()).distinct().take(16).map { it.takeScalarSafe(256) }))
        putString("view", if (workbook == null) "page" else "worksheet")
        putBoolean("paginationKnown", workbook == null)
        if (workbook == null) putInt("pageCount", info.pageCount)
        else {
            require(workbook.sheets.size in 1..1024) { "Invalid worksheet count" }
            require(workbook.sheets.map { it.index }.toSet().size == workbook.sheets.size) { "Duplicate worksheet index" }
            putInt("activeSheet", workbook.activeSheet.takeIf { active -> workbook.sheets.any { it.index == active } } ?: workbook.sheets.first().index)
            putParcelableArrayList("sheets", ArrayList(workbook.sheets.map { sheet ->
                require(sheet.index in 0..1023) { "Invalid worksheet index" }
                Bundle().apply {
                    putInt("index", sheet.index); putString("name", sheet.name.takeScalarSafe(80))
                    putInt("rowCount", sheet.rowCount); putInt("columnCount", sheet.columnCount)
                    putInt("usedRows", sheet.usedRows); putInt("usedColumns", sheet.usedColumns)
                    putDouble("width", sheet.width); putDouble("height", sheet.height)
                }
            }))
        }
        signal.throwIfCanceled()
    }
}
