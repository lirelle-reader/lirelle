package app.tfdn.lirelle.source.bookorbit.library

import app.tfdn.lirelle.source.bookorbit.library.dto.NextVolumeAnswerDto
import app.tfdn.lirelle.source.bookorbit.library.dto.PositionWriteResultsDto
import app.tfdn.lirelle.source.bookorbit.library.dto.PositionWritesDto
import app.tfdn.lirelle.source.bookorbit.library.dto.PositionsDto
import app.tfdn.lirelle.source.bookorbit.library.dto.ReadingAttemptsDto
import app.tfdn.lirelle.source.bookorbit.library.dto.ReadingSessionWriteDto
import kotlinx.serialization.json.JsonObject
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

/** The reader's routes, relative to `<server>/api/v1/`. None is restricted to the official apps. */
internal interface ReaderApi {
    @GET("reading-progress")
    suspend fun positions(@Query("fileId") fileId: Long): PositionsDto

    @PUT("reading-progress")
    suspend fun savePositions(@Body writes: PositionWritesDto): PositionWriteResultsDto

    /** Answers 204. */
    @POST("books/files/{fileId}/sessions")
    suspend fun saveSession(@Path("fileId") fileId: Long, @Body session: ReadingSessionWriteDto)

    /** Newest first. */
    @GET("books/{bookId}/reading-attempts")
    suspend fun readingAttempts(@Path("bookId") bookId: Long): ReadingAttemptsDto

    /** [rating] is `{"rating": 1..5}` or `{"rating": null}`; a JSON object so that the null is written. */
    @PATCH("reading-history/{attemptId}/rating")
    suspend fun rate(@Path("attemptId") attemptId: Long, @Body rating: JsonObject)

    /** [formatGroup] is `epub` or `cbx` here; the server also knows `pdf` and `audio`. */
    @GET("series/{seriesId}/books/{bookId}/next")
    suspend fun nextVolume(
        @Path("seriesId") seriesId: Long,
        @Path("bookId") bookId: Long,
        @Query("formatGroup") formatGroup: String,
    ): NextVolumeAnswerDto

    /** Streamed: a book can be hundreds of megabytes and must never be held in memory. */
    @Streaming
    @GET("books/files/{fileId}/serve")
    suspend fun file(@Path("fileId") fileId: Long): ResponseBody

    /** A read-along EPUB without its audio; same chapters and paths as the original. Slow the first time. */
    @Streaming
    @GET("books/files/{fileId}/audioless-epub/download")
    suspend fun audiolessFile(@Path("fileId") fileId: Long): ResponseBody
}
