package app.tfdn.lirelle.navigation

import app.tfdn.lirelle.core.ids.BookId

/**
 * The ways from the shell's mini-player into what is above the shell.
 *
 * @property onOpenPlayer the player of the book loaded.
 * @property onOpenReader the reader of a book whose narration is loaded.
 */
class PlayerEntrances(
    val onOpenPlayer: () -> Unit,
    val onOpenReader: (bookId: BookId) -> Unit,
)
