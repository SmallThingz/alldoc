package app.smallthingz.alldoc.ui

import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.PagingData
import androidx.paging.map
import androidx.paging.insertSeparators
import app.smallthingz.alldoc.state.LibraryCatalog
import app.smallthingz.alldoc.state.LibraryDocument
import app.smallthingz.alldoc.state.LibraryPage
import app.smallthingz.alldoc.state.LibraryQuery
import app.smallthingz.alldoc.state.LibraryCategory
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Keys are absolute boundaries: the start for refresh/append, the exclusive end for prepend. */
internal class LibraryPaging(private val read: (Int, Int, () -> Unit) -> LibraryPage) : PagingSource<Int, LibraryDocument>() {
    constructor(read: (Int, Int) -> LibraryPage) : this({ offset, limit, _ -> read(offset, limit) })
    constructor(catalog: LibraryCatalog, query: LibraryQuery = LibraryQuery()) : this({ offset, limit, check ->
        catalog.pageSnapshot(offset, limit, query, check)
    })
    private val generation = AtomicLong(-1L)
    private fun accepts(page: LibraryPage): Boolean {
        generation.compareAndSet(-1L, page.generation)
        return generation.get() == page.generation
    }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, LibraryDocument> = withContext(Dispatchers.IO) {
        try {
            val context = currentCoroutineContext()
            val check = { context.ensureActive() }
            val boundary = (params.key ?: 0).coerceAtLeast(0)
            val requested = params.loadSize.coerceIn(1, MAX_LOAD)
            val prepend = params is LoadParams.Prepend
            var offset = if (prepend) (boundary - requested).coerceAtLeast(0) else boundary
            val limit = if (prepend) boundary - offset else requested
            if (limit == 0) return@withContext LoadResult.Page(emptyList(), null, 0, itemsBefore = 0)
            var page = read(offset, limit, check)
            if (!accepts(page)) return@withContext LoadResult.Invalid()
            // A scan may remove the entire old tail before an anchored refresh arrives.
            if (params is LoadParams.Refresh && page.rows.isEmpty() && offset > 0) {
                offset = 0
                page = read(offset, limit, check)
                if (!accepts(page)) return@withContext LoadResult.Invalid()
            }
            val rows = page.rows
            LoadResult.Page(rows, if (offset == 0) null else offset,
                if (rows.size < limit) null else offset + rows.size, itemsBefore = offset)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: ConcurrentModificationException) {
            LoadResult.Invalid()
        } catch (failure: Exception) {
            LoadResult.Error(failure)
        }
    }

    override fun getRefreshKey(state: PagingState<Int, LibraryDocument>): Int? {
        val item = state.anchorPosition?.let(state::closestItemToPosition) ?: return null
        for (page in state.pages) {
            val index = page.data.indexOfFirst { it.id == item.id }
            if (index >= 0) return (page.itemsBefore.coerceAtLeast(0) + index -
                state.config.initialLoadSize.coerceAtMost(MAX_LOAD) / 2).coerceAtLeast(0)
        }
        return null
    }

    private companion object { const val MAX_LOAD = 200 }
}

internal sealed interface LibraryEntry {
    val key: String
    data class Header(val category: LibraryCategory, override val key: String = "header:${category.key}") : LibraryEntry
    data class Document(val value: LibraryDocument, override val key: String = "document:${value.id}") : LibraryEntry
}

internal fun PagingData<LibraryDocument>.groupedLibrary(prefix: String = ""): PagingData<LibraryEntry> {
    val documents: PagingData<LibraryEntry> = map { LibraryEntry.Document(it, "$prefix:document:${it.id}") }
    return documents.insertSeparators { before, after ->
        val category = (after as? LibraryEntry.Document)?.value?.category
        if (category != null && category != (before as? LibraryEntry.Document)?.value?.category) LibraryEntry.Header(category, "$prefix:header:${category.key}") else null
    }
}
