package app.smallthingz.alldoc.ui

import android.app.Application
import android.content.SharedPreferences
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.smallthingz.alldoc.theme.ReadingMode
import app.smallthingz.alldoc.theme.ThemeSettings
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderPreferencesTest {
    @Test fun slowPreferencesNeverBlockUiOrReplaceNewChoicesAndWritesStayOrdered() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val preferences = instrumentation.targetContext.getSharedPreferences("appearance-regression", 0)
        assertTrue(preferences.edit().clear().putString("mode", "SEPIA").commit())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val mainAccess = AtomicBoolean(false)
        val application = object : Application() {
            init { attachBaseContext(instrumentation.targetContext) }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    mainAccess.set(true)
                    throw IllegalStateException("Preferences accessed on the UI thread")
                }
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                return preferences
            }
        }
        val store = ViewModelStore()
        lateinit var model: ReaderModel
        try {
            instrumentation.runOnMainSync {
                model = ReaderModel(application)
                store.put("reader", model)
            }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            // An explicit choice matching the initial default must also defeat the delayed load.
            instrumentation.runOnMainSync {
                assertEquals(ReadingMode.SYSTEM, model.appearanceForControls.mode)
                model.setAppearance(ThemeSettings(ReadingMode.SYSTEM))
            }
            release.countDown()
            await { preferences.getString("mode", null) == "SYSTEM" }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertEquals(ReadingMode.SYSTEM, model.settings.mode)
                assertEquals(ReadingMode.SYSTEM, model.appearanceForControls.mode)
                model.setAppearance(ThemeSettings(ReadingMode.INVERT))
                model.setAppearance(ThemeSettings(ReadingMode.SEPIA))
                model.setAppearance(ThemeSettings(ReadingMode.HIGH_CONTRAST, false))
            }
            await { preferences.getString("mode", null) == "HIGH_CONTRAST" }
            assertEquals("RECOLOR", preferences.getString("imageTreatment", null))
            instrumentation.runOnMainSync {
                store.clear()
                model = ReaderModel(application)
                store.put("reader", model)
            }
            await {
                var restored = false
                instrumentation.runOnMainSync {
                    restored = model.settings == ThemeSettings(ReadingMode.HIGH_CONTRAST, false)
                }
                restored
            }
            assertFalse("No preference access may run on the UI thread", mainAccess.get())
        } finally {
            release.countDown()
            instrumentation.runOnMainSync { store.clear() }
            preferences.edit().clear().commit()
        }
    }

    @Test fun failedDefaultWriteRollsBackControlsWithoutReplacingANewerChoice() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val preferences = instrumentation.targetContext.getSharedPreferences("appearance-failure-regression", 0)
        assertTrue(preferences.edit().clear().putString("mode", "SEPIA").commit())
        val failNext = AtomicBoolean()
        val blockFailure = AtomicBoolean()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val failing = MemoryFirstPreferences(preferences, failNext) {
            if (blockFailure.get()) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        }
        val application = object : Application() {
            init { attachBaseContext(instrumentation.targetContext) }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                check(Looper.myLooper() != Looper.getMainLooper())
                return failing
            }
        }
        val store = ViewModelStore()
        lateinit var model: ReaderModel
        fun awaitMode(mode: ReadingMode) = await {
            var matched = false
            instrumentation.runOnMainSync { matched = model.appearanceForControls.mode == mode && model.settings.mode == mode }
            matched
        }
        val initializationEntered = CountDownLatch(1)
        val initialize = CountDownLatch(1)
        val field = ReaderModel::class.java.getDeclaredField("StateWorker").apply { isAccessible = true }
        (field.get(null) as ExecutorService).execute {
            initializationEntered.countDown()
            check(initialize.await(5, TimeUnit.SECONDS))
        }
        try {
            assertTrue(initializationEntered.await(3, TimeUnit.SECONDS))
            failNext.set(true)
            instrumentation.runOnMainSync {
                model = ReaderModel(application)
                store.put("reader", model)
                model.setAppearance(ThemeSettings(ReadingMode.INVERT))
            }
            initialize.countDown()
            awaitMode(ReadingMode.SEPIA)
            assertFalse("The appearance commit must consume the injected failure", failNext.get())
            instrumentation.runOnMainSync { assertEquals("Could not save appearance.", model.notice) }
            assertEquals("SEPIA", preferences.getString("mode", null))
            assertEquals("SEPIA", failing.getString("mode", null))
            instrumentation.runOnMainSync {
                store.clear()
                model = ReaderModel(application)
                store.put("reader", model)
            }
            awaitMode(ReadingMode.SEPIA)
            blockFailure.set(true)
            failNext.set(true)
            instrumentation.runOnMainSync { model.setAppearance(ThemeSettings(ReadingMode.ORIGINAL)) }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            instrumentation.runOnMainSync { model.setAppearance(ThemeSettings(ReadingMode.HIGH_CONTRAST, false)) }
            release.countDown()
            await { preferences.getString("mode", null) == "HIGH_CONTRAST" }
            awaitMode(ReadingMode.HIGH_CONTRAST)
            assertEquals("RECOLOR", preferences.getString("imageTreatment", null))
        } finally {
            initialize.countDown()
            release.countDown()
            instrumentation.runOnMainSync { store.clear() }
            preferences.edit().clear().commit()
        }
    }

    @Test fun failedResumeWriteRestoresMemoryAndRetriesTheSameHomeTarget() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val durable = instrumentation.targetContext.getSharedPreferences("resume-failure-regression", 0)
        assertTrue(durable.edit().clear().commit())
        val failNext = AtomicBoolean()
        val preferences = MemoryFirstPreferences(durable, failNext)
        val application = object : Application() {
            init { attachBaseContext(instrumentation.targetContext) }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                check(Looper.myLooper() != Looper.getMainLooper())
                return preferences
            }
        }
        val owners = ViewModelStore()
        lateinit var model: ReaderModel
        fun stateBarrier() {
            val done = CountDownLatch(1)
            val field = ReaderModel::class.java.getDeclaredField("StateWorker").apply { isAccessible = true }
            (field.get(null) as ExecutorService).execute { done.countDown() }
            assertTrue("State worker did not finish", done.await(5, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
        }
        try {
            instrumentation.runOnMainSync { model = ReaderModel(application); owners.put("reader", model) }
            stateBarrier()
            await {
                var idle = false
                instrumentation.runOnMainSync { idle = !model.storageBusy }
                idle
            }
            stateBarrier()
            val previous = "a".repeat(64)
            assertTrue(preferences.edit().putString("activeDocument", previous).commit())
            failNext.set(true)
            instrumentation.runOnMainSync { model.showReader(false) }
            stateBarrier()
            assertEquals(previous, durable.getString("activeDocument", null))
            assertEquals("A false commit must not leave an unacknowledged memory value", previous,
                preferences.getString("activeDocument", null))
            instrumentation.runOnMainSync {
                assertEquals("Reading settings could not be saved.", model.notice)
                model.showReader(false)
            }
            stateBarrier()
            assertNull("Repeating Home must retry and persist the same null target", durable.getString("activeDocument", null))
            assertNull(preferences.getString("activeDocument", null))
        } finally {
            instrumentation.runOnMainSync { owners.clear() }
            durable.edit().clear().commit()
        }
    }

    /** Model Android's commit-to-memory before a separately failing disk write. */
    private class MemoryFirstPreferences(private val durable: SharedPreferences, private val failNext: AtomicBoolean,
        private val beforeFailure: () -> Unit = {}) : SharedPreferences by durable {
        private val memory = HashMap<String, Any?>(durable.all)
        override fun getString(key: String, defValue: String?): String? = synchronized(memory) { memory[key] as? String ?: defValue }
        override fun getBoolean(key: String, defValue: Boolean): Boolean = synchronized(memory) { memory[key] as? Boolean ?: defValue }
        override fun getInt(key: String, defValue: Int): Int = synchronized(memory) { memory[key] as? Int ?: defValue }
        override fun contains(key: String): Boolean = synchronized(memory) { memory.containsKey(key) }
        override fun getAll(): MutableMap<String, *> = synchronized(memory) { HashMap(memory) }
        override fun edit(): SharedPreferences.Editor {
            val editor = durable.edit()
            val changes = LinkedHashMap<String, Any?>()
            return object : SharedPreferences.Editor by editor {
                override fun putString(key: String, value: String?): SharedPreferences.Editor {
                    changes[key] = value; editor.putString(key, value); return this
                }
                override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
                    changes[key] = value; editor.putBoolean(key, value); return this
                }
                override fun putInt(key: String, value: Int): SharedPreferences.Editor {
                    changes[key] = value; editor.putInt(key, value); return this
                }
                override fun commit(): Boolean {
                    synchronized(memory) { changes.forEach { (key, value) -> if (value == null) memory.remove(key) else memory[key] = value } }
                    if (failNext.getAndSet(false)) { beforeFailure(); return false }
                    return editor.commit()
                }
                override fun apply() { commit() }
            }
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(20)
        }
        fail("Appearance state did not settle")
    }
}
