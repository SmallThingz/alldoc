package org.smallthingz.alldoc

import android.content.*
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import java.io.File
import java.time.Duration
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h892dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class ImmersiveWorkspaceFixture {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    protected lateinit var model: DocumentModel
    protected var incoming by mutableStateOf<Uri?>(null)
    protected var fontScale by mutableFloatStateOf(1f)

    @Before
    fun setup() {
        val app = compose.activity.application
        File(app.filesDir, "recovery").deleteRecursively()
        File(app.filesDir, "recovered-drafts").deleteRecursively()
        app.getSharedPreferences("recent-documents", Context.MODE_PRIVATE).edit().clear().commit()
        model = DocumentModel(app)
        compose.activity.viewModelStore.put("document", model)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                AlldocTheme { Workspace(model, incoming) { incoming = null } }
            }
        }
        ready()
    }

    @After
    fun cleanup() {
        compose.activity.viewModelStore.clear()
    }

    protected fun ready() {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (model.busy && System.nanoTime() < deadline) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        org.junit.Assert.assertFalse(model.error, model.busy)
        compose.waitForIdle()
    }

    protected fun open(name: String) {
        val file = File("../fixtures/$name").absoluteFile
        val provider =
            object : ContentProvider() {
                override fun onCreate() = true

                override fun query(
                    uri: Uri,
                    projection: Array<out String>?,
                    selection: String?,
                    selectionArgs: Array<out String>?,
                    sortOrder: String?,
                ): Cursor =
                    MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply {
                        addRow(arrayOf(name))
                    }

                override fun getType(uri: Uri) = "application/octet-stream"

                override fun openFile(uri: Uri, mode: String) =
                    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)

                override fun insert(uri: Uri, values: ContentValues?): Uri? = null

                override fun delete(
                    uri: Uri,
                    selection: String?,
                    selectionArgs: Array<out String>?,
                ) = 0

                override fun update(
                    uri: Uri,
                    values: ContentValues?,
                    selection: String?,
                    selectionArgs: Array<out String>?,
                ) = 0
            }
        provider.attachInfo(
            compose.activity,
            ProviderInfo().apply { authority = "alldoc.immersive" },
        )
        ShadowContentResolver.registerProviderInternal("alldoc.immersive", provider)
        compose.runOnUiThread { incoming = Uri.parse("content://alldoc.immersive/$name") }
        compose.waitForIdle()
        ready()
        compose.onNodeWithTag("Document viewport").assertIsDisplayed()
    }

    protected fun settleTap() {
        compose.mainClock.advanceTimeBy(400)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            .idleFor(Duration.ofMillis(400))
        compose.waitForIdle()
    }

    protected fun showAccessible() {
        val action =
            compose
                .onNodeWithTag("Document viewport")
                .fetchSemanticsNode()
                .config[SemanticsActions.CustomActions]
                .first { it.label == "Show document controls" }
        compose.runOnIdle { action.action() }
        compose.waitForIdle()
    }

    protected fun capture(name: String) {
        compose.waitForIdle()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            File("build/screenshots/immersive/$name.png")
                .apply { parentFile?.mkdirs() }
                .outputStream()
                .use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}

