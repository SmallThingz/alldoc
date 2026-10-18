package app.smallthingz.alldoc.clienttest

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException

/** Development fixtures only; access from another UID requires an explicit read grant. */
class StressFixtureProvider : ContentProvider() {
    override fun onCreate() = true
    private fun fixture(uri: Uri): Fixture {
        if (uri.pathSegments.size != 1) throw FileNotFoundException()
        return fixture(requireNotNull(context), uri.pathSegments.single())
    }
    override fun getType(uri: Uri) = fixture(uri).mime
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Read only")
        return ParcelFileDescriptor.open(fixture(uri).file, ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val fixture = fixture(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply { addRow(columns.map { when (it) {
            OpenableColumns.DISPLAY_NAME -> fixture.name
            OpenableColumns.SIZE -> fixture.file.length()
            else -> null
        } }) }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()

    data class Fixture(val file: File, val name: String, val mime: String)
    companion object {
        const val AUTHORITY = "app.smallthingz.alldoc.clienttest.stress-fixtures"
        fun fixture(context: Context, token: String): Fixture {
            require(token.matches(Regex("[a-f0-9]{32}")))
            val directory = File(context.filesDir, "stress-$token")
            val source = File(directory, "input")
            val metadata = File(directory, "metadata.json")
            require(source.isFile && source.length() in 1..128L * 1024 * 1024)
            require(source.canonicalFile.parentFile == directory.canonicalFile)
            require(metadata.isFile && metadata.length() in 1..4096)
            val info = JSONObject(metadata.readText())
            val name = info.getString("name"); val mime = info.getString("mime")
            require(name.isNotBlank() && name.length <= 512 && mime.length <= 128)
            require(mime.matches(Regex("[a-zA-Z0-9.+_-]+/[a-zA-Z0-9.+_-]+")))
            require(info.getString("sha256").matches(Regex("[a-f0-9]{64}")))
            require(info.getLong("bytes") == source.length())
            return Fixture(source, name, mime)
        }
    }
}
