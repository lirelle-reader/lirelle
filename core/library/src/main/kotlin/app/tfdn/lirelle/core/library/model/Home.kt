package app.tfdn.lirelle.core.library.model

import app.tfdn.lirelle.core.ids.BookId

/**
 * The rows of Home, in display order; the source says which of its requests feed each. The names are those of the
 * "See all" route: they do not change.
 */
enum class HomeRowKind {
    /** The books being read or listened to. */
    Continue,
    WantToRead,

    /** The next volume of each series being read. */
    UpNext,
    RecentlyAdded,
}

/** How much of a Home row is asked for: the row itself, or its "See all" screen. The source sets the numbers. */
enum class HomeRowExtent { Row, SeeAll }

/**
 * @property hasMore whether the server has more books for this row than [books].
 * @property playback for the "Continue" row, the playback state of each book with audio whose state could
 *   be had while the row was dated (null inside: never listened to), so that it is not asked again.
 */
data class HomeRow(
    val kind: HomeRowKind,
    val books: List<BookSummary>,
    val hasMore: Boolean,
    val playback: Map<BookId, PlaybackState?> = emptyMap(),
)
