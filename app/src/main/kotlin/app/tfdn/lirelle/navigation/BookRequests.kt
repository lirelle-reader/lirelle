package app.tfdn.lirelle.navigation

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.tfdn.lirelle.core.ids.BookId

/**
 * A book whose page is asked for from above the shell: the reader, leaving for the next volume.
 * The shell is not on screen then, and its tabs are its own: the request waits here until the
 * shell is back, which opens the page in the tab in front.
 */
@Stable
class BookRequests {
    /** The book asked for and not opened yet. */
    var pending: BookId? by mutableStateOf(null)
        private set

    fun open(bookId: BookId) {
        pending = bookId
    }

    /** The book asked for, once: the request is over. */
    fun take(): BookId? = pending.also { pending = null }
}
