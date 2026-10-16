package app.smallthingz.alldoc.clienttest

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch

/** Only generated test files, readable by another UID through an explicit URI grant. */
class OacpFixtureProvider : ContentProvider() {
    override fun onCreate() = true
    private fun file(uri: Uri): File {
        val name = uri.lastPathSegment.orEmpty()
        if (uri.pathSegments.size != 1 || !Regex("[0-9a-f-]{36}\\.md").matches(name)) throw FileNotFoundException()
        return File(requireNotNull(context).cacheDir, "oacp-fixtures/$name").also {
            if (!it.isFile) throw FileNotFoundException()
        }
    }
    override fun getType(uri: Uri): String { file(uri); return "text/markdown" }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Read only")
        val file = file(uri)
        stalls[file.name]?.let { stalled ->
            val pipe = ParcelFileDescriptor.createReliablePipe()
            stalled.writers.add(pipe[1])
            stalled.opened.countDown()
            return pipe[0]
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val file = file(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply { addRow(columns.map {
            when (it) { OpenableColumns.DISPLAY_NAME -> file.name; OpenableColumns.SIZE -> file.length(); else -> null }
        }) }
    }
    class Stalled : AutoCloseable {
        val opened = CountDownLatch(1)
        val writers = ConcurrentLinkedQueue<ParcelFileDescriptor>()
        override fun close() { while (true) (writers.poll() ?: break).close() }
    }
    companion object { val stalls = ConcurrentHashMap<String, Stalled>() }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
}
