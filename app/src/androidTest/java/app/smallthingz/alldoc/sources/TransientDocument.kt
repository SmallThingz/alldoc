package app.smallthingz.alldoc.sources

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID

/** An ungranted provider document disappears when its caller closes the fixture. */
internal class TransientDocument(text: String, title: String = "Shared.md") : AutoCloseable {
    private val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
        "reader-shared/${UUID.randomUUID()}.md").apply { parentFile!!.mkdirs(); writeText(text) }
    val uri: Uri = Uri.Builder().scheme("content").authority("app.smallthingz.alldoc.test.reader-fixtures")
        .appendPath("shared").appendPath(file.name).appendQueryParameter("title", title).build()
    override fun close() { file.delete() }
}
