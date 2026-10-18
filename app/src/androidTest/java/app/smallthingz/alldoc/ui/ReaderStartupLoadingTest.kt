package app.smallthingz.alldoc.ui

import android.net.Uri
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderStartupLoadingTest {
    @Test fun pendingResumeHidesHomeAndBackOrOpenWinsBeforeStoreReadCompletes(): Unit =
        ReaderPersistenceTest().Fixture().use { fixture ->
            val (savedHash, _) = fixture.retained("<html><body>Saved startup document</body></html>")
            assertTrue(fixture.context.getSharedPreferences("reader", 0).edit()
                .putString("activeDocument", savedHash).commit())
            val replacement = File(fixture.context.root, "chosen.html").apply { writeText("<html><body>Explicit new document</body></html>") }
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val worker = ReaderModel::class.java.getDeclaredField("StateWorker").let {
                it.isAccessible = true; it.get(null) as ExecutorService
            }
            var blocker: Future<*>? = null
            try {
                fixture.launch(resume = true, width = 0, beforeCreate = {
                    blocker = worker.submit { entered.countDown(); check(release.await(20, TimeUnit.SECONDS)) }
                    assertTrue(entered.await(5, TimeUnit.SECONDS))
                })
                launchReaderTestActivity().use { scenario ->
                    var opens = 0
                    scenario.onActivity { activity -> activity.setContent {
                        ReaderScreen(fixture.reader,
                            onOpen = { opens++; fixture.reader.open(Uri.fromFile(replacement)) },
                            onExport = { _, _ -> })
                    } }
                    fun awaitOpening() = await("Pending restoration did not expose the accessible opening surface") {
                        var shown = false
                        scenario.onActivity { activity ->
                            val nodes = activity.testSemantics()
                            val progress = find(nodes) {
                                "Opening document" in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                            }
                            shown = progress?.config?.getOrNull(SemanticsProperties.ProgressBarRangeInfo) ==
                                ProgressBarRangeInfo.Indeterminate
                            if (shown) {
                                assertTrue(fixture.reader.restoringLastDocument)
                                assertFalse(fixture.reader.readerVisible)
                                assertNull("Provisional Home controls must not be exposed to accessibility", find(nodes) {
                                    "Settings" in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                                })
                            }
                        }
                        shown
                    }
                    awaitOpening()
                    scenario.onActivity { activity ->
                        activity.onBackPressedDispatcher.onBackPressed()
                        assertFalse("Back must not await the blocked store", fixture.reader.restoringLastDocument)
                    }
                    await("Back did not return to interactive Home") {
                        var home = false
                        scenario.onActivity { activity ->
                            home = find(activity.testSemantics()) {
                                "Settings" in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                            } != null
                        }
                        home
                    }
                    scenario.onActivity {
                        assertEquals(0, opens)
                        assertNull(fixture.reader.source)
                        fixture.reader.restoreLastDocument()
                    }
                    awaitOpening()
                    scenario.onActivity { activity ->
                        val label = requireNotNull(find(activity.testSemantics()) {
                            it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == "Open document" }
                        })
                        val action = generateSequence(label) { it.parent }
                            .mapNotNull { it.config.getOrNull(SemanticsActions.OnClick)?.action }.firstOrNull()
                        assertTrue("The fresh Open action must be accepted", requireNotNull(action).invoke())
                        assertEquals(1, opens)
                        assertFalse(fixture.reader.restoringLastDocument)
                        assertTrue(fixture.reader.readerVisible)
                        assertTrue(fixture.reader.loading)
                        assertEquals(Uri.fromFile(replacement), fixture.reader.source)
                    }
                    release.countDown()
                    blocker!!.get(5, TimeUnit.SECONDS)
                    await("Late resume replaced the explicitly opened document") {
                        var ready = false
                        scenario.onActivity {
                            assertNull(fixture.reader.error)
                            ready = fixture.reader.info != null && !fixture.reader.loading
                        }
                        ready
                    }
                    fixture.stateBarrier()
                    scenario.onActivity {
                        assertFalse(fixture.reader.restoringLastDocument)
                        assertEquals(replacement.name, fixture.reader.currentTitle)
                        assertEquals(Uri.fromFile(replacement), fixture.reader.source)
                        assertNotEquals(savedHash, fixture.reader.documentHash)
                    }
                }
            } finally {
                release.countDown()
                blocker?.get(5, TimeUnit.SECONDS)
            }
        }

    private fun await(message: String, ready: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (!ready()) {
            check(SystemClock.uptimeMillis() < deadline) { message }
            SystemClock.sleep(16)
        }
    }

    private fun find(nodes: List<SemanticsNode>, predicate: (SemanticsNode) -> Boolean): SemanticsNode? {
        for (node in nodes) {
            if (node.config.isClearingSemantics) continue
            if (predicate(node)) return node
            find(node.children, predicate)?.let { return it }
        }
        return null
    }
}
