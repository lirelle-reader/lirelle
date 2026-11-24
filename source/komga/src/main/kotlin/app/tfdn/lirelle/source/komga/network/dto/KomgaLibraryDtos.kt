package app.tfdn.lirelle.source.komga.network.dto

import kotlinx.serialization.Serializable

// What the app reads of Komga's books, series and read lists (Komga 1.30.1, spec 3a2 "Why"). Every field has a
// default: an answer that leaves one out is read all the same, and the fields Komga adds later are ignored.

/** A Spring page: the items of one page, and how many there are in all. */
@Serializable
internal data class PageDto<T>(val content: List<T> = emptyList(), val totalElements: Int = 0)

/** A book (`BookDto`). Dates are UTC instants; `deleted` is a book in the trash, its file gone. */
@Serializable
internal data class BookDto(
    val id: String,
    val seriesId: String? = null,
    val seriesTitle: String? = null,
    /** The file's name, which stands for the title when the metadata has none. */
    val name: String = "",
    val created: String? = null,
    val lastModified: String? = null,
    val oneshot: Boolean = false,
    val deleted: Boolean = false,
    /** The size of the book's file; 0 when Komga does not know it. */
    val sizeBytes: Long = 0,
    /** When the book's file last changed on the server's disk: the version of its pages. */
    val fileLastModified: String? = null,
    val media: MediaDto = MediaDto(),
    val metadata: BookMetadataDto = BookMetadataDto(),
    /** Null for a book never opened. */
    val readProgress: ReadProgressDto? = null,
)

/**
 * What Komga made of the book's file: its number of pages, its type (`application/zip` for a CBZ) and its profile,
 * `DIVINA` for an album of pictures (a comic), `PDF` or `EPUB` otherwise, empty until Komga has analysed it. Its
 * status is `READY` once analysed and served (`UNKNOWN`, `ERROR`, `UNSUPPORTED`, `OUTDATED` otherwise).
 */
@Serializable
internal data class MediaDto(
    val pagesCount: Int = 0,
    val mediaType: String = "",
    val mediaProfile: String = "",
    val status: String = "",
)

/** `number` is the volume number as written ("3", "2.5"). `releaseDate` is a date (`2019-04-12`). */
@Serializable
internal data class BookMetadataDto(
    val title: String? = null,
    val number: String? = null,
    val summary: String? = null,
    val releaseDate: String? = null,
    val authors: List<KomgaAuthorDto> = emptyList(),
    val isbn: String? = null,
)

/** A name with its part in the book (`writer`, `penciller`…): one person may have several. */
@Serializable
internal data class KomgaAuthorDto(val name: String = "", val role: String? = null)

/** The account's reading of a book: the page reached (from 1), whether it is finished, and when it was read. */
@Serializable
internal data class ReadProgressDto(
    val page: Int = 0,
    val completed: Boolean = false,
    val readDate: String? = null,
    val created: String? = null,
    val lastModified: String? = null,
)

/** A series (`SeriesDto`), with the account's counts of its books. */
@Serializable
internal data class SeriesDto(
    val id: String,
    val name: String = "",
    val booksCount: Int = 0,
    val booksReadCount: Int = 0,
    val created: String? = null,
    val lastModified: String? = null,
    val deleted: Boolean = false,
    val oneshot: Boolean = false,
    val metadata: SeriesMetadataDto = SeriesMetadataDto(),
    val booksMetadata: BooksMetadataDto = BooksMetadataDto(),
)

@Serializable
internal data class SeriesMetadataDto(
    val title: String? = null,
    val genres: List<String> = emptyList(),
    val publisher: String? = null,
    val language: String? = null,
    /** `LEFT_TO_RIGHT`, `RIGHT_TO_LEFT`, `VERTICAL`, `WEBTOON`, or empty when none is set. */
    val readingDirection: String? = null,
)

/** What the series' books say together: their authors. */
@Serializable
internal data class BooksMetadataDto(val authors: List<KomgaAuthorDto> = emptyList())

/** A read list (`ReadListDto`): the ids of its books, in its order. */
@Serializable
internal data class ReadListDto(val id: String, val name: String = "", val bookIds: List<String> = emptyList())
