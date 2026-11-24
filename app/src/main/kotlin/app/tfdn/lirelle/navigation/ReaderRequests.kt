package app.tfdn.lirelle.navigation

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.tfdn.lirelle.core.ids.BookId

/**
 * A reader asked for from outside the screens: a tap on the notification of a narration, which opens the reader
 * of its book. It may come before the signed-in screens exist (a cold start, before the first frame): the
 * request waits here until they take it, once. Signed out, or while the profile is being changed, it is dropped
 * (`ForgetReaderRequestsWithoutProfile`).
 *
 * A request names the profile whose notification made it: the book it asks for is a book of that account, and
 * the same id is another book, or none, for another. A notification outlives its profile by a moment, and the
 * system keeps what it was given to send: a request of another profile than the open one opens nothing.
 *
 * @param openProfile the profile that is open when a request is taken; null when none is.
 */
@Stable
class ReaderRequests(private val openProfile: () -> String? = { null }) {
    /** The book whose reader is asked for and not opened yet. */
    var pending: BookId? by mutableStateOf(null)
        private set

    /** The profile [pending] is a book of. */
    private var profileId: String? = null

    /** @param profileId the profile the notification was posted for; null when it names none. */
    fun open(bookId: BookId, profileId: String? = null) {
        this.profileId = profileId
        pending = bookId
    }

    /**
     * The book asked for, once: the request is over. None when it was asked for another profile than the open
     * one: that request is over too.
     */
    fun take(): BookId? = pending.also { pending = null }.takeIf { profileId == openProfile() }
}
