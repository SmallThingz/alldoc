package org.smallthingz.alldoc

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SlideRasterStoreTest {
    private fun png(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        bitmap.recycle()
        return output.toByteArray()
    }

    @Test fun equalContentAndNearbyTargetsShareOneBudgetAndBitmap() = runBlocking {
        val store = SlideRasterStore(128)
        val source = png(android.graphics.Color.BLUE)
        val first = store.acquire(source, 64)
        val second = store.acquire(source.clone(), 100)
        assertSame(first.bitmap, second.bitmap)
        assertEquals(64L, store.accountedPixels)
        first.close()
        first.close()
        assertFalse(second.bitmap.isRecycled)
        second.close()
        store.close()
        assertFalse(second.bitmap.isRecycled)
        assertEquals(0L, store.accountedPixels)
    }

    @Test fun leasedEntriesCannotBeEvictedButReleasedEntriesCan() = runBlocking {
        val store = SlideRasterStore(64)
        val first = store.acquire(png(android.graphics.Color.BLUE), 64)
        try {
            store.acquire(png(android.graphics.Color.RED), 64)
            fail("An active lease must retain its shared budget")
        } catch (_: IllegalStateException) { }
        assertEquals(64L, store.accountedPixels)
        first.close()
        val next = store.acquire(png(android.graphics.Color.RED), 64)
        assertEquals(64L, store.accountedPixels)
        assertNotSame(first.bitmap, next.bitmap)
        assertFalse(first.bitmap.isRecycled)
        next.close()
        store.close()
    }

    @Test fun invalidImagesReleaseReservationsAndCanBeRetriedSafely() = runBlocking {
        val store = SlideRasterStore(64)
        try {
            store.acquire(byteArrayOf(1, 2, 3), 64)
            fail("Invalid raster should fail")
        } catch (_: IllegalArgumentException) { }
        assertEquals(0L, store.accountedPixels)
        store.acquire(png(android.graphics.Color.BLUE), 64).close()
        assertEquals(64L, store.accountedPixels)
        store.close()
    }

    @Test fun cancellationRecyclesOnlyTheUnpublishedResult() = runBlocking {
        var decoded: Bitmap? = null
        var cancelDecode: (() -> Unit)? = null
        val store = SlideRasterStore(64, decoder = { _, _ ->
            Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).also {
                decoded = it
                cancelDecode!!()
            }
        })
        val result = async {
            val ownJob = currentCoroutineContext().job
            cancelDecode = { ownJob.cancel() }
            store.acquire(byteArrayOf(1), 64)
        }
        try {
            result.await()
            fail("Cancelled decode must not return a lease")
        } catch (_: CancellationException) { }
        assertTrue(decoded!!.isRecycled)
        assertEquals(0L, store.accountedPixels)
        store.close()
    }

    @Test fun closingDuringDecodeRecyclesOnlyTheUnpublishedResult() = runBlocking {
        lateinit var store: SlideRasterStore
        var decoded: Bitmap? = null
        store = SlideRasterStore(64, decoder = { _, _ ->
            Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).also {
                decoded = it
                store.close()
            }
        })
        try {
            store.acquire(byteArrayOf(1), 64)
            fail("Closed store must not publish a decode result")
        } catch (_: IllegalStateException) { }
        assertTrue(decoded!!.isRecycled)
        assertEquals(0L, store.accountedPixels)
    }

    @Test fun completedStoreCannotAcquireAgain() = runBlocking {
        val store = SlideRasterStore(64, dispatcher = Dispatchers.Unconfined)
        val lease = store.acquire(png(android.graphics.Color.BLUE), 64)
        store.close()
        store.close()
        lease.close()
        lease.close()
        assertFalse(lease.bitmap.isRecycled)
        try {
            store.acquire(png(android.graphics.Color.BLUE), 64)
            fail("Disposed presentation must not start a new decode")
        } catch (_: IllegalStateException) { }
        assertEquals(0L, store.accountedPixels)
    }
}
