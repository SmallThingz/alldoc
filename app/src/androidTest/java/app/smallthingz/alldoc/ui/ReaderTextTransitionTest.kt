package app.smallthingz.alldoc.ui

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import androidx.activity.BackEventCompat
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.ViewRootForTest
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.smallthingz.alldoc.ReaderTestActivity
import app.smallthingz.alldoc.state.TextPosition
import app.smallthingz.alldoc.theme.ReadingMode
import app.smallthingz.alldoc.theme.ThemeSettings
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Owned Activity pixels and semantics, with animation frames paused at each handoff. */
@RunWith(AndroidJUnit4::class)
class ReaderTextTransitionTest {
    @Test fun predictiveTextBackMovesPixelsCancelsAndCommitsOnlyOneLevel() {
        assumeTrue(ValueAnimator.areAnimatorsEnabled())
        launchReaderTestActivity().use { scenario ->
            val text = mutableStateOf(true)
            val committed = AtomicInteger()
            val pagePresent = AtomicBoolean()
            val pageInput = AtomicBoolean()
            val textInput = AtomicBoolean()
            val root = AtomicReference<ViewRootForTest>()
            val windowBounds = AtomicReference<Rect>()
            scenario.onActivity { activity -> activity.setContent {
                val host = LocalView.current
                SideEffect { root.set(host as ViewRootForTest) }
                MaterialTheme { Box(Modifier.width(240.dp).height(320.dp).background(Color.White)
                    .onGloballyPositioned { windowBounds.set(it.boundsInWindow()) }) {
                    ReaderTextTransition(text.value, true, true,
                        onBack = { committed.incrementAndGet(); text.value = false },
                        pageContent = { input ->
                            SideEffect { pageInput.set(input) }
                            DisposableEffect(Unit) { pagePresent.set(true); onDispose { pagePresent.set(false) } }
                            Box(Modifier.fillMaxSize().background(Color.Blue))
                        }, textContent = { input ->
                            SideEffect { textInput.set(input) }
                            Box(Modifier.fillMaxSize().background(Color.Red).semantics { contentDescription = "Text probe" })
                        })
                } }
            } }
            fun bodyBounds(): Rect? {
                var bounds: Rect? = null
                scenario.onActivity { root.get()?.let { owner ->
                    bounds = rawNodes(owner.semanticsOwner.unmergedRootSemanticsNode).firstOrNull {
                        it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Text probe") == true
                    }?.boundsInRoot
                } }
                return bounds
            }
            fun leftPixel() = pixels(scenario) { image ->
                val bounds = windowBounds.get()
                image.getPixel(bounds.left.toInt() + 2, bounds.center.y.toInt())
            }
            fun start() {
                scenario.onActivity { it.onBackPressedDispatcher.dispatchOnBackStarted(
                    BackEventCompat(0f, 100f, 0f, BackEventCompat.EDGE_LEFT)) }
                scenario.onActivity { it.onBackPressedDispatcher.dispatchOnBackProgressed(
                    BackEventCompat(100f, 100f, .5f, BackEventCompat.EDGE_LEFT)) }
            }
            await("Text did not become the settled input owner") { textInput.get() && bodyBounds() != null && windowBounds.get() != null }
            val original = requireNotNull(bodyBounds())
            assertEquals(android.graphics.Color.RED, leftPixel())
            assertFalse(pagePresent.get())
            start()
            await("Predictive text Back did not move actual content") { bodyBounds()?.left?.let { it > original.left + 2f } == true }
            await("Predictive text Back did not move rendered pixels") { leftPixel() == android.graphics.Color.WHITE }
            assertEquals("Progress must not navigate", 0, committed.get())
            assertTrue(text.value)
            assertFalse("Progress must not compose or request the destination", pagePresent.get())
            scenario.onActivity { it.onBackPressedDispatcher.dispatchOnBackCancelled() }
            await("Cancelling Back did not restore text geometry") { bodyBounds()?.let {
                kotlin.math.abs(it.left - original.left) < .5f && kotlin.math.abs(it.width - original.width) < .5f
            } == true }
            await("Cancelling Back did not restore text pixels") { leftPixel() == android.graphics.Color.RED }
            assertEquals(0, committed.get())
            assertTrue(textInput.get())
            start()
            await("A second Back did not receive progress") { bodyBounds()?.left?.let { it > original.left + 2f } == true }
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            await("Committed Back did not return to pages") { committed.get() == 1 && pagePresent.get() && pageInput.get() && bodyBounds() == null }
            assertFalse(text.value)
            assertEquals("Back must leave exactly one level", 1, committed.get())
        }
    }

