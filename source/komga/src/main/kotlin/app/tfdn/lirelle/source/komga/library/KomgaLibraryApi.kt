package app.tfdn.lirelle.source.komga.library

import app.tfdn.lirelle.source.komga.network.dto.BookDto
import app.tfdn.lirelle.source.komga.network.dto.PageDto
import app.tfdn.lirelle.source.komga.network.dto.ReadListDto
import app.tfdn.lirelle.source.komga.network.dto.SeriesDto
import kotlinx.serialization.json.JsonObject
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * What a Komga profile asks of its server about its library, with its key (the authenticated client's). Lists are
 * Spring pages: `page` from 0, `size`, and `sort` as `property,asc|desc`, one per key. The list routes take a search
 * ([bookSearch], [readListBookSearch], [seriesSearch]) in their body.
 */
internal interface KomgaLibraryApi {

    @POST("api/v1/books/list")
    suspend fun books(
        @Body search: JsonObject,
        @Query("page") page: Int,
        @Query("size") size: Int,
        @Query("sort") sort: List<String>,
    ): PageDto<BookDto>

    @POST("api/v1/series/list")
    suspend fun series(
        @Body search: JsonObject,
        @Query("page") page: Int,
        @Query("size") size: Int,
        @Query("sort") sort: List<String>,
    ): PageDto<SeriesDto>

    @GET("api/v1/books/{bookId}")
    suspend fun book(@Path("bookId") bookId: String): BookDto

    @GET("api/v1/series/{seriesId}")
    suspend fun oneSeries(@Path("seriesId") seriesId: String): SeriesDto

    @GET("api/v1/readlists")
    suspend fun readLists(@Query("page") page: Int, @Query("size") size: Int): PageDto<ReadListDto>

    /** The next book of each series the account reads ("on deck"), in Komga's order. */
    @GET("api/v1/books/ondeck")
    suspend fun onDeck(@Query("page") page: Int, @Query("size") size: Int): PageDto<BookDto>

    /** The books last added to the libraries, the latest first. */
    @GET("api/v1/books/latest")
    suspend fun latest(
        @Query("page") page: Int,
        @Query("size") size: Int,
        @Query("deleted") deleted: Boolean = false,
    ): PageDto<BookDto>

    /** The genres' names, every one at once: Komga does not count their books. */
    @GET("api/v2/genres")
    suspend fun genres(@Query("unpaged") unpaged: Boolean = true): PageDto<String>

    /**
     * The genres' names as a plain list, the route of the Komgas before 1.26 (which have no [genres]); deprecated,
     * kept until Komga 2.0.
     */
    @GET("api/v1/genres")
    suspend fun genresV1(): List<String>
}
