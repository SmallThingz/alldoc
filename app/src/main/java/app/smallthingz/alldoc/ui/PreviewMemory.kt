package app.smallthingz.alldoc.ui

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import app.smallthingz.alldoc.theme.ThemeSettings

/** Shared immutable covers. Eviction drops references; Compose may still be drawing a cover. */
internal class PreviewMemory(private val maxBytes: Long) : ComponentCallbacks2 {
    data class Key(val document: String, val width: Int, val height: Int, val appearance: ThemeSettings)
    private data class Entry(val image: Bitmap, val bytes: Int)
    private val images = LinkedHashMap<Key, Entry>(16, .75f, true)
    private var revision = 0L
    @Synchronized fun generation(): Long = revision
    internal var bytes = 0L
        private set

    @Synchronized operator fun get(key: Key): Bitmap? {
        val entry = images[key] ?: return null
        if (!entry.image.isRecycled) return entry.image
        remove(key)
        return null
    }

    @Synchronized fun put(key: Key, image: Bitmap, generation: Long = revision) {
        if (generation != revision) return
        remove(key)
        if (image.isRecycled || image.allocationByteCount.toLong() > maxBytes) return
        images[key] = Entry(image, image.allocationByteCount)
        bytes += image.allocationByteCount
        trim(maxBytes)
    }

    @Synchronized fun clear(prefix: String) {
        revision++ // Late work from another owner cannot resurrect a cleared or replaced cover.
        val entries = images.entries.iterator()
        while (entries.hasNext()) {
            val entry = entries.next()
            if (entry.key.document.startsWith(prefix)) { bytes -= entry.value.bytes; entries.remove() }
        }
    }

    private fun remove(key: Key) { images.remove(key)?.let { bytes -= it.bytes } }

    @Synchronized private fun trim(limit: Long) {
        val entries = images.entries.iterator()
        while ((bytes > limit || images.size > MAX_COVERS) && entries.hasNext()) {
            bytes -= entries.next().value.bytes
            entries.remove()
        }
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE ||
                level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> clear("")
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
                level == ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> trim(maxBytes / 2)
        }
    }
    override fun onLowMemory() = clear("")
    override fun onConfigurationChanged(newConfig: Configuration) = Unit

    companion object {
        private const val MAX_COVERS = 512
        private var shared: PreviewMemory? = null

        @Synchronized fun forApplication(context: Context): PreviewMemory {
            val application = context.applicationContext
            // A small fraction of the app heap, allocated only as covers are requested.
            val budget = (Runtime.getRuntime().maxMemory() / 16).coerceIn(4L shl 20, 64L shl 20)
            if (application !is Application) return PreviewMemory(budget)
            return shared ?: PreviewMemory(budget).also {
                shared = it
                application.registerComponentCallbacks(it)
            }
        }
    }
}
