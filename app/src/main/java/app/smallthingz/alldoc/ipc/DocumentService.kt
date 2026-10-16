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
                          callback: IAllDocCallback?): IAllDocSession = memorySafe {
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
                        session.start(input, name, mime, password)
                    }
                    adopted = true
                    session
                } catch (error: Exception) {
                    session.finish()
                    throw error
                }
            } finally { if (!adopted) input.close() }
        }
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

        fun start(input: ParcelFileDescriptor, name: String, mime: String, password: String) {
            submit(opening) {
                access.withLock {
                    try {
                        memorySafe {
                            opening.signal.throwIfCanceled()
                            document = DocumentEngine.open(this@DocumentService, input, name, mime.ifBlank { null },
                                opening.signal, password.ifEmpty { null })
                            opening.signal.throwIfCanceled()
                            val session = checkNotNull(document)
                            val info = session.info
                            val pageCount = session.pageCount(opening.signal)
                            publish(Bundle().apply {
                                putInt("version", AllDocClient.VERSION); putString("state", "ready")
                                putString("title", info.title.takeScalarSafe(512)); putString("mimeType", info.mimeType)
                                putString("format", info.format); putInt("pageCount", pageCount); putBoolean("reflowable", info.reflowable)
                                putBoolean("continuous", info.continuous)
                                putBoolean("textAvailable", info.textAvailable); putString("fidelity", info.fidelity)
                                putStringArrayList("warnings", ArrayList(info.warnings.take(16).map { it.takeScalarSafe(256) }))
                            })
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
            require(requestId > 0 && format in setOf("text", "json", "png")) { "Unknown output request" }
            require(page >= 0 && width in 64..2048) { "Invalid page or width" }
            require(theme.length <= 20) { "Unknown theme" }
            val settings = ThemeSettings(ReadingMode.valueOf(theme.uppercase(Locale.ROOT)), preserveImages).resolved(
                resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES)
            val job = Job()
            synchronized(stateLock) {
                check(!closed && state.getString("state") == "ready") { "Document is not ready" }
                check(jobs.size < 2 && requestId !in jobs) { "Too many requests or duplicate request id" }
                jobs[requestId] = job
            }
            val pipe = try { memorySafe { ParcelFileDescriptor.createReliablePipe() } }
                catch (error: Exception) { synchronized(stateLock) { jobs.remove(requestId, job) }; throw error }
            try {
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
                                                checkNotNull(document) { "Document is closed" }
                                                    .renderPage(page, width, settings, job.signal)
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
