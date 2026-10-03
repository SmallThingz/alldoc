package org.smallthingz.alldoc

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h892dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImmersiveGestureObserverTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var reveals = 0
    private var childClicks = 0
    private var moving = false
    private var text by mutableStateOf(TextFieldValue("The document stays editable on a caret tap."))

    private fun content(allowChildHandledTap: Boolean = true) {
        compose.setContent {
            Column(
                Modifier.fillMaxSize()
                    .immersiveTapObserver(
                        allowChildHandledTap = { allowChildHandledTap },
                        isScrollInProgress = { moving },
                        onReveal = { reveals++ },
                    )
            ) {
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().padding(24.dp).testTag("field"),
                    minLines = 4,
                )
                Box(
                    Modifier.fillMaxWidth()
                        .height(80.dp)
                        .background(Color.LightGray)
                        .testTag("blank")
                )
                Box(
                    Modifier.fillMaxWidth()
                        .height(80.dp)
                        .testTag("button")
                        .clickable { childClicks++ }
                )
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
    }

    private fun advance(milliseconds: Long) {
        compose.mainClock.advanceTimeBy(milliseconds, ignoreFrameDuration = true)
        compose.waitForIdle()
    }

    @Test
    fun caretTapWaitsForSingleTapWindowEvenWithDifferentAndroidClockAndKeepsFocus() {
        content()
        // A real, independent Android clock jump reproduces the original mixed-clock bug.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        compose.onNodeWithTag("field").performTouchInput { click(Offset(30f, 25f)) }
        compose.runOnIdle { assertEquals(0, reveals) }
        advance(250)
        compose.runOnIdle { assertEquals(0, reveals) }
        advance(100)
        compose.runOnIdle { assertEquals(1, reveals) }
        compose.onNodeWithTag("field").assertIsFocused().performTextInput("Typed ")
        compose.runOnIdle {
            assertTrue(text.text.contains("Typed "))
            assertTrue(text.selection.collapsed)
        }
    }

    @Test
    fun doubleTapOnBlankSurfaceNeverFlashesControls() {
        content()
        compose.onNodeWithTag("blank").performTouchInput {
            click(center)
            advanceEventTime(100)
            click(center)
        }
        compose.runOnIdle { assertEquals(0, reveals) }
        advance(600)
        compose.runOnIdle { assertEquals(0, reveals) }
    }

    @Test
    fun smallStationaryDriftRevealsOnlyAfterWindow() {
        content()
        compose.onNodeWithTag("blank").performTouchInput {
            down(center)
            moveBy(Offset(1f, 1f))
            up()
        }
        compose.runOnIdle { assertEquals(0, reveals) }
        advance(350)
        compose.runOnIdle { assertEquals(1, reveals) }
    }

    @Test
    fun pointerCancellationNeverRevealsEvenWithChildConsumptionOptIn() {
        content()
        compose.onNodeWithTag("blank").performTouchInput {
            down(center)
            cancel()
        }
        advance(600)
        compose.runOnIdle { assertEquals(0, reveals) }
    }

    @Test
    fun dragAndPinchNeverReveal() {
        content()
        compose.onNodeWithTag("blank").performTouchInput { swipeUp() }
        advance(600)
        compose.runOnIdle { assertEquals(0, reveals) }
        compose.onNodeWithTag("blank").performTouchInput {
            down(0, center - Offset(20f, 0f))
            down(1, center + Offset(20f, 0f))
            moveTo(0, center - Offset(50f, 0f))
            moveTo(1, center + Offset(50f, 0f))
            up(0)
            up(1)
        }
        advance(600)
        compose.runOnIdle { assertEquals(0, reveals) }
    }

    @Test
    fun longPressOnBlankSurfaceNeverReveals() {
        content()
        compose.onNodeWithTag("blank").performTouchInput { longClick() }
        advance(600)
        compose.runOnIdle { assertEquals(0, reveals) }
    }

    @Test
    fun activeFlingAtDownIsRejectedEvenIfItStopsBeforeRelease() {
        content()
        moving = true
        compose.onNodeWithTag("blank").performTouchInput { down(center) }
        compose.runOnIdle { moving = false }
        compose.onNodeWithTag("blank").performTouchInput { up() }
        advance(600)
        compose.runOnIdle { assertEquals(0, reveals) }
    }

    @Test
    fun consumedChildButtonKeepsItsActionWithoutRevealingWhenNotOptedIn() {
        content(allowChildHandledTap = false)
        compose.onNodeWithTag("button").performTouchInput { click() }
        advance(600)
        compose.runOnIdle {
            assertEquals(1, childClicks)
            assertEquals(0, reveals)
        }
    }
}
