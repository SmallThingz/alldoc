package org.smallthingz.alldoc

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private var incomingUri by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        incomingUri = intent?.data
        setContent {
            AlldocTheme {
                val model: DocumentModel = viewModel()
                Workspace(model, incomingUri) {
                    incomingUri = null
                    intent?.data = null
                }
            }
        }
    }

    public override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingUri = intent.data
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun Workspace(
    model: DocumentModel,
    incomingUri: Uri? = null,
    consumeIncoming: () -> Unit = {},
) {
    val largeText = LocalDensity.current.fontScale > 1.3f
    var controlsVisible by rememberSaveable { mutableStateOf(false) }
    var revealEpoch by remember { mutableIntStateOf(0) }
    var overlayHeight by remember { mutableIntStateOf(0) }
    var presentationMode by rememberSaveable { mutableStateOf(PresentationMode.Pages) }
    var previewWarnings by remember { mutableStateOf(emptyList<String>()) }
    var showDocumentDetails by remember { mutableStateOf(false) }
    val interactions = remember { DocumentInteractionState() }
    val viewportFocus = remember { FocusRequester() }
    val recommendedTimeout =
        LocalAccessibilityManager.current?.calculateRecommendedTimeoutMillis(
            4500,
            containsIcons = true,
            containsText = true,
            containsControls = true,
        ) ?: 4500L
    var overlayHasFocus by remember { mutableStateOf(false) }
    val accessibilityService =
        LocalContext.current.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE)
            as android.view.accessibility.AccessibilityManager
    var touchExploration by remember {
        mutableStateOf(accessibilityService.isTouchExplorationEnabled)
    }
    DisposableEffect(accessibilityService) {
        val listener =
            android.view.accessibility.AccessibilityManager.TouchExplorationStateChangeListener {
                touchExploration = it
            }
        accessibilityService.addTouchExplorationStateChangeListener(listener)
        onDispose { accessibilityService.removeTouchExplorationStateChangeListener(listener) }
    }
    val activity = LocalActivity.current
    var showHome by rememberSaveable { mutableStateOf(incomingUri == null) }
    var search by rememberSaveable { mutableStateOf("") }
    var searchVisible by rememberSaveable { mutableStateOf(false) }
    var editMode by rememberSaveable { mutableStateOf(false) }
    var pendingOpen by remember { mutableStateOf(false) }
    var exitDialog by remember { mutableStateOf(false) }
    var incomingConfirmation by remember { mutableStateOf<Uri?>(null) }
    var recentConfirmation by remember { mutableStateOf<Uri?>(null) }
    var showCapabilities by remember { mutableStateOf(false) }
    var showLicenses by remember { mutableStateOf(false) }
    var showRecovered by remember { mutableStateOf(false) }
    var archiveToExportId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingArchiveUri by rememberSaveable { mutableStateOf<String?>(null) }
    var showExport by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var pdfFormMode by rememberSaveable { mutableStateOf(false) }
    var pdfZoom by rememberSaveable { mutableFloatStateOf(1f) }
    var activeSheet by
        remember(model.session) {
            mutableStateOf(model.session?.sheets?.firstOrNull()?.name.orEmpty())
        }
    var confirmCellRevert by remember(model.session) { mutableStateOf(false) }
    var selectedCell by
        remember(model.session, activeSheet) {
            mutableStateOf<org.smallthingz.alldoc.core.DocumentEngine.Block?>(null)
        }
    var slideNavigationRequest by remember { mutableIntStateOf(0) }
    var activeSlide by
        rememberSaveable(model.name, model.sourceUri?.toString()) { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    val searchFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val hasDocument = model.name.isNotEmpty() && !showHome
    SideEffect {
        interactions.onTextEdited = {
            controlsVisible = false
            searchVisible = false
            search = ""
        }
    }
    LaunchedEffect(controlsVisible) {
        if (!controlsVisible) {
            searchVisible = false
            search = ""
        }
    }
    fun revealControls() {
        controlsVisible = true
        revealEpoch++
    }
    LaunchedEffect(hasDocument, model.session, model.name) {
        controlsVisible = false
        searchVisible = false
        menuOpen = false
        if (hasDocument) viewportFocus.requestFocus()
    }
    DisposableEffect(activity, hasDocument) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        if (window != null) {
            WindowCompat.setDecorFitsSystemWindows(window, !hasDocument)
            controller?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (hasDocument) controller?.hide(WindowInsetsCompat.Type.systemBars())
            else controller?.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
    LaunchedEffect(
        controlsVisible,
        revealEpoch,
        searchVisible,
        menuOpen,
        showExport,
        showDocumentDetails,
        model.busy,
        overlayHasFocus,
        touchExploration,
    ) {
        if (
            controlsVisible &&
                !searchVisible &&
                !menuOpen &&
                !showExport &&
                !showDocumentDetails &&
                !model.busy &&
                !overlayHasFocus &&
                !touchExploration
        ) {
            delay(recommendedTimeout)
            controlsVisible = false
        }
    }
    LaunchedEffect(searchVisible, hasDocument, model.pdfPages) {
        if (searchVisible && hasDocument && model.pdfPages == 0) searchFocus.requestFocus()
    }
    val open =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let {
                search = ""
                searchVisible = false
                editMode = false
                showHome = false
                model.open(it)
            }
        }
    val save =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/octet-stream")
        ) { uri ->
            uri?.let(model::export)
        }
    val saveRecovered =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/zip")
        ) { uri ->
            if (uri == null) archiveToExportId = null else pendingArchiveUri = uri.toString()
        }
    LaunchedEffect(pendingArchiveUri, model.busy, model.recoveredDrafts.size) {
        val destination = pendingArchiveUri
        if (destination != null && !model.busy) {
            val archive = model.recoveredDrafts.firstOrNull { it.id == archiveToExportId }
            pendingArchiveUri = null
            archiveToExportId = null
            if (archive != null) model.exportRecoveredDraft(archive, Uri.parse(destination))
            else
                model.error =
                    "The recovered draft is not available yet. Retry from Recovered drafts. The chosen destination may be empty."
        }
    }
    fun chooseDocument() {
        if (model.edits.isNotEmpty()) pendingOpen = true else open.launch(arrayOf("*/*"))
    }
    LaunchedEffect(incomingUri, model.busy) {
        if (incomingUri != null && !model.busy) {
            exitDialog = false
            showExport = false
            pendingOpen = false
            menuOpen = false
            showCapabilities = false
            recentConfirmation = null
            if (model.edits.isNotEmpty()) incomingConfirmation = incomingUri
            else {
                showHome = false
                searchVisible = false
                search = ""
                editMode = false
                model.open(incomingUri)
                consumeIncoming()
            }
        }
    }
    LaunchedEffect(model.status, controlsVisible, hasDocument) {
        if (!hasDocument || controlsVisible)
            model.status?.let { message ->
                snackbar.showSnackbar(message)
                if (model.status == message) model.status = null
            }
    }
    BackHandler(hasDocument) {
        if (searchVisible) {
            searchVisible = false
            search = ""
        } else if (controlsVisible) controlsVisible = false else showHome = true
    }
    val header: @Composable () -> Unit = {
        TopAppBar(
            expandedHeight = 60.dp,
            windowInsets =
                if (hasDocument) WindowInsets(0, 0, 0, 0) else TopAppBarDefaults.windowInsets,
            colors =
                TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
            navigationIcon = {
                if (hasDocument) IconAction("Back to library", Glyph.Back) { showHome = true }
                else
                    Box(
                        Modifier.padding(start = 20.dp, end = 8.dp)
                            .size(30.dp)
                            .background(
                                MaterialTheme.colorScheme.primary,
                                RoundedCornerShape(8.dp),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        AppIcon(
                            Glyph.Document,
                            Modifier.size(20.dp),
                            MaterialTheme.colorScheme.onPrimary,
                        )
                    }
            },
            title = {
                Column {
                    Text(
                        if (hasDocument) model.name else "alldoc",
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style =
                            if (hasDocument) MaterialTheme.typography.titleMedium
                            else MaterialTheme.typography.titleLarge,
                    )
                    if (hasDocument && !largeText)
                        Text(
                            if (model.edits.isEmpty()) "Original protected"
                            else if (model.hasExportedCurrentDraft) "Copy exported"
                            else "Unsaved changes",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                }
            },
            actions = {
                if (hasDocument)
                    IconAction("Hide document controls", Glyph.Close) {
                        controlsVisible = false
                        searchVisible = false
                    }
                if (
                    hasDocument &&
                        model.pdfPages == 0 &&
                        model.session?.slides.isNullOrEmpty() &&
                        model.session?.extension != "rtf"
                )
                    IconButton(
                        modifier = Modifier.semantics { contentDescription = "Find" },
                        onClick = {
                            searchVisible = !searchVisible
                            if (searchVisible) {
                                if (model.session?.extension == "docx") editMode = true
                            } else search = ""
                        },
                    ) {
                        AppIcon(Glyph.Search)
                    }
                Box {
                    IconAction("More options", Glyph.More) { menuOpen = true }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Format support") },
                            onClick = {
                                menuOpen = false
                                showCapabilities = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Open-source licenses") },
                            onClick = {
                                menuOpen = false
                                showLicenses = true
                            },
                        )
                        if (hasDocument)
                            DropdownMenuItem(
                                text = { Text("Document details") },
                                onClick = {
                                    menuOpen = false
                                    showDocumentDetails = true
                                },
                            )
                        if (hasDocument)
                            DropdownMenuItem(
                                text = { Text("Export copy") },
                                enabled = !model.busy,
                                onClick = {
                                    menuOpen = false
                                    showExport = true
                                },
                                leadingIcon = { AppIcon(Glyph.Save) },
                            )
                        if (hasDocument)
                            DropdownMenuItem(
                                text = { Text("Open another document") },
                                onClick = {
                                    menuOpen = false
                                    chooseDocument()
                                },
                                enabled = !model.busy,
                            )
                        if (model.recoveredDrafts.isNotEmpty())
                            DropdownMenuItem(
                                text = { Text("Recovered drafts") },
                                onClick = {
                                    menuOpen = false
                                    showRecovered = true
                                },
                            )
                        if (model.edits.isNotEmpty())
                            DropdownMenuItem(
                                text = { Text("Discard edits") },
                                onClick = {
                                    menuOpen = false
                                    exitDialog = true
                                },
                                enabled = !model.busy,
                            )
                    }
                }
            },
        )
    }
    val session = model.session
    val sourceText = session?.blocks?.singleOrNull()?.id == "text"
    val hasSlides = session?.slides?.isNotEmpty() == true
    val blocks =
        session?.blocks.orEmpty().filter {
            search.isBlank() ||
                (model.edits[it.id] ?: it.original).contains(search, true) ||
                it.section.contains(search, true) ||
                it.label.contains(search, true)
        }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets =
            if (hasDocument) WindowInsets(0, 0, 0, 0) else ScaffoldDefaults.contentWindowInsets,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { if (!hasDocument) header() },
    ) { padding ->
        Box(
            Modifier.fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .onPreviewKeyEvent { event ->
                    if (
                        hasDocument &&
                            event.type == KeyEventType.KeyUp &&
                            (event.key == Key.F10 || event.key == Key.Menu)
                    ) {
                        revealControls()
                        true
                    } else if (
                        hasDocument &&
                            hasDocument &&
                            event.type == KeyEventType.KeyUp &&
                            event.key == Key.Escape &&
                            controlsVisible
                    ) {
                        controlsVisible = false
                        true
                    } else false
                }
        ) {
            if (!hasDocument)
                Column(Modifier.fillMaxSize()) {
                    model.error?.let { Notice(it, true) { model.error = null } }
                    LibraryScreen(
                        model,
                        { chooseDocument() },
                        { showHome = false },
                        { recent ->
                            if (model.edits.isNotEmpty()) recentConfirmation = Uri.parse(recent.uri)
                            else {
                                showHome = false
                                editMode = false
                                search = ""
                                searchVisible = false
                                model.open(Uri.parse(recent.uri))
                            }
                        },
                        { showCapabilities = true },
                    )
                }
            else {
                CompositionLocalProvider(LocalDocumentInteraction provides interactions) {
                    Box(
                        Modifier.fillMaxSize()
                            .testTag("Document viewport")
                            .focusRequester(viewportFocus)
                            .focusable()
                            .semantics {
                                customActions =
                                    listOf(
                                        CustomAccessibilityAction(
                                            if (controlsVisible) "Hide document controls"
                                            else "Show document controls"
                                        ) {
                                            if (controlsVisible) controlsVisible = false
                                            else revealControls()
                                            true
                                        }
                                    )
                            }
                            .immersiveTapObserver(
                                resetKey =
                                    listOf(
                                        model.name,
                                        session,
                                        presentationMode,
                                        editMode,
                                        pdfFormMode,
                                    ),
                                isScrollInProgress = {
                                    interactions.moving() || interactions.selecting()
                                },
                                allowChildHandledTap = { !interactions.selecting() },
                                onReveal = { revealControls() },
                            )
                    ) {
                        when {
                            model.pdfPages > 0 && pdfFormMode ->
                                PdfFormCanvas(
                                    model.pdfFormPage,
                                    model.edits.toMap(),
                                    !model.busy && !model.pdfLoading,
                                    model::edit,
                                    Modifier.fillMaxSize().imePadding(),
                                )
                            model.pdfPages > 0 ->
                                ImmersivePdfPage(
                                    model,
                                    pdfZoom,
                                    { pdfZoom = it },
                                    Modifier.fillMaxSize(),
                                )
                            sourceText ->
                                SourceCanvas(
                                    model.edits[session!!.blocks.first().id]
                                        ?: session.blocks.first().original,
                                    session.extension,
                                    true,
                                    !model.busy,
                                    { model.edit("text", it) },
                                    Modifier.fillMaxSize().imePadding(),
                                    query = search,
                                    obscuredTopPixels = if (controlsVisible) overlayHeight else 0,
                                )
                            session?.extension == "rtf" ->
                                RtfEditingCanvas(
                                    blocks,
                                    model.edits.toMap(),
                                    !model.busy,
                                    model::editBatch,
                                    { model.error = it },
                                    Modifier.fillMaxSize().imePadding(),
                                )
                            hasSlides && !editMode ->
                                PresentationCanvas(
                                    session!!,
                                    model.edits.toMap(),
                                    presentationMode,
                                    activeSlide,
                                    { activeSlide = it },
                                    { previewWarnings = it },
                                    navigationRequest = slideNavigationRequest,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            session?.extension == "docx" && !editMode ->
                                DocxPreview(session, model.edits.toMap(), Modifier.fillMaxSize())
                            session?.extension in setOf("xlsx", "ods", "fods") ->
                                SheetCanvas(
                                    session!!,
                                    model.edits.toMap(),
                                    search,
                                    model::edit,
                                    Modifier.fillMaxSize().imePadding(),
                                    enabled = !model.busy,
                                    resetVersion = model.editorResetVersion,
                                    currentSheet = activeSheet,
                                    navigationVisible = false,
                                    onSelection = { selectedCell = it },
                                )
                            editMode || session?.blocks?.any { it.editable } == true ->
                                EditorCanvas(
                                    if (hasSlides)
                                        session!!
                                            .slides[
                                                activeSlide.coerceIn(0, session.slides.lastIndex)]
                                            .blocks
                                    else blocks,
                                    model.edits.toMap(),
                                    !model.busy,
                                    model::edit,
                                    Modifier.fillMaxSize().imePadding(),
                                    sans = hasSlides,
                                )
                            else ->
                                ReadingCanvas(blocks, model.edits.toMap(), Modifier.fillMaxSize())
                        }
                    }
                }
                if (controlsVisible) {
                    Surface(
                        Modifier.align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .testTag("Document controls")
                            .onSizeChanged { overlayHeight = it.height }
                            .onFocusChanged { overlayHasFocus = it.hasFocus }
                            .focusGroup(),
                        color =
                            MaterialTheme.colorScheme.surface.copy(
                                alpha = if (searchVisible || selectedCell != null) 1f else .96f
                            ),
                        shadowElevation = 4.dp,
                    ) {
                        Column {
                            header()
                            if (searchVisible)
                                OutlinedTextField(
                                    value = search,
                                    onValueChange = { search = it },
                                    label = { Text("Find content") },
                                    singleLine = true,
                                    modifier =
                                        Modifier.fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 4.dp)
                                            .focusRequester(searchFocus),
                                    trailingIcon = {
                                        IconAction("Close search", Glyph.Close) {
                                            searchVisible = false
                                            search = ""
                                        }
                                    },
                                )
                            Row(
                                Modifier.fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                if (hasSlides) {
                                    FilterChip(
                                        selected =
                                            presentationMode == PresentationMode.Pages && !editMode,
                                        onClick = {
                                            presentationMode = PresentationMode.Pages
                                            editMode = false
                                            controlsVisible = false
                                        },
                                        label = { Text("Pages") },
                                    )
                                    FilterChip(
                                        selected =
                                            presentationMode == PresentationMode.Slides &&
                                                !editMode,
                                        onClick = {
                                            presentationMode = PresentationMode.Slides
                                            editMode = false
                                            controlsVisible = false
                                        },
                                        label = { Text("Slides") },
                                    )
                                    if (session!!.blocks.any { it.editable })
                                        TextButton(
                                            onClick = {
                                                editMode = !editMode
                                                controlsVisible = false
                                            }
                                        ) {
                                            Text(if (editMode) "View slides" else "Edit text")
                                        }
                                    IconAction("Previous slide", Glyph.Back, activeSlide > 0) {
                                        activeSlide--
                                        slideNavigationRequest++
                                        revealEpoch++
                                    }
                                    Text(
                                        "${activeSlide+1} / ${session.slides.size}",
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                    IconAction(
                                        "Next slide",
                                        Glyph.Next,
                                        activeSlide < session.slides.lastIndex,
                                    ) {
                                        activeSlide++
                                        slideNavigationRequest++
                                        revealEpoch++
                                    }
                                } else if (model.pdfPages > 0) {
                                    TextButton(
                                        onClick = {
                                            pdfFormMode = !pdfFormMode
                                            controlsVisible = false
                                        }
                                    ) {
                                        Text(if (pdfFormMode) "View pages" else "Fill form")
                                    }
                                    IconAction(
                                        "Previous page",
                                        Glyph.Back,
                                        model.pdfPage > 0 && !model.pdfLoading,
                                    ) {
                                        model.render(model.pdfPage - 1)
                                    }
                                    Text(
                                        "${model.pdfPage+1} / ${model.pdfPages}",
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                    IconAction(
                                        "Next page",
                                        Glyph.Next,
                                        model.pdfPage + 1 < model.pdfPages && !model.pdfLoading,
                                    ) {
                                        model.render(model.pdfPage + 1)
                                    }
                                    TextButton(
                                        onClick = { pdfZoom = if (pdfZoom == 1f) 2f else 1f }
                                    ) {
                                        Text("${(pdfZoom*100).toInt()}%")
                                    }
                                } else if (session?.extension in setOf("xlsx", "ods", "fods")) {
                                    session!!.sheets.forEach { sheet ->
                                        FilterChip(
                                            selected = sheet.name == activeSheet,
                                            onClick = {
                                                activeSheet = sheet.name
                                                controlsVisible = false
                                            },
                                            label = { Text(sheet.name) },
                                        )
                                    }
                                } else if (session?.extension == "docx")
                                    TextButton(
                                        onClick = {
                                            editMode = !editMode
                                            controlsVisible = false
                                        }
                                    ) {
                                        Text(if (editMode) "View layout" else "Edit text")
                                    }
                                if (session?.editable == false)
                                    Text(
                                        "Read-only in compact engine",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                            }
                            if (
                                session?.extension in setOf("xlsx", "ods", "fods") &&
                                    selectedCell != null
                            ) {
                                val cell = selectedCell!!
                                Column(
                                    Modifier.fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 8.dp)
                                ) {
                                    Text(
                                        "Cell ${blockRange(cell)}",
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                    if (cell.editable)
                                        DocumentTextField(
                                            value = model.edits[cell.id] ?: cell.original,
                                            onValueChange = { model.edit(cell.id, it) },
                                            enabled = !model.busy,
                                            maxLines = 3,
                                            cursorBrush =
                                                androidx.compose.ui.graphics.SolidColor(
                                                    MaterialTheme.colorScheme.primary
                                                ),
                                            textStyle =
                                                MaterialTheme.typography.bodyLarge.copy(
                                                    color = MaterialTheme.colorScheme.onSurface
                                                ),
                                            modifier =
                                                Modifier.fillMaxWidth()
                                                    .heightIn(min = 48.dp)
                                                    .semantics {
                                                        contentDescription = "Cell value"
                                                    },
                                        )
                                    else
                                        Text(
                                            cell.original.take(4000),
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End,
                                    ) {
                                        if (model.edits.containsKey(cell.id))
                                            TextButton(onClick = { confirmCellRevert = true }) {
                                                Text("Revert")
                                            }
                                        TextButton(
                                            onClick = {
                                                controlsVisible = false
                                                focusManager.clearFocus()
                                            }
                                        ) {
                                            Text("Done")
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (!searchVisible)
                        Surface(
                            Modifier.align(Alignment.BottomEnd)
                                .imePadding()
                                .navigationBarsPadding()
                                .padding(16.dp),
                            shape = RoundedCornerShape(12.dp),
                            shadowElevation = 4.dp,
                            color = MaterialTheme.colorScheme.surface.copy(alpha = .96f),
                        ) {
                            Button(
                                onClick = { showExport = true },
                                enabled = !model.busy,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.padding(4.dp),
                            ) {
                                AppIcon(Glyph.Save, tint = MaterialTheme.colorScheme.onPrimary)
                                Spacer(Modifier.width(8.dp))
                                Text(if (model.edits.isNotEmpty()) "Save copy" else "Export copy")
                            }
                        }
                }
                if (model.busy)
                    LinearProgressIndicator(Modifier.align(Alignment.TopCenter).fillMaxWidth())
                model.error?.let { message ->
                    Box(Modifier.align(Alignment.TopCenter)) {
                        Notice(message, true) { model.error = null }
                    }
                }
            }
        }
    }
    if (confirmCellRevert && selectedCell != null)
        AlertDialog(
            onDismissRequest = { confirmCellRevert = false },
            title = { Text("Revert this cell?") },
            text = { Text("Restore the original value. Other edits are kept.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        selectedCell?.let { model.edit(it.id, it.original) }
                        confirmCellRevert = false
                    }
                ) {
                    Text("Revert cell")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmCellRevert = false }) { Text("Keep editing") }
            },
        )
    if (showDocumentDetails)
        AlertDialog(
            onDismissRequest = { showDocumentDetails = false },
            title = { Text("Document details") },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(model.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        session?.description ?: PdfTools.FORM_LIMITATION,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    (previewWarnings + session?.warnings.orEmpty()).distinct().forEach {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDocumentDetails = false }) { Text("Done") }
            },
        )
    recentConfirmation?.let { uri ->
        AlertDialog(
            onDismissRequest = { recentConfirmation = null },
            title = { Text("Leave this draft?") },
            text = {
                Text("Export your changes before opening another file. The original is unchanged.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        recentConfirmation = null
                        showHome = false
                        editMode = false
                        search = ""
                        model.open(uri)
                    }
                ) {
                    Text("Open document")
                }
            },
            dismissButton = {
                TextButton(onClick = { recentConfirmation = null }) { Text("Keep editing") }
            },
        )
    }
    incomingConfirmation?.let { uri ->
        AlertDialog(
            onDismissRequest = {
                incomingConfirmation = null
                consumeIncoming()
            },
            title = { Text("Open the incoming document?") },
            text = {
                Text(
                    "This replaces your current draft. Export your edits first if you want to keep them. Your original file is unchanged."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        incomingConfirmation = null
                        consumeIncoming()
                        showHome = false
                        searchVisible = false
                        search = ""
                        editMode = false
                        model.open(uri)
                    }
                ) {
                    Text("Open document")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        incomingConfirmation = null
                        consumeIncoming()
                    }
                ) {
                    Text("Keep this draft")
                }
            },
        )
    }
    if (pendingOpen)
        AlertDialog(
            onDismissRequest = { pendingOpen = false },
            title = { Text("Leave these edits?") },
            text = {
                Text(
                    "Opening another document discards unsaved edits after it successfully opens. Export a copy first if you want to keep them."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingOpen = false
                        open.launch(arrayOf("*/*"))
                    }
                ) {
                    Text("Choose document")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingOpen = false }) { Text("Keep editing") }
            },
        )
    if (exitDialog)
        AlertDialog(
            onDismissRequest = { exitDialog = false },
            title = { Text("Discard unsaved edits?") },
            text = {
                Text(
                    "Your original file is unchanged. Unsaved changes in this session will be lost."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        exitDialog = false
                        model.discardEdits()
                        selectedCell = null
                        confirmCellRevert = false
                    }
                ) {
                    Text("Discard edits")
                }
            },
            dismissButton = {
                TextButton(onClick = { exitDialog = false }) { Text("Keep editing") }
            },
        )
    if (showExport)
        AlertDialog(
            onDismissRequest = { showExport = false },
            title = { Text("Export a separate copy") },
            text = {
                Text(
                    if (model.edits.isEmpty())
                        "An unchanged export preserves the original file bytes. Choose a new filename; provider writes may not be atomic."
                    else if (model.pdfPages > 0)
                        "Save filled text fields in a separate PDF. Review the saved copy before sharing. The original stays untouched."
                    else if (model.session?.blocks?.firstOrNull()?.id == "text")
                        "Save your edits as a new file in the original text encoding. The original stays untouched."
                    else
                        "Save as a new file. Layout may change and formulas are not recalculated; review the copy in your office app. The original stays untouched."
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !model.busy,
                    onClick = {
                        if (model.busy) return@TextButton
                        showExport = false
                        val name = model.name
                        val i = name.lastIndexOf('.')
                        save.launch(
                            if (i > 0) name.substring(0, i) + "-edited" + name.substring(i)
                            else "$name-edited"
                        )
                    },
                ) {
                    Text("Choose destination")
                }
            },
            dismissButton = { TextButton(onClick = { showExport = false }) { Text("Cancel") } },
        )
    if (showRecovered)
        AlertDialog(
            onDismissRequest = { showRecovered = false },
            title = { Text("Recovered drafts") },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        "Older drafts stay separate so text is never applied to the wrong paragraph, cell or slide. Each archive contains the unchanged original and all saved text edits.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    model.recoveredDrafts.forEach { draft ->
                        Text(draft.name, style = MaterialTheme.typography.titleSmall)
                        TextButton(
                            enabled = !model.busy,
                            onClick = {
                                archiveToExportId = draft.id
                                saveRecovered.launch(
                                    draft.name.substringBeforeLast('.') + "-recovered-draft.zip"
                                )
                            },
                        ) {
                            Text("Save recovered draft")
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showRecovered = false }) { Text("Done") } },
        )
    if (showLicenses) {
        val assets = LocalContext.current.assets
        val text = remember {
            (listOf("NOTICE.txt", "Apache-2.0.txt", "JSZip-MIT.txt") +
                    assets.list("licenses/apache-poi").orEmpty().map { "apache-poi/$it" })
                .joinToString("\n\n") {
                    assets.open("licenses/$it").bufferedReader().use { reader -> reader.readText() }
                }
        }
        AlertDialog(
            onDismissRequest = { showLicenses = false },
            title = { Text("Open-source licenses") },
            text = {
                Text(
                    text,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = { TextButton(onClick = { showLicenses = false }) { Text("Done") } },
        )
    }
    if (showCapabilities) FormatSupport { showCapabilities = false }
}

@Composable
private fun Notice(message: String, error: Boolean, dismiss: () -> Unit) {
    Surface(
        color =
            if (error) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.secondaryContainer
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                message,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = dismiss) { Text("Dismiss") }
        }
    }
}

@Composable
private fun ModeTab(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    enabled: Boolean = true,
) {
    Column(
        Modifier.width(IntrinsicSize.Min)
            .widthIn(min = 72.dp)
            .clickable(enabled = enabled, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.heightIn(min = 44.dp).padding(horizontal = 14.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            ProvideTextStyle(
                MaterialTheme.typography.labelLarge.copy(
                    color =
                        if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                )
            ) {
                label()
            }
        }
        Box(
            Modifier.height(2.dp)
                .fillMaxWidth()
                .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
        )
    }
}
