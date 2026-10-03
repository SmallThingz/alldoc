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
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.*
import org.smallthingz.alldoc.core.DocumentEngine

class DocumentModel(application: Application) : AndroidViewModel(application) {
    var session by mutableStateOf<DocumentEngine.Session?>(null)
        private set

    var name by mutableStateOf("")
        private set

    var busy by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
    var status by mutableStateOf<String?>(null)
    var hasExportedCurrentDraft by mutableStateOf(false)
        private set

    var editorResetVersion by mutableIntStateOf(0)
        private set

    val edits = mutableStateMapOf<String, String>()
    val recentDocuments = mutableStateListOf<RecentDocument>()
    private val recentStore = RecentDocuments(application)
    val recoveredDrafts = mutableStateListOf<RecoveredDraft>()
    private val recoveredStore = RecoveredDraftStore(File(application.filesDir, "recovered-drafts"))
    private var migrationBlocked = false
    var pdfPages by mutableIntStateOf(0)
        private set

    var pdfPage by mutableIntStateOf(0)
        private set

    var pdfBitmap by mutableStateOf<Bitmap?>(null)
        private set

    var pdfLoading by mutableStateOf(false)
        private set

    var pdfFormPage by mutableStateOf(PdfFormPage(emptyList()))
        private set

    private val pdfFields = mutableMapOf<String, PdfFormField>()

    var sourceUri: Uri? = null
        private set

    private var source: File? = null
    private var operation = 0
    private var renderOperation = 0
    private val recovery = RecoveryStore(File(application.filesDir, "recovery"))
    private var recoveryJob: Job? = null
    private var renderJob: Job? = null
    private var remote: IPdfRenderer? = null
    private var pendingPdf: CompletableDeferred<IPdfRenderer>? = null
    private var bound = false
    private val context
        get() = getApplication<Application>()

    private val connection =
        object : ServiceConnection {
            override fun onServiceConnected(n: ComponentName, binder: IBinder) {
                remote = IPdfRenderer.Stub.asInterface(binder)
                pendingPdf?.complete(remote!!)
            }

            override fun onServiceDisconnected(n: ComponentName) {
                remote = null
                pendingPdf?.completeExceptionally(
                    IllegalStateException("The isolated PDF renderer stopped")
                )
                pendingPdf = null
                error = "The isolated PDF renderer stopped. Reopen the document to retry."
                pdfLoading = false
            }

            override fun onBindingDied(n: ComponentName) {
                onServiceDisconnected(n)
                releasePdfBinding()
            }

            override fun onNullBinding(n: ComponentName) {
                onServiceDisconnected(n)
                releasePdfBinding()
            }
        }

