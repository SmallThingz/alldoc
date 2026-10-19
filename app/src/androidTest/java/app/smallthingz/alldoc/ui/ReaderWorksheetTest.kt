package app.smallthingz.alldoc.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
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
        val geometryReady = mutableStateOf(false)
        val pauseZoom = AtomicBoolean()
        val requests = AtomicInteger()
        val gestures = AtomicInteger()
        val failure = mutableStateOf<String?>(null)
        val requested = AtomicReference<SheetViewport?>()
        val retried = AtomicBoolean()
        val image = Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
        val frame = SheetFrame(image, SheetViewport(0, 0.0, 0.0, 1.0, 600, 600))
        try {
            launchReaderTestActivity().use { scenario ->
                val geometries = sheets.map { SheetGeometry(it) }
                scenario.onActivity { activity -> activity.setContent { MaterialTheme {
                    ReaderWorksheet(1L, WorkbookInfo(sheets, 0),
                        if (geometryReady.value) geometries[position.value.sheet] else null, position.value,
                        frame, loading = true, error = failure.value, onPosition = {
                            position.value = it
                            if (pauseZoom.get() && it.zoom > 1.01f) active.value = false
                        }, onRequest = { requests.incrementAndGet(); requested.set(it) }, onSelectSheet = { position.value = SheetPosition(sheet = it) },
                        onGestureEnd = { gestures.incrementAndGet() }, onRetry = { retried.set(true) }, active = active.value, modifier = Modifier.fillMaxSize())
                } } }
                // Native workbooks publish their geometry after the first composition.
                await { visit(scenario) { node -> labelled(node, "Worksheet") &&
                    node.config.getOrNull(SemanticsProperties.StateDescription) == "Loading" } }
                assertNull("No viewport can be requested before geometry arrives", requested.get())
                scenario.onActivity { geometryReady.value = true }
                await { requested.get() != null && visible(scenario, "Worksheet") && visible(scenario, "Sheet B") }
                val original = requireNotNull(requested.get())
                await { visit(scenario) { node -> labelled(node, "Worksheet") &&
                    node.config.getOrNull(SemanticsActions.RequestFocus)?.action?.invoke() == true } }
                assertTrue("The focused worksheet must consume Right", key(scenario, KeyEvent.KEYCODE_DPAD_RIGHT))
                await { position.value.x > 0.0 }
                val right = position.value.x
                assertTrue("The focused worksheet must consume Page Down", key(scenario, KeyEvent.KEYCODE_PAGE_DOWN))
                await { position.value.y > 0.0 }
                assertEquals("Vertical keys must preserve the horizontal origin", right, position.value.x, 0.000001)
                assertTrue("Home must return to the worksheet origin", key(scenario, KeyEvent.KEYCODE_MOVE_HOME))
                await { position.value.x == 0.0 && position.value.y == 0.0 }
                // Actual two-finger input must update the numeric pose before any semantic zoom.
                val beforePinch = position.value
                val completedBeforePinch = gestures.get()
                touchWorksheet(scenario, pinch = true)
                await { gestures.get() > completedBeforePinch && position.value.zoom > beforePinch.zoom * 1.8f &&
                    requested.get()?.scale?.let { it > original.scale * 1.8 } == true }
                assertEquals("Pinch must stay on the same worksheet", beforePinch.sheet, position.value.sheet)
                assertTrue("Two touch pointers must increase zoom", position.value.zoom > beforePinch.zoom * 1.8f)
                // Restore 1x for the independent double-tap and semantic zoom checks below.
                scenario.onActivity { position.value = beforePinch }
                await { position.value == beforePinch && requested.get()?.let {
                    kotlin.math.abs(it.scale - original.scale) < .000001 && it.x == original.x && it.y == original.y
                } == true }
                val completedBeforeDoubleTap = gestures.get()
                touchWorksheet(scenario, pinch = false, doubleTap = true)
                await { gestures.get() > completedBeforeDoubleTap && position.value.zoom > 1.95f &&
                    requested.get()?.scale?.let { it > original.scale * 1.95 } == true }
                assertEquals("Double tap must use the loaded sheet geometry", 2f, position.value.zoom, .01f)
                scenario.onActivity { position.value = beforePinch }
                await { position.value == beforePinch && requested.get()?.let {
                    kotlin.math.abs(it.scale - original.scale) < .000001 && it.x == original.x && it.y == original.y
                } == true }
                pauseZoom.set(true)
                action(scenario, "Zoom in")
                await { !active.value && !visit(scenario) { node ->
                    labelled(node, "Worksheet") && node.config.getOrNull(SemanticsActions.CustomActions).orEmpty().any { it.label == "Zoom in" }
                } }
                instrumentation.waitForIdleSync()
                val paused = position.value
                val pausedRequests = requests.get()
                SystemClock.sleep(300)
                assertEquals("A modal must stop the worksheet animation", paused, position.value)
                assertEquals("An inactive worksheet must not refine behind a modal", pausedRequests, requests.get())
                val completedBeforeZoom = gestures.get()
                scenario.onActivity { pauseZoom.set(false); active.value = true }
                action(scenario, "Zoom in")
                await { gestures.get() > completedBeforeZoom && position.value.zoom > 1.45f &&
                    requested.get()?.scale?.let { it > original.scale } == true }
                assertTrue("Old pixels remain visible during pending zoom refinement", magentaPixels(scenario) > 1000)
                scenario.onActivity { failure.value = "Temporarily unavailable" }
                await { visible(scenario, "Retry worksheet") }
                click(scenario, "Retry worksheet")
                await { retried.get() }
                assertTrue("A retry error must not clear the last good frame", magentaPixels(scenario) > 1000)
                scenario.onActivity { failure.value = null }
                lateinit var beforePan: SheetPosition
                scenario.onActivity { beforePan = position.value }
                touchWorksheet(scenario, pinch = false)
                await { position.value.x > beforePan.x && position.value.y > beforePan.y }
                click(scenario, "Sheet B")
                await { position.value.sheet == 1 && requested.get()?.sheet == 1 }
                await { magentaPixels(scenario) == 0 }
                assertFalse(image.isRecycled)
            }
        } finally { image.recycle() }
    }

    private fun key(scenario: ActivityScenario<ReaderTestActivity>, code: Int): Boolean {
        var handled = false
        scenario.onActivity { activity ->
            activity.verifyTestDisplay()
            val now = SystemClock.uptimeMillis()
            fun event(action: Int) = KeyEvent(now, now, action, code, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
            handled = activity.dispatchKeyEvent(event(KeyEvent.ACTION_DOWN))
            activity.dispatchKeyEvent(event(KeyEvent.ACTION_UP))
        }
        return handled
    }

    private fun touchWorksheet(scenario: ActivityScenario<ReaderTestActivity>, pinch: Boolean, doubleTap: Boolean = false) {
        var bounds: Rect? = null
        await { visit(scenario) { node ->
            if (labelled(node, "Worksheet") && node.boundsInWindow.width > 0f && node.boundsInWindow.height > 0f) {
                bounds = node.boundsInWindow; true
            } else false
        } }
        val box = requireNotNull(bounds)
        lateinit var touchRoot: View
        val origin = IntArray(2)
        scenario.onActivity { activity ->
            activity.verifyTestDisplay()
            touchRoot = activity.window.decorView
            assertTrue("Worksheet window must be attached and focused", touchRoot.isAttachedToWindow && touchRoot.hasWindowFocus())
            touchRoot.getLocationInWindow(origin)
        }
        var start = SystemClock.uptimeMillis()
        val properties = Array(2) { index -> MotionEvent.PointerProperties().apply {
            id = index; toolType = MotionEvent.TOOL_TYPE_FINGER
        } }
        var progress = 0f
        var pressed = 0
        fun send(action: Int, count: Int) = scenario.onActivity { activity ->
            activity.verifyTestDisplay()
            assertSame("Touch must stay on the scenario's window", activity.window.decorView, touchRoot)
            val coordinates = Array(2) { index -> MotionEvent.PointerCoords().apply {
                if (doubleTap) {
                    x = box.center.x - origin[0]
                    y = box.center.y - origin[1]
                } else if (pinch) {
                    val distance = box.width * (.08f + .16f * progress)
                    x = box.center.x + (if (index == 0) -distance else distance) - origin[0]
                    y = box.center.y - origin[1]
                } else {
                    val fraction = .7f - .35f * progress
                    x = box.left + box.width * fraction - origin[0]
                    y = box.top + box.height * fraction - origin[1]
                }
                pressure = 1f; size = 1f
            } }
            val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, count, properties, coordinates,
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            try { touchRoot.dispatchTouchEvent(event) } finally { event.recycle() }
        }
        try {
            if (doubleTap) {
                repeat(2) { index ->
                    start = SystemClock.uptimeMillis()
                    pressed = 1; send(MotionEvent.ACTION_DOWN, 1)
                    send(MotionEvent.ACTION_UP, 1); pressed = 0
                    if (index == 0) SystemClock.sleep(80)
                }
                return
            }
            pressed = 1; send(MotionEvent.ACTION_DOWN, 1)
            if (pinch) {
                pressed = 2
                send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2)
            }
            val steps = if (pinch) 18 else 12
            repeat(steps) { index ->
                SystemClock.sleep(if (pinch) 16L else 20L)
                progress = (index + 1f) / steps
                send(MotionEvent.ACTION_MOVE, pressed)
            }
            if (pinch) {
                send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2)
                pressed = 1
            }
            send(MotionEvent.ACTION_UP, 1); pressed = 0
        } finally { if (pressed > 0) send(MotionEvent.ACTION_CANCEL, pressed) }
    }

    private fun action(scenario: ActivityScenario<ReaderTestActivity>, label: String) = await { visit(scenario) { node ->
        labelled(node, "Worksheet") && node.config.getOrNull(SemanticsActions.CustomActions)
            ?.firstOrNull { it.label == label }?.action?.invoke() == true
    } }

    private fun labelled(node: SemanticsNode, label: String): Boolean =
        label in node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() ||
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == label }

    private fun visible(scenario: ActivityScenario<ReaderTestActivity>, label: String) = visit(scenario) { labelled(it, label) }
    private fun click(scenario: ActivityScenario<ReaderTestActivity>, label: String) = await { visit(scenario) { node ->
        val action = node.config.getOrNull(SemanticsActions.OnClick)?.action
        action != null && (labelled(node, label) || node.children.any { labelled(it, label) }) && action.invoke()
    } }

    private fun magentaPixels(scenario: ActivityScenario<ReaderTestActivity>): Int {
        var image: Bitmap? = null
        val complete = CountDownLatch(1)
        var status = PixelCopy.ERROR_UNKNOWN
        try {
            scenario.onActivity { activity ->
                activity.verifyTestDisplay()
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

    private fun visit(scenario: ActivityScenario<ReaderTestActivity>, test: (SemanticsNode) -> Boolean): Boolean {
        var matched = false
        scenario.onActivity { activity ->
            fun traverse(node: SemanticsNode, depth: Int): Boolean {
                if (depth > 20) return false
                if (node.boundsInWindow.width > 0f && node.boundsInWindow.height > 0f && test(node)) return true
                return node.children.take(64).any { traverse(it, depth + 1) }
            }
            matched = activity.testSemantics().any { traverse(it, 0) }
        }
        return matched
    }
}
