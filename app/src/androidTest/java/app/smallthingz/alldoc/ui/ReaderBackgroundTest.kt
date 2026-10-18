package app.smallthingz.alldoc.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.smallthingz.alldoc.ReaderTestActivity
import app.smallthingz.alldoc.state.AppTheme
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderBackgroundTest {
    @Test fun grainIsStationaryAndConfinedToNonAmoledBackgrounds() {
        val theme = mutableStateOf(AppTheme.DARK)
        val bounds = ConcurrentHashMap<String, Rect>()
        fun Modifier.region(name: String) = onGloballyPositioned {
            val r = it.boundsInWindow()
            bounds[name] = Rect(r.left.roundToInt(), r.top.roundToInt(), r.right.roundToInt(), r.bottom.roundToInt())
        }
        // An empty icon isolates the real control surface from its foreground glyph.
        val icon = ImageVector.Builder("Empty", 24.dp, 24.dp, 24f, 24f).build()
        launchReaderTestActivity().use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    ReaderTheme(theme.value) {
                        Column(Modifier.fillMaxSize()) {
                            Box(Modifier.fillMaxWidth().height(128.dp).region("background").readerBackground())
                            Box(Modifier.fillMaxWidth().height(48.dp).region("raised").readerBackground(raised = true))
                            ReaderOverlayAction(icon, "Test control", Modifier.region("control"), onClick = {})
                            Box(Modifier.fillMaxWidth().region("header")) { ReaderHeader("") }
                        }
                    }
                }
            }
            for (mode in listOf(AppTheme.DARK, AppTheme.LIGHT, AppTheme.AMOLED)) {
                scenario.onActivity { it.verifyTestDisplay(); theme.value = mode }
                val surface = when (mode) {
                    AppTheme.DARK -> 0xff101418.toInt()
                    AppTheme.LIGHT -> 0xfff5f6f7.toInt()
                    else -> Color.BLACK
                }
                val raised = when (mode) {
                    AppTheme.DARK -> 0xff262e35.toInt()
                    AppTheme.LIGHT -> 0xffe2e9ed.toInt()
                    else -> 0xff202020.toInt()
                }
                val deadline = SystemClock.uptimeMillis() + 5_000
                var settled: Bitmap? = null
                while (SystemClock.uptimeMillis() < deadline && settled == null) {
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                    val frame = capture(scenario)
                    if (frame == null) { SystemClock.sleep(30); continue }
                    val bg = bounds["background"]?.let { sample(frame, it) }
                    val ready = bg != null && bg.all { closeTo(it, surface) } &&
                        (if (mode == AppTheme.AMOLED) bg.all { it == Color.BLACK } else bg.toSet().size > 3) &&
                        listOf("raised", "control").all { name ->
                            bounds[name]?.let { sample(frame, it).all { pixel -> pixel == raised } } == true
                        } && bounds["header"]?.let { sample(frame, it).all { pixel -> pixel == surface } } == true
                    if (ready) settled = frame else { frame.recycle(); SystemClock.sleep(30) }
                }
                val first = checkNotNull(settled) { "Background/control rendering did not settle for $mode" }
                try {
                    val rect = checkNotNull(bounds["background"])
                    if (mode != AppTheme.AMOLED && rect.width() > 224) {
                        // The old 96px tile repeated exactly at this displacement.
                        val y = rect.centerY()
                        assertTrue("Visible 96px tile repetition", (0 until 64).count { x ->
                            first.getPixel(rect.left + 16 + x, y) != first.getPixel(rect.left + 112 + x, y)
                        } > 16)
                    }
                    SystemClock.sleep(100)
                    val second = checkNotNull(capture(scenario)) { "Settled background lost its surface" }
                    try { assertArrayEquals("Idle grain moved", sample(first, rect), sample(second, rect)) }
                    finally { second.recycle() }
                } finally { first.recycle() }
            }
        }
    }

    private fun sample(bitmap: Bitmap, rect: Rect): IntArray {
        val side = 16
        val x = (rect.centerX() - side / 2).coerceIn(0, bitmap.width - side)
        val y = (rect.centerY() - side / 2).coerceIn(0, bitmap.height - side)
        return IntArray(side * side).also { bitmap.getPixels(it, 0, side, x, y, side, side) }
    }

    private fun closeTo(pixel: Int, base: Int) = Color.alpha(pixel) == 255 &&
        abs(Color.red(pixel) - Color.red(base)) <= 18 &&
        abs(Color.green(pixel) - Color.green(base)) <= 18 &&
        abs(Color.blue(pixel) - Color.blue(base)) <= 18

    private fun capture(scenario: ActivityScenario<ReaderTestActivity>): Bitmap? {
        var bitmap: Bitmap? = null
        val done = CountDownLatch(1)
        val status = AtomicInteger(PixelCopy.ERROR_UNKNOWN)
        scenario.onActivity { activity ->
            activity.verifyTestDisplay()
            val view = activity.window.decorView
            if (view.width > 0 && view.height > 0) {
                bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                PixelCopy.request(activity.window, bitmap!!, { status.set(it); done.countDown() }, Handler(Looper.getMainLooper()))
            } else done.countDown()
        }
        val result = bitmap ?: return null
        try {
            assertTrue("Background capture timed out", done.await(2, TimeUnit.SECONDS))
            if (status.get() == PixelCopy.ERROR_SOURCE_NO_DATA) { result.recycle(); return null }
            assertEquals(PixelCopy.SUCCESS, status.get())
            return result
        } catch (failure: Throwable) {
            result.recycle()
            throw failure
        }
    }
}
