package app.smallthingz.alldoc.engine

import android.content.Intent
import android.graphics.Bitmap
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import androidx.activity.result.contract.ActivityResultContracts
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.smallthingz.alldoc.core.DocumentFormats
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DocumentAdmissionTest {
    @Test fun standaloneNonDocumentsAreRejectedAndCloseTheirDescriptors() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val picker = ActivityResultContracts.OpenDocument().createIntent(context, DocumentFormats.pickerMimeTypes)
        assertTrue("Named XML documents must be selectable with either generic XML MIME",
            picker.getStringArrayExtra(Intent.EXTRA_MIME_TYPES).orEmpty().toList().containsAll(listOf("application/xml", "text/xml")))
        assertFalse("The picker carrier must not advertise generic XML support", "text/xml" in DocumentFormats.intentMimeTypes)
        for ((asset, mime) in listOf(
            "examples/office/office-guide.fodt" to "application/vnd.oasis.opendocument.text-flat-xml",
            "examples/text/coastal-story.fb2" to "application/x-fictionbook+xml")) {
            val bytes = context.assets.open(asset).use { it.readBytes() }
            val file = File.createTempFile("xml-carrier-", ".fixture", context.cacheDir).apply { writeBytes(bytes) }
            try {
                val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                val handle = descriptor.fileDescriptor
                DocumentEngine.open(context, descriptor, asset.substringAfterLast('/'), "text/xml", CancellationSignal()).use { session ->
                    assertEquals("Carrier MIME must resolve to the document type", mime, session.info.mimeType)
                    assertTrue("The supported document must retain extractable content", session.text(CancellationSignal()).isNotBlank())
                }
                assertFalse("Successful admission also consumes its descriptor", handle.valid())
                assertArrayEquals("Reading through a carrier must preserve source bytes", bytes, file.readBytes())
            } finally { file.delete() }
        }
        val fixtures = linkedMapOf(
            "notes.txt" to ("text/plain" to "Ordinary text".toByteArray()),
            "fake.doc" to ("application/msword" to "Not a Word document".toByteArray()),
            "graph.dot" to ("text/vnd.graphviz" to "digraph G { a -> b }".toByteArray()),
            "main.kt" to ("text/x-kotlin" to "fun main() = Unit".toByteArray()),
            "data.json" to ("application/json" to "{\"value\":1}".toByteArray()),
            "table.csv" to ("text/csv" to "name,value\nitem,1".toByteArray()),
            "data.xml" to ("application/xml" to "<objects><item/></objects>".toByteArray()),
            "data-text.xml" to ("text/xml" to "<?xml version=\"1.0\"?><objects><item/></objects>".toByteArray()),
            "mail.eml" to ("message/rfc822" to "Subject: Note\nContent-Type: text/plain\n\nMessage".toByteArray()),
            "event.ics" to ("text/calendar" to "BEGIN:VCALENDAR\nEND:VCALENDAR".toByteArray()),
            "certificate.pem" to ("application/x-pem-file" to "-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----".toByteArray()),
            "scene.obj" to ("model/obj" to "v 0 0 0".toByteArray()),
            "picture.svg" to ("image/svg+xml" to "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"8\" height=\"4\"/>".toByteArray()))
        val image = Bitmap.createBitmap(8, 4, Bitmap.Config.ARGB_8888)
        try {
            fixtures["picture.png"] = "image/png" to ByteArrayOutputStream().also {
                assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it))
            }.toByteArray()
        } finally { image.recycle() }
        for ((name, mime) in listOf("sample.psd" to "image/vnd.adobe.photoshop", "sample.tiff" to "image/tiff"))
            fixtures[name] = mime to instrumentation.context.assets.open("office/$name").use { it.readBytes() }
        for (name in listOf("notes.txt", "mail.eml", "picture.png")) {
            val payload = fixtures.getValue(name).second
            val archive = ByteArrayOutputStream().also { output ->
                ZipOutputStream(output).use { zip ->
                    for ((entry, bytes) in listOf("padding" to ByteArray(512 * 1024), name to payload)) {
                        zip.putNextEntry(ZipEntry(entry)); zip.write(bytes); zip.closeEntry()
                    }
                }
            }.toByteArray()
            fixtures["$name.zip"] = "application/zip" to archive
        }
        for ((name, fixture) in fixtures) {
            val file = File.createTempFile("unsupported-", ".fixture", context.cacheDir).apply { writeBytes(fixture.second) }
            try {
                val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                val handle = descriptor.fileDescriptor
                assertThrows("$name must not open as a document", IOException::class.java) {
                    DocumentEngine.open(context, descriptor, name, fixture.first, CancellationSignal()).use { }
                }
                assertFalse("Failed admission consumes its descriptor", handle.valid())
                assertArrayEquals("Failure must not modify the source", fixture.second, file.readBytes())
            } finally { file.delete() }
        }
    }
}
