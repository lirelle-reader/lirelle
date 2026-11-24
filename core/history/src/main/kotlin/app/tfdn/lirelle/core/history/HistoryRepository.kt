package app.tfdn.lirelle.core.history

import app.tfdn.lirelle.core.history.model.HistoryCapabilities
import app.tfdn.lirelle.core.history.model.HistoryPeriod
import app.tfdn.lirelle.core.history.model.RatingResult
import app.tfdn.lirelle.core.history.model.ReadMarks
import app.tfdn.lirelle.core.history.model.ReadOutcome
import app.tfdn.lirelle.core.history.model.ReadPage
import app.tfdn.lirelle.core.history.model.ReadingSummary
import app.tfdn.lirelle.core.ids.ReadId
import app.tfdn.lirelle.core.network.ApiResult

/** The reading history of the signed-in account. */
interface HistoryRepository {
    val capabilities: HistoryCapabilities

    suspend fun log(
        period: HistoryPeriod,
        outcomes: Set<ReadOutcome>,
        newestFirst: Boolean,
        offset: Int,
        limit: Int,
    ): ApiResult<ReadPage>

    suspend fun summary(period: HistoryPeriod): ApiResult<ReadingSummary>

    suspend fun marks(readId: ReadId): ApiResult<ReadMarks>

    /** A null [stars] clears the rating. */
    suspend fun setRating(readId: ReadId, stars: Int?): ApiResult<RatingResult>
}
