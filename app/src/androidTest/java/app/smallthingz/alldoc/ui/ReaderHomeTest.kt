package app.smallthingz.alldoc.ui

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.os.Bundle
import android.view.MotionEvent
import android.view.InputDevice
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalDensity
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.paging.PagingData
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.smallthingz.alldoc.ReaderTestActivity
import app.smallthingz.alldoc.state.DocumentMetadata
import app.smallthingz.alldoc.state.DocumentState
import app.smallthingz.alldoc.state.LibraryDocument
import app.smallthingz.alldoc.state.LibraryCategory
import app.smallthingz.alldoc.state.StoredDocument
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderHomeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun openDocumentRemainsReachableInShortViewportAtLargeFont() {
        val opened = AtomicBoolean()
        val storage = AtomicBoolean()
        ActivityScenario.launch(ReaderTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                        MaterialTheme {
                            Box(Modifier.fillMaxSize()) {
                                Box(Modifier.width(320.dp).height(180.dp).clipToBounds()) {
                                    ReaderHome({ opened.set(true) }, Modifier.fillMaxSize(),
                                        onLibrarySettings = { storage.set(true) })
                                }
                            }
                        }
                    }
                }
            }
            val deadline = SystemClock.uptimeMillis() + 8_000
            while (!opened.get() && SystemClock.uptimeMillis() < deadline) {
                visit { node ->
                    (node.text?.toString() == "Open" || node.contentDescription?.toString() == "Open") && click(node)
                }
                if (!opened.get()) {
                    visit { node ->
                        node.isEnabled && node.isVisibleToUser && node.isScrollable &&
                            node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    }
                    SystemClock.sleep(50)
                }
            }
            assertTrue("Open document must remain reachable in the compact home", opened.get())
            clickLabel("Library")
            clickLabel("Library settings")
            awaitHome { storage.get() }
        }
    }

    @Test fun cachedPreviewsAppearWithoutDuplicateDocumentsOrRepeatedLoads() {
        val first = StoredDocument("a".repeat(64), DocumentMetadata("Current.pdf", "application/pdf", 2048), DocumentState(), 2)
        val second = StoredDocument("b".repeat(64), DocumentMetadata("Notes.md", "text/markdown", 1024), DocumentState(), 1)
        val third = StoredDocument("c".repeat(64), DocumentMetadata("Manual.pdf", "application/pdf", 4096), DocumentState(), 0)
        val previews = mutableStateOf<Map<String, Bitmap>>(emptyMap())
        val active = mutableStateOf(false)
        val current = mutableStateOf<StoredDocument?>(null)
        val requests = mutableListOf<String>()
        val resumed = AtomicInteger(); val reopened = AtomicInteger(); val opened = AtomicInteger()
        val image = Bitmap.createBitmap(72, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.MAGENTA) }
        try {
            ActivityScenario.launch(ReaderTestActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent {
                    ReaderTheme(dark = true) {
                        Box(Modifier.fillMaxSize()) {
                            ReaderHome({ opened.incrementAndGet() }, Modifier.width(360.dp).height(320.dp).clipToBounds(), listOf(second, first, third),
                                onRecent = { assertEquals(second.hash, it); reopened.incrementAndGet() },
                                currentTitle = current.value?.title, currentHash = current.value?.hash, onResume = { resumed.incrementAndGet() },
                                previews = previews.value, onPreviewRequest = { requests.add(it) }, active = active.value,
                                currentPage = 1, currentPageCount = 5)
                        }
                    }
                } }
                awaitHome {
                    count(first.title) == 1 && count(second.title) == 1 && count(third.title) == 1 && count("Open") == 1 &&
                        visit { node -> node.isVisibleToUser && node.isScrollable &&
                            node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD } }
                }
                scenario.onActivity {
                    assertTrue("Hidden Home must not request previews", requests.isEmpty())
                    current.value = first
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity { assertTrue("Loading a retained document offscreen must not request previews", requests.isEmpty()); active.value = true }
                awaitHome(detail = {
                    "current=${documentRow(first.title)}, recent=${documentRow(second.title)}, " +
                        "titleCount=${count(first.title)}, pageCount=${count("2 / 5")}, backward=" +
                        visit { node -> node.isVisibleToUser && node.isScrollable &&
                            node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD } }
                }) {
                    val resume = documentRow(first.title)
                    val recent = documentRow(second.title)
                    resume != null && recent != null && resume.bounds.bottom <= recent.bounds.top &&
                        count(first.title) == 1 && count("2 / 5") == 1 &&
                        resume.state == "Continue reading" &&
                        !visit { node -> node.isVisibleToUser && node.isScrollable &&
                            node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD } }
                }
                awaitHome {
                    var ready = false
                    scenario.onActivity { ready = requests.toSet() == setOf(first.hash, second.hash, third.hash) }
                    ready
                }
                scenario.onActivity { previews.value = mapOf(first.hash to image, second.hash to image, third.hash to image) }
                awaitHome { previewPixels(scenario) > 1000 }
                scenario.onActivity { assertEquals("Publishing thumbnails must not reload them", 3, requests.size) }
                assertEquals("Current document must appear once", 1, count(first.title))
                assertEquals("Only the current document has known page progress", 1, count("2 / 5"))
                clickLabel(first.title); awaitHome { resumed.get() == 1 }
                clickLabel(second.title); awaitHome { reopened.get() == 1 }
                clickLabel("Open"); awaitHome { opened.get() == 1 }
            }
        } finally { image.recycle() }
    }

    @Test fun recentsStayShortAndLibraryShowsRealPortraitCoversForOnlyItsViewport() {
        val history = (1..10).map { number ->
            StoredDocument(number.toString(16).padStart(64, '0'),
                DocumentMetadata("Recent %02d.md".format(number), "text/markdown", 12), DocumentState(), number.toLong())
        }
        val documents = (1L..40L).map { id ->
            LibraryDocument(id, if (id == 2L) "b".repeat(64) else null, "Library %02d.pdf".format(id), "application/pdf", 1024,
                "content://home-library/$id", 1, 1)
        }
        val flow = flowOf(libraryPageData(documents))
        val active = mutableStateOf(true)
        val visible = AtomicReference<List<LibraryDocument>>(emptyList())
        val selected = AtomicBoolean()
        val tabIntents = AtomicInteger()
        val opened = AtomicReference<LibraryDocument?>()
        val current = mutableStateOf<LibraryDocument?>(null)
        val resumed = AtomicInteger()
        val image = Bitmap.createBitmap(240, 320, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.MAGENTA) }
        try {
            ActivityScenario.launch(ReaderTestActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, 1f)) {
                        ReaderTheme(dark = true) {
                            Box(Modifier.fillMaxSize()) {
                                ReaderHome({}, Modifier.width(360.dp).height(520.dp).clipToBounds(), history,
                                    active = active.value, library = flow.collectAsLazyPagingItems(),
                                    currentTitle = current.value?.title, currentHash = current.value?.hash,
                                    onResume = { resumed.incrementAndGet() },
                                    libraryPreviews = mapOf(1L to image, 2L to image), onLibrary = opened::set,
                                    onLibraryVisible = visible::set, onLibrarySelected = selected::set,
                                    onTabSelected = { tabIntents.incrementAndGet() })
                            }
                        }
                    }
                } }
                awaitHome { count(history.first().title) == 1 && count("Library") > 0 }
                val seen = linkedSetOf<String>()
                repeat(10) {
                    history.forEach { if (count(it.title) > 0) seen += it.title }
                    visit { node -> node.isVisibleToUser && node.isScrollable &&
                        node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) }
                    instrumentation.waitForIdleSync()
                    SystemClock.sleep(100)
                }
                history.forEach { if (count(it.title) > 0) seen += it.title }
                assertEquals("Recents exposes six entries, not the entire history", history.take(6).map { it.title }.toSet(), seen)
                assertTrue("Recents must not request library covers", visible.get().isEmpty())
                assertEquals("Composition and scrolling are not tab intent", 0, tabIntents.get())
                revealControls("Library")
                scenario.onActivity { current.value = documents[1] }
                clickLabel("Library")
                awaitHome { tabIntents.get() == 1 }
                awaitHome {
                    val first = documentRow(documents[0].title)
                    val second = documentRow(documents[1].title)
                    val third = documentRow(documents[2].title)
                    selected.get() && first != null && second != null && third != null &&
                        kotlin.math.abs(first.bounds.top - second.bounds.top) <= 2 &&
                        kotlin.math.abs(second.bounds.top - third.bounds.top) <= 2 &&
                        first.bounds.right <= second.bounds.left && second.bounds.right <= third.bounds.left &&
                        first.bounds.height() > first.bounds.width() && second.bounds.height() > second.bounds.width() &&
                        visible.get().map { it.id }.containsAll(listOf(1L, 2L, 3L))
                }
                assertTrue("Only intersecting covers are requested, not forty composed/data items", visible.get().size in 3..12)
                awaitHome { previewPixels(scenario) > 1000 }
                clickLabel(documents[1].title)
                awaitHome { opened.get() == documents[1] }
                assertEquals("A featured Library cover must reopen its source, not resume a snapshot", 0, resumed.get())
                assertTrue(visit { node -> node.isVisibleToUser && node.isScrollable &&
                    node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) })
                awaitHome { visible.get().isNotEmpty() && visible.get().none { it.id == 1L } && visible.get().any { it.id > 4 } }
                assertTrue("Scrolling must replace the bounded viewport window", visible.get().size <= 12)
                scenario.onActivity { active.value = false }
                awaitHome { visible.get().isEmpty() && !selected.get() }
                assertEquals("Leaving Home is not a deliberate tab change", 1, tabIntents.get())
                scenario.onActivity { active.value = true }
                awaitHome { selected.get() && visible.get().isNotEmpty() }
                assertEquals("Restoring the saved tab must not cancel startup intent", 1, tabIntents.get())
                revealControls("Recents")
                clickLabel("Recents")
                awaitHome { !selected.get() && visible.get().isEmpty() && tabIntents.get() == 2 }
            }
        } finally { image.recycle() }
    }

    @Test fun groupedLibraryFiltersNamesAndReturnsItsControlsWhenScrollingUp() {
        val documents = (1L..45L).map { id ->
            val presentation = id > 36
            LibraryDocument(id, null, if (presentation) "Deck $id.pptx" else "Report %02d.pdf".format(id),
                if (presentation) "application/vnd.openxmlformats-officedocument.presentationml.presentation" else "application/pdf", 1024, "content://home-filter/$id", 1, 1)
        }
        val history = documents.take(6).map { StoredDocument(it.id.toString().padStart(64, '0'),
            DocumentMetadata(it.title, it.mimeType, it.sizeBytes), DocumentState(), it.id) }
        val pages = MutableStateFlow(libraryPageData(documents))
        val requested = AtomicReference<Pair<String, LibraryCategory?>>()
        var generation = 0
        ActivityScenario.launch(ReaderTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                MaterialTheme {
                    Box(Modifier.fillMaxSize()) {
                        ReaderHome({}, Modifier.width(360.dp).height(420.dp).clipToBounds(), recent = history,
                            library = pages.collectAsLazyPagingItems(), onLibraryFilter = { query, category ->
                                requested.set(query to category)
                                // A deterministic backend response keeps this test focused on input, routing and layout.
                                val rows = documents.filter { (category == null || it.category == category) &&
                                    (query.isBlank() || query == "rpt" && it.category == LibraryCategory.DOCUMENTS) }
                                pages.value = libraryPageData(rows, (++generation).toString())
                            })
                    }
                }
            } }
            awaitHome { count("Recents") == 1 && count(history.first().title) == 1 }
            dragList(up = true)
            awaitHome { count("Recents") == 0 && count("Open") == 0 && count("Examples") == 0 }
            dragList(up = false)
            awaitHome { count("Recents") == 1 && count("Open") == 1 && count("Examples") == 1 }
            clickLabel("Library")
            awaitHome { count("Documents") == 1 && count(documents.first().title) == 1 }
            dragList(up = true)
            awaitHome { count("Library") == 0 && count("Document type") == 0 }
            dragList(up = false)
            awaitHome { count("Library") == 1 && count("Document type") == 1 }
            assertTrue("Old storage/action strips must not survive", count("Storage") + count("Add folder") + count("Refresh library") == 0)
            awaitHome { visit { it.isVisibleToUser && it.isEditable && it.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
                Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "rpt") }) } }
            awaitHome { requested.get() == ("rpt" to null) && count(documents.first().title) == 1 }
            clickLabel("Document type"); clickLabel("Presentations")
            awaitHome({ "filter=${requested.get()}, noMatches=${count("No matches")}" }) {
                requested.get() == ("rpt" to LibraryCategory.PRESENTATIONS) && count("No matches") == 1
            }
            clickLabel("Clear search")
            awaitHome { requested.get() == ("" to LibraryCategory.PRESENTATIONS) && count("Presentations") == 1 && count("Deck 37.pptx") == 1 }
            clickLabel("Document type"); clickLabel("All types")
            awaitHome { requested.get() == ("" to null) && count("Documents") == 1 && count(documents.first().title) == 1 }
        }
    }

    @Test fun retainedHomeDismissesItsFilterPopupAndDoesNotReopenItOnReturn() {
        val active = mutableStateOf(true)
        val clicked = AtomicBoolean()
        val requested = AtomicReference<Pair<String, LibraryCategory?>>()
        val pages = MutableStateFlow(libraryPageData(emptyList()))
        ActivityScenario.launch(ReaderTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent { MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    ReaderHome({}, Modifier.fillMaxSize().readerInput(active.value), active = active.value,
                        library = pages.collectAsLazyPagingItems(),
                        onLibraryFilter = { text, category -> requested.set(text to category) })
                    if (!active.value) androidx.compose.material3.Button(onClick = { clicked.set(true) }) {
                        androidx.compose.material3.Text("Current destination")
                    }
                }
            } } }
            clickLabel("Library")
            awaitHome { visit { it.isVisibleToUser && it.isEditable && it.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
                Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "rpt") }) } }
            clickLabel("Document type"); clickLabel("Presentations")
            awaitHome { requested.get() == ("rpt" to LibraryCategory.PRESENTATIONS) }
            clickLabel("Document type")
            awaitHome { count("All types") == 1 }
            instrumentation.runOnMainSync { active.value = false }
            awaitHome { count("All types") == 0 && count("Current destination") == 1 }
            clickLabel("Current destination")
            assertTrue("The retained menu must not intercept its new destination", clicked.get())
            instrumentation.runOnMainSync { active.value = true }
            awaitHome { count("Document type") == 1 && count("Current destination") == 0 }
            assertEquals("Returning Home must not reopen a dismissed menu", 0, count("All types"))
            assertEquals("Changing destinations preserves filtering", "rpt" to LibraryCategory.PRESENTATIONS, requested.get())
            clickLabel("Document type"); clickLabel("All types")
            awaitHome { requested.get() == ("rpt" to null) }
        }
    }

    @Test fun focusedLibrarySearchSurvivesExpandedToCompactResize() {
        val height = mutableStateOf(360.dp)
        val pages = MutableStateFlow(libraryPageData(emptyList()))
        val requested = AtomicReference<Pair<String, LibraryCategory?>>()
        ActivityScenario.launch(ReaderTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1f)) {
                    MaterialTheme {
                        ReaderHome({}, Modifier.width(360.dp).height(height.value).clipToBounds(),
                            library = pages.collectAsLazyPagingItems(),
                            onLibraryFilter = { text, category -> requested.set(text to category) })
                    }
                }
            } }
            clickLabel("Library")
            awaitHome { visit { it.isVisibleToUser && it.isEditable } }
            assertEquals("Expanded search does not need its compact toggle", 0, count("Close library search"))
            awaitHome { visit { it.isVisibleToUser && it.isEditable &&
                it.performAction(AccessibilityNodeInfo.ACTION_FOCUS) } }
            awaitHome { visit { it.isVisibleToUser && it.isEditable && it.isFocused } }
            // imePadding can reduce the parent constraint while the field still owns input focus.
            instrumentation.runOnMainSync { height.value = 180.dp }
            awaitHome { count("Close library search") == 1 &&
                visit { it.isVisibleToUser && it.isEditable && it.isFocused } }
            awaitHome { visit { it.isVisibleToUser && it.isEditable && it.performAction(
                AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "rpt")
                }) } }
            awaitHome { requested.get() == ("rpt" to null) }
            clickLabel("Clear search")
            awaitHome { requested.get() == ("" to null) }
            clickLabel("Document type"); clickLabel("Presentations")
            awaitHome { requested.get() == ("" to LibraryCategory.PRESENTATIONS) }
            clickLabel("Close library search")
            awaitHome { count("Find documents") == 1 && !visit { it.isVisibleToUser && it.isEditable } }
            clickLabel("Find documents")
            awaitHome { count("Document type") == 1 && visit { it.isVisibleToUser && it.isEditable } }
            assertEquals("Closing search keeps the chosen filter", "" to LibraryCategory.PRESENTATIONS, requested.get())
        }
    }

    @Test fun collapsedLibraryControlsReturnAutomaticallyWhenResultsFitTheViewport() {
        val documents = (1L..30L).map { id ->
            LibraryDocument(id, null, "Shrinking %02d.pdf".format(id), "application/pdf", 1024,
                "content://home-shrinking/$id", 1, 1)
        }
        val pages = MutableStateFlow(libraryPageData(documents))
        val opened = AtomicBoolean()
        val examples = AtomicBoolean()
        ActivityScenario.launch(ReaderTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1f)) {
                    MaterialTheme {
                        Box(Modifier.fillMaxSize()) {
                            ReaderHome({ opened.set(true) }, Modifier.width(360.dp).height(480.dp).clipToBounds(),
                                library = pages.collectAsLazyPagingItems(), onExamples = { examples.set(true) })
                        }
                    }
                }
            } }
            clickLabel("Library")
            awaitHome { count(documents.first().title) == 1 && count("Document type") == 1 }
            dragList(up = true)
            awaitHome { count("Library") == 0 && count("Open") == 0 && count("Examples") == 0 }
            // A scan/filter update can remove the grid's scroll actions. Controls must return
            // without a physical recovery drag, so accessibility users can still reach them.
            pages.value = libraryPageData(documents.take(3), "short")
            awaitHome { documents.take(3).all { count(it.title) == 1 } &&
                count("Recents") == 1 && count("Library") == 1 && count("Open") == 1 &&
                count("Examples") == 1 && count("Document type") == 1 }
            clickLabel("Open")
            awaitHome { opened.get() }
            clickLabel("Examples")
            awaitHome { examples.get() }
        }
    }

    @Test fun failedEarlierLibraryPageRetriesWithoutReplacingLoadedCovers() {
        val documents = (1L..24L).map { id ->
            LibraryDocument(id, null, "Earlier %02d.pdf".format(id), "application/pdf", 1024,
                "content://home-prepend/$id", 1, 1)
        }
        val refreshes = AtomicInteger()
        val prepends = AtomicInteger()
        val opened = AtomicReference<LibraryDocument?>()
        val visible = AtomicReference<List<LibraryDocument>>(emptyList())
        // An anchored refresh has earlier rows, just like returning after leading pages were dropped.
        val pages = Pager(PagingConfig(pageSize = 12, initialLoadSize = 12, prefetchDistance = 3,
            maxSize = 36, enablePlaceholders = false), initialKey = 12) {
            object : PagingSource<Int, LibraryDocument>() {
                override suspend fun load(params: LoadParams<Int>): LoadResult<Int, LibraryDocument> {
                    if (params is LoadParams.Refresh) refreshes.incrementAndGet()
                    if (params is LoadParams.Prepend && prepends.incrementAndGet() == 1)
                        return LoadResult.Error(IOException("Earlier page unavailable"))
                    val boundary = params.key ?: 12
                    val start = if (params is LoadParams.Prepend) (boundary - params.loadSize).coerceAtLeast(0) else boundary
                    val end = if (params is LoadParams.Prepend) boundary else (start + params.loadSize).coerceAtMost(documents.size)
                    return LoadResult.Page(documents.subList(start, end), if (start == 0) null else start,
                        if (end == documents.size) null else end)
                }
                override fun getRefreshKey(state: PagingState<Int, LibraryDocument>): Int? = null
            }
        }.flow.map { it.groupedLibrary() }
        val cover = Bitmap.createBitmap(240, 320, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.MAGENTA) }
        try {
            ActivityScenario.launch(ReaderTestActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, 1f)) {
                        MaterialTheme {
                            Box(Modifier.fillMaxSize()) {
                                ReaderHome({}, Modifier.width(360.dp).height(480.dp).clipToBounds(),
                                    library = pages.collectAsLazyPagingItems(), libraryPreviews = mapOf(13L to cover),
                                    onLibrary = opened::set, onLibraryVisible = visible::set)
                            }
                        }
                    }
                } }
                clickLabel("Library")
                awaitHome { prepends.get() == 1 && count("Retry earlier documents") == 1 && count(documents[12].title) == 1 }
                awaitHome { previewPixels(scenario) > 1000 }
                clickLabel(documents[12].title)
                awaitHome { opened.get() == documents[12] }
                assertEquals("A failed earlier page must not refresh current covers", 1, refreshes.get())
                clickLabel("Retry earlier documents")
                awaitHome { prepends.get() == 2 && count("Retry earlier documents") == 0 }
                repeat(4) {
                    if (documents.take(12).none { count(it.title) > 0 }) {
                        assertTrue("Earlier rows must be reachable with accessibility scrolling", visit { node ->
                            node.isVisibleToUser && node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD } &&
                                node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                        })
                        instrumentation.waitForIdleSync()
                        SystemClock.sleep(100)
                    }
                }
                awaitHome { documents.take(12).any { count(it.title) == 1 } }
                assertEquals("Retry must reload only the failed earlier page", 1, refreshes.get())
                assertTrue("Grouped lazy keys must not duplicate documents", documents.all { count(it.title) <= 1 })
                awaitHome {
                    val shown = documents.filter { count(it.title) == 1 }.map { it.id }.toSet()
                    shown.isNotEmpty() && visible.get().map { it.id }.toSet().containsAll(shown)
                }
                val earlier = documents.take(12).first { count(it.title) == 1 }
                clickLabel(earlier.title)
                awaitHome { opened.get() == earlier }
            }
        } finally { cover.recycle() }
    }

    // Static test data must explicitly finish loading, including empty filter results.
    private fun libraryPageData(documents: List<LibraryDocument>, key: String = "") = PagingData.from(documents,
        sourceLoadStates = LoadStates(LoadState.NotLoading(false), LoadState.NotLoading(true), LoadState.NotLoading(true)))
        .groupedLibrary(key)

    private fun revealControls(label: String) {
        awaitHome(detail = { "Missing $label and a backward accessibility action" }) {
            count(label) > 0 || visit { node ->
                node.isVisibleToUser && node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD } &&
                    node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            }
        }
        awaitHome { count(label) == 1 }
    }

    private fun dragList(up: Boolean, bounds: Rect? = null) {
        var viewport = bounds
        if (viewport == null) awaitHome {
            visit { node -> if (node.isVisibleToUser && node.isScrollable) {
                viewport = Rect().also(node::getBoundsInScreen); true
            } else false }
            viewport?.height()?.let { it > 80 } == true
        }
        val box = requireNotNull(viewport)
        val x = box.centerX().toFloat()
        val from = box.top + box.height() * if (up) .85f else .2f
        val to = box.top + box.height() * if (up) .15f else .65f
        val start = SystemClock.uptimeMillis()
        fun send(action: Int, y: Float) {
            val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        send(MotionEvent.ACTION_DOWN, from)
        for (step in 1..12) { SystemClock.sleep(15); send(MotionEvent.ACTION_MOVE, from + (to - from) * step / 12f) }
        send(MotionEvent.ACTION_UP, to)
        instrumentation.waitForIdleSync()
    }

    private fun previewPixels(scenario: ActivityScenario<ReaderTestActivity>): Int {
        var bitmap: Bitmap? = null
        val done = CountDownLatch(1); val status = AtomicInteger(PixelCopy.ERROR_UNKNOWN)
        try {
            scenario.onActivity { activity ->
                val view = activity.window.decorView
                if (view.width > 0 && view.height > 0) {
                    bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                    PixelCopy.request(activity.window, bitmap!!, { status.set(it); done.countDown() }, Handler(Looper.getMainLooper()))
                } else done.countDown()
            }
            val image = bitmap ?: return 0
            assertTrue("Preview capture timed out", done.await(5, TimeUnit.SECONDS))
            if (status.get() == PixelCopy.ERROR_SOURCE_NO_DATA) return 0
            assertEquals(PixelCopy.SUCCESS, status.get())
            val pixels = IntArray(image.width * image.height)
            image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
            return pixels.count { it == android.graphics.Color.MAGENTA }
        } finally { bitmap?.recycle() }
    }

    private fun awaitHome(detail: () -> String = { "" }, predicate: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < end) { if (predicate()) return; SystemClock.sleep(30) }
        fail("Home did not settle. ${detail()}; ${homeWindow()}")
    }
    @Suppress("DEPRECATION")
    private fun homeWindow(): String {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return "root=null"
        return try {
            "refreshed=${root.refresh()}, package=${root.packageName}, class=${root.className}, " +
                "window=${root.windowId}, visible=${root.isVisibleToUser}, children=${root.childCount}"
        } finally { root.recycle() }
    }
    private fun matches(node: AccessibilityNodeInfo, label: String) =
        node.contentDescription?.toString() == label || node.text?.toString()?.lineSequence()?.any { it == label } == true
    private data class DocumentRowInfo(val bounds: Rect, val state: String?)
    private fun documentRow(label: String): DocumentRowInfo? {
        var result: DocumentRowInfo? = null
        visit { node ->
            if (node.isVisibleToUser && matches(node, label)) result = rowOwner(node)
            result != null
        }
        return result
    }
    // Compose exposes title text beneath the clickable row that owns its state and hit bounds.
    @Suppress("DEPRECATION")
    private fun rowOwner(node: AccessibilityNodeInfo, depth: Int = 0): DocumentRowInfo? {
        if (!node.refresh() || node.packageName?.toString() != "app.smallthingz.alldoc" ||
            !node.isVisibleToUser || !node.isEnabled) return null
        if (node.isClickable) return DocumentRowInfo(Rect().also(node::getBoundsInScreen),
            AccessibilityNodeInfoCompat.wrap(node).stateDescription?.toString())
        if (depth == 16) return null
        val parent = node.parent ?: return null
        return try { rowOwner(parent, depth + 1) } finally { parent.recycle() }
    }
    private fun count(label: String): Int {
        var result = 0
        visit { if (it.isVisibleToUser && matches(it, label)) result++; false }
        return result
    }
    private fun clickLabel(label: String) = awaitHome { visit { it.isVisibleToUser && matches(it, label) && click(it) } }

    @Suppress("DEPRECATION")
    private fun visit(action: (AccessibilityNodeInfo) -> Boolean): Boolean {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return false
        return try { visit(root, 0, intArrayOf(0), action) } finally { root.recycle() }
    }

    @Suppress("DEPRECATION")
    private fun visit(node: AccessibilityNodeInfo, depth: Int, visited: IntArray,
        action: (AccessibilityNodeInfo) -> Boolean): Boolean {
        if (depth > 24 || visited[0]++ >= 512 || !node.refresh()) return false
        if (node.packageName?.toString() == "app.smallthingz.alldoc" && action(node)) return true
        for (index in 0 until minOf(node.childCount, 64)) {
            val child = node.getChild(index) ?: continue
            try { if (visit(child, depth + 1, visited, action)) return true }
            finally { child.recycle() }
        }
        return false
    }

    @Suppress("DEPRECATION")
    private fun click(node: AccessibilityNodeInfo, depth: Int = 0): Boolean {
        if (!node.refresh() || node.packageName?.toString() != "app.smallthingz.alldoc" ||
            !node.isVisibleToUser || !node.isEnabled) return false
        if (node.isClickable) return node.isEnabled && node.isVisibleToUser &&
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (depth == 16) return false
        val parent = node.parent ?: return false
        return try { click(parent, depth + 1) } finally { parent.recycle() }
    }
}
