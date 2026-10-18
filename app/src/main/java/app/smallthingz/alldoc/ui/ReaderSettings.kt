package app.smallthingz.alldoc.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
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
import kotlin.math.roundToInt

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
    libraryAccess: ReaderAccessModel? = null, active: Boolean = true, libraryTypesEnabled: Boolean = true,
) {
    val scroll = rememberScrollState()
    LaunchedEffect(scroll, active) { if (!active) scroll.stopScroll(MutatePriority.PreventUserInput) }
    Column(Modifier.fillMaxSize().readerBackground()) {
        ReaderHeader("Settings", onBack = onBack)
        Column(Modifier.weight(1f).verticalScroll(scroll, enabled = active).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (libraryAccess != null && onLibraryAccess != null) LibraryAccessSetting(libraryAccess, onLibraryAccess)
            SettingsSection("App theme")
            SettingsChoices(AppTheme.entries, { it == appTheme }, onAppThemeChange,
                enabled = active, label = { it.label() }, icon = { when (it) {
                    AppTheme.SYSTEM -> Icons.Outlined.BrightnessAuto; AppTheme.LIGHT -> Icons.Outlined.LightMode
                    AppTheme.DARK -> Icons.Outlined.DarkMode; AppTheme.AMOLED -> Icons.Outlined.Contrast
                } }, description = { "App theme: ${it.label()}" })
            SettingsSection(if (documentAppearance) "This document" else "Document defaults")
            ReadingAppearanceContent(appearance, appearanceEnabled && active, onChange = onAppearanceChange)
            SettingsSection("Library")
            onAddLibraryFolder?.let {
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    SettingsLink(Icons.Outlined.CreateNewFolder, "Add folder", "", it)
                }
            }
            SettingsChoices(LibraryCategory.available, { it !in hiddenLibraryTypes }, { category ->
                onHiddenLibraryTypesChange(if (category in hiddenLibraryTypes) hiddenLibraryTypes - category else hiddenLibraryTypes + category)
            }, label = { when (it) {
                LibraryCategory.DOCUMENTS -> "Docs"; LibraryCategory.PRESENTATIONS -> "Slides"
                LibraryCategory.SPREADSHEETS -> "Sheets"; else -> "Books"
            } }, icon = { when (it) {
                LibraryCategory.DOCUMENTS -> Icons.Outlined.Description; LibraryCategory.PRESENTATIONS -> Icons.Outlined.Slideshow
                LibraryCategory.SPREADSHEETS -> Icons.Outlined.TableChart; else -> Icons.Outlined.MenuBook
            } }, description = { "Library: ${it.label}" }, multiple = true, enabled = active && libraryTypesEnabled)
            SettingsSection("Storage")
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column {
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
                    SettingsLink(Icons.Outlined.Storage, "Storage limit", "${settings.maxBytes / (1024 * 1024)} MB", onLimits, !busy)
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
internal fun CopyLimitsDialog(settings: CopySettings, onChange: (CopySettings) -> Unit, onDismiss: () -> Unit,
    enabled: Boolean = true,
) {
    var megabytes by remember(settings.maxBytes) { mutableFloatStateOf((settings.maxBytes / (1024 * 1024)).coerceIn(1, 8192).toFloat()) }
    ReaderSheet("Storage limit", onDismiss) {
        Column {
            Text("${megabytes.roundToInt()} MB", style = MaterialTheme.typography.titleMedium)
            Slider(value = megabytes, onValueChange = { megabytes = it }, valueRange = 1f..8192f, enabled = enabled,
                onValueChangeFinished = {
                    val bytes = megabytes.roundToInt().toLong() * 1024 * 1024
                    if (enabled && bytes != settings.maxBytes) onChange(settings.copy(maxBytes = bytes))
                },
                modifier = Modifier.fillMaxWidth().semantics {
                    contentDescription = "Storage limit"; stateDescription = "${megabytes.roundToInt()} MB"
                })
            Text("Older copies are removed at the limit.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            SettingsChoices(ImageTreatment.entries, { it == settings.imageTreatment },
                { onChange(settings.copy(imageTreatment = it)) }, enabled = enabled,
                label = { when (it) {
                    ImageTreatment.AUTO -> "Auto"; ImageTreatment.KEEP -> "Keep"
                    ImageTreatment.DIM -> "Dim"; ImageTreatment.RECOLOR -> "Recolor"
                } }, icon = { when (it) {
                    ImageTreatment.AUTO -> Icons.Outlined.AutoFixHigh; ImageTreatment.KEEP -> Icons.Outlined.Image
                    ImageTreatment.DIM -> Icons.Outlined.BrightnessLow; ImageTreatment.RECOLOR -> Icons.Outlined.Palette
                } })
        }
    }
}

private fun AppTheme.label() = when (this) {
    AppTheme.SYSTEM -> "System"; AppTheme.LIGHT -> "Light"; AppTheme.DARK -> "Dark"; AppTheme.AMOLED -> "AMOLED"
}

/** Reverb-style segmented choices; narrow layouts keep icons and full accessible names. */
@Composable
private fun <T> SettingsChoices(items: List<T>, selected: (T) -> Boolean, onSelect: (T) -> Unit,
    label: (T) -> String, icon: (T) -> ImageVector, enabled: Boolean = true,
    description: (T) -> String = label, multiple: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val style = MaterialTheme.typography.labelMedium
    val measurer = rememberTextMeasurer()
    val widest = items.maxOf { measurer.measure(label(it), style, maxLines = 1, softWrap = false).size.width }
    val scroll = rememberScrollState()
    LaunchedEffect(scroll, enabled) { if (!enabled) scroll.stopScroll(MutatePriority.PreventUserInput) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val rowWidth = maxOf(maxWidth, (items.size * 48 + 8).dp)
        val showLabels = widest <= with(density) { ((rowWidth - 8.dp) / items.size - 12.dp).roundToPx() }
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(30.dp), color = colors.surfaceContainerLow,
            border = BorderStroke(1.dp, colors.outlineVariant)) {
            Row(Modifier.horizontalScroll(scroll, enabled = enabled).width(rowWidth).padding(4.dp)
                .then(if (multiple) Modifier else Modifier.selectableGroup())) {
                items.forEach { value ->
                    val chosen = selected(value)
                    val fill by animateColorAsState(if (chosen) colors.secondaryContainer else Color.Transparent, label = "Settings choice")
                    val ink by animateColorAsState((if (chosen) colors.onSecondaryContainer else colors.onSurfaceVariant)
                        .copy(alpha = if (enabled) 1f else .38f), label = "Settings choice label")
                    ReaderTooltip(description(value), Modifier.weight(1f), enabled = enabled) {
                        val action = if (multiple) Modifier.toggleable(chosen, enabled, Role.Checkbox) { onSelect(value) }
                        else Modifier.selectable(chosen, enabled, Role.RadioButton) { if (!chosen) onSelect(value) }
                        Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(22.dp)).background(fill)
                            .then(action).semantics { contentDescription = description(value) }
                            .padding(horizontal = 6.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)) {
                            Icon(icon(value), null, Modifier.size(20.dp), tint = ink)
                            if (showLabels) Text(label(value), Modifier.clearAndSetSemantics {}, style = style,
                                color = ink, maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
        }
    }
}

/** Document swatches keep labels legible at enlarged font sizes. */
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
