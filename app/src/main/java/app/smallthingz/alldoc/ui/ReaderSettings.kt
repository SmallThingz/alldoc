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
import app.smallthingz.alldoc.state.CopySettings
import app.smallthingz.alldoc.theme.DocumentColors
import app.smallthingz.alldoc.theme.ImageTreatment
import app.smallthingz.alldoc.theme.ReadingPalette
import app.smallthingz.alldoc.theme.ReadingMode
import app.smallthingz.alldoc.theme.ThemeSettings
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
internal fun ReaderSettingsScreen(
    settings: CopySettings, location: String, usedBytes: Long, count: Int, busy: Boolean, warning: String?,
    onChange: (CopySettings) -> Unit, onChooseStorage: () -> Unit,
    onClear: () -> Unit, appearance: ThemeSettings, onAppearanceChange: (ThemeSettings) -> Unit, onBack: () -> Unit,
    appearanceEnabled: Boolean = true, documentAppearance: Boolean = false,
    appTheme: AppTheme = AppTheme.SYSTEM, onAppThemeChange: (AppTheme) -> Unit = {},
    onLibraryAccess: (() -> Unit)? = null,
    libraryAccess: ReaderAccessModel? = null, active: Boolean = true,
) {
    val inputActive = active && LocalReaderInputAllowed.current()
    val scroll = rememberScrollState()
    LaunchedEffect(scroll, inputActive) { if (!inputActive) scroll.stopScroll(MutatePriority.PreventUserInput) }
    Column(Modifier.fillMaxSize().readerBackground()) {
        ReaderHeader("Settings", onBack = onBack)
        Column(Modifier.weight(1f).verticalScroll(scroll, enabled = inputActive).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (libraryAccess != null && onLibraryAccess != null) LibraryAccessSetting(libraryAccess, onLibraryAccess)
            SettingsSection("App theme")
            SettingsChoices(AppTheme.entries, { it == appTheme }, onAppThemeChange,
                enabled = inputActive, label = { it.label() }, icon = { when (it) {
                    AppTheme.SYSTEM -> Icons.Outlined.BrightnessAuto; AppTheme.LIGHT -> Icons.Outlined.LightMode
                    AppTheme.DARK -> Icons.Outlined.DarkMode; AppTheme.AMOLED -> Icons.Outlined.Contrast
                } }, description = { "App theme: ${it.label()}" })
            SettingsSection(if (documentAppearance) "This document" else "Document defaults")
            ReadingAppearanceContent(appearance, appearanceEnabled && inputActive, onChange = onAppearanceChange)
            SettingsSection("Storage")
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column {
                    CopyLimitControl(settings, onChange, enabled = inputActive && !busy,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
                    SettingsDivider()
                    SettingsLink(Icons.Outlined.FolderOpen, "Location", location, onChooseStorage, inputActive && !busy)
                    if (settings.treeUri != null) TextButton(onClick = { onChange(settings.copy(treeUri = null)) }, enabled = inputActive && !busy,
                        modifier = Modifier.padding(start = 44.dp).heightIn(min = 48.dp)
                            .semantics { contentDescription = "Use default location" }) { Text("Use default") }
                    SettingsDivider()
                    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (busy) CircularProgressIndicator(Modifier.size(16.dp).semantics { contentDescription = "Updating storage" }, strokeWidth = 2.dp)
                            Text("${readerBytes(usedBytes)} · $count ${if (count == 1) "copy" else "copies"}", Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick = onClear, enabled = inputActive && !busy && count > 0,
                                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Clear copies" }) { Text("Clear") }
                        }
                        if (settings.enabled && settings.maxBytes in 1 until Long.MAX_VALUE)
                            LinearProgressIndicator(progress = { (usedBytes.toDouble() / settings.maxBytes).toFloat().coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().height(3.dp))
                        Text(when {
                            !settings.enabled || settings.maxBytes == 0L -> "New temporary files aren't kept. Existing copies stay until cleared."
                            settings.maxBytes == Long.MAX_VALUE -> "No storage limit. Clear copies to free space."
                            else -> "Older copies are removed at the limit."
                        },
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

internal val copyLimitOptions: List<Long> = listOf<Long>(0, 1, 2, 4, 8, 16, 32, 64, 128, 256, 512, 768,
    1024, 1280, 1536, 1792, 2048, 2560, 3072, 3584, 4096).map { it * 1024 * 1024 } + Long.MAX_VALUE

internal fun copyLimitIndex(settings: CopySettings): Int = when {
    !settings.enabled || settings.maxBytes == 0L -> 0
    settings.maxBytes == Long.MAX_VALUE -> copyLimitOptions.lastIndex
    else -> (1 until copyLimitOptions.lastIndex).minBy { abs(copyLimitOptions[it] - settings.maxBytes) }
}

internal fun copyLimitLabel(bytes: Long): String = when (bytes) {
    0L -> "Off"
    Long.MAX_VALUE -> "Unlimited"
    else -> "${bytes / (1024 * 1024)} MB"
}

internal fun copyLimitSelection(settings: CopySettings, index: Int): CopySettings {
    val bytes = copyLimitOptions[index.coerceIn(copyLimitOptions.indices)]
    return settings.copy(enabled = bytes != 0L, maxBytes = bytes, maxCount = 1_000_000)
}

@Composable
internal fun CopyLimitControl(settings: CopySettings, onChange: (CopySettings) -> Unit,
    enabled: Boolean = true, modifier: Modifier = Modifier,
) {
    val inputAllowed = LocalReaderInputAllowed.current
    val inputEnabled = enabled && inputAllowed()
    val initial = copyLimitIndex(settings)
    var position by remember(settings.enabled, settings.maxBytes, inputEnabled) { mutableFloatStateOf(initial.toFloat()) }
    var editing by remember(settings.enabled, settings.maxBytes, inputEnabled) { mutableStateOf(false) }
    val bytes = if (editing) copyLimitOptions[position.roundToInt()] else if (settings.enabled) settings.maxBytes else 0L
    val label = copyLimitLabel(bytes)
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Storage limit", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(value = position, onValueChange = { if (enabled && inputAllowed()) { position = it.roundToInt().toFloat(); editing = true } },
            valueRange = 0f..copyLimitOptions.lastIndex.toFloat(), steps = copyLimitOptions.size - 2, enabled = inputEnabled,
            onValueChangeFinished = {
                if (enabled && inputAllowed()) copyLimitSelection(settings, position.roundToInt()).let { if (it != settings) onChange(it) }
                editing = false
            }, modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = "Storage limit"; stateDescription = label
            })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Off", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Unlimited", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun ReadingAppearanceContent(
    settings: ThemeSettings, enabled: Boolean = true, showImageTreatment: Boolean = true, onChange: (ThemeSettings) -> Unit,
) {
    val inputAllowed = LocalReaderInputAllowed.current
    val inputEnabled = enabled && inputAllowed()
    val modes = remember { listOf(
        ReadingMode.SYSTEM to "System", ReadingMode.ADAPTIVE_LIGHT to "Light", ReadingMode.ADAPTIVE to "Dark",
        ReadingMode.SEPIA to "Paper", ReadingMode.ORIGINAL to "Original", ReadingMode.HIGH_CONTRAST to "Contrast",
        ReadingMode.INVERT to "Invert",
    ) }
    val dark = isSystemInDarkTheme()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AppearanceChoices(modes, settings.mode, inputEnabled, { onChange(settings.copy(mode = it)) },
            { DocumentColors.palette(ThemeSettings(it).resolved(dark)) })
        if (settings.mode == ReadingMode.SEPIA) {
            var roughness by remember(settings.paperRoughness, inputEnabled) { mutableFloatStateOf(settings.paperRoughness.coerceIn(0, 100).toFloat()) }
            Column(Modifier.fillMaxWidth()) {
                Text("Paper roughness", style = MaterialTheme.typography.labelLarge)
                Slider(value = roughness, onValueChange = { if (enabled && inputAllowed()) roughness = it },
                    valueRange = 0f..100f, enabled = inputEnabled,
                    onValueChangeFinished = {
                        if (enabled && inputAllowed()) settings.copy(paperRoughness = roughness.toInt()).let {
                            if (it != settings) onChange(it)
                        }
                    },
                    modifier = Modifier.fillMaxWidth().semantics {
                        contentDescription = "Paper roughness"; stateDescription = "${roughness.toInt()}%"
                    })
            }
        }
        if (showImageTreatment && settings.mode != ReadingMode.ORIGINAL && settings.mode != ReadingMode.INVERT) {
            Text("Images", style = MaterialTheme.typography.labelLarge)
            SettingsChoices(ImageTreatment.entries, { it == settings.imageTreatment },
                { onChange(settings.copy(imageTreatment = it)) }, enabled = inputEnabled,
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
internal fun <T> SettingsChoices(items: List<T>, selected: (T) -> Boolean, onSelect: (T) -> Unit,
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
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(percent = 50), color = colors.surfaceContainerLow,
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
                        Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(percent = 50)).background(fill)
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
