package app.smallthingz.alldoc.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import app.smallthingz.alldoc.engine.PageRegion
import app.smallthingz.alldoc.engine.PdfSession
import app.smallthingz.alldoc.theme.ReadingMode
import app.smallthingz.alldoc.theme.ThemeSettings
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class PdfRegionTest {
    @Test fun deepRegionResolvesVectorStripesBeyondTheWholePagePixelBudget() {
        val content = buildString {
            append("1 1 1 rg 0 0 400 400 re f 0 0 0 rg\n")
            // At the whole-page pixel cap each stripe is less than a quarter pixel wide.
            // Native region sampling makes each stripe four pixels wide without a huge page bitmap.
            for (stripe in 0 until 100 step 2)
                append("${80.0 + stripe / 32.0} 316.875 0.03125 3.125 re f\n")
        }
        val source = fixture(content)
        try {
            open(source).use { pdf ->
                val region = PageRegion(.2f, .2f, .2078125f, .2078125f, 400, 400)
                val image = pdf.renderRegion(0, region, CancellationSignal())
                try {
                    assertEquals(400, image.width)
                    assertEquals(400, image.height)
                    assertEquals(640_000, image.allocationByteCount)
                    for (stripe in 0 until 100) {
                        val value = Color.red(image.getPixel(stripe * 4 + 2, 200))
                        if (stripe % 2 == 0) assertTrue("Resolved black stripe $stripe: $value", value < 20)
                        else assertTrue("Resolved white stripe $stripe: $value", value > 235)
                    }
                } finally { image.recycle() }
            }
        } finally { source.delete() }
    }

    @Test fun translatedRegionsKeepTopLeftCoordinatesAndCancellationOwnership() {
        val source = fixture("0 0 1 rg 0 200 200 200 re f 1 0 0 rg 200 0 200 200 re f")
        try {
            open(source).use { pdf ->
                for ((region, color) in listOf(
                    PageRegion(0f, 0f, .5f, .5f, 160, 160) to Color.BLUE,
                    PageRegion(.5f, .5f, 1f, 1f, 240, 240) to Color.RED,
                )) {
                    val image = pdf.renderRegion(0, region, CancellationSignal())
                    try {
                        assertEquals(region.width, image.width)
                        assertEquals(region.height, image.height)
                        assertEquals(color, image.getPixel(image.width / 2, image.height / 2))
                    } finally { image.recycle() }
                }
                val region = PageRegion(.25f, .25f, .75f, .75f, 100, 100)
                assertThrows(OperationCanceledException::class.java) {
                    pdf.renderRegion(0, region, CancellationSignal().apply { cancel() })
                }
                assertThrows(IllegalArgumentException::class.java) {
                    pdf.renderRegion(1, region, CancellationSignal())
                }
                assertThrows(IllegalArgumentException::class.java) {
                    pdf.renderRegion(0, PageRegion(0f, 0f, Float.MIN_VALUE, .5f, 16, 16), CancellationSignal())
                }
                pdf.renderRegion(0, region, CancellationSignal()).recycle()
                pdf.close()
                assertThrows(IllegalStateException::class.java) {
                    pdf.renderRegion(0, region, CancellationSignal())
                }
            }
        } finally { source.delete() }
    }

    @Test fun themedRegionsMatchPagePixelsAcrossPaperAndPartialImageEdges() {
        val photo = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val jpeg = try {
            for (y in 0 until 32) for (x in 0 until 32)
                photo.setPixel(x, y, if (x < 8) Color.WHITE else Color.rgb(40 + x * 3, 80 + y * 2, 180))
            ByteArrayOutputStream().use { bytes ->
                assertTrue(photo.compress(Bitmap.CompressFormat.JPEG, 100, bytes))
                bytes.toByteArray()
            }
        } finally { photo.recycle() }
        val source = fixture("1 1 1 rg 0 0 400 400 re f " +
            "0 0 0 rg 20 340 200 6 re f q 200 0 0 200 100 100 cm /Photo Do Q", jpeg)
        try {
            PdfSession(open(source), source.name, emptyList()).use { session ->
                val hints = checkNotNull(session.rasterRegions(0, CancellationSignal()))
                assertEquals("The JPEG footprint must be proven, not guessed from cropped pixels", 1, hints.size)
                val regions = listOf(
                    PageRegion(.125f, .125f, .625f, .625f, 200, 200),
                    PageRegion(.375f, .375f, .875f, .875f, 200, 200),
                    PageRegion(.375f, .375f, .625f, .625f, 100, 100),
                    // This is an authored white photo edge, not newly discovered document paper.
                    // A cropped-image scan classifier must retain the whole-image decision.
                    PageRegion(.3f, .375f, .35f, .625f, 20, 100),
                )
                for (mode in listOf(ReadingMode.ORIGINAL, ReadingMode.ADAPTIVE, ReadingMode.INVERT, ReadingMode.SEPIA)) {
                    val settings = ThemeSettings(mode, paperRoughness = 80)
                    val full = session.renderPage(0, 400, settings, CancellationSignal())
                    try {
                        for (region in regions) {
                            val image = session.renderRegion(0, region, settings, CancellationSignal())
                            try {
                                val expected = IntArray(region.width * region.height)
                                val actual = IntArray(expected.size)
                                full.getPixels(expected, 0, region.width, (region.left * 400).toInt(),
                                    (region.top * 400).toInt(), region.width, region.height)
                                image.getPixels(actual, 0, region.width, 0, 0, region.width, region.height)
                                assertArrayEquals("$mode paper/image crop $region", expected, actual)
                            } finally { image.recycle() }
                        }
                    } finally { full.recycle() }
                }
            }
        } finally { source.delete() }
    }

    @Test fun retainedBaseContextSurvivesReplacementOfTheEngineCache() {
        val source = fixture("1 1 1 rg 0 0 400 400 re f 0 0 0 rg 100 100 100 100 re f")
        try {
            PdfSession(open(source), source.name, emptyList()).use { session ->
                val signal = CancellationSignal()
                val dark = ThemeSettings(ReadingMode.ADAPTIVE)
                val sepia = ThemeSettings(ReadingMode.SEPIA, paperRoughness = 100)
                val base = session.renderPage(0, 400, dark, signal)
                try {
                    val retained = checkNotNull(session.pageThemeContext(0, dark, signal))
                    session.renderPage(0, 400, sepia, signal).recycle()
                    val replacement = checkNotNull(session.pageThemeContext(0, sepia, signal))
                    org.junit.Assert.assertNull(session.pageThemeContext(0, dark, signal))
                    val region = PageRegion(.25f, .25f, .75f, .75f, 200, 200)
                    val detail = session.renderRegionWithContext(0, region, dark, signal, retained)
                    try {
                        val expected = IntArray(200 * 200)
                        val actual = IntArray(expected.size)
                        base.getPixels(expected, 0, 200, 100, 100, 200, 200)
                        detail.getPixels(actual, 0, 200, 0, 0, 200, 200)
                        assertArrayEquals(expected, actual)
                        org.junit.Assert.assertSame("Using retained policy must not rebuild/replace the engine cache", replacement,
                            session.pageThemeContext(0, sepia, signal))
                    } finally { detail.recycle() }
                } finally { base.recycle() }
            }
        } finally { source.delete() }
    }

    private fun open(source: File): PdfDocument {
        PdfDocument.configure(InstrumentationRegistry.getInstrumentation().targetContext)
        return PdfDocument.open(ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY), source.name)
    }

    /** Explicit vector/image operators keep both the pixel oracle and source-footprint proof deterministic. */
    private fun fixture(content: String, jpeg: ByteArray? = null): File {
        fun bytes(value: String) = value.toByteArray(Charsets.ISO_8859_1)
        fun stream(value: ByteArray, dictionary: String = "") =
            bytes("<< /Length ${value.size} $dictionary >>\nstream\n") + value + bytes("\nendstream")
        val resources = if (jpeg == null) "" else "/XObject << /Photo 5 0 R >>"
        val objects = mutableListOf(
            bytes("<< /Type /Catalog /Pages 2 0 R >>"),
            bytes("<< /Type /Pages /Kids [3 0 R] /Count 1 >>"),
            bytes("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 400 400] /Resources << $resources >> /Contents 4 0 R >>"),
            stream(bytes(content)),
        )
        if (jpeg != null) objects += stream(jpeg, "/Type /XObject /Subtype /Image /Width 32 /Height 32 " +
            "/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode")
        val data = ByteArrayOutputStream().use { out ->
            out.write(bytes("%PDF-1.4\n"))
            val offsets = objects.mapIndexed { index, value ->
                out.size().also { out.write(bytes("${index + 1} 0 obj\n")); out.write(value); out.write(bytes("\nendobj\n")) }
            }
            val xref = out.size()
            out.write(bytes("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n"))
            offsets.forEach { out.write(bytes(it.toString().padStart(10, '0') + " 00000 n \n")) }
            out.write(bytes("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n"))
            out.toByteArray()
        }
        return File.createTempFile("pdf-region-", ".pdf", InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
            .also { it.writeBytes(data) }
    }
}
