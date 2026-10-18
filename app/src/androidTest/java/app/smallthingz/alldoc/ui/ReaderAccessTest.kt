package app.smallthingz.alldoc.ui

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import app.smallthingz.alldoc.sources.LibraryAccess
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Permission probes are injected; these tests never launch Settings or grant device access. */
class ReaderAccessTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val application get() = ApplicationProvider.getApplicationContext<Application>()
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)

    @Test fun introductionIsOptionalHomeOnlyAndItsDismissalSurvivesAnotherOwner() = fixture { permission, create ->
        val model = create()
        main {
            assertTrue(model.shouldIntroduce(home = true, external = false, reading = false))
            assertFalse(model.shouldIntroduce(home = true, external = true, reading = false))
            assertFalse(model.shouldIntroduce(home = true, external = false, reading = true))
            assertFalse(model.shouldIntroduce(home = false, external = false, reading = false))
            model.introductionShown()
            assertTrue("A denied request can remain on the introduction", model.offering)
            model.dismissIntroduction()
            assertFalse(model.offering)
            assertFalse("Skipping does not grant access", permission.get())
        }
        val restored = create()
        main { assertFalse(restored.offering); assertFalse(restored.granted) }
    }

    @Test fun denialCanRetryAndResumeTracksActualProbeGrantAndRevocation() = fixture { permission, create ->
        val model = create()
        main {
            assertTrue(model.beginRequest(legacy = true))
            assertFalse("Do not launch a second permission screen", model.beginRequest(legacy = true))
            assertTrue(model.legacyRequested)
            assertFalse(model.returned())
            assertFalse(model.requesting)
            assertFalse(model.granted)
            assertTrue(model.message!!.contains("not granted"))
            assertTrue(model.beginRequest(legacy = true))
            model.launchFailed()
            assertFalse(model.requesting)
            assertTrue(model.message!!.contains("unavailable"))
            assertTrue(model.beginRequest(legacy = true))
            permission.set(true)
            assertTrue(model.refresh())
            assertTrue(model.granted)
            assertFalse(model.offering)
            assertFalse(model.requesting)
            assertNull(model.message)
            permission.set(false)
            assertTrue(model.refresh())
            assertFalse(model.granted)
            assertFalse("Revocation restores the Settings action without repeating onboarding", model.offering)
        }
        val restored = create()
        main {
            assertTrue("Permanent legacy denial can route the next attempt to app settings", restored.legacyRequested)
            assertFalse("A previous grant does not repeat onboarding after revocation", restored.offering)
        }
    }

    @Test fun alreadyGrantedAccessSkipsIntroduction() = fixture(initiallyGranted = true) { permission, create ->
        val model = create()
        main { assertTrue(model.granted); assertFalse(model.offering); assertFalse(model.beginRequest(false)) }
        permission.set(false)
        val restored = create()
        main { assertFalse(restored.granted); assertFalse(restored.offering) }
    }

    @Test fun settingsLaunchUsesSystemRoutesAndFallsBackAfterEitherUnavailableFailure() {
        for (security in listOf(false, true)) {
            val intents = ArrayList<Intent>()
            assertTrue(LibraryAccess.openSettings("app.smallthingz.alldoc", true) { intent ->
                intents += intent
                if (intents.size == 1) {
                    if (security) throw SecurityException("Unavailable") else throw ActivityNotFoundException("Unavailable")
                }
            })
            assertEquals(listOf(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION), intents.map { it.action })
            assertEquals("package:app.smallthingz.alldoc", intents[0].data.toString())
            assertNull(intents[1].data)
        }
        var attempts = 0
        assertFalse(LibraryAccess.openSettings("app.smallthingz.alldoc", true) { attempts++; throw ActivityNotFoundException() })
        assertEquals(2, attempts)
        assertTrue(LibraryAccess.openSettings("app.smallthingz.alldoc", false) {
            assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, it.action)
            assertEquals("package:app.smallthingz.alldoc", it.data.toString())
        })
    }

    private fun fixture(initiallyGranted: Boolean = false, action: (AtomicBoolean, () -> ReaderAccessModel) -> Unit) {
        val name = "access-test-" + UUID.randomUUID()
        val permission = AtomicBoolean(initiallyGranted)
        val stores = ArrayList<ViewModelStore>()
        try {
            action(permission) {
                lateinit var model: ReaderAccessModel
                main {
                    model = ReaderAccessModel(application, permission::get, name)
                    stores += ViewModelStore().apply { put("access", model) }
                }
                val deadline = SystemClock.uptimeMillis() + 5000
                var ready = false
                while (!ready) {
                    main { ready = model.ready }
                    check(SystemClock.uptimeMillis() < deadline) { "Access preferences did not load" }
                    if (!ready) Thread.sleep(10)
                }
                model
            }
        } finally {
            main { stores.forEach { it.clear() } }
            runBlocking { ReaderModel.orderedStateRead { application.deleteSharedPreferences(name) } }
        }
    }
}
