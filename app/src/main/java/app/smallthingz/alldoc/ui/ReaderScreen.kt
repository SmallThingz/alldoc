package app.smallthingz.alldoc.ui

import android.content.res.Configuration
import android.animation.ValueAnimator
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.delete
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.smallthingz.alldoc.BuildConfig
import app.smallthingz.alldoc.core.takeScalarSafe
import app.smallthingz.alldoc.engine.SheetFrame
import app.smallthingz.alldoc.engine.SheetGeometry
import app.smallthingz.alldoc.engine.WorkbookInfo
import app.smallthingz.alldoc.state.SheetPosition
import app.smallthingz.alldoc.state.TextPosition
import app.smallthingz.alldoc.state.ViewportState
import app.smallthingz.alldoc.theme.DocumentColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first

@Composable
fun ReaderScreen(
    model: ReaderModel, onOpen: () -> Unit, onExport: (ReaderModel.ExportKind, Long) -> Unit,
    onChooseStorage: () -> Unit = {}, onPrint: (Long) -> Unit = {},
    externalDocument: Boolean = false,
    onInternalOpen: () -> Unit = {}, onFullscreen: (Boolean) -> Unit = {},
    onLibraryAccess: () -> Unit = {},
    libraryAccess: ReaderAccessModel? = null,
) {
    var routes by rememberSaveable { mutableStateOf(
        if (model.readerVisible) arrayListOf("home", "reader") else arrayListOf("home")) }
    var overlay by rememberSaveable(model.documentId) { mutableStateOf("") }
    var searching by rememberSaveable(model.documentId) { mutableStateOf(false) }
    var textBeforeSearch by rememberSaveable(model.documentId) { mutableStateOf(false) }
    var requestSearchFocus by rememberSaveable(model.documentId) { mutableStateOf(false) }
    var fullscreen by rememberSaveable(model.documentId) { mutableStateOf(false) }
    val query = rememberSaveable(model.documentId, saver = TextFieldState.Saver) { TextFieldState() }
    val library = model.libraryPages.collectAsLazyPagingItems()
    val identity = model.documentId
    var restoringEntrance by remember { mutableStateOf(model.restoringLastDocument || model.readerVisible) }
    val animateReaderEntrance = remember(identity) { !restoringEntrance }
    var routedIdentity by remember { mutableLongStateOf(identity) }
    var returningHome by remember(identity) { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!BuildConfig.DEBUG) routes = ArrayList(routes.filterNot { it == "examples" })
    }
    val info = model.info
    val snackbars = remember { SnackbarHostState() }
    val systemDark = isSystemInDarkTheme()
    LaunchedEffect(systemDark) { model.setSystemDark(systemDark) }
    fun push(route: String) {
        if (route == "examples" && !BuildConfig.DEBUG) return
        model.cancelAutomaticResume()
        if (routes.lastOrNull() != route) routes = ArrayList(routes.filterNot { it == route }).apply { add(route) }
    }
    LaunchedEffect(libraryAccess?.offering, libraryAccess?.ready, libraryAccess?.granted,
        routes.lastOrNull(), externalDocument, model.readerVisible, model.loading, model.restoringLastDocument) {
        if (libraryAccess?.shouldIntroduce(routes.lastOrNull() == "home", externalDocument,
                model.readerVisible || model.loading || model.restoringLastDocument) == true) {
            libraryAccess.introductionShown()
            push("library-access")
        }
    }
    fun requestOpen() { model.cancelAutomaticResume(); onOpen() }
    fun pop(route: String) {
        if (routes.lastOrNull() == route && routes.size > 1) routes = ArrayList(routes.dropLast(1))
    }
    fun home() {
        if (model.showReader(false, identity)) { onInternalOpen(); fullscreen = false; routes = arrayListOf("home") }
    }
    fun leaveReader() {
        if (model.showReader(false, identity)) {
            fullscreen = false
            routes = ArrayList(routes.filterNot { it == "reader" }).also { if (it.isEmpty()) it.add("home") }
        }
    }
    fun closeSearch() {
        if (searching && model.textMode) model.setTextMode(textBeforeSearch, identity)
        searching = false; requestSearchFocus = false; query.edit { delete(0, length) }
    }
    LaunchedEffect(model.readerVisible, identity, model.restoringLastDocument) {
        if (model.restoringLastDocument && !model.readerVisible) restoringEntrance = true
        if (model.readerVisible) {
            if (routedIdentity != identity || "reader" !in routes) {
                routes = if (externalDocument) arrayListOf("home", "reader")
                    else ArrayList(routes.filterNot { it == "reader" }).apply { add("reader") }
            }
        } else if ("reader" in routes) {
            routes = ArrayList(routes.filterNot { it == "reader" }).also { if (it.isEmpty()) it.add("home") }
        }
        if (model.readerVisible || !model.restoringLastDocument) restoringEntrance = false
        routedIdentity = identity
    }
    // Saved search intent can outlive its ViewModel; restore its temporary surface once ready.
    // Do not key on textMode: explicit page/view transitions must remain authoritative.
    LaunchedEffect(identity, searching, info, model.loading, model.readerVisible,
        routes.lastOrNull(), model.error, model.openingCancelled) {
        if (searching && !model.loading) {
            val unavailable = info != null && !info.textAvailable ||
                info == null && (model.error != null || model.openingCancelled)
            if (unavailable) closeSearch()
            else if (model.readerVisible && routes.lastOrNull() == "reader" && info?.textAvailable == true)
                model.setTextMode(true, identity)
        }
    }
    LaunchedEffect(identity, model.textMode, model.readerVisible, info) {
        if (model.readerVisible && (model.textMode || info?.continuous == true)) model.loadText(identity)
    }
    LaunchedEffect(model.notice) {
        model.notice?.let { snackbars.showSnackbar(it); model.dismissNotice(it) }
    }
    LaunchedEffect(routes.lastOrNull()) { if (routes.lastOrNull() == "settings") model.refreshStorageUsage() }
    val documentAppearanceAvailable = model.readerVisible && info != null && model.documentHash != null && !model.loading
    val visibleOverlay = overlay.takeUnless {
        it == "limits" || (it == "details" || it == "contents" || it == "export") && info == null ||
            it == "appearance" && !documentAppearanceAvailable
    }.orEmpty()
    LaunchedEffect(overlay, info, model.loading, model.error, model.openingCancelled, model.readerVisible) {
        if (overlay == "limits") overlay = "" // A restored removed panel must not retain modal input ownership.
        if ((overlay == "details" || overlay == "contents" || overlay == "export") && info == null) overlay = ""
        // A cold owner may not have started automatic resume yet; preserve its hidden intent.
        if (overlay == "appearance" && (!model.readerVisible && info != null ||
                info == null && !model.loading && (model.error != null || model.openingCancelled))) overlay = ""
    }
    val activeModal = visibleOverlay.isNotEmpty() || model.error != null
    val startupLoading = routes.lastOrNull() == "home" &&
        (model.restoringLastDocument || restoringEntrance && model.readerVisible)
    fun cancelStartupOpen() {
        model.cancelAutomaticResume()
        if (model.loading) model.cancelLoading(identity)
        else if (model.readerVisible) model.showReader(false, identity)
    }
    BackHandler(enabled = startupLoading && !activeModal, onBack = ::cancelStartupOpen)
    val textSurfaceVisible = info != null && !model.loading && model.textMode
    val searchSurfaceVisible = searching && textSurfaceVisible
    val immersive = fullscreen && routes.lastOrNull() == "reader" && model.readerVisible
    val currentOnFullscreen by rememberUpdatedState(onFullscreen)
    DisposableEffect(immersive) {
        currentOnFullscreen(immersive)
        onDispose { currentOnFullscreen(false) }
    }
    ReaderTheme(appTheme = model.appTheme) {
        CompositionLocalProvider(LocalReaderImmersive provides immersive) {
            Scaffold(containerColor = androidx.compose.ui.graphics.Color.Transparent,
                snackbarHost = { SnackbarHost(snackbars) }, contentWindowInsets = if (immersive) WindowInsets(0, 0, 0, 0) else WindowInsets.safeDrawing) { insets ->
                // Keep moving route foreground inside the inset viewport; ReaderTheme still covers the bars.
                Box(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets).imePadding().clipToBounds()) {
                    routes.forEachIndexed { index, route -> key(route, if (route == "reader") identity else 0L) {
                        val active = index == routes.lastIndex && !activeModal && !startupLoading
                        val surface = Modifier.fillMaxSize().readerInput(active)
                        if (route == "home") Column(surface.readerBackground()) {
                            ReaderHeader("AllDoc",
                                onAbout = { model.cancelAutomaticResume(); overlay = "about" }, onSettings = { push("settings") },
                                onLicenses = { push("licenses") })
                            ReaderHome(::requestOpen, Modifier.weight(1f).fillMaxWidth(), model.recentDocuments,
                                onRecent = { onInternalOpen(); model.reopenDocument(it) }, currentTitle = info?.let { model.currentTitle },
                                onResume = { if (model.showReader(true, identity)) { onInternalOpen(); routes = arrayListOf("home", "reader") } },
                                currentHash = model.documentHash, currentAvailable = model.currentDocumentAvailable, previews = model.recentPreviews,
                                onPreviewRequest = model::requestRecentPreview, onRecentVisible = model::setRecentPreviewsVisible, active = active,
                                currentPage = model.page.takeIf { info != null && model.currentDocumentAvailable && !model.textMode && !model.sheetMode },
                                currentPageCount = info?.pageCount?.takeIf { it > 0 && model.currentDocumentAvailable }, library = library,
                                onLibrary = { onInternalOpen(); model.openLibraryDocument(it) }, libraryPreviews = model.libraryPreviews,
                                onLibraryVisible = model::setLibraryVisible, onLibrarySelected = model::setLibraryActive,
                                onLibrarySettings = { push("settings") }, onLibraryFilter = model::setLibraryFilter,
                                onRefresh = model::refreshLibrary, libraryScanning = model.libraryScanning,
                                onTabSelected = model::cancelAutomaticResume, onExamples = { push("examples") },
                                hiddenLibraryTypes = model.libraryHiddenTypes,
                                onHiddenLibraryTypesChange = model::setLibraryHiddenTypes,
                                libraryTypesEnabled = model.librarySettingsLoaded.collectAsStateWithLifecycle().value,
                                coverPaper = Color(DocumentColors.palette(model.effectivePreviewSettings).background))
                        } else ReaderMotionLayer(active = active && !(route == "reader" && (externalDocument || fullscreen || textSurfaceVisible)),
                            onBack = {
                                if (route == "library-access") {
                                    libraryAccess?.dismissIntroduction(); pop(route)
                                } else if (route != "reader") pop(route)
                                else if (returningHome) { returningHome = false; home() } else leaveReader()
                            },
                            modifier = surface, fromTop = route == "settings",
                            visible = route != "library-access" || libraryAccess?.granted != true,
                            animateEntrance = route != "reader" || !externalDocument && animateReaderEntrance) { back ->
                            when (route) {
                                "reader" -> ReaderFullscreenLayer(active = active && fullscreen && !textSurfaceVisible,
                                    fullscreen = fullscreen, onExit = { fullscreen = false }) {
                                    ReaderDocumentScreen(model, identity, active, searchSurfaceVisible, query, fullscreen,
                                        onCloseSearch = ::closeSearch,
                                        onSearch = {
                                            if (!searching) textBeforeSearch = model.textMode
                                            model.setTextMode(true, identity); searching = true; requestSearchFocus = true
                                        },
                                        requestSearchFocus = requestSearchFocus, onSearchFocusConsumed = { requestSearchFocus = false },
                                        onHome = { returningHome = true; back() }, onFullscreen = { fullscreen = !fullscreen },
                                        onInfo = { overlay = "details" }, onExport = { overlay = "export" },
                                        onContents = { if (model.contentsError == null) model.loadContents(identity); overlay = "contents" },
                                        onNotes = { model.setNotesVisible(!model.notesVisible, identity) },
                                        onAppearance = { overlay = "appearance" })
                                }
                                "library-access" -> libraryAccess?.let { access ->
                                    ReaderAccessScreen(access, onLibraryAccess,
                                        onSkip = back)
                                }
                                "settings" -> ReaderSettingsScreen(model.copySettings, model.storageLabel, model.copyUsageBytes,
                                    model.copyUsageCount, model.storageBusy || !model.compoundSettingsReady, model.storageWarning, model::updateCopySettings,
                                    onChooseStorage, onClear = { overlay = "clear" },
                                    appearance = model.appearanceForControls,
                                    onAppearanceChange = { model.setAppearance(it, identity) }, onBack = back,
                                    appearanceEnabled = !model.loading && (model.compoundSettingsReady || model.readerVisible && model.documentHash != null),
                                    documentAppearance = model.readerVisible && model.documentHash != null,
                                    appTheme = model.appTheme, onAppThemeChange = model::updateAppTheme,
                                    onLibraryAccess = onLibraryAccess,
                                    libraryAccess = libraryAccess, active = active)
                                "examples" -> if (BuildConfig.DEBUG) ReaderExamplesScreen(onBack = back, onOpen = {
                                    onInternalOpen(); model.openExample(it)
                                }, active = active)
                                "licenses" -> ReaderLicensesScreen(back, active)
                            }
                        }
                    } }
                    if (startupLoading) ReaderOpening(
                        onCancel = ::cancelStartupOpen, onOpen = { cancelStartupOpen(); onOpen() },
                        modifier = Modifier.fillMaxSize().readerBackground())
                }
            }
            ReaderPanels(model, identity, visibleOverlay,
                onDismiss = { expected -> if (overlay == expected) overlay = "" },
                onCloseSearch = ::closeSearch,
                onExport = { onExport(it, identity) },
                onPrint = { onPrint(identity) })
            when (val dialog = visibleOverlay) {
                "clear" -> ReaderDialog("Clear saved copies?", onDismiss = { if (overlay == dialog) overlay = "" },
                    buttons = { dismiss ->
                        TextButton(onClick = dismiss) { Text("Cancel") }
                        TextButton(onClick = { model.clearRetainedCopies(); dismiss() },
                            enabled = model.compoundSettingsReady && !model.storageBusy) { Text("Clear") }
                    }) { Text("Some shared files may become unavailable. Original files are kept.") }
            }
            model.error?.let { message -> key(identity, message) {
                val passwordNeeded = message.contains("password", true) && !message.contains("not supported", true) &&
                    !message.contains("unavailable", true) && !message.contains("Android", true)
                val openingError = info == null && routes.lastOrNull() == "reader"
                var password by remember { mutableStateOf("") }
                ReaderDialog(if (passwordNeeded) "Password" else if (openingError) "Couldn’t open document" else "Error", { model.dismissError(identity, message) },
                    buttons = { dismiss ->
                        if (passwordNeeded) {
                            TextButton(onClick = dismiss) { Text("Cancel") }
                            TextButton(onClick = { model.retryWithPassword(password, identity, message) }, enabled = password.isNotEmpty()) { Text("Open") }
                        } else {
                            if (openingError && model.source != null) TextButton(onClick = { model.retryOpening(identity, message) }) { Text("Retry") }
                            TextButton(onClick = dismiss) { Text("Close") }
                        }
                    }) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(message)
                        if (passwordNeeded) OutlinedTextField(password, { password = it.takeScalarSafe(256) },
                            label = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                    }
                }
            } }
        }
    }
}

