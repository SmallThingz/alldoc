package app.smallthingz.alldoc.oacp

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.OperationCanceledException
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.smallthingz.alldoc.core.takeScalarSafe
import app.smallthingz.alldoc.engine.DocumentEngine
import app.smallthingz.alldoc.pdf.PdfPasswordException
import app.smallthingz.alldoc.pdf.PdfPasswordUnsupportedException
import app.smallthingz.alldoc.state.AppAppearanceStore
import app.smallthingz.alldoc.state.AppTheme
import app.smallthingz.alldoc.ui.ReaderModel
import app.smallthingz.alldoc.theme.ReadingMode
import app.smallthingz.alldoc.theme.ThemeSettings
import org.oacp.android.Oacp
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal sealed interface CapabilityOutcome {
    data class Success(val file: File, val uri: Uri, val mime: String, val format: String, val fidelity: String,
        val warnings: List<String>, val page: Int, val bytes: Long, val continuous: Boolean) : CapabilityOutcome
    data class Failure(val code: String, val message: String) : CapabilityOutcome
}

/** Retains one immutable operation across rotation; worker ownership ends only after session close. */
internal class DocumentCapabilityModel(application: Application) : AndroidViewModel(application) {
    var appTheme by mutableStateOf(AppTheme.SYSTEM); private set
    init {
        viewModelScope.launch {
            appTheme = ReaderModel.orderedStateRead {
                runCatching { AppAppearanceStore(application).read() }.getOrDefault(AppTheme.SYSTEM)
            }
        }
    }
    var running by mutableStateOf(false); private set
    var result by mutableStateOf<CapabilityOutcome?>(null); private set
    private var started = false
    private var delivered = false
    private val cancelled = AtomicBoolean()
    private val finished = AtomicBoolean()
    private val pendingFile = AtomicReference<File?>()
    private val signal = CancellationSignal()
    private val main = Handler(Looper.getMainLooper())

    fun start(request: CapabilityRequest): Boolean {
        if (started) return false
        started = true
        running = true
        val deadline = timer.schedule({
            if (finished.compareAndSet(false, true)) {
                cancel()
                main.post { complete(CapabilityOutcome.Failure(Oacp.ERROR_CANCELLED, "The document took too long to read.")) }
            }
        }, 60, TimeUnit.SECONDS)
        try {
            workers.execute {
                val outcome = try { export(request) }
                catch (error: Exception) {
                    val code = when (error) {
                        is OperationCanceledException -> Oacp.ERROR_CANCELLED
                        is SecurityException -> Oacp.ERROR_NOT_AUTHORIZED
                        is PdfPasswordException -> Oacp.ERROR_NOT_AUTHENTICATED
                        is PdfPasswordUnsupportedException -> Oacp.ERROR_UNSUPPORTED_STATE
                        is IllegalArgumentException -> Oacp.ERROR_INVALID_PARAMETERS
                        else -> Oacp.ERROR_INTERNAL
                    }
                    CapabilityOutcome.Failure(code, error.message?.takeScalarSafe(200) ?: "Could not read the document.")
                }
                catch (_: OutOfMemoryError) { CapabilityOutcome.Failure(Oacp.ERROR_UNSUPPORTED_STATE, "Not enough memory to read this document.") }
                finally { deadline.cancel(false) }
                val won = finished.compareAndSet(false, true)
                if (!won || cancelled.get() || outcome is CapabilityOutcome.Failure) discardPending()
                if (won) main.post { if (!cancelled.get()) complete(outcome) }
            }
        } catch (_: RejectedExecutionException) {
            deadline.cancel(false)
            finished.set(true)
            complete(CapabilityOutcome.Failure(Oacp.ERROR_UNSUPPORTED_STATE, "Two documents are already being read."))
        }
        return true
    }

