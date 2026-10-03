package org.smallthingz.alldoc

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.smallthingz.alldoc.core.DocumentEngine

@Composable
fun EditorCanvas(
    blocks: List<DocumentEngine.Block>,
    edits: Map<String, String>,
    enabled: Boolean,
    onEdit: (String, String) -> Unit,
    modifier: Modifier = Modifier,
    sans: Boolean = false,
) {
    val paragraphs = remember(blocks) { blocks.groupBy { it.paragraphId }.values.toList() }
    Box(
        modifier.background(MaterialTheme.colorScheme.surfaceContainerLow),
        contentAlignment = Alignment.TopCenter,
    ) {
        Surface(
            Modifier.padding(horizontal = 12.dp, vertical = 12.dp)
                .widthIn(max = 760.dp)
                .fillMaxHeight()
                .fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                if (blocks.isEmpty())
                    item { Text("No matching text", style = MaterialTheme.typography.bodyMedium) }
                itemsIndexed(paragraphs, key = { _, paragraph -> paragraph.first().paragraphId }) {
                    paragraphIndex,
                    paragraph ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val section = paragraph.first().section
                        if (
                            section != "document.xml" &&
                                section != "Document" &&
                                (paragraphIndex == 0 ||
                                    paragraphs[paragraphIndex - 1].first().section != section)
                        )
                            Text(
                                section,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(bottom = 10.dp),
                            )
                        paragraph.forEachIndexed { segmentIndex, block ->
                            if (block.editable)
                                BasicTextField(
                                    value = edits[block.id] ?: block.original,
                                    onValueChange = { onEdit(block.id, it) },
                                    enabled = enabled,
                                    modifier =
                                        Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                                            contentDescription =
                                                "Edit paragraph ${paragraphIndex + 1}, segment ${segmentIndex + 1}"
                                        },
                                    textStyle =
                                        MaterialTheme.typography.bodyLarge.copy(
                                            fontFamily =
                                                if (sans) FontFamily.SansSerif
                                                else FontFamily.Serif,
                                            fontSize = 16.sp,
                                            lineHeight = 26.sp,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        ),
                                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                    minLines = if (blocks.size == 1) 12 else 1,
                                )
                            else {
                                Text(block.original, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "Read-only value",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.secondary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
