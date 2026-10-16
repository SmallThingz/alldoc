package app.smallthingz.alldoc.engine

import android.graphics.Bitmap
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.smallthingz.alldoc.core.Block
import app.smallthingz.alldoc.core.Document
import app.smallthingz.alldoc.core.Section
import app.smallthingz.alldoc.theme.DocumentColors
import app.smallthingz.alldoc.theme.ReadingMode
import app.smallthingz.alldoc.theme.ThemeSettings
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EngineRenderingTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun descriptorSnapshotsStartAtByteZeroAndAdoptTheCallerHandle() {
        val file = File.createTempFile("alldoc-text-", ".md", context.cacheDir)
        val content = "Start of document\nEnglish, हिन्दी and 日本語.\nLast line."
        file.writeText(content)
        try {
            val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val descriptor = fd.fileDescriptor
            Os.lseek(descriptor, 10, OsConstants.SEEK_SET)
            DocumentEngine.open(context, fd, "example.md", "text/markdown", CancellationSignal()).use { session ->
                assertFalse(descriptor.valid())
                content.lineSequence().forEach { assertTrue(session.text(CancellationSignal()).contains(it)) }
                val image = session.renderPage(0, 320, ThemeSettings(), CancellationSignal())
                try {
                    assertEquals(320, image.width)
                    assertEquals(DocumentColors.palette(ThemeSettings()).background, image.getPixel(0, 0))
                } finally { image.recycle() }
            }
            assertEquals(content, file.readText())
        } finally { file.delete() }
    }

    @Test fun textPageCountAndGeometryAreStableAcrossRendering() {
        val content = (0 until 500).joinToString("\n") { "Line $it: English, हिन्दी and 日本語." }
        val document = Document("text", sections = listOf(Section("", blocks = listOf(Block("paragraph", content)))))
        TextSession("Long.html", "text/html", document, CancellationSignal()).use { session ->
            assertTrue(session.info.reflowable)
            assertEquals(0, session.info.pageCount)
            assertTrue(session.text(CancellationSignal()).contains(content))
            val pages = session.pageCount(CancellationSignal())
            assertTrue("Pages must include the entire document", pages > 1)
            assertEquals(pages, session.pageCount(CancellationSignal()))
            assertEquals("Intrinsic count stays lazy; callers use pageCount(signal)", 0, session.info.pageCount)
            assertEquals(900f / 640f, session.pageAspect(pages - 1, CancellationSignal()), .0001f)
            assertThrows(IllegalArgumentException::class.java) {
                session.pageAspect(pages, CancellationSignal())
            }
            assertThrows(OperationCanceledException::class.java) {
                session.pageAspect(0, CancellationSignal().apply { cancel() })
            }
            val image = session.renderPage(pages - 1, 320, ThemeSettings(ReadingMode.ORIGINAL), CancellationSignal())
            try {
                assertEquals(450, image.height)
                val pixels = IntArray(image.width * image.height)
                image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
                assertTrue("Last page must contain text", pixels.any { it != pixels[0] })
            } finally { image.recycle() }
            assertThrows(IllegalArgumentException::class.java) {
                session.renderPage(pages, 320, ThemeSettings(), CancellationSignal())
            }
        }
    }

    @Test fun cancelledOpenClosesItsDescriptorWithoutChangingTheSource() {
        val file = File.createTempFile("alldoc-cancel-", ".md", context.cacheDir)
        file.writeText("Keep this file.")
        try {
            val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val descriptor = fd.fileDescriptor
            try {
                DocumentEngine.open(context, fd, "example.md", "text/markdown", CancellationSignal().also { it.cancel() })
                fail("Canceled open returned a session")
            } catch (_: OperationCanceledException) {
                assertFalse(descriptor.valid())
                assertEquals("Keep this file.", file.readText())
            }
        } finally { file.delete() }
    }

    @Test fun invertTreatsEveryRenderedTextPixelIncludingColorEmoji() {
        val document = Document("text", sections = listOf(Section("", blocks = listOf(Block("paragraph", "AllDoc 🌈")))))
        TextSession("Emoji.html", "text/html", document, CancellationSignal()).use { session ->
            val original = session.renderPage(0, 640, ThemeSettings(ReadingMode.ORIGINAL), CancellationSignal())
            try {
                val inverted = session.renderPage(0, 640, ThemeSettings(ReadingMode.INVERT), CancellationSignal())
                try {
                    assertEquals(original.width, inverted.width)
                    assertEquals(original.height, inverted.height)
                    val before = IntArray(original.width * original.height)
                    val after = IntArray(before.size)
                    original.getPixels(before, 0, original.width, 0, 0, original.width, original.height)
                    inverted.getPixels(after, 0, inverted.width, 0, 0, inverted.width, inverted.height)
                    var colorful = false
                    for (index in before.indices) {
                        val pixel = before[index]
                        if ((pixel ushr 24) != (after[index] ushr 24)) fail("Alpha changed at pixel $index")
                        if ((pixel xor 0x00ffffff) != after[index]) fail("RGB was not inverted at pixel $index")
                        val r = pixel ushr 16 and 255
                        val g = pixel ushr 8 and 255
                        val b = pixel and 255
                        colorful = colorful || maxOf(r, g, b) - minOf(r, g, b) > 40
                    }
                    assertTrue("The emoji fixture must exercise colored glyph pixels", colorful)
                } finally { inverted.recycle() }
            } finally { original.recycle() }
        }
    }

    @Test fun engineThemingReleasesTemporarySourcesOnReplacementAndCancellation() {
        val original = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        original.eraseColor(-1)
        val kept = themed(original, ThemeSettings(ReadingMode.ORIGINAL), CancellationSignal())
        assertSame(original, kept)
        assertFalse(original.isRecycled)
        kept.recycle()

        val replaced = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        replaced.eraseColor(-1)
        val result = themed(replaced, ThemeSettings(), CancellationSignal())
        try { assertTrue(replaced.isRecycled); assertFalse(result.isRecycled) }
        finally { result.recycle() }

        val canceled = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        try {
            themed(canceled, ThemeSettings(), CancellationSignal().also { it.cancel() })
            fail("Canceled theming returned")
        } catch (_: OperationCanceledException) { assertTrue(canceled.isRecycled) }
    }
}
