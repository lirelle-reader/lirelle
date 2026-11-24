package app.tfdn.lirelle.core.downloads.job

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.text.format.Formatter
import app.tfdn.lirelle.core.downloads.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** The queue waits for Wi-Fi: not the job's own notification, since the job does not run on a metered network. */
internal const val WaitingNotificationId = 4_003

/** The queue is empty again, and something ended. */
internal const val DoneNotificationId = 4_004

/** Builds what the queue says of itself as a whole, in the channel of the downloads, where a tap opens them. */
class QueueNotifications @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloads: DownloadNotifications,
) {
    /**
     * The queue waits for Wi-Fi with [books] to fetch, [leftBytes] in all when a size is known: it can be swiped
     * away, and offers to download now.
     */
    fun waiting(books: Int, leftBytes: Long?): Notification {
        val count = plural(R.plurals.downloads_notification_books, books)
        val text = leftBytes?.takeIf { it > 0 }
            ?.let { context.getString(R.string.downloads_notification_waiting_left, count, size(it)) }
        return downloads.quiet(context.getString(R.string.downloads_notification_waiting_wifi))
            .setContentText(text ?: count)
            .setOnlyAlertOnce(true)
            .addAction(nowAction())
            .build()
    }

    /** The queue is empty: what [batch] left on the device, and how many books failed. It goes when tapped. */
    fun done(batch: Batch): Notification = downloads.quiet(context.getString(R.string.downloads_notification_done))
        .setContentText(doneText(batch))
        .setAutoCancel(true)
        .build()

    private fun doneText(batch: Batch): String {
        val books = plural(R.plurals.downloads_notification_done_books, batch.books)
        val failed = plural(R.plurals.downloads_notification_done_failed, batch.failed)
        return when {
            batch.books == 0 -> failed
            batch.failed > 0 -> context.getString(R.string.downloads_notification_joined, books, failed)
            batch.bytes > 0 -> context.getString(R.string.downloads_notification_joined, books, size(batch.bytes))
            else -> books
        }
    }

    private fun plural(id: Int, count: Int): String = context.resources.getQuantityString(id, count, count)

    private fun size(bytes: Long): String = Formatter.formatShortFileSize(context, bytes)

    private fun nowAction(): Notification.Action {
        val intent = Intent(context, DownloadNowReceiver::class.java).putExtra(ExtraProfileId, downloads.profileId)
        // Updated: the extras alone do not tell two pending intents apart, and the one of a profile that
        // closed would be given back with that profile's id.
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val pending = PendingIntent.getBroadcast(context, 0, intent, flags)
        val label = context.getString(R.string.downloads_notification_now)
        return Notification.Action.Builder(null as Icon?, label, pending).build()
    }
}
