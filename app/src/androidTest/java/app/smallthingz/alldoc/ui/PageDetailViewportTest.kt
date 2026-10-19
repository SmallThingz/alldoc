package app.smallthingz.alldoc.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.smallthingz.alldoc.ReaderTestActivity
import app.smallthingz.alldoc.engine.PageRegion
import app.smallthingz.alldoc.state.ViewportState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PageDetailViewportTest {
    @Test fun delayedDetailReplacesVisiblePixelsThroughTheSameCropAndRotationWithoutMovingThePage() {
        val base = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(AndroidColor.GRAY) }
        val initialPose = ViewportState(zoom = 16f, centerX = .5f, centerY = .5f, rotation = 1)
        val pose = mutableStateOf(initialPose)
        val details = mutableStateOf<Map<Int, ReaderPageDetail>>(emptyMap())
        val requested = AtomicReference<PageRegion>()
        val bounds = AtomicReference<Rect>()
        val positions = AtomicInteger()
        var patch: Bitmap? = null
        try {
            ActivityScenario.launch(ReaderTestActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent {
                    CompositionLocalProvider(LocalDensity provides Density(1f)) {
                        MaterialTheme {
                            ReaderPages(830L, 1, 0, .5f, pose.value, 0L,
                                mapOf(0 to ReaderPageImage(base, RectF(.1f, .1f, .9f, .9f))), mapOf(0 to .5f), {},
                                { _, _ -> positions.incrementAndGet() }, { pose.value = it }, {},
                                Modifier.width(240.dp).height(240.dp).onGloballyPositioned { layout ->
                                    val origin = layout.localToWindow(Offset.Zero)
                                    val left = origin.x.roundToInt(); val top = origin.y.roundToInt()
                                    bounds.set(Rect(left, top, left + layout.size.width, top + layout.size.height))
                                }, normalEdgePadding = 0.dp, details = details.value,
                                onVisibleRegions = { it[0]?.let(requested::set) })
                        }
                    }
                } }
                await { requested.get() != null && bounds.get() != null }
                val region = requested.get()
                assertEquals(240, region.width)
                assertEquals(240, region.height)
                assertTrue(region.left >= .1f && region.top >= .1f && region.right <= .9f && region.bottom <= .9f)
                val beforePositions = positions.get()
                awaitPixels(scenario, bounds.get(), intArrayOf(AndroidColor.GRAY, AndroidColor.GRAY, AndroidColor.GRAY, AndroidColor.GRAY))
                patch = Bitmap.createBitmap(region.width, region.height, Bitmap.Config.ARGB_8888).also { bitmap ->
                    val canvas = Canvas(bitmap)
                    val paint = Paint()
                    val halfWidth = bitmap.width / 2f; val halfHeight = bitmap.height / 2f
                    for ((index, color) in listOf(AndroidColor.RED, AndroidColor.GREEN, AndroidColor.BLUE, AndroidColor.YELLOW).withIndex()) {
                        paint.color = color
                        val left = index % 2 * halfWidth; val top = index / 2 * halfHeight
                        canvas.drawRect(left, top, left + halfWidth, top + halfHeight, paint)
                    }
                }
                scenario.onActivity { details.value = mapOf(0 to ReaderPageDetail(requireNotNull(patch), region)) }
                // A clockwise quarter-turn maps source BL/TL/BR/TR into screen TL/TR/BL/BR.
                awaitPixels(scenario, bounds.get(), intArrayOf(AndroidColor.BLUE, AndroidColor.RED, AndroidColor.YELLOW, AndroidColor.GREEN))
                assertEquals(initialPose, pose.value)
                assertEquals("Detail replacement must not rewrite the reading anchor", beforePositions, positions.get())
            }
        } finally { patch?.recycle(); base.recycle() }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5000
        while (!condition()) {
            check(SystemClock.uptimeMillis() < deadline) { "Visible detail did not settle" }
            SystemClock.sleep(16)
        }
    }

    private fun awaitPixels(scenario: ActivityScenario<ReaderTestActivity>, bounds: Rect, colors: IntArray) {
        await {
            val pixels = Bitmap.createBitmap(bounds.width(), bounds.height(), Bitmap.Config.ARGB_8888)
            val copied = CountDownLatch(1)
            val result = AtomicInteger(-1)
            try {
                scenario.onActivity { activity ->
                    PixelCopy.request(activity.window, bounds, pixels, { result.set(it); copied.countDown() }, Handler(Looper.getMainLooper()))
                }
                check(copied.await(2, TimeUnit.SECONDS)) { "Viewport capture timed out" }
                result.get() == PixelCopy.SUCCESS && colors.indices.all { index ->
                    pixels.getPixel(pixels.width * (if (index % 2 == 0) 1 else 3) / 4,
                        pixels.height * (if (index / 2 == 0) 1 else 3) / 4) == colors[index]
                }
            } finally { pixels.recycle() }
        }
    }
}
