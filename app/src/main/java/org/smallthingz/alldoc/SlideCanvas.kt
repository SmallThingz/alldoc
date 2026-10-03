package org.smallthingz.alldoc

import android.graphics.Bitmap
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.ensureActive
import org.smallthingz.alldoc.core.DocumentEngine

private data class SlideLayer(
    val key: String,
    val order: Int,
    val text: List<DocumentEngine.Block> = emptyList(),
    val image: DocumentEngine.SlideImage? = null,
    val fill: DocumentEngine.SlideFill? = null,
    val omission: DocumentEngine.SlideOmission? = null,
)

private data class SlideRasters(
    val images: Map<String, Bitmap> = emptyMap(),
    val warnings: List<String> = emptyList(),
    val loading: Boolean = false,
)

@Composable
internal fun ImmersiveSlidePage(
    session: DocumentEngine.Session,
    edits: Map<String, String>,
    slideIndex: Int,
    rasterStore: SlideRasterStore,
    onWarnings: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val slide = session.slides.getOrNull(slideIndex) ?: return
    val density = LocalDensity.current.density
    BoxWithConstraints(modifier) {
        val validCanvas = safeSlideCanvas(slide.width, slide.height)
        val pageWidth = if (validCanvas) slide.width else 720.0
        val pageHeight = if (validCanvas) slide.height else 405.0
        val stageWidth = maxWidth.coerceAtLeast(72.dp)
        val scale = stageWidth.value / pageWidth
        val stageHeight = stageWidth * (pageHeight / pageWidth).toFloat()
        val textFrames = remember(slide) { slide.blocks.groupBy { it.shapeId }.values.toList() }
        val safeTexts = textFrames.filter {
            it.first().let { b ->
                safeSlideFrame(b.x, b.y, b.width, b.height, pageWidth, pageHeight)
            }
        }
        val safeImages =
            slide.images.filter {
                safeSlideFrame(it.x, it.y, it.width, it.height, pageWidth, pageHeight)
            }
        val safeFills =
            slide.fills.filter {
                safeSlideFrame(it.x, it.y, it.width, it.height, pageWidth, pageHeight)
            }
        val safeOmissions =
            slide.omissions.filter {
                safeSlideFrame(it.x, it.y, it.width, it.height, pageWidth, pageHeight)
            }
        val boundedLayout =
            validCanvas &&
                safeTexts.size == textFrames.size &&
                safeTexts.size + safeImages.size + safeFills.size + safeOmissions.size <= 256
        val layers =
            remember(slide, boundedLayout) {
                if (!boundedLayout) emptyList()
                else
                    (safeTexts.map {
                            SlideLayer(it.first().shapeId, it.first().zOrder, text = it)
                        } +
                            safeImages.map { SlideLayer(it.id, it.zOrder, image = it) } +
                            safeFills.map { SlideLayer(it.id, it.zOrder, fill = it) } +
                            safeOmissions.map { SlideLayer(it.id, it.zOrder, omission = it) })
                        .sortedBy { it.order }
            }
        var rasters by
            remember(session, slide) {
                mutableStateOf(SlideRasters(loading = safeImages.isNotEmpty()))
            }
        val clippedFrames = remember(session, slide) { mutableStateMapOf<String, Boolean>() }
        // One fixed fit-width decode per page/session. Pinch only scales the published raster.
        // A session store shares repeated assets and bounds cached/leased aggregate pixels.
        LaunchedEffect(session, slide, rasterStore, boundedLayout) {
            val leases = mutableListOf<SlideRasterStore.Lease>()
            try {
                rasters = SlideRasters(loading = safeImages.isNotEmpty() && boundedLayout)
                val decoded = linkedMapOf<String, Bitmap>()
                val warnings = mutableListOf<String>()
                var remaining = MAX_SLIDE_DECODE_PIXELS
                if (boundedLayout)
                    for (item in safeImages) {
                        ensureActive()
                        if (remaining <= 0) {
                            warnings += "Some images exceed this slide’s preview memory limit."
                            break
                        }
                        try {
                            val targetPixels =
                                (item.width * scale * density * item.height * scale * density)
                                    .toLong()
                                    .coerceAtLeast(1)
                            val lease =
                                rasterStore.acquire(
                                    item.bytes(),
                                    minOf(MAX_IMAGE_DECODE_PIXELS, remaining, targetPixels),
                                )
                            leases += lease
                            val bitmap = lease.bitmap
                            remaining -= bitmap.width.toLong() * bitmap.height
                            decoded[item.id] = bitmap
                        } catch (error: Exception) {
                            if (error is kotlinx.coroutines.CancellationException) throw error
                            warnings +=
                                "An embedded image could not be previewed within the safe preview limits. Its original bytes are preserved."
                        }
                    }
                ensureActive()
                rasters = SlideRasters(decoded, warnings.distinct(), false)
                kotlinx.coroutines.awaitCancellation()
            } finally {
                leases.forEach { it.close() }
            }
        }
        val warnings = buildList {
            addAll(slide.warnings)
            if (!validCanvas) add("The slide size is unsupported; its text is shown as an outline.")
            if (!boundedLayout && validCanvas) add("This complex slide is shown as a text outline.")
            if (safeTexts.size < textFrames.size)
                add(
                    "Some text frames have unsupported positions. Edit text contains all editable text."
                )
            if (safeImages.size < slide.images.size)
                add("Some image positions are unsupported and are omitted.")
            if (clippedFrames.values.any { it })
                add(
                    "Some text is clipped in this approximate layout. Edit text contains the complete text."
                )
            addAll(rasters.warnings)
        }
            .distinct()
        SideEffect { onWarnings(warnings) }
        Box(Modifier.fillMaxSize()) {
            CompositionLocalProvider(LocalDensity provides Density(density, 1f)) {
                Surface(
                    Modifier.width(stageWidth).height(stageHeight),
                    color = slide.backgroundColor?.let { Color(it) } ?: Color.White,
                    shadowElevation = 2.dp,
                ) {
                    if (boundedLayout && layers.isNotEmpty())
                        Box(Modifier.fillMaxSize().clipToBounds()) {
                            layers.forEach { layer ->
                                val image = layer.image
                                val fill = layer.fill
                                val omission = layer.omission
                                if (fill != null)
                                    Box(
                                        Modifier.offset((fill.x * scale).dp, (fill.y * scale).dp)
                                            .size((fill.width * scale).dp, (fill.height * scale).dp)
                                            .background(Color(fill.color))
                                    )
                                else if (omission != null)
                                    Box(
                                        Modifier.offset(
                                                (omission.x * scale).dp,
                                                (omission.y * scale).dp,
                                            )
                                            .size(
                                                (omission.width * scale).dp,
                                                (omission.height * scale).dp,
                                            )
                                            .clipToBounds()
                                            .background(Color(0xFFE9EDF4)),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            "Image omitted",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color(0xFF536077),
                                        )
                                    }
                                else if (image != null) {
                                    val frame =
                                        Modifier.offset((image.x * scale).dp, (image.y * scale).dp)
                                            .size(
                                                (image.width * scale).dp,
                                                (image.height * scale).dp,
                                            )
                                            .clipToBounds()
                                    val bitmap = rasters.images[image.id]
                                    if (bitmap != null)
                                        Image(
                                            bitmap.asImageBitmap(),
                                            "Embedded image on slide ${slide.number}",
                                            frame,
                                            contentScale = ContentScale.FillBounds,
                                        )
                                    else
                                        Box(
                                            frame.background(Color(0xFFE9EDF4)),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text(
                                                if (rasters.loading) "Image…"
                                                else "Image unavailable",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = Color(0xFF536077),
                                            )
                                        }
                                } else {
                                    val block = layer.text.first()
                                    val frame =
                                        Modifier.offset((block.x * scale).dp, (block.y * scale).dp)
                                            .size(
                                                (block.width * scale).dp,
                                                (block.height * scale).dp,
                                            )
                                            .clipToBounds()
                                            .then(
                                                block.fillColor?.let {
                                                    Modifier.background(Color(it))
                                                } ?: Modifier
                                            )
                                    val styled = styledDocumentText(layer.text, edits, scale, 20.0)
                                    DocumentTextField(
                                        value = styled.text,
                                        onValueChange = {},
                                        readOnly = true,
                                        modifier = frame,
                                        visualTransformation =
                                            VisualTransformation {
                                                TransformedText(styled, OffsetMapping.Identity)
                                            },
                                        textStyle =
                                            MaterialTheme.typography.bodyLarge.copy(
                                                color = Color(0xFF182132)
                                            ),
                                        onTextLayout = { layout ->
                                            val clipped =
                                                layout.didOverflowHeight || layout.didOverflowWidth
                                            if (clippedFrames[layer.key] != clipped)
                                                clippedFrames[layer.key] = clipped
                                        },
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
                                    if (slide.blocks.isEmpty()) "No supported content on this slide"
                                    else "Text outline · layout unavailable",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF536077),
                                )
                            }
                            items(slide.blocks.groupBy { it.paragraphId }.values.toList()) {
                                paragraph ->
                                Text(
                                    styledDocumentText(paragraph, edits),
                                    color = Color(0xFF182132),
                                )
                            }
                        }
                }
            }
            if (rasters.loading)
                LinearProgressIndicator(Modifier.align(Alignment.TopCenter).fillMaxWidth())
        }
    }
}

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
