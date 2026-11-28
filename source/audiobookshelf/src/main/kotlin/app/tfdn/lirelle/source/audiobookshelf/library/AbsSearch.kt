package app.tfdn.lirelle.source.audiobookshelf.library

import app.tfdn.lirelle.core.library.model.Author
import app.tfdn.lirelle.core.library.model.SearchResults
import app.tfdn.lirelle.core.library.model.SearchSection
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.source.audiobookshelf.library.dto.AbsSearchDto
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * The search, asked of [server] (spec §6): each book library's search ([whole] keeps them), all at once, joined in
 * the libraries' order. The server ignores case and accents. Its books are books, each once; its series are series
 * with their books, which they count and whose covers their tiles show (the search gives no places in them); its
 * authors are authors, with their numbers of books. An author's name finds the author, not the author's books.
 * The server gives up to [SearchLimit] of each kind per library and no total: a full group counts one more, so that
 * "See all" shows. A library the account may not search (403) or no longer has (404) finds nothing; any other
 * failure fails the search.
 * The read states and progress come from the account's [progress].
 */
internal class AbsSearch(
    private val server: AbsProfileServer,
    private val progress: AbsProgress,
    private val whole: AbsWholeLibrary,
) {

    /** What the server finds for [text]; nothing is asked for a blank text. */
    suspend fun search(text: String): ApiResult<SearchResults> {
        val query = text.trim().takeIf { it.isNotEmpty() } ?: return ApiResult.Success(SearchResults(text))
        return server.call { remote ->
            val libraries = whole.libraries(remote, refresh = null)
            val (found, readings) = coroutineScope {
                val readings = async { progress.readings() }
                libraries.map { async { remote.found(it.id, query) } }.awaitAll() to readings.await()
            }
            val books = found.flatMap { it.book }.map { it.libraryItem }.distinctBy { it.id }
                .map { remote.books.summary(it, readings) }
            val series = found.flatMap { it.series }.distinctBy { it.series.id }
                .map { remote.series(it.series, it.books, readings) }
            val authors = found.flatMap { it.authors }.distinctBy { it.id }
                .map { Author(it.id.asAuthorId(), it.name, it.numBooks ?: 0, portraitUrl = null) }
            SearchResults(
                text = text,
                books = books.inSection(found.any { it.book.size >= SearchLimit }),
                series = series.inSection(found.any { it.series.size >= SearchLimit }),
                authors = authors.inSection(found.any { it.authors.size >= SearchLimit }),
            )
        }
    }
}

/** What the library [libraryId]'s search finds for [query]; nothing when the account may not search it. */
private suspend fun AbsRemote.found(libraryId: String, query: String): AbsSearchDto =
    unlessLibraryGone { api.search(libraryId, query, SearchLimit) } ?: AbsSearchDto()

/** These items in a section: one more in all than came when a library's group of them was [full]. */
private fun <T> List<T>.inSection(full: Boolean) = SearchSection(this, size + if (full) 1 else 0)

/** The most items of each kind a library's search is asked for: the server's own default, as its web client. */
private const val SearchLimit = 12
