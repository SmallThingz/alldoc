package app.smallthingz.alldoc.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.smallthingz.alldoc.ReaderTestActivity
import app.smallthingz.alldoc.engine.*
import app.smallthingz.alldoc.state.SheetPosition
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderWorksheetTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun zoomAndPanRetainPixelsAndSheetSwitchNeverShowsAnotherSheetsFrame() {
        val sheets = listOf("Sheet A", "Sheet B").mapIndexed { index, name ->
            SheetInfo(index, name, 1000, 128, 20, 10, 15360.0, 28000.0)
        }
        val position = mutableStateOf(SheetPosition(sheet = 0))
        val active = mutableStateOf(true)
        val pauseZoom = AtomicBoolean()
        val requests = AtomicInteger()
        val failure = mutableStateOf<String?>(null)
        val requested = AtomicReference<SheetViewport?>()
        val retried = AtomicBoolean()
        val image = Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
        val frame = SheetFrame(image, SheetViewport(0, 0.0, 0.0, 1.0, 600, 600))
        try {
            ActivityScenario.launch(ReaderTestActivity::class.java).use { scenario ->
                val geometries = sheets.map { SheetGeometry(it) }
                scenario.onActivity { activity -> activity.setContent { MaterialTheme {
                    ReaderWorksheet(1L, WorkbookInfo(sheets, 0), geometries[position.value.sheet], position.value,
                        frame, loading = true, error = failure.value, onPosition = {
                            position.value = it
                            if (pauseZoom.get() && it.zoom > 1.01f) active.value = false
                        }, onRequest = { requests.incrementAndGet(); requested.set(it) }, onSelectSheet = { position.value = SheetPosition(sheet = it) },
                        onGestureEnd = {}, onRetry = { retried.set(true) }, active = active.value, modifier = Modifier.fillMaxSize())
                } } }
                await { requested.get() != null && visible("Worksheet") && visible("Sheet B") }
                val original = requireNotNull(requested.get())
                await { visit { node -> node.contentDescription?.toString() == "Worksheet" &&
                    node.performAction(AccessibilityNodeInfo.ACTION_FOCUS) } }
                assertTrue("The focused worksheet must consume Right", key(scenario, KeyEvent.KEYCODE_DPAD_RIGHT))
                await { position.value.x > 0.0 }
                val right = position.value.x
                assertTrue("The focused worksheet must consume Page Down", key(scenario, KeyEvent.KEYCODE_PAGE_DOWN))
                await { position.value.y > 0.0 }
                assertEquals("Vertical keys must preserve the horizontal origin", right, position.value.x, 0.000001)
                assertTrue("Home must return to the worksheet origin", key(scenario, KeyEvent.KEYCODE_MOVE_HOME))
                await { position.value.x == 0.0 && position.value.y == 0.0 }
                pauseZoom.set(true)
                action("Zoom in")
                await { !active.value && !visit { node ->
                    node.contentDescription?.toString() == "Worksheet" && node.actionList.any { it.label?.toString() == "Zoom in" }
                } }
                instrumentation.waitForIdleSync()
                val paused = position.value
                val pausedRequests = requests.get()
                SystemClock.sleep(300)
                assertEquals("A modal must stop the worksheet animation", paused, position.value)
                assertEquals("An inactive worksheet must not refine behind a modal", pausedRequests, requests.get())
                scenario.onActivity { pauseZoom.set(false); active.value = true }
                action("Zoom in")
                await { position.value.zoom > 1.45f && requested.get()?.scale?.let { it > original.scale } == true }
                assertTrue("Old pixels remain visible during pending zoom refinement", magentaPixels(scenario) > 1000)
                scenario.onActivity { failure.value = "Temporarily unavailable" }
                await { visible("Retry worksheet") }
                click("Retry worksheet")
                await { retried.get() }
                assertTrue("A retry error must not clear the last good frame", magentaPixels(scenario) > 1000)
                scenario.onActivity { failure.value = null }
                dragWorksheet()
                await { position.value.x > 0 && position.value.y > 0 }
                click("Sheet B")
                await { position.value.sheet == 1 && requested.get()?.sheet == 1 }
                await { magentaPixels(scenario) == 0 }
                assertFalse(image.isRecycled)
            }
        } finally { image.recycle() }
    }

    private fun key(scenario: ActivityScenario<ReaderTestActivity>, code: Int): Boolean {
        var handled = false
        scenario.onActivity { activity ->
            val now = SystemClock.uptimeMillis()
            fun event(action: Int) = KeyEvent(now, now, action, code, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
            handled = activity.dispatchKeyEvent(event(KeyEvent.ACTION_DOWN))
            activity.dispatchKeyEvent(event(KeyEvent.ACTION_UP))
        }
        return handled
    }

    private fun dragWorksheet() {
        var bounds: Rect? = null
        await { visit { node ->
            if (node.contentDescription?.toString() == "Worksheet") {
                bounds = Rect().also(node::getBoundsInScreen); true
            } else false
        } }
        val box = requireNotNull(bounds)
        val start = SystemClock.uptimeMillis()
        fun send(action: Int, fraction: Float) {
            val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action,
                box.left + box.width() * fraction, box.top + box.height() * fraction, 0).apply {
                source = InputDevice.SOURCE_TOUCHSCREEN
            }
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        send(MotionEvent.ACTION_DOWN, .7f)
        for (step in 1..12) { SystemClock.sleep(20); send(MotionEvent.ACTION_MOVE, .7f - .35f * step / 12) }
        send(MotionEvent.ACTION_UP, .35f)
    }

    private fun action(label: String) = await { visit { node ->
        node.contentDescription?.toString() == "Worksheet" && node.actionList.firstOrNull {
            it.label?.toString() == label
        }?.let { node.performAction(it.id) } == true
    } }

    private fun visible(label: String) = visit { it.contentDescription?.toString() == label || it.text?.toString() == label }
    private fun click(label: String) = await { visit { node ->
        if (node.contentDescription?.toString() != label && node.text?.toString() != label) false
        else if (node.isClickable) node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        else node.parent?.let { parent -> try { parent.performAction(AccessibilityNodeInfo.ACTION_CLICK) } finally { parent.recycle() } } == true
    } }

    private fun magentaPixels(scenario: ActivityScenario<ReaderTestActivity>): Int {
        var image: Bitmap? = null
        val complete = CountDownLatch(1)
        var status = PixelCopy.ERROR_UNKNOWN
        try {
            scenario.onActivity { activity ->
                image = Bitmap.createBitmap(activity.window.decorView.width, activity.window.decorView.height, Bitmap.Config.ARGB_8888)
                PixelCopy.request(activity.window, requireNotNull(image), { status = it; complete.countDown() }, Handler(Looper.getMainLooper()))
            }
            assertTrue(complete.await(5, TimeUnit.SECONDS)); assertEquals(PixelCopy.SUCCESS, status)
            val bitmap = requireNotNull(image)
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            return pixels.count { it == Color.MAGENTA }
        } finally { image?.recycle() }
    }

    private fun await(predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 8000
        while (SystemClock.uptimeMillis() < deadline) { if (predicate()) return; SystemClock.sleep(30) }
        fail("Worksheet did not reach the expected UI state")
    }

    @Suppress("DEPRECATION")
    private fun visit(test: (AccessibilityNodeInfo) -> Boolean): Boolean {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return false
        fun traverse(node: AccessibilityNodeInfo, depth: Int): Boolean {
            if (depth > 20 || !node.refresh() || !node.isVisibleToUser) return false
            if (node.packageName?.toString() == "app.smallthingz.alldoc" && test(node)) return true
            for (index in 0 until minOf(node.childCount, 64)) {
                val child = node.getChild(index) ?: continue
                try { if (traverse(child, depth + 1)) return true } finally { child.recycle() }
            }
            return false
        }
        return try { traverse(root, 0) } finally { root.recycle() }
    }
}
