package app.tfdn.lirelle.source.kavita.reading

import app.tfdn.lirelle.source.kavita.library.dto.LibraryDto
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

/**
 * Kavita's routes of reading, relative to the server's address. Saving a progress answers `Response<Unit>`:
 * Kavita answers 200 with a bare `true` or with nothing, depending on its version, and neither is read.
 */
internal interface KavitaReadingApi {
    @GET("api/Reader/get-progress")
    suspend fun progress(@Query("chapterId") chapterId: Int): ProgressDto

    @POST("api/Reader/progress")
    suspend fun saveProgress(@Body progress: ProgressWriteDto): Response<Unit>

    @GET("api/Reader/chapter-info")
    suspend fun chapterInfo(@Query("chapterId") chapterId: Int): ChapterInfoDto

    /** A page, from 0; the key goes in the address (the authenticated client adds it). */
    @Streaming
    @GET("api/Reader/image")
    fun page(@Query("chapterId") chapterId: Int, @Query("page") page: Int): Call<ResponseBody>

    /** A chapter's PDF file, whole; no right to download needed. */
    @Streaming
    @GET("api/Reader/pdf")
    suspend fun pdf(@Query("chapterId") chapterId: Int): ResponseBody

    /** A chapter's file, or a zip of its files when it has several; needs the right to download. */
    @Streaming
    @GET("api/Download/chapter")
    suspend fun download(@Query("chapterId") chapterId: Int): Response<ResponseBody>

    @GET("api/reading-profile/{libraryId}/{seriesId}")
    suspend fun readingProfile(@Path("libraryId") libraryId: Int, @Path("seriesId") seriesId: Int): ReadingProfileDto

    @GET("api/Library/libraries")
    suspend fun libraries(): List<LibraryDto>

    @GET("api/Stats/reading-history")
    suspend fun history(
        @Query("Libraries") libraries: List<Int>,
        @Query("TimeZoneId") zone: String,
        @Query("PageNumber") page: Int,
        @Query("PageSize") size: Int,
    ): Response<List<HistoryItemDto>>

    /** Kavita 0.9 and later; 0.8.9 answers 404. */
    @GET("api/Stats/reading-history/series/{seriesId}")
    suspend fun seriesHistory(
        @Path("seriesId") seriesId: Int,
        @Query("tzId") zone: String,
        @Query("PageNumber") page: Int,
        @Query("PageSize") size: Int,
    ): Response<List<HistoryItemDto>>
}
