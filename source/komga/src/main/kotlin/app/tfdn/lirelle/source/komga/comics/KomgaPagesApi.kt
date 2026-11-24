package app.tfdn.lirelle.source.komga.comics

import kotlinx.serialization.Serializable
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

/** What Komga asks to convert a page to, for a format the device cannot draw. */
internal const val JpegConversion = "jpeg"

/**
 * The pages of a Komga book, relative to the server's address: listed with their formats, and served one at a time.
 * Komga numbers them from 1; it answers 404 for a book whose media is not ready, and refuses (403) the page itself to
 * an account without the page streaming role.
 */
internal interface KomgaPagesApi {
    @GET("api/v1/books/{bookId}/pages")
    suspend fun pages(@Path("bookId") bookId: String): List<BookPageDto>

    /**
     * The picture as Komga has it, or converted to [convert] when given. The call itself is given, not its answer:
     * its bytes come while they are read, and only the call can be cancelled then.
     */
    @Streaming
    @GET("api/v1/books/{bookId}/pages/{pageNumber}")
    fun page(
        @Path("bookId") bookId: String,
        @Path("pageNumber") number: Int,
        @Query("convert") convert: String?,
    ): Call<ResponseBody>
}

/** One page of a book (`PageDto`): its number from 1 and its format; its size and name are not read. */
@Serializable
internal data class BookPageDto(val number: Int = 0, val mediaType: String = "")
