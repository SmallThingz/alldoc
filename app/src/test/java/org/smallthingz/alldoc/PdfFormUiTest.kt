package org.smallthingz.alldoc

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** UI-model tests only: these do not stand in for native PdfRenderer form roundtrips. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h892dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PdfFormUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun textFieldsKeepTheirIndependentDraftValues() {
        val fields =
            listOf(
                PdfFormField(0, 0, "Full name", "Alex Example", 40, false),
                PdfFormField(0, 1, "Notes", "First line\nSecond line", 300, true),
            )
        val edits = mutableStateMapOf<String, String>()
        compose.setContent {
            AlldocTheme {
                PdfFormCanvas(
                    PdfFormPage(fields),
                    edits,
                    true,
                    { id, value -> edits[id] = value },
                    Modifier.fillMaxSize(),
                )
            }
        }
        compose.onNodeWithContentDescription("Full name").performTextReplacement("Alex Updated")
        compose.onNodeWithContentDescription("Notes").assertTextContains("First line\nSecond line")
        org.junit.Assert.assertEquals("Alex Updated", edits["pdf:0:0"])
        capture("pdf-form-panel-ui-model")
    }

    @Test
    fun unsupportedStateExplainsScopeWithoutEditableFields() {
        compose.setContent {
            AlldocTheme {
                PdfFormCanvas(
                    PdfFormPage(
                        emptyList(),
                        "This PDF contains a signature field. Fill it in a dedicated PDF editor.",
                    ),
                    emptyMap(),
                    true,
                    { _, _ -> },
                    Modifier.fillMaxSize(),
                )
            }
        }
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose
            .onNodeWithText("This PDF contains a signature field.", substring = true)
            .assertIsDisplayed()
        capture("pdf-form-unavailable-ui-model")
    }

    private fun capture(name: String) {
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            File("build/screenshots/v0.2/$name.png")
                .apply { parentFile?.mkdirs() }
                .outputStream()
                .use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
