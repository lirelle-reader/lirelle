package app.tfdn.lirelle.navigation

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The Downloads destination asked for from outside the screens: a tap on a notification of the downloads. As
 * a reader asked for ([ReaderRequests]), it may come before the signed-in screens exist: the request waits
 * here until the shell takes it, once. Signed out, or while the profile is being changed, it is dropped
 * (`ForgetDownloadsRequestsWithoutProfile`).
 *
 * A request names the profile whose notification made it: the downloads it means are that account's. A
 * notification outlives its profile by a moment, and the system keeps what it was given to send: a request of
 * another profile than the open one selects nothing — and changes no profile, which only the user does.
 *
 * A request that names no profile is followed, where a reader asked for without one is dropped
 * ([ReaderRequests]). Every notification this version posts names its profile, so such a request is an intent
 * that was not made here for a profile: the activity is exported, and any app may send it the extra that asks
 * for the downloads without the one that names a profile. Following it only selects the Downloads destination
 * of the profile that is open, which shows nothing a tap on the bar would not; the reader opens a book of an
 * account, which is why it wants the profile named. Whether the system still shows, after the update, a
 * notification posted by the version before it — which named none — is not known; if it does, this is also
 * what makes its tap work.
 *
 * @param openProfile the profile that is open when a request is taken; null when none is.
 */
@Stable
class DownloadsRequests(private val openProfile: () -> String? = { null }) {
    /** Whether the destination is asked for and not selected yet. */
    var pending: Boolean by mutableStateOf(false)
        private set

    /** The profile whose downloads are asked for; null when the request names none. */
    private var profileId: String? = null

    /** @param profileId the profile the notification was posted for; null when it names none. */
    fun open(profileId: String? = null) {
        this.profileId = profileId
        pending = true
    }

    /**
     * Whether it was asked for, once: the request is over. False when it was asked for another profile than
     * the open one: that request is over too.
     */
    fun take(): Boolean = pending.also { pending = false } && (profileId == null || profileId == openProfile())
}