    init {
        busy = true
        viewModelScope.launch {
            try {
                recentDocuments.addAll(withContext(Dispatchers.IO) { recentStore.read() })
                recoveredDrafts.addAll(withContext(Dispatchers.IO) { recoveredStore.list() })
                val saved =
                    withContext(Dispatchers.IO) {
                        context.cacheDir
                            .listFiles()
                            ?.filter { it.name.matches(Regex("[a-f0-9-]{36}\\.export")) }
                            ?.forEach { it.delete() }
                        recovery.read()
                    }
                if (saved != null) {
                    val oldDraft =
                        !saved.isPdf &&
                            saved.edits.isNotEmpty() &&
                            saved.mappingVersion != RecoveryStore.CURRENT_MAPPING
                    val sourceDraft =
                        saved.edits.keys == setOf("text") &&
                            saved.name.substringAfterLast('.').lowercase() in
                                setOf(
                                    "txt",
                                    "md",
                                    "csv",
                                    "tsv",
                                    "log",
                                    "json",
                                    "xml",
                                    "yaml",
                                    "yml",
                                )
                    val incompatibleDraft = oldDraft && !sourceDraft
                    // Archive BEFORE the new parser sees the original: stricter validation may now
                    // reject a document accepted by the older release, and that must not lose
                    // edits.
                    if (oldDraft) {
                        migrationBlocked = true
                        val archive = withContext(Dispatchers.IO) { recoveredStore.preserve(saved) }
                        if (recoveredDrafts.none { it.id == archive.id })
                            recoveredDrafts.add(0, archive)
                        migrationBlocked = false
                    }
                    val parsed =
                        withContext(Dispatchers.IO) {
                            if (saved.isPdf) null
                            else
                                DocumentEngine.open(
                                    saved.name,
                                    saved.original.inputStream().use {
                                        DocumentEngine.readBounded(it, DocumentEngine.MAX_FILE)
                                    },
                                )
                        }
                    if (incompatibleDraft) {
                        withContext(Dispatchers.IO) {
                            check(
                                recovery.save(
                                    saved.name,
                                    saved.original,
                                    emptyMap(),
                                    false,
                                    saved.sourceUri,
                                )
                            )
                        }
                        error =
                            "Your earlier Office draft was preserved separately because this update changes document mapping. Use Recovered drafts in the menu to save the original and all draft text. Nothing was applied to the wrong content."
                    }
                    val count =
                        if (saved.isPdf) {
                            val service = pdfService()
                            withContext(Dispatchers.IO) {
                                ParcelFileDescriptor.open(
                                        saved.original,
                                        ParcelFileDescriptor.MODE_READ_ONLY,
                                    )
                                    .use {
                                        service.open(it)
                                    }
                            }
                        } else 0
                    source = saved.original
                    sourceUri = saved.sourceUri?.let(Uri::parse)
                    name = saved.name
                    session = parsed
                    pdfPages = count
                    val validIds =
                        parsed?.blocks?.filter { it.editable }?.map { it.id }?.toSet().orEmpty()
                    if (saved.isPdf) {
                        // Recover only bounded, structurally valid PDF draft addresses. Native
                        // export verifies
                        // each field against the immutable original again before applying anything.
                        runCatching { PdfTools.encodeEdits(saved.edits) }
                            .onSuccess { edits.putAll(saved.edits) }
                            .onFailure {
                                error =
                                    "The PDF opened, but its saved form draft could not be recovered safely. Check the original and re-enter the fields."
                            }
                    } else if (!incompatibleDraft)
                        edits.putAll(saved.edits.filterKeys { it in validIds })
                    if (saved.isPdf) render(0)
                    withContext(Dispatchers.IO) { recovery.prune() }
                    status =
                        if (error == null) "Recovered your last document and saved draft edits."
                        else "Recovered the original document."
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = "Could not restore the last session. Open the original again: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    fun checkpoint() {
        if (busy || migrationBlocked) return
        val revision = recovery.reserveRevision()
        val currentName = name
        val currentSource = source ?: return
        val changes = edits.toMap()
        val isPdf = session == null
        val uri = sourceUri?.toString()
        val previous = recoveryJob
        previous?.cancel()
        recoveryJob = viewModelScope.launch {
            // Cancellation cannot interrupt an AtomicFile write. Join it before writing a newer
            // snapshot.
            previous?.join()
            try {
                withContext(Dispatchers.IO) {
                    recovery.save(currentName, currentSource, changes, isPdf, uri, revision)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error =
                    "Draft recovery could not be saved. Export your edits before closing: ${e.message}"
            }
        }
    }

    fun open(uri: Uri) {
        if (busy) return
        if (migrationBlocked) {
            error =
                "The older draft could not be preserved safely. Free some device storage and restart alldoc before opening another document. The original and old draft remain in recovery."
            return
        }
        busy = true
        error = null
        status = null
        val generation = ++operation
        viewModelScope.launch {
            var imported: File? = null
            var durable = false
            var commitAttempted = false
            var replacedPdf = false
            try {
                recoveryJob?.join()
                renderJob?.cancelAndJoin()
                renderOperation++
                pdfLoading = false
                val result =
                    withContext(Dispatchers.IO) {
                        val display =
                            context.contentResolver
                                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                                ?.use { cursor ->
                                    if (cursor.moveToFirst()) cursor.getString(0) else null
                                }
                                ?.take(512) ?: "document"
                        val bytes =
                            context.contentResolver.openInputStream(uri)?.use {
                                DocumentEngine.readBounded(it, DocumentEngine.MAX_FILE)
                            } ?: error("Could not read this file")
                        imported = File(recovery.directory(), UUID.randomUUID().toString())
                        FileOutputStream(imported!!).use {
                            it.write(bytes)
                            it.fd.sync()
                        }
                        Pair(
                            display,
                            if (
                                display.endsWith(".pdf", true) ||
                                    context.contentResolver.getType(uri) == "application/pdf"
                            )
                                null
                            else DocumentEngine.open(display, bytes),
                        )
                    }
                var count = 0
                if (result.second == null) {
                    val service = pdfService()
                    count =
                        withContext(Dispatchers.IO) {
                            ParcelFileDescriptor.open(
                                    imported!!,
                                    ParcelFileDescriptor.MODE_READ_ONLY,
                                )
                                .use { service.open(it) }
                                .also { replacedPdf = true }
                        }
                    require(count > 0) { "PDF has no pages" }
                }
                ensureActive()
                if (generation != operation) return@launch
                // Commit recovery before replacing the visible session or deleting any old
                // original.
                withContext(Dispatchers.IO) {
                    commitAttempted = true
                    check(
                        recovery.save(
                            result.first,
                            imported!!,
                            emptyMap(),
                            result.second == null,
                            uri.toString(),
                        )
                    ) {
                        "Document recovery was superseded"
                    }
                    durable = true
                }
                source = imported
                imported = null
                sourceUri = uri
                name = result.first
                session = result.second
                edits.clear()
                hasExportedCurrentDraft = false
                pdfPages = count
                pdfFields.clear()
                pdfFormPage = PdfFormPage(emptyList())
                pdfPage = 0
                pdfBitmap = null
                if (count > 0) render(0)
                else {
                    val oldRemote = remote
                    withContext(Dispatchers.IO) { runCatching { oldRemote?.close() } }
                }
                withContext(Dispatchers.IO) { recovery.prune() }
                // Recent-history failure must not turn a successful document import into data loss.
                runCatching {
                    withContext(Dispatchers.IO) { recentStore.record(uri, result.first) }
                }
                    .getOrNull()
                    ?.let {
                        recentDocuments.clear()
                        recentDocuments.addAll(it)
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Recovery failure after a valid PDF open must also restore the prior PDF renderer.
                if (replacedPdf && !durable && generation == operation) {
                    val oldSource = source
                    val oldRemote = remote
                    val wasPdf = session == null && oldSource != null
                    withContext(Dispatchers.IO) {
                        runCatching {
                            if (wasPdf)
                                ParcelFileDescriptor.open(
                                        oldSource!!,
                                        ParcelFileDescriptor.MODE_READ_ONLY,
                                    )
                                    .use {
                                        oldRemote?.open(it)
                                    }
                            else oldRemote?.close()
                        }
                    }
                }
                error = e.message ?: "Unable to open this document"
                if (session == null && source != null && pdfPages > 0) render(pdfPage)
            } finally {
                if (!durable && !commitAttempted) imported?.delete()
                busy = false
            }
        }
    }

    private suspend fun pdfService(): IPdfRenderer =
        withContext(Dispatchers.Main.immediate) {
            remote?.let {
                return@withContext it
            }
            val pending = pendingPdf ?: CompletableDeferred<IPdfRenderer>().also { pendingPdf = it }
            if (!bound) {
                bound =
                    context.bindService(
                        Intent(context, PdfService::class.java),
                        connection,
                        Context.BIND_AUTO_CREATE,
                    )
                require(bound) { "Cannot start PDF renderer" }
            }
            try {
                withTimeout(10000) { pending.await() }
            } catch (e: Exception) {
                if (pendingPdf === pending) releasePdfBinding()
                throw e
            }
        }

    private fun releasePdfBinding() {
        remote = null
        pendingPdf = null
        if (bound) {
            bound = false
            runCatching { context.unbindService(connection) }
        }
    }

    fun render(page: Int, width: Int = 1080) {
        if (page !in 0 until pdfPages) return
        val request = ++renderOperation
        val generation = operation
        renderJob?.cancel()
        pdfLoading = true
        pdfFormPage = PdfFormPage(emptyList(), "Loading this page’s form fields…")
        pdfPage = page
        renderJob = viewModelScope.launch {
            try {
                val service = pdfService()
                val bitmap =
                    withContext(Dispatchers.IO) {
                        ParcelFileDescriptor.AutoCloseInputStream(service.render(page, width)).use {
                            BitmapFactory.decodeStream(it) ?: error("Unable to render page")
                        }
                    }
                ensureActive()
                if (request == renderOperation && generation == operation) pdfBitmap = bitmap
                val fields =
                    withContext(Dispatchers.IO) { PdfTools.decodeFields(service.formFields(page)) }
                ensureActive()
                if (request == renderOperation && generation == operation) {
                    pdfFormPage = fields
                    fields.fields.forEach { pdfFields[it.id] = it }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (request == renderOperation) {
                    pdfFormPage =
                        PdfFormPage(
                            emptyList(),
                            "Form fields are unavailable on this page. Reopen the PDF to retry.",
                        )
                    error = e.message ?: "Unable to render page"
                }
            } finally {
                if (request == renderOperation) pdfLoading = false
            }
        }
    }

    fun edit(id: String, value: String) {
        if (busy || migrationBlocked) return
        if (session == null && pdfPages > 0) {
            val field = pdfFields[id] ?: return
            try {
                PdfTools.validateValue(field, value)
                val candidate =
                    edits.toMutableMap().apply {
                        if (value == field.value) remove(id) else put(id, value)
                    }
                PdfTools.encodeEdits(candidate)
                if (value == field.value) edits.remove(id) else edits[id] = value
                hasExportedCurrentDraft = false
                status = null
                checkpoint()
            } catch (e: Exception) {
                error = e.message ?: "This field could not be changed"
            }
            return
        }
        val block = session?.blocks?.firstOrNull { it.id == id && it.editable } ?: return
        val length =
            edits.entries.sumOf { if (it.key == id) 0L else it.value.length.toLong() } +
                if (value == block.original) 0 else value.length
        if (length > DocumentEngine.MAX_EDIT_CHARACTERS || value.length > DocumentEngine.MAX_TEXT) {
            error = "This edit exceeds the safe draft size limit. Shorten it before continuing."
            return
        }
        if (value == block.original) edits.remove(id) else edits[id] = value
        hasExportedCurrentDraft = false
        status = null
        checkpoint()
    }

    fun editBatch(changes: Map<String, String>) {
        if (busy || migrationBlocked) return
        val current = session ?: return
        val targets = current.blocks.associateBy { it.id }
        val candidate = edits.toMutableMap()
        try {
            for ((id, value) in changes) {
                val block = targets[id] ?: error("Unknown text edit target")
                require(block.editable || value == block.original) {
                    "This paragraph contains protected text"
                }
                require(value.length <= DocumentEngine.MAX_TEXT) { "This text edit is too large" }
                if (value == block.original) candidate.remove(id) else candidate[id] = value
            }
            require(
                candidate.values.sumOf { it.length.toLong() } <= DocumentEngine.MAX_EDIT_CHARACTERS
            ) {
                "The combined draft is too large"
            }
            if (current.extension == "rtf")
                require(
                    current.blocks.sumOf { (candidate[it.id] ?: it.original).length.toLong() } <=
                        org.smallthingz.alldoc.core.RtfDocument.MAX_TEXT
                ) {
                    "RTF text exceeds the safe document limit"
                }
            androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
                edits.clear()
                edits.putAll(candidate)
            }
            hasExportedCurrentDraft = false
            status = null
            checkpoint()
        } catch (failure: Exception) {
            error = failure.message ?: "The text changes could not be applied safely"
        }
    }

    fun discardEdits() {
        if (!busy) {
            editorResetVersion++
            edits.clear()
            hasExportedCurrentDraft = false
            checkpoint()
        }
    }

    fun export(uri: Uri) {
        if (busy) return
        if (uri == sourceUri) {
            error = "Choose a different destination. alldoc never overwrites the imported original."
            return
        }
        val snapshot = edits.toMap()
        val current = session
        val file = source
        busy = true
        error = null
        status = null
        viewModelScope.launch {
            var staging: File? = null
            try {
                val service = if (current == null && snapshot.isNotEmpty()) pdfService() else null
                withContext(Dispatchers.IO) {
                    // Finish and fsync a private staging file before opening the destination.
                    staging = File(context.cacheDir, UUID.randomUUID().toString() + ".export")
                    if (service != null) {
                        val original = file ?: error("No original PDF is available")
                        ParcelFileDescriptor.open(original, ParcelFileDescriptor.MODE_READ_ONLY)
                            .use { input ->
                                ParcelFileDescriptor.open(
                                        staging!!,
                                        ParcelFileDescriptor.MODE_CREATE or
                                            ParcelFileDescriptor.MODE_TRUNCATE or
                                            ParcelFileDescriptor.MODE_READ_WRITE,
                                    )
                                    .use { output ->
                                        service.writeFormCopy(
                                            input,
                                            output,
                                            PdfTools.encodeEdits(snapshot),
                                        )
                                    }
                            }
                        require(staging!!.length() in 1..DocumentEngine.MAX_FILE.toLong()) {
                            "Saved PDF exceeds the safe size limit"
                        }
                    } else {
                        val bytes =
                            current?.export(snapshot)
                                ?: file?.inputStream()?.use {
                                    DocumentEngine.readBounded(it, DocumentEngine.MAX_FILE)
                                }
                                ?: error("No document open")
                        FileOutputStream(staging!!).use {
                            it.write(bytes)
                            it.fd.sync()
                        }
                    }
                    ensureActive()
                    context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                        staging!!.inputStream().use { it.copyTo(output) }
                        output.flush()
                    } ?: error("Destination cannot be written")
                }
                hasExportedCurrentDraft = true
                status = "Copy exported. Original unchanged."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error =
                    if (e is IllegalArgumentException || e is SecurityException) {
                        "This copy could not be saved: ${e.message?.take(400) ?: "The document does not allow this change"}. Your original and draft are safe."
                    } else
                        "Couldn’t save the copy. Your original and draft are safe. Choose another destination and try again. The destination may contain an incomplete copy; delete or replace it."
            } finally {
                staging?.delete()
                busy = false
            }
        }
    }

    fun exportRecoveredDraft(draft: RecoveredDraft, uri: Uri) {
        if (busy) return
        if (uri == sourceUri) {
            error = "Choose a separate destination for the recovered draft"
            return
        }
        busy = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    require(recoveredStore.isOwned(draft) && draft.file.isFile) {
                        "Recovered draft is unavailable"
                    }
                    context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                        draft.file.inputStream().use { it.copyTo(output) }
                        output.flush()
                    } ?: error("Destination cannot be written")
                }
                status = "Recovered draft saved with its original and all edited text."
            } catch (e: Exception) {
                error =
                    "Couldn’t save the recovered draft. Choose another destination; the saved archive is still safe on this device."
            } finally {
                busy = false
            }
        }
    }

    override fun onCleared() {
        operation++
        renderOperation++
        renderJob?.cancel()
        // Unbinding destroys the service and closes its renderer, without blocking the UI on native
        // work.
        releasePdfBinding()
        super.onCleared()
    }
}
