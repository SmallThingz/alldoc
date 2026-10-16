package app.smallthingz.alldoc.ui

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.os.OperationCanceledException
import android.os.SystemClock
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/** Synthetic fixture provider exists only in debug builds, which include the Kotlin runtime. */
class ReaderFixtures : ContentProvider() {
    private val heldPipes = mutableListOf<ParcelFileDescriptor>()
    override fun onCreate() = true
    private fun shared(uri: Uri): File? {
        if (uri.pathSegments.firstOrNull() != "shared") return null
        val name = uri.lastPathSegment.orEmpty()
        require(uri.pathSegments.size == 2 && Regex("[0-9a-f-]{36}\\.md").matches(name))
        return File(requireNotNull(context).cacheDir, "reader-shared/$name")
    }
    override fun getType(uri: Uri): String? {
        if (shared(uri) != null) return if (uri.getQueryParameter("title")?.endsWith(".html") == true) "text/html" else "text/markdown"
        if (name(uri) == "pdfpipe") return null
        if (name(uri) == "metadata.md") throw UnsupportedOperationException("No type metadata")
        return "text/html"
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        shared(uri)?.let { file ->
            return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply {
                addRow(arrayOf<Any?>(uri.getQueryParameter("title") ?: file.name, file.length()))
            }
        }
        when (name(uri)) {
            "metadata.md", "pdfpipe" -> throw IllegalArgumentException("No display metadata")
            "metadata-cancel.md" -> throw OperationCanceledException("Metadata request cancelled")
        }
        return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply {
            addRow(arrayOf<Any?>(name(uri) + ".html", body(uri).toByteArray(Charsets.UTF_8).size))
        }
    }

    override fun openFile(uri: Uri, mode: String) = openFile(uri, mode, null)
    override fun openFile(uri: Uri, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        shared(uri)?.let { file ->
            require(mode == "r") { "Read-only fixture" }
            signal?.throwIfCanceled()
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        val name = name(uri)
        try {
            if (name == "slow") repeat(20) {
                SystemClock.sleep(20)
                signal?.throwIfCanceled()
            }
            if (name == "stalled") {
                require(mode != "r") { "Write-only fixture" }
                val pipe = ParcelFileDescriptor.createPipe()
                synchronized(heldPipes) { heldPipes.add(pipe[0]) }
                return pipe[1]
            }
            if (name == "pdfpipe") {
                require(mode == "r") { "Read-only fixture" }
                val pipe = ParcelFileDescriptor.createReliablePipe()
                Thread({
                    try {
                        File(requireNotNull(context).cacheDir, "reader-pdfpipe.pdf").inputStream().use { input ->
                            ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { output -> input.copyTo(output) }
                        }
                    } catch (_: IOException) { runCatching { pipe[1].closeWithError("Fixture stream failed") } }
                }, "alldoc-pdf-fixture").start()
                return pipe[0]
            }
            val file = File(requireNotNull(context).cacheDir, "reader-$name.html")
            if (mode == "r") {
                if (name != "export") file.writeText(body(uri), Charsets.UTF_8)
                return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            }
            require(name == "export") { "Read-only fixture" }
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_CREATE or
                ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_WRITE_ONLY)
        } catch (failure: IOException) {
            throw FileNotFoundException(failure.message)
        }
    }

    private fun name(uri: Uri): String = requireNotNull(uri.lastPathSegment).also {
        require(it in setOf("one", "two", "slow", "export", "large", "stalled", "metadata.md", "metadata-cancel.md", "pdfpipe")) { "Unknown fixture" }
    }
    private fun body(uri: Uri): String = name(uri).let {
        val text = if (it == "large") "AllDoc export fixture\n".repeat(8192)
            else "AllDoc fixture $it\n\nReadable Unicode: café, क्षि.\nLast line."
        "<html><body><pre>$text</pre></body></html>"
    }
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        require(method == "releasePipes") { "Unknown fixture action" }
        synchronized(heldPipes) {
            heldPipes.forEach { runCatching { it.close() } }
            heldPipes.clear()
        }
        return Bundle.EMPTY
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
