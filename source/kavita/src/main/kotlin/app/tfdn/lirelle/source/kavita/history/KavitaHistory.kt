package app.tfdn.lirelle.source.kavita.history

import app.tfdn.lirelle.core.history.HistoryRepository
import app.tfdn.lirelle.core.history.ReadRecordCache
import app.tfdn.lirelle.core.history.model.HistoryCapabilities
import app.tfdn.lirelle.core.history.model.HistoryPeriod
import app.tfdn.lirelle.core.history.model.RatingResult
import app.tfdn.lirelle.core.history.model.ReadMarks
import app.tfdn.lirelle.core.history.model.ReadOutcome
import app.tfdn.lirelle.core.history.model.ReadPage
import app.tfdn.lirelle.core.history.model.ReadRecord
import app.tfdn.lirelle.core.history.model.ReadingSummary
import app.tfdn.lirelle.core.history.model.ReadingTotals
import app.tfdn.lirelle.core.history.model.SeriesRef
import app.tfdn.lirelle.core.ids.ReadId
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.session.profile.ProfileSession
import app.tfdn.lirelle.source.kavita.library.KavitaBook
import app.tfdn.lirelle.source.kavita.library.KavitaCallLimit
import app.tfdn.lirelle.source.kavita.library.KavitaLibraryApi
import app.tfdn.lirelle.source.kavita.library.KavitaMapper
import app.tfdn.lirelle.source.kavita.library.KavitaTitles
import app.tfdn.lirelle.source.kavita.library.MaxBatches
import app.tfdn.lirelle.source.kavita.library.SeriesRefInfo
import app.tfdn.lirelle.source.kavita.library.books
import app.tfdn.lirelle.source.kavita.library.isLoneBook
import app.tfdn.lirelle.source.kavita.library.kavitaReadId
import app.tfdn.lirelle.source.kavita.library.wholeList
import app.tfdn.lirelle.source.kavita.network.KavitaApis
import app.tfdn.lirelle.source.kavita.network.KavitaCall
import app.tfdn.lirelle.source.kavita.network.KavitaErrors
import app.tfdn.lirelle.source.kavita.reading.KavitaReadingApi
import java.time.Duration
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * A Kavita profile's reading history: the books the account read to the end, built from every reading session of
 * its libraries (spec §5), newest end first. A book read again is one more row, with its time and sessions when
 * they were real; one marked read has none. Each read is named as the app names its book (title, people, series,
 * cover), a book no longer in its series being left out. There is no summary, outcome, rating nor marks.
 *
 * Kavita gives its sessions, not its reads, so the whole history (every year) is loaded when the screen asks its
 * first page and kept: the pages after it, and the first page of another year, are taken from it; the first page of
 * the same year again (coming back to History, a retry) loads it anew. The books of each series met are kept for the
 * profile's life, so a load asks only the series not seen yet, and those that lack the chapter of a read. The rows
 * of each page go to [cache] for their detail.
 *
 * @param zone the device's calendar, which Kavita's days are told in.
 */
