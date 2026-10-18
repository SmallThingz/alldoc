package app.smallthingz.alldoc.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageRenderSizeTest {
    private val budget = 48L * 1024 * 1024

    @Test fun croppedContentGetsViewportPixelsOnTheDisplayedAxis() {
        assertEquals(1080, pageRenderWidth(1080, 1f, 2f, false, budget))
        assertEquals(1800, pageRenderWidth(1080, 1f, 2f, false, budget, .6f, .75f))
        assertEquals(720, pageRenderWidth(1080, 1f, 2f, true, budget, .6f, .75f))
        assertEquals(1440, pageRenderWidth(1080, 2f, 2f, true, budget, .6f, .75f))
        assertEquals("Full-page behavior is unchanged", 1620,
            pageRenderWidth(1080, 1.5f, 2f, false, budget, 1f, 1f))
    }

    @Test fun cropCannotExpandTheFullRasterBudgetOrNativeWidthLimit() {
        for (sideways in listOf(false, true)) for (aspect in listOf(.25f, 1f, 2f, 8f)) {
            val bytes = 2L * 1024 * 1024
            val width = pageRenderWidth(1600, 8f, aspect, sideways, bytes, .5f, .5f)
            assertTrue(width in 1..4096)
            assertTrue("Budget must use the uncropped aspect", width.toDouble() * width * aspect * 4 <= bytes)
        }
        assertEquals(4096, pageRenderWidth(1600, 8f, .25f, false, budget, .5f, .5f))
    }

    @Test fun firstMarginProbeFitsDetectionWithoutLimitingLaterDetail() {
        val detail = pageRenderWidth(720, 2f, 2f, false, budget, .6f, .6f)
        val probe = pageMarginProbeWidth(detail, 2f)
        assertEquals(2048, probe)
        assertTrue(probe * 2 <= 4096)
        assertEquals(2400, detail)
        assertEquals(720, pageMarginProbeWidth(720, 2f))
        assertEquals(4096, pageMarginProbeWidth(4096, .5f))
    }
}
