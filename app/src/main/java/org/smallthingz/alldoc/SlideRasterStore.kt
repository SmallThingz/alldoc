package org.smallthingz.alldoc

import android.graphics.Bitmap
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * One store per open presentation, shared by all visible/prefetched pages. The budget covers
 * cached and leased pixels plus decode reservations. It is not a strict Android native-memory
 * cap: previously published bitmaps may remain in Compose/render-thread references after eviction.
 * Only unpublished, cancelled/failed decode results are explicitly recycled.
 */
internal class SlideRasterStore(
    private val maxCachedPixels: Long = 8_000_000L,
    private val decoder: (ByteArray, Long) -> Bitmap = ::decodeSlideRaster,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : AutoCloseable {
    init {
        require(maxCachedPixels > 0)
    }

    private data class Key(val digest: String, val budget: Long)

    private class Entry(val bitmap: Bitmap, val pixels: Long, var leases: Int = 0)

    private val lock = Any()
    private val decoding = Mutex()
    private val entries = LinkedHashMap<Key, Entry>(16, .75f, true)
    private var cachedPixels = 0L
    private var reservedPixels = 0L
    private var closed = false

    internal val accountedPixels: Long
        get() = synchronized(lock) { cachedPixels + reservedPixels }

    /** Closing a lease is idempotent and never invalidates a bitmap already handed to Compose. */
    internal class Lease internal constructor(
        val bitmap: Bitmap,
        private val release: () -> Unit,
    ) : AutoCloseable {
        private var released = false

        override fun close() {
            synchronized(this) {
                if (released) return
                released = true
                release()
            }
        }
    }

    suspend fun acquire(bytes: ByteArray, targetPixels: Long): Lease {
        require(bytes.isNotEmpty() && bytes.size <= MAX_SLIDE_IMAGE_BYTES)
        require(targetPixels > 0)
        // Equal content, including repeated master images, shares a cache entry. Rounding down
        // makes zoom/viewport noise within a bucket harmless and never enlarges the caller's budget.
        val budget = java.lang.Long.highestOneBit(
            minOf(targetPixels, MAX_IMAGE_DECODE_PIXELS, maxCachedPixels)
        )
        var acquired: Lease? = null
        try {
            return withContext(dispatcher) {
                currentCoroutineContext().ensureActive()
                decoding.withLock {
                    currentCoroutineContext().ensureActive()
                    val source = bytes.clone()
                    val key = Key(
                        Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(source)),
                        budget,
                    )
                    val hit = synchronized(lock) {
                        check(!closed) { "Presentation image cache is closed" }
                        entries[key]?.let { lease(it) }
                    }
                    if (hit != null) {
                        acquired = hit
                        return@withLock hit
                    }
                    synchronized(lock) {
                        check(!closed) { "Presentation image cache is closed" }
                        val iterator = entries.entries.iterator()
                        while (cachedPixels + reservedPixels > maxCachedPixels - budget && iterator.hasNext()) {
                            val entry = iterator.next().value
                            if (entry.leases == 0) {
                                iterator.remove()
                                cachedPixels -= entry.pixels
                            }
                        }
                        check(cachedPixels + reservedPixels <= maxCachedPixels - budget) {
                            "Visible presentation images exceed the shared preview memory budget"
                        }
                        reservedPixels += budget
                    }
                    var unpublished: Bitmap? = null
                    var reserved = true
                    try {
                        currentCoroutineContext().ensureActive()
                        val bitmap = decoder(source, budget)
                        unpublished = bitmap
                        currentCoroutineContext().ensureActive()
                        val pixels = bitmap.width.toLong() * bitmap.height
                        check(pixels in 1..budget && bitmap.allocationByteCount.toLong() <= budget * 4) {
                            "Image decoder exceeded its reserved preview budget"
                        }
                        synchronized(lock) {
                            check(!closed) { "Presentation image cache is closed" }
                            val entry = Entry(bitmap, pixels)
                            entries[key] = entry
                            reservedPixels -= budget
                            reserved = false
                            cachedPixels += pixels
                            // Ownership passes to the cache before a lease can escape this lock.
                            unpublished = null
                            lease(entry).also { acquired = it }
                        }
                    } finally {
                        unpublished?.recycle()
                        if (reserved) synchronized(lock) { reservedPixels -= budget }
                    }
                }
            }
        } catch (error: Throwable) {
            // withContext can discard its result when the caller is cancelled during dispatch.
            // The cache owns the published bitmap; reclaim the otherwise-undelivered lease only.
            acquired?.close()
            throw error
        }
    }

    /** Must be called with lock held. */
    private fun lease(entry: Entry): Lease {
        entry.leases++
        return Lease(entry.bitmap) { synchronized(lock) { entry.leases-- } }
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            entries.clear()
            cachedPixels = 0
            // An in-flight decoder retains and eventually releases its own reservation.
        }
    }
}
