package app.smallthingz.alldoc.ui

import android.content.res.Configuration

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
import androidx.compose.ui.unit.dp
import androidx.paging.compose.collectAsLazyPagingItems
import app.smallthingz.alldoc.core.takeScalarSafe
import app.smallthingz.alldoc.theme.DocumentColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ReaderScreen(
    model: ReaderModel, onOpen: () -> Unit, onExport: (ReaderModel.ExportKind, Long) -> Unit,
    onChooseStorage: () -> Unit = {}, onPrint: (Long) -> Unit = {},
    externalDocument: Boolean = false,
    onInternalOpen: () -> Unit = {}, onFullscreen: (Boolean) -> Unit = {},
    onLibraryAccess: () -> Unit = {}, onAddLibraryFolder: () -> Unit = {},
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
    var routedIdentity by remember { mutableLongStateOf(identity) }
    val info = model.info
    val snackbars = remember { SnackbarHostState() }
    val systemDark = isSystemInDarkTheme()
    LaunchedEffect(systemDark) { model.setSystemDark(systemDark) }
    fun push(route: String) {
        model.cancelAutomaticResume()
        if (routes.lastOrNull() != route) routes = ArrayList(routes.filterNot { it == route }).apply { add(route) }
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
    LaunchedEffect(model.readerVisible, identity) {
        if (model.readerVisible) {
            if (routedIdentity != identity || "reader" !in routes) {
                routes = if (externalDocument) arrayListOf("home", "reader")
                    else ArrayList(routes.filterNot { it == "reader" }).apply { add("reader") }
            }
        } else if ("reader" in routes) {
            routes = ArrayList(routes.filterNot { it == "reader" }).also { if (it.isEmpty()) it.add("home") }
        }
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
        (it == "details" || it == "contents" || it == "export") && info == null ||
            it == "appearance" && !documentAppearanceAvailable
    }.orEmpty()
    LaunchedEffect(overlay, info, model.loading, model.error, model.openingCancelled, model.readerVisible) {
        if ((overlay == "details" || overlay == "contents" || overlay == "export") && info == null) overlay = ""
        // A cold owner may not have started automatic resume yet; preserve its hidden intent.
        if (overlay == "appearance" && (!model.readerVisible && info != null ||
                info == null && !model.loading && (model.error != null || model.openingCancelled))) overlay = ""
    }
    val activeModal = visibleOverlay.isNotEmpty() || model.error != null
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
                Box(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets).imePadding()) {
                    routes.forEachIndexed { index, route -> key(route, if (route == "reader") identity else 0L) {
                        val active = index == routes.lastIndex && !activeModal
                        val surface = Modifier.fillMaxSize().readerInput(active)
                        if (route == "home") Column(surface.readerBackground()) {
                            ReaderHeader("AllDoc",
                                onAbout = { model.cancelAutomaticResume(); overlay = "about" }, onSettings = { push("settings") },
                                onLicenses = { push("licenses") })
                            ReaderHome(::requestOpen, Modifier.weight(1f).fillMaxWidth(), model.recentDocuments,
                                onRecent = { onInternalOpen(); model.reopenDocument(it) }, currentTitle = info?.let { model.currentTitle },
                                onResume = { if (model.showReader(true, identity)) { onInternalOpen(); routes = arrayListOf("home", "reader") } },
                                currentHash = model.documentHash, previews = model.recentPreviews,
                                onPreviewRequest = model::requestRecentPreview, active = active,
                                currentPage = model.page.takeIf { info != null && !model.textMode },
                                currentPageCount = info?.pageCount, library = library,
                                onLibrary = { onInternalOpen(); model.openLibraryDocument(it) }, libraryPreviews = model.libraryPreviews,
                                onLibraryVisible = model::setLibraryVisible, onLibrarySelected = model::setLibraryActive,
                                onLibrarySettings = { push("settings") }, onLibraryFilter = model::setLibraryFilter,
                                onRefresh = model::refreshLibrary, libraryScanning = model.libraryScanning,
                                onTabSelected = model::cancelAutomaticResume, onExamples = { push("examples") },
                                coverPaper = Color(DocumentColors.palette(model.effectivePreviewSettings).background))
                        } else ReaderMotionLayer(active = active && !(route == "reader" && (externalDocument || fullscreen || textSurfaceVisible)),
                            onBack = { if (route == "reader") leaveReader() else pop(route) },
                            modifier = surface, fromTop = route == "settings",
                            animateEntrance = route != "reader" || !externalDocument) { back ->
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
                                        onHome = ::home, onFullscreen = { fullscreen = !fullscreen },
                                        onInfo = { overlay = "details" }, onExport = { overlay = "export" },
                                        onContents = { if (model.contentsError == null) model.loadContents(identity); overlay = "contents" },
                                        onNotes = { model.setNotesVisible(!model.notesVisible, identity) },
                                        onAppearance = { overlay = "appearance" })
                                }
                                "settings" -> ReaderSettingsScreen(model.copySettings, model.storageLabel, model.copyUsageBytes,
                                    model.copyUsageCount, model.storageBusy, model.storageWarning, model::updateCopySettings,
                                    onChooseStorage, onLimits = { overlay = "limits" }, onClear = { overlay = "clear" },
                                    appearance = model.appearanceForControls,
                                    onAppearanceChange = { model.setAppearance(it, identity) }, onBack = back,
                                    appearanceEnabled = !model.loading, documentAppearance = model.readerVisible && model.documentHash != null,
                                    appTheme = model.appTheme, onAppThemeChange = model::updateAppTheme,
                                    hiddenLibraryTypes = model.libraryHiddenTypes, onHiddenLibraryTypesChange = model::setLibraryHiddenTypes,
                                    onLibraryAccess = onLibraryAccess, onAddLibraryFolder = onAddLibraryFolder)
                                "examples" -> ReaderExamplesScreen(onBack = back, onOpen = {
                                    onInternalOpen(); model.openExample(it)
                                })
                                "licenses" -> ReaderLicensesScreen(back)
                            }
                        }
                    } }
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
                        TextButton(onClick = { model.clearRetainedCopies(); dismiss() }) { Text("Clear") }
                    }) { Text("Some shared files may become unavailable. Original files are kept.") }
            }
            model.error?.let { message -> key(identity, message) {
                val passwordNeeded = message.contains("password", true) && !message.contains("not supported", true) &&
                    !message.contains("unavailable", true) && !message.contains("Android", true)
                var password by remember { mutableStateOf("") }
                ReaderDialog(if (passwordNeeded) "Password" else if (info == null) "Couldn’t open document" else "Error", { model.dismissError(identity, message) },
                    buttons = { dismiss ->
                        if (passwordNeeded) {
                            TextButton(onClick = dismiss) { Text("Cancel") }
                            TextButton(onClick = { model.retryWithPassword(password, identity, message) }, enabled = password.isNotEmpty()) { Text("Open") }
                        } else {
                            if (info == null && model.source != null) TextButton(onClick = { model.retryOpening(identity, message) }) { Text("Retry") }
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
private fun ReaderDocumentScreen(
    model: ReaderModel, identity: Long, active: Boolean, searching: Boolean, query: TextFieldState, fullscreen: Boolean,
    onCloseSearch: () -> Unit, onSearch: () -> Unit, requestSearchFocus: Boolean, onSearchFocusConsumed: () -> Unit,
    onHome: () -> Unit, onFullscreen: () -> Unit, onAppearance: () -> Unit,
    onInfo: () -> Unit, onExport: () -> Unit, onContents: () -> Unit, onNotes: () -> Unit,
) {
    val info = model.info
    var jumping by rememberSaveable(identity) { mutableStateOf(false) }
    var choosingView by rememberSaveable(identity) { mutableStateOf(false) }
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
        ReaderContentWithNotes(model, identity, Modifier.fillMaxSize(), fullscreen, onCloseSearch) { contentModifier ->
            Box(contentModifier.onSizeChanged { model.setRenderWidth(it.width) }) {
                when {
                    model.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator()
                            TextButton(onClick = { model.cancelLoading(identity) }) { Text("Cancel") }
                        }
                    }
                    info == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        TextButton(onClick = onHome) { Text("Library") }
                    }
                    info.continuous && (!model.textMode || searching) -> ReaderTextView(
                        model.text, model.textLoading, model.effectiveSettings, searching, query, onCloseSearch,
                        Modifier.fillMaxSize().readingGestures(controls, active && !searching)
                            .semantics { contentDescription = "Markdown document" },
                        identity, model.textPosition,
                        onPosition = { model.updateTextPosition(it, identity) }, onScrollEnd = model::flushDocumentState,
                        backEnabled = active && !jumping && !choosingView, requestSearchFocus = requestSearchFocus,
                        onSearchFocusConsumed = onSearchFocusConsumed,
                        contentInsets = if (fullscreen) WindowInsets.displayCutout else WindowInsets(0, 0, 0, 0),
                        contentPadding = PaddingValues(start = 22.dp, end = 22.dp,
                            top = if (fullscreen || searching) 20.dp else 76.dp, bottom = if (fullscreen) 20.dp else 84.dp),
                        onScrollDirection = { if (active) controls.scroll(it) }, markdown = model.markdown)
                    model.textMode -> ReaderMotionLayer(
                        active = active && !searching && !jumping && !choosingView,
                        onBack = { model.choosePageView(model.horizontalPages, identity) },
                        modifier = Modifier.fillMaxSize(), animateEntrance = false,
                    ) {
                        ReaderTextView(model.text, model.textLoading, model.effectiveSettings, searching, query, onCloseSearch,
                            Modifier.fillMaxSize().readingGestures(controls, active && !searching),
                            identity, model.textPosition,
                            onPosition = { model.updateTextPosition(it, identity) }, onScrollEnd = model::flushDocumentState,
                            backEnabled = active && !jumping && !choosingView, requestSearchFocus = requestSearchFocus,
                            onSearchFocusConsumed = onSearchFocusConsumed,
                            contentInsets = if (fullscreen) WindowInsets.displayCutout else WindowInsets(0, 0, 0, 0),
                            contentPadding = PaddingValues(start = 22.dp, end = 22.dp,
                                top = if (fullscreen || searching) 20.dp else 76.dp, bottom = if (fullscreen) 20.dp else 84.dp),
                            onScrollDirection = { if (active) controls.scroll(it) })
                    }
                    else -> ReaderPages(identity, info.pageCount, model.anchorPage, model.pageFraction, model.viewport,
                        model.pagePositionRevision, model.pageImages, model.pageAspects,
                        onVisiblePages = { model.requestVisiblePages(it, identity) },
                        onPosition = { page, fraction -> model.updatePagePosition(page, fraction, identity) },
                        onCurrentPage = { model.updateCurrentPage(it, identity) },
                        onViewportChange = { model.updateViewport(it, identity) }, onGestureEnd = model::flushDocumentState,
                        modifier = Modifier.fillMaxSize(), normalEdgePadding = if (fullscreen) 16.dp else 80.dp,
                        paper = Color(DocumentColors.palette(model.effectiveSettings).background), horizontal = model.horizontalPages,
                        failedPages = model.failedPages, onRetryPage = { model.retryPage(it, identity) },
                        onReadText = if (info.textAvailable || info.reflowable) ({ action { model.chooseTextView(identity) } }) else null,
                        onScrollDirection = { if (active) controls.scroll(it) }, onTap = { if (active) controls.toggle() })
                }

                ReaderControlsOverlay(controls.shown) {
                    Box(Modifier.fillMaxSize().then(if (fullscreen) Modifier.safeDrawingPadding() else Modifier)
                        .onFocusChanged { controls.focused = it.hasFocus }.focusGroup()) {
                        if (!searching) Column(Modifier.align(Alignment.TopCenter).fillMaxWidth()
                            .readingGestures(controls, active, toggle = false)) {
                            Row(Modifier.fillMaxWidth().readerBackground().heightIn(min = 56.dp).padding(horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                if (model.textMode) ReaderAction(Icons.AutoMirrored.Outlined.ArrowBack, "Return to pages") {
                                    action { model.choosePageView(model.horizontalPages, identity) }
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
                            if (model.exporting) Row(Modifier.fillMaxWidth().readerBackground().padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                Text("Exporting…", Modifier.weight(1f).padding(12.dp), style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = model::cancelExport) { Text("Cancel") }
                            }
                        }
                        if (info != null) ReaderTools(model, searching, fullscreen,
                            { action(onSearch) }, { action(onContents) }, { action(onNotes) }, { action(onAppearance) },
                            ::chooseView, { action(onFullscreen) }, onJump = { action { jumping = true } },
                            modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp)
                                .readingGestures(controls, active, toggle = false))
                    }
                }
            }
        }
    }
    if (choosingView && info != null) ReaderViewSheet(model, onDismiss = { choosingView = false },
        onPageView = { horizontal -> onCloseSearch(); model.choosePageView(horizontal, identity) },
        onTextView = { onCloseSearch(); model.chooseTextView(identity) })
    if (jumping && info != null) ReaderPageJump(model.page, info.pageCount, onDismiss = { jumping = false }) {
        model.goToPage(it, identity)
    }
}

/** Overlay visibility never changes the document's viewport or saved scroll position. */
@Composable
private fun ReaderControlsOverlay(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(visible, modifier = Modifier.fillMaxSize().zIndex(1f).graphicsLayer(),
        enter = fadeIn(tween(120)), exit = fadeOut(tween(120))) { content() }
}

@Composable
private fun ReaderTools(
    model: ReaderModel, searching: Boolean, fullscreen: Boolean,
    onSearch: () -> Unit, onContents: () -> Unit, onNotes: () -> Unit, onAppearance: () -> Unit,
    onView: () -> Unit, onFullscreen: () -> Unit, onJump: () -> Unit, modifier: Modifier = Modifier,
) {
    val info = model.info ?: return
    Row(modifier.then(if (searching) Modifier else Modifier.widthIn(max = 560.dp).fillMaxWidth())
        .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = .96f), MaterialTheme.shapes.large).readerTexture()
        .padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (!searching) Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                if (!model.textMode && info.pageCount > 1) ReaderTooltip("Jump to page") {
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
    fullscreen: Boolean = false, onNavigate: () -> Unit = {}, content: @Composable (Modifier) -> Unit,
) {
    ReaderNotesLayout(model.notesVisible && model.notes.hasContent, modifier,
        notes = { paneModifier ->
            val portrait = LocalConfiguration.current.orientation != Configuration.ORIENTATION_LANDSCAPE
            ReaderDocumentPane(model, identity, paneModifier.then(if (fullscreen) Modifier.windowInsetsPadding(
                WindowInsets.safeDrawing.only(if (portrait) WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
                    else WindowInsetsSides.Horizontal + WindowInsetsSides.Vertical)) else Modifier),
                onNavigate = onNavigate)
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
        var minimumHeight by remember(showNotes, maxWidth, maxHeight, density) { mutableStateOf(0.dp) }
        if (!showNotes) content(Modifier.fillMaxSize())
        else if (landscape) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.Absolute.Left) {
            Box(Modifier.weight(1f).fillMaxHeight()) { content(Modifier.fillMaxSize()) }
            VerticalDivider()
            notes(Modifier.fillMaxHeight().width(paneWidth))
        } else Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) { content(Modifier.fillMaxSize()) }
            HorizontalDivider()
            // Grow only while open: note-dependent page probes cannot bounce between two pane sizes.
            notes(Modifier.fillMaxWidth().heightIn(min = minimumHeight, max = paneHeight).onSizeChanged {
                minimumHeight = maxOf(minimumHeight, with(density) { it.height.toDp() }.coerceAtMost(paneHeight))
            })
        }
    }
}

@Composable
private fun ReaderPageJump(page: Int, count: Int, onDismiss: () -> Unit, onPage: (Int) -> Unit) {
    var entry by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        val value = (page + 1).toString()
        mutableStateOf(TextFieldValue(value, TextRange(0, value.length)))
    }
    var focused by rememberSaveable { mutableStateOf(false) }
    val requester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
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
        LaunchedEffect(Unit) {
            if (!focused) {
                withFrameNanos { }
                if (requester.requestFocus()) { focused = true; keyboard?.show() }
            }
        }
    }
}

@Composable
private fun ReaderLicensesScreen(onBack: () -> Unit) {
    val assets = LocalContext.current.assets
    val notices by produceState<List<String>?>(null, assets) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val names = assets.list("").orEmpty().filter { it.endsWith("notices.txt") || it.endsWith("license.txt") }
                splitText(names.sorted().joinToString("\n\n") { assets.open(it).bufferedReader().use { reader -> reader.readText() } })
            }.getOrElse { listOf("Could not load license notices.") }
        }
    }
    Column(Modifier.fillMaxSize().readerBackground()) {
        ReaderHeader("Open-source licenses", onBack = onBack)
        val loaded = notices
        if (loaded == null) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else SelectionContainer(Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                itemsIndexed(loaded) { _, text -> Text(text, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

/** Retained destinations cannot own focus, keys, touches or accessibility navigation. */
internal fun Modifier.readerInput(active: Boolean): Modifier = then(
    if (active) Modifier else Modifier.clearAndSetSemantics { hideFromAccessibility() }
).focusProperties { canFocus = active }
.onPreviewKeyEvent { !active }
.pointerInput(active) {
    if (!active) awaitPointerEventScope {
        while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
    }
}
