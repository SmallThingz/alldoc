package app.smallthingz.alldoc.ui

import app.smallthingz.alldoc.engine.PageRegion
import org.junit.Assert.*
import org.junit.Test

class PageDetailBudgetTest {
    @Test fun visibleDetailKeepsSourceCoordinatesWithinOneAggregateBudget() {
        for (count in 1..2) for ((width, height) in listOf(4000 to 2000, 2000 to 4000, 1080 to 1920, 514 to 4096, 1 to 4096)) {
            val source = PageRegion(.125f, .25f, .875f, .75f, width, height)
            val result = pageDetailRegion(source, count)
            assertEquals(source.left, result.left, 0f)
            assertEquals(source.top, result.top, 0f)
            assertEquals(source.right, result.right, 0f)
            assertEquals(source.bottom, result.bottom, 0f)
            assertTrue(kotlin.math.abs(result.height - height.toDouble() * result.width / width) <= 1.0)
            assertTrue(result.width in 1..width && result.height in 1..height)
            assertTrue(result.width.toLong() * result.height * 4 * count <= 16L * 1024 * 1024)
            if (width.toLong() * height * 4 <= pageDetailBudget(count)) assertSame(source, result)
        }
    }
}
