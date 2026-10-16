package app.smallthingz.alldoc.render

import java.io.File
import java.io.IOException

/** Once per app process: reclaim crash leftovers without removing a live sibling's work. */
internal class RenderCache {
    private var directory: File? = null

    @Synchronized fun prepare(root: File, pid: Int, alive: (Int) -> Boolean): File {
        directory?.let {
            if (!it.isDirectory && !it.mkdirs()) throw IOException("Cannot prepare visual reader cache")
            return it
        }
        val selected = File(root, "render-$pid")
        root.listFiles()?.forEach { entry ->
            val owner = entry.name.takeIf { it.startsWith("render-") }?.removePrefix("render-")?.toIntOrNull()
            if (entry.name == "render" || entry == selected || owner != null && !alive(owner)) {
                if (!entry.deleteRecursively()) throw IOException("Cannot remove obsolete visual reader cache")
            }
        }
        if (!selected.mkdirs() && !selected.isDirectory) throw IOException("Cannot prepare visual reader cache")
        directory = selected
        return selected
    }
}

/** The process-wide render semaphore owns this sole scratch slot until cleanup. */
internal fun prepareRenderScratch(root: File): File {
    if (!root.isDirectory && !root.mkdirs()) throw IOException("Cannot prepare visual reader cache")
    val directory = File(root, "visual")
    if (directory.exists() && !directory.deleteRecursively())
        throw IOException("Cannot clear visual reader scratch")
    if (!directory.mkdir()) throw IOException("Cannot prepare visual reader scratch")
    return directory
}