    @Test fun realTextPreparesItsSavedAnchorAndPixelsBeforeReceivingInput() {
        launchReaderTestActivity().use { scenario ->
            val prepare = mutableStateOf(false)
            val active = mutableStateOf(false)
            val root = AtomicReference<ViewRootForTest>()
            val bounds = AtomicReference<Rect>()
            val positions = AtomicInteger()
            val text = (0 until 200).joinToString("\n\n") { "Paragraph $it. Readable words at a retained position." }
            val anchor = TextPosition(text.indexOf("Paragraph 150."))
            scenario.onActivity { activity -> activity.setContent {
                val host = LocalView.current
                SideEffect { root.set(host as ViewRootForTest) }
                MaterialTheme {
                    ReaderTextView(text, false, ThemeSettings(ReadingMode.ORIGINAL), false, TextFieldState(), {},
                        Modifier.width(240.dp).height(320.dp).onGloballyPositioned { bounds.set(it.boundsInWindow()) },
                        9901L, anchor, { positions.incrementAndGet() }, {},
                        active = active.value, prepareContent = prepare.value)
                }
            } }
            fun scrollValue(): Float? {
                var value: Float? = null
                scenario.onActivity {
                    value = root.get()?.let { owner -> rawNodes(owner.semanticsOwner.unmergedRootSemanticsNode)
                        .firstOrNull { node -> node.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null }
                        ?.config?.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.value?.invoke() }
                }
                return value
            }
            fun savedLineTop(): Float? {
                var top: Float? = null
                scenario.onActivity {
                    val nodes = root.get()?.let { rawNodes(it.semanticsOwner.unmergedRootSemanticsNode).toList() }
                        ?: return@onActivity
                    val viewportTop = nodes.firstOrNull {
                        it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null
                    }?.boundsInRoot?.top ?: return@onActivity
                    for (node in nodes) {
                        val layouts = mutableListOf<TextLayoutResult>()
                        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
                        val layout = layouts.firstOrNull() ?: continue
                        val offset = layout.layoutInput.text.text.indexOf("Paragraph 150.")
                        if (offset < 0) continue
                        top = node.positionInRoot.y + layout.getLineTop(layout.getLineForOffset(offset)) - viewportTop
                        break
                    }
                }
                return top
            }
            await("The real inactive text viewport was not laid out") { bounds.get() != null && scrollValue() != null }
            assertEquals("Unprepared text must not flash its first block", 0, darkPixels(scenario, bounds.get()))
            scenario.onActivity { prepare.value = true }
            await("The inactive incoming text did not restore its saved anchor") {
                savedLineTop()?.let { kotlin.math.abs(it) < 2f } == true
            }
            await("The prepared incoming text did not paint readable glyphs") { darkPixels(scenario, bounds.get()) > 20 }
            val preparedOffset = requireNotNull(scrollValue())
            scenario.onActivity {
                assertFalse("Preparation must not expose a scroll action", exposedNodes(root.get().semanticsOwner.unmergedRootSemanticsNode)
                    .any { it.config.getOrNull(SemanticsActions.ScrollBy)?.action != null })
                assertEquals("Preparation must not publish a user position", 0, positions.get())
                active.value = true
            }
            await("The prepared text did not receive input") {
                var visible = false
                scenario.onActivity { visible = exposedNodes(root.get().semanticsOwner.unmergedRootSemanticsNode)
                    .any { it.config.getOrNull(SemanticsActions.ScrollBy)?.action != null } }
                visible
            }
            assertEquals("Input handoff must not restore a second time", preparedOffset, requireNotNull(scrollValue()), .01f)
        }
    }

