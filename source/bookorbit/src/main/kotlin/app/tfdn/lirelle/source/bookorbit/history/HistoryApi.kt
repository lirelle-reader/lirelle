package app.tfdn.lirelle.source.bookorbit.history

import app.tfdn.lirelle.source.bookorbit.history.dto.MarksDto
import app.tfdn.lirelle.source.bookorbit.history.dto.RatingResultDto
import app.tfdn.lirelle.source.bookorbit.history.dto.ReadingHistoryPageDto
import app.tfdn.lirelle.source.bookorbit.history.dto.ReadingSummaryDto
import kotlinx.serialization.json.JsonObject
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.Path
import retrofit2.http.Query

/** The reading-history routes, relative to `<server>/api/v1/`. None is restricted to the official apps. */
internal interface HistoryApi {
    /** [outcomes] is comma-joined, null for the server default (completed reads); [sort] is `newest` or `oldest`. */
    @GET("reading-history")
    suspend fun log(
        @Query("year") year: Int?,
        @Query("outcomes") outcomes: String?,
        @Query("sort") sort: String,
        @Query("offset") offset: Int,
        @Query("limit") limit: Int,
    ): ReadingHistoryPageDto

    @GET("reading-history/summary")
    suspend fun summary(@Query("year") year: Int?): ReadingSummaryDto

    @GET("reading-history/{attemptId}/marks")
    suspend fun marks(@Path("attemptId") attemptId: Long): MarksDto

    /** [rating] is `{"rating": 1..5}` or `{"rating": null}`; a JSON object so that the null is written. */
    @PATCH("reading-history/{attemptId}/rating")
    suspend fun rate(@Path("attemptId") attemptId: Long, @Body rating: JsonObject): RatingResultDto
}
