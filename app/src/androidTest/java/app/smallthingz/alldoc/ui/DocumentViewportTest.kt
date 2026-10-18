package app.smallthingz.alldoc.ui

import android.graphics.Bitmap
import android.graphics.RectF
import android.os.SystemClock
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.PixelCopy
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.smallthingz.alldoc.ReaderTestActivity
import app.smallthingz.alldoc.state.ViewportState
import app.smallthingz.alldoc.theme.BitmapTheme
import app.smallthingz.alldoc.theme.DocumentColors
import app.smallthingz.alldoc.theme.ReadingMode
import app.smallthingz.alldoc.theme.ThemeSettings
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DocumentViewportTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun readingProbeTracksTheNotesSizedViewportWhileAnchorsAndEndPaddingStayIndependent() {
        val bitmap = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.WHITE) }
        val height = mutableStateOf(600.dp)
        val horizontal = mutableStateOf(false)
        val pose = mutableStateOf(ViewportState())
        val finishes = AtomicInteger()
        val current = AtomicInteger(-1)
        val anchor = AtomicReference(0 to 0f)
        val directions = AtomicInteger()
        val root = AtomicReference<ViewRootForTest>()
        fun node(label: String? = null): SemanticsNode? {
            fun find(value: SemanticsNode): SemanticsNode? {
                if (if (label == null) value.config.getOrNull(SemanticsActions.ScrollBy)?.action != null
                    else value.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true) return value
                for (child in value.children) find(child)?.let { return it }
                return null
            }
            return root.get()?.semanticsOwner?.unmergedRootSemanticsNode?.let(::find)
        }
        fun await(test: () -> Boolean) {
            val end = SystemClock.uptimeMillis() + 5000
            while (!test()) {
                check(SystemClock.uptimeMillis() < end) { "Reading probe did not settle: ${current.get()} / ${anchor.get()}" }
                SystemClock.sleep(16)
            }
        }
        try { ActivityScenario.launch(ReaderTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                val host = LocalView.current
                SideEffect { root.set(host as ViewRootForTest) }
                MaterialTheme {
                    ReaderPages(110L, 6, 0, 0f, pose.value, 0L,
                        (0..5).associateWith { ReaderPageImage(bitmap) }, (0..5).associateWith { .5f }, {},
                        { page, fraction -> anchor.set(page to fraction) }, { pose.value = it }, { finishes.incrementAndGet() },
                        Modifier.width(240.dp).height(height.value), horizontal = horizontal.value, onCurrentPage = current::set,
                        onScrollDirection = { directions.incrementAndGet() })
                }
            } }
            await { current.get() == 1 }
            assertEquals("The probe must not replace the first-visible anchor", 0 to 0f, anchor.get())
            scenario.onActivity {
                val area = checkNotNull(node("Document pages")).boundsInRoot
                val first = checkNotNull(node("Page 1")).boundsInRoot
                val padding = 16f * it.resources.displayMetrics.density
                assertEquals(padding, first.top - area.top, 1f)
            }
            var before = finishes.get()
            scenario.onActivity { horizontal.value = true }
            await { current.get() == 1 && anchor.get().first == 1 && finishes.get() > before }
            before = finishes.get()
            scenario.onActivity { horizontal.value = false }
            await { current.get() == 1 && anchor.get().first == 0 && finishes.get() > before }
            val restored = anchor.get()
            scenario.onActivity { height.value = 300.dp }
            await { current.get() == 0 }
            assertEquals(restored, anchor.get())
            assertEquals("Resizing/restoring cannot masquerade as user scrolling", 0, directions.get())
            scenario.onActivity {
                val scroll = checkNotNull(node())
                assertTrue(checkNotNull(scroll.config.getOrNull(SemanticsActions.ScrollBy)?.action)(0f, 10000f))
            }
            await {
                var atEnd = false
                scenario.onActivity {
                    val area = node("Document pages")?.boundsInRoot
                    val last = node("Page 6")?.boundsInRoot
                    val padding = 16f * it.resources.displayMetrics.density
                    atEnd = area != null && last != null && kotlin.math.abs(area.bottom - last.bottom - padding) < 1f
                }
                atEnd
            }
            assertTrue(anchor.get().first > 0)
        } } finally { bitmap.recycle() }
    }

    @Test fun switchingModesAtAnExactGapMidpointKeepsTheDisplayedPage() {
        val image = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888)
        val horizontal = mutableStateOf(false)
        val pose = mutableStateOf(ViewportState())
        val current = AtomicInteger(-1)
        val finished = AtomicInteger()
        val anchor = AtomicReference(0 to 0f)
        val root = AtomicReference<ViewRootForTest>()
        fun node(label: String): SemanticsNode? {
            fun find(value: SemanticsNode): SemanticsNode? {
                if (value.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true) return value
                for (child in value.children) find(child)?.let { return it }
                return null
            }
            return root.get()?.semanticsOwner?.unmergedRootSemanticsNode?.let(::find)
        }
        fun await(test: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 5000
            while (!test()) {
                check(SystemClock.uptimeMillis() < deadline) { "Midpoint transition did not settle: ${current.get()} / ${anchor.get()}" }
                SystemClock.sleep(16)
            }
        }
        try { ActivityScenario.launch(ReaderTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                val host = LocalView.current
                SideEffect { root.set(host as ViewRootForTest) }
                // Exact pixel geometry keeps the tie reproducible on every device density.
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    ReaderPages(113L, 6, 0, 0f, pose.value, 0L,
                        (0..5).associateWith { ReaderPageImage(image) }, (0..5).associateWith { .5f }, {},
                        { page, fraction -> anchor.set(page to fraction) }, { pose.value = it }, { finished.incrementAndGet() },
                        Modifier.width(240.dp).height(420.dp), horizontal = horizontal.value, onCurrentPage = current::set)
                }
            } }
            await { current.get() == 0 }
            scenario.onActivity {
                val area = checkNotNull(node("Document pages")).boundsInRoot
                val first = checkNotNull(node("Page 1")).boundsInRoot
                val second = checkNotNull(node("Page 2")).boundsInRoot
                assertEquals(240f, area.width, 0f)
                assertEquals(420f, area.height, 0f)
                val probe = area.top + area.height / 3f
                assertEquals(4f, probe - first.bottom, 0f)
                assertEquals(4f, second.top - probe, 0f)
            }
            for (present in listOf(true, false)) {
                val before = finished.get()
                scenario.onActivity { horizontal.value = present }
                await { finished.get() > before && current.get() == 0 }
                assertEquals("Mode changes must retain the page shown by the counter", 0, anchor.get().first)
                assertEquals(0f, anchor.get().second, .001f)
            }
        } } finally { image.recycle() }
    }

    @Test fun zoomingInALaterPageGapUsesItsAdjacentEdge() {
        val image = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888)
        val pose = mutableStateOf(ViewportState())
        val current = AtomicInteger(-1)
        val finished = AtomicInteger()
        val root = AtomicReference<ViewRootForTest>()
        fun node(label: String): SemanticsNode? {
            fun find(value: SemanticsNode): SemanticsNode? {
                if (value.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true) return value
                for (child in value.children) find(child)?.let { return it }
                return null
            }
            return root.get()?.semanticsOwner?.unmergedRootSemanticsNode?.let(::find)
        }
        fun await(test: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 5000
            while (!test()) {
                check(SystemClock.uptimeMillis() < deadline) { "Gap zoom did not settle: ${pose.value}" }
                SystemClock.sleep(16)
            }
        }
        try { ActivityScenario.launch(ReaderTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                val host = LocalView.current
                SideEffect { root.set(host as ViewRootForTest) }
                ReaderPages(112L, 6, 0, 0f, pose.value, 0L,
                    (0..5).associateWith { ReaderPageImage(image) }, (0..5).associateWith { .5f }, {}, { _, _ -> },
                    { pose.value = it }, { finished.incrementAndGet() }, Modifier.width(240.dp).height(540.dp),
                    onCurrentPage = current::set)
            } }
            await { current.get() == 1 }
            var edge = 0f
            var focus = 0f
            val before = finished.get()
            scenario.onActivity {
                val area = checkNotNull(node("Document pages"))
                val previous = checkNotNull(node("Page 2")).boundsInRoot
                edge = checkNotNull(node("Page 3")).boundsInRoot.top
                focus = area.boundsInRoot.center.y
                assertTrue("The zoom center must be in the gap after two visible pages", focus > previous.bottom && focus < edge)
                assertTrue("The following page is the closer edge", edge - focus < focus - previous.bottom)
                val zoom = checkNotNull(area.config.getOrNull(SemanticsActions.CustomActions)?.first { action -> action.label == "Zoom in" })
                assertTrue(zoom.action())
            }
            await { pose.value.zoom == 1.5f && finished.get() > before }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                val after = checkNotNull(node("Page 3")).boundsInRoot.top
                // Only the tiny distance to this adjacent page scales, not every preceding gap.
                val expected = focus + (edge - focus) * pose.value.zoom
                assertEquals("Zoom must stay local to the touched gap", expected, after, 2f)
            }
        } } finally { image.recycle() }
    }

    @Test fun doubleTapKeepsLaterPagePixelsAtTheFocusWithLargeEdgePadding() = checkDoubleTapFocus(false)

    @Test fun doubleTapKeepsLaterPagePixelsWhenDetailGeometryRefreshes() = checkDoubleTapFocus(true)

    @Test fun coveringDuringZoomSavesTheMeasuredAnchorBeforeReactivation() = checkDoubleTapFocus(false, true)

    private fun checkDoubleTapFocus(refreshAspects: Boolean, coverDuringZoom: Boolean = false) {
        // Exercise actual LazyColumn layout and input; the oracle is a drawn stripe,
        // independent of the scroll-anchor helpers and saved callback values.
        val bitmap = Bitmap.createBitmap(240, 360, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.WHITE)
            setPixels(IntArray(240 * 4) { android.graphics.Color.MAGENTA }, 0, 240, 0, 38, 240, 4)
        }
        val images = (0..5).associateWith { ReaderPageImage(bitmap) }
        val aspects = mutableStateOf((0..5).associateWith { 1.5f })
        val refinements = AtomicInteger()
        val pose = mutableStateOf(ViewportState())
        val active = mutableStateOf(true)
        val covered = AtomicBoolean()
        val position = AtomicReference(1 to 218f / 368f)
        val saved = AtomicReference(position.get())
        val finishes = AtomicInteger()
        val current = AtomicInteger(-1)
        val bounds = AtomicReference<android.graphics.Rect>()
        fun await(message: String, condition: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 5000
            while (!condition()) {
                check(SystemClock.uptimeMillis() < deadline) { "$message; zoom=${pose.value.zoom}, page=${current.get()}" }
                SystemClock.sleep(16)
            }
        }
        fun find(node: SemanticsNode, label: String): SemanticsNode? {
            if (node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true) return node
            return node.children.firstNotNullOfOrNull { find(it, label) }
        }
        try { launchReaderTestActivity().use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    MaterialTheme {
                        ReaderPages(114L, 6, 1, 218f / 368f, pose.value, 0L, images, aspects.value, {},
                            { page, fraction -> position.set(page to fraction) },
                            {
                                pose.value = it
                                if (coverDuringZoom && it.zoom >= 1.5f && covered.compareAndSet(false, true)) active.value = false
                            }, { saved.set(position.get()); finishes.incrementAndGet() },
                            Modifier.width(240.dp).height(540.dp).onGloballyPositioned { layout ->
                                val origin = layout.localToWindow(Offset.Zero)
                                val left = origin.x.roundToInt(); val top = origin.y.roundToInt()
                                bounds.set(android.graphics.Rect(left, top, left + layout.size.width, top + layout.size.height))
                            }, normalEdgePadding = 80.dp, onCurrentPage = current::set, active = active.value,
                            onZoomAnimation = { moving ->
                                if (refreshAspects && !moving) {
                                    // Detail metadata can change by one raster row before settled capture.
                                    // The source page is the same; this quarter-pixel row-size difference
                                    // must not turn its transient negative scroll request into a page jump.
                                    aspects.value = aspects.value + (2 to if (aspects.value[2] == 1.5f) 1441f / 960f else 1.5f)
                                    refinements.incrementAndGet()
                                }
                            })
                    }
                }
            } }
            await("Later-page fixture did not settle") {
                var placed = false
                scenario.onActivity { activity ->
                    val roots = activity.testSemantics()
                    val area = roots.firstNotNullOfOrNull { find(it, "Document pages") }?.boundsInRoot
                    val previous = roots.firstNotNullOfOrNull { find(it, "Page 2") }?.boundsInRoot
                    val page = roots.firstNotNullOfOrNull { find(it, "Page 3") }?.boundsInRoot
                    placed = area != null && previous != null && page != null &&
                        kotlin.math.abs(page.top - area.top - 230f) < 1f &&
                        kotlin.math.abs(previous.bottom - area.top - 222f) < 1f
                }
                current.get() == 1 && bounds.get() != null && placed
            }
            scenario.onActivity { activity ->
                val roots = activity.testSemantics()
                val area = checkNotNull(roots.firstNotNullOfOrNull { find(it, "Document pages") }).boundsInRoot
                val page = checkNotNull(roots.firstNotNullOfOrNull { find(it, "Page 3") }).boundsInRoot
                assertEquals(240f, area.width, 0f)
                assertEquals(540f, area.height, 0f)
                val previous = checkNotNull(roots.firstNotNullOfOrNull { find(it, "Page 2") }).boundsInRoot
                assertEquals("The zoom target must be a later visible page", 230f, page.top - area.top, 1f)
                assertEquals(222f, previous.bottom - area.top, 1f)
            }
            val area = checkNotNull(bounds.get())
            // Page 3 starts at y230; its stripe at source y40 lies under y270.
            // Enlarging about y270 moves its top to y190, beyond the 80px leading
            // edge: LazyColumn must normalize a negative request through page 2.
            val focusX = area.left + 120f
            val focusY = area.top + 270f
            fun doubleTap() {
                fun tap() {
                    val down = SystemClock.uptimeMillis()
                    for (action in intArrayOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                        scenario.onActivity { activity ->
                            activity.verifyTestDisplay()
                            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, focusX, focusY, 0)
                                .apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
                            try { assertTrue("Owned reader rejected touch", activity.dispatchTouchEvent(event)) }
                            finally { event.recycle() }
                        }
                        if (action == MotionEvent.ACTION_DOWN) SystemClock.sleep(24)
                    }
                }
                tap(); SystemClock.sleep(80); tap()
            }
            fun stripe(phase: String, expectedThickness: Int): Float {
                instrumentation.waitForIdleSync()
                var latest: Bitmap? = null
                var rows = emptyList<Int>()
                try {
                    await("The $phase frame did not draw its expected zoomed stripe") {
                        copyViewport(scenario, area)?.let { image ->
                            latest?.recycle(); latest = image
                            rows = (0 until image.height).filter { y ->
                                val pixel = image.getPixel(image.width / 2, y)
                                // Include half-covered filtered edges when measuring the magenta stripe.
                                android.graphics.Color.red(pixel) > 220 && android.graphics.Color.blue(pixel) > 220 &&
                                    android.graphics.Color.green(pixel) < 128
                            }
                        }
                        rows.size in (expectedThickness - 1)..(expectedThickness + 1)
                    }
                    assertEquals("The stripe must be one contiguous band", rows.size, rows.last() - rows.first() + 1)
                    return (rows.first() + rows.last()) / 2f
                } finally {
                    try { latest?.let { image ->
                        val target = File(instrumentation.targetContext.cacheDir, "viewport-pixels/zoom-anchor-$phase.png")
                        check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
                        target.outputStream().use { output -> check(image.compress(Bitmap.CompressFormat.PNG, 100, output)) }
                    } } finally { latest?.recycle() }
                }
            }
            assertEquals("No detail refresh precedes the first zoom", 0, refinements.get())
            val prefix = if (coverDuringZoom) "covered-" else if (refreshAspects) "detail-" else ""
            val original = stripe("${prefix}initial", 4)
            assertEquals("The authored stripe must start under the fixed touch focus", 270f, original, 1f)
            if (coverDuringZoom) {
                val before = finishes.get()
                doubleTap()
                await("Covered zoom did not save after stopping") {
                    covered.get() && finishes.get() >= before + if (pose.value.zoom < 2f) 2 else 1
                }
                val stopped = pose.value
                val width = (4f * stopped.zoom).roundToInt()
                assertEquals(original, stripe("${prefix}stopped", width), 2f)
                val snapshot = saved.get()
                val extent = 360f * stopped.zoom
                val offset = (snapshot.second * (extent + 8f)).roundToInt()
                val pageTop = 80f - offset + (2 - snapshot.first) * (extent.roundToInt() + 8)
                assertEquals("The saved anchor must restore the drawn document point", original,
                    pageTop + 39.5f * stopped.zoom, 2f)
                scenario.onActivity { active.value = true }
                instrumentation.waitForIdleSync()
                assertEquals(stopped, pose.value)
                assertEquals(snapshot, saved.get())
                assertEquals(original, stripe("${prefix}reactivated", width), 2f)
                return@use
            }
            repeat(2) { cycle ->
                for ((target, width) in listOf(2f to 8, 1f to 4)) {
                    val before = finishes.get()
                    doubleTap()
                    await("Double tap did not finish at $target") {
                        kotlin.math.abs(pose.value.zoom - target) < 0.00001f && finishes.get() > before
                    }
                    if (refreshAspects) assertEquals("Every zoom completion must publish its detail metadata",
                        cycle * 2 + if (target == 2f) 1 else 2, refinements.get())
                    val phase = "${prefix}cycle-${cycle + 1}-zoom-${target.toInt()}"
                    val actual = stripe(phase, width)
                    assertEquals("Visible content drifted at $phase; captures are in viewport-pixels", original, actual, 2f)
                }
            }
        } } finally { bitmap.recycle() }
    }

    @Test fun pinchingInAPresentationLetterboxKeepsItsPageAndZoom() {
        val bitmap = Bitmap.createBitmap(80, 160, Bitmap.Config.ARGB_8888)
        val pose = mutableStateOf(ViewportState())
        val anchor = AtomicReference(1 to 0f)
        val current = AtomicInteger(-1)
        val screen = AtomicReference<androidx.compose.ui.geometry.Rect>()
        val finished = AtomicInteger()
        fun await(test: () -> Boolean) {
            val end = SystemClock.uptimeMillis() + 5000
            while (!test()) {
                check(SystemClock.uptimeMillis() < end) { "Letterbox pinch did not settle: ${anchor.get()} / ${pose.value}" }
                SystemClock.sleep(16)
            }
        }
        try { ActivityScenario.launch(ReaderTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                ReaderPages(111L, 5, 1, 0f, pose.value, 0L,
                    (0..4).associateWith { ReaderPageImage(bitmap) }, (0..4).associateWith { 2f }, {},
                    { page, fraction -> anchor.set(page to fraction) }, { pose.value = it }, { finished.incrementAndGet() },
                    Modifier.width(300.dp).height(180.dp).onGloballyPositioned {
                        val point = it.localToScreen(Offset.Zero)
                        screen.set(androidx.compose.ui.geometry.Rect(point, androidx.compose.ui.geometry.Size(it.size.width.toFloat(), it.size.height.toFloat())))
                    }, horizontal = true, onCurrentPage = current::set)
            } }
            await { screen.get() != null && current.get() == 1 }
            val area = screen.get()
            val x = area.left + area.width * .9f
            val y = area.center.y
            val initial = area.height / 30f
            val down = SystemClock.uptimeMillis()
            val properties = Array(2) { index -> MotionEvent.PointerProperties().apply { id = index; toolType = MotionEvent.TOOL_TYPE_FINGER } }
            fun send(action: Int, distance: Float, count: Int = 2) {
                val coordinates = Array(2) { index -> MotionEvent.PointerCoords().apply {
                    this.x = x; this.y = y + if (index == 0) -distance else distance; pressure = 1f; size = 1f
                } }
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, count, properties, coordinates,
                    0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
                try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
            }
            send(MotionEvent.ACTION_DOWN, initial, 1)
            send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), initial)
            repeat(18) { index -> SystemClock.sleep(16); send(MotionEvent.ACTION_MOVE, initial * (1f + 5f * (index + 1) / 18f)) }
            send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), initial * 6f)
            send(MotionEvent.ACTION_UP, initial * 6f, 1)
            await { finished.get() > 0 && pose.value.zoom > 3f }
            instrumentation.waitForIdleSync()
            assertEquals("Letterbox zoom must retain the same page", 1, anchor.get().first)
            assertEquals(1, current.get())
            assertTrue("Zoom must be preserved instead of resetting to fit", pose.value.zoom > 3f)
        } } finally { bitmap.recycle() }
    }

    @Test @Suppress("DEPRECATION")
    fun presentationSettlesSemanticStepsAndKeyboardScrollingToWholePages() {
        val source = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888)
        val position = mutableStateOf(0 to 0f)
        val pose = mutableStateOf(ViewportState())
        val aspects = mutableStateOf(mapOf(0 to 2f, 1 to .5f, 2 to .5f))
        val restartDuringBackward = AtomicBoolean()
        val finishes = AtomicInteger()
        val root = AtomicReference<ViewRootForTest>()
        fun node(test: (SemanticsNode) -> Boolean): SemanticsNode? {
            fun find(current: SemanticsNode): SemanticsNode? {
                if (test(current)) return current
                for (child in current.children) find(child)?.let { return it }
                return null
            }
            return root.get()?.semanticsOwner?.unmergedRootSemanticsNode?.let(::find)
        }
        fun await(message: String, test: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 5000
            while (SystemClock.uptimeMillis() < deadline) {
                if (test()) return
                SystemClock.sleep(16)
            }
            fail("$message: position=${position.value}, finishes=${finishes.get()}")
        }
        try { ActivityScenario.launch(ReaderTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                val host = LocalView.current
                SideEffect { root.set(host as ViewRootForTest) }
                Column {
                    ReaderPages(99L, 3, 0, 0f, pose.value, 0L,
                        (1..2).associateWith { ReaderPageImage(source) }, aspects.value, {},
                        { page, fraction ->
                            position.value = page to fraction
                            if (page == 0 && fraction > 0f && restartDuringBackward.compareAndSet(true, false))
                                aspects.value = aspects.value + (2 to 1.9f)
                        }, { pose.value = it }, { finishes.incrementAndGet() },
                        Modifier.width(240.dp).height(180.dp), horizontal = true, failedPages = setOf(0))
                    Text("${position.value.first + 1} / 3")
                }
            } }
            instrumentation.waitForIdleSync()
            fun settle(expected: Int, before: Int) {
                await("Expected settled whole page ${expected + 1}") {
                    var canonical = false
                    scenario.onActivity {
                        val page = node { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Page ${expected + 1}") == true }
                        val viewport = node { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Document pages") == true }
                        val counter = node { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "${expected + 1} / 3" } == true }
                        canonical = position.value.first == expected && position.value.second == 0f &&
                            finishes.get() > before && counter != null && page != null && viewport != null &&
                            kotlin.math.abs(page.boundsInRoot.left - viewport.boundsInRoot.left) < 1f &&
                            kotlin.math.abs(page.boundsInRoot.width - viewport.boundsInRoot.width) < 1f
                    }
                    canonical
                }
                instrumentation.waitForIdleSync()
                val settled = finishes.get()
                SystemClock.sleep(80)
                assertEquals("Corrective snapping must not produce a second finish", settled, finishes.get())
            }
            fun scroll(fraction: Float, expected: Int, changeAspectsDuringScroll: Boolean = false) {
                restartDuringBackward.set(changeAspectsDuringScroll)
                val before = finishes.get()
                scenario.onActivity {
                    val target = checkNotNull(node { it.config.getOrNull(SemanticsActions.ScrollBy)?.action != null })
                    val action = checkNotNull(target.config.getOrNull(SemanticsActions.ScrollBy)?.action)
                    assertTrue(action(target.boundsInRoot.width * fraction, 0f))
                }
                settle(expected, before)
                assertFalse("The backward animation must exercise an aspect-driven observer restart", restartDuringBackward.get())
            }
            // Direct semantics bypass flingBehavior. Even isolated small steps must advance in their direction.
            scroll(.1f, 1)
            scroll(-.1f, 0, changeAspectsDuringScroll = true)
            scroll(1f, 1)
            scroll(-.1f, 0)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_TAB)
            fun focusOwner(current: AccessibilityNodeInfo, depth: Int = 0): Boolean {
                if (current.isVisibleToUser && current.isEnabled && current.isClickable && current.isFocusable) {
                    if (current.isFocused) return true
                    current.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    return false
                }
                if (depth == 8) return false
                val parent = current.parent ?: return false
                return try { focusOwner(parent, depth + 1) } finally { parent.recycle() }
            }
            fun focusRetry(current: AccessibilityNodeInfo): Boolean {
                if (current.text?.toString() == "Retry" && current.isVisibleToUser) return focusOwner(current)
                for (index in 0 until current.childCount) {
                    val child = current.getChild(index) ?: continue
                    try { if (focusRetry(child)) return true } finally { child.recycle() }
                }
                return false
            }
            await("Focus real Retry descendant for page key delivery") {
                val window = instrumentation.uiAutomation.rootInActiveWindow ?: return@await false
                try { focusRetry(window) } finally { window.recycle() }
            }
            val before = finishes.get()
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_PAGE_DOWN)
            settle(1, before)
            fun zoom(label: String) {
                scenario.onActivity {
                    val action = node { it.config.getOrNull(SemanticsActions.CustomActions)?.any { action -> action.label == label } == true }
                        ?.config?.getOrNull(SemanticsActions.CustomActions)?.first { it.label == label }
                    assertTrue(checkNotNull(action).action())
                }
            }
            val beforeZoom = finishes.get()
            zoom("Zoom in")
            await("Zoomed page must settle with a nonzero horizontal offset") {
                pose.value.zoom > 1f && position.value.first == 1 && position.value.second > 0f && finishes.get() > beforeZoom
            }
            val beforeFit = finishes.get()
            zoom("Zoom out")
            settle(1, beforeFit) // A pending remeasure must not snap the old zoomed offset into page3.
        } } finally { source.recycle() }
    }

    @Test fun initialLayoutResizeAndRenderReplacementNeverOverwriteRestoredPose() {
        val state = ViewportState(zoom = 2f, centerX = .8f, centerY = .7f, rotation = 1)
        val bitmap = mutableStateOf<Bitmap?>(null)
        val wide = mutableStateOf(false)
        val crop = mutableStateOf<RectF?>(null)
        val changes = AtomicInteger()
        val positions = AtomicInteger()
        val finishes = AtomicInteger()
        ActivityScenario.launch(ReaderTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    PageViewport(bitmap.value, state, { changes.incrementAndGet() }, { finishes.incrementAndGet() },
                        Modifier.width(if (wide.value) 260.dp else 180.dp).height(if (wide.value) 180.dp else 260.dp),
                        crop = crop.value, onPosition = { positions.incrementAndGet() })
                }
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { bitmap.value = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888) }
            instrumentation.waitForIdleSync()
            scenario.onActivity { wide.value = true }
            instrumentation.waitForIdleSync()
            scenario.onActivity { bitmap.value = Bitmap.createBitmap(240, 160, Bitmap.Config.ARGB_8888) }
            instrumentation.waitForIdleSync()
            scenario.onActivity { crop.value = RectF(.1f, .2f, .9f, .8f) }
            instrumentation.waitForIdleSync()
            scenario.onActivity { crop.value = null }
            instrumentation.waitForIdleSync()
            assertEquals(0, changes.get())
            assertEquals("Relayout must not rewrite the saved page fraction", 0, positions.get())
            assertEquals(0, finishes.get())
        }
    }

    @Test fun transparentArtUsesDocumentPaperOverOppositeThemeWithoutPaintingLetterboxes() {
        val pixels = IntArray(120 * 80) { index ->
            if (index % 120 in 45..74 && index / 120 in 25..54) android.graphics.Color.BLACK else 0
        }
        val source = Bitmap.createBitmap(pixels, 120, 80, Bitmap.Config.ARGB_8888)
        try {
            for (darkPaper in listOf(false, true)) {
                val settings = ThemeSettings(if (darkPaper) ReadingMode.ADAPTIVE else ReadingMode.ADAPTIVE_LIGHT, false)
                val palette = DocumentColors.palette(settings)
                val image = BitmapTheme.apply(source, settings, CancellationSignal())
                try {
                    val bounds = AtomicReference<android.graphics.Rect>()
                    val chrome = AtomicInteger()
                    val composeView = AtomicReference<View>()
                    val runtime = AtomicReference("No window diagnostics")
                    ActivityScenario.launch(ReaderTestActivity::class.java).use { scenario ->
                        scenario.onActivity { activity -> activity.setContent {
                            ReaderTheme(dark = !darkPaper) {
                                val surface = MaterialTheme.colorScheme.surfaceContainerLowest
                                val view = LocalView.current
                                SideEffect { chrome.set(surface.toArgb()); composeView.set(view) }
                                Box(Modifier.background(surface)) {
                                    PageViewport(image, ViewportState(), {}, {},
                                        Modifier.width(240.dp).height(240.dp).onGloballyPositioned { layout ->
                                            val origin = layout.localToWindow(Offset.Zero)
                                            val left = origin.x.roundToInt(); val top = origin.y.roundToInt()
                                            bounds.set(android.graphics.Rect(left, top, left + layout.size.width, top + layout.size.height))
                                        }, paper = Color(palette.background), horizontal = true)
                                }
                            }
                        } }
                        val deadline = SystemClock.uptimeMillis() + 8000
                        val capture = File(instrumentation.targetContext.cacheDir, "viewport-pixels/${settings.mode.name.lowercase()}")
                        var correct = false
                        var samples = "No rendered viewport captured"
                        var latest: Bitmap? = null
                        try {
                            while (!correct && SystemClock.uptimeMillis() < deadline) {
                                bounds.get()?.let { region ->
                                    scenario.onActivity { runtime.set(windowDiagnostic(it, composeView.get())) }
                                    copyViewport(scenario, region)?.let { rendered ->
                                        latest?.recycle()
                                        latest = rendered
                                        val paper = rendered.getPixel(rendered.width / 4, rendered.height / 2)
                                        val ink = rendered.getPixel(rendered.width / 2, rendered.height / 2)
                                        val letterbox = rendered.getPixel(rendered.width / 2, rendered.height / 16)
                                        val canvasDelta = listOf(0, 8, 16).maxOf { shift ->
                                            kotlin.math.abs((letterbox ushr shift and 255) - (chrome.get() ushr shift and 255))
                                        }
                                        // The static canvas grain has at most 22/255 alpha; document pixels stay exact.
                                        correct = paper == palette.background && ink == palette.foreground && canvasDelta <= 22
                                        fun color(value: Int) = value.toUInt().toString(16).padStart(8, '0')
                                        samples = "paper=${color(paper)}/${color(palette.background)}, " +
                                            "ink=${color(ink)}/${color(palette.foreground)}, chrome=${color(letterbox)}/${color(chrome.get())}, " +
                                            "bitmapInk=${color(image.getPixel(60, 40))}, bitmapPaper=${color(image.getPixel(30, 40))}, " +
                                            "capture=${rendered.width}x${rendered.height}, bounds=$region"
                                    }
                                }
                                if (!correct) SystemClock.sleep(30)
                            }
                            assertTrue("$settings must show ink on document paper and leave letterbox chrome unchanged: " +
                                "$samples; ${runtime.get()}; capture=$capture.png", correct)
                        } finally {
                            val diagnostic = """$settings correct=$correct
$samples
${runtime.get()}
"""
                            android.util.Log.i("AllDocViewportTest", diagnostic)
                            try {
                                check(capture.parentFile!!.isDirectory || capture.parentFile!!.mkdirs())
                                File("$capture.txt").writeText(diagnostic)
                                latest?.let { rendered -> File("$capture.png").outputStream().use {
                                    check(rendered.compress(Bitmap.CompressFormat.PNG, 100, it))
                                } }
                            } catch (failure: Exception) {
                                android.util.Log.w("AllDocViewportTest", "Could not save viewport diagnostics", failure)
                            } finally { latest?.recycle() }
                        }
                    }
                } finally { image.recycle() }
            }
            val remaining = IntArray(pixels.size)
            source.getPixels(remaining, 0, 120, 0, 0, 120, 80)
            assertArrayEquals("Viewport compositing must not alter image pixels", pixels, remaining)
        } finally { source.recycle() }
    }

    /** The shipped lazy-page renderer, with one externally supplied image and controlled pose. */
    @Composable
    private fun PageViewport(bitmap: Bitmap?, state: ViewportState, onStateChange: (ViewportState) -> Unit,
        onGestureEnd: () -> Unit, modifier: Modifier, crop: RectF? = null, paper: Color = Color.White,
        horizontal: Boolean = false, onPosition: () -> Unit = {},
    ) {
        val image = bitmap?.let { ReaderPageImage(it, crop) }
        val aspect = bitmap?.let { it.height.toFloat() / it.width * (crop?.height() ?: 1f) / (crop?.width() ?: 1f) }
        ReaderPages(1L, 1, 0, .23f, state, 0L,
            image?.let { mapOf(0 to it) }.orEmpty(), aspect?.let { mapOf(0 to it) }.orEmpty(),
            {}, { _, _ -> onPosition() }, onStateChange, onGestureEnd, modifier, paper, horizontal)
    }

    private fun windowDiagnostic(activity: ReaderTestActivity, compose: View?): String {
        val attributes = activity.obtainStyledAttributes(intArrayOf(android.R.attr.forceDarkAllowed))
        val themeAllowsDark = try { attributes.getBoolean(0, true) } finally { attributes.recycle() }
        fun viewInfo(view: View) = view.javaClass.simpleName +
            "(forceDark=" + (if (Build.VERSION.SDK_INT >= 29) view.isForceDarkAllowed else "n/a") +
            ",hardware=${view.isHardwareAccelerated},size=${view.width}x${view.height})"
        val ancestry = generateSequence(compose) { it.parent as? View }.take(8).joinToString(" / ", transform = ::viewInfo)
        val decor = activity.window.decorView
        // isLightTheme is a platform theme attribute without a public SDK constant on older APIs.
        val lightAttribute = activity.resources.getIdentifier("isLightTheme", "attr", "android")
        val lightValue = android.util.TypedValue()
        val platformLight = if (lightAttribute != 0 && activity.theme.resolveAttribute(lightAttribute, lightValue, true)) lightValue.data != 0 else null
        return "uiMode=${activity.resources.configuration.uiMode}, platformLight=$platformLight, themeForceDark=$themeAllowsDark, " +
            "windowFlags=${activity.window.attributes.flags}, decor=${viewInfo(decor)}, compose=$ancestry"
    }

    private fun copyViewport(scenario: ActivityScenario<ReaderTestActivity>, bounds: android.graphics.Rect): Bitmap? {
        val image = Bitmap.createBitmap(bounds.width(), bounds.height(), Bitmap.Config.ARGB_8888)
        val done = CountDownLatch(1)
        val result = AtomicInteger(PixelCopy.ERROR_UNKNOWN)
        try {
            scenario.onActivity { activity ->
                PixelCopy.request(activity.window, bounds, image, { status -> result.set(status); done.countDown() }, Handler(Looper.getMainLooper()))
            }
            assertTrue("Viewport capture timed out", done.await(5, TimeUnit.SECONDS))
            if (result.get() == PixelCopy.ERROR_SOURCE_NO_DATA) { image.recycle(); return null }
            assertEquals("Viewport capture failed", PixelCopy.SUCCESS, result.get())
            return image
        } catch (failure: Throwable) { image.recycle(); throw failure }
    }

    @Test fun draggingUpdatesDocumentAnchorAndFlushesOnceAtGestureEnd() {
        val bitmap = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888)
        val pose = mutableStateOf(ViewportState(zoom = 3f))
        val center = AtomicReference<Offset>()
        val changes = AtomicInteger()
        val finishes = AtomicInteger()
        ActivityScenario.launch(ReaderTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    PageViewport(bitmap, pose.value, { pose.value = it; changes.incrementAndGet() },
                        { finishes.incrementAndGet() }, Modifier.width(240.dp).height(240.dp).onGloballyPositioned {
                            center.set(it.localToScreen(Offset(it.size.width / 2f, it.size.height / 2f)))
                        })
                }
            }
            instrumentation.waitForIdleSync()
            val point = checkNotNull(center.get())
            val down = SystemClock.uptimeMillis()
            fun send(action: Int, x: Float) {
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, point.y, 0)
                try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
            }
            send(MotionEvent.ACTION_DOWN, point.x)
            repeat(8) { step ->
                SystemClock.sleep(16)
                send(MotionEvent.ACTION_MOVE, point.x + (step + 1) * 10f)
            }
            send(MotionEvent.ACTION_UP, point.x + 80f)
            instrumentation.waitForIdleSync()
            val settled = SystemClock.uptimeMillis() + 3000
            while (finishes.get() == 0 && SystemClock.uptimeMillis() < settled) SystemClock.sleep(16)
            assertTrue("A real drag must change the normalized content anchor", changes.get() > 0)
            assertTrue(pose.value.centerX < .5f)
            assertEquals(.5f, pose.value.centerY, .0001f)
            assertEquals(1, finishes.get())
        }
    }

    @Test @Suppress("DEPRECATION")
    fun pendingLegacyRestoreYieldsToUserInputAndKeepsTrackingScroll() {
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        try {
            for (action in listOf("Zoom in", "Double tap", "Cross-axis drag", "Scroll", "Keyboard", "No-op zoom", "Resize only")) {
                val pose = mutableStateOf(ViewportState(zoom = when (action) { "No-op zoom" -> 8f; "Keyboard" -> 1.5f; else -> 3f }, centerY = .8f))
                val image = mutableStateOf<Bitmap?>(null)
                val wide = mutableStateOf(false)
                val point = AtomicReference<Offset>()
                val position = AtomicReference(0 to -1f)
                val positions = AtomicInteger()
                val finishes = AtomicInteger()
                val host = AtomicReference<android.app.Activity>()
                fun focusEvidence(): String {
                    var window = "No activity"
                    instrumentation.runOnMainSync {
                        host.get()?.window?.decorView?.let {
                            window = "windowFocus=${it.hasWindowFocus()}, touchMode=${it.isInTouchMode}, viewFocus=${it.findFocus()?.javaClass?.name}"
                        }
                    }
                    val root = instrumentation.uiAutomation.rootInActiveWindow ?: return "$window; no accessibility root"
                    val lines = ArrayList<String>()
                    fun describe(node: AccessibilityNodeInfo): String {
                        val rect = android.graphics.Rect().also(node::getBoundsInScreen)
                        val actions = node.actionList.take(12).joinToString { "${it.id}:${it.label}" }
                        return "class=${node.className}, text=${node.text?.toString()?.take(100)}, desc=${node.contentDescription?.toString()?.take(100)}, " +
                            "focusable=${node.isFocusable}, focused=${node.isFocused}, clickable=${node.isClickable}, " +
                            "visible=${node.isVisibleToUser}, enabled=${node.isEnabled}, bounds=$rect, actions=[$actions]"
                    }
                    fun visit(node: AccessibilityNodeInfo, path: String, depth: Int) {
                        if (depth > 16 || lines.size >= 48) return
                        lines += "$path ${describe(node)}"
                        for (index in 0 until minOf(node.childCount, 16)) {
                            val child = node.getChild(index) ?: continue
                            try { visit(child, "$path.$index", depth + 1) } finally { child.recycle() }
                        }
                    }
                    try {
                        root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let {
                            try { lines += "INPUT ${describe(it)}" } finally { it.recycle() }
                        }
                        visit(root, "0", 0)
                    } finally { root.recycle() }
                    return "$window\n${lines.joinToString("\n")}"
                }
                fun await(predicate: () -> Boolean) {
                    val deadline = SystemClock.uptimeMillis() + 5000
                    while (SystemClock.uptimeMillis() < deadline) {
                        if (predicate()) return
                        SystemClock.sleep(16)
                    }
                    fail("$action: position=${position.get()}, pose=${pose.value}, finishes=${finishes.get()}" +
                        if (action == "Keyboard") "\n${focusEvidence()}" else "")
                }
                fun focusOwner(node: AccessibilityNodeInfo, depth: Int = 0): Boolean {
                    if (node.isVisibleToUser && node.isEnabled && node.isClickable && node.isFocusable) {
                        if (node.isFocused) return true
                        if (node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_FOCUS })
                            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                        return false
                    }
                    if (depth >= 8) return false
                    val parent = node.parent ?: return false
                    return try { focusOwner(parent, depth + 1) } finally { parent.recycle() }
                }
                fun accessibility(node: AccessibilityNodeInfo, depth: Int = 0): Boolean {
                    if (depth > 16) return false
                    // Compose exposes the label and the clickable input-focus owner as separate nodes.
                    if (action == "Keyboard" && node.text?.toString() == "Retry" && node.isVisibleToUser)
                        return focusOwner(node)
                    val id = if (action == "Keyboard") null
                        else if (action == "Scroll") AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                        else node.actionList.firstOrNull { it.label?.toString() == "Zoom in" }?.id
                    if (id != null && node.isVisibleToUser && node.actionList.any { it.id == id } && node.performAction(id)) return true
                    for (index in 0 until node.childCount) {
                        val child = node.getChild(index) ?: continue
                        try { if (accessibility(child, depth + 1)) return true } finally { child.recycle() }
                    }
                    return false
                }
                fun touch(dx: Float = 0f, dy: Float = 0f) {
                    val center = checkNotNull(point.get())
                    val down = SystemClock.uptimeMillis()
                    fun send(kind: Int, fraction: Float) {
                        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), kind,
                            center.x + dx * fraction, center.y + dy * fraction, 0)
                        try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
                    }
                    send(MotionEvent.ACTION_DOWN, 0f)
                    if (dx != 0f || dy != 0f) repeat(8) {
                        SystemClock.sleep(16); send(MotionEvent.ACTION_MOVE, (it + 1) / 8f)
                    }
                    send(MotionEvent.ACTION_UP, 1f)
                }
                ActivityScenario.launch(ReaderTestActivity::class.java).use { scenario ->
                    scenario.onActivity { activity -> host.set(activity); activity.setContent {
                        ReaderPages(1L, 4, 0, -1f, pose.value, 0L,
                            image.value?.let { value -> (0..3).associateWith { ReaderPageImage(value) } }.orEmpty(),
                            (0..3).associateWith { 1f }, {},
                            { page, fraction -> position.set(page to fraction); positions.incrementAndGet() },
                            { pose.value = it }, { finishes.incrementAndGet() },
                            Modifier.width(if (wide.value) 260.dp else 240.dp).height(if (wide.value) 200.dp else 240.dp).onGloballyPositioned {
                                point.set(it.localToScreen(Offset(it.size.width / 2f, it.size.height / 2f)))
                            }, failedPages = if (action == "Keyboard") setOf(0) else emptySet())
                    } }
                    instrumentation.waitForIdleSync()
                    await { point.get() != null }
                    assertEquals("Missing bitmap must leave legacy restore pending", 0, positions.get())
                    when (action) {
                        "Double tap" -> { touch(); SystemClock.sleep(80); touch() }
                        "Cross-axis drag" -> touch(dx = 80f)
                        "Keyboard" -> {
                            // LazyList handles page keys from focused descendants; plain page images are not focus targets.
                            // At 1.5x the shipped Retry button is visible without scrolling; do not activate it.
                            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_TAB)
                            await {
                                val root = instrumentation.uiAutomation.rootInActiveWindow ?: return@await false
                                try { accessibility(root) } finally { root.recycle() }
                            }
                            assertEquals("Focusing Retry must leave legacy restore pending", 0, positions.get())
                            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_PAGE_DOWN)
                        }
                        "Resize only" -> {
                            scenario.onActivity { wide.value = true }
                            instrumentation.waitForIdleSync()
                            assertEquals("Resize must leave legacy restore pending", 0, positions.get())
                            scenario.onActivity { image.value = bitmap }
                        }
                        else -> await {
                            val root = instrumentation.uiAutomation.rootInActiveWindow ?: return@await false
                            try { accessibility(root) } finally { root.recycle() }
                        }
                    }
                    await { finishes.get() > 0 }
                    if (action == "No-op zoom") {
                        assertEquals("A zoom at its limit must not discard legacy restore", 0, positions.get())
                        scenario.onActivity { image.value = bitmap }
                    }
                    await { position.get().second >= 0f }
                    if (action == "Resize only" || action == "No-op zoom") {
                        assertTrue("Untouched legacy focal position must be restored", position.get().second > .4f)
                    }
                    val before = position.get()
                    val beforeFinish = finishes.get()
                    touch(dy = -120f)
                    await {
                        val after = position.get()
                        finishes.get() > beforeFinish &&
                            (after.first > before.first || after.first == before.first && after.second > before.second + .01f)
                    }
                    val moved = position.get()
                    scenario.onActivity { image.value = bitmap }
                    instrumentation.waitForIdleSync()
                    assertEquals("A late bitmap must not restore over explicit input", moved, position.get())
                }
            }
        } finally { bitmap.recycle() }
    }
}
