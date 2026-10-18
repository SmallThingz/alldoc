package app.smallthingz.alldoc.ui

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import app.smallthingz.alldoc.ReaderTestActivity
import app.smallthingz.alldoc.state.ViewportState
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real scroll animations and process-local semantics on the runner's exclusively owned display. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class ReaderRetainedInputLifecycleTest {
    private data class Axis(val id: Int, val range: ScrollAxisRange, val bounds: Rect,
        val scroll: (Float, Float) -> Boolean)

    private fun await(message: String, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 5000
        while (!condition()) {
            check(SystemClock.uptimeMillis() < end) { message }
            SystemClock.sleep(16)
        }
    }

    private fun nodes(node: SemanticsNode): Sequence<SemanticsNode> = sequence {
        yield(node)
        for (child in node.children) yieldAll(nodes(child))
    }

    private fun nodes(activity: ReaderTestActivity, dialogs: Boolean = false): Sequence<SemanticsNode> =
        activity.testSemantics(dialogs).asSequence().flatMap(::nodes)

    private fun labelled(node: SemanticsNode, label: String): Boolean =
        node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true ||
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true ||
            node.children.any { labelled(it, label) }

    private fun axis(scenario: ActivityScenario<ReaderTestActivity>, horizontal: Boolean = false,
        except: Int? = null, label: String? = null): Axis {
        val result = AtomicReference<Axis>()
        val property = if (horizontal) SemanticsProperties.HorizontalScrollAxisRange else SemanticsProperties.VerticalScrollAxisRange
        await("Scrollable viewport was not composed") {
            scenario.onActivity { activity ->
                nodes(activity).firstOrNull { node -> node.id != except &&
                    node.config.getOrNull(property)?.maxValue()?.let { it > 0f } == true &&
                    node.config.getOrNull(SemanticsActions.ScrollBy)?.action != null &&
                    (label == null || labelled(node, label))
                }?.let { result.set(Axis(it.id, it.config[property], it.boundsInRoot,
                    it.config[SemanticsActions.ScrollBy].action!!)) }
            }
            result.get() != null
        }
        return result.get()
    }

    // Observe the first actual position change and cover the owner in that same UI turn.
    // Sampling on the test thread can otherwise miss the entire animation on a busy device.
    private fun interrupt(scenario: ActivityScenario<ReaderTestActivity>, axis: Axis,
        x: Float = 0f, y: Float = 0f, cover: (ReaderTestActivity) -> Unit): Float {
        val sample = AtomicReference<Float>()
        val failure = AtomicReference<Throwable>()
        scenario.onActivity { activity ->
            val start = axis.range.value()
            activity.lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) {
                val moved = snapshotFlow { axis.range.value() }.first { kotlin.math.abs(it - start) > .00001f }
                try { cover(activity); sample.set(moved) }
                catch (error: Throwable) { failure.set(error) }
            }
            assertTrue(axis.scroll(x, y))
        }
        await("No animated position change was available to interrupt") { sample.get() != null || failure.get() != null }
        failure.get()?.let { throw it }
        return sample.get()
    }

    private data class Travel(val node: SemanticsNode, val origin: Float, val target: Float) {
        fun assertInterrupted() {
            val distance = origin - node.positionInRoot.y
            assertTrue("The retained text anchor must still be laid out", node.boundsInRoot.height > 0f)
            assertTrue("The scroll must stop before its accepted pixel target", distance > 0f && distance < target - 1f)
        }
    }

    private fun travel(scenario: ActivityScenario<ReaderTestActivity>, axis: Axis): Travel {
        val result = AtomicReference<Travel>()
        scenario.onActivity { activity ->
            val viewport = nodes(activity).first { it.id == axis.id }
            // A bottom visible text node stays composed throughout this half-viewport request.
            val text = nodes(viewport).filter { it.config.getOrNull(SemanticsProperties.Text) != null &&
                it.boundsInRoot.intersect(viewport.boundsInRoot).height > 0f }.maxBy { it.positionInRoot.y }
            result.set(Travel(text, text.positionInRoot.y, viewport.boundsInRoot.height / 2f))
        }
        return result.get()
    }

    private fun stopped(axis: Axis): Float {
        SystemClock.sleep(100)
        val value = axis.range.value()
        SystemClock.sleep(400)
        assertEquals("A covered retained viewport kept moving", value, axis.range.value(), .00001f)
        return value
    }

    private fun clickVisibleRow(activity: ReaderTestActivity, axis: Axis) {
        val current = nodes(activity).first { it.id == axis.id }
        val row = nodes(current).first { node ->
            node.config.getOrNull(SemanticsActions.OnClick)?.action != null &&
                node.boundsInRoot.intersect(current.boundsInRoot).height > 24f && node.boundsInRoot.width > 0f
        }
        assertTrue(row.config[SemanticsActions.OnClick].action!!.invoke())
    }

    private fun requireMotion() = assumeTrue("Reduced motion has no in-flight scroll to interrupt", ValueAnimator.areAnimatorsEnabled())

    @Test fun presentStepSettlesWhileCoveredAndDoesNotAdvanceOnReturn() {
        requireMotion()
        val bitmap = Bitmap.createBitmap(16, 24, Bitmap.Config.ARGB_8888)
        try { for (initial in listOf(0, 2)) launchReaderTestActivity().use { scenario ->
            val covered = mutableStateOf(false)
            val anchor = AtomicReference(PageAnchor(initial, 0f))
            val images = (0..2).associateWith { ReaderPageImage(bitmap) }
            val aspects = (0..2).associateWith { 1.5f }
            scenario.onActivity { activity -> activity.setContent {
                MaterialTheme {
                    ReaderPages(7001L, 3, initial, 0f, ViewportState(), 0L, images, aspects, {},
                        { page, fraction -> anchor.set(PageAnchor(page, fraction)) }, {}, {},
                        Modifier.width(240.dp).height(320.dp), horizontal = true, active = !covered.value)
                    if (covered.value) ReaderSheet("Appearance", { covered.value = false }) { Text("Covered reader") }
                }
            } }
            val pages = axis(scenario, horizontal = true)
            SystemClock.sleep(120)
            fun pageNode(activity: ReaderTestActivity, index: Int) = nodes(activity).first {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Page ${index + 1}") == true
            }
            val original = AtomicReference<SemanticsNode>()
            val startX = AtomicReference<Float>()
            scenario.onActivity { activity ->
                val node = pageNode(activity, initial)
                original.set(node); startX.set(node.positionInRoot.x)
                assertEquals("Initial page $initial must be aligned", pages.bounds.left, startX.get(), .5f)
                assertEquals("Initial page $initial must fill the slot", pages.bounds.width, node.boundsInRoot.width, .5f)
            }
            val before = pages.range.value()
            val direction = if (initial == 0) 1 else -1
            val movingX = AtomicReference<Float>()
            val overlap = AtomicReference<Float>()
            val movingRange = AtomicReference<Float>()
            val failure = AtomicReference<Throwable>()
            val observedView = AtomicReference<android.view.View>()
            val observer = AtomicReference<android.view.ViewTreeObserver.OnPreDrawListener>()
            fun observed(): String = "initial=$initial direction=$direction rangeBefore=$before rangeMoving=${movingRange.get()} " +
                "startX=${startX.get()} movingX=${movingX.get()} " +
                "pixels=${movingX.get()?.let { (startX.get() - it) * direction }} " +
                "viewportWidth=${pages.bounds.width} visibleOverlap=${overlap.get()}"
            fun removeObserver() {
                val tree = observedView.get()?.viewTreeObserver
                observer.get()?.let { if (tree?.isAlive == true) tree.removeOnPreDrawListener(it) }
            }
            try {
                scenario.onActivity { activity ->
                    val view = activity.window.decorView
                    observedView.set(view)
                    val deadline = SystemClock.uptimeMillis() + 5000
                    val listener = android.view.ViewTreeObserver.OnPreDrawListener {
                        try {
                            check(SystemClock.uptimeMillis() < deadline) { "Placed motion observation expired: ${observed()}" }
                            check(view.isAttachedToWindow) { "The owned reader detached before interruption: ${observed()}" }
                            // LazyList range is offset + index*500 and can update before placement.
                            // Pre-draw observes the original page's placed geometry before covering it.
                            val node = original.get()
                            movingX.set(node.positionInRoot.x)
                            overlap.set(node.boundsInRoot.intersect(pages.bounds).width)
                            movingRange.set(pages.range.value())
                            val pixels = (startX.get() - movingX.get()) * direction
                            if (pixels != 0f) {
                                assertTrue("The modal must interrupt a visible partial step: ${observed()}",
                                    pixels > 0f && pixels < pages.bounds.width && overlap.get() > 0f)
                                removeObserver()
                                covered.value = true
                            }
                        } catch (error: Throwable) { failure.set(error); removeObserver() }
                        true
                    }
                    observer.set(listener)
                    view.viewTreeObserver.addOnPreDrawListener(listener)
                    assertTrue(pages.scroll(direction * pages.bounds.width, 0f))
                }
                try {
                    await("No placed partial step was available to interrupt") { covered.value || failure.get() != null }
                } catch (error: Throwable) {
                    throw AssertionError("No placed partial step was available to interrupt: ${observed()}", error)
                }
                failure.get()?.let { throw it }
            } finally { scenario.onActivity { removeObserver() } }
            val moving = movingRange.get()
            val observed = observed()
            fun assertDestinationAligned(phase: String) = scenario.onActivity { activity ->
                // This raw tree intentionally inspects retained layout behind the modal, not exposed actions.
                val node = pageNode(activity, 1)
                assertEquals("$phase destination must be aligned: $observed", pages.bounds.left, node.positionInRoot.x, .5f)
                assertEquals("$phase destination must fill the slot: $observed", pages.bounds.width, node.boundsInRoot.width, .5f)
            }
            await("Present did not settle while its modal was open: $observed") { anchor.get() == PageAnchor(1, 0f) }
            val aligned = stopped(pages)
            assertNotEquals("The fixture must interrupt before the aligned destination: $observed", moving, aligned)
            assertDestinationAligned("Covered")
            scenario.onActivity { covered.value = false }
            SystemClock.sleep(300)
            assertEquals("Closing a modal advanced the page: $observed", PageAnchor(1, 0f), anchor.get())
            assertEquals("Closing a modal replayed the interrupted step: $observed", aligned, pages.range.value(), .00001f)
            assertDestinationAligned("Returned")
        } } finally { bitmap.recycle() }
    }

    @Test fun toolbarScrollStopsBehindAppearanceAndKeepsItsOffsetOnReturn() {
        requireMotion()
        ReaderPersistenceTest().Fixture().use { fixture ->
            val file = File(fixture.context.filesDir, "toolbar-motion.html").apply { writeText("<html><body><p>Toolbar motion</p><p>A retained reading surface.</p></body></html>") }
            fixture.launch(width = 0)
            fixture.act { it.open(Uri.fromFile(file)) }
            fixture.await { it.info != null && !it.loading }
            launchReaderTestActivity().use { scenario ->
                scenario.onActivity { activity -> activity.setContent {
                    Box(Modifier.width(180.dp).height(420.dp)) {
                        ReaderTheme { ReaderScreen(fixture.reader, onOpen = {}, onExport = { _, _ -> }) }
                    }
                } }
                val toolbar = axis(scenario, horizontal = true, label = "Reading appearance")
                SystemClock.sleep(250)
                val before = toolbar.range.value()
                val target = toolbar.range.maxValue()
                assertTrue("The narrow toolbar must overflow", target - before > 4f)
                val moving = interrupt(scenario, toolbar, x = target - before) { activity ->
                    val action = nodes(activity).first { node ->
                        node.config.getOrNull(SemanticsActions.OnClick)?.action != null &&
                            labelled(node, "Reading appearance") && node.boundsInRoot.width >= 24f && node.boundsInRoot.height >= 24f
                    }
                    assertTrue(action.config[SemanticsActions.OnClick].action!!.invoke())
                }
                assertTrue("Appearance must interrupt before the scroll target", moving > before && moving < target - 1f)
                await("The real appearance sheet did not open") {
                    var shown = false
                    scenario.onActivity { shown = nodes(it, dialogs = true).any { node ->
                        node.config.getOrNull(SemanticsProperties.PaneTitle) == "Appearance"
                    } }
                    shown
                }
                val held = stopped(toolbar)
                assertTrue("The old scroll must be cancelled before its target", held < target - 1f)
                scenario.onActivity { activity ->
                    val dismiss = nodes(activity, dialogs = true).first { it.config.getOrNull(SemanticsActions.Dismiss)?.action != null }
                    assertTrue(dismiss.config[SemanticsActions.Dismiss].action!!.invoke())
                }
                await("Appearance did not dismiss") {
                    var closed = false
                    scenario.onActivity { closed = nodes(it, dialogs = true).none { node ->
                        node.config.getOrNull(SemanticsProperties.PaneTitle) == "Appearance"
                    } }
                    closed
                }
                SystemClock.sleep(150)
                assertEquals("Returning replayed toolbar scrolling", held, toolbar.range.value(), .00001f)
            }
        }
    }

    @Test fun licensesListAndDetailsStopWhenCoveredWithoutResettingTheirOffsets() {
        requireMotion()
        launchReaderTestActivity().use { scenario ->
            val active = mutableStateOf(true)
            scenario.onActivity { activity -> activity.setContent {
                MaterialTheme { Box(Modifier.width(320.dp).height(360.dp)) {
                    ReaderLicensesScreen({}, active.value)
                    if (!active.value) ReaderSheet("Covered license", { active.value = true }) { Text("Covered details") }
                } }
            } }
            val list = axis(scenario)
            val listTravel = travel(scenario, list)
            val moving = interrupt(scenario, list, y = listTravel.target) {
                listTravel.assertInterrupted(); clickVisibleRow(it, list)
            }
            assertTrue("The license list must still be mid-scroll", moving < list.range.maxValue() - .01f)
            val details = axis(scenario, except = list.id)
            val listHeld = stopped(list)
            scenario.onActivity { listTravel.assertInterrupted() }
            assertTrue("Opening details must cancel the list before its end", listHeld < list.range.maxValue() - .01f)
            val detailTravel = travel(scenario, details)
            val detailMoving = interrupt(scenario, details, y = detailTravel.target) {
                detailTravel.assertInterrupted(); active.value = false
            }
            assertTrue("The details must still be mid-scroll", detailMoving < details.range.maxValue() - .01f)
            val detailHeld = stopped(details)
            scenario.onActivity { detailTravel.assertInterrupted() }
            assertTrue("Covering details must cancel before their end", detailHeld < details.range.maxValue() - .01f)
            scenario.onActivity { active.value = true }
            SystemClock.sleep(150)
            assertEquals("Returning reset the license detail offset", detailHeld, details.range.value(), .00001f)
            scenario.onActivity { activity ->
                val back = nodes(activity).last { it.config.getOrNull(SemanticsActions.OnClick)?.action != null && labelled(it, "Back") }
                assertTrue(back.config[SemanticsActions.OnClick].action!!.invoke())
            }
            await("License details did not close") {
                var closed = false
                scenario.onActivity { closed = nodes(it).none { node -> node.id == details.id } }
                closed
            }
            assertEquals("Returning from details reset the catalog offset", listHeld, list.range.value(), .00001f)
        }
    }

    @Test fun examplesStopWhenOpeningADocumentAndKeepTheirOffsetOnReturn() {
        requireMotion()
        launchReaderTestActivity().use { scenario ->
            val active = mutableStateOf(true)
            scenario.onActivity { activity -> activity.setContent {
                MaterialTheme { Box(Modifier.width(320.dp).height(360.dp)) {
                    ReaderExamplesScreen({}, { active.value = false }, active.value)
                    if (!active.value) ReaderSheet("Opened example", { active.value = true }) { Text("Document route") }
                } }
            } }
            val catalog = axis(scenario)
            val catalogTravel = travel(scenario, catalog)
            val moving = interrupt(scenario, catalog, y = catalogTravel.target) {
                catalogTravel.assertInterrupted(); clickVisibleRow(it, catalog)
            }
            assertTrue("The examples must still be mid-scroll", moving < catalog.range.maxValue() - .01f)
            val held = stopped(catalog)
            scenario.onActivity { catalogTravel.assertInterrupted() }
            assertTrue("Opening a document must cancel before the catalog end", held < catalog.range.maxValue() - .01f)
            scenario.onActivity { assertFalse(active.value); active.value = true }
            SystemClock.sleep(150)
            assertEquals("Returning replayed the example scroll", held, catalog.range.value(), .00001f)
        }
    }
}
