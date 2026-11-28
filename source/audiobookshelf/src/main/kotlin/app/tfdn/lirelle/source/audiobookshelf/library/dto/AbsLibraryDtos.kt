package app.tfdn.lirelle.source.audiobookshelf.library.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonTransformingSerializer

// What Audiobookshelf answers about its libraries and their items, of which the app reads only these fields. Every
// field of an item is optional: a list's item, a whole item and the items of a collection, a playlist or a search
// each carry a different part of it, and 2.26 leaves out of a whole item what 2.38 adds. The server's paths are
// never read.

/** The libraries the account can open (`GET api/libraries`). */
@Serializable
internal data class AbsLibrariesDto(val libraries: List<AbsLibraryDto> = emptyList())

/**
 * A library of the server.
 *
 * @property displayOrder its place in the server's own list of libraries.
 * @property mediaType `book` or `podcast`.
 */
@Serializable
internal data class AbsLibraryDto(
    val id: String,
    val name: String = "",
    val displayOrder: Int = 0,
    val mediaType: String? = null,
    val settings: AbsLibrarySettingsDto? = null,
)

/** How a library is shown. @property coverAspectRatio 1 for square covers, 0 for a book's (the default). */
@Serializable
internal data class AbsLibrarySettingsDto(val coverAspectRatio: Int? = null)

/** A page of a library's items (`GET api/libraries/:id/items`), from page 0; [total] counts the whole list. */
@Serializable
internal data class AbsItemsPageDto(val results: List<AbsItemDto> = emptyList(), val total: Int = 0)

/**
 * A library item: a book in a book library, a podcast in a podcast library ([mediaType]).
 *
 * @property addedAt when it was added, in milliseconds since the epoch.
 * @property updatedAt when it last changed, in milliseconds since the epoch: a new cover changes it.
 * @property progressLastUpdate when the account's progress in it last moved, in milliseconds since the epoch: on an
 *   item the account has started (`GET api/me/items-in-progress`) alone.
 */
@Serializable
internal data class AbsItemDto(
    val id: String,
    val libraryId: String? = null,
    val mediaType: String? = null,
    val addedAt: Long? = null,
    val updatedAt: Long? = null,
    val media: AbsMediaDto = AbsMediaDto(),
    val progressLastUpdate: Long? = null,
)

/**
 * What an item holds. A list's item counts its tracks and names its ebook's format ([numTracks], [ebookFormat], the
 * key absent for an item without an ebook); a whole item on 2.26 does neither, and lists its [tracks], its
 * [audioFiles] and its [ebookFile] instead; 2.38 gives both.
 *
 * @property coverPath where the server keeps the cover: only whether there is one is read, never the path itself.
 * @property duration the audio's length, in seconds.
 */
@Serializable
internal data class AbsMediaDto(
    val metadata: AbsMetadataDto = AbsMetadataDto(),
    val coverPath: String? = null,
    val duration: Double? = null,
    val numTracks: Int? = null,
    val numAudioFiles: Int? = null,
    val ebookFormat: String? = null,
    val ebookFile: AbsEbookFileDto? = null,
    val tracks: List<AbsAudioDto> = emptyList(),
    val audioFiles: List<AbsAudioDto> = emptyList(),
)

/**
 * What is known of a book. A list's item names its people and its series in one text each ([authorName],
 * [narratorName], [seriesName] "Name #seq", several joined by ", "); a whole item also links them ([authors],
 * [series], [narrators]). An item of a series' list names that series alone, as an object: it is read as a list of
 * one.
 *
 * @property publishedYear a year or a date as the server keeps it: "1979", "1992-01-01".
 * @property description in HTML; [descriptionPlain] the same without its tags, on a whole item.
 */
@Serializable
internal data class AbsMetadataDto(
    val title: String? = null,
    val subtitle: String? = null,
    val authorName: String? = null,
    val authorNameLF: String? = null,
    val narratorName: String? = null,
    val seriesName: String? = null,
    val genres: List<String> = emptyList(),
    val publishedYear: String? = null,
    val description: String? = null,
    val descriptionPlain: String? = null,
    val publisher: String? = null,
    val language: String? = null,
    val isbn: String? = null,
    val authors: List<AbsNamedDto> = emptyList(),
    @Serializable(with = AbsSeriesRefsSerializer::class)
    val series: List<AbsSeriesRefDto> = emptyList(),
    val narrators: List<String> = emptyList(),
)

/** Something the server names and links: an author of a book. */
@Serializable
internal data class AbsNamedDto(val id: String, val name: String = "")

/** A series a book is in, and its place there as the server writes it ("1", "0.5"), when it has one. */
@Serializable
internal data class AbsSeriesRefDto(val id: String, val name: String = "", val sequence: String? = null)

/** An item's ebook file, of which its format is read: `epub`, `pdf`, `cbz`, `cbr`, `mobi`, `azw3`… */
@Serializable
internal data class AbsEbookFileDto(val ebookFormat: String? = null)

/** One of an item's audio files or tracks: that there is one is what is read for now. */
@Serializable
internal data class AbsAudioDto(val index: Int? = null, val duration: Double? = null)

/** A book's series, read as a list whether the server sends a list, one object, or nothing. */
internal object AbsSeriesRefsSerializer :
    JsonTransformingSerializer<List<AbsSeriesRefDto>>(ListSerializer(AbsSeriesRefDto.serializer())) {

    override fun transformDeserialize(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonArray(listOf(element))
        is JsonNull -> JsonArray(emptyList())
        else -> element
    }
}
