package app.smallthingz.alldoc.ui

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderTooltip(label: String, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable () -> Unit) {
    // TooltipBox applies its modifier below its root; keep Row/Column parent data here.
    Box(modifier) {
        TooltipBox(positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
            tooltip = { PlainTooltip { Text(label) } }, state = rememberTooltipState(),
            enableUserInput = enabled, content = content)
    }
}

/** The same adaptive icon as the launcher, decoded once off the main thread. */
private object AppIcon {
    private var image: ImageBitmap? = null

    @Synchronized fun load(context: Context): ImageBitmap = image ?: context.packageManager
        .getApplicationIcon(context.applicationInfo).toBitmap(192, 192).asImageBitmap().also { image = it }
}

@Composable
internal fun ReaderAppIcon(modifier: Modifier = Modifier, contentDescription: String? = null) {
    val context = LocalContext.current.applicationContext
    val image by produceState<ImageBitmap?>(null, context) {
        value = withContext(Dispatchers.IO) { AppIcon.load(context) }
    }
    image?.let { Image(it, contentDescription, modifier) }
        ?: Spacer(modifier.then(if (contentDescription == null) Modifier else Modifier.semantics {
            this.contentDescription = contentDescription
        }))
}

@Composable
fun ReaderAction(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    ReaderTooltip(label) {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp)) {
            Icon(icon, label, Modifier.size(24.dp))
        }
    }
}

/** Compact opaque pads keep controls readable over any document; hit targets remain 48dp. */
@Composable
internal fun ReaderOverlayAction(
    icon: ImageVector, label: String, modifier: Modifier = Modifier,
    enabled: Boolean = true, selected: Boolean = false, onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val ink = if (selected) colors.onPrimaryContainer else colors.onSurface
    val surface = if (selected) colors.primaryContainer else colors.surfaceContainerHigh
    ReaderTooltip(label) {
        IconButton(onClick = onClick, enabled = enabled, modifier = modifier.size(48.dp)
            .then(if (selected) Modifier.semantics { this.selected = true } else Modifier)) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(surface)
                .border(1.dp, colors.outlineVariant, RoundedCornerShape(10.dp)).readerTexture(),
                contentAlignment = Alignment.Center) {
                Icon(icon, label, Modifier.size(24.dp), tint = ink.copy(alpha = if (enabled) 1f else .38f))
            }
        }
    }
}

@Composable
internal fun ReaderHeader(
    title: String, onBack: (() -> Unit)? = null, onHome: (() -> Unit)? = null,
    onFullscreen: (() -> Unit)? = null, onAbout: (() -> Unit)? = null,
    onSettings: (() -> Unit)? = null, onInfo: (() -> Unit)? = null,
    onLicenses: (() -> Unit)? = null,
) {
    Row(Modifier.fillMaxWidth().readerBackground().heightIn(min = 56.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.width(48.dp)) {
            onHome?.let { ReaderAction(Icons.Outlined.Home, "Home", onClick = it) }
            onBack?.let { ReaderAction(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onClick = it) }
            onLicenses?.let { ReaderAction(Icons.Outlined.Policy, "Licenses", onClick = it) }
        }
        if (onAbout != null) Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            ReaderTooltip("About AllDoc") {
                IconButton(onClick = onAbout, modifier = Modifier.size(48.dp)) {
                    ReaderAppIcon(Modifier.size(40.dp), "About AllDoc")
                }
            }
        } else Box(Modifier.weight(1f).heightIn(min = 48.dp)
            .then(if (onInfo != null) Modifier.clickable(role = Role.Button, onClick = onInfo)
                .semantics { contentDescription = "File info" } else Modifier)
            .padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        onFullscreen?.let { ReaderAction(Icons.Outlined.Fullscreen, "Full screen", onClick = it) }
        onSettings?.let { ReaderAction(Icons.Outlined.Settings, "Settings", onClick = it) }
    }
}
