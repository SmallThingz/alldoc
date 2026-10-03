package org.smallthingz.alldoc

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun SourceCanvas(
    value: String,
    extension: String,
    editing: Boolean,
    enabled: Boolean,
    onEdit: (String) -> Unit,
    modifier: Modifier = Modifier,
    query: String = "",
) {
    val code =
        extension in
            setOf("xml", "json", "yaml", "yml", "html", "htm", "csv", "tsv", "svg", "css", "js")
    Column(modifier.background(MaterialTheme.colorScheme.surface)) {
        if (!editing)
            Row(
                Modifier.fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    if (code) "${extension.uppercase()} source" else "Plain text",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "${value.count { it == '\n' } + 1} lines",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        val style =
            MaterialTheme.typography.bodyLarge.copy(
                fontFamily = if (code) FontFamily.Monospace else FontFamily.SansSerif,
                fontSize = if (code) 13.sp else 16.sp,
                lineHeight = if (code) 22.sp else 26.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
        if (editing)
            Box(
                Modifier.weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp)
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = onEdit,
                    enabled = enabled,
                    textStyle = style,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    minLines = 20,
                    modifier =
                        Modifier.fillMaxWidth().semantics {
                            contentDescription = "Edit document source"
                        },
                )
            }
        else {
            val chunks =
                remember(value, query) {
                    if (query.isBlank()) value.chunked(8000)
                    else
                        value
                            .lineSequence()
                            .mapIndexedNotNull { index, line ->
                                if (line.contains(query, true)) "${index + 1}  $line" else null
                            }
                            .toList()
                            .flatMap { it.chunked(8000) }
                }
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(24.dp),
                verticalArrangement = Arrangement.spacedBy(if (query.isBlank()) 0.dp else 10.dp),
            ) {
                if (chunks.isEmpty())
                    item { Text("No matching text", style = MaterialTheme.typography.bodyMedium) }
                items(chunks) { chunk -> SelectionContainer { Text(chunk, style = style) } }
            }
        }
    }
}
