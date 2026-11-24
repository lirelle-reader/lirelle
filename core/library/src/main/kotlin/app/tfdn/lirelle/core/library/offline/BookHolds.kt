package app.tfdn.lirelle.core.library.offline

import app.tfdn.lirelle.core.ids.BookId

/**
 * Says which books are in use, by the reader or the player: what the device keeps of a held book is not
 * deleted. Each [hold] is undone by one [release]; a book held twice is in use until both are released.
 */
interface BookHolds {
    fun hold(bookId: BookId)

    fun release(bookId: BookId)

    /** Nothing is kept, so nothing is held. */
    object None : BookHolds {
        override fun hold(bookId: BookId) = Unit

        override fun release(bookId: BookId) = Unit
    }
}