    @Test fun realReaderPublishesTheFinalPresentAnchorWhileAppearanceCoversInput() {
        assumeTrue(ValueAnimator.areAnimatorsEnabled())
        ReaderPersistenceTest().Fixture().use { fixture ->
            val file = File(fixture.context.filesDir, "covered-present.pdf")
            val pdf = PdfDocument()
            try {
                repeat(3) { index ->
                    val page = pdf.startPage(PdfDocument.PageInfo.Builder(240, 320, index + 1).create())
                    page.canvas.drawColor(android.graphics.Color.WHITE)
                    page.canvas.drawText("Page ${index + 1}", 20f, 50f, android.graphics.Paint().apply { textSize = 24f })
                    pdf.finishPage(page)
                }
                file.outputStream().use(pdf::writeTo)
            } finally { pdf.close() }
            fixture.launch(width = 0)
            fixture.act { it.open(Uri.fromFile(file)) }
            fixture.await { it.info?.pageCount == 3 && !it.loading }
            fixture.act { it.choosePageView(true) }
            launchReaderTestActivity().use { scenario ->
                val root = AtomicReference<ViewRootForTest>()
                scenario.onActivity { activity -> activity.setContent {
                    val host = LocalView.current
                    SideEffect { root.set(host as ViewRootForTest) }
                    Box(Modifier.width(360.dp).height(580.dp)) { ReaderScreen(fixture.reader, {}, { _, _ -> }) }
                } }
                fixture.await { 0 in it.pageImages }
                fun labelled(node: SemanticsNode, label: String) = rawNodes(node).any {
                    it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true
                }
                val axis = AtomicReference<SemanticsNode>()
                await("The real Present surface was not ready") {
                    scenario.onActivity { root.get()?.let { owner ->
                        axis.set(exposedNodes(owner.semanticsOwner.unmergedRootSemanticsNode).firstOrNull {
                            it.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange) != null &&
                                it.config.getOrNull(SemanticsActions.ScrollBy)?.action != null && labelled(it, "Page 1")
                        })
                    } }
                    axis.get() != null
                }
                val covered = AtomicBoolean()
                val failure = AtomicReference<Throwable>()
                val observedView = AtomicReference<android.view.View>()
                val observer = AtomicReference<android.view.ViewTreeObserver.OnPreDrawListener>()
                fun remove() {
                    val tree = observedView.get()?.viewTreeObserver
                    observer.get()?.let { if (tree?.isAlive == true) tree.removeOnPreDrawListener(it) }
                }
                try {
                    scenario.onActivity { activity ->
                        val viewport = axis.get()
                        val original = rawNodes(viewport).first {
                            it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Page 1") == true
                        }
                        val start = original.positionInRoot.x
                        val width = viewport.boundsInRoot.width
                        val view = activity.window.decorView
                        observedView.set(view)
                        val deadline = SystemClock.uptimeMillis() + 5000
                        val listener = android.view.ViewTreeObserver.OnPreDrawListener {
                            try {
                                check(SystemClock.uptimeMillis() < deadline) { "No partial Present step was placed" }
                                val moved = start - original.positionInRoot.x
                                if (moved > 0f) {
                                    assertTrue("Cover must interrupt before the next page aligns", moved < width)
                                    remove()
                                    val appearance = exposedNodes(root.get().semanticsOwner.unmergedRootSemanticsNode).first {
                                        it.config.getOrNull(SemanticsActions.OnClick)?.action != null && labelled(it, "Reading appearance")
                                    }
                                    assertTrue(appearance.config[SemanticsActions.OnClick].action!!.invoke())
                                    covered.set(true)
                                }
                            } catch (error: Throwable) { failure.set(error); remove() }
                            true
                        }
                        observer.set(listener)
                        view.viewTreeObserver.addOnPreDrawListener(listener)
                        assertTrue(viewport.config[SemanticsActions.ScrollBy].action!!.invoke(width, 0f))
                    }
                    await("The real reader was not covered mid-step") { covered.get() || failure.get() != null }
                    failure.get()?.let { throw it }
                    await("Appearance did not remove the reader from input navigation") {
                        var hidden = false
                        scenario.onActivity { hidden = exposedNodes(root.get().semanticsOwner.unmergedRootSemanticsNode)
                            .none { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Page 1") == true } }
                        hidden
                    }
                    fixture.await { it.anchorPage == 1 && kotlin.math.abs(it.pageFraction) < .0001f }
                } finally { scenario.onActivity { remove() } }
            }
        }
    }

    @Test fun crossfadeRetainsPixelsWithoutTwoInputOwnersAndReleasesOutgoingContent() {
        assumeTrue(ValueAnimator.areAnimatorsEnabled())
        launchReaderTestActivity().use { scenario ->
            val text = mutableStateOf(false)
            val ready = mutableStateOf(true)
            val active = mutableStateOf(true)
            val pageInput = AtomicBoolean()
            val textInput = AtomicBoolean()
            val pageDisposed = AtomicInteger()
            val textDisposed = AtomicInteger()
            val root = AtomicReference<ViewRootForTest>()
            val bounds = AtomicReference<Rect>()
            val clock = AtomicReference<BroadcastFrameClock>()
            val recomposer = AtomicReference<Recomposer>()
            val scope = AtomicReference<CoroutineScope>()
            fun idle() = clock.get()?.hasAwaiters == false && recomposer.get().currentState.value == Recomposer.State.Idle
            fun exposed(node: SemanticsNode): Sequence<SemanticsNode> = sequence {
                yield(node)
                if (!node.config.isClearingSemantics) for (child in node.children) yieldAll(exposed(child))
            }
            fun actions(): Set<String> = exposed(root.get().semanticsOwner.unmergedRootSemanticsNode)
                .filter { it.config.getOrNull(SemanticsActions.OnClick)?.action != null }
                .flatMap { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().asSequence() }.toSet()
            fun pause() { scenario.onActivity { recomposer.get().pauseCompositionFrameClock() } }
            fun resume() { scenario.onActivity { recomposer.get().resumeCompositionFrameClock() }; await("Transition did not settle", ::idle) }
            try {
                scenario.onActivity { activity ->
                    val owner = CoroutineScope(AndroidUiDispatcher.CurrentThread + Job())
                    val composition = Recomposer(owner.coroutineContext)
                    scope.set(owner); recomposer.set(composition)
                    owner.launch { composition.runRecomposeAndApplyChanges() }
                    activity.setContent(parent = composition) {
                        val host = LocalView.current
                        val effects = rememberCoroutineScope()
                        SideEffect {
                            root.set(host as ViewRootForTest)
                            clock.set(effects.coroutineContext[MonotonicFrameClock] as BroadcastFrameClock)
                        }
                        MaterialTheme { Box(Modifier.width(240.dp).height(320.dp).onGloballyPositioned { bounds.set(it.boundsInWindow()) }) {
                            ReaderTextTransition(text.value, ready.value, active.value, onBack = { text.value = false },
                                pageContent = { input ->
                                    SideEffect { pageInput.set(input) }
                                    DisposableEffect(Unit) { onDispose { pageDisposed.incrementAndGet() } }
                                    Box(Modifier.fillMaxSize().background(Color.Blue).clickable { }
                                        .semantics { contentDescription = "Page action" })
                                }, textContent = { input ->
                                    SideEffect { textInput.set(input) }
                                    DisposableEffect(Unit) { onDispose { textDisposed.incrementAndGet() } }
                                    Box(Modifier.fillMaxSize().background(Color.Red).clickable { }
                                        .semantics { contentDescription = "Text action" })
                                })
                        } }
                    }
                }
                await("Initial page did not settle") { root.get() != null && bounds.get() != null && pageInput.get() && idle() }
                scenario.onActivity { assertEquals(setOf("Page action"), actions()) }
                pause()
                scenario.onActivity { text.value = true }
                await("Entry did not retain two gated surfaces") { clock.get().hasAwaiters && !pageInput.get() && !textInput.get() }
                scenario.onActivity { assertTrue(actions().isEmpty()); assertEquals(0, pageDisposed.get()) }
                assertEquals("The entry backdrop must remain rendered", android.graphics.Color.BLUE, centerPixel(scenario, bounds.get()))
                resume()
                await("Text did not own the settled foreground") { textInput.get() && pageDisposed.get() == 1 }
                scenario.onActivity { assertEquals(setOf("Text action"), actions()) }

                pause()
                scenario.onActivity { ready.value = false; text.value = false }
                await("Unavailable raster must still reach the bounded fade") { clock.get().hasAwaiters && !pageInput.get() && !textInput.get() }
                scenario.onActivity { assertTrue(actions().isEmpty()); assertEquals(0, textDisposed.get()) }
                assertEquals("Outgoing text must remain rendered while preparing the page", android.graphics.Color.RED, centerPixel(scenario, bounds.get()))
                resume()
                await("The bounded handoff did not release text") { pageInput.get() && textDisposed.get() == 1 }
                scenario.onActivity { assertEquals(setOf("Page action"), actions()) }

                pause()
                scenario.onActivity { text.value = true }
                await("Second entry did not begin") { clock.get().hasAwaiters && !pageInput.get() }
                scenario.onActivity { text.value = false }
                resume()
                await("Superseded entry retained a text owner") { pageInput.get() && textDisposed.get() == 2 }
                scenario.onActivity { active.value = false }
                await("Covered page still accepts input") { !pageInput.get() }
                scenario.onActivity { assertTrue(actions().isEmpty()) }
            } finally {
                scenario.onActivity { recomposer.get()?.close(); scope.get()?.cancel() }
            }
        }
    }

    private fun rawNodes(node: SemanticsNode): Sequence<SemanticsNode> = sequence {
        yield(node)
        for (child in node.children) yieldAll(rawNodes(child))
    }

    private fun exposedNodes(node: SemanticsNode): Sequence<SemanticsNode> = sequence {
        yield(node)
        if (!node.config.isClearingSemantics) for (child in node.children) yieldAll(exposedNodes(child))
    }

    private fun centerPixel(scenario: ActivityScenario<ReaderTestActivity>, bounds: Rect): Int = pixels(scenario) {
        it.getPixel(bounds.center.x.toInt(), bounds.center.y.toInt())
    }

    private fun darkPixels(scenario: ActivityScenario<ReaderTestActivity>, bounds: Rect): Int = pixels(scenario) { bitmap ->
        var dark = 0
        for (y in bounds.top.toInt().coerceAtLeast(0) until bounds.bottom.toInt().coerceAtMost(bitmap.height)) {
            for (x in bounds.left.toInt().coerceAtLeast(0) until bounds.right.toInt().coerceAtMost(bitmap.width)) {
                val color = bitmap.getPixel(x, y)
                if (android.graphics.Color.red(color) < 96 && android.graphics.Color.green(color) < 96 && android.graphics.Color.blue(color) < 96) dark++
            }
        }
        dark
    }

    private fun pixels(scenario: ActivityScenario<ReaderTestActivity>, read: (Bitmap) -> Int): Int {
        var image: Bitmap? = null
        val done = CountDownLatch(1)
        var result = PixelCopy.ERROR_UNKNOWN
        try {
            scenario.onActivity { activity ->
                image = Bitmap.createBitmap(activity.window.decorView.width, activity.window.decorView.height, Bitmap.Config.ARGB_8888)
                PixelCopy.request(activity.window, requireNotNull(image), { result = it; done.countDown() }, Handler(Looper.getMainLooper()))
            }
            assertTrue(done.await(5, TimeUnit.SECONDS)); assertEquals(PixelCopy.SUCCESS, result)
            return read(requireNotNull(image))
        } finally { image?.recycle() }
    }

    private fun await(message: String, condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 5000
        while (!condition()) {
            check(SystemClock.uptimeMillis() < until) { message }
            SystemClock.sleep(16)
        }
    }
}
