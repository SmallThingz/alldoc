package app.smallthingz.alldoc.ui

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.CancellationSignal
import android.os.SystemClock
import android.util.Log
import app.smallthingz.alldoc.core.takeScalarSafe
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Slideshow
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import app.smallthingz.alldoc.sources.LibraryInput
import app.smallthingz.alldoc.sources.LibraryIndex
import app.smallthingz.alldoc.sources.PreparedDocument
import app.smallthingz.alldoc.state.LibraryCatalog
import app.smallthingz.alldoc.state.LibraryDocument
import app.smallthingz.alldoc.state.LibraryFile
import app.smallthingz.alldoc.state.LibraryCategory
import app.smallthingz.alldoc.state.LibraryQuery
import app.smallthingz.alldoc.state.StoredDocument
import app.smallthingz.alldoc.theme.ReadingMode
import app.smallthingz.alldoc.theme.ThemeSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Composable
internal fun LibraryFormatFilters(hiddenTypes: Set<LibraryCategory>, onChange: (Set<LibraryCategory>) -> Unit,
    enabled: Boolean = true,
) {
    SettingsChoices(LibraryCategory.available, { it !in hiddenTypes }, { type ->
        onChange(if (type in hiddenTypes) hiddenTypes - type else hiddenTypes + type)
    }, label = { when (it) {
        LibraryCategory.DOCUMENTS -> "Docs"; LibraryCategory.PRESENTATIONS -> "Slides"
        LibraryCategory.SPREADSHEETS -> "Sheets"; else -> "Books"
    } }, icon = { when (it) {
        LibraryCategory.DOCUMENTS -> Icons.Outlined.Description; LibraryCategory.PRESENTATIONS -> Icons.Outlined.Slideshow
        LibraryCategory.SPREADSHEETS -> Icons.Outlined.TableChart; else -> Icons.Outlined.MenuBook
    } }, description = { "Library: ${it.label}" }, multiple = true, enabled = enabled)
}

