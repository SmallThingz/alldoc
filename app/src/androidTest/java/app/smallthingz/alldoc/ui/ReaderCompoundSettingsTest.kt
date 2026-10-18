package app.smallthingz.alldoc.ui

import android.database.sqlite.SQLiteDatabase
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.activity.compose.setContent
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import app.smallthingz.alldoc.state.CopySettings
import app.smallthingz.alldoc.theme.ImageTreatment
import app.smallthingz.alldoc.theme.ReadingMode
import app.smallthingz.alldoc.theme.ThemeSettings
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderCompoundSettingsTest {
    @Test @SdkSuppress(minSdkVersion = 29)
    fun pendingCompoundControlsKeepSavedCompanionFields() = ReaderPersistenceTest().Fixture().use { fixture ->
        val appearance = ThemeSettings(ReadingMode.SEPIA, ImageTreatment.KEEP, 67)
        val copies = CopySettings(false, 768L * 1024 * 1024, 37, "content://storage/tree/docs")
        val preferences = fixture.context.getSharedPreferences("reader", 0)
        assertTrue(preferences.edit().putString("mode", appearance.mode.name)
            .putString("imageTreatment", appearance.imageTreatment.name).putInt("paperRoughness", 67).commit())
        fixture.store.saveCopySettings(copies)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var blocker: Future<*>? = null
        val worker = ReaderModel::class.java.getDeclaredField("StateWorker").let {
            it.isAccessible = true; it.get(null) as ExecutorService
        }
        try {
            fixture.launch(width = 0, beforeCreate = {
                blocker = worker.submit { entered.countDown(); check(release.await(15, TimeUnit.SECONDS)) }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
            })
            launchReaderTestActivity().use { scenario ->
                val limits = mutableStateOf(false)
                val view = AtomicReference<View>()
                scenario.onActivity { activity -> activity.setContent {
                    val current = LocalView.current
                    SideEffect { view.set(current) }
                    ReaderTheme {
                        if (limits.value) {
                            // Mount the same panel that a saved "limits" overlay restores during cold startup.
                            ReaderPanels(fixture.reader, fixture.reader.documentId, "limits",
                                onDismiss = { limits.value = false }, onCloseSearch = {})
                        } else ReaderScreen(fixture.reader, onOpen = {}, onExport = { _, _ -> })
                    }
                } }
                fun choice(label: String, nodes: List<SemanticsNode>) = find(nodes) {
                    label in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                }
                fun openSettings() = await("Settings did not open") {
                    var ready = false
                    scenario.onActivity { activity ->
                        val nodes = activity.testSemantics()
                        ready = choice("App theme: Dark", nodes) != null
                        if (!ready) choice("Settings", nodes)?.let { label ->
                            // The unmerged label belongs to the Icon; its IconButton owns the action.
                            val click = generateSequence(label) { it.parent }
                                .mapNotNull { it.config.getOrNull(SemanticsActions.OnClick)?.action }.firstOrNull()
                            assertNotNull("The Settings label has no owning click action", click)
                            assertTrue("The Settings action was not accepted", requireNotNull(click).invoke())
                        }
                    }
                    ready
                }
                openSettings()
                await("Document appearance control is not visible") {
                    var visible = false
                    scenario.onActivity { activity ->
                        val node = choice("Dark", activity.testSemantics())
                        if (node != null) {
                            visible = node.boundsInRoot.width >= 24 && node.boundsInRoot.height >= 24
                            if (!visible) {
                                var parent: SemanticsNode? = node
                                while (parent != null && parent.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) == null) parent = parent.parent
                                parent?.config?.getOrNull(SemanticsActions.ScrollBy)?.action?.invoke(0f, 120f)
                            }
                        }
                    }
                    visible
                }
                scenario.onActivity { activity ->
                    val nodes = activity.testSemantics()
                    val dark = requireNotNull(choice("Dark", nodes))
                    assertFalse(fixture.reader.compoundSettingsReady)
                    assertNotNull(dark.config.getOrNull(SemanticsProperties.Disabled))
                    assertNull("The independent app theme remains available",
                        requireNotNull(choice("App theme: Dark", nodes)).config.getOrNull(SemanticsProperties.Disabled))
                    val offset = IntArray(2).also { requireNotNull(view.get()).getLocationInWindow(it) }
                    val point = dark.boundsInRoot.center
                    val now = SystemClock.uptimeMillis()
                    for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                        val event = MotionEvent.obtain(now, now + if (action == MotionEvent.ACTION_UP) 20 else 0,
                            action, point.x + offset[0], point.y + offset[1], 0)
                        try { activity.dispatchTouchEvent(event) } finally { event.recycle() }
                    }
                    assertEquals(ThemeSettings(), fixture.reader.appearanceForControls)
                    limits.value = true
                }
                await("Pending restored storage slider is not disabled") {
                    var disabled = false
                    scenario.onActivity { activity -> disabled = choice("Storage limit", activity.testSemantics(true))?.let {
                        it.config.getOrNull(SemanticsProperties.Disabled) != null
                    } == true }
                    disabled
                }
                release.countDown()
                blocker!!.get(5, TimeUnit.SECONDS)
                await("Compound settings did not hydrate before enabling storage") {
                    var ready = false
                    scenario.onActivity { activity -> ready = fixture.reader.compoundSettingsReady && !fixture.reader.storageBusy &&
                        fixture.reader.copySettings == copies && fixture.reader.appearanceForControls == appearance &&
                        choice("Storage limit", activity.testSemantics(true))?.let {
                            it.config.getOrNull(SemanticsProperties.Disabled) == null
                        } == true }
                    ready
                }
                scenario.onActivity { activity ->
                    val slider = requireNotNull(choice("Storage limit", activity.testSemantics(true)))
                    assertTrue(slider.config.getOrNull(SemanticsActions.SetProgress)?.action?.invoke(512f) == true)
                }
                val expectedCopies = copies.copy(maxBytes = 512L * 1024 * 1024)
                await("Storage limit discarded a saved companion field") {
                    var matched = false
                    fixture.act { matched = it.copySettings == expectedCopies }
                    matched
                }
                fixture.stateBarrier()
                assertEquals(expectedCopies, fixture.store.copySettings())
                scenario.onActivity { limits.value = false }
                openSettings()
                await("Hydrated default appearance did not enable") {
                    var selected = false
                    scenario.onActivity { activity ->
                        val node = choice("Dark", activity.testSemantics())
                        if (node != null && node.config.getOrNull(SemanticsProperties.Disabled) == null)
                            selected = node.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke() == true
                    }
                    selected
                }
                fixture.act { assertEquals(appearance.copy(mode = ReadingMode.ADAPTIVE), it.appearanceForControls) }
                fixture.stateBarrier()
                assertEquals("ADAPTIVE", preferences.getString("mode", null))
                assertEquals("KEEP", preferences.getString("imageTreatment", null))
                assertEquals(67, preferences.getInt("paperRoughness", -1))
            }
        } finally {
            release.countDown()
            blocker?.get(15, TimeUnit.SECONDS)
        }
    }

    @Test fun failedCompoundReadsPublishUsableFallbacks() = ReaderPersistenceTest().Fixture().use { fixture ->
        // Both real stores fail their startup reads; neither failure may leave readiness false forever.
        assertTrue(fixture.context.getSharedPreferences("reader", 0).edit().putInt("mode", 1).commit())
        fixture.store.saveCopySettings(CopySettings())
        SQLiteDatabase.openDatabase(fixture.context.getDatabasePath("documents.db").path, null,
            SQLiteDatabase.OPEN_READWRITE).use { it.execSQL("DELETE FROM settings WHERE id=1") }
        fixture.launch(width = 0)
        await("Failed reads did not publish valid fallbacks") {
            var ready = false
            fixture.act { ready = it.compoundSettingsReady }
            ready
        }
        fixture.act {
            assertEquals(ThemeSettings(), it.appearanceForControls)
            assertEquals(CopySettings(), it.copySettings)
            assertFalse(it.storageBusy)
            assertNotNull(it.notice)
        }
    }

    private fun await(message: String, ready: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 5_000
        while (!ready()) {
            check(SystemClock.uptimeMillis() < end) { message }
            SystemClock.sleep(16)
        }
    }

    private fun find(nodes: List<SemanticsNode>, predicate: (SemanticsNode) -> Boolean): SemanticsNode? {
        for (node in nodes) {
            if (predicate(node)) return node
            find(node.children, predicate)?.let { return it }
        }
        return null
    }
}
