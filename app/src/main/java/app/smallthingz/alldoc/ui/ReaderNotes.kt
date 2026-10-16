package app.smallthingz.alldoc.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.smallthingz.alldoc.engine.DocumentInfo
import app.smallthingz.alldoc.engine.DocumentNotes
import app.smallthingz.alldoc.engine.DocumentNote

internal val DocumentNotes.hasContent get() = entries.isNotEmpty() || warnings.isNotEmpty() || truncated

/** The caller supplies bounded space beside or below the document. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReaderDocumentPane(
    model: ReaderModel,
    identity: Long,
    modifier: Modifier = Modifier,
    onNavigate: () -> Unit = {},
) {
    val info = model.info ?: return
    LaunchedEffect(identity, info.supportsNotes) { model.loadNotes(identity) }
    ReaderNotesContent(info, model.notes, model.notesLoading, model.page, identity, modifier) { page ->
        model.goToPage(page, identity)
        model.choosePageView(model.horizontalPages, identity)
        onNavigate()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReaderNotesContent(
    info: DocumentInfo, notes: DocumentNotes, loading: Boolean, currentPage: Int,
    identity: Long, modifier: Modifier = Modifier, onPage: (Int) -> Unit,
) {
    var currentPageOnly by rememberSaveable(identity) { mutableStateOf(true) }
    val hasOtherPages = remember(notes.entries, currentPage) { notes.entries.any { it.page != null && it.page != currentPage } }
    val entries = remember(notes.entries, currentPageOnly, currentPage) {
        if (currentPageOnly) notes.entries.filter { it.page == null || it.page == currentPage }
        else notes.entries
    }
    val filter: (@Composable () -> Unit)? = if (hasOtherPages) ({
        ReaderOverlayAction(Icons.Outlined.FilterList, if (currentPageOnly) "Show all notes" else "Show current page notes",
            selected = !currentPageOnly, onClick = { currentPageOnly = !currentPageOnly })
    }) else null
    val listState = rememberLazyListState()
    LaunchedEffect(identity, currentPageOnly, if (currentPageOnly) currentPage else null) {
        listState.scrollToItem(0)
    }
    Surface(modifier.semantics { paneTitle = "Notes and comments" },
        color = MaterialTheme.colorScheme.surfaceContainer) {
        LazyColumn(Modifier.fillMaxWidth(), state = listState,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when {
                !info.supportsNotes -> item(key = "unsupported") {
                    Text("Notes and comments are not supported for this format.",
                        style = MaterialTheme.typography.bodyMedium)
                }
                loading -> item(key = "loading") {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("Loading…")
                    }
                }
                else -> {
                    if (entries.isEmpty() && (filter != null || notes.warnings.isEmpty() && !notes.truncated)) item(key = "empty") {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            if (notes.warnings.isEmpty() && !notes.truncated) Text(
                                if (notes.entries.isEmpty()) "No notes." else "No notes on this page.", Modifier.weight(1f))
                            else Spacer(Modifier.weight(1f))
                            filter?.invoke()
                        }
                    }
                    itemsIndexed(entries) { index, note ->
                        ReaderPaneNote(note, currentPage, info.pageCount, currentPageOnly, onPage,
                            if (index == 0) filter else null)
                    }
                    if (notes.truncated) item(key = "truncated") {
                        Text("Some notes were omitted.", style = MaterialTheme.typography.bodySmall)
                    }
                    itemsIndexed(notes.warnings, key = { index, _ -> "warning-$index" }) { _, warning ->
                        Text(warning, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun ReaderPaneNote(
    note: DocumentNote, currentPage: Int, pageCount: Int, currentPageOnly: Boolean,
    onPage: (Int) -> Unit, action: (@Composable () -> Unit)?,
) {
    val page = note.page?.takeIf { it in 0 until pageCount }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val label = listOfNotNull(
            note.author.takeIf { it.isNotBlank() },
            note.location.takeIf { it.isNotBlank() && (!currentPageOnly && page == currentPage || page == null ||
                it != "Page ${page + 1}" && it != "Slide ${page + 1}") },
        ).joinToString(" · ")
        if (label.isNotBlank()) Row(Modifier.fillMaxWidth()) {
            Box(Modifier.weight(1f).heightIn(min = if (action != null) 48.dp else 0.dp), contentAlignment = Alignment.CenterStart) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            action?.invoke()
        }
        Row(Modifier.fillMaxWidth()) {
            Box(Modifier.weight(1f).heightIn(min = if (label.isBlank() && action != null) 48.dp else 0.dp),
                contentAlignment = Alignment.CenterStart) {
                SelectionContainer { Text(note.text, style = MaterialTheme.typography.bodyMedium) }
            }
            if (label.isBlank()) action?.invoke()
        }
        if (page != null && page != currentPage) TextButton(onClick = { onPage(page) }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("Page ${page + 1}")
        }
        HorizontalDivider()
    }
}
