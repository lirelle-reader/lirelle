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
import app.tfdn.lirelle.source.kavita.library.SeriesRefInfo
import app.tfdn.lirelle.source.kavita.library.books
import app.tfdn.lirelle.source.kavita.library.isLoneBook
import app.tfdn.lirelle.source.kavita.library.wholeList
import app.tfdn.lirelle.source.kavita.network.KavitaApis
import app.tfdn.lirelle.source.kavita.network.KavitaCall
import app.tfdn.lirelle.source.kavita.network.KavitaErrors
import app.tfdn.lirelle.source.kavita.reading.KavitaReadingApi
import java.time.Duration
import java.time.ZoneId
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * A Kavita profile's reading history: the books the account read to the end, built from every reading session of
 * its libraries (spec §5), newest end first. A book read again is one more row, with its time and sessions when
 * they were real; one marked read has none. Each read is named as the app names its book (title, people, series,
 * cover), a book no longer in its series being left out. There is no summary, outcome, rating nor marks.
 *
 * Kavita gives its sessions, not its reads, so the whole history is loaded when the screen asks its first page and
 * kept for the pages after it: asking the first page again (coming back to History, another year) loads it anew.
 * The rows of each page go to [cache] for their detail.
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

    /** The rows of the last load, and the period they were loaded for. */
    @Volatile
    private var loaded: LoadedReads? = null

    /**
     * A page of the reads ended in [period], from [offset]: the first page loads them all again, the next ones take
     * them from that load (from a new one when it was for another period). No month totals: the screen counts the
     * reads of each month from its rows. Only finished reads exist: asked for none, nothing is asked.
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
            val reads = loaded?.takeIf { offset > 0 && it.period == period }
                ?: LoadedReads(period, load(period)).also { loaded = it }
            val ordered = if (newestFirst) reads.records else reads.records.asReversed()
            val records = ordered.drop(offset).take(limit.coerceAtLeast(0))
            cache.put(records)
            ReadPage(records, months = null, total = ordered.size, offset = offset, limit = limit)
        }
    }

    /** The rows of the reads ended in [period], newest end first: every session of the account's libraries. */
    private suspend fun load(period: HistoryPeriod): List<ReadRecord> {
        val serverUrl = session.session.value.serverUrl
        val api = reading.forServer(serverUrl)
        // Kavita answers nothing for no library, and the account may see none.
        val libraries = api.libraries().map { it.id }.ifEmpty { return emptyList() }
        val zoneId = zone().id
        val reads = readsOf(wholeList { page, size -> api.history(libraries, zoneId, page, size) })
            .filter { period.year == null || it.endedOn.year == period.year }
        val series = seriesOf(library.forServer(serverUrl), reads.map { it.seriesId }.distinct())
        val mapper = KavitaMapper(serverUrl, titles)
        return reads.mapNotNull { read -> series[read.seriesId]?.let { record(mapper, read, it) } }
    }

    /** The books of each series of [ids], asked together; a series no longer there is left out. */
    private suspend fun seriesOf(api: KavitaLibraryApi, ids: List<Int>): Map<Int, SeriesBooks> = coroutineScope {
        ids.map { id ->
            async {
                calls.orGone { api.detail(id) }?.let { detail ->
                    id to SeriesBooks(detail.books().associateBy { it.chapter.id }, detail.isLoneBook)
                }
            }
        }.awaitAll().filterNotNull().toMap()
    }

    /** The row of [read], named as the app names its book in [series]; none when the chapter left the series. */
    private fun record(mapper: KavitaMapper, read: ChapterRead, series: SeriesBooks): ReadRecord? {
        val book = series.byChapter[read.chapterId] ?: return null
        val shownIn = SeriesRefInfo(read.seriesId, read.seriesName.orEmpty()).takeUnless { series.lone }
        val summary = mapper.summary(book, shownIn)
        return ReadRecord(
            id = ReadId("${summary.id.value}#${read.readNumber}"),
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

/** The rows of the reads ended in [period], newest end first. */
private class LoadedReads(val period: HistoryPeriod, val records: List<ReadRecord>)

/** A series' books by the id of the chapter each is read through, and whether it is a [lone] book. */
private class SeriesBooks(val byChapter: Map<Int, KavitaBook>, val lone: Boolean)
