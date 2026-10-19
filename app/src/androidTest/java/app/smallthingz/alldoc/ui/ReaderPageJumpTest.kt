package app.smallthingz.alldoc.ui

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextRange
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import app.smallthingz.alldoc.ReaderTestActivity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Jump content and hardware key handling, restricted to this Activity's dialog window. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class ReaderPageJumpTest {
    private fun await(message: String, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 5000
        while (!condition()) {
            assertTrue(message, SystemClock.uptimeMillis() < end)
            SystemClock.sleep(10)
        }
    }

    private fun nodes(node: SemanticsNode): Sequence<SemanticsNode> = sequence {
        yield(node)
        for (child in node.children) yieldAll(nodes(child))
    }

    private fun composeRoot(view: View): ViewRootForTest? = (view as? ViewRootForTest)
        ?: (view as? ViewGroup)?.let { group ->
            (0 until group.childCount).firstNotNullOfOrNull { composeRoot(group.getChildAt(it)) }
        }

    // testSemantics supplies the existing Activity/token ownership check. Raw descendants are
    // inspected while input is disabled; no semantic focus, text, click or IME action is invoked.
    private fun ReaderTestActivity.jumpField(): Pair<View, SemanticsNode>? {
        val owned = testSemantics(includeDialogs = true).map { it.id }.toSet()
        return WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull { decor ->
            if (!decor.isAttachedToWindow || !decor.isShown ||
                decor.display?.displayId != window.decorView.display?.displayId) return@firstNotNullOfOrNull null
            val root = composeRoot(decor)?.semanticsOwner?.unmergedRootSemanticsNode
                ?: return@firstNotNullOfOrNull null
            if (root.id !in owned) return@firstNotNullOfOrNull null
            nodes(root).firstOrNull { it.config.getOrNull(SemanticsActions.SetText) != null }
                ?.let { decor to it }
        }
    }

    private fun frames(scenario: ActivityScenario<ReaderTestActivity>, decor: View) {
        val ready = CountDownLatch(1)
        val callback = object : Runnable {
            var count = 0
            override fun run() {
                if (++count == 3) ready.countDown() else decor.postOnAnimation(this)
            }
        }
        scenario.onActivity { decor.postOnAnimation(callback) }
        try { assertTrue("The owned Jump window did not deliver three frames", ready.await(5, TimeUnit.SECONDS)) }
        finally { scenario.onActivity { decor.removeCallbacks(callback) } }
    }

    @Test fun jumpWaitsForInputEligibilityThenHardwareKeysNavigateOnce() {
        launchReaderTestActivity().use { scenario ->
            val allowed = mutableStateOf(false)
            val open = mutableStateOf(true)
            val pages = AtomicInteger()
            val destination = AtomicInteger(-1)
            val dismissals = AtomicInteger()
            scenario.onActivity { activity -> activity.setContent {
                CompositionLocalProvider(LocalReaderInputAllowed provides { allowed.value }) {
                    MaterialTheme {
                        if (open.value) ReaderPageJump(0, 217, onDismiss = {
                            dismissals.incrementAndGet()
                            open.value = false
                        }, onPage = { destination.set(it); pages.incrementAndGet() })
                    }
                }
            } }
            lateinit var dialog: View
            await("The owned Jump dialog did not attach and gain window focus") {
                var ready = false
                scenario.onActivity { activity ->
                    activity.jumpField()?.let { (decor, field) ->
                        ready = decor.hasWindowFocus() && field.boundsInWindow.width > 0f
                        if (ready) dialog = decor
                    }
                }
                ready
            }
            // Let the original one-shot effect exhaust its frame wait while the parent denies
            // input. A later permission change must cause a new focus attempt, without a tap.
            frames(scenario, dialog)
            scenario.onActivity { activity ->
                val field = requireNotNull(activity.jumpField()).second
                assertEquals("1", field.config[SemanticsProperties.EditableText].text)
                assertEquals(TextRange(0, 1), field.config[SemanticsProperties.TextSelectionRange])
                assertFalse("Disabled Jump must not take field focus",
                    field.config.getOrNull(SemanticsProperties.Focused) == true)
                assertEquals(0, pages.get())
                assertEquals(0, dismissals.get())
                allowed.value = true
            }
            await("Jump did not automatically focus when its input became eligible") {
                var focused = false
                scenario.onActivity { activity ->
                    activity.jumpField()?.let { (decor, field) ->
                        focused = decor === dialog && decor.hasWindowFocus() &&
                            field.config.getOrNull(SemanticsProperties.Focused) == true
                    }
                }
                focused
            }
            fun key(code: Int) {
                scenario.onActivity { activity ->
                    activity.verifyTestDisplay()
                    val (decor, field) = requireNotNull(activity.jumpField())
                    assertSame(dialog, decor)
                    assertTrue(decor.isAttachedToWindow && decor.hasWindowFocus())
                    assertTrue("Hardware keys must reach the actually focused field",
                        field.config.getOrNull(SemanticsProperties.Focused) == true)
                    val time = SystemClock.uptimeMillis()
                    fun event(action: Int) = KeyEvent(time, SystemClock.uptimeMillis(), action,
                        code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
                    try { decor.dispatchKeyEvent(event(KeyEvent.ACTION_DOWN)) }
                    finally { decor.dispatchKeyEvent(event(KeyEvent.ACTION_UP)) }
                }
            }
            key(KeyEvent.KEYCODE_2)
            key(KeyEvent.KEYCODE_1)
            key(KeyEvent.KEYCODE_5)
            await("Hardware digit keys did not replace the initially selected page") {
                var typed = false
                scenario.onActivity { activity ->
                    typed = activity.jumpField()?.second?.config?.getOrNull(SemanticsProperties.EditableText)?.text == "215"
                }
                typed
            }
            assertEquals("Typing must not navigate before Enter", 0, pages.get())
            key(KeyEvent.KEYCODE_ENTER)
            await("Hardware Enter did not navigate and dismiss Jump") {
                pages.get() == 1 && destination.get() == 214 && dismissals.get() == 1
            }
            await("Dismissed Jump retained its editable content") {
                var absent = false
                scenario.onActivity { absent = it.jumpField() == null }
                absent
            }
            assertEquals(1, pages.get())
            assertEquals(214, destination.get())
            assertEquals(1, dismissals.get())
        }
    }
}
