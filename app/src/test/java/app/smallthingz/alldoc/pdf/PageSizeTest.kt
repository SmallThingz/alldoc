package app.smallthingz.alldoc.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageSizeTest {
    @Test fun fitsNormalAndExtremeGeometry() {
        assertEquals(PageSize(1200, 1800), PageSize.fit(400, 600, 1200))
        for (width in listOf(1, 595, Int.MAX_VALUE)) {
            for (height in listOf(1, 842, Int.MAX_VALUE)) {
                val size = PageSize.fit(width, height, PageSize.MAX_WIDTH)
                assertTrue(size.width in 1..PageSize.MAX_WIDTH)
                assertTrue(size.height in 1..PageSize.MAX_EDGE)
                assertTrue(size.width.toLong() * size.height <= PageSize.MAX_PIXELS)
            }
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnboundedPreviewRequests() { PageSize.fit(595, 842, Int.MAX_VALUE) }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidDocumentGeometry() { PageSize.fit(0, 842, 1000) }
}
