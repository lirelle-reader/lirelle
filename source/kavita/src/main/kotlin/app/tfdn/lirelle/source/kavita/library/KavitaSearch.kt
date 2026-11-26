package app.tfdn.lirelle.source.kavita.library

import app.tfdn.lirelle.core.ids.AuthorId
import app.tfdn.lirelle.core.library.model.Author
import app.tfdn.lirelle.core.library.model.BookSummary
import app.tfdn.lirelle.core.library.model.SearchResults
import app.tfdn.lirelle.core.library.model.SearchSection
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.source.kavita.library.dto.PersonDto
import app.tfdn.lirelle.source.kavita.library.dto.SearchResultDto
import app.tfdn.lirelle.source.kavita.library.dto.SeriesDto
import app.tfdn.lirelle.source.kavita.library.dto.VolumeDto
import app.tfdn.lirelle.source.kavita.network.answer
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Search on the profile's server (spec §6 and §14): one search call, then what it does not say. Its series are
 * series, counted as none since Kavita counts chapters. Its chapters are books, which name no series: each volume
 * is asked once for its series and its number, and each series the search does not name once for its name; a
 * volume or a series gone between two calls (404) leaves its books out. Its people are authors, all of them (they
 * carry no roles), counted in one call. Kavita gives up to [GroupSize] of each and no total: a full group of series
 * or authors counts one more, so that "See all" shows; the books count what came, having no list to see all of.
 * At most six calls are made at once ([KavitaCallLimit]).
 */
internal class KavitaSearch(private val server: KavitaProfileServer) {

    private val calls = KavitaCallLimit()

    /** What the server finds for [text]; nothing is asked for a blank text. */
    suspend fun search(text: String): ApiResult<SearchResults> = server.call { remote ->
        val query = text.orNullIfBlank() ?: return@call SearchResults(text)
        val found = calls.limited { remote.api.search(query) }
        coroutineScope {
            val authors = async { authors(remote, found.persons) }
            val books = books(remote, found)
            val series = found.series.distinctBy { it.seriesId }.map {
                remote.mapper.series(SeriesDto(id = it.seriesId, name = it.name))
            }
            SearchResults(
                text = text,
                books = SearchSection(books, books.size),
                series = series.inSection(found.series.size),
                authors = authors.await().inSection(found.persons.size),
            )
        }
    }

    /** The chapters found, each brought to its book once, in the search's order, shown in its series. */
    private suspend fun books(remote: KavitaRemote, found: SearchResultDto): List<BookSummary> = coroutineScope {
        val chapters = found.chapters.distinctBy { it.item() }
        val volumes = chapters.map { it.volumeId }.distinct()
            .map { id -> async { id to calls.orGone { remote.api.volume(id) } } }
            .awaitAll().toMap()
        val named = found.series.associate { it.seriesId to it.name }
        val asked = volumes.values.mapNotNull { it?.seriesId }.distinct().filter { it !in named }
            .map { id -> async { id to calls.orGone { remote.api.oneSeries(id) } } }
            .awaitAll().toMap()
        chapters.mapNotNull { chapter ->
            val volume: VolumeDto = volumes[chapter.volumeId] ?: return@mapNotNull null
            val series = asked[volume.seriesId]
            val name = named[volume.seriesId] ?: series?.name ?: return@mapNotNull null
            remote.mapper.summary(chapter.book(volume.index), SeriesRefInfo(volume.seriesId, name), series?.format)
        }
    }

    /**
     * The people found as authors, by name, which stands for a Kavita person's id; their numbers of books from one
     * list of people, any of whose names is one of theirs.
     */
    private suspend fun authors(remote: KavitaRemote, persons: List<PersonDto>): List<Author> {
        val names = persons.mapNotNull { it.name.orNullIfBlank() }.distinct()
        if (names.isEmpty()) return emptyList()
        val filter = personFilter(names.map { Statement(PersonField.Name, Comparison.Equal, it) }, or = true)
        val counted = calls.limited { remote.api.persons(filter, FirstPage, names.size) }.answer()
        val counts = counted.associate { it.name.trim().lowercase() to it.chapterCount }
        return names.map { Author(AuthorId(it), it, counts[it.lowercase()] ?: 0, portraitUrl = null) }
    }
}

/** These items in a section: one more in all than came when Kavita's group of [found] items was full. */
private fun <T> List<T>.inSection(found: Int) = SearchSection(this, size + if (found >= GroupSize) 1 else 0)

/** The most items Kavita's search gives of each kind. */
private const val GroupSize = 15