/** A foreground, finite catalog scan, independent of reading history. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class ReaderLibrary(private val application: Application, parent: CoroutineScope,
    private val onError: (String) -> Unit) {
    private val owner = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + owner)
    private val catalog = LibraryCatalog(application)
    @Volatile private var pageSource: LibraryPaging? = null
    private val filter = MutableStateFlow(LibraryQuery())
    private val settingsLoaded = MutableStateFlow(false)
    val settingsReady: StateFlow<Boolean> get() = settingsLoaded
    private var queryGeneration = 0L
    private var settingsRevision = 0L
    @Volatile private var persistedHiddenTypes = emptySet<LibraryCategory>()
    var hiddenTypes by mutableStateOf<Set<LibraryCategory>>(emptySet())
        private set
    val pages = combine(filter, settingsLoaded) { query, ready -> query.takeIf { ready } }.filterNotNull().flatMapLatest { query ->
        val generation = (++queryGeneration).toString()
        Pager(PagingConfig(pageSize = 48, initialLoadSize = 48, prefetchDistance = 8,
            maxSize = 144, enablePlaceholders = false)) {
            LibraryPaging(catalog, query).also { pageSource = it }
        }.flow.map { it.groupedLibrary(generation) }
    }.cachedIn(scope)
    var previews by mutableStateOf<Map<Long, Bitmap>>(emptyMap())
        private set
    var scanning by mutableStateOf(false)
        private set
    private var active = false
    private var foreground = true
    private var dirty = true
    @Volatile private var closed = false
    private var scan: Job? = null
    private var scanUpdates: LibraryScanUpdates? = null
    private var signal: CancellationSignal? = null
    private var scanGeneration = 0L
    private var visible = emptyMap<Long, LibraryDocument>()
    private var appearance = ThemeSettings(ReadingMode.ORIGINAL)
    private val covers = LibraryPreviews(application,
        onSourceChanged = { document -> updateCatalog { catalog.invalidate(document) } },
        onSourceMissing = { document -> updateCatalog { catalog.removeLocation(document) } }) { document, image, generation ->
        scope.launch {
            try {
                if (!canPublish(document, generation)) return@launch
                val previous = visible[document.id] ?: return@launch
                val canonical = withContext(Dispatchers.IO) { catalog.resolve(document, requireNotNull(document.hash)) }
                    ?: return@launch
                if (previous.hash != canonical.hash || canonical.id != document.id ||
                    canonical.revision != document.revision) invalidatePages()
                if (!canPublish(document, generation)) return@launch
                if (canonical.id == document.id && canonical.revision == document.revision) {
                    previews = previews + (document.id to image)
                }
            } catch (_: Exception) { /* Covers must never prevent reading. */ }
        } // The shared preview cache and Compose may both retain this immutable bitmap.
    }

    init {
        val revision = settingsRevision
        scope.launch {
            try {
                val saved = ReaderModel.orderedStateRead { LibraryCatalog(application).use { it.hiddenTypes().also { value -> persistedHiddenTypes = value } } }
                if (revision == settingsRevision) {
                    hiddenTypes = saved
                    filter.value = filter.value.copy(excluded = saved)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { onError("Library settings couldn’t be loaded.") }
            finally { settingsLoaded.value = true }
        }
    }

    fun setFilter(text: String, category: LibraryCategory?) {
        filter.value = filter.value.copy(text = text.takeScalarSafe(128).trim(), category = category)
    }

    fun updateHiddenTypes(value: Set<LibraryCategory>) {
        if (closed || settingsLoaded.value && hiddenTypes == value) return
        val revision = ++settingsRevision
        val requested = value.toSet()
        hiddenTypes = requested
        filter.value = filter.value.copy(excluded = requested)
        // Accepted settings survive model teardown and coalesce with newer selections.
        ReaderModel.enqueueStateWrite("library-hidden-types") {
            var restored = persistedHiddenTypes
            val saved = try {
                LibraryCatalog(application).use { store ->
                    restored = store.hiddenTypes()
                    persistedHiddenTypes = restored
                    store.setHiddenTypes(requested)
                    persistedHiddenTypes = requested
                }
                true
            } catch (failure: Exception) {
                Log.e("AllDoc", "Could not save library settings", failure)
                restored = persistedHiddenTypes
                false
            }
            scope.launch {
                if (closed || revision != settingsRevision) return@launch
                if (saved) refresh()
                else {
                    hiddenTypes = restored
                    filter.value = filter.value.copy(excluded = restored)
                    onError("Library settings couldn’t be saved.")
                }
            }
        }
    }

    private fun updateCatalog(action: () -> Boolean) {
        scope.launch {
            try { if (withContext(Dispatchers.IO) { action() }) invalidatePages() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Optional cover failure; opening still validates the source. */ }
        }
    }

    private fun canPublish(document: LibraryDocument, generation: Long): Boolean = !closed && active && foreground &&
        visible[document.id]?.revision == document.revision && covers.isCurrent(document.id, document.revision, generation)

    fun setVisible(documents: List<LibraryDocument>) {
        val next = documents.associateBy { it.id }
        // An outgoing pager page still draws its covers after its demand window becomes empty.
        // Keep that one published window; workers still receive the empty request below.
        if (next.isNotEmpty()) previews = buildMap {
            for ((id, document) in next) {
                val previous = visible[id]
                val retained = previews[id]?.takeIf { previous != null && previous.revision == document.revision &&
                    (previous.hash == null || previous.hash == document.hash) && !it.isRecycled }
                (retained ?: covers.cached(document))?.let { put(id, it) }
            }
        }
        visible = next
        covers.setVisible(documents, active && foreground)
    }

    fun setAppearance(value: ThemeSettings) {
        if (appearance == value) return
        appearance = value
        covers.setAppearance(value)
        previews = buildMap { visible.forEach { (id, document) -> covers.cached(document)?.let { put(id, it) } } }
    }

    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        updateWork()
    }

    fun setForeground(value: Boolean) {
        if (foreground == value) return
        foreground = value
        updateWork()
    }

    private fun updateWork() {
        covers.setVisible(visible.values.toList(), active && foreground)
        if (active && foreground) { if (dirty && scan?.isActive != true) startScan() }
        else stopScan()
    }

    fun refresh() {
        dirty = true
        if (active && foreground) startScan()
    }

    fun addFolder(uri: Uri) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    application.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    catalog.addRoot(uri.toString())
                }
                refresh()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { onError("Couldn’t access that folder.") }
        }
    }

    private fun stopScan() {
        scanGeneration++
        signal?.cancel()
        scanUpdates?.finish()
        scanUpdates = null
        scan?.cancel()
        scanning = false
    }

    private fun startScan() {
        stopScan()
        val generation = scanGeneration
        val cancellation = CancellationSignal().also { signal = it }
        scanning = true
        val updates = LibraryScanUpdates(scope) { if (!closed) invalidatePages() }
        scanUpdates = updates
        scan = scope.launch {
            try {
                var notifiedChanges = 0L
                val result = scanGate.withLock { withContext(Dispatchers.IO) {
                    LibraryIndex(application, catalog).scan(cancellation) { progress ->
                        if (progress.changes > notifiedChanges) {
                            notifiedChanges = progress.changes
                            updates.changed()
                        }
                    }
                } }
                if (generation == scanGeneration) {
                    dirty = false
                    if (!result.complete) onError("Some folders couldn’t be scanned. Refresh to retry.")
                }
                if (result.changes > notifiedChanges) updates.changed()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (!cancellation.isCanceled && generation == scanGeneration) {
                    dirty = false
                    onError("Couldn’t finish scanning. Try refreshing the Library.")
                }
            } finally {
                updates.finish()
                if (scanUpdates === updates) scanUpdates = null
                if (generation == scanGeneration) { scanning = false; signal = null }
            }
        }
    }

    /** Worker-only catalog mutations; incoming snapshots have already been hashed by DocumentSources. */
    fun record(prepared: PreparedDocument) {
        if (closed) return
        for (uri in listOfNotNull(prepared.sourceUri, prepared.importUri.takeIf { prepared.importVerified }).distinct()) {
            val location = uri.toString()
            val previous = catalog.location(location)
            val discovered = catalog.discover(LibraryFile(location, prepared.displayName, prepared.session.info.mimeType,
                prepared.sizeBytes, previous?.modifiedAt ?: 0)) ?: continue
            catalog.resolve(discovered, prepared.hash)
        }
        invalidatePages()
    }

    fun seed(recent: List<StoredDocument>) {
        if (closed) return
        for (saved in recent) for (uri in listOfNotNull(saved.metadata.sourceUri)) {
            val previous = catalog.location(uri)
            if (LibraryInput.isMissingPrivateFile(application, Uri.parse(uri))) {
                if (previous != null) catalog.removeLocation(previous)
            } else if (previous == null) catalog.discover(LibraryFile(uri, saved.title,
                saved.metadata.mimeType, saved.metadata.sizeBytes, 0))
        }
        invalidatePages()
    }

    fun invalidate(document: LibraryDocument) {
        if (catalog.invalidate(document)) invalidatePages()
    }

    fun removeLocation(uri: String, hash: String) {
        check(!closed) { "Library cleanup is deferred until the next open." }
        if (catalog.removeLocation(uri, hash)) invalidatePages()
    }

    private fun invalidatePages() { pageSource?.invalidate() }

    private companion object { val scanGate = Mutex() }

    fun close() {
        closed = true
        stopScan()
        covers.close()
        previews = emptyMap()
        owner.cancel()
        CoroutineScope(Dispatchers.IO).launch { owner.join(); runCatching { catalog.close() } }
    }
}


/** Coalesce committed catalog changes, with one trailing refresh and no idle work. */
internal class LibraryScanUpdates(private val scope: CoroutineScope,
    private val elapsed: () -> Long = SystemClock::elapsedRealtime, private val invalidate: () -> Unit) {
    private var lastUpdate = -250L
    private var pending: Job? = null
    private var stopped = false

    @Synchronized fun changed() {
        // A provider may finish a committed batch after cancellation; its rows still need publishing.
        if (stopped) { invalidate(); return }
        val now = elapsed()
        val remaining = 250 - (now - lastUpdate)
        if (remaining <= 0) {
            pending?.cancel(); pending = null
            lastUpdate = now
            invalidate()
        } else if (pending == null) {
            pending = scope.launch(start = CoroutineStart.LAZY) {
                delay(remaining)
                synchronized(this@LibraryScanUpdates) {
                    if (pending !== coroutineContext[Job]) return@synchronized
                    pending = null
                    lastUpdate = elapsed()
                    invalidate()
                }
            }.also { it.start() }
        }
    }

    @Synchronized fun finish() {
        stopped = true
        pending?.let { it.cancel(); pending = null; invalidate() }
    }
}
