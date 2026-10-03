package org.smallthingz.alldoc

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One small, shared visual language: ink, paper, a blue action, and document-type accents. */
@Composable
internal fun AlldocTheme(content: @Composable () -> Unit) {
    val colors =
        if (isSystemInDarkTheme())
            darkColorScheme(
                primary = Color(0xFFADC6FF),
                onPrimary = Color(0xFF082B69),
                primaryContainer = Color(0xFF213B62),
                onPrimaryContainer = Color(0xFFDDE8FF),
                secondary = Color(0xFFACB5C5),
                secondaryContainer = Color(0xFF28303D),
                onSecondaryContainer = Color(0xFFE5EAF3),
                background = Color(0xFF11151C),
                onBackground = Color(0xFFF1F3F7),
                surface = Color(0xFF1B212A),
                onSurface = Color(0xFFF1F3F7),
                onSurfaceVariant = Color(0xFFABB5C5),
                surfaceContainerLow = Color(0xFF161B24),
                surfaceContainer = Color(0xFF242C38),
                surfaceContainerHigh = Color(0xFF2A3341),
                surfaceContainerHighest = Color(0xFF303A48),
                outline = Color(0xFF697689),
                outlineVariant = Color(0xFF333E4D),
            )
        else
            lightColorScheme(
                primary = Color(0xFF245BC7),
                onPrimary = Color.White,
                primaryContainer = Color(0xFFE8EFFF),
                onPrimaryContainer = Color(0xFF153D85),
                secondary = Color(0xFF647085),
                secondaryContainer = Color(0xFFF0F3F8),
                onSecondaryContainer = Color(0xFF3A475B),
                background = Color(0xFFF8F9FC),
                onBackground = Color(0xFF182132),
                surface = Color.White,
                onSurface = Color(0xFF182132),
                onSurfaceVariant = Color(0xFF687386),
                surfaceContainerLow = Color(0xFFF0F3F8),
                surfaceContainer = Color(0xFFEBEFF5),
                surfaceContainerHigh = Color(0xFFE6EBF3),
                surfaceContainerHighest = Color(0xFFDCE3EE),
                outline = Color(0xFF8490A2),
                outlineVariant = Color(0xFFE3E8F0),
            )
    fun type(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) =
        TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontSize = size.sp,
            lineHeight = line.sp,
            fontWeight = weight,
            letterSpacing = 0.sp,
        )
    MaterialTheme(
        colorScheme = colors,
        typography =
            Typography(
                headlineLarge = type(32, 40, FontWeight.SemiBold),
                headlineMedium = type(27, 34, FontWeight.SemiBold),
                headlineSmall = type(23, 30, FontWeight.SemiBold),
                titleLarge = type(20, 28, FontWeight.SemiBold),
                titleMedium = type(16, 24, FontWeight.SemiBold),
                titleSmall = type(14, 20, FontWeight.SemiBold),
                bodyLarge = type(16, 25),
                bodyMedium = type(14, 21),
                bodySmall = type(12, 18),
                labelLarge = type(14, 20, FontWeight.Medium),
                labelMedium = type(12, 18, FontWeight.Medium),
                labelSmall = type(11, 16, FontWeight.Medium),
            ),
        shapes =
            Shapes(
                extraSmall = RoundedCornerShape(6.dp),
                small = RoundedCornerShape(8.dp),
                medium = RoundedCornerShape(12.dp),
                large = RoundedCornerShape(18.dp),
                extraLarge = RoundedCornerShape(24.dp),
            ),
        content = content,
    )
}

internal enum class Glyph {
    Back,
    More,
    Search,
    Open,
    Edit,
    Save,
    Close,
    Next,
    Grid,
    Slides,
    Document,
    Shield,
}