internal class KavitaHistory(
    private val session: ProfileSession,
    private val reading: KavitaApis<KavitaReadingApi>,
    private val library: KavitaApis<KavitaLibraryApi>,
    private val titles: KavitaTitles,
    private val errors: KavitaErrors,
    private val cache: ReadRecordCache,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) : HistoryRepository {

    override val capabilities = HistoryCapabilities(
        summary = false,
        outcomes = false,
        rating = false,
        marks = false,
        durations = true,
        sessions = true,
        rereads = true,
    )

    private val calls = KavitaCallLimit()

    /** The rows of every read of the last load, all years, newest end first; null before the first. */
    @Volatile
    private var loaded: List<ReadRecord>? = null

    /** The period the screen asked last. */
    @Volatile
    private var lastPeriod: HistoryPeriod? = null

    /** The books of each series met, by series id. */
    private val seriesBooks = ConcurrentHashMap<Int, SeriesBooks>()

    /**
     * A page of the reads ended in [period], from [offset]: the first page of the period asked last loads them all
     * again; the next pages, and the first page of another period, take them from the last load. No month totals:
     * the screen counts the reads of each month from its rows. Only finished reads exist: asked for none, nothing is
     * asked.
     */
    override suspend fun log(
        period: HistoryPeriod,
        outcomes: Set<ReadOutcome>,
        newestFirst: Boolean,
        offset: Int,
        limit: Int,
    ): ApiResult<ReadPage> {
        if (ReadOutcome.Completed !in outcomes) {
            return ApiResult.Success(ReadPage(emptyList(), months = null, total = 0, offset, limit))
        }
        return errors.call(KavitaCall.Authenticated) {
            val held = loaded?.takeIf { offset > 0 || period != lastPeriod }
            lastPeriod = period
            val reads = (held ?: load().also { loaded = it })
                .filter { period.year == null || it.endedOn?.year == period.year }
            val ordered = if (newestFirst) reads else reads.asReversed()
            val records = ordered.drop(offset).take(limit.coerceAtLeast(0))
            cache.put(records)
            ReadPage(records, months = null, total = ordered.size, offset = offset, limit = limit)
        }
    }

    /** The rows of every read, newest end first: every session of the account's libraries. */
    private suspend fun load(): List<ReadRecord> {
        val serverUrl = session.session.value.serverUrl
        val api = reading.forServer(serverUrl)
        val libraryApi = library.forServer(serverUrl)
        // Kavita answers nothing for no library, and the account may see none.
        val libraries = libraryApi.libraries().map { it.id }.ifEmpty { return emptyList() }
        val zoneId = zone().id
        val sessions = wholeList(HistoryBatch, MaxBatches) { page, size -> api.history(libraries, zoneId, page, size) }
        val reads = readsOf(sessions)
        askSeries(libraryApi, reads)
        val mapper = KavitaMapper(serverUrl, titles)
        return reads.mapNotNull { read -> seriesBooks[read.seriesId]?.let { record(mapper, read, it) } }
    }

    /**
     * Asks together the books of each series of [reads] not seen yet, or seen without the chapter of one of them; a
     * series no longer there is forgotten.
     */
    private suspend fun askSeries(api: KavitaLibraryApi, reads: List<ChapterRead>) = coroutineScope {
        val wanted = reads.groupBy { it.seriesId }.filter { (id, ofSeries) ->
            val known = seriesBooks[id]
            known == null || ofSeries.any { it.chapterId !in known.byChapter }
        }.keys
        wanted.map { id ->
            async {
                val detail = calls.orGone { api.detail(id) }
                if (detail == null) {
                    seriesBooks.remove(id)
                } else {
                    seriesBooks[id] = SeriesBooks(detail.books().associateBy { it.chapter.id }, detail.isLoneBook)
                }
            }
        }.awaitAll()
    }

    /** The row of [read], named as the app names its book in [series]; none when the chapter left the series. */
    private fun record(mapper: KavitaMapper, read: ChapterRead, series: SeriesBooks): ReadRecord? {
        val book = series.byChapter[read.chapterId] ?: return null
        val shownIn = SeriesRefInfo(read.seriesId, read.seriesName.orEmpty()).takeUnless { series.lone }
        val summary = mapper.summary(book, shownIn)
        return ReadRecord(
            id = summary.id.kavitaReadId(read.readNumber),
            bookId = summary.id,
            title = summary.title,
            authors = summary.authors,
            series = summary.series?.let { SeriesRef(it.name, it.index) },
            // Kavita has one picture of a book, its cover, for the row and the detail alike.
            coverUrl = summary.coverUrl,
            detailCoverUrl = summary.coverUrl,
            startedOn = read.startedOn,
            endedOn = read.endedOn,
            outcome = ReadOutcome.Completed,
            readNumber = read.readNumber,
            progressPercent = null,
            sessions = read.sessions,
            duration = read.duration,
            rating = null,
            highlightCount = 0,
            journalCount = 0,
            removed = false,
        )
    }

    /** Kavita has no summary: never asked (the capability is off), an empty one all the same. */
    override suspend fun summary(period: HistoryPeriod): ApiResult<ReadingSummary> = ApiResult.Success(
        ReadingSummary(
            years = emptyList(),
            totals = ReadingTotals(0, 0, 0, 0, Duration.ZERO, averageRating = null),
            goal = null,
            records = emptyList(),
        ),
    )

    /** A read on Kavita has no marks of its own. */
    override suspend fun marks(readId: ReadId): ApiResult<ReadMarks> = ApiResult.Failure(ApiError.NotFound)

    /** A read on Kavita cannot be rated: Kavita rates series. */
    override suspend fun setRating(readId: ReadId, stars: Int?): ApiResult<RatingResult> =
        ApiResult.Failure(ApiError.NotFound)
}

/** How many sessions each batch of the history holds: Kavita accepts 1000. */
private const val HistoryBatch = 1000

/** A series' books by the id of the chapter each is read through, and whether it is a [lone] book. */
private class SeriesBooks(val byChapter: Map<Int, KavitaBook>, val lone: Boolean)
