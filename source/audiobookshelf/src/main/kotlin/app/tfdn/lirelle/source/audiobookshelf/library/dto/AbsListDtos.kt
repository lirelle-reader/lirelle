package app.tfdn.lirelle.source.audiobookshelf.library.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// What Audiobookshelf answers about the account's progress, its series, authors, collections and playlists, Home's
// shelves, a search and a library's genres, of which the app reads only these fields.

/** The account's progress, all of it (`GET api/me`): the rest of the account is read elsewhere. */
@Serializable
internal data class AbsMeDto(val mediaProgress: List<AbsMediaProgressDto> = emptyList())

/**
 * The account's progress in one item: a book's, or a podcast episode's ([episodeId] set).
 *
 * @property progress the audio's, 0 to 1.
 * @property ebookProgress the ebook's, 0 to 1.
 * @property lastUpdate when it last moved, [startedAt] when it began, in milliseconds since the epoch.
 */
@Serializable
internal data class AbsMediaProgressDto(
    val libraryItemId: String,
    val episodeId: String? = null,
    val progress: Double? = null,
    val ebookProgress: Double? = null,
    val isFinished: Boolean = false,
    val currentTime: Double? = null,
    val duration: Double? = null,
    val lastUpdate: Long? = null,
    val startedAt: Long? = null,
)

/** The items the account has started, of every library, the latest first (`GET api/me/items-in-progress`). */
@Serializable
internal data class AbsInProgressDto(val libraryItems: List<AbsItemDto> = emptyList())

/**
 * One of a library's Home shelves (`GET api/libraries/:id/personalized`): `continue-listening`, `recently-added`…
 * Its [entities] are of its [type]: `book` (items), `series` or `authors`, read as such.
 */
@Serializable
internal data class AbsShelfDto(
    val id: String,
    val type: String? = null,
    val entities: List<JsonObject> = emptyList(),
)

/** A page of a library's series (`GET api/libraries/:id/series`), from page 0; [total] counts them all. */
@Serializable
internal data class AbsSeriesPageDto(val results: List<AbsSeriesDto> = emptyList(), val total: Int = 0)

/** A series, with its [books] where the answer gives them (a list of series, a search). */
@Serializable
internal data class AbsSeriesDto(
    val id: String,
    val name: String = "",
    val libraryId: String? = null,
    val addedAt: Long? = null,
    val books: List<AbsItemDto> = emptyList(),
)

/** A library's authors (`GET api/libraries/:id/authors`), all of them. */
@Serializable
internal data class AbsAuthorsDto(val authors: List<AbsAuthorDto> = emptyList())

/**
 * An author of a library.
 *
 * @property lastFirst the name to sort by: "Verne, Jules".
 * @property description the author's biography, when the server has one.
 */
@Serializable
internal data class AbsAuthorDto(
    val id: String,
    val name: String = "",
    val lastFirst: String? = null,
    val numBooks: Int? = null,
    val description: String? = null,
    val libraryId: String? = null,
)

/** An author with the author's items and series (`GET api/authors/:id?include=items,series`). */
@Serializable
internal data class AbsAuthorDetailDto(
    val id: String,
    val name: String = "",
    val description: String? = null,
    val libraryId: String? = null,
    val libraryItems: List<AbsItemDto> = emptyList(),
    val series: List<AbsAuthorSeriesDto> = emptyList(),
)

/** A series of an author, with the author's items in it. */
@Serializable
internal data class AbsAuthorSeriesDto(val id: String, val name: String = "", val items: List<AbsItemDto> = emptyList())

/** A page of a library's collections (`GET api/libraries/:id/collections`). */
@Serializable
internal data class AbsCollectionsPageDto(val results: List<AbsCollectionDto> = emptyList(), val total: Int = 0)

/** A collection of one library, with its books in its order. */
@Serializable
internal data class AbsCollectionDto(
    val id: String,
    val name: String = "",
    val libraryId: String? = null,
    val books: List<AbsItemDto> = emptyList(),
)

/** The account's playlists, of every library (`GET api/playlists`). */
@Serializable
internal data class AbsPlaylistsDto(val playlists: List<AbsPlaylistDto> = emptyList())

/** A playlist of the account, with its items in its order. */
@Serializable
internal data class AbsPlaylistDto(
    val id: String,
    val name: String = "",
    val libraryId: String? = null,
    val items: List<AbsPlaylistItemDto> = emptyList(),
)

/** An item of a playlist: a book, or a podcast's episode ([episodeId] set). */
@Serializable
internal data class AbsPlaylistItemDto(
    val libraryItemId: String,
    val episodeId: String? = null,
    val libraryItem: AbsItemDto? = null,
)

/** What a library's search found (`GET api/libraries/:id/search`): books, series and authors. */
@Serializable
internal data class AbsSearchDto(
    val book: List<AbsFoundItemDto> = emptyList(),
    val series: List<AbsFoundSeriesDto> = emptyList(),
    val authors: List<AbsAuthorDto> = emptyList(),
)

/** A book the search found. */
@Serializable
internal data class AbsFoundItemDto(val libraryItem: AbsItemDto)

/** A series the search found, with its books. */
@Serializable
internal data class AbsFoundSeriesDto(val series: AbsSeriesDto, val books: List<AbsItemDto> = emptyList())

/** A library with its filter data (`GET api/libraries/:id?include=filterdata`), of which the genres are read. */
@Serializable
internal data class AbsFilterDataAnswerDto(val filterdata: AbsFilterDataDto = AbsFilterDataDto())

/** The values a library's books can be filtered by. */
@Serializable
internal data class AbsFilterDataDto(val genres: List<String> = emptyList())
