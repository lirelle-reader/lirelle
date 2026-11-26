package app.tfdn.lirelle.source.kavita.library

import androidx.paging.Pager
import androidx.paging.PagingConfig
import app.tfdn.lirelle.core.library.IndexPagingSource
import app.tfdn.lirelle.core.library.PageResult
import app.tfdn.lirelle.core.library.PagedList
import app.tfdn.lirelle.core.library.model.BookListRequest
import app.tfdn.lirelle.core.library.model.BookListSource
import app.tfdn.lirelle.core.library.model.BookSummary
import app.tfdn.lirelle.core.library.model.Shelf
import app.tfdn.lirelle.core.library.model.ShelfKind
import app.tfdn.lirelle.core.library.model.Shelves
import app.tfdn.lirelle.core.network.ApiResult
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The profile's shelves, asked of [server]: Kavita's reading lists, the account's and those promoted by others,
 * shown as collections. Kavita has no smart lists. A list opens on its books, all asked in one call and paged on
 * the device, sorted and narrowed there ([arranged]).
 */
internal class KavitaShelves(private val server: KavitaProfileServer) {

    suspend fun shelves(): ApiResult<Shelves> = server.call { remote ->
        val lists = wholeList { page, size -> remote.api.readingLists(page, size) }
        Shelves(
            smartLists = emptyList(),
            collections = lists.distinctBy { it.id }.map {
                Shelf(ShelfKind.Collection, it.id.asShelfId(), it.title, it.itemCount)
            },
        )
    }

    /**
     * A reading list's books. There is no list of all the library's books, nor any smart list: those are empty,
     * and nothing is asked.
     */
    fun books(source: BookListSource, request: BookListRequest): PagedList<BookSummary> {
        val id = (source as? BookListSource.Collection)?.id?.kavitaNumber() ?: return emptyPages()
        val total = MutableStateFlow<Int?>(null)
        val pager = Pager(PagingConfig(PageSize, initialLoadSize = PageSize, enablePlaceholders = true)) {
            // The list is asked once per load of it (a refresh asks again), then paged here.
            var books: List<BookSummary>? = null
            IndexPagingSource(PageSize, onTotal = { total.value = it }) { page, size ->
                val all = books ?: server.loaded { remote ->
                    remote.api.readingListItems(id).arranged(request, remote.mapper)
                }.also { books = it }
                PageResult(all.drop(page * size).take(size), all.size)
            }
        }
        return PagedList(pager.flow, total)
    }

    private companion object {
        const val PageSize = 60
    }
}
