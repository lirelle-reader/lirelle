package app.tfdn.lirelle.source.audiobookshelf.library

import androidx.paging.PagingData
import app.tfdn.lirelle.core.library.LibraryRepository
import app.tfdn.lirelle.core.library.PagedList
import app.tfdn.lirelle.core.library.model.Genre
import app.tfdn.lirelle.core.library.model.HomeRow
import app.tfdn.lirelle.core.library.model.HomeRowExtent
import app.tfdn.lirelle.core.library.model.HomeRowKind
import app.tfdn.lirelle.core.library.model.SearchResults
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

/** Nothing of that id: what a library that shows nothing answers to what is asked by its id. */
internal val NotFound = ApiResult.Failure(ApiError.NotFound)

/** A list that is there and empty. */
internal fun <T> none(): ApiResult<List<T>> = ApiResult.Success(emptyList())

/** A paged list that is there and empty: no page, and none in all. */
internal fun <T : Any> emptyPages() = PagedList(flowOf(PagingData.empty<T>()), MutableStateFlow(0))

/**
 * What an Audiobookshelf profile's library does not show yet: Home's rows, the search and the genres are there, with
 * nothing in them, until they are asked of the server too. The server is asked nothing for them. [AbsLibrary]
 * answers the rest.
 */
internal abstract class EmptyAbsLists : LibraryRepository {

    override suspend fun homeRow(kind: HomeRowKind, extent: HomeRowExtent): ApiResult<HomeRow> =
        ApiResult.Success(HomeRow(kind, emptyList(), hasMore = false))

    override suspend fun search(text: String): ApiResult<SearchResults> = ApiResult.Success(SearchResults(text))

    override suspend fun genres(): ApiResult<List<Genre>> = none()
}
