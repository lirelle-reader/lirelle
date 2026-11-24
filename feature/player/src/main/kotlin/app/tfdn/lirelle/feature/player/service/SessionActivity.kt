package app.tfdn.lirelle.feature.player.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import app.tfdn.lirelle.core.ids.BookId

/** The request code of the app opened as it is (its player), for an audiobook. */
private const val PlayerRequest = 0

/** The request code of the app opened on the reader, for a narration: never the same intent as the player's. */
private const val ReaderRequest = 1

/**
 * The app's launcher [launch], asking for the reader of [bookId], a book of the profile [profileId]: what a
 * narration's notification opens.
 */
fun readerIntent(launch: Intent, bookId: BookId, profileId: String): Intent = Intent(launch)
    // An intent holds no id: its text.
    .putExtra(PlayerSessionContract.EXTRA_OPEN_READER_BOOK_ID, bookId.value)
    .putExtra(PlayerSessionContract.EXTRA_OPEN_READER_PROFILE_ID, profileId)

/** The book whose reader a tap opens: the narrated one; null for an audiobook or no book. */
val LoadedBook?.readerBookId: BookId? get() = this?.takeIf { it.source is BookSource.Narration }?.bookId

/** The book whose reader a tap opens, as the session's extras say: the narrated one; null otherwise. */
val PlayerSessionExtras.readerBookId: BookId? get() = book?.id?.takeIf { narrationFileId != null }

/**
 * What a tap on a notification of the player (or the session's activity, for the system) opens: the app, as it
 * always did, for an audiobook or no book ([readerBookId] null); the app asking for the reader of
 * [readerBookId] for a narration. Null when the app has no launcher.
 *
 * @param profileId the profile the service plays for: a notification tapped once another account signed in
 *   must not open, in that account, the book that has the same id.
 */
fun Context.sessionActivity(readerBookId: BookId?, profileId: String): PendingIntent? {
    val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return null
    val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    return if (readerBookId == null) {
        PendingIntent.getActivity(this, PlayerRequest, launch, flags)
    } else {
        PendingIntent.getActivity(this, ReaderRequest, readerIntent(launch, readerBookId, profileId), flags)
    }
}
