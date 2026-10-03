package org.smallthingz.alldoc

import org.junit.Assert.*
import org.junit.Test

class SlideGeometryTest {
    @Test
    fun ordinaryLandscapeAndPortraitCanvasesAreSafe() {
        assertTrue(safeSlideCanvas(720.0, 405.0))
        assertTrue(safeSlideCanvas(540.0, 720.0))
        assertTrue(safeSlideFrame(10.0, 10.0, 400.0, 200.0, 720.0, 405.0))
    }

    @Test
    fun pathologicalCanvasRatiosAndValuesFallBack() {
        assertFalse(safeSlideCanvas(1.0, 1e8))
        assertFalse(safeSlideCanvas(1e8, 1e8))
        assertFalse(safeSlideCanvas(Double.NaN, 540.0))
        assertFalse(safeSlideCanvas(720.0, Double.POSITIVE_INFINITY))
    }

    @Test
    fun offCanvasFramesCannotReachComposeConstraints() {
        assertFalse(safeSlideFrame(0.0, 0.0, 1e8, 50.0, 720.0, 405.0))
        assertFalse(safeSlideFrame(-100.0, 0.0, 20.0, 20.0, 720.0, 405.0))
        assertFalse(safeSlideFrame(700.0, 0.0, 50.0, 20.0, 720.0, 405.0))
        assertFalse(safeSlideFrame(0.0, 0.0, Double.NaN, 20.0, 720.0, 405.0))
    }
}
