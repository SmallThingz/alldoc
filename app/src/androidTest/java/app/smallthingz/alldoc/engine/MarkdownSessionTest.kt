package app.smallthingz.alldoc.engine

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import android.os.CancellationSignal
import android.os.OperationCanceledException
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.smallthingz.alldoc.core.MarkdownDocument
import app.smallthingz.alldoc.core.MarkdownKind
import app.smallthingz.alldoc.theme.DocumentColors
import app.smallthingz.alldoc.theme.ReadingMode
import app.smallthingz.alldoc.theme.ThemeSettings
import java.io.IOException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MarkdownSessionTest {
    @Test fun oneContinuousDocumentExportsSemanticTextAndCompleteStyledImage() {
        val document = MarkdownDocument.parse("# AllDoc\n\nA **bold**, *readable* document.\n\n1. First\n2. Last\n\nEnd of document.")
        val signal = CancellationSignal()
        MarkdownSession("reader.md", "text/markdown", document.toDocument(), signal).use { session ->
            assertTrue(session.info.continuous)
            assertEquals(1, session.pageCount(signal))
            assertSame(document, session.markdown(signal))
            assertEquals(document.plainText, session.text(signal))
            assertFalse(session.text(signal).contains("**"))
            val json = JSONObject(session.json(signal))
            assertTrue(json.getBoolean("continuous")); assertEquals(1, json.getInt("pageCount"))
            for (mode in listOf(ReadingMode.ORIGINAL, ReadingMode.ADAPTIVE, ReadingMode.ADAPTIVE_LIGHT)) {
                val settings = ThemeSettings(mode)
                val bitmap = session.renderPage(0, 640, settings, signal)
                try {
                    assertEquals(640, bitmap.width)
                    assertEquals(DocumentColors.palette(settings).background, bitmap.getPixel(0, 0))
                    val pixels = IntArray(bitmap.width * bitmap.height)
                    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    assertTrue("actual styled ink $mode", pixels.count { it != pixels[0] } > 100)
                    val lastInk = pixels.indexOfLast { it != pixels[0] } / bitmap.width
                    assertTrue("last line remains inside complete image $mode", lastInk in bitmap.height - 100 until bitmap.height - 10)
                } finally { bitmap.recycle() }
            }
            try { session.renderPage(1, 640, ThemeSettings(), signal); fail("No artificial second Markdown page") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun longFlowRemainsReadableButImageAndPrintFailWithoutCropping() {
        val document = MarkdownDocument.parse((1..2000).joinToString("\n\n") { "Paragraph $it: continuous Markdown remains available without artificial page breaks." })
        val signal = CancellationSignal()
        MarkdownSession("long.md", "text/markdown", document.toDocument(), signal).use { session ->
            assertEquals(1, session.pageCount(signal))
            assertTrue(session.markdown(signal).plainText.contains("Paragraph 2000:"))
            assertTrue(session.text(signal).endsWith("without artificial page breaks."))
            for (print in listOf(false, true)) {
                try {
                    val unexpected = if (print) session.renderPrintPage(0, 1024, signal)
                        else session.renderPage(0, 1024, ThemeSettings(), signal)
                    unexpected.recycle(); fail("Expected explicit whole-document bitmap limit")
                } catch (expected: IOException) { assertTrue(expected.message.orEmpty().contains("too tall")) }
            }
            assertTrue(session.text(signal).contains("Paragraph 2000:"))
        }
    }

    @Test fun truncatedExportsDiscloseOmissionWithoutChangingReaderOffsets() {
        val document = MarkdownDocument.parse("# Start\n\n" + "x".repeat(512 * 1024) + "\n\nOMITTED_END")
        assertTrue(document.truncated)
        val signal = CancellationSignal()
        MarkdownSession("excerpt.md", "text/markdown", document.toDocument(), signal).use { session ->
            assertEquals(document.plainText, session.bodyText(signal))
            assertFalse(session.bodyText(signal).startsWith("[Document excerpt:"))
            assertFalse(session.bodyText(signal).contains("OMITTED_END"))
            assertEquals("[Document excerpt: remaining content omitted.]\n\n" + document.plainText, session.text(signal))
            assertTrue(JSONObject(session.json(signal)).getBoolean("truncated"))
            for (block in session.markdown(signal).blocks)
                assertEquals(block.content.text, session.bodyText(signal).substring(block.start, block.start + block.content.text.length))
        }
    }

    @Test fun wideOrderedMarkersLeaveSpaceAndAreNotRepeatedAtHardLineBreaks() {
        val document = MarkdownDocument.parse("100000000. Item  \n           Next line")
        val block = document.blocks.single()
        assertEquals("100000000.", block.marker)
        assertEquals("Item\nNext line", block.content.text)
        val signal = CancellationSignal()
        MarkdownSession("list.md", "text/markdown", document.toDocument(), signal).use { session ->
            val bitmap = session.renderPage(0, 640, ThemeSettings(ReadingMode.ORIGINAL), signal)
            try {
                val paper = bitmap.getPixel(0, 0)
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                val inkRows = (0 until bitmap.height).filter { y -> (0 until bitmap.width).any { x -> pixels[y * bitmap.width + x] != paper } }
                val groups = ArrayList<MutableList<Int>>()
                for (row in inkRows) {
                    if (groups.isEmpty() || row > groups.last().last() + 2) groups += arrayListOf(row)
                    else groups.last().add(row)
                }
                assertEquals("two authored text lines", 2, groups.size)
                val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 20f; typeface = Typeface.DEFAULT }
                val itemStart = 28 + kotlin.math.ceil(paint.measureText(block.marker)).toInt() + 10
                assertTrue("item starts after the full ordered number", groups.first().any { y ->
                    (itemStart until bitmap.width).any { x -> pixels[y * bitmap.width + x] != paper }
                })
                assertTrue("hard break has no repeated list number", groups.last().all { y ->
                    (0 until itemStart - 2).all { x -> pixels[y * bitmap.width + x] == paper }
                })
            } finally { bitmap.recycle() }
        }
    }

    @Test fun rtlOrderedMarkersStayInsideTheRightGutterAndDoNotRepeat() {
        val document = MarkdownDocument.parse("100000000. عنصر  \n           سطر")
        val block = document.blocks.single()
        assertEquals("100000000.", block.marker)
        assertEquals("عنصر\nسطر", block.content.text)
        val signal = CancellationSignal()
        MarkdownSession("rtl.md", "text/markdown", document.toDocument(), signal).use { session ->
            val bitmap = session.renderPage(0, 640, ThemeSettings(ReadingMode.ORIGINAL), signal)
            try {
                val paper = bitmap.getPixel(0, 0)
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 20f; typeface = Typeface.DEFAULT }
                val edge = bitmap.width - 28
                val gutterStart = edge - kotlin.math.ceil(paint.measureText(block.marker)).toInt() - 10
                assertTrue("RTL number must not extend into the page margin", (0 until bitmap.height).all { y ->
                    (edge until bitmap.width).all { x -> pixels[y * bitmap.width + x] == paper }
                })
                val inkRows = (0 until bitmap.height).filter { y -> (0 until edge).any { x -> pixels[y * bitmap.width + x] != paper } }
                val groups = ArrayList<MutableList<Int>>()
                for (row in inkRows) {
                    if (groups.isEmpty() || row > groups.last().last() + 2) groups.add(arrayListOf(row))
                    else groups.last().add(row)
                }
                assertEquals("two Arabic text lines", 2, groups.size)
                assertTrue("first RTL line has a visible number inside the gutter", groups.first().sumOf { y ->
                    (gutterStart until edge).count { x -> pixels[y * bitmap.width + x] != paper }
                } > 20)
                assertTrue("next RTL line does not repeat the number", groups.last().all { y ->
                    (gutterStart until edge).all { x -> pixels[y * bitmap.width + x] == paper }
                })
            } finally { bitmap.recycle() }
        }
    }

    @Test fun longListAndQuoteChunksKeepOneParagraphIndentInImageAndPrint() {
        for ((kind, prefix, continuation) in listOf(
            Triple(MarkdownKind.LIST_ITEM, "100000000. ", "           "),
            Triple(MarkdownKind.QUOTE, "> ", "> "),
        )) {
            val short = MarkdownDocument.parse(prefix + "Start  \n" + continuation + "WEND")
            val long = MarkdownDocument.parse(prefix + "i ".repeat(9000) + "  \n" + continuation + "WEND")
            val chunks = long.blocks.filter { it.kind == kind }
            assertTrue("the parser actually chunks the long $kind paragraph", chunks.size >= 3)
            assertTrue(chunks.zipWithNext().all { (a, b) -> a.start + a.content.text.length == b.start })
            assertTrue(long.plainText.endsWith("\nWEND"))
            val signal = CancellationSignal()
            MarkdownSession("short.md", "text/markdown", short.toDocument(), signal).use { reference ->
                MarkdownSession("long.md", "text/markdown", long.toDocument(), signal).use { session ->
                    val expected = reference.renderPage(0, 640, ThemeSettings(ReadingMode.ORIGINAL), signal)
                    try {
                        val expectedLine = lastLine(expected)
                        assertTrue("reference has a real leading margin", expectedLine.first >= 48)
                        for (print in listOf(false, true)) {
                            val actual = if (print) session.renderPrintPage(0, 640, signal)
                                else session.renderPage(0, 640, ThemeSettings(ReadingMode.ORIGINAL), signal)
                            try {
                                assertEquals("chunking must not multiply $kind indentation (print=$print)", expectedLine, lastLine(actual))
                            } finally { actual.recycle() }
                        }
                        assertEquals(long.plainText, session.bodyText(signal))
                    } finally { expected.recycle() }
                }
            }
        }
    }

    @Test fun wideLooseListContinuationsKeepTheirGutterAfterNestedItemsInImageAndPrint() {
        for (tail in listOf("WEND", "سطر")) {
            val prefix = "100000000. "
            val indent = " ".repeat(prefix.length)
            val reference = MarkdownDocument.parse("$prefix$tail  \n$indent$tail")
            val signal = CancellationSignal()
            MarkdownSession("reference.md", "text/markdown", reference.toDocument(), signal).use { expectedSession ->
                val expected = expectedSession.renderPage(0, 640, ThemeSettings(ReadingMode.ORIGINAL), signal)
                try {
                    val edge = lastLine(expected)
                    assertTrue("the authored number reserves a wide gutter", if (tail == "WEND") edge.first > 100 else edge.last < 540)
                    for (nested in listOf(false, true)) {
                        val source = "$prefix$tail\n\n" + (if (nested) "$indent- $tail\n\n" else "") + "$indent$tail"
                        val document = MarkdownDocument.parse(source)
                        assertEquals(if (nested) listOf(0, 1, 0) else listOf(0, 0), document.blocks.map { it.level })
                        assertTrue(document.blocks.last().marker.isEmpty())
                        MarkdownSession("loose.md", "text/markdown", document.toDocument(), signal).use { session ->
                            for (print in listOf(false, true)) {
                                val actual = if (print) session.renderPrintPage(0, 640, signal)
                                    else session.renderPage(0, 640, ThemeSettings(ReadingMode.ORIGINAL), signal)
                                try {
                                    assertEquals("same final glyphs align without a repeated number: rtl=${tail != "WEND"}, nested=$nested, print=$print",
                                        edge, lastLine(actual))
                                } finally { actual.recycle() }
                            }
                            assertEquals(document.plainText, session.bodyText(signal))
                        }
                    }
                } finally { expected.recycle() }
            }
        }
    }

    private fun lastLine(bitmap: Bitmap): IntRange {
        val paper = bitmap.getPixel(0, 0)
        val row = IntArray(bitmap.width)
        var left = bitmap.width
        var right = -1
        for (y in bitmap.height - 1 downTo 0) {
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            val first = row.indexOfFirst { it != paper }
            if (first < 0) { if (right >= 0) break } else {
                left = minOf(left, first)
                right = maxOf(right, row.indexOfLast { it != paper })
            }
        }
        assertTrue("the authored final line has visible ink", right > left)
        return left..right
    }

    @Test fun cancellationAndCloseDoNotPublishImagesOrInvalidateExportedFlow() {
        val document = MarkdownDocument.parse("# Stable\n\nBody")
        val session = MarkdownSession("reader.md", "text/markdown", document.toDocument(), CancellationSignal())
        val canceled = CancellationSignal().apply { cancel() }
        try { session.renderPage(0, 320, ThemeSettings(), canceled); fail("Expected cancellation") }
        catch (_: OperationCanceledException) { }
        session.close()
        assertEquals("Stable\n\nBody", document.plainText)
        try { session.text(CancellationSignal()); fail("Closed session") }
        catch (_: IllegalStateException) { }
    }
}