@Composable
private fun ReaderOpening(onCancel: () -> Unit, modifier: Modifier = Modifier.fillMaxSize(), onOpen: (() -> Unit)? = null) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.semantics { contentDescription = "Opening document" })
            TextButton(onClick = onCancel) { Text("Cancel") }
            if (onOpen != null) TextButton(onClick = onOpen) { Text("Open document") }
        }
    }
}

private data class ReaderPagesVisual(
    val count: Int, val anchor: Int, val fraction: Float, val viewport: ViewportState, val revision: Long,
    val images: Map<Int, ReaderPageImage>, val aspects: Map<Int, Float>,
    val details: Map<Int, ReaderPageDetail>, val horizontal: Boolean, val failedPages: Set<Int>,
)

private data class ReaderWorksheetVisual(
    val workbook: WorkbookInfo?, val geometry: SheetGeometry?, val position: SheetPosition,
    val frame: SheetFrame?, val loading: Boolean, val error: String?,
)

private class ReaderTransitionVisual {
    var pages: ReaderPagesVisual? = null
    var worksheet: ReaderWorksheetVisual? = null
    var text: String? = null
    var textLoading = false
    var textPosition = TextPosition()
}

/** A short crossfade retains pixels, while only the settled foreground accepts input. */
@Composable
internal fun ReaderTextTransition(
    textMode: Boolean, destinationReady: Boolean, active: Boolean, onBack: () -> Unit,
    backEnabled: Boolean = true, animate: Boolean = true, onSettled: (Boolean) -> Unit = {},
    pageContent: @Composable (Boolean) -> Unit, textContent: @Composable (Boolean) -> Unit,
) {
    val opacity = remember { Animatable(if (textMode) 1f else 0f) }
    var retainText by remember { mutableStateOf(textMode) }
    val ready by rememberUpdatedState(destinationReady)
    val settled by rememberUpdatedState(onSettled)
    val currentBack by rememberUpdatedState(onBack)
    val canReturn by rememberUpdatedState(active && textMode && backEnabled)
    val motionScope = rememberCoroutineScope()
    val backMotion = remember { ReaderMotionState(1f, motionScope) }
    SideEffect { backMotion.onClosed = { if (canReturn) currentBack() } }
    LaunchedEffect(textMode) { if (textMode) backMotion.reveal() }
    PredictiveBackHandler(enabled = active && textMode && backEnabled) { events ->
        // Preview only the current text pixels; native pages are requested after commitment.
        backMotion.follow(events) { canReturn }
    }
    LaunchedEffect(textMode, animate) {
        val motion = animate && ValueAnimator.areAnimatorsEnabled()
        if (textMode) {
            retainText = true
            if (motion) opacity.animateTo(1f, tween(180)) else opacity.snapTo(1f)
        } else if (retainText) {
            // Native rendering cannot delay Back indefinitely. A slow destination proceeds to
            // its normal loading/error surface after this bounded visual handoff.
            if (motion && !ready) withTimeoutOrNull(100) { snapshotFlow { ready }.first { it } }
            if (motion) opacity.animateTo(0f, tween(140)) else opacity.snapTo(0f)
            retainText = false
        }
        settled(textMode)
    }
    Box(Modifier.fillMaxSize()) {
        if (!textMode || opacity.value < 1f) {
            val input = active && !textMode && !retainText
            Box(Modifier.fillMaxSize().readerInput(input)) { pageContent(input) }
        }
        if (textMode || retainText) {
            val input = active && textMode && !backMotion.closing && opacity.value == 1f && !opacity.isRunning
            Box(Modifier.fillMaxSize().readerInput(input).graphicsLayer {
                val progress = 1f - backMotion.openness.value
                alpha = opacity.value
                translationX = size.width * .08f * progress
                scaleX = 1f - .03f * progress
                scaleY = scaleX
            }) {
                textContent(input)
            }
        }
    }
}

