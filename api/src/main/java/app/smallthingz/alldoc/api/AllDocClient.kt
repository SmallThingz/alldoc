package app.smallthingz.alldoc.api

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.InputStream
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Bind once and reuse the connection. Open the source with your own URI permission. */
class AllDocClient private constructor(
    private val context: Context,
    private val executor: Executor,
    private val connected: (AllDocClient) -> Unit,
    private val disconnected: () -> Unit
) : Closeable {
    @Volatile private var service: IAllDocService? = null
    private var bound = false
    @Volatile private var closed = false
    @Volatile private var generation = 0L
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val connectedGeneration = synchronized(this@AllDocClient) {
                if (closed || !bound) return
                service = IAllDocService.Stub.asInterface(binder)
                ++generation
            }
            executor.execute { if (!closed && generation == connectedGeneration) connected(this@AllDocClient) }
        }
        override fun onServiceDisconnected(name: ComponentName) = notifyDisconnected(false)
        override fun onBindingDied(name: ComponentName) = notifyDisconnected(true)
        override fun onNullBinding(name: ComponentName) = notifyDisconnected(true)
    }

    private fun notifyDisconnected(terminal: Boolean) {
        val disconnectedGeneration = synchronized(this) {
            if (closed || !bound) return
            service = null
            if (terminal) releaseBinding()
            ++generation
        }
        executor.execute { if (!closed && generation == disconnectedGeneration) disconnected() }
    }

    @Synchronized private fun releaseBinding() {
        service = null
        if (bound) { bound = false; context.unbindService(connection) }
    }

    /** Consumes input even on failure. Callbacks carry their owner and may run before open returns. */
    fun open(input: ParcelFileDescriptor, name: String, mime: String = "",
             password: String = "", onState: (Document, Bundle) -> Unit): Document =
        open(input, name, mime, password, false, onState)

    /** Version 2: workbook previews keep their worksheet grid without resolving print pages. */
    fun openForPreview(input: ParcelFileDescriptor, name: String, mime: String = "",
                       password: String = "", onState: (Document, Bundle) -> Unit): Document =
        open(input, name, mime, password, true, onState)

    private fun open(input: ParcelFileDescriptor, name: String, mime: String, password: String,
                     preview: Boolean, onState: (Document, Bundle) -> Unit): Document {
        val active = AtomicBoolean(true)
        val publication = Any()
        var document: Document? = null
        var pending: Bundle? = null
        fun dispatch(owner: Document, status: Bundle) {
            executor.execute { if (active.get()) onState(owner, status) }
        }
        val callback = object : IAllDocCallback.Stub() {
            override fun onState(status: Bundle) {
                val owner = synchronized(publication) {
                    document ?: run { pending = Bundle(status); return }
                }
                dispatch(owner, status)
            }
        }
        var remote: IAllDocSession? = null
        var version = 1
        try {
            remote = input.use {
                val bound = checkNotNull(service) { "AllDoc is not connected" }
                version = bound.version()
                if (preview) {
                    check(version >= 2) { "This AllDoc version does not support worksheet previews" }
                    bound.openForPreview(it, name, mime, password, callback)
                } else bound.open(it, name, mime, password, callback)
            }
            val opened = Document(remote, callback, active, version)
            val status = synchronized(publication) {
                document = opened
                pending.also { pending = null }
            }
            status?.let { dispatch(opened, it) }
            return opened
        } catch (error: Exception) {
            active.set(false)
            runCatching { remote?.close() }
            throw error
        }
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        releaseBinding()
    }

    class Document internal constructor(
        private val remote: IAllDocSession,
        @Suppress("unused") private val callback: IAllDocCallback,
        private val active: AtomicBoolean,
        private val version: Int
    ) : Closeable {
        private val ids = AtomicLong()
        fun status(): Bundle = remote.status()
        /** Wait for ready first. Read the returned stream on a worker; close it promptly. */
        fun read(format: String, page: Int = 0, width: Int = 1200,
                 theme: String = "original", preserveImages: Boolean = true): Result {
            check(active.get()) { "Document is closed" }
            val id = ids.incrementAndGet()
            return Result(remote, id, remote.read(id, format, page, width, theme, preserveImages))
        }
        /** Sheet IDs come from ready metadata; x/y are logical pixels, not row/column indices. */
        fun readSheet(sheet: Int, x: Double = 0.0, y: Double = 0.0, scale: Double = 1.0,
                      width: Int = 1200, height: Int = 800, theme: String = "original",
                      imageTreatment: String = "auto"): Result {
            check(active.get()) { "Document is closed" }
            check(version >= 2) { "This AllDoc version does not support worksheet previews" }
            val id = ids.incrementAndGet()
            return Result(remote, id, remote.readSheet(id, sheet, x, y, scale, width, height, theme, imageTreatment))
        }
        override fun close() { if (active.getAndSet(false)) remote.close() }
    }

    class Result internal constructor(
        private val session: IAllDocSession,
        private val id: Long,
        val descriptor: ParcelFileDescriptor
    ) : Closeable {
        /** One reader only. Reliable-pipe errors surface as IOException at EOF. */
        fun inputStream(): InputStream = ParcelFileDescriptor.AutoCloseInputStream(descriptor)
        override fun close() {
            try { session.cancel(id) } finally { descriptor.close() }
        }
    }

    companion object {
        const val PACKAGE = "app.smallthingz.alldoc"
        const val SERVICE = "app.smallthingz.alldoc.ipc.DocumentService"
        const val VERSION = 2
        fun connect(context: Context, executor: Executor = context.mainExecutor,
                    onDisconnected: () -> Unit = {}, onConnected: (AllDocClient) -> Unit): AllDocClient {
            val client = AllDocClient(context.applicationContext, executor, onConnected, onDisconnected)
            synchronized(client) {
                client.bound = try {
                    client.context.bindService(
                        Intent().setComponent(ComponentName(PACKAGE, SERVICE)), client.connection, Context.BIND_AUTO_CREATE
                    )
                } catch (failure: SecurityException) {
                    try { client.context.unbindService(client.connection) }
                    catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
                    throw failure
                }
                // Android retains the connection registration even when no service is found.
                if (!client.bound) client.context.unbindService(client.connection)
            }
            check(client.bound) { "AllDoc is not installed or its service is unavailable" }
            return client
        }
    }
}
