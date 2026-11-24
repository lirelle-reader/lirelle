package app.tfdn.lirelle.source.komga.history

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
import app.tfdn.lirelle.core.library.model.BookSummary
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.session.profile.ProfileSession
import app.tfdn.lirelle.source.komga.library.KomgaLibraryApi
import app.tfdn.lirelle.source.komga.library.KomgaMapper
import app.tfdn.lirelle.source.komga.library.historyOrder
import app.tfdn.lirelle.source.komga.library.historySearch
import app.tfdn.lirelle.source.komga.network.KomgaApis
import app.tfdn.lirelle.source.komga.network.KomgaCall
import app.tfdn.lirelle.source.komga.network.KomgaErrors
import java.time.Duration
import java.time.ZoneId

/**
 * A Komga profile's reading history: the books the account read to the end (`READ`), the most recently read first,
 * each once with the day it was finished (its read date, on the device's calendar [zone]). Komga keeps one reading
 * per book, so there is no summary, outcome, rating, marks, time, session nor reread (spec 2c): every read is
 * finished, and the history cannot be narrowed to a year. The entries listed go to [cache] for their detail.
 */
internal class KomgaHistory(
    private val session: ProfileSession,
    private val apis: KomgaApis<KomgaLibraryApi>,
    private val errors: KomgaErrors,
    private val cache: ReadRecordCache,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) : HistoryRepository {

    override val capabilities = HistoryCapabilities(
        summary = false,
        outcomes = false,
        rating = false,
        marks = false,
        durations = false,
        sessions = false,
        rereads = false,
    )

    /**
     * A page of the books read, from [offset]: Komga's page holding it, without the books before [offset] (already
     * shown) when it falls inside one, so the next offset is a page's start again. No month totals: the screen
     * counts the reads of each month from its rows.
     */
    override suspend fun log(
        period: HistoryPeriod,
        outcomes: Set<ReadOutcome>,
        newestFirst: Boolean,
        offset: Int,
        limit: Int,
    ): ApiResult<ReadPage> {
        // Only finished reads, and no year to ask (none is offered without a summary): nothing to ask otherwise.
        if (ReadOutcome.Completed !in outcomes || period.year != null || limit <= 0) {
            return ApiResult.Success(ReadPage(emptyList(), months = null, total = 0, offset, limit))
        }
        return errors.call(KomgaCall.Content) {
            val serverUrl = session.session.value.serverUrl
            val mapper = KomgaMapper(serverUrl)
            val dto = apis.forServer(serverUrl)
                .books(historySearch(), page = offset / limit, size = limit, sort = historyOrder(newestFirst))
            // The server already left the trash out; every book it sent is kept, so the next offset follows the page.
            val records = dto.content.drop(offset % limit).map { record(mapper.summary(it)) }
            cache.put(records)
            ReadPage(records, months = null, total = dto.totalElements, offset = offset, limit = limit)
        }
    }

    private fun record(book: BookSummary) = ReadRecord(
        id = ReadId(book.id.value),
        bookId = book.id,
        title = book.title,
        authors = book.authors,
        series = book.series?.let { SeriesRef(it.name, it.index) },
        // Komga has one picture of a book, its thumbnail, for the row and the detail alike.
        coverUrl = book.coverUrl,
        detailCoverUrl = book.coverUrl,
        startedOn = null,
        endedOn = book.lastActivityAt?.atZone(zone())?.toLocalDate(),
        outcome = ReadOutcome.Completed,
        readNumber = null,
        progressPercent = null,
        sessions = null,
        duration = null,
        rating = null,
        highlightCount = 0,
        journalCount = 0,
        removed = false,
    )

    /** Komga has no summary: never asked (the capability is off), an empty one all the same. */
    override suspend fun summary(period: HistoryPeriod): ApiResult<ReadingSummary> = ApiResult.Success(
        ReadingSummary(
            years = emptyList(),
            totals = ReadingTotals(0, 0, 0, 0, Duration.ZERO, averageRating = null),
            goal = null,
            records = emptyList(),
        ),
    )

    /** A read on Komga has no marks of its own. */
    override suspend fun marks(readId: ReadId): ApiResult<ReadMarks> = ApiResult.Failure(ApiError.NotFound)

    /** A read on Komga cannot be rated. */
    override suspend fun setRating(readId: ReadId, stars: Int?): ApiResult<RatingResult> =
        ApiResult.Failure(ApiError.NotFound)
}