    private fun complete(outcome: CapabilityOutcome) {
        if (result != null || delivered) { timer.execute(::discardPending); return }
        running = false
        result = outcome
    }
    fun takeResult(): Boolean = if (delivered || (result is CapabilityOutcome.Success && cancelled.get())) false else {
        delivered = true
        true
    }
    fun releaseResult() { pendingFile.set(null) } // Transfer to the result cache only after sharing.
    fun rejectResult() { timer.execute(::discardPending) }
    private fun discardPending() { pendingFile.getAndSet(null)?.let(CapabilityFiles::discard) }
    fun cancel() {
        if (started && cancelled.compareAndSet(false, true)) timer.execute { discardPending(); signal.cancel() }
    }
    override fun onCleared() { cancel() }

    private fun export(request: CapabilityRequest): CapabilityOutcome.Success {
        val context = getApplication<Application>()
        val source = requireNotNull(request.uri)
        signal.throwIfCanceled()
        val resolver = context.contentResolver
        var name = request.name ?: source.lastPathSegment?.takeScalarSafe(256) ?: "Document"
        if (request.name == null) resolver.query(source, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null, signal)?.use {
            if (it.moveToFirst() && !it.isNull(0)) name = it.getString(0).takeScalarSafe(256)
        }
        val mime = request.mime ?: resolver.getType(source)
        val descriptor = resolver.openFileDescriptor(source, "r", signal) ?: throw IOException("The provider returned no document.")
        DocumentEngine.open(context, descriptor, name, mime, signal, request.password.takeIf { it.isNotEmpty() }).use { session ->
            var file = CapabilityFiles.create(context)
            try {
                val outputMime = when (request.format) { "json" -> "application/json"; "png" -> "image/png"; else -> "text/plain" }
                FileOutputStream(file).use { raw ->
                    val output = LimitedOutput(raw, signal)
                    if (request.format == "png") {
                        val bitmap = session.renderPage(request.page, request.width, ThemeSettings(ReadingMode.ORIGINAL), signal)
                        try { if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) throw IOException("Could not encode the page.") }
                        finally { bitmap.recycle() }
                    } else {
                        val text = if (request.format == "json") session.json(signal) else session.text(signal)
                        output.bufferedWriter(Charsets.UTF_8).use { it.write(text) }
                    }
                    signal.throwIfCanceled()
                }
                file = CapabilityFiles.publish(context, file, if (request.format == "text") "txt" else request.format)
                signal.throwIfCanceled()
                pendingFile.set(file)
                return CapabilityOutcome.Success(file, CapabilityFiles.uri(context, file), outputMime, request.format, session.info.fidelity.take(80),
                    session.info.warnings.take(16).map { it.takeScalarSafe(200) }, request.page, file.length(), session.info.continuous)
            } catch (error: Throwable) { pendingFile.compareAndSet(file, null); CapabilityFiles.discard(file); throw error }
        }
    }

    private class LimitedOutput(private val output: OutputStream, private val signal: CancellationSignal) : OutputStream() {
        private var count = 0L
        private fun admit(length: Int) {
            signal.throwIfCanceled()
            if (count + length > CapabilityFiles.OUTPUT_LIMIT) throw IOException("The result exceeds 32 MiB.")
            count += length
        }
        override fun write(value: Int) { admit(1); output.write(value) }
        override fun write(bytes: ByteArray, offset: Int, length: Int) { admit(length); output.write(bytes, offset, length) }
        override fun flush() = output.flush()
        override fun close() = output.close()
    }
    companion object {
        private val workers = ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, SynchronousQueue(),
            { task -> Thread(task, "alldoc-oacp").apply { isDaemon = true } }).apply { allowCoreThreadTimeOut(true) }
        private val timer = ScheduledThreadPoolExecutor(1, { task -> Thread(task, "alldoc-oacp-cancel").apply { isDaemon = true } })
            .apply { removeOnCancelPolicy = true; setKeepAliveTime(30, TimeUnit.SECONDS); allowCoreThreadTimeOut(true) }
    }
}