@Composable
private fun ReaderDocumentScreen(
    model: ReaderModel, identity: Long, active: Boolean, searching: Boolean, query: TextFieldState, fullscreen: Boolean,
    onCloseSearch: () -> Unit, onSearch: () -> Unit, requestSearchFocus: Boolean, onSearchFocusConsumed: () -> Unit,
    onHome: () -> Unit, onFullscreen: () -> Unit, onAppearance: () -> Unit,
    onInfo: () -> Unit, onExport: () -> Unit, onContents: () -> Unit, onNotes: () -> Unit,
) {
    val info = model.info
    var jumping by rememberSaveable(identity) { mutableStateOf(false) }
    var choosingView by rememberSaveable(identity) { mutableStateOf(false) }
    val textView = model.textMode && !(info?.continuous == true && searching)
    fun pageView(horizontal: Boolean) {
        model.choosePageView(horizontal, identity)
    }
    val retained = remember(identity) { ReaderTransitionVisual() }
    val pages = info?.let { ReaderPagesVisual(it.pageCount, model.anchorPage, model.pageFraction,
        model.viewport, model.pagePositionRevision, model.pageImages, model.pageAspects,
        model.pageDetails, model.horizontalPages, model.failedPages) }
    val worksheet = if (model.sheetMode) ReaderWorksheetVisual(model.workbook, model.sheetGeometry,
        model.sheetPosition, model.sheetFrame, model.sheetsLoading, model.sheetsError) else null
    SideEffect {
        if (!model.loading && info != null) {
            if (model.textMode) {
                retained.text = model.text
                retained.textLoading = model.textLoading
                retained.textPosition = model.textPosition
            } else {
                // Keep only the current page's published raster, never the page/detail cache.
                retained.pages = if (worksheet == null) pages?.copy(
                    images = model.pageImages[model.page]?.let { mapOf(model.page to it) }.orEmpty(),
                    details = emptyMap()) else null
                retained.worksheet = worksheet
            }
        }
    }
    val controls = rememberReaderControls(fullscreen,
        !active || searching || jumping || choosingView || model.exporting || model.loading || info == null)
    fun action(block: () -> Unit) { controls.reveal(); block() }
    fun chooseView() = action {
        if (info?.continuous == true) {
            if (model.textMode) model.choosePageView(false, identity) else model.chooseTextView(identity)
        } else if (info?.textAvailable == true || info?.reflowable == true) choosingView = true
        else model.choosePageView(!model.horizontalPages, identity)
    }
    Box(Modifier.fillMaxSize().readerBackground().readingArea(fullscreen, controls)) {
        ReaderContentWithNotes(model, identity, Modifier.fillMaxSize(), fullscreen, onCloseSearch,
            active = active && !choosingView && !jumping) { contentModifier ->
            Box(contentModifier.onSizeChanged { model.setRenderWidth(it.width) }) {
                when {
                    model.loading -> ReaderOpening(onCancel = { model.cancelLoading(identity) })
                    info == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        TextButton(onClick = onHome) { Text("Library") }
                    }
                    else -> ReaderTextTransition(
                        textMode = textView,
                        destinationReady = when {
                            info.continuous -> model.text != null || !model.textLoading
                            worksheet != null -> worksheet.frame != null || worksheet.error != null
                            else -> model.page in model.pageImages || model.page in model.failedPages
                        },
                        active = active && !jumping && !choosingView, backEnabled = !searching,
                        animate = !searching, onBack = { pageView(model.horizontalPages) },
                        onSettled = { text ->
                            if (text) { retained.pages = null; retained.worksheet = null }
                            else retained.text = null
                        },
                        pageContent = { acceptingInput ->
                            val frozen = model.textMode && !info.continuous
                            val grid = if (frozen) retained.worksheet else worksheet
                            val raster = if (frozen) retained.pages else pages
                            when {
                                info.continuous -> ReaderTextView(
                                    model.text, model.textLoading, model.effectiveSettings, searching, query, onCloseSearch,
                                    Modifier.fillMaxSize().readingGestures(controls, acceptingInput && !searching)
                                        .semantics { contentDescription = "Markdown document" },
                                    identity, model.textPosition,
                                    onPosition = { if (!textView) model.updateTextPosition(it, identity) },
                                    onScrollEnd = model::flushDocumentState,
                                    active = acceptingInput, backEnabled = acceptingInput,
                                    prepareContent = active && !jumping && !choosingView,
                                    requestSearchFocus = requestSearchFocus, onSearchFocusConsumed = onSearchFocusConsumed,
                                    contentInsets = if (fullscreen) WindowInsets.displayCutout else WindowInsets(0, 0, 0, 0),
                                    contentPadding = PaddingValues(start = 22.dp, end = 22.dp,
                                        top = if (fullscreen || searching) 20.dp else 76.dp, bottom = if (fullscreen) 20.dp else 84.dp),
                                    onScrollDirection = { if (acceptingInput) controls.scroll(it) }, markdown = model.markdown)
                                grid != null -> ReaderWorksheet(identity, grid.workbook, grid.geometry,
                                    grid.position, grid.frame, grid.loading, grid.error,
                                    onPosition = { if (!frozen) model.updateSheetPosition(it, identity) },
                                    onRequest = { if (!frozen) model.requestSheetViewport(it, identity) },
                                    onViewport = { size, density -> if (!frozen) model.updateSheetViewport(size, density, identity) },
                                    onSelectSheet = { if (acceptingInput) model.selectSheet(it, identity) },
                                    onGestureEnd = model::flushDocumentState,
                                    onRetry = { if (acceptingInput) model.retrySheet(identity) }, active = acceptingInput,
                                    modifier = Modifier.fillMaxSize().padding(top = if (fullscreen) 0.dp else 56.dp,
                                        bottom = if (fullscreen) 0.dp else 64.dp),
                                    paper = Color(DocumentColors.palette(model.effectiveSettings).background),
                                    onScrollDirection = { if (acceptingInput) controls.scroll(it) },
                                    onTap = { if (acceptingInput) controls.toggle() })
                                raster != null -> ReaderPages(identity, raster.count, raster.anchor, raster.fraction,
                                    raster.viewport, raster.revision, raster.images, raster.aspects,
                                    onVisiblePages = { if (!frozen) model.requestVisiblePages(it, identity) },
                                    // Covered owners still publish their final cancelled-gesture position.
                                    onPosition = { page, fraction -> if (!frozen) model.updatePagePosition(page, fraction, identity) },
                                    onCurrentPage = { if (!frozen) model.updateCurrentPage(it, identity) },
                                    onViewportChange = { if (!frozen) model.updateViewport(it, identity) },
                                    onGestureEnd = model::flushDocumentState,
                                    onZoomAnimation = { model.setZoomAnimation(it, identity) }, active = acceptingInput,
                                    details = raster.details,
                                    onVisibleRegions = { if (!frozen) model.requestVisibleRegions(it, identity) },
                                    modifier = Modifier.fillMaxSize(), normalEdgePadding = if (fullscreen) 16.dp else 80.dp,
                                    paper = Color(DocumentColors.palette(model.effectiveSettings).background), horizontal = raster.horizontal,
                                    failedPages = raster.failedPages, onRetryPage = { if (acceptingInput) model.retryPage(it, identity) },
                                    onReadText = if (info.textAvailable || info.reflowable) ({
                                        if (acceptingInput) action { model.chooseTextView(identity) }
                                    }) else null,
                                    onScrollDirection = { if (acceptingInput) controls.scroll(it) },
                                    onTap = { if (acceptingInput) controls.toggle() })
                            }
                        },
                        textContent = { acceptingInput ->
                            ReaderTextView(if (model.textMode) model.text else retained.text,
                                if (model.textMode) model.textLoading else retained.textLoading,
                                model.effectiveSettings, searching, query, onCloseSearch,
                                Modifier.fillMaxSize().readingGestures(controls, acceptingInput && !searching),
                                identity, if (model.textMode) model.textPosition else retained.textPosition,
                                onPosition = { if (textView) model.updateTextPosition(it, identity) },
                                onScrollEnd = model::flushDocumentState,
                                active = acceptingInput, backEnabled = acceptingInput,
                                prepareContent = active && !jumping && !choosingView,
                                requestSearchFocus = requestSearchFocus, onSearchFocusConsumed = onSearchFocusConsumed,
                                contentInsets = if (fullscreen) WindowInsets.displayCutout else WindowInsets(0, 0, 0, 0),
                                contentPadding = PaddingValues(start = 22.dp, end = 22.dp,
                                    top = if (fullscreen || searching) 20.dp else 76.dp, bottom = if (fullscreen) 20.dp else 84.dp),
                                onScrollDirection = { if (acceptingInput) controls.scroll(it) })
                        })
                }

                Box(Modifier.fillMaxSize().zIndex(1f).then(if (fullscreen) Modifier.safeDrawingPadding() else Modifier)
                    .onFocusChanged { controls.focused = it.hasFocus }.focusGroup()) {
                    ReaderControlsOverlay(controls.shown && !searching, fromTop = true,
                        modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().readingGestures(controls, active, toggle = false)) {
                            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).heightIn(min = 56.dp).padding(horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                if (model.textMode) ReaderAction(Icons.AutoMirrored.Outlined.ArrowBack, "Return to pages") {
                                    action { pageView(model.horizontalPages) }
                                } else if (!fullscreen) ReaderAction(Icons.Outlined.Home, "Home") { action(onHome) }
                                ReaderTooltip("File info", Modifier.weight(1f), enabled = info != null) {
                                    Box(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                        .then(if (info != null) Modifier.clickable(role = Role.Button) { action(onInfo) }
                                            .semantics { contentDescription = "File info" } else Modifier)
                                        .padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(info?.let { model.currentTitle } ?: "AllDoc", Modifier.weight(1f),
                                                style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            if (model.markdown?.truncated == true) Text("Excerpt", Modifier.padding(start = 8.dp),
                                                style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                                if (info != null) ReaderAction(Icons.Outlined.IosShare, "Export", enabled = model.canExport) { action(onExport) }
                            }
                            AnimatedVisibility(model.exporting, modifier = Modifier.readerInput(model.exporting)) { Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                Text("Exporting…", Modifier.weight(1f).padding(12.dp), style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = model::cancelExport) { Text("Cancel") }
                            } }
                        }
                    }
                    ReaderControlsOverlay(controls.shown && info != null, fromTop = false,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp)) {
                        ReaderTools(model, searching, fullscreen,
                            { action(onSearch) }, { action(onContents) }, { action(onNotes) }, { action(onAppearance) },
                            ::chooseView, { action(onFullscreen) }, onJump = { action { jumping = true } },
                            modifier = Modifier.readingGestures(controls, active, toggle = false),
                            active = active && controls.shown && !choosingView && !jumping && !searching)
                    }
                }
            }
        }
    }
    if (choosingView && info != null) ReaderViewSheet(model, onDismiss = { choosingView = false },
        onPageView = { horizontal -> onCloseSearch(); pageView(horizontal) },
        onTextView = { onCloseSearch(); model.chooseTextView(identity) })
    if (jumping && info != null) ReaderPageJump(model.page, info.pageCount, onDismiss = { jumping = false }) {
        model.goToPage(it, identity)
    }
}

