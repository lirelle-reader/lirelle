package app.tfdn.lirelle.core.library.model

import app.tfdn.lirelle.core.ids.ShelfId

/** A playlist is a shelf of the account's own (Audiobookshelf), shown in a section of its own. */
enum class ShelfKind { SmartList, Collection, Playlist }

/** [bookCount] is null for a smart list whose rule the server could not evaluate. */
data class Shelf(val kind: ShelfKind, val id: ShelfId, val name: String, val bookCount: Int?)

data class Shelves(
    val smartLists: List<Shelf>,
    val collections: List<Shelf>,
    val playlists: List<Shelf> = emptyList(),
) {
    val isEmpty: Boolean get() = smartLists.isEmpty() && collections.isEmpty() && playlists.isEmpty()
}
