package app.smallthingz.alldoc.pdf

import org.junit.Assert.assertEquals
import org.junit.Test

class PdfTextBudgetTest {
    @Test fun keepsAScalarSafePrefixAcrossBlocks() {
        val expected = listOf("", "a", "a", "a😀", "a😀b", "a😀b")
        val groups = listOf(listOf("a", "😀", "b"), listOf("a😀", "b"), listOf("a", "😀b"))
        for (blocks in groups) for (limit in expected.indices) {
            val budget = PdfTextBudget(limit)
            assertEquals("limit=$limit, blocks=$blocks", expected[limit], blocks.joinToString("") { budget.take(it) })
            assertEquals(if (limit == 5) 1 else 0, budget.remaining)
        }
    }
}
