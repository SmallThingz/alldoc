package app.smallthingz.alldoc.ui

import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import androidx.paging.PagingState
import app.smallthingz.alldoc.state.LibraryDocument
import app.smallthingz.alldoc.state.LibraryPage
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class LibraryPagingTest {
    private fun documents(count: Int) = List(count) { index ->
        LibraryDocument(index.toLong(), null, "Document $index", "text/markdown", 1, "file:///document-$index", 0, 1)
    }

    @Test(timeout = 10000) fun supersededQueriesStopInsideTheCatalogWorker() = runBlocking {
        val entered = CountDownLatch(1)
        val stopped = AtomicBoolean()
        val source = LibraryPaging { _: Int, _: Int, check: () -> Unit ->
            entered.countDown()
            try {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (System.nanoTime() < deadline) { check(); java.util.concurrent.locks.LockSupport.parkNanos(1_000_000) }
                LibraryPage(0, emptyList())
            } finally { stopped.set(true) }
        }
        val request = async(Dispatchers.Default) { source.load(PagingSource.LoadParams.Refresh(null, 48, false)) }
        assertTrue("Query never reached the worker", entered.await(5, TimeUnit.SECONDS))
        val cancelledAt = System.nanoTime()
        request.cancelAndJoin()
        assertTrue("Cancellation must not wait for the full scan", System.nanoTime() - cancelledAt < TimeUnit.SECONDS.toNanos(2))
        assertTrue("Typing a new query must cancel the old scan, not leave background work", stopped.get())
    }

    @Test fun differentLoadSizesPreserveEveryBoundaryAndTheActualRefreshAnchor() = runBlocking {
        val rows = documents(400)
        val source = LibraryPaging { offset, limit -> LibraryPage(0, rows.drop(offset).take(limit)) }
        suspend fun page(params: PagingSource.LoadParams<Int>) =
            source.load(params) as PagingSource.LoadResult.Page<Int, LibraryDocument>
        val initial = page(PagingSource.LoadParams.Refresh(null, 120, false))
        val config = PagingConfig(pageSize = 40, initialLoadSize = 120, enablePlaceholders = false)
        assertEquals(0, source.getRefreshKey(PagingState(listOf(initial), 0, config, 0)))
        val middle = page(PagingSource.LoadParams.Refresh(80, 120, false))
        val before = page(PagingSource.LoadParams.Prepend(requireNotNull(middle.prevKey), 40, false))
        val first = page(PagingSource.LoadParams.Prepend(requireNotNull(before.prevKey), 80, false))
        val after = page(PagingSource.LoadParams.Append(requireNotNull(middle.nextKey), 25, false))
        assertNull(first.prevKey)
        assertEquals((0L until 225L).toList(), (first.data + before.data + middle.data + after.data).map { it.id })
        for ((anchor, placeholders, absolute) in listOf(Triple(0, 0, 80), Triple(30, 0, 110),
            Triple(110, 80, 110), Triple(0, 80, 80), Triple(500, 80, 224))) {
            val key = source.getRefreshKey(PagingState(listOf(middle, after), anchor, config, placeholders))
            assertEquals((absolute - 60).coerceAtLeast(0), key)
            val refreshed = page(PagingSource.LoadParams.Refresh(key, 120, false))
            assertTrue("Refresh must retain absolute row $absolute after leading pages were dropped",
                refreshed.data.any { it.id == absolute.toLong() })
        }
    }

    @Test fun queriesStayBoundedAndAnOutdatedTailRefreshFindsTheRemainingDocuments() = runBlocking {
        var rows = documents(500)
        val limits = mutableListOf<Int>()
        var generation = 0L
        fun source() = LibraryPaging { offset, limit -> limits.add(limit); LibraryPage(generation, rows.drop(offset).take(limit)) }
        val large = source().load(PagingSource.LoadParams.Refresh(null, 900, false)) as PagingSource.LoadResult.Page<Int, LibraryDocument>
        assertEquals(200, large.data.size)
        rows = rows.take(37)
        generation++
        val refreshed = source().load(PagingSource.LoadParams.Refresh(400, 48, false)) as PagingSource.LoadResult.Page<Int, LibraryDocument>
        assertEquals(rows, refreshed.data)
        assertNull(refreshed.prevKey)
        assertNull(refreshed.nextKey)
        assertEquals(listOf(200, 48, 48), limits)
    }

    @Test fun changedCatalogGenerationsNeverMixOffsetPages() = runBlocking {
        for (mutation in listOf("insert", "delete", "merge", "rename")) {
            for (direction in listOf("append", "prepend", "refresh")) {
                var rows = documents(120)
                var generation = 0L
                fun source() = LibraryPaging { offset, limit ->
                    LibraryPage(generation, rows.drop(offset).take(limit))
                }
                val original = source()
                val initialOffset = if (direction == "prepend") 48 else 0
                val initial = original.load(PagingSource.LoadParams.Refresh(initialOffset, 48, false))
                    as PagingSource.LoadResult.Page<Int, LibraryDocument>
                rows = when (mutation) {
                    "insert" -> listOf(rows.first().copy(id = 1000, title = "Earlier document")) + rows
                    "delete" -> rows.drop(1)
                    "merge" -> rows.filterNot { it.id == 1L }
                    else -> rows.drop(1) + rows.first().copy(title = "Renamed to sort last")
                }
                generation++
                val params: PagingSource.LoadParams<Int> = when (direction) {
                    "append" -> PagingSource.LoadParams.Append(requireNotNull(initial.nextKey), 48, false)
                    "prepend" -> PagingSource.LoadParams.Prepend(requireNotNull(initial.prevKey), 48, false)
                    else -> PagingSource.LoadParams.Refresh(24, 48, false)
                }
                assertTrue("$mutation during $direction must invalidate before exposing shifted rows",
                    original.load(params) is PagingSource.LoadResult.Invalid)

                val current = source()
                val seen = mutableListOf<LibraryDocument>()
                var page = current.load(PagingSource.LoadParams.Refresh(null, 48, false))
                    as PagingSource.LoadResult.Page<Int, LibraryDocument>
                var loadedPages = 0
                while (true) {
                    assertTrue("Paging must terminate within the current row count", ++loadedPages <= (rows.size + 47) / 48 + 1)
                    seen.addAll(page.data)
                    val next = page.nextKey ?: break
                    page = current.load(PagingSource.LoadParams.Append(next, 48, false))
                        as PagingSource.LoadResult.Page<Int, LibraryDocument>
                }
                assertEquals("A new generation must retain every current document", rows, seen)
                assertEquals("Lazy keys cannot collide across current pages", seen.size, seen.map { it.id }.toSet().size)
            }
        }
    }
}
