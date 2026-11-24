package app.tfdn.lirelle

import androidx.lifecycle.ViewModel
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.feature.player.controller.PlayerController
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * The screens' way to the app's one player: a book is loaded and plays (spec 11).
 *
 * @param load the player's load, which plays on a book already loaded rather than loading it again.
 */
@HiltViewModel
class ListenViewModel(private val load: (bookId: BookId, fromStart: Boolean) -> Unit) : ViewModel() {

    @Inject
    constructor(player: PlayerController) : this(player::load)

    /** Loads [bookId] and plays it, from its position, or from its start when it is listened to again. */
    fun listen(bookId: BookId, fromStart: Boolean) = load(bookId, fromStart)
}
