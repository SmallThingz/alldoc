package org.smallthingz.alldoc

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import java.io.File
import java.time.Duration
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h892dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WorkspaceUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var model: DocumentModel
    private lateinit var restoration: StateRestorationTester
    private var incoming by mutableStateOf<Uri?>(null)
    private var uiFontScale by mutableFloatStateOf(1f)
    private lateinit var exportedFile: File

    @Before
    fun setupWorkspace() {
        val app = compose.activity.application
        File(app.filesDir, "recovery").deleteRecursively()
        File(app.filesDir, "recovered-drafts").deleteRecursively()
        app.getSharedPreferences("recent-documents", android.content.Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        model = DocumentModel(app)
        compose.activity.viewModelStore.put("test-document", model)
        restoration = StateRestorationTester(compose)
        restoration.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, uiFontScale)) {
                AlldocTheme { Workspace(model, incoming) { incoming = null } }
            }
        }
        ready()
    }

    @After
    fun cleanupWorkspace() {
        compose.activity.viewModelStore.clear()
    }

    private fun openFormats() {
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Format support").performClick()
    }

    @Test
    fun homeAndFormatDialogRemainNavigable() {
        ready()
        capture("home-light")
        compose.onNodeWithContentDescription("Open a document").assertIsDisplayed()
        openFormats()
        compose.onNodeWithText("Supported formats").assertIsDisplayed()
        capture("formats-light")
        compose.onNodeWithText("Got it").performClick()
        compose.onNodeWithContentDescription("Open a document").assertIsDisplayed()
        openFormats()
        compose.onNodeWithText("Got it").performClick()
        compose.onNodeWithContentDescription("Open a document").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp-night-xhdpi")
    fun narrowDarkHome() {
        ready()
        capture("home-narrow-dark")
        compose.onNodeWithContentDescription("Open a document").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w840dp-h900dp-xhdpi")
    fun wideHome() {
        ready()
        capture("home-tablet")
        compose.onNodeWithContentDescription("Open a document").assertIsDisplayed()
    }

    @Test
    fun textEditCancelExportAndReturnHome() {
        openFixture("notes.txt")
        compose.onNodeWithContentDescription("Edit document source").performTextInput("Edited: ")
        showControls()
        compose.onNodeWithText("Save copy").assertIsDisplayed()
        capture("text-edit-focused")
        compose.onNodeWithText("Save copy").performClick()
        compose.onNodeWithText("Export a separate copy").assertIsDisplayed()
        capture("export-review")
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Save copy").assertIsDisplayed()
        showControls()
        compose.onNodeWithContentDescription("Back to library").performClick()
        compose.onNodeWithText("Current draft", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Resume current document").performClick()
        compose.onNodeWithTag("Document controls").assertDoesNotExist()
        showControls()
        compose.onNodeWithText("Save copy").assertIsDisplayed()
    }

    @Test
    fun worksheetCellEditingIsLocalAndCancelable() {
        openFixture("budget.xlsx")
        capture("worksheet-grid")
        selectCell("Paper")
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithText("Paper").assertIsDisplayed()
        selectCell("Paper")
        compose.onNodeWithContentDescription("Cell value").performTextReplacement("Temporary")
        compose.onNodeWithText("Revert").performClick()
        compose.onNodeWithText("Revert this cell?").assertIsDisplayed()
        compose.onNodeWithText("Keep editing").performClick()
        compose.onNodeWithContentDescription("Cell value").assertTextEquals("Temporary")
        compose.onNodeWithText("Revert").performClick()
        compose.onNodeWithText("Revert cell").performClick()
        compose.onNodeWithContentDescription("Cell value").assertTextEquals("Paper")
        compose.onNodeWithText("Revert").assertDoesNotExist()
        hideControls()
        compose.onNodeWithText("Paper").assertIsDisplayed()
        selectCell("Paper")
        compose.onNodeWithContentDescription("Cell value").performTextReplacement("Recycled paper")
        capture("worksheet-cell-editor")
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithText("Recycled paper").assertIsDisplayed()
        compose.onNodeWithText("Pens").assertIsDisplayed()
        capture("worksheet-edited")
    }

    @Test
    fun cellDraftSurvivesBackOutsideTapSheetSwitchAndIncomingOpen() {
        openFixture("budget.xlsx")
        selectCell("Paper")
        compose.onNodeWithContentDescription("Cell value").performTextReplacement("Retained cell")
        // A tap outside the editor selecting another cell cannot discard the live draft.
        selectCell("Pens")
        compose.onNodeWithText("Done").performClick()
        selectCell("Retained cell")
        compose.onNodeWithContentDescription("Cell value").assertIsDisplayed()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithContentDescription("Cell value").assertDoesNotExist()
        compose.onNodeWithTag("Document controls").assertDoesNotExist()
        compose.onNodeWithText("Retained cell").assertIsDisplayed()
        val sheets = model.session!!.blocks.map { it.section }.distinct()
        showControls()
        compose.onNodeWithText(sheets.last()).performClick()
        showControls()
        compose.onNodeWithText(sheets.first()).performClick()
        compose.onNodeWithText("Retained cell").assertIsDisplayed()
        showControls()
        compose.onNodeWithContentDescription("Back to library").performClick()
        compose.onNodeWithContentDescription("Resume current document").performClick()
        compose.onNodeWithText("Retained cell").assertIsDisplayed()
        showControls()
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Open another document").performClick()
        compose.onNodeWithText("Leave these edits?").assertIsDisplayed()
        compose.onNodeWithText("Keep editing").performClick()
        compose.runOnUiThread { incoming = Uri.parse("content://alldoc.tests/incoming") }
        compose.onNodeWithText("Open the incoming document?").assertIsDisplayed()
        compose.onNode(
            hasText("Keep this draft") and hasClickAction() and hasAnyAncestor(isDialog())
        ).performClick()
        compose.runOnIdle {
            org.junit.Assert.assertTrue(model.edits.values.contains("Retained cell"))
        }
        val recoveryIndex = File(compose.activity.filesDir, "recovery/session.json")
        val deadline = System.nanoTime() + 10_000_000_000L
        var durable = false
        while (!durable && System.nanoTime() < deadline) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            durable =
                runCatching {
                        org.json
                            .JSONObject(recoveryIndex.readText())
                            .getJSONObject("edits")
                            .toString()
                            .contains("Retained cell")
                    }
                    .getOrDefault(false)
            if (!durable) Thread.sleep(10)
        }
        org.junit.Assert.assertTrue("Cell edit was not committed to recovery", durable)
    }

    @Test
    fun sameCellTapKeepsRevertAndGlobalDiscardCannotRestoreDiscardedText() {
        openFixture("budget.xlsx")
        val address =
            model.session!!.blocks.first { it.original == "Paper" }.label.substringBefore(' ')
        selectCell("Paper")
        compose.onNodeWithContentDescription("Cell value").performTextReplacement("First change")
        hideControls()
        compose.onNodeWithContentDescription("$address: First change").performTouchInput { click() }
        settlePointerTap()
        showControls()
        compose.onNodeWithText("Revert").performClick()
        compose.onNodeWithText("Revert cell").performClick()
        compose.onNodeWithContentDescription("Cell value").assertTextEquals("Paper")
        compose.onNodeWithText("Revert").assertDoesNotExist()
        hideControls()
        compose.onNodeWithText("Paper").assertIsDisplayed()
        selectCell("Paper")
        compose.onNodeWithContentDescription("Cell value").performTextReplacement("Earlier draft")
        compose.onNodeWithText("Done").performClick()
        selectCell("Earlier draft")
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Discard edits").performClick()
        compose.onNodeWithText("Discard edits").performClick()
        compose.onNodeWithContentDescription("Cell value").assertDoesNotExist()
        compose.onNodeWithText("Revert").assertDoesNotExist()
        compose.onNodeWithText("Paper").assertIsDisplayed()
        compose.runOnIdle { org.junit.Assert.assertTrue(model.edits.isEmpty()) }
    }

    @Test
    fun cellLiveDraftExportsWithoutClosingEditor() {
        openFixture("budget.xlsx")
        selectCell("Paper")
        compose
            .onNodeWithContentDescription("Cell value")
            .performTextReplacement("Exported live cell")
        compose.runOnUiThread { model.export(Uri.parse("content://alldoc.tests/saved-copy")) }
        ready()
        val result =
            org.smallthingz.alldoc.core.DocumentEngine.open("copy.xlsx", exportedFile.readBytes())
        org.junit.Assert.assertTrue(result.blocks.any { it.original == "Exported live cell" })
        compose.onNodeWithContentDescription("Cell value").assertIsDisplayed()
    }

    @Test
    fun searchRequestsFocusAndBackClosesSearchBeforeDocument() {
        openFixture("notes.txt")
        showControls()
        compose.onNodeWithContentDescription("Find").performClick()
        compose.onNodeWithText("Find content").assertIsFocused()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("Find content").assertDoesNotExist()
        compose.onNodeWithText("notes.txt").assertIsDisplayed()
        compose.onNodeWithContentDescription("Edit document source").assertIsDisplayed()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(1)
        compose.onNodeWithContentDescription("Edit document source")
            .assertTextEquals(File("../fixtures/notes.txt").readText())
    }

    @Test
    fun restoredSearchWaitsForAvailableDocumentBeforeRequestingFocus() {
        openFixture("notes.txt")
        showControls()
        compose.onNodeWithContentDescription("Find").performClick()
        compose.onNodeWithText("Find content").assertIsFocused()
        compose.runOnIdle {
            compose.activity.viewModelStore.clear()
            File(compose.activity.filesDir, "recovery").deleteRecursively()
            model = DocumentModel(compose.activity.application)
            compose.activity.viewModelStore.put("test-document", model)
        }
        restoration.emulateSavedInstanceStateRestore()
        ready()
        compose.onNodeWithContentDescription("Open a document").assertIsDisplayed()
        compose.onNodeWithText("Find content").assertDoesNotExist()
    }

    @Test
    fun documentReadingCanvasAndFailedExport() {
        openFixture("field-notes.docx")
        showControls()
        compose.onNodeWithText("Edit text").performClick()
        capture("document-content-canvas")
        val viewport = compose.onNodeWithTag("Document viewport").getUnclippedBoundsInRoot()
        compose.runOnUiThread { model.export(Uri.parse("content://alldoc.tests/failed-export")) }
        ready()
        compose.onNodeWithText("Dismiss").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(96)
        compose.waitForIdle()
        val heading = compose.onNodeWithText("What matters").getUnclippedBoundsInRoot()
        val following = compose.onNodeWithText("Quiet, readable tools").getUnclippedBoundsInRoot()
        org.junit.Assert.assertTrue(
            "Error insertion overlapped document paragraphs",
            heading.bottom <= following.top,
        )
        org.junit.Assert.assertEquals(
            "Export error must not resize the document viewport",
            viewport,
            compose.onNodeWithTag("Document viewport").getUnclippedBoundsInRoot(),
        )
        capture("export-error")
        compose.onNodeWithText("Dismiss").performClick()
        showControls()
        compose.onNodeWithText("field-notes.docx").assertIsDisplayed()
    }

    @Test
    fun incomingIntentCannotDiscardEditedDraftWithoutConfirmation() {
        openFixture("notes.txt")
        compose.runOnUiThread { model.edit("text", "Keep this draft") }
        compose.runOnUiThread { incoming = Uri.parse("content://alldoc.tests/incoming") }
        compose.onNodeWithText("Open the incoming document?").assertIsDisplayed()
        compose.onNode(
            hasText("Keep this draft") and hasClickAction() and hasAnyAncestor(isDialog())
        ).performClick()
        showControls()
        compose.onNodeWithText("notes.txt").assertIsDisplayed()
        compose.runOnIdle { org.junit.Assert.assertEquals("Keep this draft", model.edits["text"]) }
    }

    @Test
    fun recentDocumentsAndRecoverableDraftAreVisible() {
        openFixture("field-notes.docx")
        showControls()
        compose.onNodeWithText("Edit text").performClick()
        compose.runOnUiThread {
            model.edit(model.session!!.blocks.first().id, "Revised opening")
            listOf(
                    "Project proposal for the community workshop October 2026.pdf",
                    "Budget.xlsx",
                    "Launch presentation.pptx",
                    "Meeting notes.odt",
                    "Quarterly plan.ods",
                    "App configuration.xml",
                    "Workshop outline.md",
                )
                .forEachIndexed { index, name ->
                    model.recentDocuments.add(
                        RecentDocument(
                            "content://alldoc.tests/library/$index",
                            name,
                            System.currentTimeMillis() - (index + 1) * 3600_000L,
                            false,
                        )
                    )
                }
        }
        showControls()
        compose.onNodeWithContentDescription("Back to library").performClick()
        compose.onNodeWithText("Current draft", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Recent").assertIsDisplayed()
        capture("home-populated")
        compose.onNodeWithText("PDF", useUnmergedTree = true).performClick()
        capture("home-filtered-pdf")
        compose.onNodeWithContentDescription("Search your files").performTextInput("nonexistent")
        compose.onNodeWithText("No files found").assertIsDisplayed()
        capture("home-no-results")
    }

    @Test
    fun textEntrySaveAndDiscardUseTheRealResultFlow() {
        openFixture("notes.txt")
        val original = File("../fixtures/notes.txt").readBytes()
        compose
            .onNodeWithContentDescription("Edit document source")
            .performTextReplacement("Saved copy ✓\nمرحبا")
        showControls()
        compose.onNodeWithText("Save copy").performClick()
        compose.onNodeWithText("Choose destination").performClick()
        val activity = org.robolectric.Shadows.shadowOf(compose.activity)
        val request = activity.nextStartedActivityForResult
        org.junit.Assert.assertEquals(Intent.ACTION_CREATE_DOCUMENT, request.intent.action)
        compose.runOnUiThread {
            activity.receiveResult(
                request.intent,
                android.app.Activity.RESULT_OK,
                Intent().setData(Uri.parse("content://alldoc.tests/saved-copy")),
            )
        }
        ready()
        org.junit.Assert.assertEquals("Saved copy ✓\nمرحبا", exportedFile.readText())
        org.junit.Assert.assertArrayEquals(original, File("../fixtures/notes.txt").readBytes())
        compose.onAllNodesWithText("Copy exported").onFirst().assertIsDisplayed()
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Discard edits").performClick()
        compose.onNodeWithText("Discard edits").performClick()
        org.junit.Assert.assertTrue(model.edits.isEmpty())
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp-night-xhdpi")
    fun narrowLargeTextAndImeInsetsKeepSaveVisible() {
        openFixture("notes.txt")
        compose.runOnUiThread { uiFontScale = 2f }
        compose.onNodeWithContentDescription("Edit document source").performTextInput("Large text: ")
        showControls()
        compose.runOnUiThread {
            val insets =
                androidx.core.view.WindowInsetsCompat.Builder()
                    .setInsets(
                        androidx.core.view.WindowInsetsCompat.Type.ime(),
                        androidx.core.graphics.Insets.of(0, 0, 0, 300),
                    )
                    .setVisible(androidx.core.view.WindowInsetsCompat.Type.ime(), true)
                    .build()
            androidx.core.view.ViewCompat.dispatchApplyWindowInsets(
                compose.activity.window.decorView,
                insets,
            )
        }
        compose.onNodeWithText("Save copy").assertIsDisplayed()
        val root = compose.onRoot().getUnclippedBoundsInRoot()
        val button =
            compose.onNode(hasText("Save copy") and hasClickAction()).getUnclippedBoundsInRoot()
        org.junit.Assert.assertTrue("Save exceeds right edge", button.right <= root.right)
        org.junit.Assert.assertTrue("Save exceeds bottom edge", button.bottom <= root.bottom)
        capture("text-edit-narrow-large-ime-insets")
    }

    @Test
    fun formattedPreviewRefusesExternalAndFileResources() {
        openFixture("field-notes.docx")
        compose.waitUntil(10000) { findWebView(compose.activity.window.decorView) != null }
        compose.runOnIdle {
            val view = findWebView(compose.activity.window.decorView)!!
            org.junit.Assert.assertFalse(view.settings.allowFileAccess)
            org.junit.Assert.assertFalse(view.settings.allowContentAccess)
            for (url in
                listOf(
                    "https://example.com/image.png",
                    "http://alldoc.invalid/preview/index.html",
                    "file:///etc/passwd",
                    "content://documents/private",
                    "https://alldoc.invalid/not-allowed.js",
                )) {
                val request =
                    object : android.webkit.WebResourceRequest {
                        override fun getUrl() = Uri.parse(url)

                        override fun isForMainFrame() = false

                        override fun isRedirect() = false

                        override fun hasGesture() = false

                        override fun getMethod() = "GET"

                        override fun getRequestHeaders() = mutableMapOf<String, String>()
                    }
                org.junit.Assert.assertEquals(
                    403,
                    view.webViewClient.shouldInterceptRequest(view, request)!!.statusCode,
                )
                org.junit.Assert.assertTrue(
                    view.webViewClient.shouldOverrideUrlLoading(view, request)
                )
            }
        }
    }

    @Test
    fun presentationAndOpenDocumentWorkspaces() {
        openFixture("studio-review.pptx")
        assertPresentationLimitationsAvailable()
        capture("slides-pptx")
        showControls()
        compose.onNodeWithText("Slides").performClick()
        showControls()
        compose.onNodeWithContentDescription("Next slide").performClick()
        compose.onNodeWithText("2 / ${model.session!!.slides.size}").assertIsDisplayed()
        hideControls()
        compose.onNodeWithTag("Slide page 2").assertIsDisplayed()
        // Presentation order deliberately differs from the numbered ZIP part names.
        compose.onNodeWithText("Work that moves.").assertIsDisplayed()
        capture("slides-pptx-second")
        showControls()
        compose.onNodeWithText("Edit text").performClick()
        compose
            .onAllNodes(hasSetTextAction())
            .onFirst()
            .performTextReplacement("A clearer direction.")
        showControls()
        compose.onNodeWithText("Save copy").assertIsDisplayed()
        capture("slides-edit")
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Discard edits").performClick()
        compose.onNodeWithText("Discard edits").performClick()
        openFixture("studio-review.odp")
        assertPresentationLimitationsAvailable()
        capture("slides-odp")
        openFixture("studio-budget.ods")
        selectCell("Notebooks")
        compose.onNodeWithContentDescription("Cell value").performTextReplacement("Sketchbooks")
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithText("Sketchbooks").assertIsDisplayed()
        capture("worksheet-ods")
    }

    @Test
    @Config(qualifiers = "w412dp-h892dp-night-xxhdpi")
    fun sourceEditingAndPopulatedDarkLibrary() {
        openFixture("manifest.xml")
        capture("xml-source-dark")
        compose
            .onNodeWithContentDescription("Edit document source")
            .performTextReplacement("<?xml version=\"1.0\"?>\n<workspace>Edited safely</workspace>")
        capture("xml-editor-dark")
        showControls()
        compose.onNodeWithText("Save copy").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back to library").performClick()
        capture("home-populated-dark")
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp-xhdpi")
    fun largeTypeLibraryReflowsOpenAction() {
        compose.runOnUiThread { uiFontScale = 2f }
        compose.onNodeWithContentDescription("Open a document").assertIsDisplayed()
        capture("home-narrow-large")
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp-night-xhdpi")
    fun largeTypeCellEditorKeepsCopyActionVisible() {
        openFixture("budget.xlsx")
        selectCell("Paper")
        compose.runOnUiThread { uiFontScale = 2f }
        compose.onNodeWithContentDescription("Cell value").performTextReplacement("Edited paper")
        compose.runOnUiThread {
            val insets =
                androidx.core.view.WindowInsetsCompat.Builder()
                    .setInsets(
                        androidx.core.view.WindowInsetsCompat.Type.ime(),
                        androidx.core.graphics.Insets.of(0, 0, 0, 300),
                    )
                    .setVisible(androidx.core.view.WindowInsetsCompat.Type.ime(), true)
                    .build()
            androidx.core.view.ViewCompat.dispatchApplyWindowInsets(
                compose.activity.window.decorView,
                insets,
            )
        }
        compose.onNodeWithText("Save copy").assertIsDisplayed()
        compose.onNodeWithContentDescription("Cell value").assertIsDisplayed()
        capture("worksheet-cell-large-ime-insets")
    }

    @Test
    fun recoveredDraftHasVisibleSavePathAndIncludesOriginalAndAllText() {
        openFixture("notes.txt")
        val original = File("../fixtures/recovery-v0.1.1-budget.xlsx")
        val snapshot =
            RecoveryStore.Snapshot(
                "budget.xlsx",
                original,
                mapOf("b4" to "Older saved edit"),
                false,
                null,
                1,
            )
        val archive =
            RecoveredDraftStore(File(compose.activity.filesDir, "recovered-drafts"))
                .preserve(snapshot)
        compose.runOnUiThread { model.recoveredDrafts.add(archive) }
        showControls()
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Recovered drafts").performClick()
        capture("recovered-drafts")
        compose.onNodeWithText("Save recovered draft").performClick()
        val activity = org.robolectric.Shadows.shadowOf(compose.activity)
        val request = activity.nextStartedActivityForResult
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnUiThread {
            activity.receiveResult(
                request.intent,
                android.app.Activity.RESULT_OK,
                Intent().setData(Uri.parse("content://alldoc.tests/saved-copy")),
            )
        }
        ready()
        java.util.zip.ZipFile(exportedFile).use { zip ->
            org.junit.Assert.assertArrayEquals(
                original.readBytes(),
                zip.getInputStream(zip.getEntry("original.xlsx")).readBytes(),
            )
            org.junit.Assert.assertTrue(
                zip.getInputStream(zip.getEntry("draft-changes.txt"))
                    .bufferedReader()
                    .readText()
                    .contains("Older saved edit")
            )
        }
    }

    @Test
    fun legacyOfficeFilesExposeReadOnlyTextAndNoEditAction() {
        for (extension in listOf("doc", "xls", "ppt")) {
            openFixture("legacy-basic.$extension")
            compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
            showControls()
            compose.onNodeWithText("Read-only in compact engine").assertIsDisplayed()
            compose.onNodeWithText("Edit text").assertDoesNotExist()
            compose.onNodeWithContentDescription("More options").performClick()
            compose.onNodeWithText("Document details").performClick()
            compose
                .onNodeWithText("Legacy Office • read-only text extraction; original-byte copies only")
                .assertIsDisplayed()
            compose.onNodeWithText("Done").performClick()
            hideControls()
            org.junit.Assert.assertFalse(model.session!!.editable)
            org.junit.Assert.assertArrayEquals(
                File("../fixtures/legacy-basic.$extension").readBytes(),
                model.session!!.export(emptyMap()),
            )
            capture("legacy-$extension-readonly")
        }
    }

    /** Accessibility reveal is independent of a document child's click/selection semantics. */
    private fun showControls() {
        if (compose.onAllNodesWithTag("Document controls").fetchSemanticsNodes().isEmpty()) {
            val action =
                compose.onNodeWithTag("Document viewport").fetchSemanticsNode()
                    .config[SemanticsActions.CustomActions]
                    .single { it.label == "Show document controls" }
            compose.runOnIdle { org.junit.Assert.assertTrue(action.action()) }
        }
        compose.onNodeWithTag("Document controls").assertIsDisplayed()
    }

    private fun hideControls() {
        if (compose.onAllNodesWithTag("Document controls").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithContentDescription("Hide document controls").performClick()
        }
        compose.onNodeWithTag("Document controls").assertDoesNotExist()
    }

    private fun settlePointerTap() {
        // Real single taps are delayed to distinguish them from double-tap selection/zoom.
        compose.mainClock.advanceTimeBy(400)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            .idleFor(Duration.ofMillis(400))
        compose.waitForIdle()
    }

    private fun selectCell(value: String) {
        // The overlay must not intercept a tap intended for a cell beneath it.
        hideControls()
        compose.onNode(hasText(value) and hasClickAction()).performTouchInput { click() }
        settlePointerTap()
        showControls()
        compose.onNodeWithContentDescription("Cell value").assertIsDisplayed()
    }

    private fun assertPresentationLimitationsAvailable() {
        compose.onNodeWithTag("Presentation viewport").assertIsDisplayed()
        showControls()
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Document details").performClick()
        compose.onNodeWithText(model.session!!.description).assertIsDisplayed()
        compose.onNodeWithText("Slide preview supports", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("Done").performClick()
        hideControls()
    }

    private fun findWebView(view: android.view.View): android.webkit.WebView? {
        if (view is android.webkit.WebView) return view
        if (view is android.view.ViewGroup)
            for (i in 0 until view.childCount) findWebView(view.getChildAt(i))?.let {
                return it
            }
        return null
    }

    private fun ready() {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (model.busy && System.nanoTime() < deadline) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        org.junit.Assert.assertFalse("Background document operation did not finish", model.busy)
        compose.waitForIdle()
    }

    private fun openFixture(name: String) {
        compose.waitUntil(10000) { !model.busy }
        val file = File("../fixtures/$name").absoluteFile
        check(file.isFile) { "Fixture missing: $file" }
        exportedFile = File(compose.activity.cacheDir, "saved-copy-$name")
        val provider = FixtureProvider(file, name, exportedFile)
        provider.attachInfo(compose.activity, ProviderInfo().apply { authority = "alldoc.tests" })
        ShadowContentResolver.registerProviderInternal("alldoc.tests", provider)
        compose.runOnUiThread { incoming = Uri.parse("content://alldoc.tests/$name") }
        compose.waitForIdle()
        ready()
        compose.onNodeWithTag("Document viewport").assertIsDisplayed()
        compose.onNodeWithTag("Document controls").assertDoesNotExist()
        compose.runOnIdle { org.junit.Assert.assertEquals(name, model.name) }
    }

    private fun capture(name: String) {
        val output =
            File("build/screenshots/immersive-regression/$name.png").apply { parentFile?.mkdirs() }
        compose.runOnIdle {
            val view =
                org.robolectric.shadows.ShadowDialog.getLatestDialog()
                    ?.takeIf { it.isShowing }
                    ?.window
                    ?.decorView ?: compose.activity.window.decorView
            val bitmap =
                Bitmap.createBitmap(
                    view.width.coerceAtLeast(1),
                    view.height.coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888,
                )
            view.draw(Canvas(bitmap))
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    private class FixtureProvider(
        private val file: File,
        private val name: String,
        private val exported: File,
    ) : ContentProvider() {
        override fun onCreate() = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor =
            MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf(name)) }

        override fun getType(uri: Uri) = "application/octet-stream"

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            if (uri.lastPathSegment == "failed-export")
                throw java.io.FileNotFoundException("Simulated provider write failure")
            if (uri.lastPathSegment == "saved-copy")
                return ParcelFileDescriptor.open(exported, ParcelFileDescriptor.parseMode(mode))
            return ParcelFileDescriptor.open(
                this@FixtureProvider.file,
                ParcelFileDescriptor.MODE_READ_ONLY,
            )
        }

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ) = 0
    }
}
