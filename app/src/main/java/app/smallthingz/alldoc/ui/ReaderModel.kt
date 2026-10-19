package app.smallthingz.alldoc.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.RectF
import android.content.res.Configuration
import android.net.Uri
import android.os.CancellationSignal
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.smallthingz.alldoc.core.MarkdownDocument
import app.smallthingz.alldoc.core.DocumentProbe
import app.smallthingz.alldoc.core.takeScalarSafe
import app.smallthingz.alldoc.engine.DocumentNotes
import app.smallthingz.alldoc.engine.DocumentOutlineEntry
import app.smallthingz.alldoc.engine.themed
import app.smallthingz.alldoc.engine.DocumentInfo
import app.smallthingz.alldoc.engine.rasterRegionsFor
import app.smallthingz.alldoc.engine.SheetFrame
import app.smallthingz.alldoc.engine.SheetGeometry
import app.smallthingz.alldoc.engine.SheetViewport
import app.smallthingz.alldoc.engine.WorkbookInfo
import app.smallthingz.alldoc.state.SheetPosition
import kotlinx.coroutines.CompletableDeferred
import app.smallthingz.alldoc.engine.DocumentSession
import app.smallthingz.alldoc.engine.PageRegion
import app.smallthingz.alldoc.ipc.DescriptorOutput
import app.smallthingz.alldoc.pdf.PdfPasswordException
import app.smallthingz.alldoc.pdf.PdfPasswordUnsupportedException
import app.smallthingz.alldoc.state.AppTheme
import app.smallthingz.alldoc.state.AppAppearanceStore
import app.smallthingz.alldoc.state.LibraryCategory
import app.smallthingz.alldoc.state.LibraryDocument
import app.smallthingz.alldoc.sources.ExampleDocument
import app.smallthingz.alldoc.sources.ExampleDocuments
import app.smallthingz.alldoc.sources.DocumentSources
import app.smallthingz.alldoc.sources.RecentDocuments
import app.smallthingz.alldoc.sources.PreparedDocument
import app.smallthingz.alldoc.sources.SavedFormat
import app.smallthingz.alldoc.state.CopySettings
import app.smallthingz.alldoc.state.DocumentMetadata
import app.smallthingz.alldoc.state.DocumentState
import app.smallthingz.alldoc.state.DocumentStore
import app.smallthingz.alldoc.state.DocumentViewMode
import app.smallthingz.alldoc.state.StoredDocument
import app.smallthingz.alldoc.state.TextPosition
import app.smallthingz.alldoc.state.ViewportState
import app.smallthingz.alldoc.theme.ImageTreatment
import app.smallthingz.alldoc.theme.ReadingMode
import app.smallthingz.alldoc.theme.RegionThemeContext
import app.smallthingz.alldoc.theme.ThemeSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The owner survives configuration changes; every worker holds a lease until it stops. */
class ReaderModel internal constructor(application: Application, private val suppliedStore: DocumentStore?,
    private val suppliedSources: DocumentSources?) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, null, null)
    var info by mutableStateOf<DocumentInfo?>(null)
        private set
    var source by mutableStateOf<Uri?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var openingCancelled = false
        private set
    var pageLoading by mutableStateOf(false)
        private set
    var textLoading by mutableStateOf(false)
        private set
    var exporting by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var notice by mutableStateOf<String?>(null)
        private set
    var bitmap by mutableStateOf<Bitmap?>(null)
        private set
    internal var pageImages by mutableStateOf<Map<Int, ReaderPageImage>>(emptyMap())
        private set
    internal var pageDetails by mutableStateOf<Map<Int, ReaderPageDetail>>(emptyMap())
        private set
    var pageAspects by mutableStateOf<Map<Int, Float>>(emptyMap())
        private set
    var pageFraction = 0f
        private set
    var pagePositionRevision by mutableStateOf(0L)
        private set
    var horizontalPages by mutableStateOf(false)
        private set
    var text by mutableStateOf<String?>(null)
        private set
    internal var markdown by mutableStateOf<MarkdownDocument?>(null)
        private set
    var page by mutableStateOf(0)
        private set
    var anchorPage by mutableStateOf(0)
        private set
    var documentHash by mutableStateOf<String?>(null)
        private set
    var currentTitle by mutableStateOf("")
        private set
    var readerVisible by mutableStateOf(false)
        private set
    var restoringLastDocument by mutableStateOf(false)
        private set
    var viewport by mutableStateOf(ViewportState())
        private set
    var textMode by mutableStateOf(false)
        private set
    var workbook by mutableStateOf<WorkbookInfo?>(null)
        private set
    var sheetGeometry by mutableStateOf<SheetGeometry?>(null)
        private set
    var sheetFrame by mutableStateOf<SheetFrame?>(null)
        private set
    var sheetPosition by mutableStateOf(SheetPosition())
        private set
    var sheetsLoading by mutableStateOf(false)
        private set
    var sheetsError by mutableStateOf<String?>(null)
        private set
    val sheetMode get() = info?.supportsSheets == true && !horizontalPages && !textMode
    private var preferredTextMode = false
    var textPosition by mutableStateOf(TextPosition())
        private set
    var recentDocuments by mutableStateOf<List<StoredDocument>>(emptyList())
        private set
    var recentPreviews by mutableStateOf<Map<String, Bitmap>>(emptyMap())
        private set
    private val library = ReaderLibrary(application, viewModelScope) { notice = it }
    internal val libraryPages get() = library.pages
    val libraryPreviews get() = library.previews
    val libraryScanning get() = library.scanning
    val libraryHiddenTypes get() = library.hiddenTypes
    val librarySettingsLoaded get() = library.settingsReady
    fun setLibraryFilter(query: String, category: LibraryCategory?) = library.setFilter(query, category)
    fun setLibraryHiddenTypes(value: Set<LibraryCategory>) = library.updateHiddenTypes(value)
    fun setLibraryActive(active: Boolean) = library.setActive(active)
    fun setLibraryVisible(documents: List<LibraryDocument>) = library.setVisible(documents)
    fun setAppVisible(visible: Boolean) {
        library.setForeground(visible)
        foreground = visible
        previews.setVisible(if (visible) recentPreviewDocuments else emptyList())
        if (visible) {
            refreshRecentDocuments()
            startPageRender()
            if (textMode || info?.continuous == true) loadText()
            if (bitmap != null || text != null) discoverMetadata()
        } else {
            stopRecentCheck()
            rendering.cancel(); pausePageDetails(); attemptedPages.clear(); pageLoading = false
            stopTextExtraction()
            pauseMetadata()
            pauseSheets()
        }
    }
    fun refreshLibrary() { library.refresh(); refreshRecentDocuments() }
    fun addLibraryFolder(uri: Uri) = library.addFolder(uri)

    fun openLibraryDocument(document: LibraryDocument) {
        val uri = Uri.parse(document.uri)
        openDocument(uri, OpenRequest(uri, mimeHint = document.mimeType)) { options, signal ->
            prepareSource {
                sources.open(uri, options, signal, mimeHint = document.mimeType).also { prepared ->
                    if (document.hash != null && prepared.hash != document.hash) {
                        runCatching { library.invalidate(document) }
                    }
                }
            }
        }
    }

    private val previews: DocumentPreviews by lazy {
        DocumentPreviews(getApplication<Application>()) { hash, image, generation ->
            viewModelScope.launch {
                if (previews.isCurrent(generation) && isHomeDocument(hash)) {
                    val next = LinkedHashMap(recentPreviews)
                    next.keys.removeAll { !isHomeDocument(it) }
                    next[hash] = image
                    recentPreviews = next
                }
            }
        }.also { it.setAppearance(effectivePreviewSettings) }
    }
    private fun isHomeDocument(hash: String) = hash == documentHash || recentDocuments.take(6).any { it.hash == hash }
    private var recentCandidates = emptyList<StoredDocument>()
    private var missingRecentHashes by mutableStateOf<Set<String>>(emptySet())
    val currentDocumentAvailable get() = !stateAvailable || documentHash !in missingRecentHashes
    private var recentCheck: Job? = null
    private var recentSignal: CancellationSignal? = null
    private val recentGate = Mutex()
    private fun updateRecent(value: List<StoredDocument>) {
        recentCandidates = value.filter { DocumentProbe.supportedHistory(it.title, it.metadata.mimeType) }
        stopRecentCheck()
        refreshRecentDocuments()
    }
    internal fun refreshRecentDocuments() {
        if (!foreground || recentCheck?.isActive == true) return
        val candidates = recentCandidates
        val signal = CancellationSignal().also { recentSignal = it }
        recentCheck = viewModelScope.launch {
            val timeout = launch(Dispatchers.Default) { delay(2_000); signal.cancel() }
            val visible = try {
                withContext(Dispatchers.IO) { recentGate.withLock {
                    RecentDocuments.visible(getApplication(), candidates, signal, sources::importUri)
                } }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { candidates }
            finally { timeout.cancel() }
            val hashes = visible.mapTo(HashSet()) { it.hash }
            missingRecentHashes = candidates.mapTo(HashSet()) { it.hash }.apply { removeAll(hashes) }
            recentDocuments = recentCandidates.filter { it.hash in hashes }
            recentPreviews = recentPreviews.filterKeys(::isHomeDocument)
        }
    }
    private fun stopRecentCheck() {
        recentSignal?.cancel()
        recentCheck?.cancel()
        recentSignal = null
        recentCheck = null
    }
    private var recentPreviewDocuments = emptyList<StoredDocument>()
    fun setRecentPreviewsVisible(documents: List<StoredDocument>) {
        recentPreviewDocuments = documents.take(6)
        previews.setVisible(if (foreground) recentPreviewDocuments else emptyList())
    }
    fun requestRecentPreview(hash: String) {
        try {
            if (hash !in recentPreviews && isHomeDocument(hash)) {
                val warm = previews.cached(hash)
                if (warm != null) recentPreviews = recentPreviews + (hash to warm) else previews.request(hash)
            }
        } catch (_: OutOfMemoryError) { /* An optional thumbnail must not interrupt Home. */ }
    }
    var compoundSettingsReady by mutableStateOf(false)
        private set
    var copySettings by mutableStateOf(CopySettings())
        private set
    var copyUsageBytes by mutableStateOf(0L)
        private set
    var copyUsageCount by mutableStateOf(0)
        private set
    var storageLabel by mutableStateOf("Documents/AllDoc")
        private set
    var storageWarning by mutableStateOf<String?>(null)
        private set
    var storageBusy by mutableStateOf(false)
        private set
    var appTheme by mutableStateOf(AppTheme.SYSTEM)
        private set
    private val appAppearance = AppAppearanceStore(application)
    private var appThemeRevision = 0L
    var settings by mutableStateOf(ThemeSettings(mode = ReadingMode.SYSTEM))
        private set
    private var darkSystemTheme by mutableStateOf(application.resources.configuration.uiMode and
        Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
    val effectiveSettings get() = settings.resolved(darkSystemTheme)
    val effectivePreviewSettings get() = defaultSettings.resolved(darkSystemTheme)
    var hideMargins by mutableStateOf(false)
        private set
    val canHideMargins get() = info?.supportsMargins == true
    var outline by mutableStateOf<List<DocumentOutlineEntry>>(emptyList())
        private set
    var contentsLoading by mutableStateOf(false)
        private set
    var contentsError by mutableStateOf<String?>(null)
        private set
    private var contentsLoaded = false
    var notes by mutableStateOf(DocumentNotes())
        private set
    var notesVisible by mutableStateOf(false)
        private set
    var notesLoading by mutableStateOf(false)
        private set
    private var notesLoaded = false
    private var metadataStarted = false
    var sizeBytes by mutableStateOf(0L)
        private set
    private var defaultSettings by mutableStateOf(ThemeSettings(ReadingMode.SYSTEM))
    val appearanceForControls get() = if (readerVisible && documentHash != null) settings else defaultSettings
    private var appearanceRevision = 0L
    private var defaultRevision = 0L
    private var copyRevision = 0L
    private var locationRevision = 0L
    private var navigationRevision = 0L
    private var lastQueuedState: Pair<String, DocumentState>? = null
    private var stateAvailable = false
    private val store get() = suppliedStore ?: SharedStore ?: DocumentStore(getApplication()).also { SharedStore = it }
    private val sources get() = suppliedSources ?: sharedSources(getApplication())
    // Accessed only by StateWorker, so a new hash receives the latest ordered default.
    private var defaultAppearance = ThemeSettings(ReadingMode.SYSTEM)
    private var defaultLoaded = false
    private var committedCopySettings = CopySettings()
    private var current: Lease? = null
    private data class OpenRequest(val uri: Uri?, val hash: String? = null, val persistPermission: Boolean = false, val mimeHint: String? = null)
    private var passwordRetry: OpenRequest? = null
    val sourceMimeHint get() = info?.mimeType ?: passwordRetry?.mimeHint
    var documentId by mutableStateOf(0L)
        private set
    private var renderWidth = 0
    private var zoomAnimationStart: ViewportState? = null
    private var foreground = true
    private var visiblePages = 0..0
    private var renderGeneration = 0L
    private var textGeneration = 0L
    private val renderLock = Mutex()
    private data class RenderWork(val page: Int, val signal: CancellationSignal, val preview: Boolean = false)
    private var renderWork by mutableStateOf<RenderWork?>(null)
    private val attemptedPages = HashSet<Int>()
    var failedPages by mutableStateOf<Set<Int>>(emptySet())
        private set
    private var previewSaved = false
    private val opening = Task { loading = false }
    private val rendering = Task { pageLoading = false }
    private val detailing = Task { }
    private var detailRequests = emptyMap<Int, PageRegion>()
    private val attemptedDetails = HashMap<Int, PageRegion>()
    private var detailGeneration = 0L
    private val extracting = Task { textLoading = false }
    private val saving = Task { exporting = false }
    private val sheetRendering = Task { sheetsLoading = false; sheetsError = "Not enough memory." }
    private val printLayout = Task { sheetsLoading = false }
    private data class SheetWork(val viewport: SheetViewport, val appearance: ThemeSettings)
    private var sheetRequest: SheetViewport? = null
    private var sheetVisibleSize = IntSize.Zero
    private var sheetDensity = 0f
    private var pendingSheet: SheetWork? = null
    private var renderedSheet: SheetWork? = null
    private var sheetGeneration = 0L
    private var sheetResident = false
    private var sheetCovering = false
    private var sheetCoverDelay = 250L
    private var sheetPause: CompletableDeferred<Unit>? = null
    private var preparingPrint = false
    private val outlining = Task { contentsLoading = false }
    private val annotating = Task { notesLoading = false; notes = DocumentNotes(warnings = listOf("Notes could not be loaded.")) }
    private var pendingExport by mutableStateOf<Export?>(null)
    val canExport get() = !exporting && pendingExport == null

    init {
        library.setAppearance(effectivePreviewSettings)
        StateWorker.execute {
            drainStateWrites()
            val app = runCatching { appAppearance.read() }.getOrElse { stateFailure(it); AppTheme.SYSTEM }
            val saved = loadDefaultAppearance()
            viewModelScope.launch {
                if (appThemeRevision == 0L) appTheme = app
                if (defaultRevision == 0L) { defaultSettings = saved; updateHomeAppearance() }
                if (appearanceRevision == 0L && documentHash == null) applyAppearance(saved)
            }
            runCatching { reconcileImports(); store.copySettings() to store.recentDocuments() }.onSuccess { (copies, recent) ->
                committedCopySettings = copies
                viewModelScope.launch {
                    if (copyRevision == 0L) copySettings = copies
                    updateRecent(recent)
                    viewModelScope.launch(Dispatchers.IO) { runCatching { library.seed(recent) } }
                    refreshStorageUsage()
                }
            }.onFailure { failure -> stateFailure(failure) }
            // Main receives this after the hydrated values above, including valid fallbacks on failure.
            viewModelScope.launch { compoundSettingsReady = true }
        }
    }

    fun open(uri: Uri, password: String? = null, persistPermission: Boolean = false, mimeHint: String? = null) {
        val request = OpenRequest(uri, persistPermission = persistPermission, mimeHint = mimeHint?.takeIf { it.length <= 128 })
        openDocument(uri, request) { options, signal ->
            prepareSource {
                val context = getApplication<Application>()
                if (persistPermission) runCatching {
                    context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                sources.open(uri, options, signal, password, mimeHint = request.mimeHint)
            }
        }
    }

    fun openExample(example: ExampleDocument) {
        openDocument(null) { options, signal ->
            prepareSource {
                val uri = ExampleDocuments.materialize(getApplication(), example, signal)
                sources.open(uri, options, signal, mimeHint = example.mime)
            }
        }
    }

    /** A rejected incoming request still owns navigation and cancels the previous open. */
    fun rejectOpen(message: String) = openDocument(null) { _, _ -> throw java.io.IOException(message) }

    fun reopenDocument(hash: String, fallback: Uri? = null, password: String? = null, mimeHint: String? = null) {
        val request = OpenRequest(fallback, hash, mimeHint = mimeHint?.takeIf { it.length <= 128 })
        openDocument(fallback, request) { options, signal ->
            val saved = readState { runCatching { store.get(hash) }.getOrElse {
                if (fallback == null) throw it else null
            } }
            prepareSource {
                val retained = try { sources.importUri(hash, options, signal) }
                catch (failure: Exception) {
                    if (cancelled(failure, signal)) throw failure
                    null
                }
                val retainedLocations = listOfNotNull(retained?.toString(), saved?.metadata?.importUri)
                val savedFormat = saved?.let { SavedFormat(it.hash, it.title, it.metadata.mimeType) }
                val fallbackFormat = fallback?.takeIf { request.mimeHint != null }?.let {
                    SavedFormat(hash, it.lastPathSegment?.substringAfterLast('/') ?: "Document", request.mimeHint)
                }
                val locations = (retainedLocations + listOfNotNull(saved?.metadata?.sourceUri, fallback?.toString())).distinct()
                var lastFailure: Exception? = null
                var prepared: PreparedDocument? = null
                for (location in locations) {
                    signal.throwIfCanceled()
                    try {
                        val format = savedFormat.takeIf { location in retainedLocations || location == saved?.metadata?.sourceUri }
                            ?: fallbackFormat.takeIf { location == fallback?.toString() }
                        prepared = sources.open(Uri.parse(location), options, signal, password, format)
                        break
                    }
                    catch (failure: Exception) {
                        if (cancelled(failure, signal) || failure is PdfPasswordException || failure is PdfPasswordUnsupportedException) throw failure
                        lastFailure = failure
                    }
                }
                if (prepared == null) {
                    sources.reusableLocal(hash, saved?.metadata?.sizeBytes, null, signal)?.let {
                        prepared = sources.openIndexed(it, signal, password)
                    }
                }
                prepared ?: throw (lastFailure ?: java.io.IOException("File unavailable."))
            }
        }
    }

    fun restoreLastDocument() {
        if (restoringLastDocument) return
        restoringLastDocument = true
        val version = documentId
        val navigation = navigationRevision
        viewModelScope.launch {
            try {
                val hash = readState {
                    try { validateResume() }
                    catch (failure: Exception) { stateFailure(failure); null }
                }
                if (hash != null && version == documentId && navigation == navigationRevision && info == null && !loading) {
                    reopenDocument(hash)
                }
            } finally {
                if (version == documentId && navigation == navigationRevision) restoringLastDocument = false
            }
        }
    }

    fun cancelAutomaticResume() {
        navigationRevision++
        restoringLastDocument = false
    }

    fun retryWithPassword(password: String, identity: Long = documentId, expectedMessage: String? = error) {
        if (identity != documentId || loading || info != null || expectedMessage == null || error != expectedMessage) return
        val request = passwordRetry ?: return
        retry(request, password)
    }

    private fun retry(request: OpenRequest, password: String? = null) {
        if (request.hash != null) reopenDocument(request.hash, request.uri, password, request.mimeHint)
        else request.uri?.let { open(it, password, request.persistPermission, request.mimeHint) }
    }

    private suspend fun prepareSource(block: () -> PreparedDocument): PreparedDocument {
        var prepared: PreparedDocument? = null
        try {
            try { withContext(Dispatchers.IO) { prepared = block() } }
            finally { withContext(NonCancellable + StateDispatcher) { reconcileImports() } }
            return requireNotNull(prepared).also { prepared = null }
        } finally {
            prepared?.let { orphan -> withContext(NonCancellable + Dispatchers.IO) { orphan.close() } }
        }
    }

    private fun openDocument(uri: Uri?, retry: OpenRequest? = null,
        opener: suspend (CopySettings?, CancellationSignal) -> PreparedDocument) {
        library.setActive(false)
        flushDocumentState()
        openingCancelled = false
        documentId++
        zoomAnimationStart = null
        val version = documentId
        passwordRetry = retry
        cancelAutomaticResume()
        readerVisible = true
        saveResume(null)
        cancelWork()
        current?.retire()
        current = null
        source = uri
        documentHash = null
        currentTitle = ""
        stateAvailable = false
        lastQueuedState = null
        info = null
        workbook = null
        sheetGeometry = null
        sheetFrame = null
        sheetPosition = SheetPosition()
        sheetVisibleSize = IntSize.Zero
        sheetDensity = 0f
        sheetRequest = null
        pendingSheet = null
        renderedSheet = null
        sheetsError = null
        outline = emptyList()
        contentsError = null
        contentsLoaded = false
        notes = DocumentNotes()
        notesLoaded = false
        metadataStarted = false
        notesVisible = false
        sizeBytes = 0
        bitmap = null
        pageImages = emptyMap()
        pageAspects = emptyMap()
        pageFraction = 0f
        horizontalPages = false
        pagePositionRevision++
        previewSaved = false
        sheetCoverDelay = 250L
        text = null
        markdown = null
        page = 0
        anchorPage = 0
        preferredTextMode = false
        textMode = false
        loading = true
        error = null
        notice = null
        opening.start { signal ->
            var opened: PreparedDocument? = null
            try {
                val (options, optionsWarning) = readState {
                    try { store.copySettings() to null }
                    catch (_: Exception) { null to "Saved settings are unavailable." }
                }
                opened = opener(options, signal)
                signal.throwIfCanceled()
                val prepared = requireNotNull(opened)
                val documentInfo = withContext(Dispatchers.IO) {
                    prepared.session.info.let { if (it.supportsSheets) it else it.copy(pageCount = prepared.session.pageCount(signal)) }
                }
                signal.throwIfCanceled()
                withContext(Dispatchers.IO) { runCatching { library.record(prepared) } }
                val restored = readState { restoreState(prepared) }
                signal.throwIfCanceled()
                if (version == documentId) {
                    current = Lease(prepared.session)
                    opened = null // The model now owns the session, including publication failures.
                    passwordRetry = null
                    info = documentInfo
                    sheetResident = documentInfo.supportsSheets // The lazy Office constructor already imported its workbook.
                    source = uri ?: prepared.sourceUri ?: prepared.importUri
                    documentHash = prepared.hash
                    currentTitle = restored.title
                    sizeBytes = prepared.sizeBytes
                    if (restored.available) updateRecent(restored.recent)
                    missingRecentHashes = missingRecentHashes - prepared.hash
                    val saved = restored.state
                    page = if (documentInfo.supportsSheets && documentInfo.pageCount == 0) saved.page else saved.page.coerceIn(0, (documentInfo.pageCount - 1).coerceAtLeast(0))
                    anchorPage = page
                    viewport = saved.viewport
                    sheetPosition = saved.sheetPosition
                    pageFraction = saved.pageFraction
                    horizontalPages = saved.horizontalPages && !documentInfo.continuous && documentInfo.pageCount > 0
                    pagePositionRevision++
                    visiblePages = page..page
                    textPosition = saved.textPosition
                    preferredTextMode = saved.mode == DocumentViewMode.TEXT && (documentInfo.textAvailable || documentInfo.reflowable)
                    textMode = preferredTextMode
                    notesVisible = saved.notesVisible && documentInfo.supportsNotes
                    hideMargins = saved.hideMargins && canHideMargins
                    settings = saved.appearance
                    appearanceRevision++
                    stateAvailable = restored.available
                    lastQueuedState = if (restored.available) prepared.hash to saved else null
                    loading = false
                    notice = listOfNotNull(optionsWarning, prepared.retentionWarning, restored.warning)
                        .distinct().takeIf { it.isNotEmpty() }?.joinToString(" ")
                    if (readerVisible) saveResume(prepared.hash.takeIf { restored.available })
                    if (!foreground || !readerVisible || textMode) pauseSheets()
                    if (saved.horizontalPages && documentInfo.supportsSheets && documentInfo.pageCount == 0 && !textMode)
                        choosePageView(true) // Resolve genuine print pages only when restoring Present.
                    else if (textMode || info?.continuous == true) loadText() else render()
                    refreshStorageUsage()
                }
            } catch (failure: Exception) {
                if (!cancelled(failure, signal) && version == documentId) {
                    error = message(failure)
                    loading = false
                }
            } finally {
                opened?.let { orphan -> withContext(NonCancellable + Dispatchers.IO) { orphan.close() } }
            }
        }
    }

    /** Restore the cancelled home state without replaying its URI or the Activity's intent. */
    internal fun restoreCancelledOpen(uri: Uri?) {
        source = uri
        openingCancelled = true
        passwordRetry = null
        readerVisible = false
    }

    fun cancelLoading(identity: Long = documentId) {
        if (identity != documentId || !loading) return
        openingCancelled = true
        passwordRetry = null
        documentId++
        opening.cancel()
        loading = false
        readerVisible = false
        saveResume(null)
        notice = "Opening cancelled."
    }

    fun showReader(visible: Boolean, identity: Long = documentId): Boolean {
        if (identity != documentId || visible && info == null && !loading) return false
        if (!visible) { passwordRetry = null; refreshRecentDocuments() }
        navigationRevision++
        if (readerVisible == visible) {
            if (!visible) saveResume(null)
            return true
        }
        readerVisible = visible
        if (visible) {
            library.setActive(false)
            startPageRender()
            if (textMode || info?.continuous == true) loadText()
            if (bitmap != null || text != null) discoverMetadata()
        } else {
            rendering.cancel(); pausePageDetails(); attemptedPages.clear(); pageLoading = false
            stopTextExtraction()
            pauseMetadata()
            pauseSheets()
        }
        flushDocumentState()
        saveResume(if (visible && stateAvailable) documentHash else null)
        return true
    }

    fun updateViewport(value: ViewportState, identity: Long = documentId) {
        if (identity != documentId || info == null) return
        val previousScale = renderScale(viewport)
        val previousRotation = viewport.rotation % 2
        viewport = value.normalized()
        if ((renderScale(viewport) != previousScale || viewport.rotation % 2 != previousRotation) &&
            !textMode && info?.continuous != true && zoomAnimationStart == null) refinePages()
    }
    fun setZoomAnimation(active: Boolean, identity: Long = documentId) {
        if (identity != documentId || info == null) return
        if (active) {
            if (zoomAnimationStart == null) { zoomAnimationStart = viewport; pausePageDetails() }
        } else {
            val start = zoomAnimationStart ?: return
            zoomAnimationStart = null
            if (renderScale(start) != renderScale(viewport) || start.rotation % 2 != viewport.rotation % 2)
                refinePages()
            else startPageRender()
        }
    }
    fun updateTextPosition(value: TextPosition, identity: Long = documentId) {
        if (identity == documentId && info != null) textPosition = value.normalized()
    }
    fun setTextMode(value: Boolean, identity: Long = documentId) {
        if (identity != documentId) return
        val next = value && info?.let { it.textAvailable || it.reflowable } == true
        if (textMode == next) return
        if (preparingPrint) pauseSheets()
        printLayout.cancel()
        preparingPrint = false
        textMode = next
        render()
        if (next || info?.continuous == true) { bitmap = null; loadText() } else stopTextExtraction()
        flushDocumentState()
    }

    /** Capture the hash and values together; later document changes cannot redirect this write. */
    fun flushDocumentState() {
        val hash = documentHash ?: return
        if (!stateAvailable) return
        val snapshot = DocumentState(anchorPage, viewport, textPosition,
            if (preferredTextMode) DocumentViewMode.TEXT else DocumentViewMode.PAGES,
            settings, hideMargins, pageFraction, horizontalPages, notesVisible, sheetPosition).normalized()
        val queued = hash to snapshot
        if (lastQueuedState == queued) return
        lastQueuedState = queued
        recentCandidates = recentCandidates.map { if (it.hash == hash) it.copy(state = snapshot) else it }
        recentDocuments = recentDocuments.map { if (it.hash == hash) it.copy(state = snapshot) else it }
        enqueueStateWrite("document:$hash") {
            try {
                check(store.saveState(hash, snapshot)) { "Reading history unavailable." }
            } catch (failure: Exception) {
                viewModelScope.launch { if (lastQueuedState == queued) lastQueuedState = null }
                stateFailure(failure)
            }
        }
    }

    fun updateCopySettings(value: CopySettings) {
        if (copySettings.treeUri != value.treeUri) ++locationRevision
        val revision = ++copyRevision
        copySettings = value
        enqueueStateWrite("copies") {
            try {
                store.saveCopySettings(value)
                committedCopySettings = value
                viewModelScope.launch { if (copyRevision == revision) refreshStorageUsage() }
            } catch (failure: Exception) {
                val restored = runCatching { store.copySettings() }.getOrDefault(committedCopySettings)
                committedCopySettings = restored
                viewModelScope.launch { if (copyRevision == revision) copySettings = restored }
                stateFailure(failure)
            }
        }
    }

    fun useStorageFolder(uri: Uri) {
        val revision = ++locationRevision
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.takePersistableUriPermission(uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                }
                // A restored picker result can precede startup preference hydration.
                snapshotFlow { compoundSettingsReady }.first { it }
                if (locationRevision == revision) updateCopySettings(copySettings.copy(treeUri = uri.toString()))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (locationRevision == revision) error = "Could not use that folder. ${message(failure)}"
            }
        }
    }

    private var refreshStorageAgain = false
    fun refreshStorageUsage() {
        if (storageBusy) { refreshStorageAgain = true; return }
        storageBusy = true
        viewModelScope.launch {
            val warnings = linkedSetOf<String>()
            try {
                val options = readState { store.copySettings() }
                val existing = withContext(NonCancellable + StateDispatcher) { reconcileImports() }
                if (existing != null) {
                    warnings += existing
                    // A failed reference write forbids more deletion, not truthful usage observation.
                    val observed = withContext(Dispatchers.IO) { sources.observeStatus(options) }
                    if (copySettings == options) {
                        copyUsageBytes = observed.bytes
                        copyUsageCount = observed.count
                        storageLabel = observed.location
                    } else refreshStorageAgain = true
                    storageWarning = existing
                    return@launch
                }
                do {
                    if (copySettings != options) { refreshStorageAgain = true; break }
                    var status: app.smallthingz.alldoc.sources.CopyStatus? = null
                    var references: String? = null
                    try { withContext(Dispatchers.IO) { status = sources.status(options) } }
                    finally {
                        // Receipt acknowledgement is a durability boundary, including on cancellation.
                        references = withContext(NonCancellable + StateDispatcher) { reconcileImports() }
                        references?.let(warnings::add)
                    }
                    val completed = requireNotNull(status)
                    completed.warning?.let(warnings::add)
                    if (copySettings != options) { refreshStorageAgain = true; break }
                    copyUsageBytes = completed.bytes
                    copyUsageCount = completed.count
                    storageLabel = completed.location
                    storageWarning = warnings.takeIf { it.isNotEmpty() }?.joinToString(" ")
                    if (references != null) break
                    if (!isActive) throw CancellationException("Storage trimming cancelled")
                } while (completed.trimPending)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                warnings += message(failure)
                storageWarning = warnings.joinToString(" ")
            }
            finally {
                storageBusy = false
                if (refreshStorageAgain) { refreshStorageAgain = false; refreshStorageUsage() }
            }
        }
    }

    fun clearRetainedCopies() {
        if (storageBusy) return
        storageBusy = true
        viewModelScope.launch {
            try {
                val warnings = linkedSetOf<String>()
                val existing = withContext(NonCancellable + StateDispatcher) { reconcileImports() }
                existing?.let(warnings::add)
                if (existing == null) {
                    var after: String? = null
                    do {
                        var change: app.smallthingz.alldoc.sources.CopyChange? = null
                        var references: String? = null
                        try { withContext(Dispatchers.IO) { change = sources.clear(after) } }
                        finally {
                            // A cancelled caller still commits reference invalidations for its durable batch.
                            references = withContext(NonCancellable + StateDispatcher) { reconcileImports() }
                            references?.let(warnings::add)
                        }
                        val completed = requireNotNull(change)
                        completed.warning?.let(warnings::add)
                        if (references != null) break // Do not fill the receipt journal after a failed reconciliation.
                        after = completed.continuation
                        if (!isActive) throw CancellationException("Storage clearing cancelled")
                    } while (after != null)
                }
                withContext(Dispatchers.IO) {
                    try { previews.clear() }
                    catch (_: OutOfMemoryError) {
                        warnings += "Previews could not be cleared."
                    }
                }
                recentPreviews = emptyMap()
                updateRecent(readState { store.recentDocuments() })
                notice = warnings.takeIf { it.isNotEmpty() }?.joinToString(" ")
                    ?: "Retained copies cleared. Reading positions were kept."
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = message(failure) }
            finally {
                storageBusy = false
                refreshStorageAgain = false
                refreshStorageUsage()
            }
        }
    }

    private fun preferences() = getApplication<Application>().getSharedPreferences("reader", 0)
    // StateWorker may drain an early preference write before the model's initialization read.
    private fun loadDefaultAppearance(): ThemeSettings {
        if (!defaultLoaded) {
            defaultAppearance = runCatching {
                val saved = preferences()
                ThemeSettings(ReadingMode.valueOf(saved.getString("mode", "SYSTEM")!!),
                    when {
                        saved.contains("imageTreatment") -> ImageTreatment.valueOf(saved.getString("imageTreatment", "AUTO")!!)
                        saved.contains("preserveImages") -> ImageTreatment.fromLegacy(saved.getBoolean("preserveImages", true))
                        else -> ImageTreatment.AUTO
                    }, saved.getInt("paperRoughness", 0)).normalized()
            }.getOrDefault(ThemeSettings(ReadingMode.SYSTEM))
            defaultLoaded = true
        }
        return defaultAppearance
    }
    private suspend fun <T> readState(block: () -> T): T = orderedStateRead(block)
    // Commit conditional invalidations before acknowledging durable, versioned receipts.
    private fun reconcileImports(): String? {
        var failure: Exception? = null
        try {
            sources.pendingEvictions().chunked(1000).forEach { batch ->
                store.clearMatchingImports(batch.map { it.hash to it.importUri })
                batch.forEach { library.removeLocation(it.importUri, it.hash) }
                sources.acknowledgeEvictions(batch.map { it.id })
            }
        } catch (error: Exception) { failure = error }
        // Deleted copies must not remain cold-resume targets even if reference cleanup failed.
        try { validateResume() } catch (error: Exception) { if (failure == null) failure = error }
        return failure?.let {
            "Saved copies could not be refreshed."
        }?.also { warning -> viewModelScope.launch { storageWarning = warning } }
    }

    private data class RestoredState(val title: String, val state: DocumentState,
        val recent: List<StoredDocument>, val available: Boolean, val warning: String?)

    private fun restoreState(prepared: PreparedDocument): RestoredState {
        val initial = DocumentState(appearance = defaultAppearance)
        var warning: String? = null
        val imported = try { sources.importUri(prepared.hash)?.toString() }
        catch (_: Exception) { warning = "Saved copies are unavailable."; null }
        return try {
            val metadata = DocumentMetadata(prepared.displayName, prepared.session.info.mimeType, prepared.sizeBytes,
                prepared.sourceUri?.toString(), imported)
            val saved = store.open(prepared.hash, metadata, initial)
            RestoredState(saved.title, saved.state, store.recentDocuments(), true, warning)
        } catch (_: Exception) {
            RestoredState(prepared.displayName, initial, emptyList(), false,
                "Reading position could not be saved.")
        }
    }
    // StateWorker owns validation and publication together, preserving switch/Home ordering.
    private fun resumeTarget(hash: String?): String? {
        if (hash == null) return null
        val saved = store.get(hash) ?: return null
        val metadata = saved.metadata
        if (!DocumentProbe.supportedHistory(saved.title, metadata.mimeType)) return null
        if (metadata.sourceUri != null || sources.importUri(hash) != null) return hash
        val imported = metadata.importUri ?: return null
        return hash.takeUnless { sources.hasPendingEviction(hash, imported) }
    }
    private fun commitResume(hash: String?) {
        val preferences = preferences()
        val previous = preferences.getString("activeDocument", null)
        if (previous != hash && !runCatching { preferences.edit().putString("activeDocument", hash).commit() }.getOrDefault(false)) {
            // commit() changes the memory map even when its disk write fails. Restore it so
            // the next request for this same target still attempts persistence.
            runCatching { preferences.edit().putString("activeDocument", previous).commit() }
            error("Could not save the active document.")
        }
    }
    private fun validateResume(): String? = resumeTarget(preferences().getString("activeDocument", null))
        .also(::commitResume)
    private fun saveResume(hash: String?) {
        enqueueStateWrite("resume") {
            try { commitResume(resumeTarget(hash)) }
            catch (failure: Exception) { stateFailure(failure) }
        }
    }

    private fun stateFailure(failure: Throwable) {
        android.util.Log.w("AllDoc", "Could not save document state", failure)
        viewModelScope.launch { notice = "Reading settings could not be saved." }
    }

    fun goToPage(index: Int, identity: Long = documentId) {
        if (identity != documentId || index !in 0 until (info?.pageCount ?: 0)) return
        page = index
        anchorPage = index
        pageFraction = 0f
        pagePositionRevision++
        viewport = viewport.copy(centerX = .5f, centerY = .5f)
        requestVisiblePages(index..index)
        syncPage()
        flushDocumentState()
    }

    fun updatePagePosition(index: Int, fraction: Float, identity: Long = documentId) {
        if (identity != documentId || index !in 0 until (info?.pageCount ?: 0)) return
        anchorPage = index
        pageFraction = fraction.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
    }

    fun updateCurrentPage(index: Int, identity: Long = documentId) {
        if (identity != documentId || index !in 0 until (info?.pageCount ?: 0) || page == index) return
        page = index
        attemptedPages.clear()
        syncPage()
        startPageRender()
    }

    fun setHorizontalPages(value: Boolean, identity: Long = documentId) {
        if (identity != documentId || horizontalPages == value) return
        choosePageView(value, identity)
    }

    fun chooseTextView(identity: Long = documentId) {
        if (identity != documentId || loading || info?.let { it.textAvailable || it.reflowable } != true) return
        preferredTextMode = true
        setTextMode(true, identity)
        flushDocumentState()
    }

    fun choosePageView(horizontal: Boolean, identity: Long = documentId) {
        if (identity != documentId || info == null || loading) return
        if (horizontal && info?.supportsSheets == true && info?.pageCount == 0) {
            if (!foreground || !readerVisible) { horizontalPages = true; return }
            if (preparingPrint) return
            horizontalPages = false // Keep the grid usable if explicit print layout fails.
            pauseSheets()
            val owner = current?.borrow() ?: return
            val version = documentId
            val generation = sheetGeneration
            preparingPrint = true
            sheetsLoading = true
            printLayout.start { signal ->
                try {
                    sheetPause?.await()
                    val value = withContext(Dispatchers.IO) { owner.session.printInfo(signal) }
                    signal.throwIfCanceled()
                    check(value.pageCount > 0) { "Print layout unavailable" }
                    if (version == documentId && generation == sheetGeneration && foreground && readerVisible) {
                        info = value.copy(warnings = (value.warnings + workbook?.warnings.orEmpty()).distinct())
                        page = page.coerceIn(0, value.pageCount - 1)
                        anchorPage = anchorPage.coerceIn(0, value.pageCount - 1)
                        visiblePages = page..page
                        preparingPrint = false
                        choosePageView(true, version)
                    }
                } catch (failure: Exception) {
                    if (!cancelled(failure, signal) && version == documentId && generation == sheetGeneration) notice = message(failure)
                } finally {
                    owner.release()
                    if (version == documentId && generation == sheetGeneration) {
                        preparingPrint = false
                        sheetsLoading = false
                        startPageRender()
                    }
                }
            }
            return
        }
        // Normal and Present use the same raster pixels; changing their layout must not blank them.
        val retainPages = !textMode && info?.let { !it.continuous && !it.supportsSheets && it.pageCount > 0 } == true
        if (preparingPrint) pauseSheets()
        printLayout.cancel()
        preparingPrint = false
        preferredTextMode = false
        horizontalPages = horizontal
        if (textMode) { textMode = false; stopTextExtraction() }
        if (retainPages) refinePages() else render()
        flushDocumentState()
    }

    fun setNotesVisible(value: Boolean, identity: Long = documentId) {
        if (identity != documentId || info == null || loading) return
        val next = value && info?.supportsNotes == true
        if (notesVisible == next) return
        notesVisible = next
        if (next) loadNotes(identity)
        flushDocumentState()
    }

    private fun updateHomeAppearance() {
        val appearance = effectivePreviewSettings
        library.setAppearance(appearance)
        if (previews.setAppearance(appearance)) {
            recentPreviews = emptyMap()
            recentDocuments.take(6).forEach { requestRecentPreview(it.hash) }
        }
    }

    fun setSystemDark(value: Boolean) {
        if (darkSystemTheme == value) return
        darkSystemTheme = value
        updateHomeAppearance()
        if (settings.mode == ReadingMode.SYSTEM) { bitmap = null; render() }
    }

    fun setHideMargins(value: Boolean, identity: Long = documentId) {
        if (identity != documentId || !canHideMargins || hideMargins == value) return
        hideMargins = value
        pageAspects = emptyMap()
        viewport = ViewportState(rotation = viewport.rotation)
        bitmap = null
        render()
        flushDocumentState()
    }

    private fun pauseMetadata() {
        outlining.cancel()
        annotating.cancel()
        contentsLoading = false
        notesLoading = false
        metadataStarted = false
    }

    private fun discoverMetadata() {
        if (metadataStarted || !foreground || !readerVisible || info == null || textLoading || exporting) return
        metadataStarted = true
        if (info?.supportsOutline == true && contentsError == null) loadContents()
        loadNotes()
    }

    fun loadContents(identity: Long = documentId) {
        if (identity != documentId || contentsLoaded || contentsLoading) return
        val owner = current?.borrow() ?: return
        contentsError = null
        contentsLoading = true
        outlining.start { signal ->
            try {
                val entries = withContext(Dispatchers.IO) { owner.session.outline(signal) }
                signal.throwIfCanceled()
                if (identity == documentId) { outline = entries; contentsLoaded = true }
            } catch (failure: Exception) {
                if (!cancelled(failure, signal) && identity == documentId) contentsError = message(failure)
            } catch (_: OutOfMemoryError) {
                if (!signal.isCanceled && identity == documentId) contentsError = "Not enough memory to load contents."
            } finally {
                if (identity == documentId && !signal.isCanceled) contentsLoading = false
                owner.release()
            }
        }
    }

    fun loadNotes(identity: Long = documentId) {
        if (identity != documentId || notesLoaded || notesLoading || info?.supportsNotes != true) return
        val owner = current?.borrow() ?: return
        notesLoading = true
        annotating.start { signal ->
            try {
                val result = withContext(Dispatchers.IO) { owner.session.notes(signal) }
                signal.throwIfCanceled()
                if (identity == documentId) { notes = result; notesLoaded = true }
            } catch (_: OutOfMemoryError) {
                if (!signal.isCanceled && identity == documentId)
                    notes = DocumentNotes(warnings = listOf("Not enough memory to load notes."))
            } catch (failure: Exception) {
                if (!cancelled(failure, signal) && identity == documentId) {
                    notes = DocumentNotes(warnings = listOf("Notes could not be loaded: ${message(failure)}"))
                }
            } finally {
                if (identity == documentId && !signal.isCanceled) notesLoading = false
                owner.release()
            }
        }
    }

    internal fun createPrintAdapter(identity: Long = documentId): DocumentPrintAdapter? {
        if (identity != documentId || loading) return null
        val owner = current?.borrow() ?: return null
        pauseSheets()
        return DocumentPrintAdapter(owner.session, currentTitle.ifBlank { "Document" }) {
            owner.release()
            startPageRender()
        }
    }

    fun setRenderWidth(width: Int) {
        if (width <= 0) return
        val next = width.coerceIn(720, 1600)
        if (next == renderWidth) return
        renderWidth = next
        refinePages()
    }

    private fun refinePages() {
        // Size and zoom changes keep the published page while background detail is replaced.
        renderGeneration++
        rendering.cancel()
        attemptedPages.clear()
        failedPages = emptySet()
        syncPage()
        startPageRender()
    }

    fun updateAppTheme(value: AppTheme) {
        val revision = ++appThemeRevision
        appTheme = value
        enqueueStateWrite("app-appearance") {
            val restored = runCatching { appAppearance.read() }.getOrDefault(AppTheme.SYSTEM)
            try { appAppearance.write(value) }
            catch (failure: Exception) {
                runCatching { appAppearance.write(restored) }
                viewModelScope.launch { if (appThemeRevision == revision) appTheme = restored }
                stateFailure(failure)
            }
        }
    }

    fun setAppearance(requested: ThemeSettings, identity: Long = documentId) {
        if (identity != documentId || loading) return
        val value = requested.normalized()
        val revision = ++appearanceRevision
        if (readerVisible && documentHash != null) {
            applyAppearance(value)
            flushDocumentState()
            return
        }
        val globalRevision = ++defaultRevision
        defaultSettings = value
        updateHomeAppearance()
        if (documentHash == null) applyAppearance(value)
        enqueueStateWrite("appearance") {
            val restored = loadDefaultAppearance()
            val saved = runCatching {
                getApplication<Application>().getSharedPreferences("reader", 0).edit()
                    .putString("mode", value.mode.name).putString("imageTreatment", value.imageTreatment.name)
                    .putInt("paperRoughness", value.paperRoughness).commit()
            }.getOrDefault(false)
            if (saved) defaultAppearance = value
            else {
                // Restore the shared preference map as well as these controls. A fresh model
                // must not reload a value whose disk write failed; newer writes run after this.
                runCatching { preferences().edit().putString("mode", restored.mode.name)
                    .putString("imageTreatment", restored.imageTreatment.name)
                    .putInt("paperRoughness", restored.paperRoughness).commit() }
                viewModelScope.launch {
                    if (defaultRevision == globalRevision) {
                        defaultSettings = restored
                        updateHomeAppearance()
                        if (appearanceRevision == revision && documentHash == null) applyAppearance(restored)
                    }
                    notice = "Could not save appearance."
                }
            }
        }
    }

    private fun applyAppearance(value: ThemeSettings) {
        if (settings == value) return
        settings = value
        bitmap = null
        render()
    }

    private fun render() {
        pausePageDetails()
        attemptedDetails.clear()
        pageDetails = emptyMap()
        renderGeneration++
        rendering.cancel()
        pageImages = emptyMap()
        attemptedPages.clear()
        failedPages = emptySet()
        syncPage()
        startPageRender()
    }

    fun requestVisiblePages(range: IntRange, identity: Long = documentId) {
        if (identity != documentId) return
        val count = info?.pageCount ?: return
        if (range.isEmpty() || count == 0) return
        val first = range.first.coerceIn(0, count - 1)
        val next = first..range.last.coerceIn(first, minOf(count - 1, first + 253))
        if (visiblePages != next) {
            pausePageDetails()
            visiblePages = next
            detailRequests = detailRequests.filterKeys { it in next }
            attemptedDetails.clear()
            pageDetails = pageDetails.filterKeys { it in next }
            attemptedPages.clear()
            failedPages = failedPages.intersect(pageOrder().toSet())
        }
        startPageRender()
    }

    /** At most two viewport-sized patches; never a zoom-sized full-page allocation. */
    internal fun requestVisibleRegions(regions: Map<Int, PageRegion>, identity: Long = documentId) {
        if (identity != documentId) return
        val count = info?.pageCount ?: return
        val next = regions.entries.asSequence().filter { it.key in 0 until count }
            .take(2).associateTo(LinkedHashMap()) { it.key to it.value }
        if (next == detailRequests) { startPageRender(); return }
        pausePageDetails()
        if (next.size != detailRequests.size) attemptedDetails.clear()
        detailRequests = next
        attemptedDetails.entries.removeAll { next[it.key] != it.value }
        pageDetails = pageDetails.filter { (index, detail) ->
            index in next && detail.bitmap.allocationByteCount <= pageDetailBudget(next.size)
        }
        next.keys.filter { pageImages[it]?.missingThemeContext == true }.forEach { attemptedPages.remove(it) }
        startPageRender()
    }

    private fun pausePageDetails(clear: Boolean = false) {
        detailGeneration++
        detailing.cancel()
        if (clear) {
            detailRequests = emptyMap()
            attemptedDetails.clear()
            pageDetails = emptyMap()
        }
    }

    private fun startPageDetails() {
        if (detailing.running || detailRequests.isEmpty() || zoomAnimationStart != null || exporting ||
            !foreground || !readerVisible || textMode || sheetMode || info?.continuous == true) return
        if (visiblePages.any { it !in pageImages && it !in failedPages }) return
        if (detailRequests.none { (index, region) -> pageImages[index]?.let { !it.missingThemeContext } == true && attemptedDetails[index] != region }) return
        val owner = current?.borrow() ?: return
        val identity = documentId
        val generation = detailGeneration
        val appearance = effectiveSettings
        detailing.start { signal ->
            try {
                // Coalesce the layout callback and final gesture frame before entering a native engine.
                delay(100)
                while (identity == documentId && generation == detailGeneration) {
                    val request = detailRequests.entries.firstOrNull { (index, region) ->
                        pageImages[index]?.let { !it.missingThemeContext } == true && attemptedDetails[index] != region
                    } ?: break
                    val index = request.key
                    val wanted = request.value
                    val upgrading = renderWork?.takeIf { it.page == index && !it.preview }
                    if (upgrading != null) {
                        // Await only this page's publication, not adjacent prefetch or the native lock.
                        snapshotFlow { renderWork }.first { it !== upgrading }
                        continue
                    }
                    val base = pageImages.getValue(index)
                    val context = base.themeContext
                    val budget = pageDetailBudget(detailRequests.size)
                    val region = pageDetailRegion(wanted, detailRequests.size)
                    var result: Bitmap? = null
                    var superseded = false
                    try {
                        withContext(Dispatchers.IO) {
                            renderLock.withLock {
                                signal.throwIfCanceled()
                                if (pageImages[index] !== base || renderWork?.let { it.page == index && !it.preview } == true) {
                                    superseded = true
                                    return@withLock
                                }
                                result = owner.session.renderRegionWithContext(index, region, appearance, signal, context)
                                result?.let { image ->
                                    check(image.width == region.width && image.height == region.height)
                                    check(image.allocationByteCount.toLong() <= budget)
                                    signal.throwIfCanceled()
                                    image.prepareToDraw()
                                }
                            }
                        }
                        signal.throwIfCanceled()
                        if (superseded) continue
                        // An upgrade begun after native work starts still cannot publish an old-policy patch.
                        if (identity == documentId && generation == detailGeneration &&
                            detailRequests[index] == wanted && pageImages[index] === base) {
                            attemptedDetails[index] = wanted
                            result?.let { image ->
                                pageDetails = pageDetails.filterKeys { it in detailRequests } + (index to ReaderPageDetail(image, region))
                                result = null // Compose may retain the preceding image through a frame.
                            }
                        }
                    } catch (failure: Exception) {
                        if (cancelled(failure, signal)) throw failure
                        if (identity == documentId && generation == detailGeneration && pageImages[index] === base)
                            attemptedDetails[index] = wanted
                    } catch (_: OutOfMemoryError) {
                        if (identity == documentId && generation == detailGeneration && pageImages[index] === base)
                            attemptedDetails[index] = wanted
                        break // The base page remains usable; do not retry this patch every frame.
                    } finally { result?.recycle() }
                }
            } finally { owner.release() }
        }
    }

    fun retryPage(index: Int, identity: Long = documentId) {
        if (identity != documentId || index !in failedPages) return
        failedPages = failedPages - index
        attemptedPages.remove(index)
        syncPage()
        startPageRender()
    }

    private fun pageOrder(): List<Int> {
        val count = info?.pageCount ?: return emptyList()
        val adjacent = if (viewport.zoom <= 1f) listOf(visiblePages.last + 1, visiblePages.first - 1) else emptyList()
        return (listOf(page) + visiblePages + adjacent)
            .asSequence().filter { it in 0 until count }.distinct().take(256).toList()
    }

    private fun pageBudget(count: Int) = 48L * 1024 * 1024 / count.coerceAtLeast(1)
    // Half-step tiers avoid restarting native work for every pinch delta. Zooming out reuses detail.
    private fun renderScale(pose: ViewportState) = kotlin.math.ceil(pose.zoom.coerceAtLeast(1f) * 2f) / 2f
    private fun renderWidth(aspect: Float, budget: Long, baseWidth: Int = renderWidth,
        pose: ViewportState = viewport, crop: RectF? = null) =
        pageRenderWidth(baseWidth, renderScale(pose), aspect, pose.rotation % 2 != 0, budget,
            crop?.width() ?: 1f, crop?.height() ?: 1f)

    private fun needsPage(index: Int, budget: Long): Boolean {
        if (index in failedPages) return false
        val image = pageImages[index] ?: return true
        if (image.missingThemeContext && index in detailRequests) return true
        val bytes = image.bitmap.allocationByteCount
        return bytes > budget || bytes < budget * .7 &&
            image.renderWidth < renderWidth(image.bitmap.height.toFloat() / image.bitmap.width, budget, crop = image.crop) * .8
    }

    private fun syncPage() {
        bitmap = pageImages[page]?.bitmap
        pageLoading = foreground && readerVisible && !sheetMode && !textMode && info?.continuous != true && renderWidth > 0 && info != null && bitmap == null && page !in failedPages
    }

    private fun startPageRender() {
        startPageDetails()
        if (info?.supportsSheets == true && info?.pageCount == 0 && horizontalPages && !textMode) {
            if (foreground && readerVisible) choosePageView(true)
            return
        }
        if (sheetMode) { pageLoading = false; startSheetRender(); return }
        if (sheetResident || sheetRendering.running) pauseSheets()
        val order = pageOrder()
        if (pageImages.keys.any { it !in order }) pageImages = pageImages.filterKeys { it in order }
        syncPage()
        val budget = pageBudget(order.size)
        val pendingPage = order.firstOrNull { needsPage(it, budget) && it !in attemptedPages }
        renderWork?.let { work ->
            if (work.page !in order || pendingPage != null &&
                (pendingPage == page || pendingPage in visiblePages) &&
                (work.preview || work.page != page && work.page !in visiblePages)) {
                rendering.cancel()
                attemptedPages.remove(work.page)
            }
        }
        if (rendering.running || zoomAnimationStart != null || exporting || !foreground || !readerVisible || textMode || info?.continuous == true || renderWidth == 0) return
        if (pendingPage == null && (previewSaved || documentHash == null || pageImages[page] == null)) return
        val owner = current?.borrow() ?: return
        val version = documentId
        val generation = renderGeneration
        val appearance = effectiveSettings
        val cropMargins = hideMargins
        val baseWidth = renderWidth
        val pose = viewport
        syncPage()
        rendering.start { signal ->
            try {
                while (version == documentId && generation == renderGeneration && foreground && readerVisible && !textMode && !exporting && zoomAnimationStart == null) {
                    val wanted = pageOrder()
                    val budget = pageBudget(wanted.size)
                    val next = wanted.firstOrNull { needsPage(it, budget) && it !in attemptedPages }
                    // Finish visible pages first; save their cover before adjacent prefetch can evict them.
                    val previewHash = documentHash.takeIf { !previewSaved && pageImages[page] != null &&
                        (next == null || next != page && next !in visiblePages) }
                    if (previewHash != null) {
                        val index = page
                        renderWork = RenderWork(index, signal, preview = true)
                        try {
                            withContext(Dispatchers.IO) {
                                renderLock.withLock {
                                    val preview = DocumentPreviews.nativePage(owner.session, index, signal)
                                    try { previews.savePage(previewHash, preview, android.graphics.Color.TRANSPARENT, owner.session.rasterRegions(index, signal)) }
                                    finally { preview.recycle() }
                                }
                            }
                        } catch (_: OutOfMemoryError) { /* Thumbnail is optional. */ }
                        catch (failure: Exception) { if (cancelled(failure, signal)) throw failure }
                        signal.throwIfCanceled()
                        if (version == documentId && generation == renderGeneration) previewSaved = true
                        if (renderWork?.signal === signal) renderWork = null
                        continue
                    }
                    val index = next ?: break
                    attemptedPages.add(index)
                    renderWork = RenderWork(index, signal)
                    var result: Bitmap? = null
                    var themeContext: RegionThemeContext? = null
                    val previousImage = pageImages[index]
                    var crop = if (cropMargins) previousImage?.crop else null
                    val discoverCrop = cropMargins && previousImage == null
                    var requestedWidth = 1
                    try {
                        val aspect = withContext(Dispatchers.IO) {
                            renderLock.withLock { owner.session.pageAspect(index, signal) }
                        }
                        signal.throwIfCanceled()
                        if (generation != renderGeneration || version != documentId) break
                        if (!cropMargins || index !in pageAspects) rememberAspect(index, aspect)
                        withContext(Dispatchers.IO) {
                            renderLock.withLock {
                                fun original(width: Int): Bitmap {
                                    signal.throwIfCanceled()
                                    requestedWidth = width
                                    val image = owner.session.renderPage(index, width, ThemeSettings(ReadingMode.ORIGINAL), signal)
                                    try {
                                        if (image.allocationByteCount <= budget) return image
                                        val scale = kotlin.math.sqrt(budget.toDouble() / image.allocationByteCount) * .99
                                        val reduced = Bitmap.createScaledBitmap(image,
                                            (image.width * scale).toInt().coerceAtLeast(1),
                                            (image.height * scale).toInt().coerceAtLeast(1), true)
                                        if (reduced !== image) image.recycle()
                                        requestedWidth = (requestedWidth * scale).toInt().coerceAtLeast(1)
                                        return reduced
                                    } catch (failure: Throwable) { image.recycle(); throw failure }
                                }
                                val target = renderWidth(aspect, budget, baseWidth, pose, crop)
                                var image = original(if (discoverCrop) pageMarginProbeWidth(target, aspect) else target)
                                try {
                                    if (discoverCrop) {
                                        crop = detectPageMargins(image, signal)
                                        val detail = renderWidth(aspect, budget, baseWidth, pose, crop)
                                        if (requestedWidth < detail * .8) {
                                            // One upgrade, before theming; keep the normalized probe crop at every detail tier.
                                            image.recycle()
                                            image = original(detail)
                                        }
                                    }
                                } catch (failure: Throwable) { image.recycle(); throw failure }
                                result = try { owner.session.themePage(index, image, appearance, signal) }
                                catch (failure: Throwable) { if (!image.isRecycled) image.recycle(); throw failure }
                                themeContext = owner.session.pageThemeContext(index, appearance, signal)
                            }
                            signal.throwIfCanceled()
                            requireNotNull(result).prepareToDraw()
                        }
                        signal.throwIfCanceled()
                        if (version == documentId && generation == renderGeneration && index in pageOrder()) {
                            val image = requireNotNull(result)
                            val bounds = crop
                            rememberAspect(index, image.height.toFloat() / image.width * (bounds?.height() ?: 1f) / (bounds?.width() ?: 1f))
                            val candidates = pageImages + (index to ReaderPageImage(image, crop, requestedWidth, themeContext))
                            val contextPages = (detailRequests.keys + pageOrder()).distinct().take(2)
                            if (previousImage?.themeContext !== themeContext) {
                                pageDetails = pageDetails - index
                                attemptedDetails.remove(index)
                            }
                            var bytes = 0L
                            val retained = LinkedHashMap<Int, ReaderPageImage>()
                            for (key in pageOrder()) candidates[key]?.let { candidate ->
                                val size = candidate.bitmap.allocationByteCount.toLong()
                                if (retained.isEmpty() || bytes + size <= 48L * 1024 * 1024) {
                                    retained[key] = if (key in contextPages) candidate else candidate.copy(themeContext = null)
                                    bytes += size
                                }
                            }
                            if (index in retained) result = null // Published images are released by GC after Compose drops them.
                            pageImages = retained
                            startPageDetails()
                            syncPage()
                            discoverMetadata()
                        }
                    } catch (_: OutOfMemoryError) {
                        if (!signal.isCanceled && version == documentId && generation == renderGeneration) {
                            failedPages = failedPages + index
                            if (index == page) error = "Not enough memory."
                            syncPage()
                        }
                        break
                    } catch (failure: Exception) {
                        if (cancelled(failure, signal)) throw failure
                        failedPages = failedPages + index
                        if (index == page) error = message(failure)
                        syncPage()
                    } finally {
                        result?.recycle()
                        if (renderWork?.signal === signal) renderWork = null
                    }
                }
            } finally {
                if (renderWork?.signal === signal) renderWork = null
                owner.release()
                startPageDetails()
            }
        }
    }

    fun updateSheetPosition(value: SheetPosition, identity: Long = documentId) {
        if (identity != documentId || info?.supportsSheets != true) return
        if (workbook?.sheets?.none { it.index == value.sheet } == true) return
        val next = value.normalized()
        val geometry = sheetGeometry?.takeIf { it.sheet.index == next.sheet }
        sheetPosition = if (geometry == null) next else next.copy(
            x = next.x.coerceAtMost(geometry.sheet.width), y = next.y.coerceAtMost(geometry.sheet.height))
    }

    fun selectSheet(index: Int, identity: Long = documentId) {
        if (identity != documentId || workbook?.sheets?.none { it.index == index } != false || sheetPosition.sheet == index) return
        if (sheetCovering) pauseSheets()
        sheetPosition = SheetPosition(index)
        sheetGeometry = null
        sheetFrame = null
        sheetRequest = null
        pendingSheet = null
        renderedSheet = null
        sheetsError = null
        startSheetRender()
        flushDocumentState()
    }

    fun updateSheetViewport(size: IntSize, density: Float, identity: Long = documentId) {
        if (identity != documentId || info?.supportsSheets != true) return
        sheetVisibleSize = size
        sheetDensity = density
    }

    fun requestSheetViewport(value: SheetViewport, identity: Long = documentId) {
        if (identity != documentId || !sheetMode || value.sheet != sheetPosition.sheet) return
        sheetRequest = value.checked()
        if (sheetCovering) pauseSheets() // Optional thumbnail work never delays a new visible viewport.
        startSheetRender()
    }

    fun retrySheet(identity: Long = documentId) {
        if (identity != documentId || !sheetMode) return
        pauseSheets()
        sheetsError = null
        renderedSheet = null
        startSheetRender()
    }

    /** One active render and the newest pending viewport; panning never restarts a busy worker. */
    private fun startSheetRender() {
        if (!sheetMode || !foreground || !readerVisible || exporting || preparingPrint || sheetsError != null) return
        sheetRequest?.let { request ->
            val work = SheetWork(request, effectiveSettings)
            pendingSheet = work.takeIf { it != renderedSheet }
        }
        if (sheetRendering.running || workbook != null && sheetGeometry?.sheet?.index == sheetPosition.sheet && pendingSheet == null) return
        val owner = current?.borrow() ?: return
        val version = documentId
        val generation = sheetGeneration
        sheetResident = true
        sheetsLoading = true
        sheetRendering.start { signal ->
            try {
                sheetPause?.await()
                while (version == documentId && generation == sheetGeneration && sheetMode && foreground && readerVisible && !exporting && !preparingPrint) {
                    if (workbook == null) {
                        val value = withContext(Dispatchers.IO) { owner.session.workbook(signal) }
                            ?: throw java.io.IOException("Worksheet view unavailable")
                        signal.throwIfCanceled()
                        if (version != documentId || generation != sheetGeneration) break
                        workbook = value
                        if (value.sheets.none { it.index == sheetPosition.sheet }) sheetPosition = SheetPosition(value.activeSheet)
                        info = info?.let { it.copy(warnings = (it.warnings + value.warnings).distinct()) }
                    }
                    val selected = sheetPosition.sheet
                    if (sheetGeometry?.sheet?.index != selected) {
                        val geometry = try {
                            withContext(Dispatchers.IO) { owner.session.sheetGeometry(selected, signal) }
                                ?: throw java.io.IOException("Worksheet geometry unavailable")
                        } catch (failure: Exception) {
                            if (!cancelled(failure, signal) && selected != sheetPosition.sheet) continue
                            throw failure
                        }
                        signal.throwIfCanceled()
                        if (version != documentId || generation != sheetGeneration) break
                        if (selected != sheetPosition.sheet) continue
                        check(geometry.sheet.index == selected) { "Worksheet changed" }
                        sheetGeometry = geometry
                        updateSheetPosition(sheetPosition, version)
                    }
                    if (pendingSheet == null) {
                        val hash = documentHash
                        val coverViewport = sheetFrame?.viewport
                        if (!previewSaved && hash != null && coverViewport != null) {
                            sheetsLoading = false
                            delay(sheetCoverDelay) // Defer optional covers until visible pan/zoom work settles.
                            if (pendingSheet != null || selected != sheetPosition.sheet) continue
                            previewSaved = true
                            sheetCovering = true
                            try {
                                withContext(Dispatchers.IO) {
                                    val cover = DocumentPreviews.nativeSheet(owner.session, signal, viewport = coverViewport)
                                    try { previews.savePage(hash, cover.image, android.graphics.Color.TRANSPARENT, cover.regions) }
                                    finally { cover.image.recycle() }
                                }
                            } catch (_: OutOfMemoryError) { /* The visible grid remains usable without a cover. */ }
                            catch (failure: Exception) { if (cancelled(failure, signal)) throw failure }
                            finally { if (version == documentId && generation == sheetGeneration) sheetCovering = false }
                            continue
                        }
                        break
                    }
                    val work = requireNotNull(pendingSheet)
                    pendingSheet = null
                    sheetsLoading = true
                    if (work == renderedSheet || work.viewport.sheet != selected) continue
                    var image: Bitmap? = null
                    try {
                        withContext(Dispatchers.IO) { image = owner.session.renderSheet(work.viewport, work.appearance, signal) }
                        signal.throwIfCanceled()
                        if (version == documentId && generation == sheetGeneration && sheetMode && foreground && readerVisible &&
                            selected == sheetPosition.sheet && work.appearance == effectiveSettings &&
                            (renderedSheet?.viewport != sheetRequest || renderedSheet?.appearance != work.appearance)) {
                            sheetFrame = SheetFrame(requireNotNull(image), work.viewport)
                            image = null // Compose may still draw an older published frame; GC owns those images.
                            renderedSheet = work
                            discoverMetadata()
                        }
                    } catch (failure: Exception) {
                        if (!cancelled(failure, signal) && (selected != sheetPosition.sheet ||
                            work.viewport != sheetRequest || work.appearance != effectiveSettings)) continue
                        throw failure
                    } finally { image?.recycle() }
                }
            } catch (failure: Exception) {
                if (!cancelled(failure, signal) && version == documentId && generation == sheetGeneration)
                    sheetsError = message(failure)
            } finally {
                if (version == documentId && generation == sheetGeneration) sheetsLoading = false
                owner.release()
            }
        }
    }

    /** Cancellation is immediate; child shutdown and session monitors are worker-only. */
    private fun pauseSheets() {
        sheetGeneration++
        sheetRendering.cancel()
        printLayout.cancel()
        preparingPrint = false
        pendingSheet = null
        if (sheetCovering) { previewSaved = false; sheetCoverDelay = 1_000 }
        sheetCovering = false
        sheetsLoading = false
        if (!sheetResident) return
        sheetResident = false
        val owner = current?.borrow() ?: return
        val done = CompletableDeferred<Unit>()
        sheetPause = done
        CloseWorker.execute {
            try { runCatching { owner.session.pauseSheets() } }
            finally { owner.release(); done.complete(Unit) }
        }
    }

    private fun rememberAspect(index: Int, value: Float) {
        if (!value.isFinite() || value <= 0f || pageAspects[index] == value) return
        val next = LinkedHashMap(pageAspects)
        next.remove(index)
        next[index] = value
        if (next.size > 256) {
            val visible = pageOrder().toSet()
            next.remove(next.keys.first { it !in visible })
        }
        pageAspects = next
    }

    private fun stopTextExtraction() {
        textGeneration++
        extracting.cancel()
        textLoading = false
    }

    fun loadText(identity: Long = documentId) {
        if (identity != documentId || !foreground || !readerVisible || text != null || textLoading ||
            info?.let { it.textAvailable || it.reflowable } != true) return
        val owner = current?.borrow() ?: return
        val version = documentId
        val generation = ++textGeneration
        pauseMetadata()
        textLoading = true
        extracting.start { signal ->
            try {
                val (result, rich) = withContext(Dispatchers.IO) {
                    val rich = owner.session.markdown(signal)
                    (rich?.plainText ?: owner.session.bodyText(signal)) to rich
                }
                signal.throwIfCanceled()
                if (version == documentId && generation == textGeneration && foreground && readerVisible) {
                    markdown = rich
                    text = result
                    textLoading = false // Optional cover work must not hold the readable flow behind a loader.
                    val previewHash = documentHash
                    if (rich != null && previewHash != null) {
                        try {
                            withContext(Dispatchers.IO) {
                                renderLock.withLock {
                                    val preview = DocumentPreviews.nativePage(owner.session, 0, signal)
                                    try { previews.savePage(previewHash, preview, android.graphics.Color.TRANSPARENT, owner.session.rasterRegions(0, signal)) }
                                    finally { preview.recycle() }
                                }
                            }
                        } catch (_: OutOfMemoryError) { /* Long documents can keep the format-icon fallback. */ }
                        catch (failure: Exception) { if (cancelled(failure, signal)) throw failure }
                    } else {
                        try { previewHash?.let { previews.saveText(it, result) } }
                        catch (_: OutOfMemoryError) { /* The readable text is already available. */ }
                    }
                }
            } catch (failure: Exception) {
                if (!cancelled(failure, signal) && version == documentId && generation == textGeneration &&
                    foreground && readerVisible) error = message(failure)
            } finally {
                if (version == documentId && generation == textGeneration) {
                    textLoading = false
                    if (text != null || bitmap != null) discoverMetadata()
                }
                owner.release()
            }
        }
    }

    enum class ExportKind(val mime: String, val extension: String) {
        TEXT("text/plain", "txt"), JSON("application/json", "json"), IMAGE("image/png", "png")
    }

    fun prepareExport(kind: ExportKind, identity: Long = documentId): String? {
        if (identity != documentId || !canExport) return null
        val sheet = if (kind == ExportKind.IMAGE && sheetMode) {
            val visible = if (sheetGeometry?.sheet?.index == sheetPosition.sheet)
                worksheetVisibleViewport(sheetPosition, sheetVisibleSize, sheetDensity.toDouble()) else null
            visible ?: run { notice = "Worksheet is loading."; return null }
        } else null
        val owner = current?.borrow() ?: return null
        pendingExport = Export(owner, kind, page, effectiveSettings, currentTitle.ifBlank { "document" }.substringBeforeLast('.'), sheet)
        return pendingExport!!.name + when {
            sheet != null -> "-sheet-${sheet.sheet + 1}.png"
            kind == ExportKind.IMAGE -> "-page-${page + 1}.png"
            else -> ".${kind.extension}"
        }
    }

    fun discardPendingExport() {
        pendingExport?.owner?.release()
        pendingExport = null
    }

    fun export(destination: Uri?) {
        val request = pendingExport ?: run {
            if (destination != null) error = "Export unavailable. Choose the document again."
            return
        }
        pendingExport = null
        if (destination == null) { request.owner.release(); return }
        notice = null
        exporting = true
        pausePageDetails()
        pauseSheets()
        // Explicit export owns the session before optional covers or adjacent-page work.
        renderWork?.let { attemptedPages.remove(it.page) }
        rendering.cancel()
        // Once the rich flow is published, its extraction task only owns an optional cover.
        if (markdown != null && text != null) stopTextExtraction()
        pauseMetadata()
        saving.start { signal ->
            try {
                sheetPause?.await()
                withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    resolver.openFileDescriptor(destination, "wt", signal)?.use { descriptor ->
                        val output = DescriptorOutput(descriptor, signal)
                        when (request.kind) {
                            ExportKind.TEXT, ExportKind.JSON -> {
                                val text = if (request.kind == ExportKind.TEXT) request.owner.session.text(signal)
                                    else request.owner.session.json(signal)
                                output.bufferedWriter(Charsets.UTF_8).use { it.write(text) }
                            }
                            ExportKind.IMAGE -> {
                                val image = request.sheet?.let { request.owner.session.renderSheet(it, request.settings, signal) }
                                    ?: request.owner.session.renderPage(request.page, 1600, request.settings, signal)
                                try {
                                    signal.throwIfCanceled()
                                    check(image.compress(Bitmap.CompressFormat.PNG, 100, output)) { "Could not encode page." }
                                } finally { image.recycle() }
                            }
                        }
                        signal.throwIfCanceled()
                        output.flush()
                        descriptor.checkError()
                    } ?: error("Could not open the selected destination.")
                }
                notice = "Export saved."
            } catch (failure: Exception) {
                if (!cancelled(failure, signal)) error = message(failure)
                else notice = "Export cancelled; the destination may be incomplete."
            } finally {
                exporting = false
                if (request.sheet != null && request.owner === current) {
                    sheetResident = true
                    if (!foreground || !readerVisible || !sheetMode) pauseSheets()
                }
                request.owner.release()
                if (text != null || bitmap != null) discoverMetadata()
                startPageRender()
            }
        }
    }

    fun cancelExport() { saving.cancel() }
    fun retryOpening(identity: Long = documentId, expectedMessage: String? = error) {
        if (identity != documentId || expectedMessage == null || error != expectedMessage) return
        passwordRetry?.let { retry(it) } ?: source?.let { open(it, mimeHint = sourceMimeHint) }
    }
    fun showError(message: String) { error = message }
    fun dismissError(identity: Long = documentId, expectedMessage: String? = error) {
        if (identity == documentId && error == expectedMessage) error = null
    }
    fun dismissNotice(expectedMessage: String? = notice) { if (notice == expectedMessage) notice = null }

    private fun cancelWork() {
        pauseSheets()
        opening.cancel()
        rendering.cancel()
        pausePageDetails(clear = true)
        stopTextExtraction()
        outlining.cancel()
        annotating.cancel()
        attemptedPages.clear()
        notesLoading = false
        contentsLoading = false
        pageLoading = false
        textLoading = false
    }

    override fun onCleared() {
        stopRecentCheck()
        library.close()
        previews.close()
        recentPreviews = emptyMap()
        passwordRetry = null
        flushDocumentState()
        cancelWork()
        saving.cancel()
        discardPendingExport()
        current?.retire()
        current = null
    }

    private class Lease(val session: DocumentSession) {
        private var references = 1
        @Synchronized fun borrow(): Lease? {
            if (references == 0) return null
            references++
            return this
        }
        fun release() {
            val close = synchronized(this) { --references == 0 }
            if (close) {
                // Engines can own native renderers; closing must not block the UI thread.
                CloseWorker.execute { runCatching { session.close() } }
            }
        }
        fun retire() = release()
    }
    private class Export(val owner: Lease, val kind: ExportKind, val page: Int,
        val settings: ThemeSettings, val name: String, val sheet: SheetViewport?)
    private inner class Task(private val settleFailure: () -> Unit) {
        private var job: Job? = null
        private var signal: CancellationSignal? = null
        val running get() = job?.isActive == true
        fun start(block: suspend (CancellationSignal) -> Unit) {
            cancel()
            val next = CancellationSignal()
            signal = next
            val identity = documentId
            job = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
                try { block(next) }
                catch (failure: Exception) {
                    if (failure !is CancellationException && (next.isCanceled || !isActive))
                        throw CancellationException("Document work cancelled").apply { initCause(failure) }
                    throw failure
                }
                catch (_: OutOfMemoryError) {
                    if (isActive && signal === next && !next.isCanceled && identity == documentId) {
                        settleFailure()
                        error = "Not enough memory."
                    }
                }
            }
        }
        fun cancel() {
            signal?.cancel()
            job?.cancel()
            signal = null
            job = null
        }
    }
    companion object {
        // Ordered across model lifetimes so rapid changes cannot persist an older selection last.
        private val StateWorker = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
            Thread(task, "alldoc-state").apply { isDaemon = true }
        }
        private val StateDispatcher = StateWorker.asCoroutineDispatcher()
        private val PendingWrites = LinkedHashMap<String, () -> Unit>()
        private var writeScheduled = false
        internal suspend fun <T> orderedStateRead(block: () -> T): T = withContext(StateDispatcher) {
            drainStateWrites()
            block()
        }
        internal fun enqueueStateWrite(key: String, action: () -> Unit) {
            synchronized(PendingWrites) {
                PendingWrites[key] = action
                if (!writeScheduled) {
                    writeScheduled = true
                    StateWorker.execute { drainStateWrites() }
                }
            }
        }
        // StateWorker is the only consumer; reads use this barrier before loading a hash.
        private fun drainStateWrites() {
            val batch = synchronized(PendingWrites) {
                PendingWrites.values.toList().also {
                    PendingWrites.clear()
                    writeScheduled = false
                }
            }
            batch.forEach { it() }
        }
        // One bounded SQLite connection per app process, accessed only on StateWorker.
        private var SharedStore: DocumentStore? = null
        private var SharedSources: DocumentSources? = null
        @Synchronized private fun sharedSources(application: Application): DocumentSources =
            SharedSources ?: DocumentSources(application).also { SharedSources = it }
        private val CloseWorker = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
            Thread(task, "alldoc-close").apply { isDaemon = true }
        }
        private fun cancelled(failure: Exception, signal: CancellationSignal) =
            signal.isCanceled || failure is CancellationException
        private fun message(failure: Exception) = failure.message?.takeIf { it.isNotBlank() }?.takeScalarSafe(500)
            ?: "Could not read this document."
    }
}