/** Overlay visibility never changes the document's viewport or saved scroll position. */
@Composable
private fun ReaderControlsOverlay(
    visible: Boolean, fromTop: Boolean, modifier: Modifier, content: @Composable () -> Unit,
) {
    AnimatedVisibility(visible, modifier = modifier.readerInput(visible),
        enter = fadeIn(tween(180)) + slideInVertically(tween(220)) { if (fromTop) -it else it },
        exit = fadeOut(tween(180)) + slideOutVertically(tween(220)) { if (fromTop) -it else it }) { content() }
}

@Composable
private fun ReaderTools(
    model: ReaderModel, searching: Boolean, fullscreen: Boolean,
    onSearch: () -> Unit, onContents: () -> Unit, onNotes: () -> Unit, onAppearance: () -> Unit,
    onView: () -> Unit, onFullscreen: () -> Unit, onJump: () -> Unit, modifier: Modifier = Modifier,
    active: Boolean,
) {
    val info = model.info ?: return
    val scroll = rememberScrollState()
    LaunchedEffect(scroll, active) {
        if (!active) scroll.stopScroll(MutatePriority.PreventUserInput)
    }
    Row(modifier.animateContentSize().then(if (searching) Modifier else Modifier.widthIn(max = 560.dp).fillMaxWidth())
        .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = .96f), MaterialTheme.shapes.large)
        .padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (!searching) Row(Modifier.weight(1f).horizontalScroll(scroll, enabled = active), verticalAlignment = Alignment.CenterVertically) {
                if (!model.textMode && !model.sheetMode && info.pageCount > 1) ReaderTooltip("Jump to page") {
                    TextButton(onClick = onJump,
                        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Jump to page" }) {
                        Text("${model.page + 1} / ${info.pageCount}", maxLines = 1, style = MaterialTheme.typography.labelLarge)
                    }
                }
                if (info.textAvailable) ReaderOverlayAction(Icons.Outlined.Search, "Find in document", onClick = onSearch)
                if (model.outline.isNotEmpty() || model.contentsError != null)
                    ReaderOverlayAction(Icons.AutoMirrored.Outlined.List, "Contents", onClick = onContents)
                if (!model.notesVisible && model.notes.hasContent) ReaderOverlayAction(Icons.AutoMirrored.Outlined.Notes, "Notes",
                    selected = model.notesVisible, onClick = onNotes)
                ReaderOverlayAction(Icons.Outlined.Palette, "Reading appearance", onClick = onAppearance)
                ReaderOverlayAction(Icons.Outlined.ViewCarousel, "Reading view", selected = model.textMode || model.horizontalPages, onClick = onView)
        }
        if (model.notesVisible && model.notes.hasContent) ReaderOverlayAction(Icons.AutoMirrored.Outlined.Notes,
            "Notes", selected = true, onClick = onNotes)
        ReaderOverlayAction(if (fullscreen) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen,
            if (fullscreen) "Exit full screen" else "Full screen", onClick = onFullscreen)
    }
}