@Composable
internal fun AppIcon(
    glyph: Glyph,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Canvas(modifier.size(22.dp)) {
        val s = size.width / 24f
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(
                tint,
                Offset(x1 * s, y1 * s),
                Offset(x2 * s, y2 * s),
                1.7f * s,
                StrokeCap.Round,
            )
        fun path(vararg p: Float) {
            val path = Path()
            path.moveTo(p[0] * s, p[1] * s)
            for (i in 2 until p.size step 2) path.lineTo(p[i] * s, p[i + 1] * s)
            drawPath(path, tint, style = Stroke(1.7f * s, cap = StrokeCap.Round))
        }
        when (glyph) {
            Glyph.Back -> {
                path(14f, 5f, 7f, 12f, 14f, 19f)
                line(7f, 12f, 21f, 12f)
            }
            Glyph.Next -> path(9f, 5f, 16f, 12f, 9f, 19f)
            Glyph.More ->
                for (y in listOf(5f, 12f, 19f)) drawCircle(tint, 1.5f * s, Offset(12 * s, y * s))
            Glyph.Search -> {
                drawCircle(tint, 6.5f * s, Offset(10 * s, 10 * s), style = Stroke(1.7f * s))
                line(15f, 15f, 21f, 21f)
            }
            Glyph.Open -> {
                path(
                    3f,
                    18f,
                    3f,
                    6f,
                    9f,
                    6f,
                    11f,
                    9f,
                    21f,
                    9f,
                    18f,
                    19f,
                    3f,
                    19f,
                    6f,
                    12f,
                    21f,
                    12f,
                )
            }
            Glyph.Edit -> {
                path(5f, 15f, 16f, 4f, 20f, 8f, 9f, 19f, 4f, 20f, 5f, 15f)
                line(13f, 7f, 17f, 11f)
            }
            Glyph.Save -> {
                path(5f, 14f, 5f, 20f, 19f, 20f, 19f, 14f)
                path(8f, 10f, 12f, 14f, 16f, 10f)
                line(12f, 3f, 12f, 14f)
            }
            Glyph.Close -> {
                line(6f, 6f, 18f, 18f)
                line(6f, 18f, 18f, 6f)
            }
            Glyph.Grid -> {
                drawRoundRect(
                    tint,
                    Offset(4 * s, 4 * s),
                    Size(16 * s, 16 * s),
                    CornerRadius(2 * s),
                    style = Stroke(1.6f * s),
                )
                line(4f, 10f, 20f, 10f)
                line(10f, 4f, 10f, 20f)
                line(4f, 15f, 20f, 15f)
            }
            Glyph.Slides -> {
                drawRoundRect(
                    tint,
                    Offset(3 * s, 4 * s),
                    Size(18 * s, 13 * s),
                    CornerRadius(2 * s),
                    style = Stroke(1.6f * s),
                )
                line(12f, 17f, 12f, 21f)
                line(8f, 21f, 16f, 21f)
                line(7f, 9f, 16f, 9f)
                line(7f, 12f, 13f, 12f)
            }
            Glyph.Document -> {
                path(6f, 3f, 14f, 3f, 19f, 8f, 19f, 21f, 5f, 21f, 5f, 3f, 6f, 3f)
                path(14f, 3f, 14f, 8f, 19f, 8f)
                line(8f, 12f, 16f, 12f)
                line(8f, 16f, 14f, 16f)
            }
            Glyph.Shield -> {
                path(
                    12f,
                    3f,
                    20f,
                    6f,
                    19f,
                    14f,
                    16f,
                    18f,
                    12f,
                    21f,
                    8f,
                    18f,
                    5f,
                    14f,
                    4f,
                    6f,
                    12f,
                    3f,
                )
                path(8f, 11f, 11f, 14f, 16f, 9f)
            }
        }
    }
}

@Composable
internal fun IconAction(label: String, icon: Glyph, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.semantics { contentDescription = label },
    ) {
        AppIcon(
            icon,
            tint =
                if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.outline,
        )
    }
}

internal fun fileGroup(name: String): String =
    when (name.substringAfterLast('.', "").lowercase()) {
        "pdf" -> "PDF"
        "doc",
        "docx",
        "odt",
        "rtf" -> "Documents"
        "xls",
        "xlsx",
        "ods",
        "csv",
        "tsv" -> "Sheets"
        "ppt",
        "pptx",
        "odp" -> "Slides"
        else -> "Text"
    }

@Composable
internal fun FileBadge(name: String, modifier: Modifier = Modifier) {
    val group = fileGroup(name)
    val dark = isSystemInDarkTheme()
    val color =
        if (dark)
            when (group) {
                "PDF" -> Color(0xFFFFA9A3)
                "Sheets" -> Color(0xFF89D9B0)
                "Slides" -> Color(0xFFF5BC88)
                "Documents" -> Color(0xFFA8C8FF)
                else -> Color(0xFFD3B7F2)
            }
        else
            when (group) {
                "PDF" -> Color(0xFFBE4A44)
                "Sheets" -> Color(0xFF257454)
                "Slides" -> Color(0xFFBB662E)
                "Documents" -> Color(0xFF3769BC)
                else -> Color(0xFF786397)
            }
    Box(
        modifier.size(44.dp).background(color.copy(alpha = .09f), RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        AppIcon(
            when (group) {
                "Sheets" -> Glyph.Grid
                "Slides" -> Glyph.Slides
                else -> Glyph.Document
            },
            Modifier.size(24.dp),
            color,
        )
    }
}
