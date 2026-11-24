package app.tfdn.lirelle.core.downloads.job

import android.content.Intent

/** On the intents the buttons of the downloads' notifications send: the profile the notification is of. */
internal const val ExtraProfileId = "app.tfdn.lirelle.core.downloads.PROFILE_ID"

/**
 * Whether [intent] was sent by a notification of the profile [openProfileId], the one that is open.
 *
 * The notifications of a profile are taken away when it closes, but a button may have been pressed just
 * before, and the system keeps what it was given to send: without this, Cancel would name a book in the queue
 * of the account that signed in next, and Download now would let that account's queue use any network. An
 * intent that names no profile is of none.
 */
internal fun sentByProfile(intent: Intent?, openProfileId: String): Boolean =
    intent?.getStringExtra(ExtraProfileId) == openProfileId
