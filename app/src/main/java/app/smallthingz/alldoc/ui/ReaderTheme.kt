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
import androidx.compose.ui.platform.LocalConfiguration
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

// Reader chrome is original AllDoc code. The background tile is Reverb's GPL-3.0 asset;
// its source revision, license and unchanged SHA-256 are recorded in docs/UI_REFERENCE.md.
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
    val configuration = LocalConfiguration.current
    val dark = appTheme.isDark(isSystemInDarkTheme())
    val black = appTheme == AppTheme.AMOLED
    val texture by produceState<ShaderBrush?>(null, context.resources, black) {
        value = if (black) null else withContext(Dispatchers.IO) {
            try { ReaderTexture.brush(context.resources) } catch (_: OutOfMemoryError) { null }
        }
    }
    val colors = remember(context, configuration, dark, black) {
        val palette = if (Build.VERSION.SDK_INT >= 31) {
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else if (dark) darkColorScheme() else lightColorScheme()
        readerColorScheme(palette, black)
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
            Box(Modifier.fillMaxSize().background(colors.background)) { content() }
        }
    }
}

internal fun readerColorScheme(palette: ColorScheme, black: Boolean): ColorScheme =
    if (!black) palette else palette.copy(background = Color.Black, surface = Color.Black,
        surfaceDim = Color.Black, surfaceContainerLowest = Color.Black)

/** Explicit brightness remains useful for host screens and UI tests. */
@Composable
fun ReaderTheme(dark: Boolean, content: @Composable () -> Unit) =
    ReaderTheme(if (dark) AppTheme.DARK else AppTheme.LIGHT, content)

/** One immutable 1024px tile and shader for backgrounds; no timers or generated frames. */
private object ReaderTexture {
    private var brush: ShaderBrush? = null

    @Synchronized fun brush(resources: android.content.res.Resources): ShaderBrush = brush ?: run {
        val image = checkNotNull(BitmapFactory.decodeResource(resources, R.drawable.reader_noise,
            BitmapFactory.Options().apply { inScaled = false })).asImageBitmap()
        ShaderBrush(ImageShader(image, TileMode.Repeated, TileMode.Repeated)).also { brush = it }
    }
}

@Composable
internal fun Modifier.readerBackground(raised: Boolean = false, color: Color = Color.Unspecified): Modifier {
    val surface = if (color != Color.Unspecified) color else if (raised)
        MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surface
    val base = background(surface)
    val brush = LocalReaderTexture.current
    return if (raised || brush == null || LocalAppTheme.current == AppTheme.AMOLED) base
        else base.drawBehind { drawRect(brush) }
}