@Composable
private fun ReaderViewSheet(
    model: ReaderModel, onDismiss: () -> Unit, onPageView: (Boolean) -> Unit, onTextView: () -> Unit,
) {
    ReaderSheet("Reading view", onDismiss, showTitle = false) { dismiss ->
        ReaderViewChoices(if (model.textMode) 2 else if (model.horizontalPages) 1 else 0) { index ->
            if (index == 2) onTextView() else onPageView(index == 1)
            dismiss()
        }
    }
}

@Composable
internal fun ReaderViewChoices(selected: Int, onSelect: (Int) -> Unit) {
    val labels = listOf("Normal", "Present", "Text")
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelLarge
    val labelWidth = labels.maxOf { measurer.measure(it, style).size.width }
    BoxWithConstraints(Modifier.fillMaxWidth().selectableGroup()) {
        val gap = with(density) { 8.dp.roundToPx() }
        val minimum = maxOf(with(density) { 72.dp.roundToPx() }, labelWidth + gap)
        val columns = ((constraints.maxWidth + gap) / (minimum + gap)).coerceIn(1, labels.size)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            labels.withIndex().chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { (index, label) ->
                        Surface(Modifier.weight(1f).heightIn(min = 48.dp).selectable(selected == index,
                            role = Role.RadioButton, onClick = { onSelect(index) })
                            .semantics { contentDescription = "$label view" }, shape = MaterialTheme.shapes.medium,
                            color = if (selected == index) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh) {
                            Column(Modifier.padding(vertical = 16.dp, horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(when (index) { 0 -> Icons.Outlined.ViewDay; 1 -> Icons.Outlined.ViewCarousel; else -> Icons.AutoMirrored.Outlined.Notes }, null)
                                Text(label, style = style, maxLines = 1)
                            }
                        }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun ReaderContentWithNotes(
    model: ReaderModel, identity: Long, modifier: Modifier,
    fullscreen: Boolean = false, onNavigate: () -> Unit = {}, active: Boolean = true, content: @Composable (Modifier) -> Unit,
) {
    ReaderNotesLayout(model.notesVisible && model.notes.hasContent, modifier,
        notes = { paneModifier ->
            val portrait = LocalConfiguration.current.orientation != Configuration.ORIENTATION_LANDSCAPE
            ReaderDocumentPane(model, identity, paneModifier.then(if (fullscreen) Modifier.windowInsetsPadding(
                WindowInsets.safeDrawing.only(if (portrait) WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
                    else WindowInsetsSides.Horizontal + WindowInsetsSides.Vertical)) else Modifier),
                onNavigate = onNavigate, active = active && model.notesVisible)
        }, content = content)
}

@Composable
internal fun ReaderNotesLayout(
    showNotes: Boolean, modifier: Modifier,
    notes: @Composable (Modifier) -> Unit, content: @Composable (Modifier) -> Unit,
) {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    BoxWithConstraints(modifier) {
        val paneWidth = maxWidth * .4f
        val paneHeight = maxHeight * .35f
        val density = LocalDensity.current
        var minimumHeight by remember(maxWidth, maxHeight, density) { mutableStateOf(0.dp) }
        LaunchedEffect(showNotes) { if (showNotes) minimumHeight = 0.dp }
        // Both subtrees keep one composition address as the dock changes orientation.
        // Its measured extent changes once; slide/fade motion never reflows the document.
        Layout(modifier = Modifier.fillMaxSize(), content = {
            Box { content(Modifier.fillMaxSize()) }
            // Keep a second measurable even when AnimatedVisibility has no content.
            Box {
                AnimatedVisibility(showNotes, modifier = Modifier.readerInput(showNotes),
                    enter = fadeIn(tween(180)) + if (landscape) slideInHorizontally(tween(220)) { it }
                        else slideInVertically(tween(220)) { it },
                    exit = fadeOut(tween(180)) + if (landscape) slideOutHorizontally(tween(220)) { it }
                        else slideOutVertically(tween(220)) { it }) {
                    Box {
                        Box(Modifier.absolutePadding(left = if (landscape) 1.dp else 0.dp,
                            top = if (landscape) 0.dp else 1.dp)) {
                            val pane = if (landscape) Modifier.fillMaxHeight().width(paneWidth)
                                else Modifier.fillMaxWidth().heightIn(min = minimumHeight, max = paneHeight).onSizeChanged {
                                    minimumHeight = maxOf(minimumHeight,
                                        with(density) { it.height.toDp() }.coerceAtMost(paneHeight))
                                }
                            notes(pane)
                        }
                        if (landscape) VerticalDivider(Modifier.align(AbsoluteAlignment.CenterLeft))
                        else HorizontalDivider(Modifier.align(Alignment.TopCenter))
                    }
                }
            }
        }) { children, constraints ->
            val dock = children[1].measure(constraints.copy(minWidth = 0, minHeight = 0))
            val document = children[0].measure(Constraints.fixed(
                (constraints.maxWidth - if (landscape) dock.width else 0).coerceAtLeast(0),
                (constraints.maxHeight - if (landscape) 0 else dock.height).coerceAtLeast(0)))
            layout(constraints.maxWidth, constraints.maxHeight) {
                document.place(0, 0)
                dock.place(if (landscape) document.width else 0, if (landscape) 0 else document.height)
            }
        }
    }
}

@Composable
internal fun ReaderPageJump(page: Int, count: Int, onDismiss: () -> Unit, onPage: (Int) -> Unit) {
    var entry by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        val value = (page + 1).toString()
        mutableStateOf(TextFieldValue(value, TextRange(0, value.length)))
    }
    var focused by remember { mutableStateOf(false) }
    val requester = remember { FocusRequester() }
    val number = entry.text.toIntOrNull()?.takeIf { it in 1..count }
    fun go(dismiss: () -> Unit) { number?.let { onPage(it - 1); dismiss() } }
    ReaderSheet("Jump to page", onDismiss,
        buttons = { dismiss ->
            TextButton(onClick = dismiss) { Text("Cancel") }
            TextButton(onClick = { go(dismiss) }, enabled = number != null) { Text("Go") }
        }) { dismiss ->
        OutlinedTextField(entry, { value ->
            if (value.text.length <= 10 && value.text.all { it in '0'..'9' }) entry = value
        }, modifier = Modifier.fillMaxWidth().focusRequester(requester), singleLine = true,
            label = { Text("Page") }, suffix = { Text("/ $count") }, isError = entry.text.isNotEmpty() && number == null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { go(dismiss) }))
        val inputAllowed = LocalReaderInputAllowed.current
        val window = LocalWindowInfo.current
        val keyboard = LocalSoftwareKeyboardController.current
        val ready = inputAllowed() && window.isWindowFocused
        LaunchedEffect(ready) {
            if (ready && !focused) {
                withFrameNanos { }
                if (inputAllowed() && window.isWindowFocused && requester.requestFocus()) {
                    focused = true
                    keyboard?.show()
                }
            }
        }
    }
}

/** Retained destinations cannot own focus, keys, touches or accessibility navigation. */
@Composable
internal fun Modifier.readerInput(active: Boolean): Modifier {
    val focus = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    SideEffect { if (!active && focused) focus.clearFocus(force = true) }
    return then(if (active) Modifier else Modifier.clearAndSetSemantics { hideFromAccessibility() })
        .onFocusChanged { focused = it.hasFocus }
        .focusProperties {
            canFocus = false
            onEnter = { if (!active) cancelFocusChange() }
        }.focusGroup()
        .onPreviewKeyEvent { !active }
        .pointerInput(active) {
            if (!active) awaitPointerEventScope {
                while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
            }
        }
}
