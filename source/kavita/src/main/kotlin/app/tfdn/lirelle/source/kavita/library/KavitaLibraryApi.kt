package app.tfdn.lirelle.source.kavita.library

import app.tfdn.lirelle.source.kavita.library.dto.ChapterDto
import app.tfdn.lirelle.source.kavita.library.dto.HistoryItemDto
import app.tfdn.lirelle.source.kavita.library.dto.LibraryDto
import app.tfdn.lirelle.source.kavita.library.dto.ReadingListDto
import app.tfdn.lirelle.source.kavita.library.dto.ReadingListItemDto
import app.tfdn.lirelle.source.kavita.library.dto.ReadingProfileDto
import app.tfdn.lirelle.source.kavita.library.dto.SeriesDetailDto
import app.tfdn.lirelle.source.kavita.library.dto.SeriesDto
import app.tfdn.lirelle.source.kavita.library.dto.SeriesMetadataDto
import app.tfdn.lirelle.source.kavita.library.dto.VolumeDto
import app.tfdn.lirelle.source.kavita.network.KavitaJson
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Kavita's routes the library reads, relative to the server's address. A list is asked by page, the first being 1,
 * and its total comes in the `Pagination` header ([totalItems]): those answer a [Response]. The lists that take a
 * filter are sent one of [seriesFilter] or [personFilter]; the others an empty body, as Kavita's own page does.
 * Home's rows, the search, the genres and the people are in [KavitaBrowseApi], which it extends.
 */
internal interface KavitaLibraryApi : KavitaBrowseApi {

    @GET("api/Library/libraries")
    suspend fun libraries(): List<LibraryDto>

    @POST("api/Series/all-v2")
    suspend fun series(
        @Body filter: JsonObject,
        @Query("PageNumber") page: Int,
        @Query("PageSize") size: Int,
    ): Response<List<SeriesDto>>

    @GET("api/Series/{seriesId}")
    suspend fun oneSeries(@Path("seriesId") seriesId: Int): SeriesDto

    @GET("api/Series/series-detail")
    suspend fun detail(@Query("seriesId") seriesId: Int): SeriesDetailDto

    @GET("api/Series/metadata")
    suspend fun metadata(@Query("seriesId") seriesId: Int): SeriesMetadataDto

    @GET("api/Volume")
    suspend fun volume(@Query("volumeId") volumeId: Int): VolumeDto

    @GET("api/Chapter")
    suspend fun chapter(@Query("chapterId") chapterId: Int): ChapterDto

    @GET("api/Search/series-for-chapter")
    suspend fun seriesOfChapter(@Query("chapterId") chapterId: Int): SeriesDto

    @GET("api/reading-profile/{libraryId}/{seriesId}")
    suspend fun readingProfile(
        @Path("libraryId") libraryId: Int,
        @Path("seriesId") seriesId: Int,
    ): ReadingProfileDto

    /** A series' reading sessions, from Kavita 0.9 (0.8.9 answers 404), told in the calendar of [zone]. */
    @GET("api/Stats/reading-history/series/{seriesId}")
    suspend fun seriesHistory(
        @Path("seriesId") seriesId: Int,
        @Query("tzId") zone: String,
        @Query("PageNumber") page: Int,
        @Query("PageSize") size: Int,
    ): Response<List<HistoryItemDto>>

    @GET("api/Reader/continue-point")
    suspend fun continuePoint(@Query("seriesId") seriesId: Int): ChapterDto

    @POST("api/ReadingList/lists")
    suspend fun readingLists(
        @Query("PageNumber") page: Int,
        @Query("PageSize") size: Int,
        @Query("includePromoted") promoted: Boolean = true,
        @Query("sortByLastModified") byLastModified: Boolean = false,
        @Body body: JsonObject = JsonObject(emptyMap()),
    ): Response<List<ReadingListDto>>

    @GET("api/ReadingList/items")
    suspend fun readingListItems(@Query("readingListId") id: Int): List<ReadingListItemDto>
}

/**
 * How many items the whole list holds, as its `Pagination` header says
 * (`{"currentPage":1,"itemsPerPage":20,"totalItems":453,"totalPages":23}`); null when there is none, or none that
 * can be read.
 */
internal fun Response<*>.totalItems(): Int? {
    val header = headers()[PaginationHeader] ?: return null
    return try {
        (KavitaJson.parseToJsonElement(header) as? JsonObject)?.get("totalItems")?.jsonPrimitive?.intOrNull
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}

private const val PaginationHeader = "Pagination"
