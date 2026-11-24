package app.tfdn.lirelle.navigation

import app.tfdn.lirelle.core.ids.BookId

/**
 * The ways to go on with a book, above the shell: in the reader, in the comics reader, or in the player.
 *
 * @property onRead opens the reader on a book: where it was left, or from its start.
 * @property onReadComic opens the comics reader on a book that is a comic: where it was left, or from its start.
 * @property onListen loads a book in the player and plays it, where it was left or from its start
 *   when its listening is done again, and opens the player.
 */
class BookModes(
    val onRead: (ReaderDestination) -> Unit,
    val onReadComic: (ComicsDestination) -> Unit,
    val onListen: (bookId: BookId, fromStart: Boolean) -> Unit,
)
