package app.tfdn.lirelle.source.kavita.history

import app.tfdn.lirelle.core.history.HistoryRepository
import app.tfdn.lirelle.core.history.model.HistoryCapabilities
import app.tfdn.lirelle.core.history.model.HistoryPeriod
import app.tfdn.lirelle.core.history.model.RatingResult
import app.tfdn.lirelle.core.history.model.ReadMarks
import app.tfdn.lirelle.core.history.model.ReadOutcome
import app.tfdn.lirelle.core.history.model.ReadPage
import app.tfdn.lirelle.core.history.model.ReadingSummary
import app.tfdn.lirelle.core.history.model.ReadingTotals
import app.tfdn.lirelle.core.ids.ReadId
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import java.time.Duration

/**
 * A Kavita profile's reading history until its books are read (#51): an empty list, which no screen leads to yet,
 * with no summary, outcome, rating, marks, durations, sessions nor rereads. The server is asked nothing.
 */
internal class EmptyKavitaHistory : HistoryRepository {

    override val capabilities = HistoryCapabilities(
        summary = false,
        outcomes = false,
        rating = false,
        marks = false,
        durations = false,
        sessions = false,
        rereads = false,
    )

    override suspend fun log(
        period: HistoryPeriod,
        outcomes: Set<ReadOutcome>,
        newestFirst: Boolean,
        offset: Int,
        limit: Int,
    ): ApiResult<ReadPage> = ApiResult.Success(ReadPage(emptyList(), months = null, total = 0, offset, limit))

    override suspend fun summary(period: HistoryPeriod): ApiResult<ReadingSummary> = ApiResult.Success(
        ReadingSummary(
            years = emptyList(),
            totals = ReadingTotals(0, 0, 0, 0, Duration.ZERO, averageRating = null),
            goal = null,
            records = emptyList(),
        ),
    )

    override suspend fun marks(readId: ReadId): ApiResult<ReadMarks> = ApiResult.Failure(ApiError.NotFound)

    override suspend fun setRating(readId: ReadId, stars: Int?): ApiResult<RatingResult> =
        ApiResult.Failure(ApiError.NotFound)
}
