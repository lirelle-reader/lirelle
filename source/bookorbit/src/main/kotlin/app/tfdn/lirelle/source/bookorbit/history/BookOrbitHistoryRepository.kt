package app.tfdn.lirelle.source.bookorbit.history

import app.tfdn.lirelle.core.history.HistoryRepository
import app.tfdn.lirelle.core.history.ReadRecordCache
import app.tfdn.lirelle.core.history.model.HistoryCapabilities
import app.tfdn.lirelle.core.history.model.HistoryPeriod
import app.tfdn.lirelle.core.history.model.RatingResult
import app.tfdn.lirelle.core.history.model.ReadMarks
import app.tfdn.lirelle.core.history.model.ReadOutcome
import app.tfdn.lirelle.core.history.model.ReadPage
import app.tfdn.lirelle.core.history.model.ReadingSummary
import app.tfdn.lirelle.core.ids.ReadId
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.source.bookorbit.network.ApiProvider
import app.tfdn.lirelle.source.bookorbit.network.CallKind
import app.tfdn.lirelle.source.bookorbit.network.ErrorMapper
import app.tfdn.lirelle.source.bookorbit.network.bookOrbit
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** The reading history served by a BookOrbit server. */
internal class BookOrbitHistoryRepository(
    private val api: ApiProvider<HistoryApi>,
    private val errors: ErrorMapper,
    private val serverUrl: () -> String?,
    private val cache: ReadRecordCache,
) : HistoryRepository {

    override val capabilities = HistoryCapabilities.All

    /**
     * Runs [block] with the API and a mapper of the profile's server. A profile always has one, closed or not;
     * the type allows none, which fails as an expired session.
     */
    private suspend fun <T> call(block: suspend (HistoryApi, HistoryMapper) -> T): ApiResult<T> {
        val server = serverUrl() ?: return ApiResult.Failure(ApiError.SessionExpired)
        val remote = api.forServer(server)
        val mapper = HistoryMapper(server)
        return errors.call(CallKind.Authenticated) { block(remote, mapper) }
    }

    override suspend fun log(
        period: HistoryPeriod,
        outcomes: Set<ReadOutcome>,
        newestFirst: Boolean,
        offset: Int,
        limit: Int,
    ): ApiResult<ReadPage> = call { remote, mapper ->
        val selected = outcomes.takeUnless { it == setOf(ReadOutcome.Completed) }
            ?.sortedBy { it.ordinal }?.joinToString(",") { it.name.lowercase() }
        val sort = if (newestFirst) "newest" else "oldest"
        mapper.page(remote.log(period.year, selected, sort, offset, limit)).also { cache.put(it.records) }
    }

    override suspend fun summary(period: HistoryPeriod): ApiResult<ReadingSummary> =
        call { remote, mapper -> mapper.summary(remote.summary(period.year)) }

    override suspend fun marks(readId: ReadId): ApiResult<ReadMarks> =
        call { remote, mapper -> mapper.marks(remote.marks(readId.bookOrbit())) }

    override suspend fun setRating(readId: ReadId, stars: Int?): ApiResult<RatingResult> = call { remote, _ ->
        val body = buildJsonObject { put("rating", stars?.let(::JsonPrimitive) ?: JsonNull) }
        remote.rate(readId.bookOrbit(), body).let { RatingResult(it.rating, it.bookRatingChanged) }
    }
}
