package app.smallthingz.alldoc.sources

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.os.Looper
import app.smallthingz.alldoc.core.DocumentFormats
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

data class ExampleDocument(val id: String, val title: String, val asset: String, val mime: String,
    val extension: String, val description: String, val category: String, val bytes: Long, val sha256: String) {
    val name: String get() = asset.substringAfterLast('/')
}

/** Offline, first-party examples. Browsing reads only the catalog; opening copies one tiny asset. */
object ExampleDocuments {
    private const val MAX_FILE_BYTES = 512 * 1024L
    private const val MAX_TOTAL_BYTES = 4 * 1024 * 1024L
    private val lock = ReentrantLock()
    private val idPattern = Regex("[a-z0-9][a-z0-9-]{0,63}")
    private val assetPattern = Regex("examples/(office|text)/[A-Za-z0-9][A-Za-z0-9._ -]{0,100}")
    private val hashPattern = Regex("[a-f0-9]{64}")
    @Volatile private var catalog: List<ExampleDocument>? = null

    fun list(context: Context): List<ExampleDocument> {
        worker()
        catalog?.let { return it }
        val raw = context.assets.open("examples/manifest.json").use { input ->
            val buffer = ByteArray(256 * 1024 + 1)
            var size = 0
            while (size < buffer.size) {
                val count = input.read(buffer, size, buffer.size - size)
                if (count < 0) break
                size += count
            }
            require(size < buffer.size) { "Example catalog is too large." }
            String(buffer, 0, size, Charsets.UTF_8)
        }
        val manifest = JSONObject(raw)
        require(manifest.getInt("version") == 1)
        val entries = manifest.getJSONArray("examples")
        require(entries.length() in 1..256)
        val examples = List(entries.length()) { index ->
            val entry = entries.getJSONObject(index)
            ExampleDocument(entry.getString("id"), entry.getString("title"), entry.getString("asset"),
                entry.getString("mime"), entry.getString("extension"), entry.getString("description"),
                entry.getString("category"), entry.getLong("bytes"), entry.getString("sha256")).also {
                require(it.id.matches(idPattern))
                require(it.asset.matches(assetPattern))
                require(it.extension == DocumentFormats.extension(it.name))
                require(it.title.length in 1..80 && it.description.length <= 160 && it.category.length in 1..40)
                require(it.mime.length in 1..128 && it.bytes in 1..MAX_FILE_BYTES)
                require(it.sha256.matches(hashPattern))
            }
        }
        require(examples.sumOf { it.bytes } <= MAX_TOTAL_BYTES)
        require(examples.map { it.id }.toSet().size == examples.size)
        require(examples.map { it.name.lowercase(java.util.Locale.ROOT) }.toSet().size == examples.size)
        val supported = DocumentFormats.all.flatMap { it.extensions }.groupingBy { it }.eachCount()
        require(examples.groupingBy { it.extension }.eachCount() == supported) { "Example catalog does not match supported formats." }
        return examples.also { catalog = it }
    }

    /** Stable private files preserve recent-document reopen; the shipped catalog bounds storage. */
    fun materialize(context: Context, example: ExampleDocument, signal: CancellationSignal): Uri {
        worker(); signal.throwIfCanceled()
        val examples = list(context)
        require(example in examples) { "Unknown example." }
        while (!lock.tryLock(50, TimeUnit.MILLISECONDS)) signal.throwIfCanceled()
        try {
            signal.throwIfCanceled()
            val directory = File(context.filesDir, "examples")
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot open example storage.")
            val sizes = examples.associate { it.name to it.bytes }
            directory.listFiles()?.forEach { file ->
                signal.throwIfCanceled()
                if (sizes[file.name] != file.length() && !file.delete()) throw IOException("Cannot clean example storage.")
            }
            val target = File(directory, example.name)
            if (target.isFile && target.length() == example.bytes && hash(target, example.bytes, signal) == example.sha256)
                return Uri.fromFile(target)
            val temporary = File.createTempFile(".partial-", ".tmp", directory)
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                var size = 0L
                context.assets.open(example.asset).use { input ->
                    temporary.outputStream().use { output ->
                        val buffer = ByteArray(32768)
                        while (true) {
                            signal.throwIfCanceled()
                            val count = input.read(buffer)
                            if (count < 0) break
                            size += count
                            if (size > example.bytes) throw IOException("Example size changed.")
                            output.write(buffer, 0, count); digest.update(buffer, 0, count)
                        }
                    }
                }
                if (size != example.bytes || digest.digest().hex() != example.sha256)
                    throw IOException("Example content changed.")
                signal.throwIfCanceled()
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                return Uri.fromFile(target)
            } finally { temporary.delete() }
        } finally { lock.unlock() }
    }

    private fun hash(file: File, limit: Long, signal: CancellationSignal): String? {
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        file.inputStream().use { input ->
            val buffer = ByteArray(32768)
            while (true) {
                signal.throwIfCanceled()
                val count = input.read(buffer)
                if (count < 0) break
                size += count
                if (size > limit) return null
                digest.update(buffer, 0, count)
            }
        }
        signal.throwIfCanceled()
        return digest.digest().hex()
    }

    private fun worker() = check(Looper.myLooper() != Looper.getMainLooper()) { "Example I/O requires a worker thread." }
}
