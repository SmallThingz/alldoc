package app.smallthingz.alldoc.ui

import android.content.ComponentCallbacks2
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.smallthingz.alldoc.sources.LibrarySourceMissingException
import app.smallthingz.alldoc.state.LibraryDocument
import app.smallthingz.alldoc.theme.ReadingMode
import app.smallthingz.alldoc.theme.ThemeSettings
import java.io.File
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PreviewMemoryTest {
    private class Queue : Executor {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.addLast(command) }
        fun drain() { var count = 0; while (tasks.isNotEmpty()) { check(count++ < 20); tasks.removeFirst().run() } }
    }
    private val original = ThemeSettings(ReadingMode.ORIGINAL)
    private val inverted = ThemeSettings(ReadingMode.INVERT)
    private val hash = "1".repeat(64)
    private fun image(color: Int = Color.WHITE) = Bitmap.createBitmap(12, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
    private fun directory() = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "preview-memory-${UUID.randomUUID()}")

    @Suppress("DEPRECATION")
    @Test fun boundedLruKeepsDisplayedImagesAliveAndSeparatesSizeAndAppearance() {
        val pictures = List(4) { image() }
        val weight = pictures.first().allocationByteCount.toLong()
        val cache = PreviewMemory(2 * weight)
        val first = PreviewMemory.Key("recent:$hash", 144, 192, original)
        val second = first.copy(appearance = inverted)
        val third = first.copy(width = 240, height = 320)
        try {
            cache.put(first, pictures[0]); cache.put(second, pictures[1])
            assertSame(pictures[0], cache[first]) // The touched cover survives the next eviction.
            cache.put(third, pictures[2])
            assertNull(cache[second]); assertSame(pictures[0], cache[first]); assertSame(pictures[2], cache[third])
            assertEquals(2 * weight, cache.bytes)
            assertFalse("Eviction must not recycle a bitmap still held by Compose", pictures[1].isRecycled)
            cache.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
            assertSame("Opening another screen must not flush covers", pictures[0], cache[first])
            cache.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
            assertEquals(weight, cache.bytes)
            assertSame(pictures[0], cache[first])
            cache.put(second, pictures[3]); pictures[3].recycle()
            assertNull(cache[second])
            assertEquals(weight, cache.bytes)
            cache.onLowMemory()
            assertEquals(0L, cache.bytes); assertNull(cache[first])
            assertFalse(pictures[0].isRecycled)
        } finally { cache.onLowMemory(); pictures.forEach { if (!it.isRecycled) it.recycle() } }
    }

    @Test fun oversizedAndTinyCoversCannotEscapeTheMemoryAndEntryBounds() {
        val small = image(); val large = Bitmap.createBitmap(24, 32, Bitmap.Config.ARGB_8888)
        val cache = PreviewMemory(small.allocationByteCount.toLong())
        val key = PreviewMemory.Key("recent:$hash", 144, 192, original)
        val tiny = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val many = PreviewMemory(1L shl 20)
        try {
            cache.put(key, large); assertNull(cache[key]); assertEquals(0L, cache.bytes)
            val pending = cache.generation()
            cache.clear("recent:")
            cache.put(key, small, pending)
            assertNull("An old owner cannot repopulate a cache cleared by another owner", cache[key])
            for (index in 0..512) many.put(key.copy(document = "recent:$index"), tiny)
            assertNull(many[key.copy(document = "recent:0")])
            assertSame(tiny, many[key.copy(document = "recent:512")])
            assertEquals(512L * tiny.allocationByteCount, many.bytes)
            assertFalse("A rejected bitmap remains owned by its caller", large.isRecycled)
        } finally { cache.onLowMemory(); many.onLowMemory(); small.recycle(); large.recycle(); tiny.recycle() }
    }

    @Test fun libraryReturnAndNewOwnerReuseTheSameCoverWithoutDecodeOrRendering() {
        val folder = directory(); val queue = Queue(); val memory = PreviewMemory(1L shl 20)
        val document = LibraryDocument(1, hash, "one.pdf", "application/pdf", 8, "content://preview/one", 1, 1)
        val delivered = mutableListOf<Bitmap>()
        var renders = 0; var directoryReads = 0; var validations = 0; var missing = false; var missingCalls = 0
        fun owner() = LibraryPreviews({ directoryReads++; folder }, { doc, _ ->
            renders++
            LibraryPreviews.Preview(requireNotNull(doc.hash), image(), doc.mimeType)
        }, queue, onSourceMissing = { missingCalls++ }, validate = {
            validations++; if (missing) throw LibrarySourceMissingException()
        }, memory = memory) { _, image, _ -> delivered += image }
        val first = owner(); val second = owner()
        try {
            // Both consumers queue before the first completes; the shared serial worker deduplicates the load.
            first.setVisible(listOf(document)); second.setVisible(listOf(document))
            queue.drain()
            assertEquals(1, renders); assertEquals(1, directoryReads); assertEquals(2, validations)
            assertEquals(2, delivered.size); assertSame(delivered[0], delivered[1])
            first.setVisible(emptyList(), active = false)
            assertSame(delivered[0], first.cached(document))
            first.setVisible(listOf(document)); queue.drain()
            assertEquals(1, renders); assertEquals(1, directoryReads)
            assertSame(delivered[0], delivered.last())
            first.setAppearance(inverted); queue.drain()
            val dark = delivered.last()
            assertEquals(Color.BLACK, dark.getPixel(0, 0)); assertEquals(1, renders)
            assertEquals(Color.WHITE, delivered.first().getPixel(0, 0))
            first.setAppearance(original)
            assertSame(delivered[0], first.cached(document))
            queue.drain()
            assertSame(delivered[0], delivered.last())
            val count = delivered.size
            first.setVisible(emptyList(), active = false); first.setVisible(listOf(document))
            first.setVisible(emptyList(), active = false); queue.drain()
            assertEquals("Cancelled queued work cannot publish a warm cover", count, delivered.size)
            missing = true
            first.setVisible(listOf(document)); queue.drain()
            assertEquals("A warm cache cannot skip missing-source validation", 1, missingCalls)
            assertEquals(count, delivered.size)
        } finally {
            first.close(); second.close(); queue.drain(); memory.onLowMemory()
            delivered.distinct().forEach { it.recycle() }; folder.deleteRecursively()
        }
    }

    @Test fun replacementDuringAnOldThemeLoadCannotLeaveItsOldPageCached() {
        val folder = directory(); val queue = Queue(); val memory = PreviewMemory(1L shl 20)
        val source = image(); val replacement = image(Color.BLUE); val delivered = mutableListOf<Bitmap>()
        var reads = 0
        lateinit var cache: DocumentPreviews
        cache = DocumentPreviews({
            if (++reads == 2) cache.savePage(hash, replacement, Color.WHITE)
            folder
        }, queue, memory) { _, bitmap, _ ->
            delivered += bitmap
            if (delivered.size == 2) cache.setAppearance(original)
        }
        try {
            cache.savePage(hash, source, Color.WHITE); queue.drain()
            cache.setAppearance(inverted); cache.request(hash); queue.drain()
            assertEquals("The newer page drains after the old read completes", Color.BLUE, delivered.last().getPixel(0, 0))
            cache.setAppearance(inverted)
            assertNull("The invalidated old in-flight theme must not survive replacement", cache.cached(hash))
            cache.request(hash); queue.drain()
            assertEquals(Color.YELLOW, delivered.last().getPixel(0, 0))
        } finally {
            cache.close(); queue.drain(); memory.onLowMemory(); source.recycle(); replacement.recycle()
            delivered.distinct().forEach { it.recycle() }; folder.deleteRecursively()
        }
    }

    @Test fun queuedOwnerCannotKeepPreviousPageWhenAnotherOwnerReplacesIt() {
        val folder = directory(); val queue = Queue(); val memory = PreviewMemory(1L shl 20)
        val source = image(); val replacement = image(Color.BLUE); val delivered = mutableListOf<Bitmap>()
        fun owner() = DocumentPreviews({ folder }, queue, memory) { _, bitmap, _ -> delivered += bitmap }
        val first = owner(); val second = owner()
        try {
            first.savePage(hash, source, Color.WHITE); queue.drain()
            first.setAppearance(inverted); first.request(hash) // Queued, but has not selected its work yet.
            second.savePage(hash, replacement, Color.WHITE) // Invalidates before the older load begins.
            queue.drain()
            assertEquals(Color.BLUE, delivered.last().getPixel(0, 0))
            assertNull("Save commit must invalidate old disk covers admitted after save enqueue", first.cached(hash))
            first.request(hash); queue.drain()
            assertEquals(Color.YELLOW, delivered.last().getPixel(0, 0))
        } finally {
            first.close(); second.close(); queue.drain(); memory.onLowMemory(); source.recycle(); replacement.recycle()
            delivered.distinct().forEach { it.recycle() }; folder.deleteRecursively()
        }
    }

    @Test fun recentOwnerReplacementReusesMemoryAndSavingANewPageInvalidatesOldThemes() {
        val folder = directory(); val queue = Queue(); val memory = PreviewMemory(1L shl 20)
        val delivered = mutableListOf<Bitmap>(); var directoryReads = 0
        fun owner() = DocumentPreviews({ directoryReads++; folder }, queue, memory) { _, image, _ -> delivered += image }
        val first = owner(); val second = owner()
        val source = image(); val replacement = image(Color.BLUE)
        try {
            first.savePage(hash, source, Color.WHITE); queue.drain()
            val initial = delivered.single()
            first.close()
            assertSame(initial, second.cached(hash))
            second.request(hash); second.request(hash); queue.drain()
            assertEquals(1, directoryReads)
            assertEquals(2, delivered.size); assertSame(initial, delivered.last())
            second.setAppearance(inverted); second.request(hash); queue.drain()
            assertEquals(Color.BLACK, delivered.last().getPixel(0, 0))
            second.setAppearance(original)
            assertSame(initial, second.cached(hash))
            second.savePage(hash, replacement, Color.WHITE)
            assertNull("A pending replacement cannot expose the previous page as warm", second.cached(hash))
            queue.drain()
            val changed = delivered.last()
            assertEquals(Color.BLUE, changed.getPixel(0, 0)); assertNotSame(initial, changed)
            second.setAppearance(inverted)
            assertNull("All previous themes of the old page must be invalidated", second.cached(hash))
            second.request(hash); queue.drain()
            assertEquals(Color.YELLOW, delivered.last().getPixel(0, 0))
            second.clear()
            assertNull(second.cached(hash)); queue.drain()
            assertFalse(File(folder, "$hash.png").exists())
            assertFalse("Published originals remain drawable after replacement and clear", initial.isRecycled)
        } finally {
            first.close(); second.close(); queue.drain(); memory.onLowMemory()
            source.recycle(); replacement.recycle(); delivered.distinct().forEach { it.recycle() }; folder.deleteRecursively()
        }
    }
}
