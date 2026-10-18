package app.smallthingz.alldoc.ui

import android.os.Build
import android.os.SystemClock
import android.view.Window
import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.activity.ComponentDialog
import androidx.activity.compose.setContent
import androidx.activity.findViewTreeOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import app.smallthingz.alldoc.ReaderTestActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class ReaderModalWindowTest {
    @Test fun topPanelsAndBottomSheetsPreserveFullscreenAcrossFocusAndModeChanges() {
        for (top in listOf(true, false)) {
            val immersive = mutableStateOf(true)
            val open = mutableStateOf(false)
            ActivityScenario.launch(ReaderTestActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    WindowCompat.setDecorFitsSystemWindows(activity.window, false)
                    activity.setContent {
                        ReaderTheme {
                            CompositionLocalProvider(LocalReaderImmersive provides immersive.value) {
                                SideEffect { configureHost(activity.window, immersive.value) }
                                Box(Modifier.fillMaxSize()) { Text("Reader") }
                                if (open.value) {
                                    if (top) ReaderTopPanel("File info", { open.value = false }) { Text("Details") }
                                    else ReaderSheet("Appearance", { open.value = false }, showTitle = false) { Text("Themes") }
                                }
                            }
                        }
                    }
                }
                await("Host enters fullscreen") {
                    var ready = false
                    scenario.onActivity { ready = barsHidden(it.window) }
                    ready
                }
                scenario.onActivity { open.value = true }
                assertDialogChrome(scenario, immersive = true)
                scenario.onActivity { immersive.value = false }
                assertDialogChrome(scenario, immersive = false)
                scenario.onActivity { immersive.value = true }
                assertDialogChrome(scenario, immersive = true)
                scenario.onActivity {
                    val owner = WindowInspector.getGlobalWindowViews().single { view -> view.hasWindowFocus() }
                        .findViewTreeOnBackPressedDispatcherOwner() as ComponentDialog
                    owner.onBackPressedDispatcher.onBackPressed()
                }
                await("Closing a modal restores an immersive reader") {
                    var ready = false
                    scenario.onActivity { ready = !open.value && it.window.decorView.hasWindowFocus() && barsHidden(it.window) }
                    ready
                }
            }
        }
    }

    @Test fun actionDismissalClosesThePanelBeforeLaunchingExactlyOneAction() {
        val open = mutableStateOf(true)
        val events = ArrayList<String>()
        var dismiss: ((() -> Unit) -> Unit)? = null
        ActivityScenario.launch(ReaderTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                ReaderTheme {
                    if (open.value) ReaderTopPanel("Export", {
                        events += "dismiss"
                        open.value = false
                    }) { close ->
                        SideEffect { dismiss = close }
                        Text("Export formats")
                    }
                }
            } }
            await("Panel content is ready") {
                var ready = false
                scenario.onActivity { ready = dismiss != null }
                ready
            }
            scenario.onActivity {
                requireNotNull(dismiss) { "Panel must expose animated dismissal" }.invoke { events += "export" }
                requireNotNull(dismiss).invoke { events += "duplicate" }
            }
            await("The exit transition completes the first action") {
                var done = false
                scenario.onActivity { done = events.size >= 2 }
                done
            }
            scenario.onActivity { assertEquals(listOf("dismiss", "export"), events) }
        }
    }

    private fun assertDialogChrome(scenario: ActivityScenario<ReaderTestActivity>, immersive: Boolean) {
        await("Focused dialog preserves immersive=$immersive") {
            var ready = false
            scenario.onActivity { activity ->
                val owner = WindowInspector.getGlobalWindowViews().filter { it.isShown && it.hasWindowFocus() }
                    .mapNotNull { it.findViewTreeOnBackPressedDispatcherOwner() as? ComponentDialog }.singleOrNull()
                val window = owner?.window
                val insets = window?.decorView?.let(ViewCompat::getRootWindowInsets)
                if (window != null && insets != null) {
                    ready = if (immersive) barsHidden(window) else insets.isVisible(WindowInsetsCompat.Type.statusBars())
                    if (ready) assertEquals("Modal must keep the reader's cutout mode", activity.window.attributes.layoutInDisplayCutoutMode,
                        window.attributes.layoutInDisplayCutoutMode)
                }
            }
            ready
        }
    }

    private fun barsHidden(window: Window): Boolean {
        val insets = ViewCompat.getRootWindowInsets(window.decorView) ?: return false
        return !insets.isVisible(WindowInsetsCompat.Type.statusBars()) && !insets.isVisible(WindowInsetsCompat.Type.navigationBars())
    }

    private fun configureHost(window: Window, immersive: Boolean) {
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = when {
                !immersive -> WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                Build.VERSION.SDK_INT >= 30 -> WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                else -> WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (immersive) hide(WindowInsetsCompat.Type.systemBars()) else show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun await(message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (predicate()) return
            SystemClock.sleep(40)
        }
        fail(message)
    }
}
