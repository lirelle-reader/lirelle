package app.tfdn.lirelle.source.bookorbit.library

import app.tfdn.lirelle.source.bookorbit.library.dto.BookmarkDto
import app.tfdn.lirelle.source.bookorbit.library.dto.BookmarkWriteDto
import app.tfdn.lirelle.source.bookorbit.library.dto.HighlightDto
import app.tfdn.lirelle.source.bookorbit.library.dto.HighlightWriteDto
import kotlinx.serialization.json.JsonObject
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/** The bookmark and annotation routes, relative to `<server>/api/v1/`. None is restricted to the official apps. */
internal interface MarksApi {
    /** A bare array, oldest first. */
    @GET("books/{bookId}/bookmarks")
    suspend fun bookmarks(@Path("bookId") bookId: Long, @Query("readerFileId") fileId: Long): List<BookmarkDto>

    /** Answers the bookmark already at the same place, if there is one (even a deleted one, revived). */
    @POST("books/{bookId}/bookmarks")
    suspend fun addBookmark(@Path("bookId") bookId: Long, @Body bookmark: BookmarkWriteDto): BookmarkDto

    /** Answers 204. */
    @DELETE("books/{bookId}/bookmarks/{id}")
    suspend fun deleteBookmark(@Path("bookId") bookId: Long, @Path("id") id: Long)

    /** Without `page`: a bare array of all the live highlights of the book. */
    @GET("books/{bookId}/annotations")
    suspend fun highlights(@Path("bookId") bookId: Long, @Query("readerFileId") fileId: Long): List<HighlightDto>

    @POST("books/{bookId}/annotations")
    suspend fun addHighlight(@Path("bookId") bookId: Long, @Body highlight: HighlightWriteDto): HighlightDto

    /** [patch] is a JSON object so that `"note": null` can be written. */
    @PATCH("books/{bookId}/annotations/{id}")
    suspend fun changeHighlight(
        @Path("bookId") bookId: Long,
        @Path("id") id: Long,
        @Body patch: JsonObject,
    ): HighlightDto

    /** Moves it to the trash. Answers 204. */
    @DELETE("books/{bookId}/annotations/{id}")
    suspend fun deleteHighlight(@Path("bookId") bookId: Long, @Path("id") id: Long)

    /** Answers 200 with a hub item, which is not read. */
    @POST("annotations/{id}/restore")
    suspend fun restoreHighlight(@Path("id") id: Long)
}
