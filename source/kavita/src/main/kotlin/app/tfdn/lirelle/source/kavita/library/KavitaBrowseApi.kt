package app.tfdn.lirelle.source.kavita.library

import app.tfdn.lirelle.source.kavita.library.dto.BrowsePersonDto
import app.tfdn.lirelle.source.kavita.library.dto.GenreDto
import app.tfdn.lirelle.source.kavita.library.dto.PersonDto
import app.tfdn.lirelle.source.kavita.library.dto.RecentlyUpdatedDto
import app.tfdn.lirelle.source.kavita.library.dto.SearchResultDto
import app.tfdn.lirelle.source.kavita.library.dto.SeriesDto
import kotlinx.serialization.json.JsonObject
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * Kavita's routes for Home's rows, the search, the genres and the people, which [KavitaLibraryApi] extends: one
 * service for the whole library, its routes in two interfaces.
 */
internal interface KavitaBrowseApi {

    @POST("api/Series/on-deck")
    suspend fun onDeck(
        @Query("PageNumber") page: Int,
        @Query("PageSize") size: Int,
        @Query("libraryId") libraryId: Int = 0,
        @Body body: JsonObject = JsonObject(emptyMap()),
    ): Response<List<SeriesDto>>

    @POST("api/want-to-read/v2")
    suspend fun wantToRead(
        @Body filter: JsonObject,
        @Query("PageNumber") page: Int,
        @Query("PageSize") size: Int,
    ): Response<List<SeriesDto>>

    @POST("api/Series/recently-updated-series")
    suspend fun recentlyUpdated(
        @Query("PageNumber") page: Int,
        @Query("PageSize") size: Int,
        @Body body: JsonObject = JsonObject(emptyMap()),
    ): Response<List<RecentlyUpdatedDto>>

    @GET("api/Search/search")
    suspend fun search(
        @Query("queryString") text: String,
        @Query("includeChapterAndFiles") chapters: Boolean = true,
    ): SearchResultDto

    @GET("api/Metadata/genres")
    suspend fun genres(): List<GenreDto>

    @POST("api/Person/all")
    suspend fun persons(
        @Body filter: JsonObject,
        @Query("PageNumber") page: Int,
        @Query("PageSize") size: Int,
    ): Response<List<BrowsePersonDto>>

    @GET("api/Person")
    suspend fun person(@Query("name") name: String): PersonDto
}
