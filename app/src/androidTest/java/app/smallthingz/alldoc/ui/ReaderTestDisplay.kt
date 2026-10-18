package app.smallthingz.alldoc.ui

import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.Intent
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.activity.ComponentDialog
import androidx.activity.findViewTreeOnBackPressedDispatcherOwner
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.smallthingz.alldoc.ReaderTestActivity

// The controller owns and validates the display lifetime. No argument keeps the ordinary test launch.
private fun requestedTestDisplay(): Int? = InstrumentationRegistry.getArguments().getString("alldocDisplayId")?.let {
    requireNotNull(it.toIntOrNull()?.takeIf { id -> id > 0 }) { "alldocDisplayId must name an owned nondefault display" }
}

internal fun launchReaderTestActivity(): ActivityScenario<ReaderTestActivity> {
    val display = requestedTestDisplay() ?: return ActivityScenario.launch(ReaderTestActivity::class.java)
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    require(context.packageManager.hasSystemFeature(PackageManager.FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS))
    require(context.getSystemService(DisplayManager::class.java).getDisplay(display) != null) { "Test display no longer exists" }
    val intent = Intent(context, ReaderTestActivity::class.java)
    if (Build.VERSION.SDK_INT >= 29) require(context.getSystemService(ActivityManager::class.java).isActivityStartAllowedOnDisplay(context, display, intent)) {
        "Test activity cannot launch on the requested display"
    }
    val scenario = ActivityScenario.launch<ReaderTestActivity>(intent, ActivityOptions.makeBasic().setLaunchDisplayId(display).toBundle())
    try { scenario.onActivity { it.verifyTestDisplay() } }
    catch (failure: Throwable) { scenario.close(); throw failure }
    return scenario
}

internal fun ReaderTestActivity.verifyTestDisplay() {
    requestedTestDisplay()?.let { expected ->
        check(window.decorView.display?.displayId == expected) { "Test activity is not on its owned display" }
    }
}

/** Call on the Activity thread. Only inspect this Activity's windows, never system accessibility. */
internal fun ReaderTestActivity.testSemantics(includeDialogs: Boolean = false): List<SemanticsNode> {
    verifyTestDisplay()
    val host = window.decorView
    val token = host.applicationWindowToken ?: return emptyList()
    val activity = this
    fun owns(context: Context): Boolean {
        var current = context
        repeat(16) {
            if (current === activity) return true
            current = (current as? ContextWrapper)?.baseContext ?: return false
        }
        return false
    }
    val windows = if (includeDialogs) {
        check(Build.VERSION.SDK_INT >= 29) { "Process-local dialog inspection requires Android 10" }
        WindowInspector.getGlobalWindowViews().filter {
            it.isShown && it.display?.displayId == host.display?.displayId &&
                (it.applicationWindowToken == token ||
                    (it.findViewTreeOnBackPressedDispatcherOwner() as? ComponentDialog)?.context?.let(::owns) == true)
        }
    } else listOf(host)
    fun root(view: View): ViewRootForTest? = (view as? ViewRootForTest) ?: (view as? ViewGroup)?.let {
        (0 until it.childCount).firstNotNullOfOrNull { index -> root(it.getChildAt(index)) }
    }
    return windows.mapNotNull { root(it)?.semanticsOwner?.unmergedRootSemanticsNode }
}
