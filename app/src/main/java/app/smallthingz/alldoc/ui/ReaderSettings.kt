package app.smallthingz.alldoc.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.smallthingz.alldoc.state.AppTheme
import app.smallthingz.alldoc.state.LibraryCategory
import app.smallthingz.alldoc.state.CopySettings
import app.smallthingz.alldoc.theme.DocumentColors
import app.smallthingz.alldoc.theme.ImageTreatment
import app.smallthingz.alldoc.theme.ReadingPalette
import app.smallthingz.alldoc.theme.ReadingMode
import app.smallthingz.alldoc.theme.ThemeSettings
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReaderSettingsScreen(
    settings: CopySettings, location: String, usedBytes: Long, count: Int, busy: Boolean, warning: String?,
    onChange: (CopySettings) -> Unit, onChooseStorage: () -> Unit, onLimits: () -> Unit,
    onClear: () -> Unit, appearance: ThemeSettings, onAppearanceChange: (ThemeSettings) -> Unit, onBack: () -> Unit,
    appearanceEnabled: Boolean = true, documentAppearance: Boolean = false,
    appTheme: AppTheme = AppTheme.SYSTEM, onAppThemeChange: (AppTheme) -> Unit = {},
    hiddenLibraryTypes: Set<LibraryCategory> = emptySet(),
    onHiddenLibraryTypesChange: (Set<LibraryCategory>) -> Unit = {},
    onLibraryAccess: (() -> Unit)? = null, onAddLibraryFolder: (() -> Unit)? = null,
) {
    Column(Modifier.fillMaxSize().readerBackground()) {
        ReaderHeader("Settings", onBack = onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SettingsSection("App theme")
            val systemDark = isSystemInDarkTheme()
            AppearanceChoices(AppTheme.entries.map { it to when (it) {
                AppTheme.SYSTEM -> "System"; AppTheme.LIGHT -> "Light"; AppTheme.DARK -> "Dark"; AppTheme.AMOLED -> "AMOLED"
            } }, appTheme, true, onAppThemeChange, { theme ->
                val dark = theme.isDark(systemDark)
                ReadingPalette(if (theme == AppTheme.AMOLED) 0xff000000.toInt() else
                    if (dark) 0xff101418.toInt() else 0xffF5F6F7.toInt(),
                    if (dark) 0xffE5EBEF.toInt() else 0xff1A2329.toInt(), 0, 0)
            }, { "App theme: $it" })
            SettingsSection(if (documentAppearance) "This document" else "Document defaults")
            ReadingAppearanceContent(appearance, appearanceEnabled, onChange = onAppearanceChange)
            if (onLibraryAccess != null || onAddLibraryFolder != null) {
                SettingsSection("Library")
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.readerTexture()) {
                        onLibraryAccess?.let { SettingsLink(Icons.Outlined.Storage, "Device access", "", it) }
                        onAddLibraryFolder?.let { SettingsLink(Icons.Outlined.CreateNewFolder, "Add folder", "", it) }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    LibraryCategory.available.forEach { category ->
                        val included = category !in hiddenLibraryTypes
                        FilterChip(selected = included, onClick = {
                            onHiddenLibraryTypesChange(if (included) hiddenLibraryTypes + category else hiddenLibraryTypes - category)
                        }, modifier = Modifier.heightIn(min = 48.dp).semantics {
                            contentDescription = "Library: ${category.label}"
                        }, label = { Text(category.label) })
                    }
                }
            }
            SettingsSection("Storage")
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.readerTexture()) {
                    Row(Modifier.fillMaxWidth().toggleable(settings.enabled, enabled = !busy, role = Role.Switch,
                        onValueChange = { onChange(settings.copy(enabled = it)) }).heightIn(min = 56.dp)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Keep temporary files", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Switch(settings.enabled, onCheckedChange = null, enabled = !busy)
                    }
                    SettingsDivider()
                    SettingsLink(Icons.Outlined.FolderOpen, "Location", location, onChooseStorage, !busy)
                    if (settings.treeUri != null) TextButton(onClick = { onChange(settings.copy(treeUri = null)) }, enabled = !busy,
                        modifier = Modifier.padding(start = 44.dp).heightIn(min = 48.dp)
                            .semantics { contentDescription = "Use default location" }) { Text("Use default") }
                    SettingsDivider()
                    SettingsLink(Icons.Outlined.Storage, "Limits", "${readerBytes(settings.maxBytes)} · ${settings.maxCount} files", onLimits, !busy)
                    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (busy) CircularProgressIndicator(Modifier.size(16.dp).semantics { contentDescription = "Updating storage" }, strokeWidth = 2.dp)
                            Text("${readerBytes(usedBytes)} · $count ${if (count == 1) "copy" else "copies"}", Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick = onClear, enabled = !busy && count > 0,
                                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Clear copies" }) { Text("Clear") }
                        }
                        LinearProgressIndicator(progress = { (usedBytes.toDouble() / settings.maxBytes.coerceAtLeast(1)).toFloat().coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(3.dp))
                        Text("Older copies are removed at the limit.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        warning?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsSection(title: String, detail: String? = null) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall)
        detail?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
@Composable
private fun SettingsDivider() { HorizontalDivider(Modifier.padding(horizontal = 14.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .08f)) }
@Composable
private fun SettingsLink(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit, enabled: Boolean = true) {
    Surface(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(12.dp), color = Color.Transparent) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else .38f))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else .5f))
                if (subtitle.isNotEmpty()) Text(subtitle, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Outlined.ChevronRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

internal fun readerBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f GiB", bytes.toDouble() / (1024L * 1024 * 1024))
    bytes >= 1024L * 1024 -> String.format(Locale.getDefault(), "%.0f MiB", bytes.toDouble() / (1024L * 1024))
    bytes >= 1024 -> String.format(Locale.getDefault(), "%.0f KiB", bytes / 1024.0)
    else -> "$bytes B"
}

@Composable
internal fun CopyLimitsDialog(settings: CopySettings, onChange: (CopySettings) -> Unit, onDismiss: () -> Unit) {
    var megabytes by rememberSaveable { mutableStateOf((settings.maxBytes / (1024 * 1024)).toString()) }
    var documents by rememberSaveable { mutableStateOf(settings.maxCount.toString()) }
    val size = megabytes.toLongOrNull()?.takeIf { it in 1..8192 }
    val count = documents.toIntOrNull()?.takeIf { it in 1..10000 }
    ReaderDialog("Storage limits", onDismiss, buttons = { dismiss ->
        TextButton(onClick = dismiss) { Text("Cancel") }
        TextButton(onClick = { onChange(settings.copy(maxBytes = requireNotNull(size) * 1024 * 1024,
            maxCount = requireNotNull(count))); dismiss() }, enabled = size != null && count != null) { Text("Save") }
    }) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Lower limits may remove older copies.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(megabytes, { megabytes = it.filter(Char::isDigit).take(5) }, label = { Text("Storage (MiB)") },
                supportingText = { Text("1–8192") }, singleLine = true, isError = size == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(documents, { documents = it.filter(Char::isDigit).take(5) }, label = { Text("Files") },
                supportingText = { Text("1–10,000") }, singleLine = true, isError = count == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
internal fun ReadingAppearanceContent(
    settings: ThemeSettings, enabled: Boolean = true, showImageTreatment: Boolean = true, onChange: (ThemeSettings) -> Unit,
) {
    val modes = remember { listOf(
        ReadingMode.SYSTEM to "System", ReadingMode.ADAPTIVE_LIGHT to "Light", ReadingMode.ADAPTIVE to "Dark",
        ReadingMode.SEPIA to "Paper", ReadingMode.ORIGINAL to "Original", ReadingMode.HIGH_CONTRAST to "Contrast",
        ReadingMode.INVERT to "Invert",
    ) }
    val dark = isSystemInDarkTheme()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AppearanceChoices(modes, settings.mode, enabled, { onChange(settings.copy(mode = it)) },
            { DocumentColors.palette(ThemeSettings(it).resolved(dark)) })
        if (settings.mode == ReadingMode.SEPIA) {
            var roughness by remember(settings.paperRoughness) { mutableFloatStateOf(settings.paperRoughness.coerceIn(0, 100).toFloat()) }
            Column(Modifier.fillMaxWidth()) {
                Text("Paper roughness", style = MaterialTheme.typography.labelLarge)
                Slider(value = roughness, onValueChange = { roughness = it }, valueRange = 0f..100f, enabled = enabled,
                    onValueChangeFinished = { onChange(settings.copy(paperRoughness = roughness.toInt())) },
                    modifier = Modifier.fillMaxWidth().semantics {
                        contentDescription = "Paper roughness"; stateDescription = "${roughness.toInt()}%"
                    })
            }
        }
        if (showImageTreatment && settings.mode != ReadingMode.ORIGINAL && settings.mode != ReadingMode.INVERT) {
            Text("Images", style = MaterialTheme.typography.labelLarge)
            BoxWithConstraints(Modifier.fillMaxWidth().selectableGroup()) {
                val density = LocalDensity.current
                val labelWidth = rememberTextMeasurer().measure("Recolor", style = MaterialTheme.typography.labelLarge).size.width
                val chipWidth = labelWidth + with(density) { 32.dp.roundToPx() }
                val gap = with(density) { 6.dp.roundToPx() }
                val columns = when {
                    constraints.maxWidth >= chipWidth * 4 + gap * 3 -> 4
                    constraints.maxWidth >= chipWidth * 2 + gap -> 2
                    else -> 1
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    ImageTreatment.entries.chunked(columns).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.forEach { treatment ->
                                val label = when (treatment) {
                                    ImageTreatment.AUTO -> "Auto"; ImageTreatment.KEEP -> "Keep"
                                    ImageTreatment.DIM -> "Dim"; ImageTreatment.RECOLOR -> "Recolor"
                                }
                                FilterChip(selected = settings.imageTreatment == treatment,
                                    onClick = { onChange(settings.copy(imageTreatment = treatment)) }, enabled = enabled,
                                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics {
                                        contentDescription = label; role = Role.RadioButton
                                    }, label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) })
                            }
                            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }
}

/** One layout for app and document swatches, keeping labels legible at enlarged font sizes. */
@Composable
private fun <T> AppearanceChoices(
    choices: List<Pair<T, String>>, selected: T, enabled: Boolean, onChange: (T) -> Unit,
    palette: (T) -> ReadingPalette, description: (String) -> String = { it },
) {
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelMedium
    val labelWidth = choices.maxOf { measurer.measure(it.second, style).size.width }
    BoxWithConstraints(Modifier.fillMaxWidth().selectableGroup()) {
        val gap = with(density) { 8.dp.roundToPx() }
        val minimum = maxOf(with(density) { 60.dp.roundToPx() }, labelWidth + gap)
        val columns = ((constraints.maxWidth + gap) / (minimum + gap)).coerceIn(1, minOf(5, choices.size))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            choices.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { (value, label) ->
                        val active = value == selected
                        val tones = palette(value)
                        Column(Modifier.weight(1f).heightIn(min = 48.dp)
                            .selectable(active, enabled = enabled, role = Role.RadioButton, onClick = { onChange(value) })
                            .semantics { contentDescription = description(label) },
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Surface(Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(8.dp),
                                color = Color(tones.background), border = BorderStroke(if (active) 2.dp else 1.dp,
                                    if (active) colors.primary else colors.outlineVariant)) {
                                Box(Modifier.fillMaxSize()) {
                                    Canvas(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp)) {
                                        val ink = Color(tones.foreground).copy(alpha = if (enabled) .65f else .25f)
                                        for (line in 0..2) drawLine(ink, Offset(0f, size.height * (.2f + .3f * line)),
                                            Offset(size.width * if (line == 2) .65f else 1f, size.height * (.2f + .3f * line)),
                                            strokeWidth = 1.5.dp.toPx())
                                    }
                                    if (active) Icon(Icons.Outlined.CheckCircle, null,
                                        Modifier.align(Alignment.TopEnd).padding(4.dp).size(14.dp), tint = Color(tones.foreground))
                                }
                            }
                            Text(label, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = colors.onSurface.copy(alpha = if (enabled) 1f else .38f))
                        }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}
