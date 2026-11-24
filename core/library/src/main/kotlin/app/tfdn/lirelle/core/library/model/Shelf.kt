package app.tfdn.lirelle.core.library.model

import app.tfdn.lirelle.core.ids.ShelfId

enum class ShelfKind { SmartList, Collection }

/** [bookCount] is null for a smart list whose rule the server could not evaluate. */
data class Shelf(val kind: ShelfKind, val id: ShelfId, val name: String, val bookCount: Int?)

data class Shelves(val smartLists: List<Shelf>, val collections: List<Shelf>) {
    val isEmpty: Boolean get() = smartLists.isEmpty() && collections.isEmpty()
}
