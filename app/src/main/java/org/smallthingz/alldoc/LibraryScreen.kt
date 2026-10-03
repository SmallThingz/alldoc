package org.smallthingz.alldoc

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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun LibraryScreen(
    model: DocumentModel,
    open: () -> Unit,
    resume: () -> Unit,
    recent: (RecentDocument) -> Unit,
    formats: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val largeText = LocalDensity.current.fontScale > 1.3f
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("All files") }
    val matching =
        model.recentDocuments.filter {
            (filter == "All files" || fileGroup(it.name) == filter) && it.name.contains(query, true)
        }
    val currentInList = model.recentDocuments.any { it.uri == model.sourceUri?.toString() }
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            Modifier.widthIn(max = 760.dp).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 18.dp),
        ) {
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Documents", style = MaterialTheme.typography.headlineMedium)
                    }
                    if (!largeText)
                        Button(
                            onClick = open,
                            enabled = !model.busy,
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                            modifier =
                                Modifier.semantics { contentDescription = "Open a document" },
                        ) {
                            AppIcon(
                                Glyph.Open,
                                Modifier.size(18.dp),
                                MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Open file")
                        }
                }
                if (largeText)
                    Button(
                        onClick = open,
                        enabled = !model.busy,
                        shape = RoundedCornerShape(10.dp),
                        modifier =
                            Modifier.padding(top = 16.dp).semantics {
                                contentDescription = "Open a document"
                            },
                    ) {
                        Text("Open file")
                    }
                if (model.recentDocuments.isNotEmpty() || model.name.isNotEmpty()) {
                    Spacer(Modifier.height(20.dp))
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Search your files") },
                        singleLine = true,
                        leadingIcon = { AppIcon(Glyph.Search, Modifier.size(19.dp)) },
                        shape = RoundedCornerShape(12.dp),
                        colors =
                            OutlinedTextFieldDefaults.colors(
                                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                                focusedContainerColor = MaterialTheme.colorScheme.surface,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                            ),
                        modifier =
                            Modifier.fillMaxWidth().semantics {
                                contentDescription = "Search your files"
                            },
                    )
                    Row(
                        Modifier.fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(top = 14.dp, bottom = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        listOf("All files", "Documents", "PDF", "Sheets", "Slides", "Text")
                            .forEach { label ->
                                FilterChip(
                                    selected = filter == label,
                                    onClick = { filter = label },
                                    label = { Text(label) },
                                    shape = RoundedCornerShape(8.dp),
                                    border = null,
                                    colors =
                                        FilterChipDefaults.filterChipColors(
                                            containerColor = MaterialTheme.colorScheme.background,
                                            selectedContainerColor =
                                                MaterialTheme.colorScheme.primaryContainer,
                                            selectedLabelColor = MaterialTheme.colorScheme.primary,
                                        ),
                                )
                            }
                    }
                }
            }
            if (model.recentDocuments.isEmpty() && model.name.isEmpty()) {
                item { EmptyLibrary(formats) }
            } else {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Recent", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "On this device",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (
                    model.name.isNotEmpty() &&
                        !currentInList &&
                        (filter == "All files" || fileGroup(model.name) == filter) &&
                        model.name.contains(query, true)
                )
                    item {
                        DocumentRow(
                            model.name,
                            "Current draft",
                            true,
                            model.edits.isNotEmpty(),
                            resume,
                        )
                    }
                items(matching, key = { it.uri }) { document ->
                    val current = document.uri == model.sourceUri?.toString()
                    val whenOpened =
                        if (System.currentTimeMillis() - document.openedAt < 60_000) "Just now"
                        else
                            android.text.format.DateUtils.getRelativeTimeSpanString(
                                    document.openedAt,
                                    System.currentTimeMillis(),
                                    android.text.format.DateUtils.MINUTE_IN_MILLIS,
                                )
                                .toString()
                    DocumentRow(
                        document.name,
                        whenOpened,
                        current,
                        current && model.edits.isNotEmpty(),
                    ) {
                        if (current) resume() else recent(document)
                    }
                }
                if (matching.isEmpty() && (currentInList || model.name.isEmpty()))
                    item {
                        Column(
                            Modifier.fillMaxWidth().padding(vertical = 44.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            AppIcon(Glyph.Search, Modifier.size(28.dp))
                            Spacer(Modifier.height(12.dp))
                            Text("No files found", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Try a different name or file type",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 26.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        AppIcon(Glyph.Shield, Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Original files stay untouched",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DocumentRow(
    name: String,
    time: String,
    current: Boolean,
    edited: Boolean,
    click: () -> Unit,
) {
    Surface(
        onClick = click,
        color = MaterialTheme.colorScheme.surface,
        modifier =
            Modifier.fillMaxWidth()
                .then(
                    if (current)
                        Modifier.semantics { contentDescription = "Resume current document" }
                    else Modifier
                ),
        shape = RoundedCornerShape(0.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 14.dp, horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            FileBadge(name)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    name.substringBeforeLast('.', name),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${name.substringAfterLast('.', "FILE").uppercase()} · ${if(edited) "Current draft" else time}",
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        if (edited) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AppIcon(
                if (edited) Glyph.Edit else Glyph.Next,
                Modifier.size(18.dp),
                if (edited) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outline,
            )
        }
    }
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.padding(start = 70.dp),
    )
}

@Composable
private fun EmptyLibrary(formats: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 40.dp, bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(130.dp, 108.dp), contentAlignment = Alignment.Center) {
            Surface(
                Modifier.size(68.dp, 86.dp).rotate(-13f).offset(x = (-21).dp),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {}
            Surface(
                Modifier.size(72.dp, 92.dp).rotate(7f).offset(x = 17.dp),
                shape = RoundedCornerShape(8.dp),
                shadowElevation = 2.dp,
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppIcon(Glyph.Document, tint = MaterialTheme.colorScheme.primary)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.width(28.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(22.dp))
        Text(
            "No recent documents",
            style = MaterialTheme.typography.titleLarge,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Text(
            "Open a file to start reading or editing.\nIt will appear here next time.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        Spacer(Modifier.height(30.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf("file.pdf", "file.docx", "file.xlsx", "file.pptx").forEach { FileBadge(it) }
        }
        TextButton(onClick = formats, modifier = Modifier.padding(top = 10.dp)) {
            Text("Explore supported formats")
        }
        Row(Modifier.padding(top = 42.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(Glyph.Shield, Modifier.size(15.dp))
            Spacer(Modifier.width(7.dp))
            Text(
                "Works offline · original files protected",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
