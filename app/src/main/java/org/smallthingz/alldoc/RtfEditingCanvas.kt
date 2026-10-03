package org.smallthingz.alldoc

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import org.smallthingz.alldoc.core.DocumentEngine
import org.smallthingz.alldoc.core.RtfDocument

/** Apply one contiguous text diff while retaining each unaffected source run and its style. */
internal fun reconcileTextRuns(current: List<String>, replacement: String): List<String> {
    require(current.isNotEmpty())
    require(replacement.length <= RtfDocument.MAX_TEXT) {
        "This paragraph exceeds the RTF text limit"
    }
    for (index in replacement.indices) {
        val ch = replacement[index]
        require(
            !ch.isHighSurrogate() ||
                index + 1 < replacement.length && replacement[index + 1].isLowSurrogate()
        ) {
            "Finish the Unicode character before saving"
        }
        require(!ch.isLowSurrogate() || index > 0 && replacement[index - 1].isHighSurrogate()) {
            "Finish the Unicode character before saving"
        }
    }
    val previous = current.joinToString("")
    if (previous == replacement) return current
    var prefix = 0
    while (
        prefix < minOf(previous.length, replacement.length) &&
            previous[prefix] == replacement[prefix]
    ) prefix++
    if (
        prefix > 0 &&
            prefix < previous.length &&
            previous[prefix].isLowSurrogate() &&
            previous[prefix - 1].isHighSurrogate()
    )
        prefix--
    var suffix = 0
    while (
        suffix < minOf(previous.length, replacement.length) - prefix &&
            previous[previous.lastIndex - suffix] == replacement[replacement.lastIndex - suffix]
    ) suffix++
    val oldEnd = previous.length - suffix
    if (
        suffix > 0 &&
            oldEnd > 0 &&
            oldEnd < previous.length &&
            previous[oldEnd].isLowSurrogate() &&
            previous[oldEnd - 1].isHighSurrogate()
    )
        suffix--
    val removedEnd = previous.length - suffix
    val inserted = replacement.substring(prefix, replacement.length - suffix)
    var offset = 0
    var anchor = -1
    current.forEachIndexed { index, text ->
        if (
            anchor < 0 &&
                prefix <= offset + text.length &&
                (text.isNotEmpty() || index == current.lastIndex)
        )
            anchor = index
        offset += text.length
    }
    if (anchor < 0) anchor = current.lastIndex
    offset = 0
    return current
        .mapIndexed { index, text ->
            val start = offset
            offset += text.length
            val before = text.substring(0, (prefix - start).coerceIn(0, text.length))
            val after = text.substring((removedEnd - start).coerceIn(0, text.length))
            before + (if (index == anchor) inserted else "") + after
        }
        .also {
            require(it.joinToString("") == replacement) {
                "This change could not be mapped safely to the original formatting"
            }
        }
}

@Composable
internal fun RtfEditingCanvas(
    blocks: List<DocumentEngine.Block>,
    edits: Map<String, String>,
    enabled: Boolean,
    onEdit: (Map<String, String>) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberLazyListState()
    RegisterDocumentScroll(scroll)
    val paragraphs = remember(blocks) { blocks.groupBy { it.paragraphId }.values.toList() }
    LazyColumn(
        state = scroll,
        modifier = modifier.background(MaterialTheme.colorScheme.surface),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        itemsIndexed(paragraphs, key = { _, paragraph -> paragraph.first().paragraphId }) {
            index,
            paragraph ->
            val values = paragraph.map { edits[it.id] ?: it.original }
            if (paragraph.all { it.editable })
                DocumentTextField(
                    value = values.joinToString(""),
                    onValueChange = { replacement ->
                        runCatching { reconcileTextRuns(values, replacement) }
                            .onSuccess { reconciled ->
                                onEdit(
                                    paragraph
                                        .mapIndexed { i, block -> block.id to reconciled[i] }
                                        .toMap()
                                )
                            }
                            .onFailure {
                                onError(it.message ?: "This paragraph could not be changed safely")
                            }
                    },
                    enabled = enabled,
                    visualTransformation =
                        VisualTransformation { original ->
                            val styled =
                                styledDocumentText(
                                    paragraph,
                                    edits,
                                    scale = 1.2,
                                    defaultSize = 14.0,
                                )
                            TransformedText(
                                if (styled.text == original.text) styled else original,
                                OffsetMapping.Identity,
                            )
                        },
                    modifier =
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                            contentDescription = "Edit RTF paragraph ${index+1}"
                        },
                    textStyle =
                        MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                )
            else {
                val styled = styledDocumentText(paragraph, edits, scale = 1.2, defaultSize = 14.0)
                DocumentTextField(
                    value = values.joinToString(""),
                    onValueChange = {},
                    readOnly = true,
                    visualTransformation =
                        VisualTransformation { source ->
                            TransformedText(
                                if (source.text == styled.text) styled else source,
                                OffsetMapping.Identity,
                            )
                        },
                    textStyle =
                        MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                    modifier =
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                            contentDescription = "Read-only RTF paragraph ${index+1}"
                        },
                )
            }
        }
    }
}
