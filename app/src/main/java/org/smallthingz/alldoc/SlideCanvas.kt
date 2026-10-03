package org.smallthingz.alldoc

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.smallthingz.alldoc.core.DocumentEngine

/** Geometry-aware text preview. Deliberately not presented as PowerPoint rendering. */
@Composable
internal fun SlideCanvas(
    session: DocumentEngine.Session,
    edits: Map<String, String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var zoom by remember(session) { mutableFloatStateOf(1f) }
    val slides = session.slides
    val slide = slides.getOrNull(selected)
    Column(modifier.background(MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Text layout · images and styles omitted",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { zoom = if (zoom == 1f) 1.5f else 1f }) {
                Text(if (zoom == 1f) "Zoom in" else "Fit")
            }
        }
        if (slide == null)
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("No slides found")
            }
        else
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val stageWidth = (maxWidth - 32.dp) * zoom
                val validCanvas = safeSlideCanvas(slide.width, slide.height)
                val originalWidth = if (validCanvas) slide.width else 720.0
                val originalHeight = if (validCanvas) slide.height else 405.0
                val scale = stageWidth.value / originalWidth
                val stageHeight = stageWidth * (originalHeight / originalWidth).toFloat()
                Column(
                    Modifier.fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Surface(
                        Modifier.width(stageWidth).height(stageHeight),
                        color = Color.White,
                        shadowElevation = 2.dp,
                    ) {
                        val shapes = slide.blocks.groupBy { it.shapeId }
                        if (shapes.isEmpty())
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("No text on this slide", color = Color(0xFF6B7280))
                            }
                        else if (
                            validCanvas &&
                                shapes.size <= 256 &&
                                shapes.values.all {
                                    val b = it.first()
                                    safeSlideFrame(
                                        b.x,
                                        b.y,
                                        b.width,
                                        b.height,
                                        originalWidth,
                                        originalHeight,
                                    )
                                }
                        )
                            Box(Modifier.fillMaxSize()) {
                                shapes.values.forEach { blocks ->
                                    val first = blocks.first()
                                    val text =
                                        blocks
                                            .groupBy { it.paragraphId }
                                            .values
                                            .joinToString("\n") { paragraph ->
                                                paragraph.joinToString("") {
                                                    edits[it.id] ?: it.original
                                                }
                                            }
                                    SelectionContainer(
                                        Modifier.offset((first.x * scale).dp, (first.y * scale).dp)
                                            .width((first.width * scale).coerceAtLeast(12.0).dp)
                                            .heightIn(
                                                max = (first.height * scale).coerceAtLeast(18.0).dp
                                            )
                                    ) {
                                        Text(
                                            text,
                                            color = Color(0xFF1B2433),
                                            fontSize = (20 * scale).coerceIn(8.0, 64.0).sp,
                                            lineHeight = (26 * scale).coerceIn(10.0, 84.0).sp,
                                        )
                                    }
                                }
                            }
                        else
                            LazyColumn(
                                contentPadding = PaddingValues(20.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                item {
                                    Text(
                                        "Text outline · layout unavailable",
                                        color = Color(0xFF687386),
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                                items(slide.blocks.groupBy { it.paragraphId }.values.toList()) {
                                    paragraph ->
                                    Text(
                                        paragraph
                                            .joinToString("") { edits[it.id] ?: it.original }
                                            .take(8000),
                                        color = Color(0xFF1B2433),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                    }
                    Text(
                        "${slide.number} / ${slides.size}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
            }
        Surface(color = MaterialTheme.colorScheme.surface) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                slides.forEachIndexed { index, item ->
                    Surface(
                        onClick = { onSelect(index) },
                        color =
                            if (index == selected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = RoundedCornerShape(8.dp),
                        border =
                            if (index == selected)
                                BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                            else null,
                    ) {
                        Row(
                            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            AppIcon(
                                Glyph.Slides,
                                Modifier.size(18.dp),
                                if (index == selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                "${item.number}",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
            }
        }
    }
}

// Android packs layout constraints into bounded integer fields. Never trust file geometry as dp.
internal fun safeSlideCanvas(width: Double, height: Double): Boolean =
    width.isFinite() &&
        height.isFinite() &&
        width in 72.0..14400.0 &&
        height in 72.0..14400.0 &&
        height / width in .25..4.0

internal fun safeSlideFrame(
    x: Double,
    y: Double,
    width: Double,
    height: Double,
    pageWidth: Double,
    pageHeight: Double,
): Boolean =
    listOf(x, y, width, height).all { it.isFinite() } &&
        x >= 0 &&
        y >= 0 &&
        width > 0 &&
        height > 0 &&
        x + width <= pageWidth &&
        y + height <= pageHeight
