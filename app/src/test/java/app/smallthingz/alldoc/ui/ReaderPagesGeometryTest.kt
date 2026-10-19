package app.smallthingz.alldoc.ui

import app.smallthingz.alldoc.state.ViewportState
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderPagesGeometryTest {
    @Test fun interruptedZoomSavesAnEquivalentAnchorBeforeNegativeRequestsAreMeasured() {
        val gap = 22f
        val focus = 654f
        val originalTop = 567f
        val originalHeight = 607.5f
        for (leading in listOf(44f, 220f)) {
            var top = originalTop
            var height = originalHeight
            for (zoom in listOf(2f, 1f)) {
                val nextHeight = originalHeight * zoom
                val nextTop = focus - (focus - top) * nextHeight / height
                val requested = (leading - nextTop).roundToInt()
                assertTrue(requested < 0)
                val anchor = pageOffsetAnchor(1, requested.toFloat(), gap, 2) { nextHeight }
                assertEquals("A negative focal-page offset belongs to the preceding row", 0, anchor.page)
                val restoredOffset = (anchor.fraction * (nextHeight + gap)).roundToInt()
                val restoredTop = leading - restoredOffset + nextHeight.roundToInt() + gap
                assertEquals("Saving before layout must retain the requested page position", nextTop, restoredTop, .001f)
                top = restoredTop
                height = nextHeight
            }
            assertEquals(originalTop, top, .001f)
        }
        val lengths = listOf(100f, 200f, 300f)
        assertEquals(PageAnchor(0, .4f), pageOffsetAnchor(2, -260f, 0f, lengths.size) { lengths[it] })
        assertEquals(PageAnchor(0, 0f), pageOffsetAnchor(2, -400f, 0f, lengths.size) { lengths[it] })
    }

    @Test fun interruptedZoomNormalizesMixedFractionalRowsUsingTheirMeasuredSpans() {
        val lengths = listOf(10.49f, 11.49f, 12.49f, 10.49f, 11.49f, 12.49f, 25f)
        val gap = 3f
        val anchor = pageOffsetAnchor(6, -80f, gap, lengths.size) { lengths[it] }
        assertEquals(0, anchor.page)
        // LazyList measures the preceding spans as 13+14+15+13+14+15 = 84px.
        // place() converts the saved fraction using the unrounded model extent.
        val restoredOffset = (anchor.fraction * (lengths[anchor.page] + gap)).roundToInt()
        assertEquals(4, restoredOffset)
        assertEquals("The focal row remains 80px below the padded origin", 80, 84 - restoredOffset)
    }

    @Test fun interruptedForwardPanSavesTheNextRowOffsetAndStopsAtTheLastRow() {
        val lengths = listOf(608f, 407.49f, 900f)
        val gap = 22f
        fun anchor(offset: Float) = pageOffsetAnchor(0, offset, gap, lengths.size) { lengths[it] }
        val next = anchor(700f)
        assertEquals(1, next.page)
        assertEquals(70, (next.fraction * (lengths[next.page] + gap)).roundToInt())
        val across = anchor(1600f)
        assertEquals(2, across.page)
        assertEquals(541, (across.fraction * (lengths[across.page] + gap)).roundToInt())
        assertEquals(PageAnchor(2, 1f), anchor(3000f))
        assertEquals(PageAnchor(0, 0f), anchor(-3000f))
    }

    @Test fun zoomOutPreservesPageGeometryAndCenteredPresentationSlots() {
        val pose = ViewportState(zoom = .75f).normalized()
        assertEquals(.75f, pose.zoom, 0f)
        assertEquals(.75f, ViewportState(zoom = .1f).normalized().zoom, 0f)
        assertEquals(1f, ViewportState(zoom = Float.NaN).normalized().zoom, 0f)
        assertEquals(32f, ViewportState(zoom = 40f).normalized().zoom, 0f)
        assertEquals(225f, pageRowLength(600f, 400f, .5f, pose, false), 0f)
        assertEquals(450f, pageImageLength(600f, 400f, .5f, pose, true), 0f)
        assertEquals(600f, pageRowLength(600f, 400f, .5f, pose, true), 0f)
        assertEquals(150f, pageImageLength(600f, 400f, 2f, pose, true), 0f)
        assertEquals(450f, pageImageLength(600f, 400f, 2f, pose.copy(rotation = 1), true), 0f)
    }

    @Test fun presentationPinchingInLetterboxesCannotEscapeTheCurrentPage() {
        val width = 1000f
        val height = 400f
        for (focus in listOf(50f, 950f)) for (zoom in listOf(2f, 4f, 8f)) {
            val oldImage = 200f
            val localPoint = (focus - (width - oldImage) / 2f) / oldImage
            val newImage = oldImage * zoom
            val slot = maxOf(width, newImage)
            val requested = ((slot - newImage) / 2f + localPoint * newImage - focus).toInt()
            val offset = pageScrollOffset(requested, slot, width, discrete = true)
            assertTrue("zoom=$zoom focus=$focus", offset in 0..(slot - width).toInt())
            assertEquals(if (focus < width / 2f) 0 else (slot - width).toInt(), offset)
        }
        assertEquals(0, pageScrollOffset(1350, 1000f, width, discrete = true))
        assertEquals(600, pageScrollOffset(3450, 1600f, width, discrete = true))
        assertEquals("Normal gestures can move through preceding pages", -400,
            pageScrollOffset(-400, height, width, discrete = false))
        assertEquals(0, pageScrollOffset(-400, height, width, discrete = false, first = true))
    }

    @Test fun currentPageUsesOneThirdOfTheActualReadingViewportWithoutChangingItsAnchor() {
        data class Row(val page: Int, val offset: Float, val extent: Float)
        // Content padding shifts the viewport start, while item offsets remain in list coordinates.
        val rows = listOf(Row(0, 0f, 120f), Row(1, 128f, 120f), Row(2, 256f, 120f))
        fun current(height: Float) = pageAtProbe(rows, -16f + height / 3f, { it.offset }, { it.extent })?.page
        assertEquals(1, current(600f))
        assertEquals("Notes reduce the reading viewport; the probe must reduce with it", 0, current(300f))
        assertEquals(0, pageAtProbe(rows, 121f, { it.offset }, { it.extent })?.page)
        assertEquals(1, pageAtProbe(rows, 127f, { it.offset }, { it.extent })?.page)
        assertEquals(0, pageAtProbe(rows, -50f, { it.offset }, { it.extent })?.page)
        assertEquals(2, pageAtProbe(rows, 500f, { it.offset }, { it.extent })?.page)
        assertEquals(null, pageAtProbe(emptyList<Row>(), 50f, { it.offset }, { it.extent }))
    }

    @Test fun switchingModesKeepsTheReadingProbePageWhenSeveralShortSlidesAreVisible() {
        val viewport = 600f
        val leading = 16f
        val probe = viewport / 3f - leading
        val focal = pageFocalAnchor(0, 0f, viewport - 2f * leading, 8, 8f, probeOffset = probe) { 120f }
        assertEquals(1, focal.page)
        assertEquals(56f / 120f, focal.fraction, .0001f)
        assertEquals("The geometric center is a different slide and must not choose the mode target", 2,
            pageFocalAnchor(0, 0f, viewport - 2f * leading, 8, 8f) { 120f }.page)
        val present = pageScrollAnchor(focal.page, .5f, 300f, 8f, discrete = true, probeOffset = 100f) { 300f }
        assertEquals(PageAnchor(1, 0f), present)
        val normal = pageScrollAnchor(focal.page, focal.fraction, viewport - 2f * leading, 8f, probeOffset = probe) { 120f }
        assertEquals(PageAnchor(0, 0f), normal)
    }

    @Test fun presentationReleasesSnapAcrossOneBoundaryWhileZoomedPanStaysInsideItsPage() {
        for (velocity in listOf(-100_000f, -600f, 0f, 600f, 100_000f)) {
            assertEquals(0f, presentationSnapOffset(0, 600, 600, 8, true, velocity, 400f), 0f)
            assertEquals(0f, presentationSnapOffset(200, 1200, 600, 8, true, velocity, 400f), 0f)
            assertEquals(0f, presentationSnapOffset(600, 1200, 600, 8, true, velocity, 400f), 0f)
        }
        assertEquals(208f, presentationSnapOffset(400, 600, 600, 8, true, 100_000f, 400f), 0f)
        assertEquals(-400f, presentationSnapOffset(400, 600, 600, 8, true, -100_000f, 400f), 0f)
        assertEquals(-100f, presentationSnapOffset(100, 600, 600, 8, true), 0f)
        assertEquals(108f, presentationSnapOffset(500, 600, 600, 8, true), 0f)
        // A zoomed page ends at offset 600. Crossing its edge settles to that end or the next start.
        assertEquals(-100f, presentationSnapOffset(700, 1200, 600, 8, true), 0f)
        assertEquals(108f, presentationSnapOffset(1100, 1200, 600, 8, true), 0f)
        assertEquals(508f, presentationSnapOffset(700, 1200, 600, 8, true, 100_000f, 400f), 0f)
        assertEquals(-500f, presentationSnapOffset(1100, 1200, 600, 8, true, -100_000f, 400f), 0f)
        assertEquals(-100f, presentationSnapOffset(700, 1200, 600, 8, false, 100_000f, 400f), 0f)
    }

    @Test fun verticalPagesFitWidthWhileHorizontalPagesFitTheAvailableScreen() {
        assertEquals(1800f, pageRowLength(300f, 600f, 2f, ViewportState(zoom = 3f), false), 0f)
        assertEquals(450f, pageRowLength(300f, 600f, 2f, ViewportState(zoom = 3f, rotation = 1), false), 0f)
        assertEquals(200f, pageImageLength(600f, 400f, 2f, ViewportState(), true), 0f)
        assertEquals("Portrait pages occupy a full presentation slot", 600f,
            pageRowLength(600f, 400f, 2f, ViewportState(), true), 0f)
        assertEquals(800f, pageRowLength(600f, 400f, 2f, ViewportState(zoom = 4f), true), 0f)
        assertEquals(600f, pageRowLength(600f, 400f, 2f, ViewportState(rotation = 1), true), 0f)
        assertTrue(pageRowLength(400f, 800f, Float.MAX_VALUE, ViewportState(zoom = 8f), false) <= 131000f)
    }

    @Test fun enteringPresentKeepsTheFocalPageWhenTheImageIsNarrowOrNearAnEdge() {
        // A zoomed portrait page is still narrower than a landscape viewport.
        val pose = ViewportState(zoom = 1.5f, centerX = .4f)
        val slot = pageRowLength(1000f, 400f, 2f, pose, true)
        val image = pageImageLength(1000f, 400f, 2f, pose, true)
        assertEquals(1000f, slot, 0f)
        assertEquals(300f, image, 0f)
        val center = pageSlotFraction(.4f, slot, image)
        assertEquals(.47f, center, .0001f)
        assertEquals(PageAnchor(2, 0f), pageScrollAnchor(2, center, 1000f, 8f, discrete = true) { slot })
        // Continuous mode intentionally spans the preceding row for the same negative offset.
        val continuous = pageScrollAnchor(2, center, 1000f, 8f) { slot }
        assertEquals(1, continuous.page)
        assertEquals(978f / 1008f, continuous.fraction, .0001f)
        for ((point, offset) in listOf(.1f to 0f, .5f to 500f, .9f to 1000f)) {
            var visited = 0
            val anchor = pageScrollAnchor(50_000, point, 1000f, 8f, discrete = true) { visited++; 2000f }
            assertEquals(50_000, anchor.page)
            assertEquals(offset / 2008f, anchor.fraction, .0001f)
            assertEquals("Discrete conversion only reads the focal page", 1, visited)
        }
    }

    @Test fun presentationLetterboxCoordinatesRetainTheCenteredImageAndInvertExactly() {
        assertEquals(.5f, pageAlongFraction(.5f, 600f, 200f), 0f)
        assertEquals(.5f, pageSlotFraction(.5f, 600f, 200f), 0f)
        assertEquals(-1f, pageAlongFraction(0f, 600f, 200f), 0f)
        assertEquals(2f, pageAlongFraction(1f, 600f, 200f), 0f)
        assertEquals(1f / 3f, pageSlotFraction(0f, 600f, 200f), .0001f)
        assertEquals(2f / 3f, pageSlotFraction(1f, 600f, 200f), .0001f)
        for (image in listOf(200f, 400f, 600f, 800f)) {
            val slot = maxOf(600f, image)
            for (fraction in listOf(-.25f, 0f, .25f, .5f, .75f, 1f, 1.25f)) {
                val inSlot = pageSlotFraction(fraction, slot, image)
                assertEquals(fraction, pageAlongFraction(inSlot, slot, image), .0001f)
            }
        }
    }

    @Test fun zoomedPresentationLetterboxAndReturnToVerticalKeepTheDocumentFocalPoint() {
        val pose = pageFocalPose(ViewportState(zoom = 2f, centerX = .7f), 0, .65f, false)
        val slot = pageRowLength(600f, 400f, 2f, pose, true)
        val image = pageImageLength(600f, 400f, 2f, pose, true)
        assertEquals(600f, slot, 0f)
        assertEquals(400f, image, 0f)
        val slotCenter = pageSlotFraction(pose.centerX, slot, image)
        val restored = pageFocalPose(pose, 0, pageAlongFraction(slotCenter, slot, image), true)
        assertEquals(.7f, restored.centerX, .0001f)
        assertEquals(.65f, restored.centerY, .0001f)
        val verticalLength = pageRowLength(600f, 400f, 2f, restored, false)
        val verticalFraction = legacyPageFraction(verticalLength, 400f, restored, false, 8f)
        assertEquals(.65f, (verticalFraction * (verticalLength + 8f) + 200f) / verticalLength, .0001f)
    }

    @Test fun crossAxisPanFollowsTheFingerInEachRotationAndReadingDirection() {
        val expected = listOf(
            ViewportState(2f, .45f, .5f, 0), ViewportState(2f, .5f, .55f, 1),
            ViewportState(2f, .55f, .5f, 2), ViewportState(2f, .5f, .45f, 3),
        )
        for (rotation in 0..3) {
            val actual = pagePan(ViewportState(zoom = 2f, rotation = rotation), 600f, 300f, 60f)
            assertEquals(expected[rotation].centerX, actual.centerX, .0001f)
            assertEquals(expected[rotation].centerY, actual.centerY, .0001f)
        }
        val horizontal = pagePan(ViewportState(zoom = 2f), 600f, 300f, 60f, horizontal = true)
        assertEquals(.5f, horizontal.centerX, .0001f)
        assertEquals(.45f, horizontal.centerY, .0001f)
        val shortPage = pagePan(ViewportState(zoom = 2f), 800f, 400f, 100f,
            horizontal = true, baseExtent = 200f)
        assertEquals("A page shorter than the viewport cannot be dragged into blank space", .5f, shortPage.centerY, 0f)
    }

    @Test fun pinchingUsesTheOldVisibleCenterForRestoredOutOfBoundsPosesInEveryDirection() {
        fun poseAt(center: Float, zoom: Float, rotation: Int, horizontal: Boolean): ViewportState =
            when ((rotation + if (horizontal) 3 else 0) % 4) {
                1 -> ViewportState(zoom, .5f, 1f - center, rotation)
                2 -> ViewportState(zoom, 1f - center, .5f, rotation)
                3 -> ViewportState(zoom, .5f, center, rotation)
                else -> ViewportState(zoom, center, .5f, rotation)
            }
        for (horizontal in listOf(false, true)) for (rotation in 0..3) {
            for (previousZoom in listOf(1f, 2f)) for (storedCenter in listOf(0f, 1f)) {
                val visibleHalf = 1f / (2f * previousZoom)
                val visibleCenter = if (storedCenter == 0f) visibleHalf else 1f - visibleHalf
                val nextZoom = previousZoom + 1f
                val stored = poseAt(storedCenter, nextZoom, rotation, horizontal)
                val actual = pagePan(stored, 600f, 400f, 30f, previousZoom, horizontal)
                // Independent screen-to-document mapping starts at the center the old frame actually drew.
                val touchedPoint = visibleCenter + 100f / (600f * previousZoom)
                val expectedCenter = touchedPoint - 130f / (600f * nextZoom)
                val expected = poseAt(expectedCenter, nextZoom, rotation, horizontal)
                assertEquals("rotation=$rotation horizontal=$horizontal oldZoom=$previousZoom stored=$storedCenter",
                    expected.centerX, actual.centerX, .0001f)
                assertEquals(expected.centerY, actual.centerY, .0001f)
            }
            // A short letterboxed page starts centered even when the persisted coordinate is at an edge.
            for (storedCenter in listOf(0f, 1f)) {
                val actual = pagePan(poseAt(storedCenter, 4f, rotation, horizontal),
                    600f, 300f, 30f, previousZoom = 1f, horizontal = horizontal, baseExtent = 200f)
                val expected = poseAt(.5f - 30f / 800f, 4f, rotation, horizontal)
                assertEquals(expected.centerX, actual.centerX, .0001f)
                assertEquals(expected.centerY, actual.centerY, .0001f)
            }
        }
    }

    @Test fun legacyZoomedPagesRestoreTheSavedFocalPointRatherThanThePageTop() {
        val pose = ViewportState(zoom = 2f, centerX = .7f, centerY = .65f)
        val fraction = legacyPageFraction(1600f, 600f, pose, false, 8f)
        assertTrue(fraction > 0f)
        assertEquals(.65f, (fraction * 1608f + 300f) / 1600f, .0001f)
        val rotated = legacyPageFraction(1600f, 600f, pose.copy(rotation = 1), false, 8f)
        assertEquals(.7f, (rotated * 1608f + 300f) / 1600f, .0001f)
        assertEquals(0f, legacyPageFraction(200f, 600f, pose, false, 8f), 0f)
    }

    @Test fun rotatingAfterScrollingKeepsTheSameUnrotatedDocumentPoint() {
        val rotated = pageFocalPose(ViewportState(zoom = 3f, centerX = .7f, rotation = 1), 0, .65f, false)
        assertEquals(.7f, rotated.centerX, 0f)
        assertEquals(.65f, rotated.centerY, 0f)
        val fraction = legacyPageFraction(1800f, 600f, rotated, false, 8f)
        assertEquals(.7f, (fraction * 1808f + 300f) / 1800f, .0001f)
        val turnedAgain = pageFocalPose(rotated.copy(rotation = 2), 1, .7f, false)
        assertEquals(rotated.centerX, turnedAgain.centerX, 0f)
        assertEquals(rotated.centerY, turnedAgain.centerY, 0f)
    }

    @Test fun switchingScrollDirectionAndBackPreservesTheSameDocumentPoint() {
        val pose = pageFocalPose(ViewportState(zoom = 3f, centerX = .7f), 0, .65f, false)
        val horizontalFraction = legacyPageFraction(1200f, 400f, pose, true, 8f)
        val horizontalCenter = (horizontalFraction * 1208f + 200f) / 1200f
        assertEquals(.7f, horizontalCenter, .0001f)
        val restored = pageFocalPose(pose, 0, horizontalCenter, true)
        val verticalFraction = legacyPageFraction(2400f, 800f, restored, false, 8f)
        assertEquals(.65f, (verticalFraction * 2408f + 400f) / 2400f, .0001f)
        assertEquals(3f, restored.zoom, 0f)
    }

    @Test fun focalConversionUsesTheCenteredPageAndReanchorsThroughEarlierRows() {
        val lengths = floatArrayOf(400f, 100f, 800f)
        val focal = pageFocalAnchor(0, .75f, 800f, lengths.size, 8f) { lengths[it] }
        assertEquals(2, focal.page)
        assertEquals(190f / 800f, focal.fraction, .0001f)
        val restored = pageScrollAnchor(2, .1f, 600f, 8f) { lengths[it] }
        assertEquals(0, restored.page)
        assertEquals(296f / 408f, restored.fraction, .0001f)
        val center = pageFocalAnchor(restored.page, restored.fraction, 600f, lengths.size, 8f) { lengths[it] }
        assertEquals(2, center.page)
        assertEquals(.1f, center.fraction, .0001f)
    }

    @Test fun focalGapsAndDocumentEndsHaveDeterministicNearestLegalPositions() {
        val before = pageFocalAnchor(0, 0f, 206f, 2, 8f) { 100f }
        assertEquals(PageAnchor(0, 1f), before)
        val midpoint = pageFocalAnchor(0, 0f, 208f, 2, 8f) { 100f }
        assertEquals("Equal-distance gaps keep the displayed earlier page", PageAnchor(0, 1f), midpoint)
        val after = pageFocalAnchor(0, 0f, 210f, 2, 8f) { 100f }
        assertEquals(PageAnchor(1, 0f), after)
        assertEquals(PageAnchor(0, 0f), pageScrollAnchor(1, 0f, 600f, 8f) { 100f })
        assertEquals(PageAnchor(1, 1f), pageFocalAnchor(1, .5f, 600f, 2, 8f) { 100f })
        var visited = 0
        pageFocalAnchor(500_000, 0f, 600f, 1_000_000, 8f) { visited++; 300f }
        assertTrue("Conversion only visits the viewport span", visited < 10)
    }

    @Test fun pinchingKeepsTheTouchedDocumentCoordinateAndPanCannotExposeEmptyMargins() {
        val before = ViewportState(zoom = 2f)
        val after = pagePan(before.copy(zoom = 3f), 600f, 400f, 0f, previousZoom = before.zoom)
        val oldPoint = before.centerX + 100f / (600f * before.zoom)
        val newPoint = after.centerX + 100f / (600f * after.zoom)
        assertEquals(oldPoint, newPoint, .0001f)
        val clamped = pagePan(after, 600f, 300f, 100000f)
        assertEquals(1f / 6f, clamped.centerX, .0001f)
    }
}
