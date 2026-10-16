package app.smallthingz.alldoc.ui

import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.smallthingz.alldoc.ReaderTestActivity
import app.smallthingz.alldoc.state.TextPosition
import app.smallthingz.alldoc.theme.ThemeSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderFocusTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun inactiveReaderSearchRejectsKeysWhileForegroundFieldAcceptsThem() {
        val active = mutableStateOf(true)
        val query = TextFieldState()
        val foreground = TextFieldState()
        val foregroundEnabled = mutableStateOf(false)
        fun value(state: TextFieldState): String {
            var text = ""
            instrumentation.runOnMainSync { text = state.text.toString() }
            return text
        }
        fun awaitForeground(enabled: Boolean) = await { visit { node ->
            // The description can live on a synthetic child; disabled state belongs to EditText.
            node.className?.toString() == "android.widget.EditText" && node.isVisibleToUser &&
                node.isEnabled == enabled && node.isEditable == enabled
        } }
        ActivityScenario.launch(ReaderTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    ReaderTheme {
                        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                            // This is the same retained reader field and route input gate used by ReaderScreen.
                            Column(Modifier.fillMaxSize().readerInput(active.value)) {
                                Text("Reader destination marker")
                                ReaderTextView("Searchable document", false, ThemeSettings(), true, query,
                                    onCloseSearch = {},
                                    modifier = Modifier.weight(1f).fillMaxSize(), documentKey = 1L,
                                    position = TextPosition(), onPosition = {}, onScrollEnd = {}, backEnabled = active.value)
                            }
                            if (!active.value) Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
                                Text("Settings destination marker")
                                OutlinedTextField(state = foreground, enabled = foregroundEnabled.value,
                                    label = { Text("Foreground setting") }, lineLimits = TextFieldLineLimits.SingleLine)
                            }
                        }
                    }
                }
            }
            await { visit { it.text?.toString() == "Reader destination marker" } }
            stage("focus initial reader search")
            focusVisibleField()
            stage("type into initial reader search")
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_A)
            await { value(query).equals("a", ignoreCase = true) }
            val savedQuery = value(query)
            stage("deactivate reader immediately after text update")
            scenario.onActivity { active.value = false }
            await { visit { it.text?.toString() == "Settings destination marker" } }
            instrumentation.waitForIdleSync()
            assertFalse("Inactive route descendants must leave the accessibility tree",
                visit { it.text?.toString() == "Reader destination marker" })
            // Hardware navigation may focus a newly enabled foreground field automatically.
            // Keep it disabled until this probe has independently checked the retained reader.
            awaitForeground(false)
            stage("send key with reader inactive")
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_B)
            instrumentation.waitForIdleSync()
            assertEquals("An inactive retained search must not receive hardware input", savedQuery, value(query))
            assertEquals("A disabled foreground fixture must not receive input", "", value(foreground))

            stage("focus foreground setting")
            scenario.onActivity { foregroundEnabled.value = true }
            awaitForeground(true)
            focusVisibleField()
            stage("type into foreground setting")
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_C)
            await { value(foreground).equals("c", ignoreCase = true) }
            assertEquals("Typing in the foreground must not change hidden search", savedQuery, value(query))

            stage("reactivate retained reader search")
            scenario.onActivity { active.value = true }
            await { visit { it.text?.toString() == "Reader destination marker" } }
            instrumentation.waitForIdleSync()
            stage("focus retained reader search")
            focusVisibleField()
            stage("type into retained reader search")
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_D)
            await { value(query).length == savedQuery.length + 1 }
            assertTrue("Returning to the reader must make its field usable", value(query).endsWith("d", ignoreCase = true))
        }
    }

    private fun await(predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (predicate()) return
            SystemClock.sleep(30)
        }
        fail("Reader focus did not settle")
    }

    private fun stage(value: String) { Log.i("ReaderFocusTest", value) }

    private fun focusVisibleField() {
        await { visit { node ->
            node.isEditable && node.isEnabled && node.isVisibleToUser &&
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } }
        await { visit { it.isEditable && it.isEnabled && it.isVisibleToUser && it.isFocused } }
    }

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
}
