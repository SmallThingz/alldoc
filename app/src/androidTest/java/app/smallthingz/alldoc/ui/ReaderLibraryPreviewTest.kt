package app.smallthingz.alldoc.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.smallthingz.alldoc.state.LibraryDocument
import app.smallthingz.alldoc.theme.ReadingMode
import app.smallthingz.alldoc.theme.ThemeSettings
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderLibraryPreviewTest {
    @Test fun outgoingCoversSurviveEmptyDemandWithoutRetainingStaleThemesOrWindows() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as Application
        val folder = File(application.cacheDir, "library-retention-${UUID.randomUUID()}").apply { mkdirs() }
        val source = File(folder, "cover.md").apply { writeText("# ${UUID.randomUUID()}") }
        val hash = MessageDigest.getInstance("SHA-256").digest(source.readBytes()).toHexString()
        val appearance = ThemeSettings(ReadingMode.ORIGINAL)
        val image = Bitmap.createBitmap(LibraryPreviews.WIDTH, LibraryPreviews.HEIGHT, Bitmap.Config.ARGB_8888)
            .apply { eraseColor(Color.MAGENTA) }
        val memory = PreviewMemory.forApplication(application)
        val key = PreviewMemory.Key("library:$hash:text/markdown", LibraryPreviews.WIDTH, LibraryPreviews.HEIGHT, appearance)
        memory.put(key, image, memory.generation())
        val document = LibraryDocument(91001L, hash, source.name, "text/markdown", source.length(),
            Uri.fromFile(source).toString(), 1L, 1L)
        // Queue catalog jobs until this main-thread transition finishes; no device scan is needed.
        val parent = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        try {
            instrumentation.runOnMainSync {
                val library = ReaderLibrary(application, parent) {}
                try {
                    library.setActive(true)
                    library.setVisible(listOf(document))
                    assertSame(image, library.previews[document.id])

                    library.setActive(false)
                    library.setVisible(emptyList())
                    assertSame("Outgoing page must keep its pixels after currentPage changes", image, library.previews[document.id])
                    assertFalse(image.isRecycled)

                    library.setActive(true)
                    library.setVisible(listOf(document))
                    assertSame("Returning to Library reuses the immutable warm cover", image, library.previews[document.id])
                    library.setVisible(emptyList())
                    library.setActive(false)
                    assertSame("Callback ordering must not erase outgoing covers", image, library.previews[document.id])

                    library.setAppearance(ThemeSettings(ReadingMode.INVERT))
                    assertTrue("A retained light cover must not leak into a different appearance", library.previews.isEmpty())
                    library.setAppearance(appearance)
                    library.setVisible(listOf(document))
                    assertSame(image, library.previews[document.id])

                    val replacement = document.copy(id = document.id + 1, revision = document.revision + 1)
                    library.setVisible(listOf(replacement))
                    assertEquals("Only the newest visible window is retained", setOf(replacement.id), library.previews.keys)
                    assertSame(image, library.previews[replacement.id])
                    library.setVisible(listOf(replacement.copy(hash = "f".repeat(64))))
                    assertTrue("Changed content cannot retain a previous hash's pixels", library.previews.isEmpty())
                } finally {
                    library.close()
                    assertTrue(library.previews.isEmpty())
                }
            }
        } finally {
            parent.cancel()
            memory.clear("library:$hash:")
            // Published images are GC-owned: a canceled worker or Compose may still hold a reference.
            folder.deleteRecursively()
        }
    }
}
