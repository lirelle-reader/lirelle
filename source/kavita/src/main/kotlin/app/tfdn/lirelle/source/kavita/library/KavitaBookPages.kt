package app.tfdn.lirelle.source.kavita.library

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.model.BookDetail
import app.tfdn.lirelle.core.library.model.BookReading
import app.tfdn.lirelle.core.library.model.PageDirection
import app.tfdn.lirelle.core.library.model.RelatedBooks
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.source.kavita.library.dto.ChapterDto
import app.tfdn.lirelle.source.kavita.library.dto.SeriesDto
import app.tfdn.lirelle.source.kavita.library.dto.VolumeDto
import java.time.ZoneId
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * A book's page, its reading and the other books of its series, asked of [server] by what its id says it is: a
 * volume is asked as a volume (`GET api/Volume`), a chapter as a chapter (`GET api/Chapter`). An id that is none of
 * a Kavita book's is not found, without a word to the server.
 *
 * @param zone the device's calendar, which Kavita's days are told in.
 */
internal class KavitaBookPages(
    private val server: KavitaProfileServer,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {

    /**
     * The book, its series, and what the series' metadata says that its chapter does not: a book whose metadata
     * cannot be read shows without. A special alone in its series has no series line, which its series' content
     * tells.
     */
    suspend fun book(bookId: BookId): ApiResult<BookDetail> = when (val item = bookId.kavitaItem()) {
        null -> NotFound
        is KavitaItem.Volume -> server.call { remote ->
            val volume = remote.api.volume(item.id)
            val book = volume.bookOf(item)
            coroutineScope {
                val series = async { remote.api.oneSeries(volume.seriesId) }
                val metadata = async { server.orNull { remote.api.metadata(volume.seriesId) } }
                val direction = async { directionOf(remote, series.await()) }
                remote.mapper.detail(book, series.await(), metadata.await(), direction = direction.await())
            }
        }
        is KavitaItem.Chapter -> server.call { remote ->
            val (chapter, series) = coroutineScope {
                val chapter = async { remote.api.chapter(item.id) }
                val series = async { remote.api.seriesOfChapter(item.id) }
                chapter.await() to series.await()
            }
            coroutineScope {
                val metadata = async { server.orNull { remote.api.metadata(series.id) } }
                // Only a special may be alone in its series: its series' content tells.
                val lone = async {
                    chapter.isSpecial && server.orNull { remote.api.detail(series.id) }?.isLoneBook == true
                }
                val direction = async { directionOf(remote, series) }
                remote.mapper.detail(chapter.asAsked(), series, metadata.await(), lone.await(), direction.await())
            }
        }
    }

    /**
     * The direction the [series]' pages turn in: its reading profile and its library's type, asked together. Null
     * when either cannot be had (a server older than 0.8.7 has no profile), the page then showing without.
     */
    private suspend fun directionOf(remote: KavitaRemote, series: SeriesDto): PageDirection? = coroutineScope {
        val profile = async { server.orNull { remote.api.readingProfile(series.libraryId, series.id) } }
        val libraries = async { server.orNull { remote.api.libraries() } }
        val type = libraries.await()?.firstOrNull { it.id == series.libraryId }?.type ?: UnknownLibraryType
        directionOf(profile.await(), type)
    }

    /**
     * Where the book's reading is: the pages read of its chapter, and the time its real sessions took, which Kavita
     * 0.9 tells and 0.8.9 does not (none then, as when the series' history cannot be had).
     */
    suspend fun bookReading(bookId: BookId): ApiResult<BookReading> = when (val item = bookId.kavitaItem()) {
        null -> NotFound
        is KavitaItem.Volume -> server.call { remote ->
            val volume = remote.api.volume(item.id)
            val chapter = volume.bookOf(item).chapter
            readingOf(remote, chapter, volume.seriesId)
        }
        is KavitaItem.Chapter -> server.call { remote ->
            val chapter = remote.api.chapter(item.id)
            readingOf(remote, chapter, server.orNull { remote.api.seriesOfChapter(item.id) }?.id)
        }
    }

    /** The reading of [chapter], with its time from its series' history; none when [seriesId] is unknown or fails. */
    private suspend fun readingOf(remote: KavitaRemote, chapter: ChapterDto, seriesId: Int?): BookReading {
        val items = seriesId?.let { id ->
            val zoneId = zone().id
            server.orNull { wholeList { page, size -> remote.api.seriesHistory(id, zoneId, page, size) } }
        }.orEmpty()
        val entries = items.filter { !it.isGeneratedSession }
            .flatMap { item -> item.chapters.filter { it.chapterId == chapter.id }.map { item.sessionId to it } }
        return remote.mapper.reading(
            chapter,
            seconds = entries.sumOf { it.second.durationSeconds },
            sessions = entries.map { it.first }.distinct().size,
        )
    }

    /**
     * The other books of the book's series, in its order; none for a lone book. Kavita has no books "by the same
     * author" nor similar books to give. Never fails: a call that fails leaves the row empty.
     */
    suspend fun relatedBooks(bookId: BookId): RelatedBooks {
        val item = bookId.kavitaItem() ?: return RelatedBooks()
        val inSeries = server.call { remote ->
            val seriesId = when (item) {
                is KavitaItem.Volume -> remote.api.volume(item.id).seriesId
                is KavitaItem.Chapter -> remote.api.seriesOfChapter(item.id).id
            }
            val detail = remote.api.detail(seriesId)
            if (detail.isLoneBook) {
                emptyList()
            } else {
                detail.books().filter { it.item != item }.map(remote.mapper::related)
            }
        }
        return RelatedBooks(inSeries = (inSeries as? ApiResult.Success)?.value.orEmpty())
    }
}

/** The volume's book of that id; not found when the volume holds none, its only chapter being another's book. */
private fun VolumeDto.bookOf(item: KavitaItem.Volume): KavitaBook =
    books().firstOrNull { it.item == item } ?: notFound()

/**
 * The chapter as the book its id named: itself, even one numbered as a default chapter, since a chapter outside
 * any volume is a book of its own in its series' books.
 */
private fun ChapterDto.asAsked(): KavitaBook =
    KavitaBook(KavitaItem.Chapter(id), this, if (isSpecial) null else range.orIndex(), isSpecial)

/** The type of a library the server does not list: none of Kavita's. */
private const val UnknownLibraryType = -1
