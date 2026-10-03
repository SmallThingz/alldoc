package org.smallthingz.alldoc

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.smallthingz.alldoc.core.DocumentEngine

/** Virtualized rows and a bounded column window avoid giant layouts on sparse workbooks. */
@Composable
fun SheetCanvas(
    session: DocumentEngine.Session,
    edits: Map<String, String>,
    query: String,
    onEdit: (String, String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    resetVersion: Int = 0,
    currentSheet: String? = null,
    navigationVisible: Boolean = true,
    onSelection: ((DocumentEngine.Block?)->Unit)? = null,
) {
    val sheets =
        remember(session) {
            session.sheets.map { it.name }.ifEmpty { session.blocks.map { it.section }.distinct() }
        }
    var localSheet by remember(session) { mutableStateOf(sheets.firstOrNull().orEmpty()) }
    val sheet = currentSheet?.takeIf { it in sheets } ?: localSheet
    var selected by remember(session, resetVersion) { mutableStateOf<DocumentEngine.Block?>(null) }
    var initialValue by remember(session, resetVersion) { mutableStateOf("") }
    var confirmRevert by remember(session, resetVersion) { mutableStateOf(false) }
    var firstColumn by remember(session, sheet, query) { mutableIntStateOf(0) }
    fun address(block: DocumentEngine.Block) = block.label.substringBefore(' ')
    val cells =
        remember(session, sheet, query, edits) {
            session.blocks.filter {
                it.section == sheet &&
                    (query.isBlank() ||
                        it.label.contains(query, true) ||
                        (edits[it.id] ?: it.original).contains(query, true))
            }
        }
    val columns =
        remember(cells) {
            cells
                .map { address(it).takeWhile(Char::isLetter) }
                .filter { it.isNotBlank() }
                .distinct()
                .sortedWith(compareBy<String> { it.length }.thenBy { it })
        }
    val rows =
        remember(cells) {
            cells.groupBy { address(it).dropWhile(Char::isLetter).toIntOrNull() ?: 0 }.toSortedMap()
        }
    BackHandler(selected != null && onSelection == null) {
        if (confirmRevert) confirmRevert = false else selected = null
    }
    LaunchedEffect(sheet, resetVersion) {
        selected = null
        confirmRevert = false
        onSelection?.invoke(null)
    }
    val horizontal = rememberScrollState()
    val vertical = rememberLazyListState()
    RegisterDocumentScroll(horizontal)
    RegisterDocumentScroll(vertical)
    Column(modifier) {
        val current = selected
        if (current != null && onSelection == null) {
            CellEditor(
                current,
                sheet,
                edits[current.id] ?: current.original,
                { onEdit(current.id, it) },
                enabled,
                { selected = null },
                { confirmRevert = true },
                (edits[current.id] ?: current.original) != initialValue,
                confirmRevert,
                {
                    onEdit(current.id, initialValue)
                    confirmRevert = false
                    selected = null
                },
                { confirmRevert = false },
            )
        } else if (navigationVisible) {
            Row(
                Modifier.fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                sheets.forEach { name ->
                    TextButton(
                        onClick = { localSheet = name },
                        shape = RoundedCornerShape(6.dp),
                        colors =
                            ButtonDefaults.textButtonColors(
                                containerColor =
                                    if (name == sheet) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surface
                            ),
                    ) {
                        Text(name, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
        if (
            navigationVisible &&
                current == null &&
                cells.any { it.rowRepeat > 1 || it.columnRepeat > 1 }
        )
            Text(
                "Repeated ranges are shown once",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            // A bounded window keeps hostile wide sheets below Android's layout-size limits.
            // Within the window, all header/cell rows share one natural horizontal scroll position.
            val count = 64
            val start = firstColumn.coerceAtMost((columns.size - 1).coerceAtLeast(0))
            val visible = columns.drop(start).take(count)
            Column(Modifier.fillMaxSize()) {
                if (columns.size > count)
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconAction("Previous columns", Glyph.Back, start > 0) {
                            firstColumn = (start - count).coerceAtLeast(0)
                        }
                        Text(
                            "${visible.firstOrNull().orEmpty()}–${visible.lastOrNull().orEmpty()}",
                            style = MaterialTheme.typography.labelSmall,
                        )
                        IconAction("Next columns", Glyph.Next, start + count < columns.size) {
                            firstColumn = start + count
                        }
                    }
                Row(
                    Modifier.fillMaxWidth()
                        .height(36.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                ) {
                    Box(
                        Modifier.width(48.dp).fillMaxHeight(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("#", style = MaterialTheme.typography.labelSmall)
                    }
                    Row(Modifier.weight(1f).horizontalScroll(horizontal)) {
                        visible.forEach { column ->
                            Box(
                                Modifier.width(140.dp).fillMaxHeight(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    column,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }
                if (rows.isEmpty())
                    Text(
                        if (query.isBlank()) "This sheet has no populated cells"
                        else "No matching cells",
                        Modifier.padding(24.dp),
                    )
                LazyColumn(state = vertical, modifier = Modifier.weight(1f).fillMaxWidth()) {
                    items(rows.entries.toList(), key = { it.key }) { (row, blocks) ->
                        val byColumn = blocks.associateBy { address(it).takeWhile(Char::isLetter) }
                        Row(
                            Modifier.fillMaxWidth()
                                .height(
                                    if (blocks.any { it.rowRepeat > 1 || it.columnRepeat > 1 })
                                        64.dp
                                    else 48.dp
                                )
                        ) {
                            Box(
                                Modifier.width(48.dp)
                                    .fillMaxHeight()
                                    .background(MaterialTheme.colorScheme.surfaceContainerLow),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(row.toString(), style = MaterialTheme.typography.labelSmall)
                            }
                            Row(Modifier.weight(1f).horizontalScroll(horizontal)) {
                                visible.forEach { column ->
                                    val block = byColumn[column]
                                    val text = block?.let { edits[it.id] ?: it.original }.orEmpty()
                                    val edited = block != null && edits.containsKey(block.id)
                                    Surface(
                                        modifier =
                                            Modifier.width(140.dp)
                                                .fillMaxHeight()
                                                .padding(end = 1.dp, bottom = 1.dp)
                                                .then(
                                                    if (block != null && enabled)
                                                        Modifier.semantics {
                                                                contentDescription =
                                                                    "${blockRange(block)}: ${text.take(2000)}"
                                                                stateDescription =
                                                                    if (edited) "Edited"
                                                                    else if (!block.editable)
                                                                        "Read-only"
                                                                    else "Editable"
                                                            }
                                                            .clickable {
                                                                if (selected?.id != block.id) {
                                                                    selected = block
                                                                    onSelection?.invoke(block)
                                                                    initialValue = text
                                                                    confirmRevert = false
                                                                }
                                                            }
                                                    else Modifier
                                                ),
                                        color =
                                            if (edited || selected?.id == block?.id) MaterialTheme.colorScheme.primaryContainer
                                            else MaterialTheme.colorScheme.surface,
                                    ) {
                                        Box(
                                            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                            contentAlignment =
                                                if (
                                                    block != null &&
                                                        edits[block.id] == null &&
                                                        block.valueType in
                                                            setOf(
                                                                "float",
                                                                "currency",
                                                                "percentage",
                                                                "n",
                                                                "number",
                                                            )
                                                )
                                                    Alignment.CenterEnd
                                                else Alignment.CenterStart,
                                        ) {
                                            Column {
                                                Text(
                                                    text,
                                                    maxLines =
                                                        if (
                                                            block != null &&
                                                                (block.rowRepeat > 1 ||
                                                                    block.columnRepeat > 1)
                                                        )
                                                            1
                                                        else 2,
                                                    overflow = TextOverflow.Ellipsis,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                )
                                                if (
                                                    block != null &&
                                                        (block.rowRepeat > 1 ||
                                                            block.columnRepeat > 1)
                                                )
                                                    Text(
                                                        blockRange(block),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color =
                                                            MaterialTheme.colorScheme
                                                                .onSurfaceVariant,
                                                    )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CellEditor(
    block: DocumentEngine.Block,
    sheet: String,
    input: String,
    onInput: (String) -> Unit,
    enabled: Boolean,
    apply: () -> Unit,
    cancel: () -> Unit,
    changed: Boolean,
    confirmRevert: Boolean,
    revert: () -> Unit,
    keepEditing: () -> Unit,
) {
    val largeText = LocalDensity.current.fontScale > 1.3f
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(
            Modifier.fillMaxWidth()
                .heightIn(max = 260.dp)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                if (largeText) "Cell ${blockRange(block)}" else "$sheet · ${blockRange(block)}",
                style = MaterialTheme.typography.titleSmall,
            )
            if (confirmRevert) {
                Text("Revert this cell?", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Restore the value from when you opened this cell. Other draft edits stay unchanged."
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = keepEditing) { Text("Keep editing") }
                    Button(onClick = revert, enabled = enabled) { Text("Revert cell") }
                }
            } else if (block.editable) {
                DocumentTextField(
                    value = input,
                    onValueChange = onInput,
                    enabled = enabled,
                    minLines = 1,
                    maxLines = 4,
                    modifier =
                        Modifier.fillMaxWidth()
                            .heightIn(min = 48.dp, max = 112.dp)
                            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                            .padding(12.dp)
                            .semantics { contentDescription = "Cell value" },
                    textStyle =
                        MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                )
                if (!largeText)
                    Text(
                        "Text value · formulas are not recalculated",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
            } else {
                Text(block.original.take(8000), style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (block.rowRepeat > 1 || block.columnRepeat > 1)
                        "Repeated range ${blockRange(block)} · read-only"
                    else if (block.formula.isNotEmpty()) "Formula · read-only"
                    else "Read-only cell",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (!confirmRevert) {
                    if (block.editable && changed)
                        TextButton(onClick = cancel, enabled = enabled) { Text("Revert") }
                    Button(onClick = apply, enabled = enabled) { Text("Done") }
                }
            }
        }
    }
}

internal fun blockRange(block: DocumentEngine.Block): String {
    val start = block.label.substringBefore(' ')
    if (block.rowRepeat <= 1 && block.columnRepeat <= 1) return start
    var col = block.column + block.columnRepeat - 1
    var label = ""
    while (col > 0) {
        col--
        label = ('A'.code + col % 26).toChar() + label
        col /= 26
    }
    return "$start:$label${block.row + block.rowRepeat - 1}"
}
