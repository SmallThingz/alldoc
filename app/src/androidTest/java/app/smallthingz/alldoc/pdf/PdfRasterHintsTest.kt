package app.smallthingz.alldoc.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import androidx.test.platform.app.InstrumentationRegistry
import app.smallthingz.alldoc.engine.DocumentEngine
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class PdfRasterHintsTest {
    @Test fun vectorAndPhotographicPagesShareBoundedMetadataWithoutTakingSnapshotOwnership() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File.createTempFile("raster-hints-", ".pdf", context.cacheDir)
        val pixels = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        try {
            pixels.eraseColor(Color.BLUE)
            pixels.setPixel(0, 0, Color.RED)
            source.writeBytes(jpegDocument(ByteArrayOutputStream().use { bytes ->
                assertTrue(pixels.compress(Bitmap.CompressFormat.JPEG, 95, bytes))
                bytes.toByteArray()
            }, vectorFirst = true))
        } finally { pixels.recycle() }
        try {
            DocumentEngine.openSnapshot(context, source, "raster-hints.pdf", "application/pdf", CancellationSignal()).use { session ->
                assertEquals(2, session.info.pageCount)
                assertEquals(emptyList<Any>(), session.rasterRegions(0, CancellationSignal()))
                assertTrue(session.rasterRegions(1, CancellationSignal()).let { it == null || it.isNotEmpty() })
                val outline = session.outline(CancellationSignal())
                assertSame(outline, session.outline(CancellationSignal()))
                assertEquals(emptyList<Any>(), session.rasterRegions(0, CancellationSignal()))
                assertTrue(source.exists())
                assertThrows(OperationCanceledException::class.java) {
                    session.rasterRegions(0, CancellationSignal().apply { cancel() })
                }
                session.close()
                assertThrows(IllegalStateException::class.java) { session.rasterRegions(0, CancellationSignal()) }
            }
            assertFalse(source.exists())
        } finally { source.delete() }
    }
    @Test fun opaqueJpegFootprintsMatchFullPageAndInsetDraws() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PdfDocument.configure(context)
        val source = File.createTempFile("jpeg-footprints-", ".pdf", context.cacheDir)
        val pixels = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        try {
            pixels.eraseColor(Color.BLUE)
            source.writeBytes(jpegDocument(ByteArrayOutputStream().use { bytes ->
                assertTrue(pixels.compress(Bitmap.CompressFormat.JPEG, 95, bytes))
                bytes.toByteArray()
            }))
        } finally { pixels.recycle() }
        try {
            PdfDocument.openSnapshot(ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY),
                "jpeg-footprints.pdf", null, CancellationSignal(), source).use { document ->
                assertEquals(2, document.pageCount)
                for ((page, expected) in listOf(
                    0 to floatArrayOf(0f, 0f, 1f, 1f),
                    1 to floatArrayOf(.1f, .1f, .9f, .9f),
                )) {
                    val regions = checkNotNull(document.rasterRegions(page, CancellationSignal()))
                    assertEquals(1, regions.size)
                    val region = regions.single()
                    assertArrayEquals(expected, floatArrayOf(region.left, region.top, region.right, region.bottom), .00001f)
                    assertSame(regions, document.rasterRegions(page, CancellationSignal()))
                    val rendered = document.renderPage(page, 150, CancellationSignal())
                    try {
                        val center = rendered.getPixel(rendered.width / 2, rendered.height / 2)
                        assertTrue("The fixture must contain a decoded blue JPEG", Color.blue(center) > 200 && Color.red(center) < 30)
                    } finally { rendered.recycle() }
                }
                assertTrue(source.exists())
            }
            assertTrue("The borrowed snapshot survives the direct PDF session", source.exists())
        } finally { source.delete() }
    }

    private fun jpegDocument(jpeg: ByteArray, vectorFirst: Boolean = false): ByteArray {
        fun bytes(value: String) = value.toByteArray(Charsets.ISO_8859_1)
        fun stream(value: ByteArray, dictionary: String = ""): ByteArray =
            bytes("<< /Length ${value.size} $dictionary >>\nstream\n") + value + bytes("\nendstream")
        val page = "/Type /Page /Parent 2 0 R /MediaBox [0 0 300 400] /Resources << /XObject << /Photo 7 0 R >> /Font << /F 8 0 R >> >>"
        // Canvas font/resource encoding varies by Android; the positive proof needs an explicit supported vector page.
        val firstPage = if (vectorFirst)
            "/Type /Page /Parent 2 0 R /MediaBox [0 0 300 400] /Resources << /Font << /F 8 0 R >> >>" else page
        val firstContent = if (vectorFirst)
            "1 1 1 rg 0 0 300 400 re f 0 0 0 rg BT /F 24 Tf 20 340 Td (Vector page) Tj ET" else
            "q 300 0 0 400 0 0 cm /Photo Do Q 1 1 1 rg 10 10 100 20 re f BT /F 9 Tf 10 20 Td (Footer) Tj ET"
        val objects = listOf(
            bytes("<< /Type /Catalog /Pages 2 0 R >>"),
            bytes("<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 >>"),
            bytes("<< $firstPage /Contents 5 0 R >>"),
            bytes("<< $page /Contents 6 0 R >>"),
            stream(bytes(firstContent)),
            stream(bytes("q 1 0 0 1 30 40 cm q 240 0 0 320 0 0 cm /Photo Do Q Q")),
            stream(jpeg, "/Type /XObject /Subtype /Image /Width 16 /Height 16 /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode"),
            bytes("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"),
        )
        return ByteArrayOutputStream().use { out ->
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
    }

}
