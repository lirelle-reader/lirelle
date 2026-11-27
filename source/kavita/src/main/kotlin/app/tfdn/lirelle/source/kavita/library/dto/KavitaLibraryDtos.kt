package app.tfdn.lirelle.source.kavita.library.dto

import kotlinx.serialization.Serializable

// What the library reads of Kavita's answers. Every field has a default, so that one a version leaves out never
// fails a whole list; the fields the app does not read are ignored, the server's paths among them.

/** A library of the server, as `GET api/Library/libraries` lists it. */
@Serializable
internal data class LibraryDto(val id: Int, val name: String = "")

/**
 * A series, as a list of series and `GET api/Series/{id}` give it.
 *
 * @property format what its files are: 0 images, 1 an archive, 2 unknown, 3 EPUB, 4 PDF.
 * @property libraryId the library it is in.
 * @property lastChapterAddedUtc when the series was last added to.
 */
@Serializable
internal data class SeriesDto(
    val id: Int,
    val name: String = "",
    val sortName: String? = null,
    val format: Int = 2,
    val libraryId: Int = 0,
    val libraryName: String? = null,
    val lastChapterAddedUtc: String? = null,
)

/** A person named on a chapter or a series: a writer, a penciller, a publisher. */
@Serializable
internal data class PersonRefDto(val id: Int, val name: String = "")

/** A genre, on a chapter, a series, or in the server's list of them. */
@Serializable
internal data class GenreDto(val id: Int, val title: String = "")

/**
 * A file of a chapter: its id, its size, its format (0 images, 1 an archive, 2 unknown, 3 EPUB, 4 PDF) and its
 * extension. The server's path is never read.
 */
@Serializable
internal data class FileDto(val id: Int = 0, val format: Int = 2, val bytes: Long = 0, val extension: String? = null)

/**
 * A chapter, as `GET api/Chapter`, a series detail, a volume and the search give it. Its number is `-100000` for
 * the default chapter, the one of a volume that is a single file.
 */
@Serializable
internal data class ChapterDto(
    val id: Int,
    val number: String? = null,
    val range: String? = null,
    val sortOrder: Double = 0.0,
    val isSpecial: Boolean = false,
    val title: String? = null,
    val titleName: String? = null,
    val volumeId: Int = 0,
    val pages: Int = 0,
    val pagesRead: Int = 0,
    val lastReadingProgressUtc: String? = null,
    val createdUtc: String? = null,
    val releaseDate: String? = null,
    val summary: String? = null,
    val language: String? = null,
    val isbn: String? = null,
    val format: Int? = null,
    val writers: List<PersonRefDto> = emptyList(),
    val pencillers: List<PersonRefDto> = emptyList(),
    val publishers: List<PersonRefDto> = emptyList(),
    val genres: List<GenreDto> = emptyList(),
    val files: List<FileDto> = emptyList(),
)

/**
 * A volume and its chapters, as `GET api/Volume` and a series detail give it. Its [number] is cut to a whole
 * number: [minNumber] and [maxNumber] keep a decimal ("10.5") and a range ("1-3").
 */
@Serializable
internal data class VolumeDto(
    val id: Int,
    val number: Int = 0,
    val minNumber: Float = 0f,
    val maxNumber: Float = 0f,
    val seriesId: Int = 0,
    val chapters: List<ChapterDto> = emptyList(),
)

/**
 * What a series holds, as `GET api/Series/series-detail` gives it: its volumes with their chapters, the chapters
 * outside a volume ([storylineChapters]) and its specials. [chapters] gives every chapter again, those of the
 * volumes too, and is not read for the books.
 */
@Serializable
internal data class SeriesDetailDto(
    val volumes: List<VolumeDto> = emptyList(),
    val chapters: List<ChapterDto> = emptyList(),
    val storylineChapters: List<ChapterDto> = emptyList(),
    val specials: List<ChapterDto> = emptyList(),
)

/** What `GET api/Series/metadata` says of a series, which stands for what its chapters do not say. */
@Serializable
internal data class SeriesMetadataDto(
    val summary: String? = null,
    val genres: List<GenreDto> = emptyList(),
    val writers: List<PersonRefDto> = emptyList(),
    val pencillers: List<PersonRefDto> = emptyList(),
    val publishers: List<PersonRefDto> = emptyList(),
    val language: String? = null,
)

/** A person as `GET api/Person` and the search give it. */
@Serializable
internal data class PersonDto(val id: Int, val name: String = "", val description: String? = null)

/** A person as `POST api/Person/all` lists it, with the number of chapters they are named on. */
@Serializable
internal data class BrowsePersonDto(val id: Int, val name: String = "", val chapterCount: Int = 0)
