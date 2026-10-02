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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

@Composable
internal fun AlldocTheme(content: @Composable () -> Unit) {
  val dark = isSystemInDarkTheme()
  val colors =
    if (dark)
      darkColorScheme(
        primary = Color(0xFFD6EDAE),
        onPrimary = Color(0xFF203722),
        primaryContainer = Color(0xFF314737),
        onPrimaryContainer = Color(0xFFDFF3B8),
        secondary = Color(0xFFA5CEC4),
        secondaryContainer = Color(0xFF2C4038),
        onSecondaryContainer = Color(0xFFD1E8DD),
        background = Color(0xFF121A18),
        onBackground = Color(0xFFE2E8DE),
        surface = Color(0xFF18221F),
        onSurface = Color(0xFFE2E8DE),
        surfaceContainerLow = Color(0xFF1E2B25),
        surfaceContainer = Color(0xFF27352D),
        surfaceContainerHigh = Color(0xFF2C3A30),
        surfaceContainerHighest = Color(0xFF344337),
        outline = Color(0xFF87938B),
      )
    else
      lightColorScheme(
        primary = Color(0xFF235A49),
        secondary = Color(0xFF45685C),
        background = Color(0xFFF6F7F2),
        surface = Color(0xFFFCFDF8),
        primaryContainer = Color(0xFFD9EDBD),
        onPrimaryContainer = Color(0xFF163923),
        secondaryContainer = Color(0xFFE7ECE3),
        onSecondaryContainer = Color(0xFF244338),
        onSurface = Color(0xFF202821),
        onBackground = Color(0xFF202821),
        surfaceContainerLow = Color(0xFFEFF2EB),
        surfaceContainer = Color(0xFFE8EEE5),
        surfaceContainerHigh = Color(0xFFE5EBE1),
        surfaceContainerHighest = Color(0xFFDEE5DA),
        onSurfaceVariant = Color(0xFF495349),
        outline = Color(0xFF87918A),
      )
  MaterialTheme(colorScheme = colors, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun Workspace(
  model: DocumentModel,
  incomingUri: Uri? = null,
  consumeIncoming: () -> Unit = {},
) {
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
  var showExport by remember { mutableStateOf(false) }
  var menuOpen by remember { mutableStateOf(false) }
  var pdfZoom by rememberSaveable { mutableFloatStateOf(1f) }
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
        navigationIcon = {
          if (hasDocument)
            TextButton(
              onClick = { showHome = true },
              modifier = Modifier.semantics { contentDescription = "Back to library" },
            ) {
              Text("‹", fontSize = 30.sp)
            }
        },
        title = {
          Column {
            Text(
              if (hasDocument) model.name else "alldoc",
              fontWeight = if (hasDocument) FontWeight.SemiBold else FontWeight.Black,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              style =
                if (hasDocument) MaterialTheme.typography.titleMedium
                else MaterialTheme.typography.headlineSmall,
            )
            if (hasDocument)
              Text(
                if (model.edits.isEmpty()) "Original protected"
                else if (model.hasExportedCurrentDraft) "Copy exported" else "Unsaved changes",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
              )
          }
        },
        actions = {
          if (hasDocument && model.pdfPages == 0)
            TextButton(
              onClick = {
                searchVisible = !searchVisible
                if (searchVisible) {
                  formattedPreview = false
                  editMode = false
                } else search = ""
              }
            ) {
              Text("Find")
            }
          Box {
            TextButton(
              onClick = { menuOpen = true },
              modifier = Modifier.semantics { contentDescription = "More options" },
            ) {
              Text("⋮", fontSize = 26.sp)
            }
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
                  text = { Text("Open another document") },
                  onClick = {
                    menuOpen = false
                    chooseDocument()
                  },
                  enabled = !model.busy,
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
      if (hasDocument)
        Surface(tonalElevation = 2.dp) {
          Row(
            Modifier.fillMaxWidth()
              .navigationBarsPadding()
              .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            if (model.edits.isNotEmpty())
              TextButton(
                onClick = { exitDialog = true },
                enabled = !model.busy,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
              ) {
                Text("Discard")
              }
            else
              TextButton(
                onClick = { chooseDocument() },
                enabled = !model.busy,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
              ) {
                Text("Open")
              }
            Button(
              onClick = { showExport = true },
              enabled = !model.busy,
              modifier = Modifier.weight(1f).heightIn(min = 48.dp),
              contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            ) {
              Text(
                if (model.edits.isNotEmpty()) "Save copy" else "Export copy",
                maxLines = 2,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
              )
            }
          }
        }
    },
  ) { padding ->
    Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
      if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
      model.error?.let { message -> Notice(message, true) { model.error = null } }
      if (!hasDocument) {
        LazyColumn(
          Modifier.fillMaxHeight()
            .widthIn(max = 840.dp)
            .fillMaxWidth()
            .align(Alignment.CenterHorizontally),
          contentPadding = PaddingValues(20.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          item {
            Button(
              onClick = { chooseDocument() },
              enabled = !model.busy,
              modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
              shape = RoundedCornerShape(14.dp),
            ) {
              Text("Open a document", style = MaterialTheme.typography.titleMedium)
            }
          }
          if (model.name.isNotEmpty()) {
            item {
              Text(
                if (model.edits.isNotEmpty()) "Current draft" else "Continue reading",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(top = 12.dp),
              )
            }
            item {
              Surface(
                onClick = { showHome = false },
                modifier = Modifier.semantics { contentDescription = "Resume current document" },
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
              ) {
                Column(
                  Modifier.fillMaxWidth().padding(18.dp),
                  verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                  Text(
                    model.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                  )
                  Text(
                    if (model.edits.isNotEmpty())
                      if (model.hasExportedCurrentDraft) "Copy exported · draft retained"
                      else "Unsaved changes · recoverable on this device"
                    else "Resume your local copy",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                  )
                }
              }
            }
          }
          item {
            Text(
              "Recent documents",
              style = MaterialTheme.typography.labelLarge,
              color = MaterialTheme.colorScheme.secondary,
              modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
            )
          }
          if (model.recentDocuments.isEmpty())
            item {
              Column(
                Modifier.fillMaxWidth().padding(vertical = 52.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                Text("No recent documents", style = MaterialTheme.typography.titleMedium)
                Text(
                  "Files you open will appear here.",
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.secondary,
                )
              }
            }
          items(model.recentDocuments, key = { it.uri }) { recent ->
            Surface(
              onClick = {
                if (model.sourceUri?.toString() == recent.uri) showHome = false
                else if (model.edits.isNotEmpty()) recentConfirmation = Uri.parse(recent.uri)
                else {
                  showHome = false
                  formattedPreview = true
                  editMode = false
                  search = ""
                  model.open(Uri.parse(recent.uri))
                }
              },
              enabled = !model.busy,
              color = MaterialTheme.colorScheme.surface,
              shape = RoundedCornerShape(12.dp),
            ) {
              Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Text(
                  recent.name.substringAfterLast('.', "FILE").uppercase().take(5),
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.primary,
                  modifier = Modifier.width(42.dp),
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                  Text(
                    recent.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                  )
                  Text(
                    if (System.currentTimeMillis() - recent.openedAt < 60_000) "Just now"
                    else
                      android.text.format.DateUtils.getRelativeTimeSpanString(
                          recent.openedAt,
                          System.currentTimeMillis(),
                          android.text.format.DateUtils.MINUTE_IN_MILLIS,
                        )
                        .toString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                  )
                }
              }
            }
          }
        }
      } else if (model.pdfPages > 0) {
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
            enabled = model.pdfPage + 1 < model.pdfPages && !model.pdfLoading && !model.busy,
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
        if (model.session?.extension != "xlsx")
          Row(
            Modifier.fillMaxWidth()
              .horizontalScroll(rememberScrollState())
              .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            if (model.session?.extension == "docx")
              FilterChip(
                selected = formattedPreview,
                onClick = {
                  formattedPreview = true
                  editMode = false
                  searchVisible = false
                  search = ""
                },
                label = { Text("Layout") },
              )
            FilterChip(
              selected = !editMode && (model.session?.extension != "docx" || !formattedPreview),
              onClick = {
                formattedPreview = false
                editMode = false
              },
              label = {
                Text(
                  if (model.session?.extension == "docx" || model.session?.extension == "pptx")
                    "Text"
                  else "Read"
                )
              },
            )
            FilterChip(
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
        when {
          model.session?.extension == "docx" && formattedPreview ->
            DocxPreview(model.session!!, model.edits.toMap(), Modifier.weight(1f).fillMaxWidth())
          model.session?.extension == "xlsx" ->
            SheetCanvas(
              model.session!!,
              model.edits.toMap(),
              search,
              model::edit,
              Modifier.weight(1f).fillMaxWidth(),
              enabled = !model.busy,
            )
          editMode ->
            EditorCanvas(
              blocks,
              model.edits.toMap(),
              !model.busy,
              model::edit,
              Modifier.weight(1f).fillMaxWidth(),
            )
          else -> ReadingCanvas(blocks, model.edits.toMap(), Modifier.weight(1f).fillMaxWidth())
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
      dismissButton = { TextButton(onClick = { pendingOpen = false }) { Text("Keep editing") } },
    )
  if (exitDialog)
    AlertDialog(
      onDismissRequest = { exitDialog = false },
      title = { Text("Discard unsaved edits?") },
      text = {
        Text("Your original file is unchanged. Unsaved changes in this session will be lost.")
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
      dismissButton = { TextButton(onClick = { exitDialog = false }) { Text("Keep editing") } },
    )
  if (showExport)
    AlertDialog(
      onDismissRequest = { showExport = false },
      title = { Text("Export a separate copy") },
      text = {
        Text(
          if (model.edits.isEmpty())
            "An unchanged export preserves the original file bytes. Choose a new filename; provider writes may not be atomic."
          else if (model.session?.blocks?.firstOrNull()?.id == "text")
            "Save your edits as a new file in the original text encoding. The original stays untouched."
          else
            "Save as a new file. Layout may change and formulas are not recalculated; review the copy in your office app. The original stays untouched."
        )
      },
      confirmButton = {
        TextButton(
          onClick = {
            showExport = false
            val name = model.name
            val i = name.lastIndexOf('.')
            save.launch(
              if (i > 0) name.substring(0, i) + "-edited" + name.substring(i) else "$name-edited"
            )
          }
        ) {
          Text("Choose destination")
        }
      },
      dismissButton = { TextButton(onClick = { showExport = false }) { Text("Cancel") } },
    )
  if (showLicenses) {
    val assets = LocalContext.current.assets
    val text = remember {
      listOf("NOTICE.txt", "Apache-2.0.txt", "JSZip-MIT.txt").joinToString("\n\n") {
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
  if (showCapabilities)
    AlertDialog(
      onDismissRequest = { showCapabilities = false },
      title = { Text("Clear format boundaries") },
      text = {
        Column(
          Modifier.verticalScroll(rememberScrollState()),
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Text("PDF: page rendering, zoom and copy export. No text editing or annotations yet.")
          Text(
            "DOCX: formatted preview and existing text-run editing. Pagination and complex shapes may differ from Word. PPTX: text-run content only; no slide-layout preview."
          )
          Text(
            "XLSX: sparse worksheet grid of existing cells; edits become text. Formulas are read-only, cached values are not recalculated."
          )
          Text(
            "ODT / ODP: existing paragraph text. ODS: string-cell text only; numeric and formula cells are read-only."
          )
          Text(
            "TXT / MD / CSV / TSV / JSON / XML / YAML: plain-text source editing, not a spreadsheet or rendered-markup view."
          )
          Text(
            "DOC / XLS / PPT / RTF and encrypted documents: unsupported, original never changed."
          )
          Text(
            "Limits: 32 MiB input, 64 MiB expanded archive, 8 MiB per archive member, 12,000 content blocks, 2 MiB editable plain text."
          )
          Text(
            "Draft edits are saved in app-private crash recovery. Export creates a separate document you can keep outside alldoc. Force termination during a write may lose the latest keystrokes."
          )
        }
      },
      confirmButton = { TextButton(onClick = { showCapabilities = false }) { Text("Got it") } },
    )
}

@Composable
private fun Notice(message: String, error: Boolean, dismiss: () -> Unit) {
  Surface(
    color =
      if (error) MaterialTheme.colorScheme.errorContainer
      else MaterialTheme.colorScheme.secondaryContainer
  ) {
    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
      Text(message, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
      TextButton(onClick = dismiss) { Text("Dismiss") }
    }
  }
}
