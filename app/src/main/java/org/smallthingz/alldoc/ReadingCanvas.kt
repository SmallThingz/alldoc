package org.smallthingz.alldoc

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.smallthingz.alldoc.core.DocumentEngine

@Composable
fun ReadingCanvas(
  blocks: List<DocumentEngine.Block>,
  edits: Map<String, String>,
  modifier: Modifier = Modifier,
) {
  val paragraphs =
    remember(blocks, edits) {
      blocks
        .groupBy { it.paragraphId }
        .values
        .flatMap { group ->
          val text = group.joinToString("") { edits[it.id] ?: it.original }
          text.chunked(8000).map { group.first().section to it }
        }
    }
  Box(
    modifier.background(MaterialTheme.colorScheme.surfaceContainerLow),
    contentAlignment = Alignment.TopCenter,
  ) {
    Surface(
      Modifier.padding(horizontal = 16.dp).widthIn(max = 760.dp).fillMaxHeight().fillMaxWidth(),
      color = MaterialTheme.colorScheme.surface,
    ) {
      LazyColumn(
        contentPadding = PaddingValues(horizontal = 28.dp, vertical = 30.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        itemsIndexed(paragraphs) { index, (section, text) ->
          if (
            section != "document.xml" &&
              section != "Document" &&
              (index == 0 || paragraphs[index - 1].first != section)
          )
            Text(
              when {
                section == "document.xml" -> "Document"
                section.startsWith("header") -> "Header"
                section.startsWith("footer") -> "Footer"
                section == "footnotes.xml" -> "Footnotes"
                section == "endnotes.xml" -> "Endnotes"
                else -> section
              },
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.secondary,
              modifier = Modifier.padding(bottom = 10.dp),
            )
          SelectionContainer {
            Text(text, fontFamily = FontFamily.Serif, fontSize = 18.sp, lineHeight = 29.sp)
          }
        }
      }
    }
  }
}
