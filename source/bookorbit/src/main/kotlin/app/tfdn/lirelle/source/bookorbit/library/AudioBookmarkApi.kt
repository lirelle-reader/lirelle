package app.tfdn.lirelle.source.bookorbit.library

import app.tfdn.lirelle.source.bookorbit.library.dto.AudioBookmarkDto
import app.tfdn.lirelle.source.bookorbit.library.dto.AudioBookmarkWriteDto
import kotlinx.serialization.json.JsonObject
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path

/** The audiobook bookmark routes, relative to `<server>/api/v1/`. None is restricted to the official apps. */
internal interface AudioBookmarkApi {
    /** A bare array, in position order. */
    @GET("audiobooks/{bookId}/bookmarks")
    suspend fun list(@Path("bookId") bookId: Long): List<AudioBookmarkDto>

    /** A [Response], because 409 (another bookmark at that second) is an answer, not a failure. */
    @POST("audiobooks/{bookId}/bookmarks")
    suspend fun create(@Path("bookId") bookId: Long, @Body bookmark: AudioBookmarkWriteDto): Response<AudioBookmarkDto>

    /** [patch] is a JSON object so that `"note": null` can be written. */
    @PATCH("audiobooks/{bookId}/bookmarks/{id}")
    suspend fun edit(@Path("bookId") bookId: Long, @Path("id") id: String, @Body patch: JsonObject): AudioBookmarkDto

    /** Answers 204. The id stays taken on the server. */
    @DELETE("audiobooks/{bookId}/bookmarks/{id}")
    suspend fun delete(@Path("bookId") bookId: Long, @Path("id") id: String)
}
