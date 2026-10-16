package app.smallthingz.alldoc.ui

import android.net.Uri
import android.view.Gravity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.FitScreen
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.automirrored.outlined.RotateRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import app.smallthingz.alldoc.BuildConfig
import app.smallthingz.alldoc.engine.DocumentInfo
import app.smallthingz.alldoc.state.ViewportState
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

private const val AllDocRepository = "https://github.com/SmallThingz/alldoc"

/** Material owns sheet focus, dismissal and predictive Back; do not add a second handler. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderSheet(
    title: String, onDismiss: () -> Unit, onDismissCancelled: () -> Unit = {},
    showTitle: Boolean = true,
    buttons: (@Composable RowScope.(dismiss: () -> Unit) -> Unit)? = null,
    content: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val currentDismiss by rememberUpdatedState(onDismiss)
    val currentDismissCancelled by rememberUpdatedState(onDismissCancelled)
    var dismissing by remember { mutableStateOf(false) }
    val dismiss: () -> Unit = {
        if (!dismissing) {
            dismissing = true
            scope.launch {
                var closed = false
                try {
                    state.hide()
                    currentCoroutineContext().ensureActive()
                    if (!state.isVisible) {
                        closed = true
                        currentDismiss()
                    }
                } finally {
                    if (!closed) currentDismissCancelled()
                    dismissing = false
                }
            }
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, sheetMaxWidth = 560.dp,
        modifier = Modifier.semantics { paneTitle = title },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
        ReaderModalWindow()
        ReaderDialogContent(title, dismiss, Modifier.fillMaxWidth(), buttons, showTitle, content)
    }
}

/** Native modal focus and outside dismissal, with the visible panel attached to the top edge. */
@Composable
internal fun ReaderTopPanel(
    title: String, onDismiss: () -> Unit, showTitle: Boolean = true, content: @Composable ColumnScope.() -> Unit,
) {
    var closing by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = { closing = true }, properties = DialogProperties(
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false, windowTitle = title,
    )) {
        ReaderModalWindow()
        val view = LocalView.current
        DisposableEffect(view) {
            (view.parent as? DialogWindowProvider)?.window?.setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            onDispose { }
        }
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
            val availableHeight = maxHeight
            Box(Modifier.matchParentSize().clickable(remember { MutableInteractionSource() }, indication = null) {
                closing = true
            }.clearAndSetSemantics { })
            ReaderMotionLayer(active = !closing, visible = !closing, onBack = onDismiss, fromTop = true,
                modifier = Modifier.align(Alignment.TopCenter).widthIn(max = 560.dp).fillMaxWidth()) {
                Surface(shape = RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 6.dp) {
                    Column(Modifier.fillMaxWidth().heightIn(max = availableHeight).readerTexture()
                        .verticalScroll(rememberScrollState()).padding(16.dp).semantics {
                            paneTitle = title
                            dismiss { closing = true; true }
                        }, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (showTitle) Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                        content()
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReaderPanels(
    model: ReaderModel, identity: Long, panel: String,
    onDismiss: (String) -> Unit, onCloseSearch: () -> Unit,
    onExport: (ReaderModel.ExportKind) -> Unit = {}, onPrint: () -> Unit = {},
) {
    when (panel) {
        "appearance" -> ReaderSheet("Appearance", { onDismiss(panel) }, showTitle = false) { _ ->
            ReadingAppearanceContent(model.appearanceForControls, enabled = !model.loading,
                showImageTreatment = model.info?.continuous != true) {
                model.setAppearance(it, identity)
            }
            if (model.readerVisible && model.info != null && !model.textMode && model.info?.continuous != true) {
                HorizontalDivider()
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row {
                        ReaderAction(Icons.AutoMirrored.Outlined.RotateRight, "Rotate page", enabled = !model.loading) {
                            model.updateViewport(model.viewport.copy(rotation = (model.viewport.rotation + 1) % 4), identity)
                            model.flushDocumentState()
                        }
                        ReaderAction(Icons.Outlined.FitScreen, "Reset zoom and rotation", enabled = !model.loading) {
                            model.updateViewport(ViewportState(), identity)
                            model.flushDocumentState()
                        }
                    }
                    if (model.canHideMargins) FilterChip(selected = model.hideMargins,
                        onClick = { model.setHideMargins(!model.hideMargins, identity) }, enabled = !model.loading,
                        modifier = Modifier.heightIn(min = 48.dp).semantics {
                            contentDescription = if (model.hideMargins) "Show margins" else "Hide margins"
                        }, label = { Text("Trim margins") }, leadingIcon = { Icon(Icons.Outlined.Crop, null, Modifier.size(18.dp)) })
                }
            }
        }
        "export" -> model.info?.let { info ->
            ReaderTopPanel("Export", { onDismiss(panel) }) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { onDismiss(panel); onPrint() }, enabled = model.canExport,
                        modifier = Modifier.heightIn(min = 48.dp)) {
                        Icon(Icons.Outlined.Print, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Print to PDF")
                    }
                    ReaderModel.ExportKind.entries.forEach { kind ->
                        if (kind != ReaderModel.ExportKind.TEXT || info.textAvailable) {
                            val (label, icon) = when (kind) {
                                ReaderModel.ExportKind.TEXT -> "Text" to Icons.Outlined.TextFields
                                ReaderModel.ExportKind.JSON -> "JSON" to Icons.Outlined.DataObject
                                ReaderModel.ExportKind.IMAGE -> "Page image" to Icons.Outlined.Image
                            }
                            FilledTonalButton(onClick = { onDismiss(panel); onExport(kind) }, enabled = model.canExport,
                                modifier = Modifier.heightIn(min = 48.dp)) {
                                Icon(icon, null, Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(label)
                            }
                        }
                    }
                }
            }
        }
        "details" -> model.info?.let { info ->
            ReaderTopPanel("File info", { onDismiss(panel) }) {
                DocumentDetails(info, model.sizeBytes, model.source)
            }
        }
        "contents" -> ReaderSheet("Contents", { onDismiss(panel) }) { dismiss ->
            when {
                model.contentsLoading -> CircularProgressIndicator(Modifier.size(24.dp))
                model.contentsError != null -> Column {
                    Text(model.contentsError.orEmpty())
                    TextButton(onClick = { model.loadContents(identity) }) { Text("Retry") }
                }
                model.outline.isEmpty() -> Text("No contents")
                else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                    itemsIndexed(model.outline) { _, entry ->
                        TextButton(onClick = {
                            model.goToPage(entry.page, identity)
                            model.choosePageView(model.horizontalPages, identity)
                            onCloseSearch()
                            dismiss()
                        }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .padding(start = (entry.level.coerceIn(0, 4) * 12).dp)) {
                            Text(entry.title, Modifier.weight(1f), maxLines = 2)
                            Text("${entry.page + 1}", Modifier.padding(start = 12.dp))
                        }
                    }
                }
            }
        }
        "limits" -> CopyLimitsDialog(model.copySettings, model::updateCopySettings) { onDismiss(panel) }
        "about" -> ReaderTopPanel("AllDoc", { onDismiss(panel) }, showTitle = false) {
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ReaderAppIcon(Modifier.size(80.dp))
                Text("AllDoc", Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
                Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val browser = LocalUriHandler.current
            Surface(onClick = {
                try { browser.openUri(AllDocRepository) }
                catch (_: RuntimeException) { onDismiss(panel); model.showError("No browser available.") }
            }, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "GitHub repository" }) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.Code, null)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("GitHub", style = MaterialTheme.typography.titleSmall)
                        Text("SmallThingz/alldoc", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
private fun DocumentDetails(info: DocumentInfo, size: Long, source: Uri?) {
    SelectionContainer {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(info.title, style = MaterialTheme.typography.titleMedium)
            FileInfoRow("Type", info.format.uppercase())
            FileInfoRow("Size", readerBytes(size))
            if (!info.reflowable) FileInfoRow("Pages", info.pageCount.toString())
            source?.let { uri ->
                FileInfoRow(if (uri.scheme == "file") "Location" else "Source",
                    if (uri.scheme == "file") uri.path.orEmpty() else uri.authority.orEmpty())
            }
            if (info.fidelity in setOf("PREVIEW", "EMBEDDED_PREVIEW_ONLY")) Text("Embedded preview")
            info.warnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun FileInfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.width(64.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(1f))
    }
}
