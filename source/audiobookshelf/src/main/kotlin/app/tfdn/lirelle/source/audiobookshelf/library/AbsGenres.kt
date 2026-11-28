package app.tfdn.lirelle.source.audiobookshelf.library

import app.tfdn.lirelle.core.library.model.Genre
import app.tfdn.lirelle.core.network.ApiResult
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * The genres of the profile's server, asked of [server] (spec §4): each book library's filter data ([whole] keeps
 * the libraries), all at once, their genres joined. Each name is trimmed and comes once, a blank one left out; they
 * are sorted as a reader sorts them, two names sorted alike keeping the libraries' order. Audiobookshelf does not
 * count a genre's books. A failure fails them all.
 */
internal class AbsGenres(private val server: AbsProfileServer, private val whole: AbsWholeLibrary) {

    /** The genres of the book libraries, by name. */
    suspend fun genres(): ApiResult<List<Genre>> = server.call { remote ->
        val libraries = whole.libraries(remote, refresh = null)
        val names = coroutineScope {
            libraries.map { async { remote.api.filterData(it.id).filterdata.genres } }.awaitAll().flatten()
        }
        names.map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sortedWith(readerCollator())
            .map { Genre(it, bookCount = null) }
    }
}