// Keep native graphics sandboxes small while retaining every fixture and assertion.
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h892dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImmersiveWorkspaceTest : ImmersiveWorkspaceFixture() {
    @Test
    fun sourceStartsEditableWithoutChromeAndCaretTapRevealsOverlayWithoutReflow() {
        open("notes.txt")
        compose.onNodeWithTag("Document controls").assertDoesNotExist()
        compose.onNodeWithText("Read").assertDoesNotExist()
        compose.onNodeWithText("Edit").assertDoesNotExist()
        compose.onNodeWithContentDescription("Edit document source").assertIsDisplayed()
        val before = compose.onNodeWithTag("Document viewport").getUnclippedBoundsInRoot()
        capture("source-hidden")
        compose.onNodeWithContentDescription("Edit document source").performTouchInput {
            click(androidx.compose.ui.geometry.Offset(45f, 35f))
        }
        settleTap()
        compose.onNodeWithTag("Document controls").assertIsDisplayed()
        val after = compose.onNodeWithTag("Document viewport").getUnclippedBoundsInRoot()
        Assert.assertEquals(before, after)
        capture("source-overlay")
        compose
            .onNodeWithContentDescription("Edit document source")
            .assertIsFocused()
            .performTextInput("Typed directly: ")
        compose.onNodeWithTag("Document controls").assertDoesNotExist()
        Assert.assertTrue(model.edits["text"]!!.contains("Typed directly"))
        capture("source-typing-controls-hidden")
    }

    @Test
    fun sourceDragNeverRevealsControls() {
        open("notes.txt")
        compose.onNodeWithTag("Document viewport").performTouchInput { swipeUp() }
        settleTap()
        compose.onNodeWithTag("Document controls").assertDoesNotExist()
    }

    @Test
    fun findSelectsActualSourceTextAndTypingReturnsToClearCanvas() {
        open("immersive-long.txt")
        showAccessible()
        compose.onNodeWithContentDescription("Find").performClick()
        compose.onNodeWithText("Find content").performTextInput("orbit violet 927")
        compose.waitForIdle()
        val field = compose.onNodeWithContentDescription("Edit document source")
        val selection = field.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange]
        Assert.assertFalse(selection.collapsed)
        val text = field.fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        Assert.assertEquals("orbit violet 927", text.substring(selection.min, selection.max))
        capture("source-find-overlay")
    }

    @Test
    fun rtfStartsDirectlyEditableAndUnicodeEditReopensWithoutChangingOriginal() {
        open("formatted.rtf")
        compose.onNodeWithTag("Document controls").assertDoesNotExist()
        compose.onNodeWithText("This paragraph includes read-only fields").assertDoesNotExist()
        capture("rtf-hidden")
        val field = compose.onNodeWithContentDescription("Edit RTF paragraph 1")
        field.performTouchInput { click(Offset(45f, 35f)) }
        settleTap()
        compose.onNodeWithTag("Document controls").assertIsDisplayed()
        field.performTextReplacement("Updated heading 世界 😀")
        compose.onNodeWithTag("Document controls").assertDoesNotExist()
        val session = model.session!!
        val copy = session.export(model.edits.toMap())
        val reopened = org.smallthingz.alldoc.core.DocumentEngine.open("edited.rtf", copy)
        Assert.assertTrue(reopened.blocks.any { it.original.contains("Updated heading 世界 😀") })
        Assert.assertArrayEquals(
            File("../fixtures/formatted.rtf").readBytes(),
            session.export(emptyMap()),
        )
        capture("rtf-edited-hidden")
    }
}

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h892dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImmersivePresentationTest : ImmersiveWorkspaceFixture() {

    @Test
    fun presentationModesAreRealAndControlsDoNotTakeDocumentSpace() {
        open("presentation-fidelity.pptx")
        compose.onNodeWithTag("Document controls").assertDoesNotExist()
        capture("presentation-pages-hidden")
        val before = compose.onNodeWithTag("Document viewport").getUnclippedBoundsInRoot()
        showAccessible()
        compose.onNodeWithText("Pages").assertIsDisplayed()
        compose.onNodeWithText("Slides").performClick()
        compose.onNodeWithTag("Document controls").assertDoesNotExist()
        Assert.assertEquals(
            before,
            compose.onNodeWithTag("Document viewport").getUnclippedBoundsInRoot(),
        )
        capture("presentation-slides-hidden")
        showAccessible()
        capture("presentation-overlay")
    }

    @Test
    fun presentationPointerAndNavigationRetainCurrentSlide() {
        open("immersive-six-slides.pptx")
        val viewport = compose.onNodeWithTag("Presentation viewport")
        viewport.performTouchInput { click(Offset(10f, 500f)) }
        settleTap()
        compose.onNodeWithTag("Document controls").assertIsDisplayed()
        compose.onNodeWithContentDescription("Next slide").performClick()
        compose.onNodeWithText("2 / 6").assertIsDisplayed()
        compose.onNodeWithTag("Slide page 1").assertIsNotDisplayed()
        compose.onNodeWithTag("Slide page 2").assertIsDisplayed()
        compose.onNodeWithText("Slides").performClick()
        compose.onNodeWithTag("Slide page 2").assertIsDisplayed()
        viewport.performTouchInput { click(Offset(10f, 500f)) }
        settleTap()
        compose.onNodeWithText("2 / 6").assertIsDisplayed()
        compose.onNodeWithText("Pages").performClick()
        compose.onNodeWithTag("Slide page 2").assertIsDisplayed()
        viewport.performTouchInput { swipeUp() }
        settleTap()
        compose.onNodeWithTag("Document controls").assertDoesNotExist()
        showAccessible()
        val pageText =
            compose
                .onAllNodes(hasText(" / 6", substring = true))
                .onFirst()
                .fetchSemanticsNode()
                .config[SemanticsProperties.Text]
                .first()
                .text
        Assert.assertNotEquals("1 / 6", pageText)
        compose.onNodeWithText("Slides").performClick()
        showAccessible()
        compose.onNodeWithText(pageText).assertIsDisplayed()
        compose.onNodeWithContentDescription("Previous slide").performClick()
        capture("presentation-navigation-overlay")
    }

    @Test
    fun presentationDoubleTapPinchAndPagerSwipeStayImmersive() {
        open("immersive-six-slides.pptx")
        showAccessible()
        compose.onNodeWithText("Slides").performClick()
        val viewport = compose.onNodeWithTag("Presentation viewport")
        viewport.performTouchInput { swipeLeft() }
        settleTap()
        compose.onNodeWithTag("Document controls").assertDoesNotExist()
        compose.onNodeWithTag("Slide page 2").assertIsDisplayed()
        viewport.performTouchInput {
            click(Offset(10f, 500f))
            advanceEventTime(100)
            click(Offset(10f, 500f))
        }
        settleTap()
        compose.onNodeWithTag("Document controls").assertDoesNotExist()
        viewport.performTouchInput {
            down(0, center - Offset(40f, 0f))
            down(1, center + Offset(40f, 0f))
            moveTo(0, center - Offset(100f, 0f))
            moveTo(1, center + Offset(100f, 0f))
            up(0)
            up(1)
        }
        settleTap()
        compose.onNodeWithTag("Document controls").assertDoesNotExist()
        capture("presentation-pinched-hidden")
    }

    @Test
    fun freshPresentationKeyboardControlsAndOverlayButtonsDoNotBubble() {
        open("immersive-six-slides.pptx")
        compose.onNodeWithTag("Document viewport").performKeyInput { pressKey(Key.F10) }
        compose.onNodeWithTag("Document controls").assertIsDisplayed()
        compose.onNodeWithContentDescription("Next slide").performTouchInput { click() }
        settleTap()
        compose.onNodeWithTag("Document controls").assertIsDisplayed()
        compose.onNodeWithText("2 / 6").assertIsDisplayed()
        compose.onNodeWithTag("Document viewport").performKeyInput { pressKey(Key.Escape) }
        compose.onNodeWithTag("Document controls").assertDoesNotExist()
    }
}

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h892dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImmersiveSelectionTest : ImmersiveWorkspaceFixture() {

    @Test
    @Config(qualifiers = "w320dp-h640dp-night-xhdpi")
    fun largeFontDarkSourceRemainsEditableWithOverlayAndTyping() {
        compose.runOnIdle { fontScale = 2f }
        open("notes.txt")
        compose.onNodeWithContentDescription("Edit document source").performTouchInput {
            click(Offset(45f, 35f))
        }
        settleTap()
        capture("source-large-dark-after-tap")
        compose.onNodeWithTag("Document controls").assertIsDisplayed()
        capture("source-large-dark-overlay")
        compose
            .onNodeWithContentDescription("Edit document source")
            .performTextInput("Visible typing ")
        compose.onNodeWithTag("Document controls").assertDoesNotExist()
        capture("source-large-dark-typing")
    }

    @Ignore(
        "Robolectric native Magnifier.destroy hits null Surface; requires Android device verification. Do not replace with a production selection workaround."
    )
    @Test
    fun actualLongPressSelectionAndHandleDragKeepControlsHidden() {
        open("immersive-long.txt")
        compose.onNodeWithContentDescription("Edit document source").performTouchInput {
            longClick(Offset(55f, 40f))
        }
        settleTap()
        compose.onNodeWithTag("Document controls").assertDoesNotExist()
    }

    @Test
    fun actualLongSourceFlingAndStoppingTapDoNotRevealControls() {
        open("immersive-long.txt")
        compose.mainClock.autoAdvance = false
        val scroll = compose.onNodeWithTag("Source scroll")
        scroll.performTouchInput { swipeUp(durationMillis = 180) }
        compose.mainClock.advanceTimeBy(32)
        val range = scroll.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        Assert.assertTrue("Long source must actually scroll", range.value() > 0f)
        scroll.performTouchInput { click(center) }
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithTag("Document controls").assertDoesNotExist()
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        capture("source-scrolled-hidden")
    }

    @Test
    fun tapDismissingExistingSourceSelectionDoesNotReveal() {
        open("notes.txt")
        val field = compose.onNodeWithContentDescription("Edit document source")
        field.performTouchInput { click(Offset(45f, 35f)) }
        settleTap()
        compose.onNodeWithContentDescription("Hide document controls").performClick()
        field.performTextInputSelection(androidx.compose.ui.text.TextRange(0, 9))
        Assert.assertFalse(
            field.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange].collapsed
        )
        field.performTouchInput { click(Offset(200f, 40f)) }
        settleTap()
        compose.onNodeWithTag("Document controls").assertDoesNotExist()
    }
}

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h892dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImmersiveSheetTest : ImmersiveWorkspaceFixture() {

    @Test
    fun populatedSheetCellTapRevealsItsEditorWithoutResizingGrid() {
        open("budget.xlsx")
        val before = compose.onNodeWithTag("Document viewport").getUnclippedBoundsInRoot()
        compose.onNodeWithText("Paper").performTouchInput { click() }
        settleTap()
        compose.onNodeWithTag("Document controls").assertIsDisplayed()
        compose
            .onNodeWithContentDescription("Cell value")
            .assertTextEquals("Paper")
            .performTextReplacement("Edited paper")
        compose.onNodeWithTag("Document controls").assertIsDisplayed()
        Assert.assertEquals(
            before,
            compose.onNodeWithTag("Document viewport").getUnclippedBoundsInRoot(),
        )
        capture("sheet-cell-overlay")
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithTag("Document controls").assertDoesNotExist()
    }
}
