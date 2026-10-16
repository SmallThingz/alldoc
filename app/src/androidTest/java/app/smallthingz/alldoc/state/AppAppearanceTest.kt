package app.smallthingz.alldoc.state

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppAppearanceTest {
    @Test fun explicitAppThemesSurviveReopenAndRejectMainThreadIo() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val base = instrumentation.targetContext
        val name = "appearance-test-${UUID.randomUUID()}"
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(ignored: String, mode: Int) = base.getSharedPreferences(name, mode)
        }
        try {
            assertEquals(AppTheme.SYSTEM, AppAppearanceStore(context).read())
            for (theme in AppTheme.entries) {
                AppAppearanceStore(context).write(theme)
                assertEquals(theme, AppAppearanceStore(context).read())
            }
            base.getSharedPreferences(name, Context.MODE_PRIVATE).edit().putString("theme", "unknown").commit()
            assertEquals(AppTheme.SYSTEM, AppAppearanceStore(context).read())
            instrumentation.runOnMainSync {
                // Construction is cheap; reads and durable writes belong to the state worker.
                val store = AppAppearanceStore(context)
                assertThrows(IllegalStateException::class.java) { store.read() }
                assertThrows(IllegalStateException::class.java) { store.write(AppTheme.LIGHT) }
            }
        } finally { base.deleteSharedPreferences(name) }
    }
}
