package app.smallthingz.alldoc.ui

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.smallthingz.alldoc.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderRestorationTest {
    private fun fixture(name: String) = Uri.parse("content://app.smallthingz.alldoc.test.reader-fixtures/$name")

    @Test fun cancelledUriStaysCancelledAfterFreshModelRestoration() = verifyCancelledRestore()

    private fun verifyCancelledRestore() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW).setDataAndType(fixture("one"), "text/html")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            awaitDocument(scenario)
            val expectedSource = fixture("slow")
            scenario.onActivity { activity ->
                val model = ViewModelProvider(activity)[ReaderModel::class.java]
                model.open(fixture("slow"))
                assertTrue(model.loading)
                model.cancelLoading()
                assertEquals(expectedSource, model.source)
            }
            // Exercise real Activity saved-state callbacks with a fresh owner, without killing
            // the instrumentation process. Repeating proves the cancellation marker survives.
            repeat(2) {
                var previous: ReaderModel? = null
                scenario.onActivity { activity ->
                    previous = ViewModelProvider(activity)[ReaderModel::class.java]
                    activity.viewModelStore.clear()
                }
                scenario.recreate()
                scenario.onActivity { activity ->
                    val restored = ViewModelProvider(activity)[ReaderModel::class.java]
                    assertNotSame(previous, restored)
                    assertTrue(restored.openingCancelled)
                    assertEquals(expectedSource, restored.source)
                    assertFalse(restored.loading)
                    assertNull(restored.info)
                    assertNull(restored.error)
                }
            }
            scenario.onActivity { activity ->
                val model = ViewModelProvider(activity)[ReaderModel::class.java]
                model.open(fixture("one"))
                assertFalse(model.openingCancelled)
            }
            awaitDocument(scenario)
        }
    }

    private fun awaitDocument(scenario: ActivityScenario<MainActivity>) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false
            scenario.onActivity { activity ->
                val model = ViewModelProvider(activity)[ReaderModel::class.java]
                assertNull(model.error)
                ready = model.info?.title == "one.html" && !model.loading
            }
            if (ready) return
            SystemClock.sleep(25)
        }
        fail("A fresh explicit open did not complete")
    }
}
