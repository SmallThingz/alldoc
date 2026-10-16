package app.smallthingz.alldoc.oacp

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/** Only generated outputs live here. Inputs and document history never enter this provider. */
internal object CapabilityFiles {
    const val OUTPUT_LIMIT = 32L * 1024 * 1024
    private const val CACHE_LIMIT = 64L * 1024 * 1024
    private const val LIFETIME = 60L * 60 * 1000
    private val active = HashSet<File>()
    private fun directory(context: Context) = File(context.cacheDir, "oacp-results").also {
        if (!it.isDirectory && !it.mkdirs()) throw IOException("Cannot create result storage.")
    }
    fun uri(context: Context, file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.oacp.results", file)

    @Synchronized fun create(context: Context): File {
        prune(context)
        return File.createTempFile("pending-", ".part", directory(context)).also { active.add(it) }
    }
    @Synchronized fun publish(context: Context, pending: File, extension: String): File {
        val target = File(pending.parentFile, pending.name.removeSuffix(".part") + ".$extension")
        if (!pending.renameTo(target)) throw IOException("Cannot save the result.")
        active.remove(pending)
        target.setLastModified(System.currentTimeMillis())
        prune(context, target)
        if (!target.exists()) throw IOException("Result storage is full.")
        return target
    }
    @Synchronized fun discard(file: File) { active.remove(file); file.delete() }

    @Synchronized fun prune(context: Context, keep: File? = null) {
        val now = System.currentTimeMillis()
        val files = directory(context).listFiles().orEmpty().filter { it.isFile && it !in active }
        val ready = files.filter { file ->
            if (file.extension == "part" || now - file.lastModified() >= LIFETIME || file.lastModified() > now) {
                remove(context, file); false
            } else true
        }.sortedBy { it.lastModified() }.toMutableList()
        var bytes = ready.sumOf { it.length() }
        while (ready.size > 8 || bytes > CACHE_LIMIT) {
            val file = ready.firstOrNull { it != keep } ?: ready.first()
            ready.remove(file); bytes -= file.length(); remove(context, file)
        }
    }
    private fun remove(context: Context, file: File) {
        context.revokeUriPermission(uri(context, file), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        file.delete()
    }
}

/** FileProvider performs grant/path checks; expiry and read-only mode are enforced here too. */
class CapabilityResultProvider : FileProvider() {
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Results are read-only.")
        CapabilityFiles.prune(requireNotNull(context))
        return super.openFile(uri, mode) ?: throw FileNotFoundException("Result unavailable.")
    }
}
