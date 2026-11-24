package app.tfdn.lirelle.source.bookorbit.library

import app.tfdn.lirelle.source.bookorbit.library.dto.AudioManifestDto
import app.tfdn.lirelle.source.bookorbit.library.dto.ListeningSessionWriteDto
import app.tfdn.lirelle.source.bookorbit.library.dto.NextVolumeAnswerDto
import app.tfdn.lirelle.source.bookorbit.library.dto.PlaybackStateDto
import app.tfdn.lirelle.source.bookorbit.library.dto.PlaybackWriteDto
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path

/** The audiobook routes, relative to `<server>/api/v1/`. None is restricted to the official apps. */
internal interface AudioApi {
    @GET("audiobooks/{bookId}/manifest")
    suspend fun manifest(@Path("bookId") bookId: Long): AudioManifestDto

    /** Answers 200 with an empty body when there is no state yet, so the body is read as text. */
    @GET("audiobooks/{bookId}/playback-state")
    suspend fun playbackState(@Path("bookId") bookId: Long): ResponseBody

    /** A [Response], because 409 (revision conflict) and 412 (manifest changed) are answers, not failures. */
    @PUT("audiobooks/{bookId}/playback-state")
    suspend fun savePlaybackState(
        @Path("bookId") bookId: Long,
        @Body write: PlaybackWriteDto,
    ): Response<PlaybackStateDto>

    /** Answers 204. */
    @POST("books/files/{fileId}/sessions")
    suspend fun saveSession(@Path("fileId") fileId: Long, @Body session: ListeningSessionWriteDto)

    @GET("series/{seriesId}/books/{bookId}/next?formatGroup=audio")
    suspend fun nextVolume(@Path("seriesId") seriesId: Long, @Path("bookId") bookId: Long): NextVolumeAnswerDto
}
