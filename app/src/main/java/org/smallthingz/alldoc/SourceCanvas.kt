package org.smallthingz.alldoc

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
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
    obscuredTopPixels: Int = 0,
) {
    val editorScroll = rememberScrollState()
    val readerScroll = rememberLazyListState()
    RegisterDocumentScroll(editorScroll)
    RegisterDocumentScroll(readerScroll)
    val density = LocalDensity.current
    var viewportHeight by remember { mutableIntStateOf(0) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val searchPadding =
        if (query.isBlank()) 24.dp
        else
            with(density) {
                ((viewportHeight - obscuredTopPixels).coerceAtLeast(0) / 2)
                    .toDp()
                    .coerceAtLeast(24.dp)
            }
    LaunchedEffect(query, layout?.size, viewportHeight, obscuredTopPixels) {
        if (query.isNotBlank()) {
            val index = value.indexOf(query, ignoreCase = true)
            if (index >= 0)
                layout
                    ?.takeIf { index < it.layoutInput.text.length }
                    ?.getCursorRect(index)
                    ?.let { rectangle ->
                        // The search header overlays content; center the match in the unobscured
                        // editor.
                        withFrameNanos {}
                        val center =
                            (viewportHeight + obscuredTopPixels.coerceAtMost(viewportHeight)) / 2f
                        editorScroll.scrollTo(
                            (rectangle.center.y + with(density) { 24.dp.toPx() } - center)
                                .toInt()
                                .coerceAtLeast(0)
                        )
                    }
        }
    }
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
                    .onSizeChanged { viewportHeight = it.height }
                    .verticalScroll(editorScroll)
                    .testTag("Source scroll")
                    .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = searchPadding)
            ) {
                DocumentTextField(
                    value = value,
                    onValueChange = onEdit,
                    enabled = enabled,
                    textStyle = style,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    minLines = 20,
                    searchQuery = query,
                    visualTransformation =
                        if (query.isBlank()) VisualTransformation.None
                        else {
                            val highlight = MaterialTheme.colorScheme.primary.copy(alpha = .24f)
                            VisualTransformation { source ->
                                val index = source.text.indexOf(query, ignoreCase = true)
                                val marked =
                                    AnnotatedString.Builder(source)
                                        .apply {
                                            if (index >= 0)
                                                addStyle(
                                                    SpanStyle(background = highlight),
                                                    index,
                                                    index + query.length,
                                                )
                                        }
                                        .toAnnotatedString()
                                TransformedText(marked, OffsetMapping.Identity)
                            }
                        },
                    onTextLayout = { layout = it },
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
                state = readerScroll,
                modifier = Modifier.weight(1f).fillMaxWidth(),
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
