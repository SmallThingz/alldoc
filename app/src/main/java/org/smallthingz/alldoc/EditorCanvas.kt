package org.smallthingz.alldoc

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
) {
  val paragraphs = remember(blocks) { blocks.groupBy { it.paragraphId }.values.toList() }
  Box(
    modifier.background(MaterialTheme.colorScheme.surfaceContainerLow),
    contentAlignment = Alignment.TopCenter,
  ) {
    Surface(
      Modifier.padding(horizontal = 12.dp).widthIn(max = 760.dp).fillMaxHeight().fillMaxWidth(),
      color = MaterialTheme.colorScheme.surface,
    ) {
      LazyColumn(
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
      ) {
        if (blocks.isEmpty())
          item { Text("No matching text", style = MaterialTheme.typography.bodyMedium) }
        items(paragraphs, key = { it.first().paragraphId }) { paragraph ->
          Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            paragraph.forEach { block ->
              if (block.editable)
                BasicTextField(
                  value = edits[block.id] ?: block.original,
                  onValueChange = { onEdit(block.id, it) },
                  enabled = enabled,
                  modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Edit text" },
                  textStyle =
                    MaterialTheme.typography.bodyLarge.copy(
                      fontFamily = FontFamily.Serif,
                      fontSize = 18.sp,
                      lineHeight = 28.sp,
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
