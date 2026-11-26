package app.tfdn.lirelle.feature.player.car

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.SeriesId

/**
 * The media ids of what a car browses: `root`, `tab/<name>`, `series/<id>` and `book/<id>`. They are the app's
 * own text, told apart from the player's items (`resume/<bookId>`, `<bookId>/<chapter>`) by their first word;
 * what follows `series/` or `book/` is the server's id, kept whole and never read.
 */
internal object CarIds {
    private const val Root = "root"
    private const val TabPrefix = "tab/"
    private const val SeriesPrefix = "series/"
    private const val BookPrefix = "book/"

    /** The word of each tab in its id: not the constant's name, which may change. */
    private val CarPlace.Tab.word: String
        get() = when (this) {
            CarPlace.Tab.Home -> "home"
            CarPlace.Tab.Downloaded -> "downloaded"
            CarPlace.Tab.Audiobooks -> "audiobooks"
            CarPlace.Tab.Series -> "series"
        }

    fun of(place: CarPlace): String = when (place) {
        CarPlace.Root -> Root
        is CarPlace.Tab -> TabPrefix + place.word
        is CarPlace.OneSeries -> SeriesPrefix + place.id.value
    }

    /** The id of what [node] shows: a tab's or a series' is the id of the place it opens. */
    fun of(node: CarNode): String = when (node) {
        is CarNode.Tab -> of(node.tab)
        is CarNode.Series -> of(CarPlace.OneSeries(node.id))
        is CarNode.Book -> book(node.id)
    }

    fun book(id: BookId): String = BookPrefix + id.value

    /** The place [mediaId] names; null for a book, and for an id that is not one of these. */
    fun placeOf(mediaId: String): CarPlace? = when {
        mediaId == Root -> CarPlace.Root
        mediaId.startsWith(TabPrefix) -> CarPlace.Tab.entries.firstOrNull { TabPrefix + it.word == mediaId }
        else -> mediaId.after(SeriesPrefix)?.let { CarPlace.OneSeries(SeriesId(it)) }
    }

    /** The book [mediaId] names; null for a place, and for an id that is not one of these. */
    fun bookOf(mediaId: String): BookId? = mediaId.after(BookPrefix)?.let(::BookId)

    /** What follows [prefix]; null without the prefix, or with nothing after it. */
    private fun String.after(prefix: String): String? =
        takeIf { startsWith(prefix) }?.removePrefix(prefix)?.takeIf { it.isNotEmpty() }
}
