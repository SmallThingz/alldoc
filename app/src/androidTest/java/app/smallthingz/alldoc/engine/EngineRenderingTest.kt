package app.smallthingz.alldoc.engine

import android.graphics.Bitmap
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.smallthingz.alldoc.core.Block
import app.smallthingz.alldoc.core.Document
import app.smallthingz.alldoc.core.Section
import app.smallthingz.alldoc.theme.DocumentColors
import app.smallthingz.alldoc.theme.ReadingMode
import app.smallthingz.alldoc.theme.ThemeSettings
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EngineRenderingTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun descriptorSnapshotsStartAtByteZeroAndAdoptTheCallerHandle() {
        val file = File.createTempFile("alldoc-text-", ".md", context.cacheDir)
        val content = "Start of document\nEnglish, हिन्दी and 日本語.\nLast line."
        file.writeText(content)
        try {
            val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val descriptor = fd.fileDescriptor
            Os.lseek(descriptor, 10, OsConstants.SEEK_SET)
            DocumentEngine.open(context, fd, "example.md", "text/markdown", CancellationSignal()).use { session ->
                assertFalse(descriptor.valid())
                content.lineSequence().forEach { assertTrue(session.text(CancellationSignal()).contains(it)) }
                val image = session.renderPage(0, 320, ThemeSettings(), CancellationSignal())
                try {
                    assertEquals(320, image.width)
                    assertEquals(DocumentColors.palette(ThemeSettings()).background, image.getPixel(0, 0))
                } finally { image.recycle() }
            }
            assertEquals(content, file.readText())
        } finally { file.delete() }
    }

    @Test fun textPageCountAndGeometryAreStableAcrossRendering() {
        val content = (0 until 500).joinToString("\n") { "Line $it: English, हिन्दी and 日本語." }
        val document = Document("text", sections = listOf(Section("", blocks = listOf(Block("paragraph", content)))))
        TextSession("Long.html", "text/html", document, CancellationSignal()).use { session ->
            assertTrue(session.info.reflowable)
            assertEquals(0, session.info.pageCount)
            assertTrue(session.text(CancellationSignal()).contains(content))
            val pages = session.pageCount(CancellationSignal())
            assertTrue("Pages must include the entire document", pages > 1)
            assertEquals(pages, session.pageCount(CancellationSignal()))
            assertEquals("Intrinsic count stays lazy; callers use pageCount(signal)", 0, session.info.pageCount)
            assertEquals(900f / 640f, session.pageAspect(pages - 1, CancellationSignal()), .0001f)
            assertThrows(IllegalArgumentException::class.java) {
                session.pageAspect(pages, CancellationSignal())
            }
            assertThrows(OperationCanceledException::class.java) {
                session.pageAspect(0, CancellationSignal().apply { cancel() })
            }
            val image = session.renderPage(pages - 1, 320, ThemeSettings(ReadingMode.ORIGINAL), CancellationSignal())
            try {
                assertEquals(450, image.height)
                val pixels = IntArray(image.width * image.height)
                image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
                assertTrue("Last page must contain text", pixels.any { it != pixels[0] })
            } finally { image.recycle() }
            assertThrows(IllegalArgumentException::class.java) {
                session.renderPage(pages, 320, ThemeSettings(), CancellationSignal())
            }
        }
    }

    @Test fun textZoomRendersAdditionalPixelsWithoutRepaginatingOrExceedingBounds() {
        val content = (0 until 90).joinToString("\n") { "Fine detail $it: English, हिन्दी and 日本語." }
        val document = Document("html", sections = listOf(Section("", blocks = listOf(Block("paragraph", content)))))
        TextSession("detail.html", "text/html", document, CancellationSignal()).use { session ->
            val count = session.pageCount(CancellationSignal())
            for (requested in listOf(400, 2000, 4096)) {
                val image = session.renderPage(0, requested, ThemeSettings(ReadingMode.ORIGINAL), CancellationSignal())
                try {
                    if (requested <= 2000) assertEquals("Requested detail must not be capped at1600", requested, image.width)
                    else assertTrue(image.width > 2000)
                    assertTrue(image.width.toLong() * image.height <= 8_000_000L)
                    assertTrue(image.height <= 8192)
                    assertEquals(900f / 640f, image.height.toFloat() / image.width, .002f)
                    val left = image.width / 20
                    val top = image.width / 20
                    val sampleWidth = minOf(image.width - left, image.width / 2)
                    val sampleHeight = minOf(image.height - top, image.width / 10)
                    val pixels = IntArray(sampleWidth * sampleHeight)
                    image.getPixels(pixels, 0, sampleWidth, left, top, sampleWidth, sampleHeight)
                    assertTrue("Native text must still be drawn at each requested resolution",
                        pixels.any { it != android.graphics.Color.WHITE })
                    assertEquals("Zoom does not change semantic page boundaries", count, session.pageCount(CancellationSignal()))
                } finally { image.recycle() }
            }
        }
    }

    @Test fun cancelledOpenClosesItsDescriptorWithoutChangingTheSource() {
        val file = File.createTempFile("alldoc-cancel-", ".md", context.cacheDir)
        file.writeText("Keep this file.")
        try {
            val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val descriptor = fd.fileDescriptor
            try {
                DocumentEngine.open(context, fd, "example.md", "text/markdown", CancellationSignal().also { it.cancel() })
                fail("Canceled open returned a session")
            } catch (_: OperationCanceledException) {
                assertFalse(descriptor.valid())
                assertEquals("Keep this file.", file.readText())
            }
        } finally { file.delete() }
    }

    @Test fun invertTreatsEveryRenderedTextPixelIncludingColorEmoji() {
        val document = Document("text", sections = listOf(Section("", blocks = listOf(Block("paragraph", "AllDoc 🌈")))))
        TextSession("Emoji.html", "text/html", document, CancellationSignal()).use { session ->
            val original = session.renderPage(0, 640, ThemeSettings(ReadingMode.ORIGINAL), CancellationSignal())
            try {
                val inverted = session.renderPage(0, 640, ThemeSettings(ReadingMode.INVERT), CancellationSignal())
                try {
                    assertEquals(original.width, inverted.width)
                    assertEquals(original.height, inverted.height)
                    val before = IntArray(original.width * original.height)
                    val after = IntArray(before.size)
                    original.getPixels(before, 0, original.width, 0, 0, original.width, original.height)
                    inverted.getPixels(after, 0, inverted.width, 0, 0, inverted.width, inverted.height)
                    var colorful = false
                    for (index in before.indices) {
                        val pixel = before[index]
                        if ((pixel ushr 24) != (after[index] ushr 24)) fail("Alpha changed at pixel $index")
                        if ((pixel xor 0x00ffffff) != after[index]) fail("RGB was not inverted at pixel $index")
                        val r = pixel ushr 16 and 255
                        val g = pixel ushr 8 and 255
                        val b = pixel and 255
                        colorful = colorful || maxOf(r, g, b) - minOf(r, g, b) > 40
                    }
                    assertTrue("The emoji fixture must exercise colored glyph pixels", colorful)
                } finally { inverted.recycle() }
            } finally { original.recycle() }
        }
    }

    @Test fun largeWorksheetOpensWithExplicitBoundedTextExcerpt() {
        val file = File.createTempFile("alldoc-large-sheet-", ".xlsx", context.cacheDir)
        try {
            ZipOutputStream(file.outputStream().buffered()).use { zip ->
                fun part(name: String, text: String) {
                    zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry()
                }
                part("[Content_Types].xml", """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>""")
                part("_rels/.rels", """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
                part("xl/workbook.xml", """<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Data" sheetId="1" r:id="rId1"/></sheets></workbook>""")
                part("xl/_rels/workbook.xml.rels", """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>""")
                zip.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
                val writer = zip.writer()
                writer.write("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")
                repeat(10001) { row ->
                    writer.write("<row r=\"${row + 1}\">")
                    repeat(12) { column ->
                        writer.write("<c r=\"${'A' + column}${row + 1}\"><v>${row * 12 + column}</v></c>")
                    }
                    writer.write("</row>")
                }
                writer.write("</sheetData></worksheet>"); writer.flush(); zip.closeEntry()
            }
            val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val descriptor = fd.fileDescriptor
            DocumentEngine.open(context, fd, "large.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", CancellationSignal()).use { session ->
                assertFalse(descriptor.valid())
                assertTrue(session.info.warnings.any { it.contains("remaining worksheet data was not parsed") })
                val text = session.text(CancellationSignal())
                assertTrue(text.startsWith("[Document excerpt:"))
                val document = JSONObject(session.json(CancellationSignal()))
                assertTrue(document.getBoolean("truncated"))
                val blocks = document.getJSONArray("sections").getJSONObject(0).getJSONArray("blocks")
                assertEquals(4096, blocks.length())
                assertEquals("0", blocks.getJSONObject(0).getString("text"))
                assertEquals("4095", blocks.getJSONObject(4095).getString("text"))
                assertTrue(session.pageCount(CancellationSignal()) > 0)
                val bitmap = session.renderPage(0, 320, ThemeSettings(ReadingMode.ORIGINAL), CancellationSignal())
                try {
                    val pixels = IntArray(bitmap.width * bitmap.height)
                    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    assertTrue("The opened excerpt must contain visible content", pixels.any { it != pixels[0] })
                } finally { bitmap.recycle() }
            }
        } finally { file.delete() }
    }

    @Test fun engineThemingReleasesTemporarySourcesOnReplacementAndCancellation() {
        val original = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        original.eraseColor(-1)
        val kept = themed(original, ThemeSettings(ReadingMode.ORIGINAL), CancellationSignal())
        assertSame(original, kept)
        assertFalse(original.isRecycled)
        kept.recycle()

        val replaced = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        replaced.eraseColor(-1)
        val result = themed(replaced, ThemeSettings(), CancellationSignal())
        try { assertTrue(replaced.isRecycled); assertFalse(result.isRecycled) }
        finally { result.recycle() }

        val canceled = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        try {
            themed(canceled, ThemeSettings(), CancellationSignal().also { it.cancel() })
            fail("Canceled theming returned")
        } catch (_: OperationCanceledException) { assertTrue(canceled.isRecycled) }
    }
}
