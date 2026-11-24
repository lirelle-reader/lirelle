package app.tfdn.lirelle.core.downloads.job

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.tfdn.lirelle.core.downloads.di.DownloadsProfileEntry
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.session.profile.CurrentProfile
import app.tfdn.lirelle.core.session.profile.openEntryOrNull
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** The book to cancel, by its id's text. */
internal const val ExtraBookId = "app.tfdn.lirelle.core.downloads.BOOK_ID"

/** The book [intent] asks to cancel; null when it names none. */
internal fun cancelledBook(intent: Intent?): BookId? =
    intent?.getStringExtra(ExtraBookId)?.takeIf { it.isNotEmpty() }?.let(::BookId)

/**
 * Cancel, on the notification of a download: what is not complete of that book leaves the queue.
 *
 * The queue is the open profile's, asked when the button is pressed, and only when the notification was that
 * profile's ([sentByProfile]). With no profile open, or another than the one the notification was posted for
 * (it closed, and another account signed in), the button does nothing: the book it names is a book of the
 * profile that posted it, and the same id is another book, or none, in another profile's queue.
 */
@AndroidEntryPoint
class DownloadCancelReceiver : BroadcastReceiver() {

    @Inject
    lateinit var profile: CurrentProfile

    override fun onReceive(context: Context, intent: Intent?) {
        val bookId = cancelledBook(intent) ?: return
        val open = profile.openEntryOrNull<DownloadsProfileEntry>() ?: return
        if (!sentByProfile(intent, open.profileId)) return
        val downloads = open.entry.downloadsQueue()
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                downloads.cancel(bookId)
            } finally {
                pending.finish()
            }
        }
    }
}
