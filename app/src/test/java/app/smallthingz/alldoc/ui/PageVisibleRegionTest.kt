package app.smallthingz.alldoc.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import app.smallthingz.alldoc.engine.PageRegion
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class PageVisibleRegionTest {
    private fun visible(width: Float, height: Float, scale: Float): PageRegion? {
        val transform = PageImageTransform(Rect(0f, 0f, 1000f, 1000f), Offset.Zero, scale, 0)
        return pageVisibleRegion(1000, 1000, transform, Rect(0f, 0f, width, height))
    }

    private fun assertUniform(region: PageRegion) {
        val expectedHeight = region.width * region.heightFraction.toDouble() / region.widthFraction
        assertTrue("A detail surface must retain the source aspect: $region", abs(expectedHeight - region.height) <= .501)
        assertTrue(region.width in 1..PageRegion.MAX_EDGE && region.height in 1..PageRegion.MAX_EDGE)
        assertTrue(region.width.toLong() * region.height <= PageRegion.MAX_PIXELS)
    }

    @Test fun fractionalNarrowStripUsesOneScaleForBothAxes() {
        val region = requireNotNull(visible(1.2f, 2000.2f, 10f))
        assertEquals(2, region.width)
        assertUniform(region)
        assertTrue("The thin strip can be oversampled within the same bounded output", region.height > 3000)
    }

    @Test fun edgeAndPixelCapsKeepUniformNativeGeometry() {
        assertUniform(requireNotNull(visible(514f, 5000f, 20f)))
        assertUniform(requireNotNull(visible(5000f, 5000f, 20f)))
        assertNull("No one-pixel raster can retain an extreme strip's aspect within 4096", visible(.1f, 2000f, 10f))
        assertNull(visible(2000f, .1f, 10f))
    }

    @Test fun nonFiniteGeometryDoesNotEscapeTheLayoutCallback() {
        val source = Rect(0f, 0f, 1000f, 1000f)
        val transform = PageImageTransform(source, Offset(Float.NaN, 0f), 2f, 0)
        assertNull(pageVisibleRegion(1000, 1000, transform, source))
        assertNull(pageVisibleRegion(1000, 1000, transform.copy(center = Offset.Zero), Rect(0f, 0f, Float.NaN, 100f)))
    }
}
