package app.tfdn.lirelle.source.audiobookshelf.library

import app.tfdn.lirelle.source.audiobookshelf.library.dto.AbsAuthorDetailDto
import app.tfdn.lirelle.source.audiobookshelf.library.dto.AbsAuthorsDto
import app.tfdn.lirelle.source.audiobookshelf.library.dto.AbsCollectionDto
import app.tfdn.lirelle.source.audiobookshelf.library.dto.AbsCollectionsPageDto
import app.tfdn.lirelle.source.audiobookshelf.library.dto.AbsFilterDataAnswerDto
import app.tfdn.lirelle.source.audiobookshelf.library.dto.AbsInProgressDto
import app.tfdn.lirelle.source.audiobookshelf.library.dto.AbsItemDto
import app.tfdn.lirelle.source.audiobookshelf.library.dto.AbsItemsPageDto
import app.tfdn.lirelle.source.audiobookshelf.library.dto.AbsLibrariesDto
import app.tfdn.lirelle.source.audiobookshelf.library.dto.AbsMeDto
import app.tfdn.lirelle.source.audiobookshelf.library.dto.AbsPlaylistDto
import app.tfdn.lirelle.source.audiobookshelf.library.dto.AbsPlaylistsDto
import app.tfdn.lirelle.source.audiobookshelf.library.dto.AbsSearchDto
import app.tfdn.lirelle.source.audiobookshelf.library.dto.AbsSeriesDto
import app.tfdn.lirelle.source.audiobookshelf.library.dto.AbsSeriesPageDto
import app.tfdn.lirelle.source.audiobookshelf.library.dto.AbsShelfDto
import app.tfdn.lirelle.source.audiobookshelf.network.MeRoute
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Audiobookshelf's routes the library reads, relative to the server's address. A list is asked by page, the first
 * being 0, and says its total in its answer; `limit=0` asks for a whole list of items or collections, never of
 * series (the server answers none). Home's shelves, the search and the genres are in [AbsBrowseApi], which it
 * extends.
 */
internal interface AbsLibraryApi : AbsBrowseApi {

    @GET("api/libraries")
    suspend fun libraries(): AbsLibrariesDto

    /** A page of a library's items, by [sort] (`addedAt`, `media.metadata.title`…), [desc] 1 for descending. */
    @GET("api/libraries/{libraryId}/items")
    suspend fun items(
        @Path("libraryId") libraryId: String,
        @Query("limit") limit: Int,
        @Query("page") page: Int,
        @Query("sort") sort: String,
        @Query("desc") desc: Int,
    ): AbsItemsPageDto

    /**
     * A page of a library's items of one group, [filter] `genres.<base64>`, `series.<base64>` or `authors.<base64>`,
     * by [sort] when one is given (`sequence` for a series).
     */
    @GET("api/libraries/{libraryId}/items")
    suspend fun groupItems(
        @Path("libraryId") libraryId: String,
        @Query("filter") filter: String,
        @Query("limit") limit: Int,
        @Query("page") page: Int,
        @Query("sort") sort: String? = null,
    ): AbsItemsPageDto

    /** A whole item, its authors and series linked. 403 for an item the account may not see. */
    @GET("api/items/{itemId}")
    suspend fun item(@Path("itemId") itemId: String, @Query("expanded") expanded: Int = 1): AbsItemDto

    /** The account's progress, all of it, with the rest of the account. */
    @GET(MeRoute)
    suspend fun me(): AbsMeDto

    /** A page of a library's series; [limit] never 0. */
    @GET("api/libraries/{libraryId}/series")
    suspend fun series(
        @Path("libraryId") libraryId: String,
        @Query("limit") limit: Int,
        @Query("page") page: Int,
        @Query("sort") sort: String = "name",
    ): AbsSeriesPageDto

    @GET("api/series/{seriesId}")
    suspend fun oneSeries(@Path("seriesId") seriesId: String): AbsSeriesDto

    @GET("api/libraries/{libraryId}/authors")
    suspend fun authors(@Path("libraryId") libraryId: String): AbsAuthorsDto

    @GET("api/authors/{authorId}")
    suspend fun author(
        @Path("authorId") authorId: String,
        @Query("include") include: String = "items,series",
    ): AbsAuthorDetailDto

    @GET("api/libraries/{libraryId}/collections")
    suspend fun collections(
        @Path("libraryId") libraryId: String,
        @Query("limit") limit: Int = 0,
    ): AbsCollectionsPageDto

    @GET("api/collections/{collectionId}")
    suspend fun collection(@Path("collectionId") collectionId: String): AbsCollectionDto

    @GET("api/playlists")
    suspend fun playlists(): AbsPlaylistsDto

    @GET("api/playlists/{playlistId}")
    suspend fun playlist(@Path("playlistId") playlistId: String): AbsPlaylistDto
}

/** Audiobookshelf's routes for Home's rows, the search and the genres. */
internal interface AbsBrowseApi {

    /** The items the account has started, of every library, the latest first. */
    @GET("api/me/items-in-progress")
    suspend fun itemsInProgress(@Query("limit") limit: Int): AbsInProgressDto

    @GET("api/libraries/{libraryId}/personalized")
    suspend fun personalized(@Path("libraryId") libraryId: String, @Query("limit") limit: Int): List<AbsShelfDto>

    /** A library's search; [query] never empty (the server answers 400). */
    @GET("api/libraries/{libraryId}/search")
    suspend fun search(
        @Path("libraryId") libraryId: String,
        @Query("q") query: String,
        @Query("limit") limit: Int,
    ): AbsSearchDto

    @GET("api/libraries/{libraryId}")
    suspend fun filterData(
        @Path("libraryId") libraryId: String,
        @Query("include") include: String = "filterdata",
    ): AbsFilterDataAnswerDto
}
