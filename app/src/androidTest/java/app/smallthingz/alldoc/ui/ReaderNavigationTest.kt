package app.smallthingz.alldoc.ui

import android.content.Intent
import android.accessibilityservice.AccessibilityService
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.graphics.Rect
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Handler
import android.os.Bundle
import android.os.Looper
import android.view.PixelCopy
import android.view.InputDevice
import android.view.MotionEvent
import android.view.WindowManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.lifecycle.Lifecycle
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.smallthingz.alldoc.MainActivity
import app.smallthingz.alldoc.pdf.pdfOutlineFixture
import app.smallthingz.alldoc.state.TextPosition
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderNavigationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    @Test fun settingsReturnsToItsOriginAndExplicitHomeSurvivesRecreation() {
        val intent = Intent(instrumentation.targetContext, MainActivity::class.java).setAction(Intent.ACTION_MAIN)
        val version = instrumentation.targetContext.packageManager.getPackageInfo(
            instrumentation.targetContext.packageName, 0).versionName
        fun openFixture(scenario: ActivityScenario<MainActivity>) {
            scenario.onActivity { ViewModelProvider(it)[ReaderModel::class.java].open(
                Uri.parse("content://app.smallthingz.alldoc.test.reader-fixtures/one")) }
            await {
                var ready = false
                scenario.onActivity { ready = ViewModelProvider(it)[ReaderModel::class.java].info != null }
                ready
            }
        }
        var resumedHash: String? = null
        ActivityScenario.launch<MainActivity>(intent).use { warm ->
            openFixture(warm)
            warm.onActivity { resumedHash = ViewModelProvider(it)[ReaderModel::class.java].documentHash }
        }
        assertNotNull(resumedHash)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val field = ReaderModel::class.java.getDeclaredField("StateWorker").apply { isAccessible = true }
        val blocked = (field.get(null) as ExecutorService).submit {
            entered.countDown()
            check(release.await(30, TimeUnit.SECONDS)) { "Pending resume was not released" }
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertEquals("The real warm session must seed a recoverable resume", resumedHash,
                instrumentation.targetContext.getSharedPreferences("reader", 0).getString("activeDocument", null))
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                try {
                    await {
                        var pending = false
                        scenario.onActivity { val model = ViewModelProvider(it)[ReaderModel::class.java]
                            pending = model.restoringLastDocument && model.info == null }
                        pending && homeVisible(scenario)
                    }
                    click("About AllDoc")
                    await { visible("Version $version") && !visible("Licenses") && !visible("Settings") }
                } finally { release.countDown() }
                blocked.get(5, TimeUnit.SECONDS)
                await {
                    var settled = false
                    scenario.onActivity { settled = !ViewModelProvider(it)[ReaderModel::class.java].restoringLastDocument }
                    settled
                }
                scenario.onActivity { val model = ViewModelProvider(it)[ReaderModel::class.java]
                    assertFalse("An explicit About action must defeat pending automatic resume", model.readerVisible)
                    assertNull(model.info); assertNull(model.error) }
                assertTrue("About must remain visible after the delayed lookup finishes", visible("Version $version"))
                assertTrue(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
                await { homeVisible(scenario) }
                openFixture(scenario)
                await { visible("Find in document") }
                assertDocumentControls()
                click("Home")
                await { homeVisible(scenario) }
                click("Settings")
                await { visible("App theme") && !visible("Home") }
                scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                await { homeVisible(scenario) }
                scenario.onActivity {
                    val model = ViewModelProvider(it)[ReaderModel::class.java]
                    assertFalse(model.readerVisible)
                    assertNotNull("Home settings must retain the current document", model.info)
                }
                scenario.onActivity { assertFalse(ViewModelProvider(it)[ReaderModel::class.java].readerVisible) }
                scenario.recreate()
                await { homeVisible(scenario) }
                click("About AllDoc")
                await { visible("Version $version") && !visible("Licenses") && !visible("Settings") }
                assertTrue(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
                await { homeVisible(scenario) && !visible("Version $version") }
                click("Licenses")
                await { visible("Open-source licenses") && visible("Back") && !visible("Version $version") }
                assertTrue(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
                await { homeVisible(scenario) }
                click("Settings")
                await { visible("App theme") }
                scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                await { homeVisible(scenario) }
                scenario.onActivity { assertFalse(ViewModelProvider(it)[ReaderModel::class.java].readerVisible) }
            }
        } finally { release.countDown(); blocked.get(5, TimeUnit.SECONDS) }
    }
    @Test fun openingInternallyFromSettingsReturnsToSettingsWithoutDuplicatingDestinations() {
        val intent = Intent(instrumentation.targetContext, MainActivity::class.java).setAction(Intent.ACTION_MAIN)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            fun open(name: String) {
                scenario.onActivity { ViewModelProvider(it)[ReaderModel::class.java].open(
                    Uri.parse("content://app.smallthingz.alldoc.test.reader-fixtures/$name")) }
                await {
                    var ready = false
                    scenario.onActivity {
                        val model = ViewModelProvider(it)[ReaderModel::class.java]
                        assertNull(model.error)
                        ready = model.currentTitle == "$name.html" && model.bitmap != null && !model.pageLoading && !model.textMode
                    }
                    ready && visible("Find in document")
                }
            }
            open("one")
            assertDocumentControls()
            click("Home")
            await { homeVisible(scenario) }
            click("Settings")
            await { visible("App theme") && !visible("Home") }
            open("two")
            var second = 0L
            scenario.onActivity {
                second = ViewModelProvider(it)[ReaderModel::class.java].documentId
                it.onBackPressedDispatcher.onBackPressed()
            }
            await { visible("App theme") && !visible("Find in document") }
            scenario.onActivity {
                val model = ViewModelProvider(it)[ReaderModel::class.java]
                assertFalse(model.readerVisible)
                assertEquals(second, model.documentId)
                assertEquals("two.html", model.currentTitle)
            }
            scenario.recreate()
            await { visible("App theme") }
            // An internal open retains its prior Settings destination across recreation.
            open("one")
            assertDocumentControls()
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            await { visible("App theme") && !visible("Find in document") }
            scenario.onActivity {
                val model = ViewModelProvider(it)[ReaderModel::class.java]
                assertFalse(model.readerVisible)
                assertEquals("one.html", model.currentTitle)
                it.onBackPressedDispatcher.onBackPressed()
            }
            await { homeVisible(scenario) }
            assertFalse("Back must not return to a duplicate Settings route", visible("App theme"))
        }
    }
    @Test fun failedOpenAfterTextModeReturnsThroughDirectHomeAndSystemBack() {
        val intent = Intent(instrumentation.targetContext, MainActivity::class.java).setAction(Intent.ACTION_MAIN)
        val missing = Uri.fromFile(File(instrumentation.targetContext.cacheDir, "missing-${UUID.randomUUID()}.txt"))
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            scenario.onActivity { ViewModelProvider(it)[ReaderModel::class.java].open(
                Uri.parse("content://app.smallthingz.alldoc.test.reader-fixtures/one")) }
            await {
                var ready = false
                scenario.onActivity { ready = ViewModelProvider(it)[ReaderModel::class.java].info != null }
                ready
            }
            scenario.onActivity {
                val model = ViewModelProvider(it)[ReaderModel::class.java]
                model.setTextMode(true)
                assertTrue(model.textMode)
            }
            fun failOpen() {
                scenario.onActivity { ViewModelProvider(it)[ReaderModel::class.java].open(missing) }
                await {
                    var failed = false
                    scenario.onActivity {
                        val model = ViewModelProvider(it)[ReaderModel::class.java]
                        failed = model.error != null && !model.loading && model.info == null
                    }
                    failed
                }
                click("Close")
                await { !visible("Couldn’t open document") && visible("Home") }
            }
            failOpen()
            click("Home")
            await { homeVisible(scenario) }
            scenario.onActivity {
                val model = ViewModelProvider(it)[ReaderModel::class.java]
                assertFalse(model.readerVisible)
                // A replacement open clears the old text surface, even when its source fails.
                assertFalse("A failed replacement must not leave Text owning Back", model.textMode)
            }
            failOpen()
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            await { homeVisible(scenario) }
            scenario.onActivity { assertFalse(ViewModelProvider(it)[ReaderModel::class.java].readerVisible) }
        }
    }
    @Test fun textScrollsVerticallyAndFullscreenSurvivesRecreationBeforeBackReturnsToCaller() {
        val intent = Intent(instrumentation.targetContext, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse("content://app.smallthingz.alldoc.test.reader-fixtures/large"), "text/html")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            await {
                var loaded = false
                scenario.onActivity { loaded = ViewModelProvider(it)[ReaderModel::class.java].info != null }
                loaded
            }
            // This test exercises the text utility's scrolling and chrome, rather than the default page surface.
            scenario.onActivity { ViewModelProvider(it)[ReaderModel::class.java].setTextMode(true) }
            await {
                var ready = false
                scenario.onActivity {
                    val model = ViewModelProvider(it)[ReaderModel::class.java]
                    ready = model.text != null && !model.textLoading && model.textMode
                    if (ready) { assertTrue(model.info!!.reflowable); assertNull(model.bitmap) }
                }
                ready && visible("Full screen")
            }
            assertFalse(visible("Next page"))
            assertFalse(visible("Previous page"))
            await { visit { it.isVisibleToUser && it.isScrollable &&
                it.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) } }
            await {
                var moved = false
                scenario.onActivity { moved = ViewModelProvider(it)[ReaderModel::class.java].textPosition.offset > 0 }
                moved
            }
            click("Find in document")
            await { visible("Close search") && visit { it.isVisibleToUser && it.isEditable && it.isFocused } }
            assertFalse("Empty search must not reserve match controls", visible("Next match") || visible("Previous match"))
            click("Full screen")
            await { fullScreen() && visible("Exit full screen") }
            awaitSeparateActions("Exit full screen", "Close search")
            val closeBounds = tapControl("Close search")
            await(detail = { "Close search tapped at $closeBounds; ${readingControlsState()}" }) {
                !visible("Close search") && visible("Exit full screen")
            }
            // The next gesture must not mask a delayed toggle from the search-close tap.
            val tapDelay = android.view.ViewConfiguration.getDoubleTapTimeout().toLong() + 120
            SystemClock.sleep(tapDelay)
            assertTrue("Closing search must keep fullscreen controls visible", fullScreen() && visible("Exit full screen"))
            await {
                var hidden = false
                scenario.onActivity {
                    val insets = ViewCompat.getRootWindowInsets(it.window.decorView)
                    hidden = insets != null && !insets.isVisible(WindowInsetsCompat.Type.statusBars()) &&
                        !insets.isVisible(WindowInsetsCompat.Type.navigationBars()) && !insets.isVisible(WindowInsetsCompat.Type.captionBar())
                }
                hidden
            }
            assertDocumentControls(fullscreen = true)
            assertFullscreenCoversWindow(scenario)
            awaitUsableControl(scenario, "Exit full screen")
            readingGesture(drag = false, cancelled = true)
            SystemClock.sleep(tapDelay)
            assertTrue("Cancelled touch must leave shown controls visible", fullScreen() && visible("Exit full screen"))
            readingGesture(drag = false)
            await { fullScreen() && !visible("Exit full screen") }
            readingGesture(drag = false, cancelled = true)
            SystemClock.sleep(tapDelay)
            assertTrue("Cancelled touch must leave hidden controls hidden", fullScreen() &&
                !visible("Exit full screen") && readingActionAvailable("Show reading controls"))
            readingGesture(drag = false)
            await { fullScreen() && visible("Exit full screen") }
            val (beforeBounds, before) = initialBottomControlPixels(scenario, "Exit full screen", Icons.Outlined.FullscreenExit)
            val ink = glyphAndPadMask(beforeBounds, Icons.Outlined.FullscreenExit)
            assertLegibleGlyph(before, ink)
            val mask = ink.first
            val baseline = "bounds=$beforeBounds pixels=${before.size} glyph=${ink.first.size} pad=${ink.second.size}"
            val previousOffset = settledTextOffset(scenario)
            readingGesture(drag = true)
            await {
                var moved = false
                scenario.onActivity { moved = ViewModelProvider(it)[ReaderModel::class.java].textPosition.offset > previousOffset }
                moved
            }
            await { fullScreen() && !visible("Exit full screen") }
            performReadingAction("Show reading controls")
            awaitUsableControl(scenario, "Exit full screen")
            // Revealing chrome after downward scrolling must preserve its painted icon and position.
            val (afterBounds, after) = controlPixels(scenario, "Exit full screen")
            assertEquals("Scrolling must preserve fullscreen exit placement", beforeBounds, afterBounds)
            assertEquals("Control bounds changed: $beforeBounds to $afterBounds", before.size, after.size)
            assertLegibleGlyph(after, ink)
            val matched = mask.count { colorDistance(before[it], after[it]) <= 16 }
            assertTrue("Scrolling must preserve the painted fullscreen exit glyph: $baseline after=$afterBounds matched=$matched/${mask.size}",
                matched >= mask.size * 9 / 10)
            val beforeGapDrag = settledTextOffset(scenario)
            val exit = checkNotNull(actionBounds("Exit full screen"))
            val readingArea = checkNotNull(controlBounds("Reading area"))
            val gapX = (readingArea.left + exit.left) / 2f
            val gapY = readingArea.top + readingArea.height() * .25f
            assertTrue("The single-action overlay leaves a blank reading region", gapX < exit.left && gapY < exit.top)
            touchGesture(gapX, gapY, gapY + readingArea.height() * .35f, drag = true)
            await {
                var moved = false
                scenario.onActivity { moved = ViewModelProvider(it)[ReaderModel::class.java].textPosition.offset < beforeGapDrag }
                moved
            }
            await { fullScreen() && visible("Exit full screen") }
            performReadingAction("Hide reading controls")
            await { fullScreen() && !visible("Exit full screen") && readingActionAvailable("Show reading controls") }
            val hiddenOffset = settledTextOffset(scenario)
            readingGesture(drag = true)
            assertFalse("A drag must not toggle hidden controls", visible("Exit full screen"))
            await {
                var moved = false
                scenario.onActivity { moved = ViewModelProvider(it)[ReaderModel::class.java].textPosition.offset > hiddenOffset }
                moved
            }
            val offset = settledTextOffset(scenario)
            assertFalse("Dragging hidden chrome must not reveal it", visible("Exit full screen"))
            var hash: String? = null
            var pose = app.smallthingz.alldoc.state.ViewportState()
            var textPosition = TextPosition()
            scenario.onActivity {
                val model = ViewModelProvider(it)[ReaderModel::class.java]
                hash = model.documentHash; pose = model.viewport; textPosition = model.textPosition
            }
            readingGesture(drag = false)
            await(detail = {
                var current = TextPosition()
                scenario.onActivity { current = ViewModelProvider(it)[ReaderModel::class.java].textPosition }
                "Single tap after text settled at $textPosition; current=$current; ${readingControlsState()}"
            }) { fullScreen() && visible("Exit full screen") }
            awaitUsableControl(scenario, "Exit full screen")
            scenario.onActivity {
                val model = ViewModelProvider(it)[ReaderModel::class.java]
                assertEquals("Reveal must preserve text position", offset, model.textPosition.offset)
                assertEquals("Reveal must preserve saved zoom and rotation", pose, model.viewport)
            }
            performReadingAction("Hide reading controls")
            await { fullScreen() && !visible("Exit full screen") }
            performReadingAction("Show reading controls")
            await { fullScreen() && visible("Exit full screen") }
            performReadingAction("Hide reading controls")
            await { fullScreen() && !visible("Exit full screen") }
            scenario.recreate()
            await { fullScreen() && readingActionAvailable("Show reading controls") }
            assertFalse("Recreation must preserve hidden chrome", visible("Exit full screen"))
            scenario.onActivity {
                val model = ViewModelProvider(it)[ReaderModel::class.java]
                assertEquals(hash, model.documentHash)
                assertEquals(offset, model.textPosition.offset)
                assertEquals(pose, model.viewport)
                assertTrue(model.readerVisible)
                it.onBackPressedDispatcher.onBackPressed()
            }
            await {
                var pages = false
                scenario.onActivity { pages = !ViewModelProvider(it)[ReaderModel::class.java].textMode }
                pages && fullScreen() && visible("Document pages")
            }
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            await { visible("Full screen") && !fullScreen() && !visible("Exit full screen") }
            scenario.onActivity {
                assertTrue("Fullscreen Back must leave the reader open", ViewModelProvider(it)[ReaderModel::class.java].readerVisible)
                it.onBackPressedDispatcher.onBackPressed()
            }
            await { scenario.state == Lifecycle.State.DESTROYED }
        }
    }
    @Test fun pdfScrollPositionSurvivesRecreationAndReopeningIdenticalBytesAtAnotherPath() {
        val directory = File(instrumentation.targetContext.cacheDir, "scroll-ui-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val first = File(directory, "first.pdf").also { writeReaderPdf(it, pages = 8) }
        val renamed = File(directory, "renamed.pdf").also { first.copyTo(it) }
        fun launch(file: File) = ActivityScenario.launch<MainActivity>(
            Intent(instrumentation.targetContext, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
                .setDataAndType(Uri.fromFile(file), "application/pdf"))
        var hash: String? = null
        var expectedPage = 0
        var expectedFraction = 0f
        var expectedHorizontal = false
        var expectedZoom = 1f
        var expectedRotation = 0
        fun assertPosition(scenario: ActivityScenario<MainActivity>) = await {
            var ready = false
            scenario.onActivity {
                val model = ViewModelProvider(it)[ReaderModel::class.java]
                assertNull(model.error)
                ready = model.documentHash == hash && model.anchorPage == expectedPage && model.horizontalPages == expectedHorizontal &&
                    model.viewport.zoom == expectedZoom && model.viewport.rotation == expectedRotation &&
                    kotlin.math.abs(model.pageFraction - expectedFraction) < .025f &&
                    model.bitmap != null && !model.pageLoading
            }
            ready && visible("Document pages") && firstVisiblePage()?.let { it in (expectedPage - 1)..expectedPage } == true
        }
        try {
            launch(first).use { scenario ->
                await {
                    var ready = false
                    scenario.onActivity { val model = ViewModelProvider(it)[ReaderModel::class.java]
                        assertNull(model.error)
                        ready = model.bitmap != null && !model.pageLoading && !model.textMode
                        if (ready) hash = model.documentHash }
                    ready && visible("Document pages")
                }
                assertDocumentControls()
                scenario.onActivity { assertFalse("Documents must scroll vertically by default", ViewModelProvider(it)[ReaderModel::class.java].horizontalPages) }
                scrollToPage(scenario, 2)
                val position = settledPagePosition(scenario)
                expectedPage = position.first; expectedFraction = position.second
                assertTrue("A real swipe must retain a partial-page offset", expectedFraction > .01f)
                assertPosition(scenario)
                scenario.recreate()
                assertPosition(scenario)
            }
            launch(renamed).use { scenario ->
                assertPosition(scenario)
                scenario.onActivity { val model = ViewModelProvider(it)[ReaderModel::class.java]
                    assertEquals("renamed.pdf", model.currentTitle)
                    model.updateViewport(model.viewport.copy(zoom = 3f)) }
                val before = settledPagePosition(scenario)
                readingGesture(drag = true)
                await {
                    var moved = false
                    scenario.onActivity { val model = ViewModelProvider(it)[ReaderModel::class.java]
                        assertEquals("Vertical scrolling must preserve zoom", 3f, model.viewport.zoom, 0f)
                        moved = model.anchorPage > before.first || model.anchorPage == before.first && model.pageFraction > before.second + .01f }
                    moved
                }
                val beforeRotation = settledPagePosition(scenario)
                val area = checkNotNull(controlBounds("Document pages"))
                var expectedCenterY = 0f
                var expectedRotatedOffset = 0f
                scenario.onActivity {
                    val model = ViewModelProvider(it)[ReaderModel::class.java]
                    val aspect = requireNotNull(model.pageAspects[model.anchorPage])
                    val gap = 8 * it.resources.displayMetrics.density
                    val padding = minOf(80f * it.resources.displayMetrics.density, area.height() / 3f)
                    val oldHeight = area.width() * aspect * model.viewport.zoom
                    expectedCenterY = ((beforeRotation.second * (oldHeight + gap) + area.height() / 2f - padding) / oldHeight).coerceIn(0f, 1f)
                    val rotatedHeight = area.width() / aspect * model.viewport.zoom
                    expectedRotatedOffset = ((model.viewport.centerX * rotatedHeight - area.height() / 2f + padding) / (rotatedHeight + gap)).coerceAtLeast(0f)
                }
                click("Reading appearance")
                click("Rotate page")
                assertTrue(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
                await {
                    var rotated = false
                    scenario.onActivity { val model = ViewModelProvider(it)[ReaderModel::class.java]
                        rotated = model.viewport.rotation == 1 && model.viewport.zoom == 3f &&
                            model.anchorPage == beforeRotation.first && kotlin.math.abs(model.viewport.centerY - expectedCenterY) < .025f &&
                            kotlin.math.abs(model.pageFraction - expectedRotatedOffset) < .025f }
                    rotated && !visible("Rotate page")
                }
                val beforeDirection = settledPagePosition(scenario)
                val directionArea = checkNotNull(controlBounds("Document pages"))
                var focal = app.smallthingz.alldoc.state.ViewportState()
                var expectedHorizontalOffset = 0f
                scenario.onActivity {
                    val model = ViewModelProvider(it)[ReaderModel::class.java]
                    val pose = model.viewport
                    val aspect = requireNotNull(model.pageAspects[model.anchorPage])
                    val gap = 8 * it.resources.displayMetrics.density
                    val padding = minOf(80f * it.resources.displayMetrics.density, directionArea.height() / 3f)
                    val height = directionArea.width() / aspect * pose.zoom
                    val centerX = (beforeDirection.second * (height + gap) + directionArea.height() / 3f - padding) / height
                    assertEquals(beforeRotation.first, beforeDirection.first)
                    assertTrue("Fixture focal point must remain inside this page", centerX in 0f..1f)
                    // At 90 degrees, horizontal pan is source Y; rendering clamps it to avoid blank margins.
                    val half = 1f / (2f * pose.zoom)
                    focal = pose.copy(centerX = centerX, centerY = pose.centerY.coerceIn(half, 1f - half))
                    val fitHeight = directionArea.height() - 2f * minOf(16f * it.resources.displayMetrics.density, directionArea.height() / 8f)
                    val imageWidth = minOf(directionArea.width().toFloat(), fitHeight * aspect) * focal.zoom
                    val width = maxOf(directionArea.width().toFloat(), imageWidth)
                    val offset = (width - imageWidth) / 2f + (1 - focal.centerY) * imageWidth - directionArea.width() / 3f
                    expectedHorizontalOffset = offset.coerceIn(0f, width - directionArea.width()) / (width + gap)
                }
                click("Reading view")
                click("Present view")
                await(detail = { "Expected horizontal page=${beforeRotation.first}, fraction=$expectedHorizontalOffset, pose=$focal; ${pageState(scenario)}" }) {
                    var horizontal = false
                    scenario.onActivity { val model = ViewModelProvider(it)[ReaderModel::class.java]
                        horizontal = model.horizontalPages && model.anchorPage == beforeRotation.first &&
                            model.viewport.zoom == focal.zoom && model.viewport.rotation == focal.rotation &&
                            kotlin.math.abs(model.viewport.centerX - focal.centerX) < .025f &&
                            kotlin.math.abs(model.viewport.centerY - focal.centerY) < .025f &&
                            kotlin.math.abs(model.pageFraction - expectedHorizontalOffset) < .025f }
                    horizontal && !visible("Present view")
                }
                expectedHorizontal = true; expectedZoom = focal.zoom; expectedRotation = focal.rotation
                val beforeHorizontal = settledPagePosition(scenario)
                scrollToPage(scenario, beforeHorizontal.first + 1, horizontal = true)
                val horizontalPosition = settledPagePosition(scenario)
                expectedPage = horizontalPosition.first; expectedFraction = horizontalPosition.second
                scenario.recreate()
                assertPosition(scenario)
            }
            launch(first).use { assertPosition(it) }
        } finally { directory.deleteRecursively() }
    }

    @Test fun normalPagesScrollVerticallyAndPresentationSwipesStayDiscreteAcrossFormatsAndZoom() {
        val directory = File(instrumentation.targetContext.cacheDir, "discrete-ui-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val pdf = File(directory, "pages.pdf").also { writeReaderPdf(it, pages = 8) }
        val text = File(directory, "pages.html").also { file -> file.writeText(buildString {
            append("<html><body><pre>"); appendLine(UUID.randomUUID())
            repeat(1_000) { appendLine("Line $it: Continuous reading and discrete presentation pages.") }
            append("</pre></body></html>")
        }) }
        val slides = File(directory, "slides.pptx").also { file ->
            instrumentation.targetContext.assets.open("examples/office/office-guide.pptx").use { input ->
                java.util.zip.ZipInputStream(input).use { source -> java.util.zip.ZipOutputStream(file.outputStream()).use { output ->
                    output.setComment(UUID.randomUUID().toString())
                    while (true) {
                        val entry = source.nextEntry ?: break
                        output.putNextEntry(java.util.zip.ZipEntry(entry.name))
                        source.copyTo(output); output.closeEntry(); source.closeEntry()
                    }
                } }
            }
        }
        try {
            ActivityScenario.launch<MainActivity>(Intent(instrumentation.targetContext, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN)).use { scenario ->
                fun settled(expected: Int? = null): Pair<Int, Float> {
                    if (expected != null) await(detail = { pageState(scenario) }) {
                        var atPage = false
                        scenario.onActivity { val model = ViewModelProvider(it)[ReaderModel::class.java]
                            assertNull(model.error)
                            atPage = model.page == expected && model.pageFraction < .002f && !model.pageLoading && model.bitmap != null }
                        atPage
                    }
                    val result = settledPagePosition(scenario)
                    if (expected != null) { assertEquals(expected, result.first); assertEquals(0f, result.second, .002f) }
                    return result
                }
                fun swipe(forward: Boolean, fast: Boolean = true, distance: Float = .7f) {
                    val area = checkNotNull(controlBounds("Document pages"))
                    val from = if (forward) .85f else .15f
                    val to = from + if (forward) -distance else distance
                    touchGesture(area.left + area.width() * from, area.exactCenterY(), area.exactCenterY(),
                        drag = true, endX = area.left + area.width() * to, stepMillis = if (fast) 6L else 25L,
                        beforeRelease = { if (!fast) SystemClock.sleep(150) })
                }
                for (file in listOf(pdf, slides, text)) {
                    scenario.onActivity { ViewModelProvider(it)[ReaderModel::class.java].open(Uri.fromFile(file)) }
                    await(detail = { "${file.name}: ${pageState(scenario)}" }) {
                        var ready = false
                        scenario.onActivity { val model = ViewModelProvider(it)[ReaderModel::class.java]
                            assertNull(model.error)
                            ready = model.currentTitle == file.name && model.bitmap != null && !model.pageLoading && !model.textMode }
                        ready && visible("Document pages")
                    }
                    scenario.onActivity { val model = ViewModelProvider(it)[ReaderModel::class.java]
                        assertFalse("${file.name} must default to Normal", model.horizontalPages)
                        assertTrue("Fixture must have enough pages to expose skipped slides", model.info!!.pageCount >= 4) }
                    val area = checkNotNull(controlBounds("Document pages"))
                    touchGesture(area.exactCenterX(), area.top + area.height() * .7f, area.top + area.height() * .55f,
                        drag = true, beforeRelease = { SystemClock.sleep(150) })
                    val normal = settledPagePosition(scenario)
                    assertTrue("${file.name} must scroll continuously down", normal.first > 0 || normal.second > .01f)
                    for (direction in listOf(android.view.View.LAYOUT_DIRECTION_LTR, android.view.View.LAYOUT_DIRECTION_RTL)) {
                        scenario.onActivity { activity ->
                            activity.window.decorView.layoutDirection = direction
                            ViewModelProvider(activity)[ReaderModel::class.java].apply {
                                choosePageView(true); updateViewport(app.smallthingz.alldoc.state.ViewportState()); goToPage(0)
                            }
                        }
                        settled(0)
                        swipe(true); settled(1)
                        swipe(true); settled(2)
                        swipe(false); settled(1)
                        swipe(true, fast = false, distance = .2f); settled(1)
                        swipe(true, fast = false); settled(2)
                        swipe(false, fast = false, distance = .2f); settled(2)
                        swipe(false, fast = false); settled(1)
                    }
                    scenario.onActivity { ViewModelProvider(it)[ReaderModel::class.java].apply {
                        updateViewport(viewport.copy(zoom = 2f)); goToPage(1)
                    } }
                    settled(1)
                    repeat(3) {
                        val before = settledPagePosition(scenario)
                        swipe(true, fast = false)
                        val bounds = checkNotNull(controlBounds("Document pages"))
                        val after = settledPagePosition(scenario, presentationBounds = bounds)
                        assertTrue("A zoomed edge swipe must not skip a page", after.first in before.first..before.first + 1)
                        scenario.onActivity { activity -> val model = ViewModelProvider(activity)[ReaderModel::class.java]
                            assertEquals(2f, model.viewport.zoom, 0f)
                            val fitHeight = bounds.height() - 2f * minOf(16f * activity.resources.displayMetrics.density, bounds.height() / 8f)
                            val extent = pageRowLength(bounds.width().toFloat(), fitHeight,
                                requireNotNull(model.pageAspects[model.anchorPage]), model.viewport, true)
                            val offset = model.pageFraction * (extent + 8 * activity.resources.displayMetrics.density)
                            assertTrue("A zoomed presentation must rest inside one page: offset=$offset extent=$extent", offset <= extent - bounds.width() + 2f)
                        }
                    }
                    scenario.onActivity { it.window.decorView.layoutDirection = android.view.View.LAYOUT_DIRECTION_LTR }
                }
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun fullscreenKeepsExportCancellationReachable() {
        val fixture = Uri.parse("content://app.smallthingz.alldoc.test.reader-fixtures/large")
        val stalled = Uri.parse("content://app.smallthingz.alldoc.test.reader-fixtures/stalled")
        val intent = Intent(instrumentation.targetContext, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
            .setDataAndType(fixture, "text/html").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                await {
                    var ready = false
                    scenario.onActivity {
                        val model = ViewModelProvider(it)[ReaderModel::class.java]
                        assertNull(model.error)
                        ready = model.info != null && model.bitmap != null && !model.pageLoading && !model.textMode
                    }
                    ready && visible("Full screen")
                }
                var hash: String? = null
                var identity = 0L
                scenario.onActivity {
                    val model = ViewModelProvider(it)[ReaderModel::class.java]
                    hash = model.documentHash; identity = model.documentId
                    assertNotNull(model.prepareExport(ReaderModel.ExportKind.TEXT))
                    model.export(stalled)
                }
                await { visible("Cancel") }
                click("Full screen")
                awaitSeparateActions("Exit full screen", "Cancel")
                // A pending export must keep cancellation reachable beyond the normal idle timeout.
                SystemClock.sleep(5_500)
                assertTrue(fullScreen())
                awaitSeparateActions("Exit full screen", "Cancel")
                click("Cancel")
                await {
                    var done = false
                    scenario.onActivity {
                        val model = ViewModelProvider(it)[ReaderModel::class.java]
                        assertNull(model.error)
                        assertEquals(hash, model.documentHash)
                        assertEquals(identity, model.documentId)
                        assertTrue(model.readerVisible)
                        done = !model.exporting
                    }
                    done && !visible("Cancel") && fullScreen()
                }
            }
        } finally {
            instrumentation.context.contentResolver.call(stalled, "releasePipes", null, null)
        }
    }
    @Test fun searchRestoresTheViewBeforeContentsNavigationAndHomeHidesRetainedDocumentActions() {
        val context = instrumentation.targetContext
        val file = File(context.cacheDir, "contents-ui-${UUID.randomUUID()}.pdf")
        // The final page must be taller than the viewport to become its first visible item.
        file.writeBytes(pdfOutlineFixture(pageHeight = 900))
        try {
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN)).use { scenario ->
                scenario.onActivity { ViewModelProvider(it)[ReaderModel::class.java].open(Uri.fromFile(file)) }
                await {
                    var ready = false
                    scenario.onActivity {
                        val model = ViewModelProvider(it)[ReaderModel::class.java]
                        assertNull(model.error)
                        ready = model.info != null && !model.loading
                    }
                    ready && visible("Find in document")
                }
                scenario.onActivity {
                    val model = ViewModelProvider(it)[ReaderModel::class.java]
                    model.choosePageView(false) // Keep this geometry test independent of the saved presentation choice.
                    model.goToPage(0)
                }
                await {
                    var ready = false
                    scenario.onActivity { val model = ViewModelProvider(it)[ReaderModel::class.java]
                        ready = model.page == 0 && model.bitmap != null && !model.pageLoading }
                    ready
                }
                click("Full screen")
                await { fullScreen() && visible("Exit full screen") }
                assertDocumentControls(fullscreen = true)
                assertFullscreenCoversWindow(scenario)
                awaitUsableControl(scenario, "Exit full screen")
                val (glyphBounds, glyph) = controlPixels(scenario, "Exit full screen")
                assertLegibleGlyph(glyph, glyphAndPadMask(glyphBounds, Icons.Outlined.FullscreenExit))
                var pose = app.smallthingz.alldoc.state.ViewportState()
                scenario.onActivity { pose = ViewModelProvider(it)[ReaderModel::class.java].viewport }
                performReadingAction("Hide reading controls")
                await { fullScreen() && !visible("Exit full screen") }
                readingGesture(drag = false)
                await { fullScreen() && visible("Exit full screen") }
                scenario.onActivity {
                    val model = ViewModelProvider(it)[ReaderModel::class.java]
                    assertEquals("Reveal must not zoom, rotate or pan the PDF", pose, model.viewport)
                    assertEquals(0, model.page)
                }
                scrollToPage(scenario, 1)
                assertDocumentControls(fullscreen = true)
                if (!visible("Exit full screen")) performReadingAction("Show reading controls")
                await { visible("Jump to page") }
                click("Jump to page")
                await { visible("Go") && visit { it.isVisibleToUser && it.isEditable && it.isFocused } }
                fun pageNumber(value: String) {
                    await { visit { node ->
                        node.isVisibleToUser && node.isEditable && node.isFocused && node.performAction(
                            AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
                            })
                    } }
                    await { visit { it.isVisibleToUser && it.isEditable && it.isFocused && it.text?.toString() == value } }
                }
                pageNumber("3")
                await { visit { matches(it, "Go") && it.isVisibleToUser && disabledAction(it) } }
                scenario.onActivity { assertEquals("Invalid input must not navigate", 1, ViewModelProvider(it)[ReaderModel::class.java].page) }
                pageNumber("1")
                click("Go")
                await {
                    var ready = false
                    scenario.onActivity { val model = ViewModelProvider(it)[ReaderModel::class.java]
                        ready = model.page == 0 && model.bitmap != null && !model.pageLoading }
                    ready && !visible("Go")
                }
                click("Exit full screen")
                await { !fullScreen() && visible("Full screen") }
                // Force the same destination page: goToPage alone is deliberately a no-op.
                scenario.onActivity { ViewModelProvider(it)[ReaderModel::class.java].goToPage(0) }
                click("Find in document")
                await {
                    var ready = false
                    scenario.onActivity {
                        val model = ViewModelProvider(it)[ReaderModel::class.java]
                        ready = model.textMode && model.text?.contains("Outline fixture") == true && !model.textLoading
                    }
                    ready && visible("Close search")
                }
                assertFalse("Search replaces the general reading tools", visible("Reading view"))
                click("Close search")
                await {
                    var restored = false
                    scenario.onActivity {
                        val model = ViewModelProvider(it)[ReaderModel::class.java]
                        restored = !model.textMode && !model.horizontalPages && model.page == 0
                    }
                    restored && visible("Document pages") && !visible("Close search")
                }
                click("Reading view")
                click("Present view")
                await {
                    var pages = false
                    scenario.onActivity {
                        val model = ViewModelProvider(it)[ReaderModel::class.java]
                        pages = !model.textMode && model.horizontalPages && model.bitmap != null && !model.pageLoading
                    }
                    pages && visible("Document pages") && !visible("Close search") && !visible("Present view")
                }
                // A later search must restore the newly chosen presentation view, not the previous mode.
                click("Find in document")
                await { visible("Close search") }
                click("Close search")
                await {
                    var presentation = false
                    scenario.onActivity {
                        val model = ViewModelProvider(it)[ReaderModel::class.java]
                        presentation = !model.textMode && model.horizontalPages
                    }
                    presentation && visible("Document pages") && !visible("Close search")
                }
                click("Find in document")
                await { visible("Close search") }
                assertFalse("Search replaces the general reading tools", visible("Contents"))
                click("Close search")
                await {
                    var restored = false
                    scenario.onActivity {
                        val model = ViewModelProvider(it)[ReaderModel::class.java]
                        restored = !model.textMode && model.horizontalPages && model.page == 0
                    }
                    restored && visible("Document pages") && !visible("Close search")
                }
                click("Contents")
                await { visible("Repeated") }
                click("Repeated")
                await {
                    var ready = false
                    scenario.onActivity {
                        val model = ViewModelProvider(it)[ReaderModel::class.java]
                        assertNull(model.error)
                        ready = !model.textMode && model.page == 0 && model.bitmap != null && !model.pageLoading
                    }
                    ready && visible("Document pages") && !visible("Close search") && !visible("Repeated")
                }
                // Search must be closed in state, not merely hidden by the page surface.
                click("Reading view")
                await { visit { node -> node.isVisibleToUser && matches(node, "Present view") && selectedChoice(node) } }
                // A selected radio has no ACTION_CLICK in Compose accessibility; dismiss without changing it.
                assertTrue(instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
                await { visible("Home") && visible("Document pages") && !visible("Present view") }
                assertFalse(visible("Close search"))
                scenario.onActivity {
                    val model = ViewModelProvider(it)[ReaderModel::class.java]
                    assertFalse(model.textMode)
                    assertTrue(model.horizontalPages)
                }
                await { visible("Contents") && visible("Reading appearance") }
                click("Home")
                await { homeVisible(scenario) && !visible("Home") }
                scenario.onActivity {
                    val model = ViewModelProvider(it)[ReaderModel::class.java]
                    assertFalse(model.readerVisible)
                    assertNotNull(model.info)
                    assertTrue(model.info!!.supportsOutline)
                }
                await { visible("Recents") && visible("Settings") }
                listOf("Contents", "Reading appearance", "Reading view", "Normal view", "Present view", "Rotate page",
                    "Reset zoom and rotation", "Export", "Text", "JSON", "Page image",
                    "Print to PDF", "File info").forEach { label ->
                    assertFalse("Home must not expose $label for its retained document", visit { matches(it, label) })
                }
                click("Settings")
                await { visible("App theme") && visible("App theme: System") }
                for (label in listOf("Document defaults", "Original")) await {
                    if (visible(label)) true else {
                        visit { it.isVisibleToUser && it.isScrollable &&
                            it.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) }
                        false
                    }
                }
                assertTrue("Default appearance is directly available in Settings", visit { node ->
                    node.isVisibleToUser && matches(node, "Original") && enabledChoiceOwner(node)
                })
                scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                await { homeVisible(scenario) }
                click(file.name)
                await { visible("Find in document") }
                click("Contents")
                await { visible("Repeated") }
                assertTrue(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
                await { !visible("Repeated") && visible("Find in document") }
            }
        } finally { file.delete() }
    }
    @Test fun printActionOpensTheSystemSpoolerAndCancelReturnsToTheSameDocument() {
        val context = instrumentation.targetContext
        assumeTrue("Device has no Android printing framework", context.packageManager.hasSystemFeature(PackageManager.FEATURE_PRINTING))
        val file = File(context.cacheDir, "print-ui-${UUID.randomUUID()}.pdf")
        writeReaderPdf(file)
        try {
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN)).use { scenario ->
                scenario.onActivity { ViewModelProvider(it)[ReaderModel::class.java].open(Uri.fromFile(file)) }
                await {
                    var ready = false
                    scenario.onActivity {
                        val model = ViewModelProvider(it)[ReaderModel::class.java]
                        assertNull(model.error)
                        ready = model.currentTitle == file.name && model.bitmap != null && !model.pageLoading
                    }
                    ready && visible("Document pages")
                }
                awaitUsableControl(scenario, "Export")
                val position = settledPagePosition(scenario)
                var hash: String? = null
                var identity = 0L
                var pose = app.smallthingz.alldoc.state.ViewportState()
                scenario.onActivity {
                    val model = ViewModelProvider(it)[ReaderModel::class.java]
                    hash = model.documentHash; identity = model.documentId; pose = model.viewport
                }
                assertDocumentControls()
                click("Export")
                await { visible("Print to PDF") && visible("Text") && visible("JSON") && visible("Page image") }
                click("Print to PDF")
                await { systemPrintSpoolerVisible() }
                assertTrue("Cancel must be delivered to the verified print window", instrumentation.uiAutomation
                    .performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
                await { scenario.state == Lifecycle.State.RESUMED && visible("Document pages") && visible("Home") }
                scenario.onActivity {
                    val model = ViewModelProvider(it)[ReaderModel::class.java]
                    assertNull(model.error)
                    assertEquals(hash, model.documentHash)
                    assertEquals(identity, model.documentId)
                    assertEquals(pose, model.viewport)
                    assertEquals(position.first, model.anchorPage)
                    assertEquals(position.second, model.pageFraction, .001f)
                    assertTrue(model.readerVisible)
                    assertNotNull(model.bitmap)
                }
            }
        } finally {
            if (systemPrintSpoolerVisible()) instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            file.delete()
        }
    }

    @Suppress("DEPRECATION")
    private fun systemPrintSpoolerVisible(): Boolean {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return false
        val owner = try { root.packageName?.toString() } finally { root.recycle() } ?: return false
        if (owner == instrumentation.targetContext.packageName) return false
        return runCatching {
            val info = instrumentation.targetContext.packageManager.getPackageInfo(owner, PackageManager.GET_SERVICES)
            val flags = info.applicationInfo?.flags ?: 0
            flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0 &&
                info.services.orEmpty().any { it.permission == "android.permission.BIND_PRINT_SPOOLER_SERVICE" }
        }.getOrDefault(false)
    }

    // The 24dp crop lies inside the opaque 36dp pad; test actual glyph and pad contrast.
    private fun glyphAndPadMask(bounds: Rect, vector: ImageVector): Pair<List<Int>, List<Int>> {
        val bitmap = Bitmap.createBitmap(bounds.width(), bounds.height(), Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }
            val paths = vector.root.map { node ->
                PathParser().addPathNodes((node as VectorPath).pathData).toPath().asAndroidPath()
            }
            canvas.scale(bitmap.width / vector.viewportWidth, bitmap.height / vector.viewportHeight)
            paths.forEach { canvas.drawPath(it, paint) }
            val core = IntArray(bitmap.width * bitmap.height).also {
                bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            }.map { android.graphics.Color.alpha(it) }
            return core.indices.filter { core[it] == 255 } to core.indices.filter { core[it] == 0 }
        } finally { bitmap.recycle() }
    }

    private fun assertLegibleGlyph(pixels: IntArray, mask: Pair<List<Int>, List<Int>>) {
        assertTrue("Expected nonempty glyph and pad masks", mask.first.isNotEmpty() && mask.second.isNotEmpty())
        assertTrue("The visible vector and its tonal pad must contrast", hasLegibleGlyph(pixels, mask))
    }

    private fun hasLegibleGlyph(pixels: IntArray, mask: Pair<List<Int>, List<Int>>): Boolean {
        if (mask.first.isEmpty() || mask.second.isEmpty()) return false
        fun dominant(indices: List<Int>) = indices.groupingBy { pixels[it] }.eachCount().maxBy { it.value }.key
        return colorDistance(dominant(mask.first), dominant(mask.second)) > 64
    }

    private fun colorDistance(first: Int, second: Int): Int = maxOf(
        kotlin.math.abs((first shr 16 and 255) - (second shr 16 and 255)),
        kotlin.math.abs((first shr 8 and 255) - (second shr 8 and 255)),
        kotlin.math.abs((first and 255) - (second and 255)))

    @Suppress("DEPRECATION")
    private fun enabledChoiceOwner(node: AccessibilityNodeInfo, depth: Int = 0): Boolean {
        if (depth > 16 || !node.refresh() || node.packageName?.toString() != "app.smallthingz.alldoc" ||
            !node.isVisibleToUser || !node.isEnabled) return false
        if (node.isCheckable) return true
        val parent = node.parent ?: return false
        return try { enabledChoiceOwner(parent, depth + 1) } finally { parent.recycle() }
    }

    private fun controlBounds(label: String): Rect? {
        var bounds: Rect? = null
        visit { node ->
            if (node.isVisibleToUser && matches(node, label)) bounds = Rect().also { node.getBoundsInScreen(it) }
            bounds != null
        }
        return bounds
    }

    private fun awaitUsableControl(scenario: ActivityScenario<MainActivity>, label: String) = await {
        val bounds = actionBounds(label) ?: return@await false
        var usable = false
        scenario.onActivity { activity ->
            val view = activity.window.decorView
            val origin = IntArray(2).also(view::getLocationOnScreen)
            val insets = ViewCompat.getRootWindowInsets(view)?.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val safe = Rect(origin[0] + (insets?.left ?: 0), origin[1] + (insets?.top ?: 0),
                origin[0] + view.width - (insets?.right ?: 0), origin[1] + view.height - (insets?.bottom ?: 0))
            val minimum = (48 * view.resources.displayMetrics.density).toInt() - 1
            usable = safe.contains(bounds) && bounds.width() >= minimum && bounds.height() >= minimum
        }
        usable
    }

    private fun fullScreen() = visit { matches(it, "Reading area") && it.isVisibleToUser &&
        AccessibilityNodeInfoCompat.wrap(it).stateDescription?.toString() == "Full screen" }

    private fun readingActionAvailable(label: String) = visit { node -> node.isVisibleToUser &&
        matches(node, "Reading area") && node.actionList.any { it.label?.toString() == label } }

    private fun performReadingAction(label: String) = await { visit { node ->
        if (!node.isVisibleToUser || !matches(node, "Reading area")) false
        else node.actionList.firstOrNull { it.label?.toString() == label }?.let { node.performAction(it.id) } == true
    } }

    private fun settledTextOffset(scenario: ActivityScenario<MainActivity>): Int {
        var previous: TextPosition? = null
        var stable = 0
        // The character offset stays fixed while a fling moves within one visual line.
        await(detail = { "Text position did not settle: $previous; ${readingControlsState()}" }) {
            var current: TextPosition? = null
            scenario.onActivity { current = ViewModelProvider(it)[ReaderModel::class.java].textPosition }
            stable = if (current == previous) stable + 1 else 0
            previous = current
            current != null && stable >= 5
        }
        return checkNotNull(previous).offset
    }

    private fun readingGesture(drag: Boolean, cancelled: Boolean = false) = touchGesture(
        checkNotNull(controlBounds("Reading area")) { "Reading area is not visible" }, drag, cancelled)

    private fun tapControl(label: String): Rect {
        var bounds: Rect? = null
        var stableSince = SystemClock.uptimeMillis()
        await(detail = { "$label did not settle; ${readingControlsState()}" }) {
            val current = actionBounds(label)
            if (current == null || current.isEmpty || current != bounds) stableSince = SystemClock.uptimeMillis()
            bounds = current
            current != null && !current.isEmpty && SystemClock.uptimeMillis() - stableSince >= 200
        }
        return checkNotNull(bounds).also { touchGesture(it, drag = false) }
    }

    @Suppress("DEPRECATION")
    private fun readingControlsState(target: String? = null): String {
        val state = mutableListOf<String>()
        instrumentation.uiAutomation.rootInActiveWindow?.let { root ->
            try { root.refresh(); state += "window=${root.windowId} owner=${root.packageName}" }
            finally { root.recycle() }
        }
        val labels = (listOf("Reading area", "Close search", "Exit full screen", "Previous page", "Next page", "Page") +
            listOfNotNull(target)).distinct()
        visit { node ->
            labels.firstOrNull { matches(node, it) }?.let { label ->
                val bounds = Rect().also(node::getBoundsInScreen)
                state += "$label visible=${node.isVisibleToUser} enabled=${node.isEnabled} bounds=$bounds state=${AccessibilityNodeInfoCompat.wrap(node).stateDescription} " +
                    "actions=${node.actionList.mapNotNull { it.label }}; ${clickOwnerState(node)}"
            }
            false
        }
        return state.joinToString("; ")
    }

    private fun touchGesture(area: Rect, drag: Boolean, cancelled: Boolean = false) {
        val x = area.exactCenterX()
        val from = area.top + area.height() * if (drag) .75f else .5f
        val to = area.top + area.height() * if (drag) .35f else .5f
        touchGesture(x, from, to, drag, cancelled)
    }

    private fun touchGesture(x: Float, from: Float, to: Float, drag: Boolean, cancelled: Boolean = false,
        endX: Float = x, beforeRelease: () -> Unit = {}, stepMillis: Long = 25,
    ) {
        val down = SystemClock.uptimeMillis()
        fun event(action: Int, px: Float, y: Float) {
            val motion = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, px, y, 0)
            motion.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue("Touch event was rejected", instrumentation.uiAutomation.injectInputEvent(motion, true)) }
            finally { motion.recycle() }
        }
        var released = false
        try {
            event(MotionEvent.ACTION_DOWN, x, from)
            if (drag) for (step in 1..12) {
                SystemClock.sleep(stepMillis)
                event(MotionEvent.ACTION_MOVE, x + (endX - x) * step / 12, from + (to - from) * step / 12)
            } else SystemClock.sleep(60)
            beforeRelease()
            event(if (cancelled) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, endX, to)
            released = true
        } finally { if (!released) event(MotionEvent.ACTION_CANCEL, endX, to) }
    }

    private fun initialBottomControlPixels(scenario: ActivityScenario<MainActivity>, label: String,
        vector: ImageVector,
    ): Pair<Rect, IntArray> {
        var previous: Rect? = null
        var stable = 0
        await {
            val area = controlBounds("Reading area") ?: return@await false
            val target = actionBounds(label) ?: return@await false
            var padding = 0
            var bottomInset = 0
            var rightInset = 0
            scenario.onActivity {
                padding = (8 * it.resources.displayMetrics.density).roundToInt()
                val safe = ViewCompat.getRootWindowInsets(it.window.decorView)?.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                bottomInset = safe?.bottom ?: 0; rightInset = safe?.right ?: 0
            }
            // The bottom toolbar is centered and width-limited; its action need not hug the screen edge.
            val placed = kotlin.math.abs(area.bottom - target.bottom - bottomInset - padding) <= 2 &&
                target.top > area.centerY() && target.left >= area.left + padding &&
                target.right <= area.right - rightInset - padding
            stable = if (placed && target == previous) stable + 1 else 0
            previous = target
            stable >= 5
        }
        // PixelCopy fences the committed baseline at the final slot; never request a redraw.
        var baseline: Pair<Rect, IntArray>? = null
        await {
            val frame = controlPixels(scenario, label)
            val mask = glyphAndPadMask(frame.first, vector)
            if (hasLegibleGlyph(frame.second, mask)) { baseline = frame; true } else false
        }
        return checkNotNull(baseline)
    }

    private fun glyphBounds(scenario: ActivityScenario<MainActivity>, label: String): Rect? {
        val target = actionBounds(label) ?: return null
        var side = 0
        scenario.onActivity { side = (24 * it.resources.displayMetrics.density).roundToInt() }
        val left = target.left + (target.width() - side) / 2
        val top = target.top + (target.height() - side) / 2
        return Rect(left, top, left + side, top + side)
    }

    private fun controlPixels(scenario: ActivityScenario<MainActivity>, label: String): Pair<Rect, IntArray> {
        var bounds: Rect? = null
        await { bounds = glyphBounds(scenario, label); bounds != null }
        val screen = requireNotNull(bounds)
        val image = Bitmap.createBitmap(screen.width(), screen.height(), Bitmap.Config.ARGB_8888)
        val copied = CountDownLatch(1)
        val status = AtomicInteger(-1)
        try {
            scenario.onActivity { activity ->
                val origin = IntArray(2).also(activity.window.decorView::getLocationOnScreen)
                val source = Rect(screen).apply { offset(-origin[0], -origin[1]) }
                PixelCopy.request(activity.window, source, image, { result -> status.set(result); copied.countDown() }, Handler(Looper.getMainLooper()))
            }
            assertTrue("PixelCopy did not complete", copied.await(5, TimeUnit.SECONDS))
            assertEquals(PixelCopy.SUCCESS, status.get())
            assertEquals("Control moved during PixelCopy", screen, glyphBounds(scenario, label))
            return screen to IntArray(image.width * image.height).also { image.getPixels(it, 0, image.width, 0, 0, image.width, image.height) }
        } finally { image.recycle() }
    }

    private fun firstVisiblePage(): Int? {
        var first: Int? = null
        visit { node ->
            if (node.isVisibleToUser) node.contentDescription?.toString()?.takeIf { it.startsWith("Page ") }?.removePrefix("Page ")?.toIntOrNull()?.let {
                first = minOf(first ?: Int.MAX_VALUE, it - 1)
            }
            false
        }
        return first
    }

    private fun settledPagePosition(
        scenario: ActivityScenario<MainActivity>, presentationBounds: Rect? = null,
    ): Pair<Int, Float> {
        var previous = -1 to -1f
        var stable = 0
        var geometry = ""
        await(detail = { "${pageState(scenario)} $geometry" }) {
            var current = previous
            var legalPosition = presentationBounds == null
            scenario.onActivity { activity -> val model = ViewModelProvider(activity)[ReaderModel::class.java]
                current = model.anchorPage to model.pageFraction
                if (presentationBounds != null) {
                    geometry = "Presentation aspect is not available"
                    val aspect = model.pageAspects[model.anchorPage] ?: return@onActivity
                    val density = activity.resources.displayMetrics.density
                    val fitHeight = presentationBounds.height() - 2f * minOf(16f * density, presentationBounds.height() / 8f)
                    val extent = pageRowLength(presentationBounds.width().toFloat(), fitHeight, aspect, model.viewport, true)
                    val offset = model.pageFraction * (extent + 8 * density)
                    geometry = "offset=$offset extent=$extent viewport=${presentationBounds.width()}"
                    legalPosition = offset <= extent - presentationBounds.width() + 2f
                }
            }
            // A snap spring can move less than .001 per sample while still crossing a page gap.
            stable = if (legalPosition && current.first == previous.first && kotlin.math.abs(current.second - previous.second) < .001f) stable + 1 else 0
            previous = current
            stable >= 5
        }
        return previous
    }

    private fun scrollToPage(scenario: ActivityScenario<MainActivity>, expected: Int, horizontal: Boolean = false) {
        repeat(8) {
            var page = -1
            scenario.onActivity { page = ViewModelProvider(it)[ReaderModel::class.java].anchorPage }
            if (page != expected) {
                val area = checkNotNull(controlBounds("Document pages"))
                if (horizontal) touchGesture(area.left + area.width() * .8f, area.exactCenterY(), area.exactCenterY(),
                    drag = true, endX = area.left + area.width() * .25f)
                else touchGesture(area.exactCenterX(), area.top + area.height() * .8f,
                    area.top + area.height() * .25f, drag = true)
                instrumentation.waitForIdleSync()
            }
        }
        await(detail = { "Expected page=$expected, horizontal=$horizontal; ${pageState(scenario)}" }) {
            var ready = false
            scenario.onActivity { val model = ViewModelProvider(it)[ReaderModel::class.java]
                assertNull(model.error)
                ready = model.anchorPage == expected && model.bitmap != null && !model.pageLoading }
            ready
        }
    }

    private fun pageState(scenario: ActivityScenario<MainActivity>): String {
        var state = ""
        scenario.onActivity { val model = ViewModelProvider(it)[ReaderModel::class.java]
            state = "page=${model.page}, anchor=${model.anchorPage}, fraction=${model.pageFraction}, horizontal=${model.horizontalPages}, " +
                "pose=${model.viewport}, loading=${model.pageLoading}, bitmap=${model.bitmap != null}, error=${model.error}" }
        visit { node ->
            if (node.isVisibleToUser && node.contentDescription?.toString() == "Document pages") {
                state += "; bounds=${Rect().also(node::getBoundsInScreen)}, actions=${node.actionList.map { it.id }}"
                true
            } else false
        }
        return state
    }

    private fun assertDocumentControls(fullscreen: Boolean = false) {
        val hidden = listOf("Settings", "Open", "Open document", "Browse", "Print to PDF", "Next page", "Previous page") +
            if (fullscreen) listOf("Home") else emptyList()
        hidden.forEach { assertFalse("Document view must not expose $it", visible(it)) }
    }

    private fun assertFullscreenCoversWindow(scenario: ActivityScenario<MainActivity>) = await {
        val area = controlBounds("Reading area") ?: return@await false
        var covers = false
        scenario.onActivity {
            val window = it.window
            val decor = window.decorView
            val origin = IntArray(2).also(decor::getLocationOnScreen)
            val insets = ViewCompat.getRootWindowInsets(decor)
            covers = kotlin.math.abs(area.left - origin[0]) <= 1 && kotlin.math.abs(area.top - origin[1]) <= 1 &&
                kotlin.math.abs(area.right - origin[0] - decor.width) <= 1 &&
                kotlin.math.abs(area.bottom - origin[1] - decor.height) <= 1 &&
                insets != null && !insets.isVisible(WindowInsetsCompat.Type.statusBars()) &&
                !insets.isVisible(WindowInsetsCompat.Type.navigationBars()) && !insets.isVisible(WindowInsetsCompat.Type.captionBar())
            assertTrue("Fullscreen must permit drawing beneath display cutouts", window.attributes.layoutInDisplayCutoutMode in
                listOf(WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES,
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS))
        }
        covers
    }

    private fun homeVisible(scenario: ActivityScenario<MainActivity>): Boolean {
        var home = false
        scenario.onActivity { home = !ViewModelProvider(it)[ReaderModel::class.java].readerVisible }
        return home && visible("Open") && visible("About AllDoc")
    }
    private fun await(detail: () -> String = { "" }, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 12000
        while (SystemClock.uptimeMillis() < deadline) {
            if (predicate()) return
            SystemClock.sleep(30)
        }
        fail("Reader navigation did not settle. ${detail()}")
    }
    private fun click(label: String) {
        await(detail = { "Could not click $label; ${readingControlsState(label)}" }) {
            if (!visible(label) && readingActionAvailable("Show reading controls")) {
                performReadingAction("Show reading controls")
                return@await false
            }
            visit { node -> node.isVisibleToUser && node.isEnabled && matches(node, label) && clickNode(node) }
        }
    }
    private fun awaitSeparateActions(first: String, second: String) = await {
        val a = actionBounds(first)
        val b = actionBounds(second)
        a != null && b != null && !a.isEmpty && !b.isEmpty && !Rect.intersects(a, b)
    }
    private fun actionBounds(label: String): Rect? {
        var bounds: Rect? = null
        visit { node ->
            if (node.isVisibleToUser && matches(node, label)) bounds = actionBounds(node)
            bounds != null
        }
        return bounds
    }
    @Suppress("DEPRECATION")
    private fun actionBounds(node: AccessibilityNodeInfo, depth: Int = 0): Rect? {
        if (!node.refresh() || !node.isVisibleToUser) return null
        if (node.isClickable) return Rect().also { node.getBoundsInScreen(it) }
        if (depth >= 16) return null
        val parent = node.parent ?: return null
        return try { actionBounds(parent, depth + 1) } finally { parent.recycle() }
    }
    @Suppress("DEPRECATION")
    private fun disabledAction(node: AccessibilityNodeInfo, depth: Int = 0): Boolean {
        if (depth >= 16 || !node.refresh()) return false
        if (node.isClickable) return node.isVisibleToUser && !node.isEnabled
        val parent = node.parent ?: return false
        return try { disabledAction(parent, depth + 1) } finally { parent.recycle() }
    }
    @Suppress("DEPRECATION")
    private fun selectedChoice(node: AccessibilityNodeInfo, depth: Int = 0): Boolean {
        if (depth >= 16 || !node.refresh() || !node.isVisibleToUser) return false
        if (node.isCheckable || node.isSelected) return node.isChecked || node.isSelected
        val parent = node.parent ?: return false
        return try { selectedChoice(parent, depth + 1) } finally { parent.recycle() }
    }

    private fun visible(label: String) = visit { it.isVisibleToUser && matches(it, label) }
    private fun matches(node: AccessibilityNodeInfo, label: String) =
        node.text?.toString()?.lineSequence()?.any { it == label } == true || node.contentDescription?.toString() == label
    @Suppress("DEPRECATION")
    private fun visit(test: (AccessibilityNodeInfo) -> Boolean): Boolean {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return false
        return try { visit(root, 0, intArrayOf(0), test) } finally { root.recycle() }
    }
    @Suppress("DEPRECATION")
    private fun visit(node: AccessibilityNodeInfo, depth: Int, seen: IntArray, test: (AccessibilityNodeInfo) -> Boolean): Boolean {
        if (depth > 24 || seen[0]++ >= 512 || !node.refresh()) return false
        if (node.packageName?.toString() == "app.smallthingz.alldoc" && test(node)) return true
        for (index in 0 until minOf(node.childCount, 64)) {
            val child = node.getChild(index) ?: continue
            try { if (visit(child, depth + 1, seen, test)) return true } finally { child.recycle() }
        }
        return false
    }
    @Suppress("DEPRECATION")
    private fun clickOwnerState(node: AccessibilityNodeInfo, depth: Int = 0): String {
        if (depth > 16) return "owner=depth-limit"
        if (!node.refresh()) return "owner=unavailable"
        if (node.isClickable) {
            val bounds = Rect().also(node::getBoundsInScreen)
            return "owner=${node.className} visible=${node.isVisibleToUser} enabled=${node.isEnabled} bounds=$bounds " +
                "clickAction=${node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }}"
        }
        val parent = node.parent ?: return "owner=none"
        return try { clickOwnerState(parent, depth + 1) } finally { parent.recycle() }
    }

    @Suppress("DEPRECATION")
    private fun clickNode(node: AccessibilityNodeInfo, depth: Int = 0): Boolean {
        if (!node.refresh() || !node.isVisibleToUser) return false
        if (node.isClickable) return node.isEnabled && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (depth >= 16) return false
        val parent = node.parent ?: return false
        return try { clickNode(parent, depth + 1) } finally { parent.recycle() }
    }
}
