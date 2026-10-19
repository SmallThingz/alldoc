package app.smallthingz.alldoc.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import app.smallthingz.alldoc.engine.PageRegion
import app.smallthingz.alldoc.state.ViewportState
import org.junit.Assert.assertNull
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

    @Test fun visibleDetailUsesUncroppedSourceCoordinatesAtEveryRotation() {
        val source = Rect(100f, 200f, 700f, 1800f)
        val visible = Rect(0f, 0f, 800f, 600f)
        for (rotation in 0..3) {
            val transform = PageImageTransform(source, Offset(400f, 300f), 4f, rotation)
            val region = requireNotNull(pageVisibleRegion(1000, 2000, transform, visible))
            assertEquals(.4f, (region.left + region.right) / 2f, .00001f)
            assertEquals(.5f, (region.top + region.bottom) / 2f, .00001f)
            assertEquals(if (rotation % 2 == 0) 800 else 600, region.width)
            assertEquals(if (rotation % 2 == 0) 600 else 800, region.height)
            val topLeft = transform.itemAt(Offset(region.left * 1000f, region.top * 2000f))
            val bottomRight = transform.itemAt(Offset(region.right * 1000f, region.bottom * 2000f))
            assertEquals(0f, minOf(topLeft.x, bottomRight.x), .001f)
            assertEquals(800f, maxOf(topLeft.x, bottomRight.x), .001f)
            assertEquals(0f, minOf(topLeft.y, bottomRight.y), .001f)
            assertEquals(600f, maxOf(topLeft.y, bottomRight.y), .001f)
        }
    }

    @Test fun deeperZoomShrinksTheSourceRegionWithoutGrowingItsBitmap() {
        val source = Rect(0f, 0f, 1000f, 2000f)
        for (zoom in listOf(2f, 8f, 16f, 32f)) {
            val size = Size(1000f, 2000f * zoom)
            val transform = pageImageTransform(source, size, ViewportState(zoom = zoom), false, 0f)
            val visible = Rect(0f, size.height / 2f - 1000f, 1000f, size.height / 2f + 1000f)
            val region = requireNotNull(pageVisibleRegion(1000, 2000, transform, visible))
            assertEquals(1000, region.width)
            assertEquals(2000, region.height)
            assertEquals(1f / zoom, region.widthFraction, .00001f)
            assertEquals(1f / zoom, region.heightFraction, .00001f)
        }
    }

    @Test fun presentationLetterboxesAndAlreadySharpPixelsRequestNoExtraRaster() {
        val source = Rect(0f, 0f, 500f, 250f)
        val transform = pageImageTransform(source, Size(1000f, 600f), ViewportState(), true, 16f)
        assertEquals(2f, transform.scale, 0f)
        assertNull(pageVisibleRegion(500, 250, transform, Rect(0f, 0f, 1000f, 40f)))
        assertNull(pageVisibleRegion(500, 250, transform, Rect(0f, 560f, 1000f, 600f)))
        val region = requireNotNull(pageVisibleRegion(500, 250, transform, Rect(0f, 0f, 1000f, 600f)))
        assertEquals(PageRegion(0f, 0f, 1f, 1f, 1000, 500), region)
        assertNull(pageVisibleRegion(500, 250, transform.copy(scale = 1f), Rect(0f, 0f, 1000f, 600f)))
    }

    @Test fun regionDimensionsRespectBothEdgeAndAreaBounds() {
        val source = Rect(0f, 0f, 1000f, 2000f)
        val transform = PageImageTransform(source, Offset.Zero, 32f, 0)
        for (visible in listOf(Rect(-20000f, -20000f, 20000f, 20000f),
            Rect(-20000f, -50f, 20000f, 50f), Rect(-50f, -20000f, 50f, 20000f))) {
            val region = requireNotNull(pageVisibleRegion(1000, 2000, transform, visible))
            assertTrue(region.width in 1..PageRegion.MAX_EDGE)
            assertTrue(region.height in 1..PageRegion.MAX_EDGE)
            assertTrue(region.width.toLong() * region.height <= PageRegion.MAX_PIXELS)
            assertTrue(region.left >= 0f && region.top >= 0f && region.right <= 1f && region.bottom <= 1f)
        }
    }

    @Test fun sharedTransformRetainsCropAndClampedCrossAxisCenter() {
        val source = Rect(100f, 200f, 700f, 1400f)
        for (horizontal in listOf(false, true)) for (rotation in 0..3) {
            val transform = pageImageTransform(source, Size(1200f, 2400f),
                ViewportState(zoom = 8f, centerX = .8f, centerY = .2f, rotation = rotation), horizontal, 16f)
            for (point in listOf(source.topLeft, source.center, source.bottomRight)) {
                val restored = transform.sourceAt(transform.itemAt(point))
                assertEquals(point.x, restored.x, .001f)
                assertEquals(point.y, restored.y, .001f)
            }
        }
        val panned = pageImageTransform(Rect(0f, 0f, 100f, 200f), Size(600f, 2400f),
            ViewportState(zoom = 2f, centerX = .75f), false, 0f)
        assertEquals(12f, panned.scale, 0f)
        assertEquals(75f, panned.sourceAt(Offset(300f, 1200f)).x, 0f)
    }

}
