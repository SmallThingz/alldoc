package org.smallthingz.alldoc

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.smallthingz.alldoc.core.DocumentEngine

internal fun nativeDocumentFont(name: String): FontFamily =
    when {
        listOf("courier", "consolas", "mono", "cascadia").any { name.contains(it, true) } ->
            FontFamily.Monospace
        listOf("times", "cambria", "georgia", "garamond", "baskerville", "serif").any {
            name.contains(it, true)
        } && !name.contains("sans", true) -> FontFamily.Serif
        else -> FontFamily.SansSerif
    }

internal fun styledDocumentText(
    blocks: List<DocumentEngine.Block>,
    edits: Map<String, String>,
    scale: Double = 1.0,
    defaultSize: Double = 16.0,
): AnnotatedString = buildAnnotatedString {
    val paragraphs = blocks.groupBy { it.paragraphId }.values.toList()
    paragraphs.forEachIndexed { index, paragraph ->
        val paragraphStart = length
        paragraph.forEach { block ->
            val start = length
            append(edits[block.id] ?: block.original)
            val style = block.style
            val sourceSize = style.fontSize.takeIf { it.isFinite() && it > 0 } ?: defaultSize
            val decorations = buildList {
                if (style.underline) add(TextDecoration.Underline)
                if (style.strike) add(TextDecoration.LineThrough)
            }
            addStyle(
                SpanStyle(
                    color = style.color?.let { Color(it) } ?: Color.Unspecified,
                    fontSize = (sourceSize * scale).coerceIn(8.0, 64.0).sp,
                    fontFamily = nativeDocumentFont(style.fontFamily),
                    fontWeight = if (style.bold) FontWeight.Bold else FontWeight.Normal,
                    fontStyle = if (style.italic) FontStyle.Italic else FontStyle.Normal,
                    textDecoration =
                        if (decorations.isEmpty()) TextDecoration.None
                        else TextDecoration.combine(decorations),
                ),
                start,
                length,
            )
        }
        if (index < paragraphs.lastIndex) append('\n')
        val align =
            when (paragraph.first().style.alignment.lowercase()) {
                "ctr",
                "center" -> TextAlign.Center
                "r",
                "right",
                "end" -> TextAlign.Right
                "just",
                "justify" -> TextAlign.Justify
                else -> TextAlign.Left
            }
        val largest = paragraph.maxOf { block ->
            ((block.style.fontSize.takeIf { it.isFinite() && it > 0 } ?: defaultSize) * scale)
                .coerceIn(8.0, 64.0)
        }
        if (length > paragraphStart)
            addStyle(
                ParagraphStyle(textAlign = align, lineHeight = (largest * 1.3).sp),
                paragraphStart,
                length,
            )
    }
}

/** Reflowed rich-text reading, separate from an office page-layout claim. */
@Composable
internal fun StyledReadingCanvas(
    blocks: List<DocumentEngine.Block>,
    edits: Map<String, String>,
    modifier: Modifier = Modifier,
) {
    val paragraphs = remember(blocks) { blocks.groupBy { it.paragraphId }.values.toList() }
    Box(
        modifier.background(MaterialTheme.colorScheme.surfaceContainerLow),
        contentAlignment = Alignment.TopCenter,
    ) {
        LazyColumn(
            Modifier.padding(12.dp).widthIn(max = 760.dp).fillMaxSize().background(Color.White),
            contentPadding = PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(paragraphs, key = { it.first().paragraphId }) { paragraph ->
                SelectionContainer {
                    Text(
                        styledDocumentText(paragraph, edits, defaultSize = 16.0),
                        color = Color(0xFF182132),
                    )
                }
            }
        }
    }
}
