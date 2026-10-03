package org.smallthingz.alldoc

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap

@Composable
internal fun ImmersivePdfPage(
    model: DocumentModel,
    zoom: Float,
    onZoom: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val vertical = rememberScrollState()
    val horizontal = rememberScrollState()
    RegisterDocumentScroll(vertical)
    RegisterDocumentScroll(horizontal)
    val interaction = LocalDocumentInteraction.current
    val currentZoom by rememberUpdatedState(zoom)
    val currentOnZoom by rememberUpdatedState(onZoom)
    val transform: (Float, androidx.compose.ui.geometry.Offset) -> Unit = remember {
        { factor, _ ->
            interaction?.nativeMotion()
            currentOnZoom((currentZoom * factor).coerceIn(1f, 4f))
        }
    }
    BoxWithConstraints(
        modifier.background(MaterialTheme.colorScheme.surfaceContainerLow).documentPinch(transform)
    ) {
        val width = maxWidth * zoom
        Box(Modifier.fillMaxSize().verticalScroll(vertical).horizontalScroll(horizontal)) {
            model.pdfBitmap?.let {
                Image(
                    it.asImageBitmap(),
                    "Page ${model.pdfPage+1} of ${model.name}",
                    Modifier.width(width),
                )
            }
        }
        if (model.pdfLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}
