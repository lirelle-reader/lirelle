package app.tfdn.lirelle.source.bookorbit.library

import app.tfdn.lirelle.source.bookorbit.library.dto.EpubInfoDto
import app.tfdn.lirelle.source.bookorbit.library.dto.MediaOverlayMapDto
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/** The read-along routes, relative to `<server>/api/v1/`. None is restricted to the official apps. */
internal interface ReadAlongApi {
    /** Large: several megabytes for a long book. */
    @GET("epub/{bookId}/media-overlay")
    suspend fun mediaOverlay(@Path("bookId") bookId: Long, @Query("fileId") fileId: Long): MediaOverlayMapDto

    @GET("epub/{bookId}/info")
    suspend fun info(@Path("bookId") bookId: Long, @Query("fileId") fileId: Long): EpubInfoDto
}
