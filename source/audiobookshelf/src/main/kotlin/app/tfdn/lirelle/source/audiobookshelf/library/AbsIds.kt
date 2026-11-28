package app.tfdn.lirelle.source.audiobookshelf.library

import app.tfdn.lirelle.core.ids.AuthorId
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.LibraryId
import app.tfdn.lirelle.core.ids.SeriesId
import app.tfdn.lirelle.core.ids.ShelfId

// The Audiobookshelf side's id conversions, in one place. Audiobookshelf names its items, series, authors,
// libraries, collections and playlists by UUIDs, and the app's ids for them are those UUIDs as they are; a shelf's
// says which of a collection or a playlist it is, `c:<id>` or `p:<id>`, as the two share the Shelves tab. Everywhere
// else an id is text that means nothing but itself.

/** The id of the book that is the library item of this UUID. */
internal fun String.asBookId(): BookId = BookId(this)

/** The id of the series of this UUID. */
internal fun String.asSeriesId(): SeriesId = SeriesId(this)

/** The id of the author of this UUID. */
internal fun String.asAuthorId(): AuthorId = AuthorId(this)

/** The id of the library of this UUID. */
internal fun String.asLibraryId(): LibraryId = LibraryId(this)

/** The id of the shelf that is the collection of this UUID. */
internal fun String.asCollectionShelfId(): ShelfId = ShelfId(CollectionPrefix + this)

/** The id of the shelf that is the playlist of this UUID. */
internal fun String.asPlaylistShelfId(): ShelfId = ShelfId(PlaylistPrefix + this)

/** The UUID of the library item of this book; null for an id that cannot be one of Audiobookshelf's. */
internal val BookId.absId: String? get() = value.absUuid()

/** The UUID of this series; null for an id that cannot be one of Audiobookshelf's. */
internal val SeriesId.absId: String? get() = value.absUuid()

/** The UUID of this author; null for an id that cannot be one of Audiobookshelf's. */
internal val AuthorId.absId: String? get() = value.absUuid()

/** The UUID of this library; null for an id that cannot be one of Audiobookshelf's. */
internal val LibraryId.absId: String? get() = value.absUuid()

/** What an Audiobookshelf shelf is: one of a library's collections, or one of the account's playlists. */
internal sealed interface AbsShelf {
    val id: String

    data class Collection(override val id: String) : AbsShelf

    data class Playlist(override val id: String) : AbsShelf
}

/** The collection or the playlist of this shelf id; null for an id that is none of Audiobookshelf's shelves. */
internal fun ShelfId.absShelf(): AbsShelf? {
    val collection = value.removePrefix(CollectionPrefix).takeIf { value.startsWith(CollectionPrefix) }
    val playlist = value.removePrefix(PlaylistPrefix).takeIf { value.startsWith(PlaylistPrefix) }
    return collection?.absUuid()?.let(AbsShelf::Collection) ?: playlist?.absUuid()?.let(AbsShelf::Playlist)
}

/** This text as one of Audiobookshelf's ids: not blank, and no path of its own in a route. */
private fun String.absUuid(): String? = takeIf { it.isNotBlank() && '/' !in it }

private const val CollectionPrefix = "c:"
private const val PlaylistPrefix = "p:"
