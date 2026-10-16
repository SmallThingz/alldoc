package app.smallthingz.alldoc.ui

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.os.CancellationSignal
import android.os.OperationCanceledException
import app.smallthingz.alldoc.engine.DocumentInfo
import app.smallthingz.alldoc.engine.DocumentSession
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.smallthingz.alldoc.theme.BitmapTheme
import app.smallthingz.alldoc.theme.ReadingMode
import app.smallthingz.alldoc.theme.ThemeSettings
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DocumentPreviewsTest {
    private data class Result(val hash: String, val image: Bitmap, val generation: Long)
    private fun hash(index: Int) = index.toString(16).padStart(64, '0')

    @Test fun pagePreviewCompositesPaperAndReopensByHashWithoutChangingSource() {
        val folder = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "preview-test-${UUID.randomUUID()}")
        val ready = LinkedBlockingQueue<Result>()
        val source = Bitmap.createBitmap(288, 384, Bitmap.Config.ARGB_8888)
        source.setPixel(144, 192, Color.WHITE)
        val paper = Color.rgb(18, 22, 28)
        val thumbnail = DocumentPreviews.page(source, paper)
        val images = mutableListOf<Bitmap>()
        var cache = DocumentPreviews({ folder }) { key, image, generation -> ready.put(Result(key, image, generation)) }
        try {
            cache.savePage(hash(1), thumbnail, paper)
            val first = requireNotNull(ready.poll(5, TimeUnit.SECONDS))
            images += first.image
            assertEquals(hash(1), first.hash)
            assertEquals(144, first.image.width); assertEquals(192, first.image.height)
            assertEquals(paper, first.image.getPixel(0, 0))
            assertEquals(Color.TRANSPARENT, source.getPixel(0, 0))
            assertFalse(source.isRecycled)
            cache.close()
            cache = DocumentPreviews({ folder }) { key, image, generation -> ready.put(Result(key, image, generation)) }
            cache.request(hash(1))
            val reopened = requireNotNull(ready.poll(5, TimeUnit.SECONDS))
            images += reopened.image
            assertEquals(paper, reopened.image.getPixel(0, 0))
            assertTrue(cache.isCurrent(reopened.generation))
            cache.clear()
            assertFalse("Clear invalidates already delivered results", cache.isCurrent(reopened.generation))
            cache.saveText(hash(2), "Different document, हिन्दी and 日本語.")
            val replacement = requireNotNull(ready.poll(5, TimeUnit.SECONDS))
            images += replacement.image
            assertEquals(hash(2), replacement.hash)
            assertFalse(File(folder, "${hash(1)}.png").exists())
            assertTrue(File(folder, "${hash(2)}.png").isFile)
        } finally {
            cache.close()
            images.forEach { it.recycle() }
            thumbnail.recycle()
            source.recycle()
            folder.deleteRecursively()
        }
    }

    @Test fun originalSnapshotsOutliveSourceAndRecolorWithoutMutatingDisk() {
        val folder = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "preview-owned-${UUID.randomUUID()}")
        val tasks = java.util.ArrayDeque<Runnable>()
        val results = mutableListOf<Result>()
        val source = Bitmap.createBitmap(137, 191, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
            for (x in 0 until width) setPixel(x, 32, Color.rgb(163, 164, 166))
            for (y in 0 until 32) for (x in 115 until width) setPixel(x, y, Color.rgb(31, 92, 199))
        }
        val originalPixels = IntArray(source.width * source.height)
        source.getPixels(originalPixels, 0, source.width, 0, 0, source.width, source.height)
        DocumentPreviews({ folder }, java.util.concurrent.Executor(tasks::addLast)) { key, image, generation ->
            results += Result(key, image, generation)
        }.use { cache ->
            try {
                cache.setAppearance(ThemeSettings(ReadingMode.INVERT))
                cache.savePage(hash(1), source, Color.WHITE)
                source.eraseColor(Color.MAGENTA)
                source.recycle()
                tasks.removeFirst().run()
                assertEquals(Color.BLACK, results.single().image.getPixel(0, 0))
                val original = requireNotNull(DocumentPreviews.read(File(folder, "${hash(1)}.png")))
                try {
                    assertEquals(137, original.width); assertEquals(191, original.height)
                    val pixels = IntArray(original.width * original.height)
                    original.getPixels(pixels, 0, original.width, 0, 0, original.width, original.height)
                    assertArrayEquals("The original native palette and AA survive the owned copy and PNG cache", originalPixels, pixels)
                } finally { original.recycle() }
                cache.setAppearance(ThemeSettings(ReadingMode.ORIGINAL))
                assertFalse(cache.isCurrent(results.single().generation))
                cache.request(hash(1)); tasks.removeFirst().run()
                assertEquals(Color.WHITE, results.last().image.getPixel(0, 0))
                cache.setAppearance(ThemeSettings(ReadingMode.ORIGINAL))
                assertTrue(tasks.isEmpty())
                val paper = ThemeSettings(ReadingMode.SEPIA)
                cache.setAppearance(paper); cache.request(hash(1)); tasks.removeFirst().run()
                val smooth = results.last()
                cache.setAppearance(paper.copy(paperRoughness = 100))
                assertFalse("Roughness alone invalidates recent previews", cache.isCurrent(smooth.generation))
                cache.request(hash(1)); tasks.removeFirst().run()
                val rough = results.last().image
                assertTrue((0 until 64).any { x -> rough.getPixel(x, 0) != smooth.image.getPixel(x, 0) })
                cache.setAppearance(paper.copy(paperRoughness = 100))
                assertTrue(tasks.isEmpty())
            } finally {
                source.recycle(); results.forEach { it.image.recycle() }; folder.deleteRecursively()
            }
        }
    }

    @Test fun legacyThemedCacheIsDiscardedBeforeOriginalCacheIsUsed() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "preview-migration-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getCacheDir() = root
        }
        val obsolete = listOf("previews-v1", "previews-v2", "previews-v3", "previews-v4").map { name ->
            File(root, name).apply { mkdirs(); File(this, "${hash(1)}.png").writeText("Previously fitted or themed preview") }
        }
        val ready = LinkedBlockingQueue<Result>()
        try {
            DocumentPreviews(context) { key, image, generation -> ready.put(Result(key, image, generation)) }.use { cache ->
                cache.setAppearance(ThemeSettings(ReadingMode.ADAPTIVE))
                cache.saveText(hash(1), "Original text snapshot")
                val result = requireNotNull(ready.poll(5, TimeUnit.SECONDS))
                try {
                    assertTrue(obsolete.none(File::exists))
                    val raw = requireNotNull(DocumentPreviews.read(File(root, "previews-v5/${hash(1)}.png")))
                    try {
                        assertEquals(DocumentPreviews.PAPER, raw.getPixel(0, 0))
                        assertNotEquals(raw.getPixel(0, 0), result.image.getPixel(0, 0))
                    } finally { raw.recycle() }
                } finally { result.image.recycle() }
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun acceptedClearFinishesAfterItsOwnerCloses() {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "preview-close-${UUID.randomUUID()}").apply { mkdirs() }
        val folder = File(root, "cache").apply { mkdirs() }
        val existing = File(folder, "${hash(1)}.png").apply { writeText("old cached preview") }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val callbacks = AtomicInteger()
        val cache = DocumentPreviews({
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            folder
        }) { _, image, _ -> callbacks.incrementAndGet(); image.recycle() }
        val completed = LinkedBlockingQueue<Bitmap>()
        val next = DocumentPreviews({ File(root, "barrier") }) { _, image, _ -> completed.put(image) }
        try {
            cache.request(hash(9)) // An absent key must not remove the unrelated cached file itself.
            assertTrue("Preview worker did not start", entered.await(5, TimeUnit.SECONDS))
            cache.clear()
            cache.close()
            next.saveText(hash(2), "Next owner")
            release.countDown()
            requireNotNull(completed.poll(5, TimeUnit.SECONDS)).recycle()
            assertFalse("An accepted clear must survive owner teardown", existing.exists())
            assertEquals(0, callbacks.get())
        } finally {
            release.countDown()
            cache.close(); next.close()
            root.deleteRecursively()
        }
    }

    @Test fun allocationFailureDropsOnePreviewAndKeepsQueuedAndFutureWorkUsable() {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "preview-memory-${UUID.randomUUID()}")
        val folder = File(root, "cache")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val attempts = AtomicInteger()
        val ready = LinkedBlockingQueue<Result>()
        val images = mutableListOf<Bitmap>()
        val source = Bitmap.createBitmap(144, 192, Bitmap.Config.ARGB_8888)
        val cache = DocumentPreviews({
            if (attempts.getAndIncrement() == 0) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                throw OutOfMemoryError("Simulated optional preview allocation")
            }
            folder
        }) { key, image, generation -> ready.put(Result(key, image, generation)) }
        val completed = LinkedBlockingQueue<Bitmap>()
        val barrier = DocumentPreviews({ File(root, "barrier") }) { _, image, _ -> completed.put(image) }
        try {
            cache.savePage(hash(1), source, Color.WHITE)
            assertTrue("Preview worker did not start", entered.await(5, TimeUnit.SECONDS))
            cache.saveText(hash(2), "Recovered preview")
            release.countDown()
            val queued = requireNotNull(ready.poll(5, TimeUnit.SECONDS))
            images += queued.image
            assertEquals(hash(2), queued.hash)
            assertEquals(144, queued.image.width); assertEquals(192, queued.image.height)
            assertFalse(source.isRecycled)
            assertFalse(File(folder, "${hash(1)}.png").exists())
            // This separate owner runs only after the first drain has returned and reset scheduling.
            barrier.saveText(hash(4), "Worker barrier")
            requireNotNull(completed.poll(5, TimeUnit.SECONDS)).recycle()
            cache.saveText(hash(3), "Later preview")
            val later = requireNotNull(ready.poll(5, TimeUnit.SECONDS))
            images += later.image
            assertEquals(hash(3), later.hash)
            assertTrue(cache.isCurrent(later.generation))
            assertFalse(source.isRecycled)
        } finally {
            release.countDown()
            cache.close(); barrier.close()
            images.forEach { it.recycle() }
            source.recycle()
            root.deleteRecursively()
        }
    }

    @Test fun failedWorkerAdmissionAllowsRetryAndPreservesClearOnClose() {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "preview-admission-${UUID.randomUUID()}")
        val source = Bitmap.createBitmap(24, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val images = mutableListOf<Bitmap>()
        try {
            for (operation in listOf("load", "save", "clear")) {
                val folder = File(root, operation).apply { check(mkdirs()) }
                val existing = File(folder, "${hash(1)}.png")
                existing.outputStream().use { assertTrue(source.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                val tasks = java.util.ArrayDeque<Runnable>()
                var attempts = 0
                val executor = java.util.concurrent.Executor { task ->
                    if (attempts++ == 0) throw OutOfMemoryError("Simulated preview worker admission")
                    tasks.addLast(task)
                }
                val ready = LinkedBlockingQueue<Result>()
                DocumentPreviews({ folder }, executor) { key, image, generation ->
                    images += image
                    ready.add(Result(key, image, generation))
                }.use { cache ->
                    assertThrows(OutOfMemoryError::class.java) {
                        when (operation) {
                            "load" -> cache.request(hash(1))
                            "save" -> cache.savePage(hash(2), source, Color.WHITE)
                            else -> cache.clear()
                        }
                    }
                    assertEquals(1, attempts)
                    assertTrue(tasks.isEmpty())
                    assertTrue(ready.isEmpty())
                    assertFalse(source.isRecycled)
                    when (operation) {
                        "load" -> { cache.request(hash(1)); cache.request(hash(1)) }
                        "save" -> cache.savePage(hash(3), source, Color.WHITE)
                        else -> { cache.request(hash(1)); cache.close() }
                    }
                    assertEquals("Retry must admit exactly one worker", 2, attempts)
                    assertEquals(1, tasks.size)
                    tasks.removeFirst().run()
                    assertTrue(tasks.isEmpty())
                    if (operation == "clear") {
                        assertFalse("An accepted clear must survive close after an earlier admission failure", existing.exists())
                        assertTrue(ready.isEmpty())
                    } else {
                        val result = requireNotNull(ready.poll())
                        assertEquals(hash(if (operation == "load") 1 else 3), result.hash)
                        assertTrue(cache.isCurrent(result.generation))
                        assertTrue(ready.isEmpty())
                        assertFalse("A replaced failed save must not be retained", File(folder, "${hash(2)}.png").exists())
                        cache.saveText(hash(4), "Later preview")
                        assertEquals("Recovery must reset scheduling for later work", 3, attempts)
                        assertEquals(1, tasks.size)
                        tasks.removeFirst().run()
                        assertEquals(hash(4), requireNotNull(ready.poll()).hash)
                        assertTrue(ready.isEmpty())
                    }
                    cache.close()
                    val settled = attempts
                    cache.request(hash(5)); cache.savePage(hash(6), source, Color.WHITE); cache.clear()
                    assertEquals("Closed caches must not admit another worker", settled, attempts)
                    assertTrue(tasks.isEmpty())
                    assertFalse("Borrowed source bitmaps remain owned by the reader", source.isRecycled)
                }
            }
        } finally {
            images.forEach { it.recycle() }
            source.recycle()
            root.deleteRecursively()
        }
    }

    @Test fun cacheAdmissionRejectsOversizedImagesAndPrunesOldFilesBeforeLoading() {
        val folder = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "preview-test-${UUID.randomUUID()}").apply { mkdirs() }
        val ready = LinkedBlockingQueue<Result>()
        val small = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val large = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
        try {
            for (index in 1..70) File(folder, "${hash(index)}.png").also { file ->
                file.outputStream().use { assertTrue(small.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                assertTrue(file.setLastModified(index * 1000L))
            }
            val invalid = File(folder, "${hash(71)}.png")
            invalid.outputStream().use { assertTrue(large.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            File(folder, ".pending-interrupted.png").writeText("incomplete")
            DocumentPreviews({ folder }) { key, image, generation -> ready.put(Result(key, image, generation)) }.use { cache ->
                cache.request(hash(71))
                cache.saveText(hash(72), "Small preview " + "text ".repeat(1000))
                val result = requireNotNull(ready.poll(5, TimeUnit.SECONDS))
                try {
                    assertEquals(hash(72), result.hash)
                    assertEquals(144, result.image.width); assertEquals(192, result.image.height)
                    val pixels = IntArray(result.image.width * result.image.height)
                    result.image.getPixels(pixels, 0, result.image.width, 0, 0, result.image.width, result.image.height)
                    assertTrue(pixels.any { it != pixels[0] })
                } finally { result.image.recycle() }
                // Queue a known cached entry after the invalid file, providing a worker barrier.
                cache.request(hash(70))
                val loaded = requireNotNull(ready.poll(5, TimeUnit.SECONDS))
                loaded.image.recycle()
                assertEquals(hash(70), loaded.hash)
                assertFalse(invalid.exists())
                assertFalse(File(folder, ".pending-interrupted.png").exists())
                val files = folder.listFiles().orEmpty()
                assertTrue(files.size <= DocumentPreviews.MAX_FILES)
                assertTrue(files.sumOf { it.length() } <= DocumentPreviews.MAX_BYTES)
            }
        } finally { small.recycle(); large.recycle(); folder.deleteRecursively() }
    }
    @Test fun transparentInkSurvivesColdAndWarmAdaptiveCacheWithoutFlatteningOriginal() {
        val folder = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "preview-alpha-${UUID.randomUUID()}")
        val tasks = java.util.ArrayDeque<Runnable>()
        val results = mutableListOf<Result>()
        val settings = ThemeSettings(ReadingMode.ADAPTIVE)
        val source = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply {
            for (y in 8 until 24) for (x in 12 until 20) setPixel(x, y, Color.WHITE)
            for (y in 8 until 24) setPixel(11, y, Color.argb(128, 255, 255, 255))
        }
        fun pixels(image: Bitmap) = IntArray(image.width * image.height).also {
            image.getPixels(it, 0, image.width, 0, 0, image.width, image.height)
        }
        val original = pixels(source)
        val expectedSource = requireNotNull(source.copy(Bitmap.Config.ARGB_8888, true))
        val expected = BitmapTheme.apply(expectedSource, settings, CancellationSignal())
        if (expected !== expectedSource) expectedSource.recycle()
        val expectedPixels = pixels(expected)
        val file = File(folder, "${hash(1)}.png")
        try {
            assertEquals(0, Color.alpha(expected.getPixel(0, 0)))
            val ink = expected.getPixel(15, 15)
            assertEquals(255, Color.alpha(ink))
            assertTrue("White foreground remains bright on a dark document surface",
                minOf(Color.red(ink), Color.green(ink), Color.blue(ink)) > 200)
            DocumentPreviews({ folder }, java.util.concurrent.Executor(tasks::addLast)) { key, image, generation ->
                results += Result(key, image, generation)
            }.use { cache ->
                cache.setAppearance(settings)
                cache.savePage(hash(1), source, Color.TRANSPARENT)
                assertFalse(source.isRecycled)
                assertArrayEquals(original, pixels(source))
                tasks.removeFirst().run()
                assertArrayEquals("Cold preview uses the native alpha-bearing source", expectedPixels, pixels(results.single().image))
            }
            val cachedBytes = file.readBytes()
            val raw = requireNotNull(DocumentPreviews.read(file))
            try {
                assertArrayEquals("Disk retains original white ink, alpha and antialiasing", original, pixels(raw))
                assertEquals(0, Color.alpha(raw.getPixel(0, 0)))
                assertEquals(Color.WHITE, raw.getPixel(15, 15))
                assertEquals(128, Color.alpha(raw.getPixel(11, 15)))
            } finally { raw.recycle() }
            source.recycle()
            DocumentPreviews({ folder }, java.util.concurrent.Executor(tasks::addLast)) { key, image, generation ->
                results += Result(key, image, generation)
            }.use { cache ->
                cache.setAppearance(settings)
                cache.request(hash(1)); tasks.removeFirst().run()
                assertEquals(2, results.size)
                assertArrayEquals("A reopened cache produces identical adaptive pixels", expectedPixels, pixels(results.last().image))
                assertArrayEquals("Themed reads never rewrite the original PNG", cachedBytes, file.readBytes())
                assertTrue(tasks.isEmpty())
            }
        } finally {
            source.recycle(); expected.recycle(); results.forEach { it.image.recycle() }; folder.deleteRecursively()
        }
    }

    @Test fun nativePreviewBoundsRetriesAndCancellationKeepOwnershipExplicit() {
        var aspect = 1.4f
        var actualAspect = 1.4f
        var alwaysOversized = false
        var cancelAt = 0
        var closes = 0
        val widths = mutableListOf<Int>()
        val images = mutableListOf<Bitmap>()
        val session = object : DocumentSession {
            override val info = DocumentInfo("Preview", "application/pdf", "pdf", 1, false, "ORIGINAL")
            override fun pageAspect(index: Int, signal: CancellationSignal) = aspect
            override fun text(signal: CancellationSignal) = ""
            override fun json(signal: CancellationSignal) = "{}"
            override fun close() { closes++ }
            override fun renderPage(index: Int, width: Int, settings: ThemeSettings, signal: CancellationSignal): Bitmap {
                assertEquals(0, index)
                assertEquals(ReadingMode.ORIGINAL, settings.mode)
                assertTrue("A retry cannot retain the earlier raster", images.all { it.isRecycled })
                widths += width
                val bitmap = Bitmap.createBitmap(width,
                    if (alwaysOversized) 193 else (width * actualAspect).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                images += bitmap
                if (widths.size == cancelAt) signal.cancel()
                return bitmap
            }
        }
        try {
            val native = DocumentPreviews.nativePage(session, 0, CancellationSignal())
            try {
                assertEquals(listOf(137), widths)
                assertEquals(137, native.width); assertEquals(191, native.height)
                assertFalse(native.isRecycled)
            } finally { native.recycle() }
            widths.clear(); actualAspect = 2f
            val corrected = DocumentPreviews.nativePage(session, 0, CancellationSignal())
            try {
                assertEquals("A placeholder aspect gets exactly one native retry", listOf(137, 96), widths)
                assertEquals(96, corrected.width); assertEquals(192, corrected.height)
            } finally { corrected.recycle() }
            widths.clear(); alwaysOversized = true
            assertThrows(IllegalStateException::class.java) { DocumentPreviews.nativePage(session, 0, CancellationSignal()) }
            assertEquals(2, widths.size)
            assertTrue(images.all { it.isRecycled })
            widths.clear(); aspect = 1000f
            assertThrows(IllegalStateException::class.java) { DocumentPreviews.nativePage(session, 0, CancellationSignal()) }
            assertEquals("An impossible one-pixel width must not retry forever", listOf(1), widths)
            assertTrue(images.all { it.isRecycled })
            aspect = 1.4f; alwaysOversized = false
            for (attempt in 1..2) {
                widths.clear(); cancelAt = attempt
                assertThrows(OperationCanceledException::class.java) {
                    DocumentPreviews.nativePage(session, 0, CancellationSignal())
                }
                assertEquals(attempt, widths.size)
                assertTrue("Cancellation recycles every unreturned native raster", images.all { it.isRecycled })
            }
            widths.clear(); cancelAt = 0
            assertThrows(OperationCanceledException::class.java) {
                DocumentPreviews.nativePage(session, 0, CancellationSignal().apply { cancel() })
            }
            assertTrue(widths.isEmpty())
            for (invalid in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
                aspect = invalid
                assertThrows(IllegalArgumentException::class.java) { DocumentPreviews.nativePage(session, 0, CancellationSignal()) }
            }
            assertTrue(widths.isEmpty())
            assertEquals("The caller retains its document session", 0, closes)
        } finally { images.forEach(Bitmap::recycle); session.close() }
    }

    @Test fun libraryCacheMigrationIsLazyAndLeavesOtherCachesIntact() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "library-preview-migration-${UUID.randomUUID()}").apply { mkdirs() }
        val reads = AtomicInteger()
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getCacheDir(): File { reads.incrementAndGet(); return root }
        }
        val obsolete = listOf("library-previews-v1", "library-previews-v2", "library-previews-v3").map { name ->
            File(root, name).apply { mkdirs(); File(this, "${hash(1)}.png").writeText("Previously fitted or source-only preview") }
        }
        val recent = File(root, "previews-v5").apply { mkdirs() }
        val retained = File(recent, "${hash(1)}.png").apply { writeText("Other cache owner") }
        try {
            val directory = DocumentPreviews.cacheDirectory(context, "library-previews-v4", "library-previews-v1", "library-previews-v2", "library-previews-v3")
            assertEquals("Constructing a cache does no directory I/O", 0, reads.get())
            assertTrue(obsolete.all(File::isDirectory))
            val current = directory()
            assertEquals(File(root, "library-previews-v4"), current)
            assertTrue(obsolete.none(File::exists))
            assertEquals("Other cache owner", retained.readText())
            assertSame(current, directory())
            assertEquals("Resolved cache directory is reused", 1, reads.get())
        } finally { root.deleteRecursively() }
    }

}
