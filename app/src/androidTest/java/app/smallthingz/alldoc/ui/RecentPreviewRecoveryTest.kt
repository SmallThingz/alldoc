package app.smallthingz.alldoc.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.smallthingz.alldoc.state.DocumentMetadata
import app.smallthingz.alldoc.state.DocumentState
import app.smallthingz.alldoc.state.StoredDocument
import app.smallthingz.alldoc.theme.ReadingMode
import app.smallthingz.alldoc.theme.ThemeSettings
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Own worker and cache only; no Activity, provider, history, or global settings. */
@RunWith(AndroidJUnit4::class)
class RecentPreviewRecoveryTest {
    private data class Result(val hash: String, val image: Bitmap, val revision: Long)
    private fun document() = StoredDocument("1".padStart(64, '0'),
        DocumentMetadata("Saved.txt", "text/plain", 8, "content://preview/saved"), DocumentState(), 1)
    private fun folder() = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
        "recent-recovery-${UUID.randomUUID()}")
    private fun original() = Bitmap.createBitmap(12, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
    private fun drain(worker: ExecutorService) { worker.submit {}.get(5, TimeUnit.SECONDS) }
    private fun cleanup(cache: DocumentPreviews, worker: ExecutorService, images: Collection<Bitmap>, folder: File) {
        cache.close()
        worker.shutdownNow()
        try { assertTrue("Owned preview worker did not finish", worker.awaitTermination(5, TimeUnit.SECONDS)) }
        finally { images.forEach { if (!it.isRecycled) it.recycle() }; folder.deleteRecursively() }
    }

    @Test fun coldRecoveryStoresOriginalAndNewOwnerUsesDiskWithoutReopening() {
        val folder = folder()
        val worker = Executors.newSingleThreadExecutor()
        val images = ConcurrentLinkedQueue<Bitmap>()
        val ready = LinkedBlockingQueue<Result>()
        val recoveries = AtomicInteger()
        val document = document()
        fun owner() = DocumentPreviews({ folder }, executor = worker, recover = { saved, signal ->
            check(Looper.myLooper() != Looper.getMainLooper())
            signal.throwIfCanceled()
            assertEquals(document, saved)
            recoveries.incrementAndGet()
            DocumentPreviews.Companion.Cached(original(), emptyList())
        }) { hash, image, revision -> images.add(image); ready.add(Result(hash, image, revision)) }
        var cache = owner()
        try {
            cache.setAppearance(ThemeSettings(ReadingMode.INVERT))
            cache.setVisible(listOf(document))
            cache.request(document.hash)
            drain(worker)
            val first = requireNotNull(ready.poll()) { "Cold visible document did not recover a cover" }
            assertEquals(document.hash, first.hash)
            assertTrue(cache.isCurrent(first.revision))
            assertEquals(Color.BLACK, first.image.getPixel(0, 0))
            assertEquals(1, recoveries.get())
            val file = File(folder, "${document.hash}.png")
            val stored = requireNotNull(DocumentPreviews.readCached(file))
            try {
                assertEquals(Color.WHITE, stored.image.getPixel(0, 0))
                assertEquals(emptyList<app.smallthingz.alldoc.theme.RasterRegion>(), stored.regions)
            } finally { stored.image.recycle() }
            val encoded = file.readBytes()
            cache.close()
            ready.clear()
            cache = owner() // No shared memory cache: this owner must read the persisted original.
            cache.setVisible(listOf(document))
            cache.request(document.hash)
            drain(worker)
            val reopened = requireNotNull(ready.poll()) { "New owner did not reuse the disk cover" }
            assertEquals(Color.WHITE, reopened.image.getPixel(0, 0))
            assertEquals("Warm cache must not reopen document bytes", 1, recoveries.get())
            assertArrayEquals("Themed publication must leave the original PNG unchanged", encoded, file.readBytes())
        } finally { cleanup(cache, worker, images, folder) }
    }

    @Test fun hidingRecentsCancelsRecoveryWithoutStaleOutputAndRevisitRetries() {
        val folder = folder()
        val worker = Executors.newSingleThreadExecutor()
        val images = ConcurrentLinkedQueue<Bitmap>()
        val ready = LinkedBlockingQueue<Result>()
        val entered = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val cancelledImage = AtomicReference<Bitmap>()
        val release = CountDownLatch(1)
        val recoveries = AtomicInteger()
        val document = document()
        val cache = DocumentPreviews({ folder }, executor = worker, recover = { _, signal ->
            check(Looper.myLooper() != Looper.getMainLooper())
            if (recoveries.incrementAndGet() == 1) {
                signal.setOnCancelListener { cancelled.countDown() }
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                // Simulate a provider returning after cancellation: the owner must reject its result.
            }
            val image = original()
            if (signal.isCanceled) cancelledImage.set(image)
            DocumentPreviews.Companion.Cached(image, emptyList())
        }) { hash, image, revision -> images.add(image); ready.add(Result(hash, image, revision)) }
        try {
            cache.setVisible(listOf(document))
            cache.request(document.hash)
            assertTrue("Visible cache miss did not start recovery", entered.await(5, TimeUnit.SECONDS))
            cache.setVisible(emptyList())
            assertTrue("Hiding Recents did not cancel its source", cancelled.await(5, TimeUnit.SECONDS))
            release.countDown()
            drain(worker)
            assertTrue("A hidden recovery published a stale cover", ready.isEmpty())
            assertTrue("Cancelled recovery leaked its unpublished bitmap", requireNotNull(cancelledImage.get()).isRecycled)
            val file = File(folder, "${document.hash}.png")
            assertFalse("A cancelled recovery wrote its original PNG", file.exists())
            assertFalse("A cancelled recovery wrote raster metadata", DocumentPreviews.regionsFile(file).exists())
            assertEquals(1, recoveries.get())
            cache.setVisible(listOf(document))
            cache.request(document.hash)
            drain(worker)
            val retry = requireNotNull(ready.poll()) { "Returning to Recents did not retry the cancelled miss" }
            assertEquals(document.hash, retry.hash)
            assertTrue(cache.isCurrent(retry.revision))
            assertEquals(2, recoveries.get())
            assertTrue(file.isFile)
        } finally {
            release.countDown()
            cleanup(cache, worker, images, folder)
        }
    }
    @Test fun cancelledRecoveryCommitPreservesPriorCoverAndRemovesPartialOutput() {
        val folder = folder().apply { mkdirs() }
        val image = original()
        val file = File(folder, "${document().hash}.png")
        try {
            // The guard runs after actual PNG encoding, before the owned temporary file is renamed.
            assertThrows(IllegalStateException::class.java) {
                DocumentPreviews.write(folder, file, image, emptyList(), valid = { false })
            }
            assertTrue(folder.listFiles().orEmpty().isEmpty())
            var checks = 0
            assertThrows(IllegalStateException::class.java) {
                DocumentPreviews.write(folder, file, image, emptyList(), valid = { ++checks == 1 })
            }
            assertEquals("Cancellation must also be checked after both renames", 2, checks)
            assertTrue("Cancelled commit left PNG, metadata or temporary files", folder.listFiles().orEmpty().isEmpty())
            DocumentPreviews.write(folder, file, image, emptyList())
            val originalBytes = file.readBytes()
            val originalRegions = DocumentPreviews.regionsFile(file).readBytes()
            image.eraseColor(Color.RED)
            DocumentPreviews.write(folder, file, image, emptyList(), valid = { true })
            assertThrows(IllegalStateException::class.java) {
                DocumentPreviews.write(folder, file, image, emptyList(), valid = { false })
            }
            assertArrayEquals("Recovery must not replace or delete a previously restored cover", originalBytes, file.readBytes())
            assertArrayEquals(originalRegions, DocumentPreviews.regionsFile(file).readBytes())
            assertEquals(2, folder.listFiles().orEmpty().size)
        } finally { image.recycle(); folder.deleteRecursively() }
    }
}
