package org.smallthingz.alldoc

import android.app.Application
import android.content.*
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.*
import android.provider.OpenableColumns
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import org.smallthingz.alldoc.core.DocumentEngine
import java.io.File
import java.util.UUID

class DocumentModel(application: Application) : AndroidViewModel(application) {
    var session by mutableStateOf<DocumentEngine.Session?>(null); private set
    var name by mutableStateOf(""); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null)
    var status by mutableStateOf<String?>(null)
    val edits = mutableStateMapOf<String, String>()
    var pdfPages by mutableIntStateOf(0); private set
    var pdfPage by mutableIntStateOf(0); private set
    var pdfBitmap by mutableStateOf<Bitmap?>(null); private set
    var pdfLoading by mutableStateOf(false); private set
    var sourceUri: Uri? = null; private set
    private var source: File? = null
    private var operation = 0
    private val recovery = RecoveryStore(File(application.filesDir, "recovery"))
    private var recoveryJob: Job? = null
    private var renderJob: Job? = null
    private var remote: IPdfRenderer? = null
    private var pendingPdf: CompletableDeferred<IPdfRenderer>? = null
    private var bound = false
    private val context get() = getApplication<Application>()
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(n: ComponentName, binder: IBinder) { remote = IPdfRenderer.Stub.asInterface(binder); pendingPdf?.complete(remote!!) }
        override fun onServiceDisconnected(n: ComponentName) { remote = null; error = "The isolated PDF renderer stopped. Reopen the document to retry."; pdfLoading = false }
    }
    init {
        busy = true
        viewModelScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) { recovery.read() }
                if (saved != null) {
                    val parsed = withContext(Dispatchers.IO) { if (saved.name.endsWith(".pdf", true)) null else DocumentEngine.open(saved.name, saved.original.readBytes()) }
                    source = saved.original; name = saved.name; session = parsed
                    val validIds = parsed?.blocks?.filter { it.editable }?.map { it.id }?.toSet().orEmpty()
                    edits.putAll(saved.edits.filterKeys { it in validIds })
                    if (parsed == null) {
                        val service = pdfService()
                        pdfPages = withContext(Dispatchers.IO) { ParcelFileDescriptor.open(saved.original, ParcelFileDescriptor.MODE_READ_ONLY).use { service.open(it) } }
                        render(0)
                    }
                    status = "Recovered your last document and saved draft edits."
                }
            } catch (e: Exception) { error = "Could not restore the last session. Open the original again: ${e.message}" }
            finally { busy = false }
        }
    }
    fun checkpoint() {
        recoveryJob?.cancel()
        val currentName = name; val currentSource = source ?: return; val changes = edits.toMap()
        recoveryJob = viewModelScope.launch {
            try { withContext(Dispatchers.IO) { recovery.save(currentName, currentSource, changes) } }
            catch (e: Exception) { error = "Draft recovery could not be saved. Export your edits before closing: ${e.message}" }
        }
    }
    fun open(uri: Uri) {
        if (busy) return
        busy = true; error = null; status = null
        val generation = ++operation
        viewModelScope.launch {
            var imported: File? = null
            try {
                val result = withContext(Dispatchers.IO) {
                    val display = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    } ?: "document"
                    val bytes = context.contentResolver.openInputStream(uri)?.use { DocumentEngine.readBounded(it, DocumentEngine.MAX_FILE) } ?: error("Could not read this file")
                    imported = File(recovery.directory(), UUID.randomUUID().toString()).apply { outputStream().use { it.write(bytes) } }
                    Triple(display, bytes, if (display.endsWith(".pdf", true) || context.contentResolver.getType(uri) == "application/pdf") null else DocumentEngine.open(display, bytes))
                }
                var count = 0
                if (result.third == null) {
                    val service = pdfService()
                    count = withContext(Dispatchers.IO) { ParcelFileDescriptor.open(imported!!, ParcelFileDescriptor.MODE_READ_ONLY).use { service.open(it) } }
                    require(count > 0) { "PDF has no pages" }
                } else withContext(Dispatchers.IO) { runCatching { remote?.close() } }
                if (generation != operation) return@launch
                renderJob?.cancel(); pdfBitmap = null
                val previousSource = source; source = imported; imported = null
                sourceUri = uri; name = result.first; session = result.third; edits.clear(); pdfPages = count; pdfPage = 0
                checkpoint()
                recoveryJob?.join()
                previousSource?.takeIf { it != source }?.delete()
                if (count > 0) render(0)
            } catch (e: Exception) { error = e.message ?: "Unable to open this document" }
            finally { imported?.delete(); busy = false }
        }
    }
    private suspend fun pdfService(): IPdfRenderer {
        remote?.let { return it }
        pendingPdf = CompletableDeferred()
        if (!bound) { bound = context.bindService(Intent(context, PdfService::class.java), connection, Context.BIND_AUTO_CREATE); require(bound) { "Cannot start PDF renderer" } }
        return withTimeout(10000) { pendingPdf!!.await() }
    }
    fun render(page: Int, width: Int = 1080) {
        if (page !in 0 until pdfPages) return
        renderJob?.cancel(); pdfLoading = true; pdfPage = page
        renderJob = viewModelScope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) { val service = pdfService(); service.render(page, width).use { fd -> ParcelFileDescriptor.AutoCloseInputStream(fd).use { BitmapFactory.decodeStream(it) ?: error("Unable to render page") } } }
                ensureActive(); pdfBitmap = bitmap
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Unable to render page" }
            finally { pdfLoading = false }
        }
    }
    fun edit(id: String, value: String) {
        val block = session?.blocks?.firstOrNull { it.id == id } ?: return
        if (value == block.original) edits.remove(id) else edits[id] = value
        checkpoint()
    }
    fun discardEdits() { edits.clear(); checkpoint() }
    fun export(uri: Uri) {
        if (busy) return
        if (uri == sourceUri) { error = "Choose a different destination. alldoc never overwrites the imported original."; return }
        val snapshot = edits.toMap(); val current = session; val file = source
        busy = true; error = null
        viewModelScope.launch {
            var staging: File? = null
            try {
                withContext(Dispatchers.IO) {
                    // Finish and fsync a private staging file before opening the destination.
                    val bytes = current?.export(snapshot) ?: file?.readBytes() ?: error("No document open")
                    staging = File(context.cacheDir, UUID.randomUUID().toString()+".export")
                    java.io.FileOutputStream(staging!!).use { it.write(bytes); it.fd.sync() }
                    context.contentResolver.openOutputStream(uri, "w")?.use { output -> staging!!.inputStream().use { it.copyTo(output) }; output.flush() } ?: error("Destination cannot be written")
                }
                status = "Copy exported. Original unchanged."
            } catch (e: Exception) { error = "Export failed: ${e.message}. Your original and edits are safe. The destination may contain an incomplete copy; delete or replace it." }
            finally { staging?.delete(); busy = false }
        }
    }
    override fun onCleared() {
        operation++; renderJob?.cancel(); runCatching { remote?.close() }
        if (bound) context.unbindService(connection)
        super.onCleared()
    }
}
