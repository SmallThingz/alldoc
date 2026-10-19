package app.smallthingz.alldoc.ui

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.net.Uri
import android.os.CancellationSignal
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.snapshots.Snapshot
import androidx.test.core.app.ActivityScenario
import app.smallthingz.alldoc.ReaderTestActivity
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.smallthingz.alldoc.core.MarkdownDocument
import app.smallthingz.alldoc.engine.DocumentInfo
import app.smallthingz.alldoc.engine.DocumentNotes
import app.smallthingz.alldoc.engine.DocumentNote
import app.smallthingz.alldoc.engine.DocumentOutlineEntry
import app.smallthingz.alldoc.engine.DocumentSession
import app.smallthingz.alldoc.engine.PageRegion
import app.smallthingz.alldoc.sources.DocumentSources
import app.smallthingz.alldoc.state.DocumentStore
import app.smallthingz.alldoc.theme.BitmapTheme
import app.smallthingz.alldoc.theme.RegionThemeContext
import app.smallthingz.alldoc.theme.ThemeSettings
import java.io.Closeable
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderAllocationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync { action() }

    @Test fun addingASecondViewportPatchRebudgetsAnUnchangedFirstRequest() {
        val closes = AtomicInteger()
        Fixture(closes, pages = 2).use { fixture ->
            main {
                fixture.reader.setAppearance(ThemeSettings(app.smallthingz.alldoc.theme.ReadingMode.ORIGINAL))
                fixture.reader.requestVisiblePages(0..1)
                fixture.reader.setRenderWidth(720)
            }
            fixture.await { it.pageImages.keys.containsAll(listOf(0, 1)) }
            val wanted = PageRegion(.1f, .1f, .7f, .7f, 2048, 2048)
            main { fixture.reader.requestVisibleRegions(mapOf(0 to wanted)) }
            fixture.await { it.pageDetails[0]?.bitmap?.allocationByteCount == 16 * 1024 * 1024 && detailJob(it)?.isActive != true }
            lateinit var original: Bitmap
            main {
                original = requireNotNull(fixture.reader.pageDetails[0]).bitmap
                fixture.reader.requestVisibleRegions(mapOf(0 to wanted, 1 to wanted))
            }
            fixture.await { reader -> reader.pageDetails.size == 2 && reader.pageDetails.values.all {
                it.bitmap.allocationByteCount <= 8 * 1024 * 1024
            } && detailJob(reader)?.isActive != true }
            val calls = fixture.session.regionCalls.toList()
            assertEquals("Both patches must render again under the smaller shared budget", listOf(0, 0, 1), calls.map { it.first })
            assertEquals(wanted, calls.first().second)
            assertTrue(calls.drop(1).all { it.second.width < wanted.width && it.second.height < wanted.height })
            main {
                assertTrue(fixture.reader.pageDetails.values.sumOf { it.bitmap.allocationByteCount.toLong() } <= 16L * 1024 * 1024)
                assertFalse("A previously displayed patch remains valid through its final Compose frame", original.isRecycled)
                repeat(20) { fixture.reader.requestVisibleRegions(mapOf(0 to wanted, 1 to wanted)) }
                assertTrue(detailJob(fixture.reader)?.isActive != true)
                assertNull(fixture.reader.error)
            }
            assertEquals("Unchanged viewport callbacks must not rerender", calls, fixture.session.regionCalls.toList())
        }
        assertEquals(1, closes.get())
    }

    @Test fun supersededHiddenAndReplacedDocumentsRejectLateViewportPixels() {
        for (exit in listOf("superseded", "hidden", "document")) {
            val closes = AtomicInteger()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            Fixture(closes).use { fixture ->
                try {
                    main {
                        fixture.reader.setAppearance(ThemeSettings(app.smallthingz.alldoc.theme.ReadingMode.ORIGINAL))
                        fixture.reader.setRenderWidth(720)
                    }
                    fixture.await { it.bitmap != null && !it.pageLoading }
                    val warm = PageRegion(0f, 0f, .2f, .2f, 64, 64)
                    val pending = PageRegion(.1f, .1f, .6f, .6f, 512, 512)
                    val next = PageRegion(.3f, .3f, .8f, .8f, 384, 384)
                    main { fixture.reader.requestVisibleRegions(mapOf(0 to warm)) }
                    fixture.await { it.pageDetails[0]?.region == warm && detailJob(it)?.isActive != true }
                    lateinit var retained: Bitmap
                    var late: Bitmap? = null
                    var canceled: CancellationSignal? = null
                    val attempts = AtomicInteger()
                    fixture.session.afterRegion = { _, _, image, signal ->
                        if (attempts.getAndIncrement() == 0) {
                            late = image; canceled = signal
                            entered.countDown()
                            check(release.await(15, TimeUnit.SECONDS))
                        }
                    }
                    lateinit var oldJob: Job
                    var identity = 0L
                    main {
                        retained = requireNotNull(fixture.reader.pageDetails[0]).bitmap
                        identity = fixture.reader.documentId
                        fixture.reader.requestVisibleRegions(mapOf(0 to pending))
                        oldJob = requireNotNull(detailJob(fixture.reader))
                    }
                    assertTrue(entered.await(5, TimeUnit.SECONDS))
                    main {
                        when (exit) {
                            "superseded" -> fixture.reader.requestVisibleRegions(mapOf(0 to next))
                            "hidden" -> fixture.reader.showReader(false)
                            else -> {
                                val file = File(fixture.context.root, "replacement.html").apply {
                                    writeText("<html><body>Replacement document</body></html>")
                                }
                                fixture.reader.open(Uri.fromFile(file))
                            }
                        }
                        assertTrue(oldJob.isCancelled)
                        assertTrue(requireNotNull(canceled).isCanceled)
                        if (exit != "document") assertSame(retained, fixture.reader.pageDetails[0]?.bitmap)
                    }
                    if (exit == "document") {
                        fixture.await { it.currentTitle == "replacement.html" && it.info != null && !it.loading }
                        main {
                            assertTrue(fixture.reader.pageDetails.isEmpty())
                            fixture.reader.requestVisibleRegions(mapOf(0 to pending), identity)
                            assertTrue("An old view cannot repopulate the replacement document", fixture.reader.pageDetails.isEmpty())
                        }
                    }
                    assertEquals("Canceled native work remains serialized until it returns", 2, fixture.session.regionCalls.size)
                    release.countDown()
                    runBlocking { withTimeout(15_000) { oldJob.join() } }
                    assertTrue("An unpublished late viewport must be recycled", requireNotNull(late).isRecycled)
                    if (exit == "hidden") {
                        main {
                            assertFalse(fixture.reader.readerVisible)
                            assertSame(retained, fixture.reader.pageDetails[0]?.bitmap)
                            assertEquals(2, fixture.session.regionCalls.size)
                            fixture.reader.showReader(true)
                        }
                        fixture.await { it.pageDetails[0]?.region == pending && detailJob(it)?.isActive != true }
                    } else if (exit == "superseded") {
                        fixture.await { it.pageDetails[0]?.region == next && detailJob(it)?.isActive != true }
                    } else fixture.await { closes.get() == 1 && it.bitmap != null && !it.pageLoading }
                    main {
                        assertNull(fixture.reader.error)
                        assertFalse(retained.isRecycled)
                        assertTrue(fixture.reader.pageDetails.values.none { it.bitmap === late })
                        if (exit == "document") assertTrue(fixture.reader.pageDetails.isEmpty())
                    }
                } finally { release.countDown() }
            }
            assertEquals(1, closes.get())
        }
    }

    private fun detailJob(reader: ReaderModel): Job? {
        val task = ReaderModel::class.java.getDeclaredField("detailing").apply { isAccessible = true }.get(reader)
        return task.javaClass.getDeclaredField("job").apply { isAccessible = true }.get(task) as Job?
    }

    @Test fun changingAppearanceRebuildsTheSameViewportWithoutAnotherLayoutCallback() {
        val closes = AtomicInteger()
        Fixture(closes).use { fixture ->
            val appearances = CopyOnWriteArrayList<ThemeSettings>()
            fixture.session.regionAppearance = { appearances += it }
            main {
                fixture.reader.setAppearance(ThemeSettings(app.smallthingz.alldoc.theme.ReadingMode.ORIGINAL))
                fixture.reader.setRenderWidth(720)
            }
            fixture.await { it.bitmap != null && !it.pageLoading }
            val wanted = PageRegion(.1f, .2f, .6f, .7f, 512, 512)
            main { fixture.reader.requestVisibleRegions(mapOf(0 to wanted)) }
            fixture.await { it.pageDetails[0]?.region == wanted && detailJob(it)?.isActive != true }
            lateinit var first: Bitmap
            val appearance = ThemeSettings(app.smallthingz.alldoc.theme.ReadingMode.ADAPTIVE)
            main {
                first = requireNotNull(fixture.reader.pageDetails[0]).bitmap
                fixture.reader.setAppearance(appearance)
                assertTrue(fixture.reader.pageDetails.isEmpty())
            }
            fixture.await { it.pageDetails[0]?.region == wanted && it.pageDetails[0]?.bitmap !== first && detailJob(it)?.isActive != true }
            main {
                assertEquals(listOf(ThemeSettings(app.smallthingz.alldoc.theme.ReadingMode.ORIGINAL), appearance), appearances.toList())
                assertEquals(listOf(0 to wanted, 0 to wanted), fixture.session.regionCalls.toList())
                assertFalse(first.isRecycled)
                assertNull(fixture.reader.error)
            }
        }
        assertEquals(1, closes.get())
    }

    @Test fun aBaseUpgradeRejectsQueuedOldPolicyDetailAndRetriesTheCurrentPolicy() {
        val closes = AtomicInteger()
        val baseEntered = CountDownLatch(1)
        val releaseBase = CountDownLatch(1)
        val detailEntered = CountDownLatch(1)
        val releaseDetail = CountDownLatch(1)
        Fixture(closes).use { fixture ->
            try {
                fixture.session.trackThemeContexts = true
                main {
                    fixture.reader.setAppearance(ThemeSettings(app.smallthingz.alldoc.theme.ReadingMode.ADAPTIVE))
                    fixture.reader.setRenderWidth(720)
                }
                fixture.await { it.pageImages[0]?.themeContext != null && !it.pageLoading }
                lateinit var originalContext: RegionThemeContext
                main { originalContext = requireNotNull(fixture.reader.pageImages[0]?.themeContext) }
                val contexts = CopyOnWriteArrayList<RegionThemeContext?>()
                val images = CopyOnWriteArrayList<Bitmap>()
                fixture.session.regionContext = { _, context -> contexts += context }
                fixture.session.beforeRender = { _, _, _ ->
                    baseEntered.countDown()
                    check(releaseBase.await(15, TimeUnit.SECONDS))
                }
                fixture.session.afterRegion = { _, _, image, _ ->
                    images += image
                    if (images.size == 1) {
                        detailEntered.countDown()
                        check(releaseDetail.await(15, TimeUnit.SECONDS))
                    }
                }
                main { fixture.reader.setRenderWidth(1440) }
                assertTrue(baseEntered.await(5, TimeUnit.SECONDS))
                val wanted = PageRegion(.1f, .1f, .6f, .6f, 128, 128)
                main { fixture.reader.requestVisibleRegions(mapOf(0 to wanted)) }
                // Let the detail's 100 ms coalescing timer reach the pending base upgrade.
                SystemClock.sleep(150)
                instrumentation.waitForIdleSync()
                assertTrue("A known base upgrade must defer native detail work", contexts.isEmpty())
                releaseBase.countDown()
                fixture.await { it.pageImages[0]?.themeContext?.let { context -> context !== originalContext } == true }
                // This headless fixture has no Compose owner to publish pending snapshot writes.
                main { Snapshot.sendApplyNotifications() }
                assertTrue(detailEntered.await(5, TimeUnit.SECONDS))
                main {
                    assertSame("The queued request must capture the newly published base policy",
                        fixture.reader.pageImages[0]?.themeContext, contexts.single())
                    assertTrue(fixture.reader.pageDetails.isEmpty())
                }
                releaseDetail.countDown()
                fixture.await { it.pageDetails[0]?.region == wanted && detailJob(it)?.isActive != true }
                main {
                    assertEquals("A queued upgrade must need only one native detail render", 1, contexts.size)
                    assertSame(fixture.reader.pageImages[0]?.themeContext, contexts.single())
                    assertSame(images.single(), fixture.reader.pageDetails[0]?.bitmap)
                    assertFalse(images.single().isRecycled)
                    repeat(20) { fixture.reader.requestVisibleRegions(mapOf(0 to wanted)) }
                    assertNull(fixture.reader.error)
                }
                assertEquals(1, fixture.session.regionCalls.size)
                assertEquals("Detail must not reclassify another complete page", 2, fixture.session.themedContexts.size)
            } finally { releaseBase.countDown(); releaseDetail.countDown() }
        }
        assertEquals(1, closes.get())
    }

    @Test fun viewportDetailUsesItsRetainedBasePolicyAndRebuildsOnlyADiscardedPolicy() {
        val closes = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        Fixture(closes, pages = 3).use { fixture ->
            try {
                fixture.session.trackThemeContexts = true
                val details = CopyOnWriteArrayList<Pair<Int, RegionThemeContext?>>()
                fixture.session.regionContext = { index, context -> details += index to context }
                main {
                    fixture.reader.setAppearance(ThemeSettings(app.smallthingz.alldoc.theme.ReadingMode.ADAPTIVE))
                    fixture.reader.requestVisiblePages(0..2)
                    fixture.reader.setRenderWidth(720)
                }
                fixture.await { it.pageImages.keys.containsAll(listOf(0, 1, 2)) }
                lateinit var firstContext: RegionThemeContext
                lateinit var thirdBitmap: Bitmap
                main {
                    firstContext = requireNotNull(fixture.reader.pageImages[0]?.themeContext)
                    thirdBitmap = requireNotNull(fixture.reader.pageImages[2]).bitmap
                    assertEquals(setOf(0, 1), fixture.reader.pageImages.filterValues { it.themeContext != null }.keys)
                    assertTrue(requireNotNull(fixture.reader.pageImages[2]).missingThemeContext)
                }
                assertEquals(listOf(0, 1, 2), fixture.session.themedContexts.map { it.first })
                assertEquals("The engine's single-entry cache has already moved to another page", 2,
                    fixture.session.cachedThemeContext?.first)
                val wanted = PageRegion(.1f, .1f, .6f, .6f, 128, 128)
                main { fixture.reader.requestVisibleRegions(mapOf(0 to wanted)) }
                fixture.await { it.pageDetails[0]?.region == wanted && detailJob(it)?.isActive != true }
                assertSame("Detail must use the policy of the displayed base, not the engine's latest page",
                    firstContext, details.single().second)
                assertEquals("Retained policy needs no extra complete-page render", listOf(0, 1, 2), fixture.session.pages.toList())
                val discardedContext = fixture.session.themedContexts.last().second
                fixture.session.beforeRender = { index, _, _ ->
                    if (index == 2) {
                        entered.countDown()
                        check(release.await(15, TimeUnit.SECONDS))
                    }
                }
                main { fixture.reader.requestVisibleRegions(mapOf(0 to wanted, 2 to wanted)) }
                assertTrue("Demand for an evicted policy must rebuild that page", entered.await(5, TimeUnit.SECONDS))
                main {
                    assertSame("Keep the published page visible while rebuilding its policy", thirdBitmap,
                        fixture.reader.pageImages[2]?.bitmap)
                    assertTrue(requireNotNull(fixture.reader.pageImages[2]).missingThemeContext)
                }
                release.countDown()
                fixture.await { it.pageDetails.keys.containsAll(listOf(0, 2)) && detailJob(it)?.isActive != true }
                main {
                    assertEquals(setOf(0, 2), fixture.reader.pageImages.filterValues { it.themeContext != null }.keys)
                    assertTrue(requireNotNull(fixture.reader.pageImages[1]).missingThemeContext)
                    val rebuilt = requireNotNull(fixture.reader.pageImages[2]?.themeContext)
                    assertNotSame(discardedContext, rebuilt)
                    assertSame(rebuilt, details.last { it.first == 2 }.second)
                    assertTrue(details.filter { it.first == 0 }.all { it.second === firstContext })
                    assertTrue(fixture.reader.pageImages.values.sumOf { it.themeContext?.byteCount ?: 0 } <=
                        2 * RegionThemeContext.MAX_BYTE_COUNT)
                    assertFalse(thirdBitmap.isRecycled)
                    repeat(20) { fixture.reader.requestVisibleRegions(mapOf(0 to wanted, 2 to wanted)) }
                    assertNull(fixture.reader.error)
                }
                assertEquals("Only the requested discarded policy may be rebuilt", listOf(0, 1, 2, 2),
                    fixture.session.themedContexts.map { it.first })
                assertEquals(listOf(0, 1, 2, 2), fixture.session.pages.toList())
            } finally { release.countDown() }
        }
        assertEquals(1, closes.get())
    }

    @Test fun pageTextAndContentsAllocationFailuresAllowRetryWithoutClosingTheDocument() {
        for (operation in listOf("page", "text", "contents")) {
            val closes = AtomicInteger()
            Fixture(closes).use { fixture ->
                val attempts = AtomicInteger()
                fixture.session.before = { kind ->
                    if (kind == operation && attempts.getAndIncrement() == 0)
                        throw OutOfMemoryError("Controlled $operation allocation failure")
                }
                fun start() = main {
                    when (operation) {
                        "page" -> fixture.reader.setRenderWidth(if (attempts.get() == 0) 720 else 721)
                        "text" -> if (!fixture.reader.textMode) fixture.reader.setTextMode(true) else fixture.reader.loadText()
                        else -> fixture.reader.loadContents()
                    }
                }
                start()
                fixture.await {
                    val failed = if (operation == "contents")
                        it.contentsError == "Not enough memory to load contents." && it.error == null
                    else it.error == "Not enough memory."
                    failed && !it.loading && !it.pageLoading && !it.textLoading && !it.contentsLoading
                }
                main {
                    assertNotNull(fixture.reader.info)
                    assertEquals(fixture.hash, fixture.reader.documentHash)
                    assertEquals(0, closes.get())
                    if (operation == "contents") assertNull(fixture.reader.error)
                    else fixture.reader.dismissError()
                }
                start()
                fixture.await { model ->
                    model.error == null && when (operation) {
                        "page" -> model.bitmap != null && !model.pageLoading
                        "text" -> model.text == "Recovered text" && !model.textLoading
                        else -> model.outline.size == 1 && !model.contentsLoading && model.contentsError == null
                    }
                }
                main { fixture.reader.bitmap?.let { assertFalse("Published page must remain valid", it.isRecycled) } }
                assertEquals(2, attempts.get())
                assertEquals(0, closes.get())
            }
            assertEquals("The session closes exactly once after its model is cleared", 1, closes.get())
        }
    }

    @Test fun staleWorkerFailuresCannotSettleAReplacementTaskOrDocument() {
        for (failure in listOf("allocation", "android", "io")) for (replaceDocument in listOf(false, true)) {
            val closes = AtomicInteger()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            Fixture(closes).use { fixture ->
                try {
                    val attempts = AtomicInteger()
                    var lateImage: Bitmap? = null
                    fun pause() { entered.countDown(); check(release.await(15, TimeUnit.SECONDS)) }
                    if (failure == "android") fixture.session.afterRender = { image ->
                        if (attempts.getAndIncrement() == 0) { lateImage = image; pause() }
                    } else fixture.session.before = { operation ->
                        if (operation == "page" && attempts.getAndIncrement() == 0) {
                            pause()
                            if (failure == "allocation") throw OutOfMemoryError("Controlled stale render allocation failure")
                            throw java.io.IOException("Controlled stale render failure")
                        }
                    }
                    lateinit var oldJob: Job
                    main {
                        fixture.reader.setRenderWidth(720)
                        val field = ReaderModel::class.java.getDeclaredField("rendering").apply { isAccessible = true }
                        val task = requireNotNull(field.get(fixture.reader))
                        oldJob = task.javaClass.getDeclaredField("job").apply { isAccessible = true }.get(task) as Job
                    }
                    assertTrue(entered.await(5, TimeUnit.SECONDS))
                    main { fixture.reader.setRenderWidth(721) }
                    main {
                        assertTrue("Superseding must cancel the old job", oldJob.isCancelled)
                        assertTrue("The replacement waits without blocking UI", fixture.reader.pageLoading)
                        assertNull(fixture.reader.bitmap)
                        assertNull(fixture.reader.error)
                    }
                    assertEquals("Blocked native rendering must remain serialized", 1, attempts.get())
                    if (replaceDocument) {
                        val next = File(fixture.context.root, "next.html").apply { writeText("<html><body>New document</body></html>") }
                        main { fixture.reader.open(Uri.fromFile(next)) }
                        fixture.await { it.currentTitle == "next.html" && it.info != null && !it.loading }
                        main {
                            assertFalse("Replacement text documents open as pages", fixture.reader.textMode)
                            assertTrue("The new page waits for the serialized old worker", fixture.reader.pageLoading)
                            assertNull(fixture.reader.bitmap)
                            assertNull(fixture.reader.error)
                        }
                    }
                    assertEquals("The session remains leased while its old worker is blocked", 0, closes.get())
                    release.countDown()
                    runBlocking { withTimeout(15_000) { oldJob.join() } }
                    lateImage?.let { assertTrue("Canceled theme work must recycle its unpublished bitmap", it.isRecycled) }
                    if (replaceDocument) fixture.await {
                        closes.get() == 1 && it.bitmap != null && !it.pageLoading
                    }
                    else {
                        fixture.await { it.bitmap != null && !it.pageLoading }
                        assertEquals(0, closes.get())
                    }
                    main {
                        assertNull(fixture.reader.error)
                        assertFalse(fixture.reader.loading)
                        assertFalse(fixture.reader.pageLoading)
                        if (replaceDocument) {
                            assertEquals("next.html", fixture.reader.currentTitle)
                            assertFalse(fixture.reader.textMode)
                            assertFalse("The replacement document page remains valid", requireNotNull(fixture.reader.bitmap).isRecycled)
                        }
                        else {
                            assertEquals(fixture.hash, fixture.reader.documentHash)
                            assertFalse("The replacement page remains valid", requireNotNull(fixture.reader.bitmap).isRecycled)
                        }
                    }
                } finally { release.countDown() }
            }
            assertEquals("Every session closes exactly once", 1, closes.get())
        }
    }

    @Test fun liveWorkerFailuresRemainVisibleAndRetryable() {
        for (failure in listOf(android.os.OperationCanceledException("Controlled active renderer failure"),
            java.io.IOException("Controlled active renderer failure"))) {
            val closes = AtomicInteger()
            Fixture(closes).use { fixture ->
                val attempts = AtomicInteger()
                fixture.session.before = { operation ->
                    if (operation == "page" && attempts.getAndIncrement() == 0)
                        throw failure
                }
                main { fixture.reader.setRenderWidth(720) }
                fixture.await { it.error == "Controlled active renderer failure" && !it.pageLoading && 0 in it.failedPages }
                main {
                    assertEquals(fixture.hash, fixture.reader.documentHash)
                    assertEquals(0, closes.get())
                    fixture.reader.dismissError()
                    fixture.reader.retryPage(0)
                }
                fixture.await { it.bitmap != null && !it.pageLoading && it.error == null }
                assertEquals(2, attempts.get())
            }
            assertEquals(1, closes.get())
        }
    }

    @Test fun visibleWindowRenderingHasABoundedCacheAndDoesNotRepeatUnchangedRequests() {
        val closes = AtomicInteger()
        Fixture(closes, pages = 12).use { fixture ->
            fixture.session.renderSize = 1024 to 4096 // 16 MiB inputs force downsampling to the shared byte budget.
            main {
                fixture.reader.setAppearance(ThemeSettings(app.smallthingz.alldoc.theme.ReadingMode.ORIGINAL))
                fixture.reader.requestVisiblePages(0..10)
                fixture.reader.setRenderWidth(720)
            }
            val task = ReaderModel::class.java.getDeclaredField("rendering").apply { isAccessible = true }.get(fixture.reader)
            val job = task.javaClass.getDeclaredField("job").apply { isAccessible = true }
            fun settled() = fixture.await { reader ->
                (job.get(task) as? Job)?.isActive != true && !reader.pageLoading && reader.bitmap != null
            }
            settled()
            val calls = fixture.session.pages.toList()
            assertTrue("Only the visible window and adjacent pages should render", calls.size in 11..13)
            assertTrue("Every requested visible page must render", calls.containsAll((0..10).toList()))
            assertEquals("A page is rendered at most once in a stable window", calls.size, calls.distinct().size)
            lateinit var previousPage: Bitmap
            main {
                val images = fixture.reader.pageImages.values.map { it.bitmap }
                assertTrue("Every requested visible page must remain ready", fixture.reader.pageImages.keys.containsAll((0..10).toList()))
                assertTrue("Published image memory must stay within 48 MiB", images.sumOf { it.allocationByteCount.toLong() } <= 48L * 1024 * 1024)
                previousPage = requireNotNull(fixture.reader.bitmap)
                repeat(30) { fixture.reader.requestVisiblePages(0..10) }
            }
            settled()
            assertEquals("Repeated viewport updates must not invoke native rendering", calls, fixture.session.pages.toList())
            main { fixture.reader.goToPage(8) }
            fixture.await { it.page == 8 && it.bitmap != null && !it.pageLoading }
            settled()
            main {
                assertFalse("Previously published pages must not be recycled while a view can retain them", previousPage.isRecycled)
                assertTrue("The current page must be retained", 8 in fixture.reader.pageImages)
                assertTrue("Distant pages must leave the visible window cache", 0 !in fixture.reader.pageImages)
                assertTrue(fixture.reader.pageImages.values.sumOf { it.bitmap.allocationByteCount.toLong() } <= 48L * 1024 * 1024)
                assertNull(fixture.reader.error)
            }
        }
        assertEquals(1, closes.get())
    }

    @Test fun revisitingPagesKeepsVisibleAspectRatiosWhenTheCacheIsFull() {
        val closes = AtomicInteger()
        Fixture(closes, pages = 500).use { fixture ->
            val remember = ReaderModel::class.java.getDeclaredMethod("rememberAspect", Int::class.javaPrimitiveType,
                Float::class.javaPrimitiveType).apply { isAccessible = true }
            main {
                val reader = fixture.reader
                fun aspect(index: Int) { remember.invoke(reader, index, .5625f) }
                (listOf(100, 101, 99) + (200..452)).forEach(::aspect)
                assertEquals(256, reader.pageAspects.size)
                val unchanged = reader.pageAspects
                aspect(101)
                assertSame("Equal ratios must not allocate or publish another map", unchanged, reader.pageAspects)
                reader.goToPage(101)
                reader.updatePagePosition(100, .375f)
                reader.requestVisiblePages(100..102)
                val pose = reader.viewport
                listOf(101, 100, 102, 103, 99).forEach(::aspect)
                assertEquals(256, reader.pageAspects.size)
                for (index in 99..103) {
                    assertEquals("Visible and adjacent page $index must keep its real ratio", .5625f,
                        requireNotNull(reader.pageAspects[index]), 0f)
                }
                assertEquals(101, reader.page)
                assertEquals(100, reader.anchorPage)
                assertEquals(.375f, reader.pageFraction, 0f)
                assertEquals(pose, reader.viewport)

                // A full render window still leaves an unprotected victim for an unrelated insertion.
                reader.updateCurrentPage(200)
                reader.requestVisiblePages(200..453)
                (199..454).forEach(::aspect)
                assertEquals((199..454).toSet(), reader.pageAspects.keys)
                aspect(499)
                assertEquals("The cache stays bounded even with 256 protected pages",
                    (199..454).toSet(), reader.pageAspects.keys)
                assertTrue("This cache test must not start native page rendering", fixture.session.pages.isEmpty())
            }
        }
        assertEquals(1, closes.get())
    }

    @Test fun resizingKeepsPublishedPagesUntilTheCurrentUpgradeIsReady() {
        val closes = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        Fixture(closes).use { fixture ->
            try {
                main {
                    fixture.reader.setAppearance(ThemeSettings(app.smallthingz.alldoc.theme.ReadingMode.ORIGINAL))
                    fixture.reader.setRenderWidth(1024)
                }
                fixture.await { it.bitmap != null && !it.pageLoading }
                lateinit var original: Bitmap
                main {
                    original = requireNotNull(fixture.reader.bitmap)
                    fixture.reader.setRenderWidth(720)
                    assertSame("Shrinking must reuse the published page", original, fixture.reader.bitmap)
                    assertFalse(fixture.reader.pageLoading)
                }
                assertEquals(listOf(1024), fixture.session.widths.toList())

                val upgrades = AtomicInteger()
                var stale: Bitmap? = null
                fixture.session.afterRender = { image ->
                    if (upgrades.getAndIncrement() == 0) {
                        stale = image
                        entered.countDown()
                        check(release.await(15, TimeUnit.SECONDS))
                    }
                }
                main { fixture.reader.setRenderWidth(1600) }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                main {
                    assertSame("Keep the usable page during an upgrade", original, fixture.reader.bitmap)
                    fixture.reader.setRenderWidth(1400)
                    assertSame("Superseding an upgrade must not blank the page", original, fixture.reader.bitmap)
                    assertFalse(fixture.reader.pageLoading)
                }
                release.countDown()
                fixture.await { it.bitmap != null && it.bitmap !== original && !it.pageLoading }
                main {
                    assertEquals(listOf(1024, 1600, 1400), fixture.session.widths.toList())
                    assertEquals(1400, requireNotNull(fixture.reader.pageImages[0]).renderWidth)
                    assertTrue("Stale unpublished work must be recycled", requireNotNull(stale).isRecycled)
                    assertFalse("Previously published pages remain valid for Compose", original.isRecycled)
                    assertFalse(requireNotNull(fixture.reader.bitmap).isRecycled)
                    assertNull(fixture.reader.error)
                }
            } finally { release.countDown() }
        }
        assertEquals(1, closes.get())
    }

    @Test fun hiddenMarginsRefineOnceAndReuseCropAboveDetectorSize() {
        val closes = AtomicInteger()
        Fixture(closes).use { fixture ->
            val produced = CopyOnWriteArrayList<Bitmap>()
            val ink = android.graphics.Paint().apply { color = android.graphics.Color.BLACK }
            fixture.session.aspect = { 2f }
            fixture.session.beforeRender = { _, width, _ -> fixture.session.renderSize = width to width * 2 }
            fixture.session.afterRender = { image ->
                image.eraseColor(android.graphics.Color.WHITE)
                android.graphics.Canvas(image).drawRect(image.width * .2f, image.height * .2f,
                    image.width * .8f, image.height * .8f, ink)
                produced += image
            }
            main {
                fixture.reader.setAppearance(ThemeSettings(app.smallthingz.alldoc.theme.ReadingMode.ORIGINAL))
                fixture.reader.setHideMargins(true)
                fixture.reader.setRenderWidth(720)
            }
            val task = requireNotNull(ReaderModel::class.java.getDeclaredField("rendering")
                .apply { isAccessible = true }.get(fixture.reader))
            val job = task.javaClass.getDeclaredField("job").apply { isAccessible = true }
            fixture.await { (job.get(task) as? Job)?.isActive != true && it.bitmap != null && !it.pageLoading }
            var firstWidth = 0
            var croppedWidth = 0f
            var croppedHeight = 0f
            lateinit var first: Bitmap
            main {
                val image = requireNotNull(fixture.reader.pageImages[0])
                val crop = requireNotNull(image.crop)
                first = image.bitmap
                firstWidth = image.renderWidth
                croppedWidth = crop.width(); croppedHeight = crop.height()
                assertEquals(2, fixture.session.widths.size)
                assertEquals(720, fixture.session.widths.first())
                assertEquals(firstWidth, fixture.session.widths.last())
                assertTrue("The displayed crop has real viewport pixels", image.bitmap.width * crop.width() >= 719f)
                assertTrue("Discovery raster is released before publication", produced.first().isRecycled)
                assertFalse(image.bitmap.isRecycled)
                fixture.reader.updateViewport(fixture.reader.viewport.copy(zoom = 2f))
                assertSame("Existing detail remains drawable during refinement", first, fixture.reader.bitmap)
            }
            fixture.await { (job.get(task) as? Job)?.isActive != true && it.bitmap != null && it.bitmap !== first }
            main {
                val image = requireNotNull(fixture.reader.pageImages[0])
                val crop = requireNotNull(image.crop)
                assertEquals("Only the new detail render is needed", 3, fixture.session.widths.size)
                assertTrue(image.bitmap.height > 4096)
                assertEquals(croppedWidth, crop.width(), 0f)
                assertEquals(croppedHeight, crop.height(), 0f)
                assertTrue("Zoomed crop keeps actual source detail", image.bitmap.width * crop.width() >= 1439f)
                assertEquals(image.bitmap.width, image.renderWidth)
                assertTrue(image.bitmap.allocationByteCount.toLong() <= 48L * 1024 * 1024)
                val completed = job.get(task)
                repeat(10) { fixture.reader.requestVisiblePages(0..0) }
                assertSame("A satisfied crop must not schedule another worker", completed, job.get(task))
                assertFalse((job.get(task) as? Job)?.isActive == true)
                assertEquals(3, fixture.session.widths.size)
                assertFalse(first.isRecycled)
                assertNull(fixture.reader.error)
            }
        }
        assertEquals(1, closes.get())
    }

    @Test fun animatedZoomDefersNativeDetailUntilTheLatestPoseSettles() {
        val closes = AtomicInteger()
        Fixture(closes).use { fixture ->
            fixture.session.beforeRender = { _, width, _ -> fixture.session.renderSize = width to width * 4 / 3 }
            main {
                fixture.reader.setAppearance(ThemeSettings(app.smallthingz.alldoc.theme.ReadingMode.ORIGINAL))
                fixture.reader.setRenderWidth(720)
            }
            fixture.await { it.bitmap?.width == 720 && !it.pageLoading }
            lateinit var original: Bitmap
            main {
                original = requireNotNull(fixture.reader.bitmap)
                fixture.reader.setZoomAnimation(true)
            }
            for (zoom in listOf(1.2f, 1.6f, 2f)) {
                main {
                    // Retargeting retains ownership instead of rendering an interrupted pose.
                    if (zoom == 1.6f) fixture.reader.setZoomAnimation(true)
                    fixture.reader.updateViewport(fixture.reader.viewport.copy(zoom = zoom))
                    fixture.reader.requestVisiblePages(0..0)
                    assertSame(original, fixture.reader.bitmap)
                }
                SystemClock.sleep(40)
                assertEquals("Animation frames must not schedule native tiers", listOf(720), fixture.session.widths.toList())
            }
            main { fixture.reader.setZoomAnimation(false) }
            fixture.await { it.bitmap?.width == 1440 }
            assertEquals(listOf(720, 1440), fixture.session.widths.toList())
            main {
                fixture.reader.setZoomAnimation(false)
                fixture.reader.updateViewport(fixture.reader.viewport.copy(zoom = 2.5f))
            }
            fixture.await { it.bitmap?.width == 1800 }
            assertEquals("Direct pinch refinement resumes after animation ownership ends", listOf(720, 1440, 1800), fixture.session.widths.toList())
        }
        assertEquals(1, closes.get())
    }

    @Test fun zoomRefinesInBoundedTiersAndKeepsPublishedPixelsDuringCancellation() {
        val closes = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        Fixture(closes).use { fixture ->
            try {
                fixture.session.beforeRender = { _, width, _ -> fixture.session.renderSize = width to width * 4 / 3 }
                main {
                    fixture.reader.setAppearance(ThemeSettings(app.smallthingz.alldoc.theme.ReadingMode.ORIGINAL))
                    fixture.reader.setRenderWidth(720)
                }
                fixture.await { it.bitmap?.width == 720 && !it.pageLoading }
                lateinit var original: Bitmap
                var stale: Bitmap? = null
                var upgradeSignal: CancellationSignal? = null
                val upgrades = AtomicInteger()
                fixture.session.beforeRender = { _, width, signal ->
                    fixture.session.renderSize = width to width * 4 / 3
                    if (width == 1080) upgradeSignal = signal
                }
                fixture.session.afterRender = { image ->
                    if (upgrades.getAndIncrement() == 0) {
                        stale = image; entered.countDown()
                        check(release.await(15, TimeUnit.SECONDS))
                    }
                }
                main {
                    original = requireNotNull(fixture.reader.bitmap)
                    fixture.reader.updateViewport(fixture.reader.viewport.copy(zoom = 1.2f))
                }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                main {
                    repeat(20) { fixture.reader.updateViewport(fixture.reader.viewport.copy(zoom = 1.21f + it * .009f)) }
                    assertEquals("Pinch deltas within a tier share one render", listOf(720, 1080), fixture.session.widths.toList())
                    assertFalse(requireNotNull(upgradeSignal).isCanceled)
                    assertSame(original, fixture.reader.bitmap)
                    fixture.reader.updateViewport(fixture.reader.viewport.copy(zoom = 2f))
                    assertTrue("A newer detail tier cancels obsolete work", requireNotNull(upgradeSignal).isCanceled)
                    assertSame("Refinement must not blank visible pixels", original, fixture.reader.bitmap)
                    assertFalse(fixture.reader.pageLoading)
                }
                release.countDown()
                fixture.await { it.bitmap?.width == 1440 && !it.pageLoading }
                lateinit var refined: Bitmap
                main {
                    refined = requireNotNull(fixture.reader.bitmap)
                    assertEquals(listOf(720, 1080, 1440), fixture.session.widths.toList())
                    assertTrue("Sharper output contains more real pixels", refined.width > original.width)
                    assertTrue(requireNotNull(stale).isRecycled)
                    assertFalse(original.isRecycled)
                    assertTrue(fixture.reader.pageImages.values.sumOf { it.bitmap.allocationByteCount.toLong() } <= 48L * 1024 * 1024)
                    fixture.session.beforeRender = { _, width, _ ->
                        fixture.session.renderSize = width to width * 4 / 3
                        if (width == 2160) throw OutOfMemoryError("Controlled detail allocation failure")
                    }
                    fixture.reader.updateViewport(fixture.reader.viewport.copy(zoom = 3f))
                }
                fixture.await { it.error == "Not enough memory." && 0 in it.failedPages }
                main {
                    assertSame("Failed refinement keeps the published page", refined, fixture.reader.bitmap)
                    assertFalse(refined.isRecycled)
                    assertFalse(fixture.reader.pageLoading)
                    fixture.reader.dismissError()
                    fixture.session.beforeRender = { _, width, _ -> fixture.session.renderSize = width to width * 4 / 3 }
                    fixture.reader.retryPage(0)
                }
                val task = requireNotNull(ReaderModel::class.java.getDeclaredField("rendering")
                    .apply { isAccessible = true }.get(fixture.reader))
                val job = task.javaClass.getDeclaredField("job").apply { isAccessible = true }
                fixture.await { it.bitmap?.width == 2160 && !it.pageLoading && (job.get(task) as? Job)?.isActive != true }
                main {
                    val recovered = requireNotNull(fixture.reader.bitmap)
                    val retainedPage = requireNotNull(fixture.reader.pageImages[0])
                    assertFalse(refined.isRecycled)
                    assertTrue(fixture.reader.failedPages.isEmpty())
                    assertTrue(fixture.reader.pageImages.values.sumOf { it.bitmap.allocationByteCount.toLong() } <= 48L * 1024 * 1024)
                    fixture.reader.updateViewport(fixture.reader.viewport.copy(zoom = .75f, rotation = 1))
                    assertEquals(.75f, fixture.reader.viewport.zoom, 0f)
                    assertSame("Zoom-out and rotation reuse sharper detail", recovered, fixture.reader.bitmap)
                    fixture.reader.setHorizontalPages(true)
                    assertTrue(fixture.reader.horizontalPages)
                    assertSame("Entering Present keeps published pixels", recovered, fixture.reader.bitmap)
                    assertSame(retainedPage, fixture.reader.pageImages[0])
                    assertFalse(fixture.reader.pageLoading)
                    assertNull("A satisfied Present page must not schedule another render", job.get(task))
                    fixture.reader.setHorizontalPages(false)
                    assertFalse(fixture.reader.horizontalPages)
                    assertSame("Returning to Normal keeps published pixels", recovered, fixture.reader.bitmap)
                    assertSame(retainedPage, fixture.reader.pageImages[0])
                    assertFalse(fixture.reader.pageLoading)
                    assertFalse(recovered.isRecycled)
                    assertNull("A satisfied Normal page must not schedule another render", job.get(task))
                    assertEquals(listOf(720, 1080, 1440, 2160, 2160), fixture.session.widths.toList())
                    assertNull(fixture.reader.error)
                }
            } finally { release.countDown() }
        }
        assertEquals(1, closes.get())
    }

    @Test fun rotatedLandscapePagesPromoteRasterDetailWithinExistingBounds() {
        val closes = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        Fixture(closes).use { fixture ->
            try {
                fixture.session.aspect = { 9f / 16f }
                fixture.session.beforeRender = { _, width, _ ->
                    check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
                    fixture.session.renderSize = width to width * 9 / 16
                }
                main {
                    fixture.reader.setAppearance(ThemeSettings(app.smallthingz.alldoc.theme.ReadingMode.ORIGINAL))
                    fixture.reader.setRenderWidth(720)
                }
                fixture.await { it.bitmap?.width == 720 && !it.pageLoading }
                lateinit var original: Bitmap
                var upgradeSignal: CancellationSignal? = null
                fixture.session.beforeRender = { _, width, signal ->
                    check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
                    fixture.session.renderSize = width to width * 9 / 16
                    if (width == 1280) upgradeSignal = signal
                }
                fixture.session.afterRender = { image ->
                    if (image.width == 1280) {
                        entered.countDown()
                        check(release.await(15, TimeUnit.SECONDS))
                    }
                }
                main {
                    original = requireNotNull(fixture.reader.bitmap)
                    assertEquals(405, original.height)
                    fixture.reader.updateViewport(fixture.reader.viewport.copy(rotation = 1))
                }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                main {
                    assertEquals(listOf(720, 1280), fixture.session.widths.toList())
                    assertSame("Rotation refinement retains the published page", original, fixture.reader.bitmap)
                    assertFalse(original.isRecycled)
                    assertFalse(fixture.reader.pageLoading)
                    fixture.reader.updateViewport(fixture.reader.viewport.copy(rotation = 3))
                    assertFalse("Opposite quarter turns need the same raster", requireNotNull(upgradeSignal).isCanceled)
                }
                release.countDown()
                fixture.await { it.bitmap?.width == 1280 && !it.pageLoading }
                main {
                    val refined = requireNotNull(fixture.reader.bitmap)
                    assertEquals("Source height must cover the rotated viewport", 720, refined.height)
                    assertFalse(original.isRecycled)
                    assertTrue(fixture.reader.pageImages.values.sumOf { it.bitmap.allocationByteCount.toLong() } <= 48L * 1024 * 1024)
                    fixture.reader.updateViewport(fixture.reader.viewport.copy(zoom = .75f, rotation = 0))
                    assertEquals(.75f, fixture.reader.viewport.zoom, 0f)
                    assertSame("Zoom-out reuses the sharper raster", refined, fixture.reader.bitmap)
                    assertEquals(listOf(720, 1280), fixture.session.widths.toList())
                    assertNull(fixture.reader.error)
                }
            } finally { release.countDown() }
        }
        assertEquals(1, closes.get())

        val narrowCloses = AtomicInteger()
        Fixture(narrowCloses, pages = 2).use { fixture ->
            fixture.session.aspect = { if (it == 0) 1f / 16f else 9f / 16f }
            fixture.session.beforeRender = { index, width, _ ->
                fixture.session.renderSize = width to width * (if (index == 0) 1 else 9) / 16
            }
            main {
                fixture.reader.setAppearance(ThemeSettings(app.smallthingz.alldoc.theme.ReadingMode.ORIGINAL))
                fixture.reader.requestVisiblePages(0..1)
                fixture.reader.setRenderWidth(720)
            }
            fixture.await { it.pageImages[0]?.bitmap?.width == 720 && it.pageImages[1]?.bitmap?.width == 720 }
            main { fixture.reader.updateViewport(fixture.reader.viewport.copy(rotation = 1)) }
            fixture.await { it.pageImages[0]?.bitmap?.width == 4096 && it.pageImages[1]?.bitmap?.width == 1280 }
            main { fixture.reader.updateViewport(fixture.reader.viewport.copy(zoom = 1.2f)) }
            fixture.await { it.pageImages[1]?.bitmap?.width == 1920 && !it.pageLoading }
            main {
                val capped = requireNotNull(fixture.reader.bitmap)
                assertEquals(4096, capped.width)
                assertEquals(256, capped.height)
                assertEquals("A capped current page must not suppress another visible page's next tier",
                    listOf(720, 1280, 1920), fixture.session.pages.zip(fixture.session.widths).filter { it.first == 1 }.map { it.second })
                assertTrue(fixture.reader.pageImages.values.sumOf { it.bitmap.allocationByteCount.toLong() } <= 48L * 1024 * 1024)
                fixture.reader.requestVisiblePages(0..0)
                fixture.reader.updateViewport(fixture.reader.viewport.copy(zoom = 8f))
                assertSame("Zoom at the width ceiling reuses existing pixels", capped, fixture.reader.bitmap)
                assertEquals(listOf(720, 4096), fixture.session.pages.zip(fixture.session.widths).filter { it.first == 0 }.map { it.second })
                assertNull(fixture.reader.error)
            }
        }
        assertEquals(1, narrowCloses.get())
    }

    @Test fun retainedDetailFailuresCanRetryAfterDismissingTheErrorInBothModes() {
        for (horizontal in listOf(false, true)) {
            val closes = AtomicInteger()
            Fixture(closes).use { fixture ->
                val attempts = AtomicInteger()
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1)
                var baseWidth = Int.MAX_VALUE
                fixture.session.beforeRender = { _, width, _ ->
                    fixture.session.renderSize = width to width * 4 / 3
                    if (width > baseWidth) {
                        if (attempts.incrementAndGet() <= 2) throw java.io.IOException("Controlled detail failure")
                        entered.countDown()
                        check(release.await(15, TimeUnit.SECONDS)) { "Retry was not released" }
                    }
                }
                fixture.session.afterRender = { it.eraseColor(android.graphics.Color.BLUE) }
                main { fixture.reader.choosePageView(horizontal) }
                try {
                    ActivityScenario.launch(ReaderTestActivity::class.java).use { scenario ->
                        scenario.onActivity { activity -> activity.setContent {
                            ReaderScreen(fixture.reader, onOpen = {}, onExport = { _, _ -> })
                        } }
                        fixture.await { it.bitmap != null && !it.pageLoading }
                        lateinit var original: Bitmap
                        main {
                            original = requireNotNull(fixture.reader.bitmap)
                            baseWidth = original.width
                            fixture.reader.updateViewport(fixture.reader.viewport.copy(zoom = 2f))
                        }
                        fixture.await { it.error == "Controlled detail failure" && 0 in it.failedPages }
                        main { assertSame(original, fixture.reader.bitmap); assertFalse(original.isRecycled) }
                        clickVisibleControl("Close")
                        fixture.await { it.error == null && 0 in it.failedPages }
                        clickVisibleControl("Retry page 1") // The real viewport action, not a model call.
                        fixture.await { attempts.get() == 2 && it.error == "Controlled detail failure" && 0 in it.failedPages }
                        clickVisibleControl("Close")
                        fixture.await { it.error == null && 0 in it.failedPages }
                        main {
                            assertSame("Dismissing the sheet keeps readable pixels", original, fixture.reader.bitmap)
                            assertEquals(horizontal, fixture.reader.horizontalPages)
                        }
                        clickVisibleControl("Retry page 1") // The viewport action must remain reachable at zoom 2.
                        assertTrue("The actual button starts recovery", entered.await(5, TimeUnit.SECONDS))
                        main {
                            assertSame("Retry must not blank the retained page", original, fixture.reader.bitmap)
                            assertFalse(original.isRecycled)
                            assertFalse(fixture.reader.pageLoading)
                        }
                        release.countDown()
                        fixture.await { it.bitmap !== original && it.bitmap != null && it.failedPages.isEmpty() && it.error == null }
                        main {
                            assertTrue(requireNotNull(fixture.reader.bitmap).width > original.width)
                            assertEquals(android.graphics.Color.BLUE, requireNotNull(fixture.reader.bitmap).getPixel(0, 0))
                            assertFalse(original.isRecycled)
                            assertEquals(horizontal, fixture.reader.horizontalPages)
                            assertEquals(2f, fixture.reader.viewport.zoom, 0f)
                        }
                        assertEquals(3, attempts.get())
                    }
                } finally { release.countDown() }
            }
            assertEquals(1, closes.get())
        }
    }

    @Suppress("DEPRECATION")
    private fun clickVisibleControl(label: String) {
        fun click(node: AccessibilityNodeInfo, depth: Int = 0): Boolean {
            if (depth >= 12 || !node.isEnabled || !node.isVisibleToUser) return false
            if (node.isClickable) {
                val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
                return !bounds.isEmpty && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            val parent = node.parent ?: return false
            return try { click(parent, depth + 1) } finally { parent.recycle() }
        }
        fun visit(node: AccessibilityNodeInfo, depth: Int): Boolean {
            if (depth > 24 || !node.refresh()) return false
            if (node.isVisibleToUser && (node.text?.toString() == label || node.contentDescription?.toString() == label) && click(node)) return true
            for (index in 0 until minOf(node.childCount, 96)) {
                val child = node.getChild(index) ?: continue
                try { if (visit(child, depth + 1)) return true } finally { child.recycle() }
            }
            return false
        }
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            val root = instrumentation.uiAutomation.rootInActiveWindow
            if (root != null) try {
                if (root.packageName?.toString() == instrumentation.targetContext.packageName && visit(root, 0)) return
            } finally { root.recycle() }
            SystemClock.sleep(25)
        }
        fail("Visible reader control was unavailable: $label")
    }

    @Test fun cancelledTextWorkersCannotSettleAReenteredSameDocument() {
        for (exit in listOf("home", "background", "pages", "normal-view", "presentation-view")) {
            val closes = AtomicInteger()
            val oldEntered = CountDownLatch(1)
            val oldRelease = CountDownLatch(1)
            val nextEntered = CountDownLatch(1)
            val nextRelease = CountDownLatch(1)
            Fixture(closes).use { fixture ->
                try {
                    val attempts = AtomicInteger()
                    fixture.session.before = { operation -> if (operation == "text") {
                        if (attempts.getAndIncrement() == 0) {
                            oldEntered.countDown(); check(oldRelease.await(15, TimeUnit.SECONDS))
                            throw java.io.IOException("Late cancelled extraction must not become a reader error")
                        } else {
                            nextEntered.countDown(); check(nextRelease.await(15, TimeUnit.SECONDS))
                        }
                    } }
                    lateinit var oldJob: Job
                    main {
                        fixture.reader.setTextMode(true)
                        val field = ReaderModel::class.java.getDeclaredField("extracting").apply { isAccessible = true }
                        val task = requireNotNull(field.get(fixture.reader))
                        oldJob = task.javaClass.getDeclaredField("job").apply { isAccessible = true }.get(task) as Job
                    }
                    assertTrue(oldEntered.await(5, TimeUnit.SECONDS))
                    main {
                        when (exit) {
                            "home" -> fixture.reader.showReader(false)
                            "background" -> fixture.reader.setAppVisible(false)
                            "normal-view" -> fixture.reader.choosePageView(false)
                            "presentation-view" -> fixture.reader.choosePageView(true)
                            else -> fixture.reader.setTextMode(false)
                        }
                        assertTrue("Leaving text demand cancels its job", oldJob.isCancelled)
                        assertFalse(fixture.reader.textLoading)
                        assertNull(fixture.reader.text)
                        assertNull(fixture.reader.error)
                        if (exit == "home" || exit == "background") {
                            fixture.reader.loadText()
                            assertFalse("Hidden/background explicit extraction must remain idle", fixture.reader.textLoading)
                        }
                    }
                    assertEquals("Hidden/background extraction must not restart", 1, attempts.get())
                    main {
                        when (exit) {
                            "home" -> fixture.reader.showReader(true)
                            "background" -> fixture.reader.setAppVisible(true)
                            else -> fixture.reader.setTextMode(true)
                        }
                        assertTrue("Reentering the same document starts replacement extraction", fixture.reader.textLoading)
                    }
                    oldRelease.countDown()
                    runBlocking { withTimeout(15_000) { oldJob.join() } }
                    assertTrue(nextEntered.await(5, TimeUnit.SECONDS))
                    main {
                        assertTrue("Old finally must not settle the replacement's loading state", fixture.reader.textLoading)
                        assertNull(fixture.reader.text)
                        assertNull(fixture.reader.error)
                        assertEquals(fixture.hash, fixture.reader.documentHash)
                    }
                    assertEquals("An outstanding session remains leased", 0, closes.get())
                    nextRelease.countDown()
                    fixture.await { it.text == "Recovered text" && !it.textLoading }
                    main { assertNull(fixture.reader.error); assertTrue(fixture.reader.readerVisible) }
                    assertEquals(2, attempts.get())
                } finally { oldRelease.countDown(); nextRelease.countDown() }
            }
            assertEquals("All extraction leases eventually release", 1, closes.get())
        }
    }

    @Test fun lateMetadataFailuresStayInlineAfterClosingOrLeavingTheReader() {
        for (kind in listOf("notes", "contents"))
            for (exit in listOf("visible", "pane", "home", "background")) for (allocation in listOf(false, true)) {
                val closes = AtomicInteger()
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1)
                Fixture(closes).use { fixture ->
                    try {
                        val attempts = AtomicInteger()
                        fixture.session.before = { operation ->
                            if (operation == kind && attempts.getAndIncrement() == 0) {
                                entered.countDown()
                                check(release.await(15, TimeUnit.SECONDS))
                                if (allocation) throw OutOfMemoryError("Controlled $kind allocation failure")
                                throw java.io.IOException("Controlled $kind failure")
                            }
                        }
                        fun start() = main {
                            if (kind == "notes") {
                                fixture.reader.setNotesVisible(false)
                                fixture.reader.setNotesVisible(true)
                            } else fixture.reader.loadContents()
                        }
                        main { if (kind == "notes") assertTrue(requireNotNull(fixture.reader.info).supportsNotes) }
                        start()
                        assertTrue(entered.await(5, TimeUnit.SECONDS))
                        lateinit var pending: Job
                        main {
                            val field = ReaderModel::class.java.getDeclaredField(if (kind == "notes") "annotating" else "outlining")
                                .apply { isAccessible = true }
                            val task = requireNotNull(field.get(fixture.reader))
                            pending = task.javaClass.getDeclaredField("job").apply { isAccessible = true }.get(task) as Job
                            when (exit) {
                                "pane" -> if (kind == "notes") fixture.reader.setNotesVisible(false)
                                "home" -> fixture.reader.showReader(false)
                                "background" -> fixture.reader.setAppVisible(false)
                            }
                            // Contents sheet visibility has no model flag; closing it leaves this worker running.
                            assertNull(fixture.reader.error)
                        }
                        val paused = exit == "home" || exit == "background"
                        if (paused) assertTrue("Leaving the reader must cancel optional metadata work", pending.isCancelled)
                        release.countDown()
                        runBlocking { withTimeout(15_000) { pending.join() } }
                        val warningText = if (allocation) "Not enough memory to load $kind." else "Controlled $kind failure"
                        main {
                            if (kind == "notes") {
                                assertFalse(fixture.reader.notesLoading)
                                if (paused) assertTrue("Cancelled metadata must not publish a late warning", fixture.reader.notes.warnings.isEmpty())
                                else assertTrue(fixture.reader.notes.warnings.any { it.contains(warningText) })
                            } else {
                                assertFalse(fixture.reader.contentsLoading)
                                if (paused) assertNull("Cancelled metadata must not publish a late error", fixture.reader.contentsError)
                                else assertTrue(fixture.reader.contentsError?.contains(warningText) == true)
                            }
                        }
                        main {
                            assertNull("Late optional metadata errors must not open a modal on the new destination", fixture.reader.error)
                            assertEquals(fixture.hash, fixture.reader.documentHash)
                            assertEquals(exit != "home", fixture.reader.readerVisible)
                            if (kind == "notes" && exit == "pane") assertFalse(fixture.reader.notesVisible)
                        }
                        assertEquals(1, attempts.get())
                        main {
                            if (exit == "home") fixture.reader.showReader(true)
                            if (exit == "background") fixture.reader.setAppVisible(true)
                        }
                        start()
                        fixture.await {
                            if (kind == "notes") !it.notesLoading && it.notes.entries.any { note -> note.text == "Recovered notes" }
                            else !it.contentsLoading && it.outline.any { entry -> entry.title == "Chapter" }
                        }
                        main {
                            if (kind == "notes") assertTrue(fixture.reader.notes.warnings.isEmpty())
                            else assertNull(fixture.reader.contentsError)
                            assertNull(fixture.reader.error)
                            assertTrue(fixture.reader.readerVisible)
                        }
                        assertEquals(2, attempts.get())
                    } finally { release.countDown() }
                }
                assertEquals("Metadata workers release their session lease", 1, closes.get())
            }
    }

    @Test fun leavingAnActivePageCancelsOnlyObsoleteWorkAndKeepsTheSessionUsable() {
        val closes = AtomicInteger()
        val entered = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val release = CountDownLatch(1)
        Fixture(closes, pages = 8).use { fixture ->
            try {
                var active: CancellationSignal? = null
                fixture.session.beforeRender = { index, _, signal ->
                    if (index == 0) {
                        active = signal
                        signal.setOnCancelListener { cancelled.countDown(); release.countDown() }
                        entered.countDown()
                        check(release.await(15, TimeUnit.SECONDS))
                    }
                }
                main { fixture.reader.setRenderWidth(720) }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                main {
                    repeat(20) { fixture.reader.requestVisiblePages(0..0) }
                    assertFalse("Unchanged demand must not restart its active page", requireNotNull(active).isCanceled)
                    assertEquals(listOf(0), fixture.session.pages.toList())
                    fixture.reader.goToPage(6)
                }
                assertTrue("Leaving the window cancels obsolete native work", cancelled.await(5, TimeUnit.SECONDS))
                fixture.await { it.page == 6 && it.bitmap != null && !it.pageLoading }
                main {
                    assertTrue(requireNotNull(active).isCanceled)
                    assertEquals(fixture.hash, fixture.reader.documentHash)
                    assertFalse("Cancellation is not a page failure", 0 in fixture.reader.failedPages)
                    assertNull(fixture.reader.error)
                    assertFalse(requireNotNull(fixture.reader.bitmap).isRecycled)
                }
                assertEquals("No obsolete neighboring page should start", 6, fixture.session.pages[1])
                assertEquals("The active document remains leased", 0, closes.get())
            } finally { release.countDown() }
        }
        assertEquals("The session closes once after all cancelled work drains", 1, closes.get())
    }

    @Test fun visiblePagesPrecedeTheRecentsCoverAndNewDemandCancelsThatCover() {
        val closes = AtomicInteger()
        val entered = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val release = CountDownLatch(1)
        val previews = AtomicInteger()
        Fixture(closes, pages = 8, savePreview = true).use { fixture ->
            try {
                fixture.session.beforeRender = { _, width, signal ->
                    if (width <= DocumentPreviews.WIDTH && previews.getAndIncrement() == 0) {
                        signal.setOnCancelListener { cancelled.countDown(); release.countDown() }
                        entered.countDown()
                        check(release.await(15, TimeUnit.SECONDS))
                    }
                }
                main {
                    fixture.reader.requestVisiblePages(0..2)
                    fixture.reader.setRenderWidth(720)
                }
                assertTrue("The optional cover eventually starts", entered.await(5, TimeUnit.SECONDS))
                main {
                    assertTrue("Every visible page is published before optional cover work",
                        fixture.reader.pageImages.keys.containsAll(listOf(0, 1, 2)))
                    assertEquals(listOf(0, 1, 2), fixture.session.pages.take(3))
                    assertTrue(fixture.session.widths.take(3).all { it == 720 })
                    assertEquals("The cover precedes adjacent prefetch", listOf(0, 1, 2, 0), fixture.session.pages.toList())
                    assertTrue(fixture.session.widths.last() <= DocumentPreviews.WIDTH)
                    assertNull(fixture.reader.error)
                    fixture.reader.goToPage(6)
                }
                assertTrue("New visible demand cancels the optional native cover", cancelled.await(5, TimeUnit.SECONDS))
                fixture.await { it.page == 6 && it.bitmap != null && !it.pageLoading && previews.get() >= 2 }
                main {
                    assertNull(fixture.reader.error)
                    assertTrue(fixture.reader.failedPages.isEmpty())
                    assertFalse(requireNotNull(fixture.reader.bitmap).isRecycled)
                    assertEquals(fixture.hash, fixture.reader.documentHash)
                }
                assertEquals(0, closes.get())
            } finally { release.countDown() }
        }
        assertEquals(1, closes.get())

        val prefetchEntered = CountDownLatch(1)
        val prefetchCancelled = CountDownLatch(1)
        val prefetchRelease = CountDownLatch(1)
        val prefetchAttempts = AtomicInteger()
        Fixture(closes, pages = 8).use { fixture ->
            try {
                fixture.session.beforeRender = { index, _, signal ->
                    if (index == 1 && prefetchAttempts.getAndIncrement() == 0) {
                        signal.setOnCancelListener { prefetchCancelled.countDown(); prefetchRelease.countDown() }
                        prefetchEntered.countDown()
                        check(prefetchRelease.await(15, TimeUnit.SECONDS))
                    }
                }
                main { fixture.reader.setRenderWidth(720) }
                assertTrue("Adjacent prefetch starts after visible page 0", prefetchEntered.await(5, TimeUnit.SECONDS))
                main {
                    assertEquals(listOf(0, 1), fixture.session.pages.toList())
                    assertTrue(0 in fixture.reader.pageImages)
                    // Page 1 remains adjacent to page 2, but must yield to its newly visible pixels.
                    fixture.reader.goToPage(2)
                }
                assertTrue("Visible demand preempts still-adjacent prefetch", prefetchCancelled.await(5, TimeUnit.SECONDS))
                fixture.await { it.page == 2 && it.bitmap != null && !it.pageLoading }
                main {
                    assertEquals(listOf(0, 1, 2), fixture.session.pages.take(3))
                    assertTrue(fixture.reader.failedPages.isEmpty())
                    assertNull(fixture.reader.error)
                    assertFalse(requireNotNull(fixture.reader.bitmap).isRecycled)
                }
                assertEquals("The active session remains leased", 1, closes.get())
            } finally { prefetchRelease.countDown() }
        }
        assertEquals("Each session closes once after its work drains", 2, closes.get())
    }

    @Test fun anOptionalCoverFailureSettlesOnceWithoutRestartingVisibleWork() {
        val closes = AtomicInteger()
        val previews = AtomicInteger()
        Fixture(closes, pages = 2, savePreview = true).use { fixture ->
            fixture.session.beforeRender = { _, width, _ ->
                if (width <= DocumentPreviews.WIDTH) {
                    previews.incrementAndGet()
                    throw java.io.IOException("Controlled optional cover failure")
                }
            }
            main { fixture.reader.setRenderWidth(720) }
            val field = ReaderModel::class.java.getDeclaredField("rendering").apply { isAccessible = true }
            val task = requireNotNull(field.get(fixture.reader))
            val job = task.javaClass.getDeclaredField("job").apply { isAccessible = true }
            fixture.await { (job.get(task) as? Job)?.isActive != true && it.bitmap != null && !it.pageLoading }
            val rendered = fixture.session.pages.toList()
            main {
                assertEquals("One failed optional attempt, then adjacent prefetch", listOf(0, 0, 1), rendered)
                repeat(20) { fixture.reader.requestVisiblePages(0..0) }
                assertTrue("Stable demand stays settled", (job.get(task) as? Job)?.isActive != true)
                assertNull(fixture.reader.error)
                assertTrue(fixture.reader.failedPages.isEmpty())
                assertFalse(requireNotNull(fixture.reader.bitmap).isRecycled)
            }
            assertEquals(1, previews.get())
            assertEquals(rendered, fixture.session.pages.toList())
            assertEquals(0, closes.get())
        }
        assertEquals(1, closes.get())
    }

    @Test fun explicitExportPreemptsOptionalRenderingAndResumesAfterSuccessOrCancellation() {
        for (cover in listOf(true, false)) {
            val closes = AtomicInteger()
            val workEntered = CountDownLatch(1)
            val workCancelled = CountDownLatch(1)
            val releaseWork = CountDownLatch(1)
            val exportEntered = CountDownLatch(1)
            val releaseExport = CountDownLatch(1)
            val attempts = AtomicInteger()
            val sessionLock = Any()
            Fixture(closes, pages = 2, savePreview = cover).use { fixture ->
                try {
                    // Model the Office session monitor without changing other fake-session behavior.
                    fixture.session.beforeRender = { index, width, signal ->
                        val optional = if (cover) width <= DocumentPreviews.WIDTH else index == 1
                        if (optional && attempts.getAndIncrement() == 0) synchronized(sessionLock) {
                            signal.setOnCancelListener { workCancelled.countDown(); releaseWork.countDown() }
                            workEntered.countDown()
                            check(releaseWork.await(15, TimeUnit.SECONDS))
                            signal.throwIfCanceled()
                        }
                    }
                    fixture.session.before = { operation -> if (operation == "text") synchronized(sessionLock) {
                        exportEntered.countDown()
                        check(releaseExport.await(15, TimeUnit.SECONDS))
                    } }
                    main {
                        fixture.reader.requestVisiblePages(0..0)
                        fixture.reader.setRenderWidth(720)
                    }
                    assertTrue("Optional work must own the session first", workEntered.await(5, TimeUnit.SECONDS))
                    val task = requireNotNull(ReaderModel::class.java.getDeclaredField("rendering")
                        .apply { isAccessible = true }.get(fixture.reader))
                    val job = task.javaClass.getDeclaredField("job").apply { isAccessible = true }
                    lateinit var previous: Job
                    lateinit var published: Bitmap
                    val destination = File(fixture.context.root, "priority.txt")
                    main {
                        previous = job.get(task) as Job
                        published = requireNotNull(fixture.reader.bitmap)
                        assertEquals(listOf(0, if (cover) 0 else 1), fixture.session.pages.toList())
                        assertNotNull(fixture.reader.prepareExport(ReaderModel.ExportKind.TEXT))
                        fixture.reader.export(Uri.fromFile(destination))
                        assertTrue(fixture.reader.exporting)
                        assertSame("Export must not blank published pixels", published, fixture.reader.bitmap)
                    }
                    assertTrue("Accepted export cancels optional rendering", workCancelled.await(5, TimeUnit.SECONDS))
                    assertTrue("Export acquires the released session", exportEntered.await(5, TimeUnit.SECONDS))
                    // Join only on the instrumentation thread, never the UI thread.
                    runBlocking { withTimeout(15_000) { previous.join() } }
                    main {
                        repeat(20) { fixture.reader.requestVisiblePages(0..0) }
                        assertTrue(fixture.reader.exporting)
                        assertFalse("Viewport notifications cannot restart work during export", (job.get(task) as? Job)?.isActive == true)
                        assertEquals(1, attempts.get())
                        assertSame(published, fixture.reader.bitmap)
                        assertFalse(published.isRecycled)
                        assertEquals("Export and reader still own the session", 0, closes.get())
                        if (!cover) fixture.reader.cancelExport()
                    }
                    releaseExport.countDown()
                    fixture.await { !it.exporting && attempts.get() >= 2 && 1 in it.pageImages }
                    main {
                        assertSame("Unchanged visible pixels survive export and resumed prefetch", published, fixture.reader.bitmap)
                        assertFalse(published.isRecycled)
                        assertNull(fixture.reader.error)
                        assertTrue(fixture.reader.failedPages.isEmpty())
                        assertEquals(fixture.hash, fixture.reader.documentHash)
                        assertEquals(if (cover) "Export saved." else "Export cancelled; the destination may be incomplete.", fixture.reader.notice)
                    }
                    if (cover) assertEquals("Recovered text", destination.readText())
                    assertEquals(0, closes.get())
                } finally { releaseWork.countDown(); releaseExport.countDown() }
            }
            assertEquals("All reader/export leases close exactly once", 1, closes.get())
        }
        for (cancelExport in listOf(false, true)) {
            val closes = AtomicInteger()
            val coverEntered = CountDownLatch(1)
            val coverCancelled = CountDownLatch(1)
            val releaseCover = CountDownLatch(1)
            val exportEntered = CountDownLatch(1)
            val releaseExport = CountDownLatch(1)
            val attempts = AtomicInteger()
            val sessionLock = Any()
            val flow = MarkdownDocument.parse("# Recovered text")
            Fixture(closes).use { fixture ->
                try {
                    fixture.session.rich = flow
                    fixture.session.beforeRender = { _, _, signal -> synchronized(sessionLock) {
                        attempts.incrementAndGet()
                        signal.setOnCancelListener { coverCancelled.countDown(); releaseCover.countDown() }
                        coverEntered.countDown()
                        check(releaseCover.await(15, TimeUnit.SECONDS))
                        signal.throwIfCanceled()
                    } }
                    fixture.session.before = { operation -> if (operation == "text") synchronized(sessionLock) {
                        exportEntered.countDown()
                        check(releaseExport.await(15, TimeUnit.SECONDS))
                    } }
                    main { fixture.reader.loadText() }
                    assertTrue("The published Markdown flow starts its optional cover", coverEntered.await(5, TimeUnit.SECONDS))
                    val task = requireNotNull(ReaderModel::class.java.getDeclaredField("extracting")
                        .apply { isAccessible = true }.get(fixture.reader))
                    val job = task.javaClass.getDeclaredField("job").apply { isAccessible = true }
                    lateinit var previous: Job
                    val destination = File(fixture.context.root, "markdown-priority.txt")
                    main {
                        previous = job.get(task) as Job
                        assertSame(flow, fixture.reader.markdown)
                        assertSame(flow.plainText, fixture.reader.text)
                        assertFalse("Mandatory text is ready before optional cover cancellation", fixture.reader.textLoading)
                        assertNotNull(fixture.reader.prepareExport(ReaderModel.ExportKind.TEXT))
                        fixture.reader.export(Uri.fromFile(destination))
                    }
                    assertTrue("Export cancels the extracting task's optional cover", coverCancelled.await(5, TimeUnit.SECONDS))
                    assertTrue("Export acquires the Markdown session without waiting for its cover", exportEntered.await(5, TimeUnit.SECONDS))
                    runBlocking { withTimeout(15_000) { previous.join() } }
                    main {
                        repeat(20) { fixture.reader.loadText() }
                        assertTrue(fixture.reader.exporting)
                        assertFalse((job.get(task) as? Job)?.isActive == true)
                        assertSame(flow, fixture.reader.markdown)
                        assertSame(flow.plainText, fixture.reader.text)
                        assertFalse(fixture.reader.textLoading)
                        assertEquals(1, attempts.get())
                        assertEquals(0, closes.get())
                        if (cancelExport) fixture.reader.cancelExport()
                    }
                    releaseExport.countDown()
                    fixture.await { !it.exporting }
                    main {
                        assertSame(flow, fixture.reader.markdown)
                        assertSame(flow.plainText, fixture.reader.text)
                        assertEquals(fixture.hash, fixture.reader.documentHash)
                        assertNull(fixture.reader.error)
                        assertTrue(fixture.reader.failedPages.isEmpty())
                        assertEquals(if (cancelExport) "Export cancelled; the destination may be incomplete." else "Export saved.", fixture.reader.notice)
                    }
                    assertEquals("Published flow must not restart its cancelled optional cover", 1, attempts.get())
                    if (!cancelExport) assertEquals(flow.plainText, destination.readText())
                    assertEquals(0, closes.get())
                } finally { releaseCover.countDown(); releaseExport.countDown() }
            }
            assertEquals("Markdown reader/export leases close exactly once", 1, closes.get())
        }

    }

    private class FakeSession(override val info: DocumentInfo, private val closes: AtomicInteger) : DocumentSession {
        @Volatile var before: (String) -> Unit = {}
        @Volatile var beforeRender: (Int, Int, CancellationSignal) -> Unit = { _, _, _ -> }
        @Volatile var afterRender: (Bitmap) -> Unit = {}
        @Volatile var afterRegion: (Int, PageRegion, Bitmap, CancellationSignal) -> Unit = { _, _, _, _ -> }
        @Volatile var regionAppearance: (ThemeSettings) -> Unit = {}
        @Volatile var regionContext: (Int, RegionThemeContext?) -> Unit = { _, _ -> }
        @Volatile var trackThemeContexts = false
        @Volatile var cachedThemeContext: Pair<Int, RegionThemeContext>? = null
        val themedContexts = CopyOnWriteArrayList<Pair<Int, RegionThemeContext>>()
        @Volatile var aspect: ((Int) -> Float)? = null
        var renderSize = 24 to 32
        var rich: MarkdownDocument? = null
        val pages = CopyOnWriteArrayList<Int>()
        val widths = CopyOnWriteArrayList<Int>()
        val regionCalls = CopyOnWriteArrayList<Pair<Int, PageRegion>>()
        override fun pageAspect(index: Int, signal: CancellationSignal): Float {
            signal.throwIfCanceled()
            return aspect?.invoke(index) ?: super<DocumentSession>.pageAspect(index, signal)
        }
        override fun renderPage(index: Int, width: Int, settings: ThemeSettings, signal: CancellationSignal): Bitmap {
            pages += index
            widths += width
            before("page")
            beforeRender(index, width, signal)
            signal.throwIfCanceled()
            val image = Bitmap.createBitmap(renderSize.first, renderSize.second, Bitmap.Config.ARGB_8888)
            try { afterRender(image); return image } catch (failure: Throwable) { image.recycle(); throw failure }
        }
        override fun themePage(index: Int, bitmap: Bitmap, settings: ThemeSettings, signal: CancellationSignal): Bitmap {
            if (!trackThemeContexts) return super<DocumentSession>.themePage(index, bitmap, settings, signal)
            val context = BitmapTheme.prepareContext(bitmap, settings, signal)
            cachedThemeContext = index to context
            themedContexts += index to context
            return bitmap
        }
        override fun pageThemeContext(index: Int, settings: ThemeSettings, signal: CancellationSignal): RegionThemeContext? {
            signal.throwIfCanceled()
            return cachedThemeContext?.takeIf { it.first == index && it.second.settings == settings }?.second
        }
        override fun renderRegionWithContext(index: Int, region: PageRegion, settings: ThemeSettings,
            signal: CancellationSignal, context: RegionThemeContext?): Bitmap {
            regionContext(index, context)
            return renderRegion(index, region, settings, signal)
        }
        override fun renderRegion(index: Int, region: PageRegion, settings: ThemeSettings, signal: CancellationSignal): Bitmap {
            regionCalls += index to region
            regionAppearance(settings)
            before("region")
            signal.throwIfCanceled()
            val image = Bitmap.createBitmap(region.width, region.height, Bitmap.Config.ARGB_8888)
            try { afterRegion(index, region, image, signal); return image }
            catch (failure: Throwable) { image.recycle(); throw failure }
        }
        override fun markdown(signal: CancellationSignal): MarkdownDocument? { signal.throwIfCanceled(); return rich }
        override fun text(signal: CancellationSignal): String { before("text"); signal.throwIfCanceled(); return "Recovered text" }
        override fun notes(signal: CancellationSignal): DocumentNotes {
            before("notes"); signal.throwIfCanceled()
            return DocumentNotes(listOf(DocumentNote("Recovered notes")))
        }
        override fun json(signal: CancellationSignal) = "{}"
        override fun outline(signal: CancellationSignal): List<DocumentOutlineEntry> {
            before("contents"); signal.throwIfCanceled(); return listOf(DocumentOutlineEntry("Chapter", 0))
        }
        override fun close() { closes.incrementAndGet() }
    }

    private inner class Fixture(closes: AtomicInteger, pages: Int = 1, savePreview: Boolean = false) : Closeable {
        val context = ScopedContext(instrumentation.targetContext)
        private val store = DocumentStore(context)
        private val sources = DocumentSources(context)
        private val application = object : Application() { init { attachBaseContext(context) } }
        private val owner = ViewModelStore()
        lateinit var reader: ReaderModel
        lateinit var session: FakeSession
        lateinit var hash: String
        init {
            val file = File(context.root, "memory.pdf").also { writeReaderPdf(it, pages = pages) }
            main { reader = ReaderModel(application, store, sources); owner.put("reader", reader); reader.open(Uri.fromFile(file)) }
            await { it.info != null && !it.loading }
            main {
                hash = requireNotNull(reader.documentHash)
                val info = requireNotNull(reader.info)
                assertTrue(info.textAvailable)
                session = FakeSession(info, closes)
                // Allocation stressors isolate viewport work; scheduling tests also enable the small cover.
                // Native cover sizing and ownership are covered by DocumentPreviewsTest and LibraryPreviewsTest.
                ReaderModel::class.java.getDeclaredField("previewSaved").apply { isAccessible = true }
                    .setBoolean(reader, !savePreview)
                // Replace only the worker session; exercise the real model's normal public commands.
                val field = ReaderModel::class.java.getDeclaredField("current").apply { isAccessible = true }
                val previous = requireNotNull(field.get(reader))
                previous.javaClass.getDeclaredMethod("retire").apply { isAccessible = true }.invoke(previous)
                val constructor = previous.javaClass.getDeclaredConstructor(DocumentSession::class.java).apply { isAccessible = true }
                field.set(reader, constructor.newInstance(session))
            }
        }
        fun await(ready: (ReaderModel) -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 15_000
            while (SystemClock.uptimeMillis() < deadline) {
                var result = false
                main { result = ready(reader) }
                if (result) return
                SystemClock.sleep(25)
            }
            fail("Allocation recovery did not settle")
        }
        override fun close() {
            var job: Job? = null
            main { job = reader.viewModelScope.coroutineContext[Job]; owner.clear() }
            runBlocking { withTimeout(15_000) { job?.join() } }
            for (name in listOf("StateWorker", "CloseWorker")) {
                val field = ReaderModel::class.java.getDeclaredField(name).apply { isAccessible = true }
                (field.get(null) as ExecutorService).submit {}.get(15, TimeUnit.SECONDS)
            }
            store.close(); sources.close(); context.clear()
        }
    }

    private class ScopedContext(base: Context) : ContextWrapper(base) {
        val root = File(base.cacheDir, "reader-allocation-${UUID.randomUUID()}").apply { mkdirs() }
        override fun getApplicationContext(): Context = this
        override fun getDatabasePath(name: String): File = File(root, name)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
            SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, handler: DatabaseErrorHandler?): SQLiteDatabase =
            SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory, handler)
        override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
        override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences(root.name + "-" + name, mode)
        fun clear() { super.deleteSharedPreferences(root.name + "-reader"); root.deleteRecursively() }
    }
}
