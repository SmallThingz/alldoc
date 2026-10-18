package app.smallthingz.alldoc.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.os.CancellationSignal
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.smallthingz.alldoc.sources.LibrarySourceChangedException
import app.smallthingz.alldoc.sources.LibrarySourceMissingException
import app.smallthingz.alldoc.state.LibraryDocument
import app.smallthingz.alldoc.theme.BitmapTheme
import app.smallthingz.alldoc.theme.RasterRegion
import app.smallthingz.alldoc.theme.ReadingMode
import app.smallthingz.alldoc.theme.ThemeSettings
import java.io.File
import java.io.IOException
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryPreviewsTest {
    private class Queue : Executor {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.addLast(command) }
        fun drain() { var count = 0; while (tasks.isNotEmpty()) { check(count++ < 20); tasks.removeFirst().run() } }
    }
    private fun hash(id: Long) = id.toString(16).padStart(64, '0')
    private fun document(id: Long, revision: Long = 1) = LibraryDocument(id, null, "$id.pdf", "application/pdf", 8, "content://preview/$id", 1, revision)
    private fun image() = Bitmap.createBitmap(24, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
    private fun directory() = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "library-preview-${UUID.randomUUID()}")

    @Test fun allFortyVisibleCoversLoadSeriallyAndAnUnchangedWindowDoesNotReload() {
        val folder = directory(); val queue = Queue()
        val loaded = mutableListOf<Long>(); val sources = mutableListOf<Bitmap>(); val published = linkedMapOf<Long, Bitmap>()
        val cache = LibraryPreviews({ folder }, { document, signal ->
            assertFalse(signal.isCanceled); loaded += document.id
            LibraryPreviews.Preview(hash(document.id), image().also(sources::add), document.mimeType)
        }, queue) { document, bitmap, _ ->
            assertEquals(hash(document.id), document.hash); published[document.id] = bitmap
        }
        try {
            val documents = (1L..40L).map(::document)
            cache.setVisible(documents)
            assertEquals("One serial drain, not one executor task per cover", 1, queue.tasks.size)
            queue.drain()
            assertEquals(documents.map { it.id }, loaded); assertEquals(40, published.size)
            assertTrue(sources.all { it.isRecycled })
            published.values.forEach { bitmap ->
                assertFalse(bitmap.isRecycled); assertTrue(bitmap.width <= 240 && bitmap.height <= 320)
                assertEquals(Color.MAGENTA, bitmap.getPixel(bitmap.width / 2, bitmap.height / 2))
            }
            cache.setVisible(documents); assertTrue(queue.tasks.isEmpty()); assertEquals(40, loaded.size)
            assertTrue(folder.listFiles().orEmpty().count { it.extension == "png" } <= 64)
        } finally { cache.close(); queue.drain(); published.values.forEach(Bitmap::recycle); folder.deleteRecursively() }
    }

    @Test fun changedViewportAndRevisionCancelInFlightImagesBeforePublication() {
        val folder = directory(); val queue = Queue(); val sources = mutableListOf<Bitmap>()
        val published = mutableListOf<Pair<LibraryDocument, Bitmap>>()
        val loaded = mutableListOf<Pair<Long, Long>>()
        lateinit var cache: LibraryPreviews
        cache = LibraryPreviews({ folder }, { doc, signal ->
            loaded += doc.id to doc.revision
            val bitmap = image().also(sources::add)
            if (doc.id == 1L && doc.revision == 1L) {
                cache.setVisible(listOf(document(1, 2), document(3)))
                assertTrue("Replacing a revision cancels its actual in-flight signal", signal.isCanceled)
            }
            LibraryPreviews.Preview(hash(doc.id * 10 + doc.revision), bitmap, doc.mimeType)
        }, queue) { document, bitmap, _ -> published += document to bitmap }
        try {
            cache.setVisible(listOf(document(9)))
            cache.setVisible(listOf(document(1), document(2)))
            assertEquals(1, queue.tasks.size); queue.drain()
            assertEquals(listOf(1L to 1L, 1L to 2L, 3L to 1L), loaded)
            assertEquals(listOf(1L to 2L, 3L to 1L), published.map { it.first.id to it.first.revision })
            assertTrue(sources.all { it.isRecycled })
            cache.setVisible(listOf(document(4)))
            cache.setVisible(emptyList(), active = false); queue.drain()
            assertEquals(3, loaded.size)
            cache.setVisible(listOf(document(5))); cache.close(); queue.drain()
            assertEquals(3, loaded.size)
            assertTrue(published.all { !it.second.isRecycled })
        } finally { cache.close(); queue.drain(); published.forEach { it.second.recycle() }; folder.deleteRecursively() }
    }

    @Test fun appearanceChangesCancelOldWorkAndReuseImmutableOriginalCovers() {
        val folder = directory(); val queue = Queue()
        val published = mutableListOf<Pair<Bitmap, Long>>()
        val sources = mutableListOf<Bitmap>()
        var loads = 0
        lateinit var cache: LibraryPreviews
        cache = LibraryPreviews({ folder }, { doc, signal ->
            loads++
            val source = image().apply { eraseColor(Color.WHITE) }.also(sources::add)
            if (loads == 1) {
                cache.setAppearance(ThemeSettings(ReadingMode.INVERT))
                assertTrue("Theme changes cancel the actual in-flight render", signal.isCanceled)
            }
            LibraryPreviews.Preview(hash(doc.id), source, doc.mimeType)
        }, queue) { _, bitmap, generation -> published += bitmap to generation }
        try {
            cache.setVisible(listOf(document(1)))
            queue.drain()
            assertEquals("The stale original must never be published", 1, published.size)
            assertEquals(Color.BLACK, published.single().first.getPixel(0, 0))
            assertTrue(sources.all { it.isRecycled })
            assertEquals(2, loads)
            val disk = requireNotNull(DocumentPreviews.readCached(folder.listFiles()!!.single { it.extension == "png" }, 240, 320)).image
            try { assertEquals("Appearance never mutates the original disk thumbnail", Color.WHITE, disk.getPixel(0, 0)) }
            finally { disk.recycle() }
            cache.setVisible(listOf(document(1).copy(hash = hash(1))))
            cache.setAppearance(ThemeSettings(ReadingMode.ORIGINAL))
            assertFalse(cache.isCurrent(1, 1, published.single().second))
            queue.drain()
            assertEquals(2, loads)
            assertEquals(Color.WHITE, published.last().first.getPixel(0, 0))
            cache.setAppearance(ThemeSettings(ReadingMode.ADAPTIVE, true)); queue.drain()
            val previous = published.last().second
            cache.setAppearance(ThemeSettings(ReadingMode.ADAPTIVE, false))
            assertFalse("Image treatment participates in preview identity", cache.isCurrent(1, 1, previous))
            queue.drain()
            assertEquals("Color changes must not reopen the document", 2, loads)
            assertEquals(4, published.size)
            cache.setAppearance(ThemeSettings(ReadingMode.ADAPTIVE, false))
            assertTrue("An unchanged appearance does no work", queue.tasks.isEmpty())
            val paper = ThemeSettings(ReadingMode.SEPIA)
            cache.setAppearance(paper); queue.drain()
            val smooth = published.last()
            cache.setAppearance(paper.copy(paperRoughness = 100))
            assertFalse("Roughness alone invalidates the previous cover", cache.isCurrent(1, 1, smooth.second))
            queue.drain()
            val rough = published.last().first
            assertTrue((0 until 64).any { x -> rough.getPixel(x, 0) != smooth.first.getPixel(x, 0) })
            assertEquals("Roughness reuses the original disk cover", 2, loads)
            cache.setAppearance(paper.copy(paperRoughness = 100))
            assertTrue("An unchanged roughness does no work", queue.tasks.isEmpty())
        } finally { cache.close(); queue.drain(); published.forEach { it.first.recycle() }; folder.deleteRecursively() }
    }

    @Test fun transparentInkSurvivesColdAndWarmAdaptiveLibraryCovers() {
        val folder = directory(); val queue = Queue()
        val published = mutableListOf<Bitmap>()
        val settings = ThemeSettings(ReadingMode.ADAPTIVE)
        val document = document(1).copy(hash = hash(1), title = "White ink.pptx", mimeType = "application/vnd.openxmlformats-officedocument.presentationml.presentation")
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
        var loads = 0
        try {
            assertEquals(0, Color.alpha(expected.getPixel(0, 0)))
            val ink = expected.getPixel(15, 15)
            assertEquals(255, Color.alpha(ink))
            assertTrue(minOf(Color.red(ink), Color.green(ink), Color.blue(ink)) > 200)
            LibraryPreviews({ folder }, { doc, _ ->
                loads++
                assertArrayEquals("The loader supplies original alpha-bearing pixels", original, pixels(source))
                LibraryPreviews.Preview(requireNotNull(doc.hash), source, doc.mimeType)
            }, queue) { _, bitmap, _ -> published += bitmap }.use { cache ->
                cache.setAppearance(settings); cache.setVisible(listOf(document)); queue.drain()
                assertEquals(1, loads)
                assertTrue("Library owns and releases the loaded native raster", source.isRecycled)
                assertArrayEquals("Cold Library copy must not flatten transparent ink", expectedPixels, pixels(published.single()))
            }
            val file = folder.listFiles().orEmpty().single { it.extension == "png" }
            val cachedBytes = file.readBytes()
            val raw = requireNotNull(DocumentPreviews.readCached(file, 240, 320)).image
            try {
                assertArrayEquals("Disk retains the complete original alpha and AA", original, pixels(raw))
                assertEquals(0, Color.alpha(raw.getPixel(0, 0)))
                assertEquals(Color.WHITE, raw.getPixel(15, 15))
                assertEquals(128, Color.alpha(raw.getPixel(11, 15)))
            } finally { raw.recycle() }
            LibraryPreviews({ folder }, { _, _ ->
                loads++; error("A warm cover must not reopen the source")
            }, queue) { _, bitmap, _ -> published += bitmap }.use { cache ->
                cache.setAppearance(settings); cache.setVisible(listOf(document)); queue.drain()
                assertEquals(1, loads)
                assertEquals(2, published.size)
                assertArrayEquals("Reopened Library cache preserves adaptive pixels", expectedPixels, pixels(published.last()))
                assertArrayEquals("Adaptive reads cannot replace the original PNG", cachedBytes, file.readBytes())
                cache.setVisible(listOf(document)); assertTrue(queue.tasks.isEmpty())
            }
        } finally { source.recycle(); expected.recycle(); published.forEach(Bitmap::recycle); folder.deleteRecursively() }
    }

    @Test fun optionalFailureAndConfirmedStaleSourceDoNotBlockLaterCoversOrFutureRequests() {
        val folder = directory(); val queue = Queue(); val stale = mutableListOf<Long>(); val missing = mutableListOf<Long>()
        var failAdmission = true
        var missingSource = false
        val executor = Executor { task ->
            if (failAdmission) { failAdmission = false; throw OutOfMemoryError("Synthetic worker admission failure") }
            queue.execute(task)
        }
        val published = mutableListOf<Pair<Long, Bitmap>>()
        val sources = mutableListOf<Bitmap>(); var attempts = 0
        val cache = LibraryPreviews({ folder }, { doc, _ ->
            attempts++
            when (doc.id) {
                1L -> throw OutOfMemoryError("Synthetic optional cover failure")
                2L -> throw LibrarySourceChangedException()
                3L -> throw IOException("Temporary access failure")
                else -> LibraryPreviews.Preview(hash(doc.id), image().also(sources::add), doc.mimeType)
            }
        }, executor, onSourceChanged = { stale += it.id }, onSourceMissing = { missing += it.id },
            validate = { if (missingSource && it.id == 4L) throw LibrarySourceMissingException() }) {
                doc, bitmap, _ -> published += doc.id to bitmap
        }
        try {
            val documents = (1L..4L).map(::document)
            cache.setVisible(documents)
            assertTrue(queue.tasks.isEmpty()); assertEquals(0, attempts); assertTrue(published.isEmpty())
            cache.setVisible(documents); assertEquals(1, queue.tasks.size); queue.drain()
            assertEquals(listOf(2L), stale); assertEquals(listOf(4L), published.map { it.first }); assertEquals(4, attempts)
            val cachedFourth = folder.listFiles().orEmpty().single { it.extension == "png" }
            cache.setVisible(listOf(document(5))); queue.drain()
            assertEquals(listOf(4L, 5L), published.map { it.first }); assertEquals(5, attempts)
            assertTrue(missing.isEmpty())
            assertTrue("The missing-source check must also run before a real cached PNG hit", cachedFourth.isFile)
            missingSource = true
            cache.setVisible(listOf(document(4).copy(hash = hash(4)))); queue.drain()
            assertEquals(listOf(4L), missing); assertEquals(listOf(2L), stale)
            assertEquals(listOf(4L, 5L), published.map { it.first }); assertEquals(5, attempts)
            cache.setVisible(listOf(document(6))); queue.drain()
            assertEquals(listOf(4L, 5L, 6L), published.map { it.first }); assertEquals(6, attempts)
            assertTrue(sources.all { it.isRecycled }); assertTrue(published.all { !it.second.isRecycled })
        } finally { cache.close(); queue.drain(); published.forEach { it.second.recycle() }; folder.deleteRecursively() }
    }
    @Test fun rasterRegionRolesSurviveWarmLibraryHitsAndMissingMetadataReopensOnlyItsSource() {
        val folder = directory(); val queue = Queue()
        val settings = ThemeSettings(ReadingMode.ADAPTIVE)
        val roles = listOf(null, emptyList(), listOf(RasterRegion(.125f, .125f, .875f, .875f)))
        val documents = roles.indices.map { document(it + 1L).copy(hash = hash(it + 1L)) }
        fun original() = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
            for (y in 5 until 27) for (x in 5 until 27)
                setPixel(x, y, Color.rgb((x * 7 + y * 13) and 255, (x * 11 + y * 3) and 255, (x * 5 + y * 17) and 255))
        }
        val expected = roles.map { role ->
            val source = original()
            BitmapTheme.apply(source, settings, CancellationSignal(), role).also { if (it !== source) source.recycle() }
        }
        val delivered = mutableListOf<Bitmap>()
        val sources = mutableListOf<Bitmap>()
        var loads = 0
        val loader: (LibraryDocument, CancellationSignal) -> LibraryPreviews.Preview = { doc, _ ->
            loads++
            val source = original().also(sources::add)
            LibraryPreviews.Preview(requireNotNull(doc.hash), source, doc.mimeType, roles[doc.id.toInt() - 1])
        }
        fun open(load: (LibraryDocument, CancellationSignal) -> LibraryPreviews.Preview) {
            LibraryPreviews({ folder }, load, queue) { doc, bitmap, _ ->
                delivered += bitmap
                assertTrue("Cold/warm Library pixels use the complete image-role metadata", expected[doc.id.toInt() - 1].sameAs(bitmap))
            }.use { cache -> cache.setAppearance(settings); cache.setVisible(documents); queue.drain() }
        }
        try {
            assertFalse("Known no-image metadata must not retain photographic colors", expected[1].sameAs(expected[2]))
            original().let { original ->
                try {
                    assertEquals("Known photo edges remain original", Color.WHITE, expected[2].getPixel(4, 12))
                    assertEquals(original.getPixel(16, 16), expected[2].getPixel(16, 16))
                    assertNotEquals(Color.WHITE, expected[2].getPixel(0, 0))
                }
                finally { original.recycle() }
            }
            open(loader)
            assertEquals(3, loads); assertTrue(sources.all { it.isRecycled })
            val originals = folder.listFiles().orEmpty().associate { it.name to it.readBytes() }
            open { _, _ -> error("A metadata-complete warm cache must not reopen a document") }
            assertEquals(6, delivered.size); assertEquals(3, loads)
            originals.forEach { (name, bytes) -> assertArrayEquals(bytes, File(folder, name).readBytes()) }
            val photo = folder.listFiles().orEmpty().filter { it.extension == "png" }.single { file ->
                val cached = requireNotNull(DocumentPreviews.readCached(file, 240, 320))
                try { cached.regions?.isNotEmpty() == true } finally { cached.image.recycle() }
            }
            assertTrue(DocumentPreviews.regionsFile(photo).delete())
            open(loader)
            assertEquals("Only the incomplete cache pair needs a new source render", 4, loads)
            assertEquals(9, delivered.size); assertTrue(sources.all { it.isRecycled })
            assertTrue(DocumentPreviews.regionsFile(photo).isFile)
        } finally { expected.forEach(Bitmap::recycle); delivered.forEach(Bitmap::recycle); folder.deleteRecursively() }
    }

}
