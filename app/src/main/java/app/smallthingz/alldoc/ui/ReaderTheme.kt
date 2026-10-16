package app.smallthingz.alldoc.ui

import android.app.Activity
import android.graphics.BitmapFactory
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import app.smallthingz.alldoc.R
import app.smallthingz.alldoc.state.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Original AllDoc implementation. Reverb's static textured Material chrome is the visual reference.
// No Reverb code or assets are incorporated; reader_noise.png is an independently generated tile.
private val ReaderTypography = Typography(
    headlineLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 36.sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 24.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
)

val LocalAppTheme = staticCompositionLocalOf { AppTheme.SYSTEM }
private val LocalReaderTexture = staticCompositionLocalOf<ShaderBrush?> { null }

@Composable
fun ReaderTheme(appTheme: AppTheme = AppTheme.SYSTEM, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = appTheme.isDark(isSystemInDarkTheme())
    val black = appTheme == AppTheme.AMOLED
    val texture by produceState<ShaderBrush?>(null, context.resources) {
        value = withContext(Dispatchers.IO) { ReaderTexture.brush(context.resources) }
    }
    val colors = remember(context, dark, black) {
        val accent = if (Build.VERSION.SDK_INT >= 31) {
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else if (dark) darkColorScheme() else lightColorScheme()
        fun tone(night: Long, day: Long) = Color(if (dark) night else day)
        accent.copy(
            background = if (black) Color.Black else tone(0xFF101418, 0xFFF5F6F7),
            surface = if (black) Color.Black else tone(0xFF101418, 0xFFF5F6F7),
            onBackground = tone(0xFFE5EBEF, 0xFF1A2329), onSurface = tone(0xFFE5EBEF, 0xFF1A2329),
            onSurfaceVariant = tone(0xFFAFBAC2, 0xFF56636C),
            surfaceDim = if (black) Color.Black else tone(0xFF101418, 0xFFDDE3E7), surfaceBright = tone(0xFF343C43, 0xFFFAFCFD),
            surfaceContainerLowest = if (black) Color.Black else tone(0xFF0B0F12, 0xFFFFFFFF),
            surfaceContainerLow = if (black) Color(0xFF111111) else tone(0xFF171D22, 0xFFF0F3F5),
            surfaceContainer = if (black) Color(0xFF181818) else tone(0xFF1D242A, 0xFFEAF0F3),
            surfaceContainerHigh = if (black) Color(0xFF202020) else tone(0xFF262E35, 0xFFE2E9ED),
            surfaceContainerHighest = if (black) Color(0xFF292929) else tone(0xFF303A42, 0xFFD8E1E7),
            surfaceVariant = tone(0xFF303A42, 0xFFD8E1E7),
            outline = tone(0xFF64727D, 0xFF77858F), outlineVariant = tone(0xFF34404A, 0xFFC8D3DB),
        )
    }
    val view = LocalView.current
    SideEffect {
        (view.context as? Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(colorScheme = colors, typography = ReaderTypography,
        shapes = Shapes(small = RoundedCornerShape(8.dp), medium = RoundedCornerShape(12.dp),
            large = RoundedCornerShape(16.dp), extraLarge = RoundedCornerShape(20.dp))) {
        CompositionLocalProvider(LocalContentColor provides colors.onSurface, LocalAppTheme provides appTheme,
            LocalReaderTexture provides texture) {
            Box(Modifier.fillMaxSize().readerBackground()) { content() }
        }
    }
}

/** Explicit brightness remains useful for host screens and UI tests. */
@Composable
fun ReaderTheme(dark: Boolean, content: @Composable () -> Unit) =
    ReaderTheme(if (dark) AppTheme.DARK else AppTheme.LIGHT, content)

/** One immutable 96px tile and shader, shared by all chrome. No timers or generated frames. */
private object ReaderTexture {
    private var brush: ShaderBrush? = null

    @Synchronized fun brush(resources: android.content.res.Resources): ShaderBrush = brush ?: run {
        val image = checkNotNull(BitmapFactory.decodeResource(resources, R.drawable.reader_noise,
            BitmapFactory.Options().apply { inScaled = false })).asImageBitmap()
        ShaderBrush(ImageShader(image, TileMode.Repeated, TileMode.Repeated)).also { brush = it }
    }
}

/** Grain only, for a Material surface that already paints its own color. */
@Composable
internal fun Modifier.readerTexture(): Modifier {
    val brush = LocalReaderTexture.current ?: return this
    return drawBehind { drawRect(brush) }
}

@Composable
internal fun Modifier.readerBackground(raised: Boolean = false, color: Color = Color.Unspecified): Modifier {
    val surface = if (color != Color.Unspecified) color else if (raised)
        MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surface
    val base = background(surface)
    return if (LocalAppTheme.current == AppTheme.AMOLED && surface == Color.Black) base else base.readerTexture()
}
