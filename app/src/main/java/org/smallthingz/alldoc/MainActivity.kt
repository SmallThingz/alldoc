package org.smallthingz.alldoc

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

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
    var showHome by rememberSaveable { mutableStateOf(incomingUri == null) }
    var search by rememberSaveable { mutableStateOf("") }
    var searchVisible by rememberSaveable { mutableStateOf(false) }
    var formattedPreview by rememberSaveable { mutableStateOf(true) }
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
    var activeSlide by remember(model.session) { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    val searchFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val hasDocument = model.name.isNotEmpty() && !showHome
    LaunchedEffect(searchVisible, hasDocument, model.pdfPages) {
        if (searchVisible && hasDocument && model.pdfPages == 0) searchFocus.requestFocus()
        else focusManager.clearFocus()
    }
    val open =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let {
                search = ""
                searchVisible = false
                editMode = false
                showHome = false
                formattedPreview = true
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
                formattedPreview = true
                searchVisible = false
                search = ""
                editMode = false
                model.open(incomingUri)
                consumeIncoming()
            }
        }
    }
    LaunchedEffect(model.status) {
        model.status?.let { message ->
            snackbar.showSnackbar(message)
            if (model.status == message) model.status = null
        }
    }
    BackHandler(hasDocument) {
        if (searchVisible) {
            searchVisible = false
            search = ""
        } else showHome = true
    }
    Scaffold(
        modifier = Modifier.imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                expandedHeight = 60.dp,
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
                    if (hasDocument && model.pdfPages == 0)
                        IconButton(
                            modifier = Modifier.semantics { contentDescription = "Find" },
                            onClick = {
                                searchVisible = !searchVisible
                                if (searchVisible) {
                                    formattedPreview = false
                                    editMode = false
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
        },
        bottomBar = {
            if (hasDocument && model.edits.isNotEmpty())
                Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 4.dp) {
                    Row(
                        Modifier.fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (largeText) Spacer(Modifier.weight(1f))
                        else
                            Column(Modifier.weight(1f)) {
                                Text(
                                    if (model.hasExportedCurrentDraft) "Copy exported"
                                    else "Draft changes",
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                Text(
                                    "Original unchanged",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        Button(
                            onClick = { showExport = true },
                            enabled = !model.busy,
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        ) {
                            Text("Save copy")
                        }
                    }
                }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            model.error?.let { message -> Notice(message, true) { model.error = null } }
            if (!hasDocument) {
                LibraryScreen(
                    model,
                    { chooseDocument() },
                    { showHome = false },
                    { recent ->
                        if (model.edits.isNotEmpty()) recentConfirmation = Uri.parse(recent.uri)
                        else {
                            showHome = false
                            formattedPreview = true
                            editMode = false
                            search = ""
                            searchVisible = false
                            model.open(Uri.parse(recent.uri))
                        }
                    },
                    { showCapabilities = true },
                )
            } else if (model.pdfPages > 0) {
                Row(
                    Modifier.fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 12.dp)
                ) {
                    ModeTab(
                        selected = !pdfFormMode,
                        onClick = { pdfFormMode = false },
                        label = { Text("Pages") },
                    )
                    ModeTab(
                        selected = pdfFormMode,
                        onClick = { pdfFormMode = true },
                        label = { Text("Fill form") },
                    )
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = { model.render(model.pdfPage - 1) },
                        enabled = model.pdfPage > 0 && !model.pdfLoading && !model.busy,
                    ) {
                        Text("Previous")
                    }
                    Text("${model.pdfPage+1} / ${model.pdfPages}", modifier = Modifier.weight(1f))
                    TextButton(
                        onClick = { model.render(model.pdfPage + 1) },
                        enabled =
                            model.pdfPage + 1 < model.pdfPages && !model.pdfLoading && !model.busy,
                    ) {
                        Text("Next")
                    }
                    TextButton(
                        onClick = { pdfZoom = if (pdfZoom < 2f) 2f else 1f },
                        modifier = Modifier.semantics { contentDescription = "Change PDF zoom" },
                    ) {
                        Text("${(pdfZoom*100).toInt()}%")
                    }
                }
                if (model.pdfLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (pdfFormMode)
                    PdfFormCanvas(
                        model.pdfFormPage,
                        model.edits.toMap(),
                        !model.busy && !model.pdfLoading,
                        model::edit,
                        Modifier.weight(1f).fillMaxWidth(),
                    )
                else
                    BoxWithConstraints(
                        Modifier.weight(1f)
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    ) {
                        val pageWidth = maxWidth * pdfZoom
                        Box(
                            Modifier.fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .horizontalScroll(rememberScrollState())
                        ) {
                            model.pdfBitmap?.let { bitmap ->
                                Image(
                                    bitmap.asImageBitmap(),
                                    contentDescription = "Page ${model.pdfPage+1} of ${model.name}",
                                    modifier = Modifier.width(pageWidth).padding(12.dp),
                                )
                            }
                        }
                    }
            } else {
                if (model.session?.extension !in setOf("xlsx", "ods", "fods"))
                    Row(
                        Modifier.fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (
                            model.session?.extension == "docx" ||
                                model.session?.slides?.isNotEmpty() == true
                        )
                            ModeTab(
                                selected = formattedPreview,
                                onClick = {
                                    formattedPreview = true
                                    editMode = false
                                    searchVisible = false
                                    search = ""
                                },
                                label = {
                                    Text(
                                        if (model.session?.extension == "docx") "Layout"
                                        else "Slides"
                                    )
                                },
                            )
                        ModeTab(
                            selected =
                                !editMode &&
                                    (!(model.session?.extension == "docx" ||
                                        model.session?.slides?.isNotEmpty() == true) ||
                                        !formattedPreview),
                            onClick = {
                                formattedPreview = false
                                editMode = false
                            },
                            label = {
                                Text(
                                    if (
                                        model.session?.extension == "docx" ||
                                            model.session?.extension == "pptx"
                                    )
                                        "Text"
                                    else "Read"
                                )
                            },
                        )
                        if (model.session?.blocks?.any { it.editable } == true)
                            ModeTab(
                                selected = editMode,
                                onClick = {
                                    formattedPreview = false
                                    editMode = true
                                },
                                enabled = !model.busy,
                                label = { Text("Edit") },
                            )
                    }
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
                            TextButton(
                                onClick = {
                                    searchVisible = false
                                    search = ""
                                }
                            ) {
                                Text("Close")
                            }
                        },
                    )
                val blocks =
                    model.session?.blocks.orEmpty().filter {
                        search.isBlank() ||
                            (model.edits[it.id] ?: it.original).contains(search, true) ||
                            it.label.contains(search, true) ||
                            it.section.contains(search, true)
                    }
                if (model.session?.editable == false)
                    Text(
                        "Read-only text extraction · original copy export",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                when {
                    model.session?.blocks?.singleOrNull()?.id == "text" -> {
                        val block = model.session!!.blocks.first()
                        SourceCanvas(
                            model.edits[block.id] ?: block.original,
                            model.session!!.extension,
                            editMode,
                            !model.busy,
                            { model.edit(block.id, it) },
                            Modifier.weight(1f).fillMaxWidth(),
                            query = search,
                        )
                    }
                    model.session?.slides?.isNotEmpty() == true && formattedPreview ->
                        SlideCanvas(
                            model.session!!,
                            model.edits.toMap(),
                            activeSlide,
                            { activeSlide = it },
                            Modifier.weight(1f).fillMaxWidth(),
                        )
                    model.session?.extension == "docx" && formattedPreview ->
                        DocxPreview(
                            model.session!!,
                            model.edits.toMap(),
                            Modifier.weight(1f).fillMaxWidth(),
                        )
                    model.session?.extension in setOf("xlsx", "ods", "fods") ->
                        SheetCanvas(
                            model.session!!,
                            model.edits.toMap(),
                            search,
                            model::edit,
                            Modifier.weight(1f).fillMaxWidth(),
                            enabled = !model.busy,
                            resetVersion = model.editorResetVersion,
                        )
                    editMode ->
                        Column(Modifier.weight(1f).fillMaxWidth()) {
                            val slides = model.session?.slides.orEmpty()
                            if (slides.isNotEmpty())
                                Row(
                                    Modifier.fillMaxWidth()
                                        .background(MaterialTheme.colorScheme.surface)
                                        .padding(horizontal = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    IconAction("Previous slide", Glyph.Back, activeSlide > 0) {
                                        activeSlide--
                                    }
                                    Text(
                                        "Slide ${activeSlide + 1} of ${slides.size}",
                                        style = MaterialTheme.typography.labelMedium,
                                        modifier = Modifier.weight(1f),
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    )
                                    IconAction(
                                        "Next slide",
                                        Glyph.Next,
                                        activeSlide < slides.lastIndex,
                                    ) {
                                        activeSlide++
                                    }
                                }
                            if (!largeText)
                                Text(
                                    "Edit existing text · review layout after export",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier =
                                        Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                                )
                            EditorCanvas(
                                if (slides.isNotEmpty())
                                    slides[activeSlide.coerceIn(0, slides.lastIndex)].blocks
                                else blocks,
                                model.edits.toMap(),
                                !model.busy,
                                model::edit,
                                Modifier.weight(1f).fillMaxWidth(),
                                sans = slides.isNotEmpty(),
                            )
                        }
                    else ->
                        ReadingCanvas(
                            blocks,
                            model.edits.toMap(),
                            Modifier.weight(1f).fillMaxWidth(),
                        )
                }
            }
        }
    }
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
                        formattedPreview = true
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
                        formattedPreview = true
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
