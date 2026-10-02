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
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import java.io.File
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
  private var incoming by mutableStateOf<Uri?>(null)
  private var uiFontScale by mutableFloatStateOf(1f)
  private lateinit var exportedFile: File

  @Before
  fun setupWorkspace() {
    val app = compose.activity.application
    File(app.filesDir, "recovery").deleteRecursively()
    app
      .getSharedPreferences("recent-documents", android.content.Context.MODE_PRIVATE)
      .edit()
      .clear()
      .commit()
    model = DocumentModel(app)
    compose.activity.viewModelStore.put("test-document", model)
    compose.setContent {
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
    compose.onNodeWithText("Open a document").assertIsDisplayed()
    openFormats()
    compose.onNodeWithText("Clear format boundaries").assertIsDisplayed()
    capture("formats-light")
    compose.onNodeWithText("Got it").performClick()
    compose.onNodeWithText("Open a document").assertIsDisplayed()
    openFormats()
    compose.onNodeWithText("Got it").performClick()
    compose.onNodeWithText("Open a document").assertIsDisplayed()
  }

  @Test
  @Config(qualifiers = "w320dp-h640dp-night-xhdpi")
  fun narrowDarkHome() {
    ready()
    capture("home-narrow-dark")
    compose.onNodeWithText("Open a document").assertIsDisplayed()
  }

  @Test
  @Config(qualifiers = "w840dp-h900dp-xhdpi")
  fun wideHome() {
    ready()
    capture("home-tablet")
    compose.onNodeWithText("Open a document").assertIsDisplayed()
  }

  @Test
  fun textEditCancelExportAndReturnHome() {
    openFixture("notes.txt")
    compose.onNodeWithText("Edit", useUnmergedTree = true).performClick()
    compose.onAllNodes(hasSetTextAction()).onLast().performTextInput("Edited: ")
    compose.onNodeWithText("Save copy").assertIsDisplayed()
    capture("text-edit-focused")
    compose.onNodeWithText("Save copy").performClick()
    compose.onNodeWithText("Export a separate copy").assertIsDisplayed()
    capture("export-review")
    compose.onNodeWithText("Cancel").performClick()
    compose.onNodeWithText("Save copy").assertIsDisplayed()
    compose.onNodeWithText("‹").performClick()
    compose.onNodeWithText("Current draft").assertIsDisplayed()
    compose.onNodeWithContentDescription("Resume current document").performClick()
    compose.onNodeWithText("Save copy").assertIsDisplayed()
  }

  @Test
  fun worksheetCellEditingIsLocalAndCancelable() {
    openFixture("budget.xlsx")
    capture("worksheet-grid")
    compose.onNodeWithText("Paper").performClick()
    compose.onNodeWithText("Done").performClick()
    compose.onNodeWithText("Paper").assertIsDisplayed()
    compose.onNodeWithText("Paper").performClick()
    compose.onNodeWithContentDescription("Cell value").performTextReplacement("Temporary")
    compose.onNodeWithText("Revert").performClick()
    compose.onNodeWithText("Revert this cell?").assertIsDisplayed()
    compose.onNodeWithText("Keep editing").performClick()
    compose.onNodeWithContentDescription("Cell value").assertTextEquals("Temporary")
    compose.onNodeWithText("Revert").performClick()
    compose.onNodeWithText("Revert cell").performClick()
    compose.onNodeWithText("Paper").assertIsDisplayed()
    compose.onNodeWithText("Paper").performClick()
    compose.onNodeWithContentDescription("Cell value").performTextReplacement("Recycled paper")
    compose.onNodeWithText("Done").performClick()
    compose.onNodeWithText("Recycled paper").assertIsDisplayed()
    compose.onNodeWithText("Pens").assertIsDisplayed()
    capture("worksheet-edited")
  }

  @Test
  fun cellDraftSurvivesBackOutsideTapSheetSwitchAndIncomingOpen() {
    openFixture("budget.xlsx")
    compose.onNodeWithText("Paper").performClick()
    compose.onNodeWithContentDescription("Cell value").performTextReplacement("Retained cell")
    // A tap outside the editor selecting another cell cannot discard the live draft.
    compose.onNodeWithText("Pens").performClick()
    compose.onNodeWithText("Done").performClick()
    compose.onNodeWithText("Retained cell").performClick()
    compose.onNodeWithContentDescription("Cell value").assertIsDisplayed()
    compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
    compose.onNodeWithText("Retained cell").assertIsDisplayed()
    val sheets = model.session!!.blocks.map { it.section }.distinct()
    compose.onNodeWithText(sheets.last()).performClick()
    compose.onNodeWithText(sheets.first()).performClick()
    compose.onNodeWithText("Retained cell").assertIsDisplayed()
    compose.onNodeWithContentDescription("Back to library").performClick()
    compose.onNodeWithContentDescription("Resume current document").performClick()
    compose.onNodeWithText("Retained cell").assertIsDisplayed()
    compose.onNodeWithContentDescription("More options").performClick()
    compose.onNodeWithText("Open another document").performClick()
    compose.onNodeWithText("Leave these edits?").assertIsDisplayed()
    compose.onNodeWithText("Keep editing").performClick()
    compose.runOnUiThread { incoming = Uri.parse("content://alldoc.tests/incoming") }
    compose.onNodeWithText("Open the incoming document?").assertIsDisplayed()
    compose.onNode(hasText("Keep this draft") and hasClickAction()).performClick()
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
  fun searchRequestsFocusAndBackClosesSearchBeforeDocument() {
    openFixture("notes.txt")
    compose.onNodeWithText("Find").performClick()
    compose.onNode(hasSetTextAction()).assertIsFocused()
    compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
    compose.onNodeWithText("Find content").assertDoesNotExist()
    compose.onNodeWithText("notes.txt").assertIsDisplayed()
    compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
  }

  @Test
  fun documentReadingCanvasAndFailedExport() {
    openFixture("field-notes.docx")
    compose.onNodeWithText("Text").performClick()
    capture("document-content-canvas")
    compose.runOnUiThread { model.export(Uri.parse("content://alldoc.tests/failed-export")) }
    ready()
    compose.onNodeWithText("Dismiss").assertIsDisplayed()
    capture("export-error")
    compose.onNodeWithText("Dismiss").performClick()
    compose.onNodeWithText("field-notes.docx").assertIsDisplayed()
  }

  @Test
  fun incomingIntentCannotDiscardEditedDraftWithoutConfirmation() {
    openFixture("notes.txt")
    compose.runOnUiThread { model.edit("text", "Keep this draft") }
    compose.runOnUiThread { incoming = Uri.parse("content://alldoc.tests/incoming") }
    compose.onNodeWithText("Open the incoming document?").assertIsDisplayed()
    compose.onNode(hasText("Keep this draft") and hasClickAction()).performClick()
    compose.onNodeWithText("notes.txt").assertIsDisplayed()
    compose.runOnIdle { org.junit.Assert.assertEquals("Keep this draft", model.edits["text"]) }
  }

  @Test
  fun recentDocumentsAndRecoverableDraftAreVisible() {
    openFixture("notes.txt")
    compose.onNodeWithContentDescription("Back to library").performClick()
    openFixture("field-notes.docx")
    compose.onNodeWithText("Text").performClick()
    compose.runOnUiThread { model.edit(model.session!!.blocks.first().id, "Revised opening") }
    compose.onNodeWithContentDescription("Back to library").performClick()
    compose.onNodeWithText("Current draft").assertIsDisplayed()
    compose.onNodeWithText("Recent documents").assertIsDisplayed()
    capture("home-populated")
  }

  @Test
  fun textEntrySaveAndDiscardUseTheRealResultFlow() {
    openFixture("notes.txt")
    val original = File("../fixtures/notes.txt").readBytes()
    compose.onNodeWithText("Edit").performClick()
    compose.onAllNodes(hasSetTextAction()).onLast().performTextReplacement("Saved copy ✓\nمرحبا")
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
    compose.onNodeWithText("Copy exported").assertIsDisplayed()
    compose.onNodeWithText("Discard").performClick()
    compose.onNodeWithText("Discard edits").performClick()
    org.junit.Assert.assertTrue(model.edits.isEmpty())
  }

  @Test
  @Config(qualifiers = "w320dp-h640dp-night-xhdpi")
  fun narrowLargeTextAndImeInsetsKeepSaveVisible() {
    openFixture("notes.txt")
    compose.runOnUiThread { uiFontScale = 2f }
    compose.onNodeWithText("Edit").performClick()
    compose.onAllNodes(hasSetTextAction()).onLast().performTextInput("Large text: ")
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
        org.junit.Assert.assertTrue(view.webViewClient.shouldOverrideUrlLoading(view, request))
      }
    }
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
    compose.onNodeWithText(name).assertIsDisplayed()
  }

  private fun capture(name: String) {
    val output = File("build/screenshots/$name.png").apply { parentFile?.mkdirs() }
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
    ): Cursor = MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf(name)) }

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
