package app.tfdn.lirelle.source.bookorbit.library

import app.tfdn.lirelle.source.bookorbit.library.dto.AuthorSummaryDto
import app.tfdn.lirelle.source.bookorbit.library.dto.AuthorsPageDto
import app.tfdn.lirelle.source.bookorbit.library.dto.BookDetailDto
import app.tfdn.lirelle.source.bookorbit.library.dto.BooksPageDto
import app.tfdn.lirelle.source.bookorbit.library.dto.CollectionDto
import app.tfdn.lirelle.source.bookorbit.library.dto.GenreDistributionDto
import app.tfdn.lirelle.source.bookorbit.library.dto.ReadingProgressListDto
import app.tfdn.lirelle.source.bookorbit.library.dto.RelatedBookDto
import app.tfdn.lirelle.source.bookorbit.library.dto.ScrollerDto
import app.tfdn.lirelle.source.bookorbit.library.dto.SearchDto
import app.tfdn.lirelle.source.bookorbit.library.dto.SeriesBooksPageDto
import app.tfdn.lirelle.source.bookorbit.library.dto.SeriesPageDto
import app.tfdn.lirelle.source.bookorbit.library.dto.SessionsDto
import app.tfdn.lirelle.source.bookorbit.library.dto.SmartScopeDto
import kotlinx.serialization.json.JsonObject
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Library routes, relative to `<server>/api/v1/`. None is restricted to the official apps.
 * Book queries are zero-based pages; `books/{id}/sessions` is one-based.
 */
@Suppress("TooManyFunctions") // One function per server route.
internal interface LibraryApi {
    @POST("books/query")
    suspend fun books(@Body query: JsonObject): BooksPageDto

    @POST("collections/{id}/books/query")
    suspend fun collectionBooks(@Path("id") id: Long, @Body query: JsonObject): BooksPageDto

    @POST("smart-scopes/{id}/books/query")
    suspend fun smartListBooks(@Path("id") id: Long, @Body query: JsonObject): BooksPageDto

    @GET("series")
    suspend fun series(
        @Query("page") page: Int,
        @Query("size") size: Int,
        @Query("q") text: String? = null,
    ): SeriesPageDto

    @GET("series/{id}/books?sort=seriesIndex&order=asc")
    suspend fun seriesBooks(
        @Path("id") id: Long,
        @Query("page") page: Int,
        @Query("size") size: Int,
    ): SeriesBooksPageDto

    @GET("authors")
    suspend fun authors(
        @Query("page") page: Int,
        @Query("size") size: Int,
        @Query("sort") sort: String,
        @Query("order") order: String,
        @Query("q") text: String? = null,
    ): AuthorsPageDto

    @GET("authors/{id}")
    suspend fun author(@Path("id") id: Long): AuthorSummaryDto

    /** [collapseLinked] exists since 3.10.0 and an older server refuses it: null sends nothing. */
    @GET("authors/{id}/books?sort=publishedYear&order=desc")
    suspend fun authorBooks(
        @Path("id") id: Long,
        @Query("page") page: Int,
        @Query("size") size: Int,
        @Query("collapseLinked") collapseLinked: Boolean? = null,
    ): BooksPageDto

    @GET("collections")
    suspend fun collections(): List<CollectionDto>

    @GET("smart-scopes")
    suspend fun smartLists(): List<SmartScopeDto>

    @GET("books/{id}")
    suspend fun book(@Path("id") id: Long): BookDetailDto

    @GET("reading-progress")
    suspend fun readingProgress(@Query("bookId") bookId: Long): ReadingProgressListDto

    @GET("books/{id}/sessions?page=1&pageSize=1")
    suspend fun sessions(@Path("id") id: Long): SessionsDto

    @GET("books/{id}/series-books")
    suspend fun seriesBooksOf(@Path("id") id: Long): List<RelatedBookDto>

    @GET("books/{id}/author-books")
    suspend fun authorBooksOf(@Path("id") id: Long): List<RelatedBookDto>

    @GET("books/{id}/recommendations")
    suspend fun recommendations(@Path("id") id: Long): List<RelatedBookDto>

    @GET("dashboard/scrollers/{type}")
    suspend fun scroller(@Path("type") type: String, @Query("limit") limit: Int): ScrollerDto

    @GET("search")
    suspend fun search(@Query("q") text: String, @Query("limit") limit: Int): SearchDto

    @GET("statistics/genre-distribution")
    suspend fun genres(): GenreDistributionDto
}
