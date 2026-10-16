package app.smallthingz.alldoc.ui

import android.content.Intent
import android.accessibilityservice.AccessibilityService
import android.content.pm.ActivityInfo
import android.graphics.Rect
import android.graphics.Bitmap
import android.os.Bundle
import org.json.JSONObject
import java.security.MessageDigest
import app.smallthingz.alldoc.BuildConfig
import android.net.Uri
import androidx.lifecycle.ViewModelProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import app.smallthingz.alldoc.MainActivity
import java.io.File
import java.util.UUID
import android.content.res.Configuration
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.smallthingz.alldoc.ReaderTestActivity
import app.smallthingz.alldoc.engine.DocumentInfo
import app.smallthingz.alldoc.engine.DocumentNote
import app.smallthingz.alldoc.engine.DocumentNotes
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderNotesTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val info = DocumentInfo("Slides", "application/test", "PPTX", 3, true, "FULL", supportsNotes = true)
    private val fixtureNotes = listOf(
        "Speaker note 1: Five pages. Two ways to read.",
        "Speaker note 2: Normal view scrolls from top to bottom.",
        "Speaker note 3: Present view snaps to a single page.",
        "Speaker note 4: Notes and comments stay beside the document.",
        "Speaker note 5: Return to any page and carry on reading.",
    )

    @Test fun currentPageAndAllNotesKeepUnpagedCommentsAndNavigate() {
        val page = mutableStateOf(0)
        val notes = DocumentNotes(listOf(DocumentNote("First speaker", "speaker-note", location = "Slide 1", page = 0),
            DocumentNote("Second speaker", "speaker-note", location = "Slide 2", page = 1), DocumentNote("Document comment")))
        ActivityScenario.launch(ReaderTestActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { ReaderTheme {
                ReaderNotesContent(info, notes, false, page.value, 1, Modifier.fillMaxSize(), { page.value = it })
            } } }
            await { visible("First speaker") && visible("Document comment") }
            assertFalse("Other pages must be excluded by the page filter", visible("Second speaker"))
            clickLabel("Show all notes")
            await(scroll = true) { visible("Second speaker") && visible("Page 2") }
            assertFalse("A jump button already identifies the note's page", visible("Slide 2"))
            clickLabel("Page 2")
            await { page.value == 1 && visible("Slide 2") }
            assertFalse("The current note has a page label instead of a redundant jump", visible("Page 2"))
            clickLabel("Show current page notes")
            await { visible("Second speaker") && !visible("First speaker") }
        }
    }

    @Test fun loadingEmptyUnsupportedAndWarningsRemainDistinct() {
        val supported = mutableStateOf(true)
        val loading = mutableStateOf(true)
        val notes = mutableStateOf(DocumentNotes())
        ActivityScenario.launch(ReaderTestActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { ReaderTheme {
                ReaderNotesContent(info.copy(supportsNotes = supported.value), notes.value, loading.value,
                    0, 2, Modifier.fillMaxSize(), {})
            } } }
            await { visible("Loading…") }
            instrumentation.runOnMainSync { loading.value = false }
            await { visible("No notes.") }
            instrumentation.runOnMainSync { notes.value = DocumentNotes(warnings = listOf("Notes could not be loaded.")) }
            await { visible("Notes could not be loaded.") }
            assertFalse(visible("No notes."))
            instrumentation.runOnMainSync { supported.value = false }
            await { visible("Notes and comments are not supported for this format.") }
        }
    }

    @Test fun portraitAndLandscapeKeepDocumentAndNotesSeparateAndCloseRestoresSpace() {
        for (direction in listOf(LayoutDirection.Ltr, LayoutDirection.Rtl)) {
            for (orientation in listOf(Configuration.ORIENTATION_PORTRAIT, Configuration.ORIENTATION_LANDSCAPE)) {
                val shown = mutableStateOf(true)
                val documentBounds = AtomicReference<androidx.compose.ui.geometry.Rect>()
                val paneBounds = AtomicReference<androidx.compose.ui.geometry.Rect>()
                val documentDirection = AtomicReference<LayoutDirection>()
                val paneDirection = AtomicReference<LayoutDirection>()
                val diagnostic = "direction=$direction, orientation=$orientation"
                ActivityScenario.launch(ReaderTestActivity::class.java).use { scenario ->
                    scenario.onActivity { it.setContent {
                        val configuration = Configuration(LocalConfiguration.current).apply { this.orientation = orientation }
                        CompositionLocalProvider(LocalConfiguration provides configuration, LocalLayoutDirection provides direction) {
                            ReaderTheme {
                                Box(Modifier.width(320.dp).height(420.dp).clipToBounds()) {
                                    ReaderNotesLayout(shown.value, Modifier.fillMaxSize(), notes = { modifier ->
                                        val inheritedDirection = LocalLayoutDirection.current
                                        SideEffect { paneDirection.set(inheritedDirection) }
                                        ReaderNotesContent(info, DocumentNotes(listOf(DocumentNote("Visible speaker"))), false, 0, 3,
                                            modifier.onGloballyPositioned { paneBounds.set(it.boundsInRoot()) },
                                            {})
                                    }, content = { modifier ->
                                        val inheritedDirection = LocalLayoutDirection.current
                                        SideEffect { documentDirection.set(inheritedDirection) }
                                        Box(modifier.onGloballyPositioned { documentBounds.set(it.boundsInRoot()) }
                                            .semantics { contentDescription = "Document viewport" }) { Text("Document page") }
                                    })
                                }
                            }
                        }
                    } }
                    await { documentBounds.get() != null && paneBounds.get() != null }
                    val document = documentBounds.get()
                    val pane = paneBounds.get()
                    assertTrue("Document retains useful area: $diagnostic", document.width > 0 && document.height > 0)
                    assertTrue("Notes retain useful area: $diagnostic", pane.width > 0 && pane.height > 0)
                    if (orientation == Configuration.ORIENTATION_PORTRAIT)
                        assertTrue("Portrait notes appear below the document: $diagnostic", pane.top >= document.bottom)
                    else assertTrue("Landscape notes appear physically right of the document: $diagnostic", pane.left >= document.right)
                    assertEquals("Document content keeps inherited text direction: $diagnostic", direction, documentDirection.get())
                    assertEquals("Notes keep inherited text direction: $diagnostic", direction, paneDirection.get())
                    // Short panes must expose note text through accessibility scrolling in either direction.
                    await(scroll = true) { visible("Visible speaker") }
                    instrumentation.runOnMainSync { shown.value = false }
                    await { !shown.value && (if (orientation == Configuration.ORIENTATION_PORTRAIT)
                        documentBounds.get().height > document.height else documentBounds.get().width > document.width) }
                    assertTrue(visible("Document page"))
                    assertEquals("Hiding notes preserves document direction: $diagnostic", direction, documentDirection.get())
                }
            }
        }
    }

    @Test fun shortPortraitNotesWrapAndLongNotesStayBoundedAndScrollable() {
        val first = DocumentNote("Brief speaker note", "speaker-note", location = "Slide 1", page = 0)
        val other = DocumentNote("Another slide", "speaker-note", location = "Slide 2", page = 1)
        val notes = mutableStateOf(DocumentNotes(listOf(first, other)))
        val shown = mutableStateOf(true)
        val root = AtomicReference<androidx.compose.ui.geometry.Rect>()
        val pane = AtomicReference<androidx.compose.ui.geometry.Rect>()
        ActivityScenario.launch(ReaderTestActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent {
                val configuration = Configuration(LocalConfiguration.current).apply { orientation = Configuration.ORIENTATION_PORTRAIT }
                CompositionLocalProvider(LocalConfiguration provides configuration) {
                    ReaderTheme {
                        Box(Modifier.width(320.dp).height(480.dp).onGloballyPositioned { root.set(it.boundsInRoot()) }) {
                            ReaderNotesLayout(shown.value, Modifier.fillMaxSize(), notes = { modifier ->
                                ReaderNotesContent(info, notes.value, false, 0, 4,
                                    modifier.onGloballyPositioned { pane.set(it.boundsInRoot()) }, {})
                            }, content = { modifier -> Box(modifier) { Text("Document page") } })
                        }
                    }
                }
            } }
            await { visible("Brief speaker note") && visible("Show all notes") && pane.get() != null }
            val shortHeight = pane.get().height
            assertTrue("Short notes must return unused space to the document", shortHeight < root.get().height * .3f)
            assertFalse("The current note must not repeat the current slide label", visible("Slide 1"))
            val noteBounds = requireNotNull(labelBounds("Brief speaker note"))
            val filterBounds = requireNotNull(labelBounds("Show all notes"))
            assertTrue("Filter belongs beside the first note, not in its own row", filterBounds.centerY() in noteBounds.top..noteBounds.bottom)
            clickLabel("Show all notes")
            await { visible("Slide 1") }
            clickLabel("Show current page notes")
            await { !visible("Slide 1") }
            instrumentation.runOnMainSync { notes.value = DocumentNotes(
                List(24) { DocumentNote("Note ${it + 1}", page = 0) } + DocumentNote("End of notes", page = 0) + other) }
            await { pane.get().height > shortHeight }
            assertTrue("Long notes must leave most of the document visible", pane.get().height <= root.get().height * .35f + 1)
            await(scroll = true) { visible("End of notes") }
            val expandedHeight = pane.get().height
            instrumentation.runOnMainSync { notes.value = DocumentNotes(listOf(first, other)) }
            await { visible("Brief speaker note") }
            assertEquals("Changing notes must not oscillate the reading viewport", expandedHeight, pane.get().height, 1f)
            instrumentation.runOnMainSync { shown.value = false }
            await { !visible("Brief speaker note") }
            instrumentation.runOnMainSync { shown.value = true }
            await { visible("Brief speaker note") && pane.get().height < root.get().height * .3f }
            assertTrue(visible("Document page"))
        }
    }

    @Test fun actualNotesUseTheToolbarToggleAndEmptyDocumentsHideNotesAndContents() {
        val context = instrumentation.targetContext
        val slides = File(context.cacheDir, "notes-access-${UUID.randomUUID()}.pptx")
        val plain = File(context.cacheDir, "notes-empty-${UUID.randomUUID()}.pdf")
        context.assets.open("examples/office/office-guide.pptx").use { source ->
            slides.outputStream().use { source.copyTo(it) }
        }
        writeReaderPdf(plain)
        try {
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN)).use { scenario ->
                scenario.onActivity { ViewModelProvider(it)[ReaderModel::class.java].open(Uri.fromFile(plain)) }
                await { var ready = false
                    scenario.onActivity { val model = ViewModelProvider(it)[ReaderModel::class.java]
                        assertNull(model.error); ready = model.info != null && !model.loading && !model.notesLoading && !model.contentsLoading }
                    ready && visible("Full screen")
                }
                assertFalse("Empty PDF notes must not expose an action", visible("Notes"))
                assertFalse("Empty PDF outline must not expose an action", visible("Contents"))
                scenario.onActivity { ViewModelProvider(it)[ReaderModel::class.java].open(Uri.fromFile(slides)) }
                await { visible("Notes") && visible("Full screen") }
                scenario.onActivity { ViewModelProvider(it)[ReaderModel::class.java].apply {
                    choosePageView(false); updateViewport(app.smallthingz.alldoc.state.ViewportState()); goToPage(0); setNotesVisible(false)
                } }
                clickLabel("Full screen")
                val captures = if (InstrumentationRegistry.getArguments().getString("captureNotes") == "true")
                    File(context.cacheDir, "notes-evidence-${UUID.randomUUID()}").also { check(it.mkdir()) } else null
                var originalOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                scenario.onActivity { originalOrientation = it.requestedOrientation }
                try { for (orientation in listOf(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)) {
                    scenario.onActivity { it.requestedOrientation = orientation }
                    await { var ready = false
                        scenario.onActivity { val expected = if (orientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
                            Configuration.ORIENTATION_PORTRAIT else Configuration.ORIENTATION_LANDSCAPE
                            ready = it.resources.configuration.orientation == expected }
                        ready && visible("Notes")
                    }
                    clickLabel("Notes")
                    var displayed = -1
                    var previous = -1
                    var stable = 0
                    await(detail = { "orientation=$orientation, displayedPage=${displayed + 1}, note=${fixtureNotes.getOrNull(displayed)}" }) {
                        var ready = false
                        scenario.onActivity { val model = ViewModelProvider(it)[ReaderModel::class.java]
                            assertNull(model.error)
                            assertEquals(fixtureNotes.size, model.info?.pageCount)
                            assertFalse(model.horizontalPages); assertFalse(model.textMode)
                            displayed = model.page
                            ready = model.notesVisible && !model.notesLoading && !model.pageLoading && model.bitmap != null
                        }
                        val shown = ready && fixtureNotes.getOrNull(displayed)?.let(::visible) == true
                        stable = if (shown && displayed == previous) stable + 1 else 0
                        previous = displayed
                        stable >= 3
                    }
                    fixtureNotes.forEachIndexed { index, note ->
                        if (index != displayed) assertFalse("Only the displayed page's speaker note belongs in this view", visible(note))
                    }
                    assertFalse("Notes need no repeated header", visit { it.isVisibleToUser && it.text?.toString() == "Notes and comments" })
                    assertFalse("Notes close through the existing toolbar toggle", visible("Hide notes and comments"))
                    assertFalse("Current page is already shown in the toolbar", visible("Page ${displayed + 1} · Current page"))
                    assertFalse("Current slide label must not be repeated", visible("Slide ${displayed + 1}"))
                    captures?.let { captureNotes(scenario, slides, it, orientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) }
                    if (!visible("Notes")) visit { node ->
                        node.actionList.firstOrNull { it.label?.toString() == "Show reading controls" }?.let {
                            node.performAction(it.id)
                        } ?: false
                    }
                    clickLabel("Notes")
                    await {
                        var closed = false
                        scenario.onActivity { closed = !ViewModelProvider(it)[ReaderModel::class.java].notesVisible }
                        closed && fixtureNotes.none(::visible)
                    }
                    assertTrue("Closing notes keeps fullscreen available", visible("Exit full screen"))
                } } finally { scenario.onActivity { it.requestedOrientation = originalOrientation } }
                captures?.let { folder -> instrumentation.addResults(Bundle().apply {
                    putString("alldoc.notes.captures", "cache/${folder.name}")
                }) }
            }
        } finally { slides.delete(); plain.delete() }
    }

    @Test fun warningOnlyAndTruncatedNotesRemainReachableButEmptySuccessStaysHidden() {
        val context = instrumentation.targetContext
        val file = File(context.cacheDir, "notes-warning-${UUID.randomUUID()}.pdf")
        writeReaderPdf(file)
        try {
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN)).use { scenario ->
                scenario.onActivity { ViewModelProvider(it)[ReaderModel::class.java].open(Uri.fromFile(file)) }
                await {
                    var ready = false
                    scenario.onActivity { val model = ViewModelProvider(it)[ReaderModel::class.java]
                        assertNull(model.error)
                        ready = model.bitmap != null && !model.notesLoading && !model.contentsLoading
                    }
                    ready && visible("Full screen")
                }
                assertFalse(visible("Notes"))
                fun publish(value: DocumentNotes) = scenario.onActivity {
                    val model = ViewModelProvider(it)[ReaderModel::class.java]
                    assertTrue(requireNotNull(model.info).supportsNotes)
                    val field = ReaderModel::class.java.getDeclaredField("notes\$delegate").apply { isAccessible = true }
                    @Suppress("UNCHECKED_CAST")
                    (field.get(model) as MutableState<DocumentNotes>).value = value
                }
                publish(DocumentNotes(warnings = listOf("Controlled notes failure.")))
                await { visible("Notes") }
                clickLabel("Notes")
                await { visible("Controlled notes failure.") }
                publish(DocumentNotes(truncated = true))
                await { visible("Some notes were omitted.") && !visible("Controlled notes failure.") }
                clickLabel("Notes")
                await { !visible("Some notes were omitted.") && visible("Notes") }
                publish(DocumentNotes())
                await { !visible("Notes") }
            }
        } finally { file.delete() }
    }

    @Test fun failedContentsDiscoveryStaysReachableUntilAnExplicitSuccessfulRetry() {
        val context = instrumentation.targetContext
        val file = File(context.cacheDir, "contents-retry-${UUID.randomUUID()}.pdf")
        writeReaderPdf(file, marker = file.name)
        val loaded = ReaderModel::class.java.getDeclaredField("contentsLoaded").apply { isAccessible = true }
        val failure = "Controlled contents failure."
        try {
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN)).use { scenario ->
                scenario.onActivity { ViewModelProvider(it)[ReaderModel::class.java].open(Uri.fromFile(file)) }
                await {
                    var ready = false
                    scenario.onActivity { val model = ViewModelProvider(it)[ReaderModel::class.java]
                        assertNull(model.error)
                        ready = model.bitmap != null && loaded.getBoolean(model) && !model.contentsLoading
                    }
                    ready && visible("Full screen")
                }
                assertFalse("Confirmed empty contents must remain hidden", visible("Contents"))
                // Inject only the failed-discovery state. Retry still calls the real PDF session.
                scenario.onActivity {
                    val model = ViewModelProvider(it)[ReaderModel::class.java]
                    assertTrue(requireNotNull(model.info).supportsOutline)
                    assertTrue(model.outline.isEmpty())
                    loaded.setBoolean(model, false)
                    val field = ReaderModel::class.java.getDeclaredField("contentsError\$delegate").apply { isAccessible = true }
                    @Suppress("UNCHECKED_CAST")
                    (field.get(model) as MutableState<String?>).value = failure
                }
                fun assertFailureRetained() = scenario.onActivity { val model = ViewModelProvider(it)[ReaderModel::class.java]
                    assertEquals("Returning to the reader must preserve the failure", failure, model.contentsError)
                    assertFalse("Returning must not silently retry contents", loaded.getBoolean(model))
                    assertFalse(model.contentsLoading)
                }
                clickLabel("Home")
                await { visible("Recents") && visible(file.name) }
                clickLabel(file.name)
                await { visible("Full screen") }
                assertFailureRetained()
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
                await { visible("Full screen") }
                assertFailureRetained()
                clickLabel("Contents")
                await { visible(failure) && visible("Retry") }
                scenario.onActivity { val model = ViewModelProvider(it)[ReaderModel::class.java]
                    assertEquals("Opening the panel must preserve the failure", failure, model.contentsError)
                    assertFalse("Opening the panel must not silently retry", loaded.getBoolean(model))
                }
                clickLabel("Retry")
                await {
                    var finished = false
                    scenario.onActivity { val model = ViewModelProvider(it)[ReaderModel::class.java]
                        finished = loaded.getBoolean(model) && !model.contentsLoading && model.contentsError == null
                    }
                    finished && visible("No contents") && !visible(failure) && !visible("Retry")
                }
                assertTrue("Back must reach the focused Contents dialog", instrumentation.uiAutomation
                    .performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
                await(detail = { "fullScreen=${visible("Full screen")}, emptyPanel=${visible("No contents")}, contents=${visible("Contents")}" }) {
                    visible("Full screen") && !visible("No contents") && !visible("Contents")
                }
                scenario.onActivity { val model = ViewModelProvider(it)[ReaderModel::class.java]
                    assertTrue("Dismissing Contents must keep the reader open", model.readerVisible)
                    assertEquals(file.name, model.currentTitle)
                    assertTrue(loaded.getBoolean(model))
                    assertNull(model.contentsError)
                }
            }
        } finally { file.delete() }
    }

    /** Opt-in evidence from the actual reader; no global rotation or production-only test hook. */
    private fun captureNotes(scenario: ActivityScenario<MainActivity>, source: File, folder: File, landscape: Boolean) {
        val orientation = if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
        var previous: Triple<Int, Rect, Rect>? = null
        var stable = 0
        fun readyPage(): Int? {
            var page: Int? = null
            scenario.onActivity { activity ->
                val model = ViewModelProvider(activity)[ReaderModel::class.java]
                assertNull(model.error)
                val insets = ViewCompat.getRootWindowInsets(activity.window.decorView)
                if (activity.resources.configuration.orientation == orientation && model.bitmap != null &&
                    !model.pageLoading && !model.notesLoading && model.notesVisible && !model.textMode && !model.horizontalPages &&
                    model.info?.pageCount == fixtureNotes.size && model.page in fixtureNotes.indices &&
                    insets != null && !insets.isVisible(WindowInsetsCompat.Type.statusBars()) &&
                    !insets.isVisible(WindowInsetsCompat.Type.navigationBars()) && !insets.isVisible(WindowInsetsCompat.Type.captionBar()))
                    page = model.page
            }
            return page.takeIf { visit { node -> node.isVisibleToUser && node.contentDescription?.toString() == "Reading area" &&
                AccessibilityNodeInfoCompat.wrap(node).stateDescription?.toString() == "Full screen" } }
        }
        await {
            val page = readyPage()
            val current = page?.let { index -> labelBounds("Document pages")?.let { document ->
                labelBounds(fixtureNotes[index])?.let { Triple(index, document, it) }
            } }
            stable = if (current != null && current == previous) stable + 1 else 0
            previous = current
            stable >= 3
        }
        val captured = requireNotNull(previous)
        val (page, document, note) = captured
        if (landscape) assertTrue("Actual speaker notes must be right of the reader", note.left >= document.right)
        else assertTrue("Actual speaker notes must be below the reader", note.top >= document.bottom)
        fun assertUnchanged() {
            assertEquals("Capture must keep its displayed page and actual reading mode", page, readyPage())
            assertEquals(document, labelBounds("Document pages"))
            assertEquals(note, labelBounds(fixtureNotes[page]))
        }
        assertUnchanged()
        val stem = if (landscape) "landscape" else "portrait"
        val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            assertUnchanged()
            if (!landscape) assertTrue("One short speaker note must not reserve a third of the screen",
                document.height() > screenshot.height * .75f)
            File(folder, "$stem.png").outputStream().use { check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            File(folder, "$stem.json").writeText(JSONObject().apply {
                put("version", BuildConfig.VERSION_NAME)
                put("fixture", "examples/office/office-guide.pptx")
                put("fixtureSha256", MessageDigest.getInstance("SHA-256").digest(source.readBytes()).joinToString("") { "%02x".format(it) })
                put("orientation", stem); put("width", screenshot.width); put("height", screenshot.height)
                put("documentBounds", document.flattenToString()); put("noteBounds", note.flattenToString())
                put("page", page + 1); put("pageCount", fixtureNotes.size)
                put("speakerText", fixtureNotes[page]); put("mode", "Normal"); put("fullscreen", true)
            }.toString(2))
        } finally { screenshot.recycle() }
    }

    private fun labelBounds(label: String): Rect? {
        var result: Rect? = null
        visit { node ->
            if (node.isVisibleToUser && (node.text?.toString() == label || node.contentDescription?.toString() == label)) {
                result = Rect().also(node::getBoundsInScreen); true
            } else false
        }
        return result
    }

    private fun visible(text: String): Boolean = visit { it.isVisibleToUser &&
        (it.text?.toString() == text || it.contentDescription?.toString() == text) }

    private fun scrollBackTo(label: String) = await {
        if (visible(label)) true else {
            visit { it.isVisibleToUser && it.isScrollable &&
                it.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) }
            false
        }
    }

    private fun clickLabel(label: String) = await(detail = {
        "action=$label, visible=${visible(label)}, controlsHidden=${visit { node ->
            node.isVisibleToUser && node.actionList.any { it.label?.toString() == "Show reading controls" }
        }}"
    }) { visit { node -> node.isVisibleToUser &&
        (node.text?.toString() == label || node.contentDescription?.toString() == label) && click(node) } }

    private fun await(scroll: Boolean = false, detail: () -> String = { "" }, ready: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            if (ready()) return
            if (scroll) visit { it.isVisibleToUser && it.isScrollable &&
                it.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) }
            SystemClock.sleep(40)
        }
        fail("Notes UI did not expose the expected state; ${detail()}")
    }

    @Suppress("DEPRECATION")
    private fun visit(action: (AccessibilityNodeInfo) -> Boolean): Boolean {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return false
        return try { visit(root, 0, action) } finally { root.recycle() }
    }

    @Suppress("DEPRECATION")
    private fun visit(node: AccessibilityNodeInfo, depth: Int, action: (AccessibilityNodeInfo) -> Boolean): Boolean {
        if (depth > 24 || !node.refresh()) return false
        if (node.packageName?.toString() == "app.smallthingz.alldoc" && action(node)) return true
        for (index in 0 until minOf(node.childCount, 64)) {
            val child = node.getChild(index) ?: continue
            try { if (visit(child, depth + 1, action)) return true } finally { child.recycle() }
        }
        return false
    }

    @Suppress("DEPRECATION")
    private fun click(node: AccessibilityNodeInfo, depth: Int = 0): Boolean {
        if (node.isClickable) return node.isEnabled && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (depth >= 16) return false
        val parent = node.parent ?: return false
        return try { click(parent, depth + 1) } finally { parent.recycle() }
    }
}
