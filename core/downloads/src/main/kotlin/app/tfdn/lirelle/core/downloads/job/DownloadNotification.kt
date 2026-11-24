package app.tfdn.lirelle.core.downloads.job

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.text.format.Formatter
import app.tfdn.lirelle.core.downloads.QueueEntry
import app.tfdn.lirelle.core.downloads.QueueState
import app.tfdn.lirelle.core.downloads.R
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.WaitReason
import app.tfdn.lirelle.core.downloads.nameRes
import app.tfdn.lirelle.core.downloads.pagesOf
import app.tfdn.lirelle.core.ids.BookId

private const val ChannelId = "downloads"
private const val ProgressMax = 1_000

/** The notification of the job, which the system removes with it. */
internal const val ProgressNotificationId = 4_001

/** Where the notifications of the downloads lead: the application says how its Downloads destination is opened. */
fun interface DownloadsIntents {
    /**
     * What opens the Downloads destination for the notifications of the profile [profileId]. It names that
     * profile: the downloads shown are those of the profile open when it is tapped, which must be the one
     * the notification spoke of. A notification outlives its profile by a moment.
     */
    fun openDownloads(profileId: String): PendingIntent

    companion object {
        /** Set, to true, on the intent that opens the application on its Downloads destination. */
        const val EXTRA_OPEN_DOWNLOADS = "app.tfdn.lirelle.core.downloads.OPEN_DOWNLOADS"

        /**
         * On the same intent, the profile whose notification it is. Absent from the intent of a notification
         * posted before the profiles could be several: that one is followed for whoever is open.
         */
        const val EXTRA_OPEN_DOWNLOADS_PROFILE_ID = "app.tfdn.lirelle.core.downloads.OPEN_DOWNLOADS_PROFILE_ID"
    }
}

/**
 * Builds the notifications of one profile's downloads, in their own quiet channel, made when the first one is.
 *
 * @property profileId the profile they are of, written into what their buttons send ([ExtraProfileId]) and
 *   into what a tap on them opens ([DownloadsIntents.openDownloads]): a button pressed or a notification
 *   tapped once that profile closed must not act on, nor show, the downloads of whoever is open then.
 */
class DownloadNotifications(
    private val context: Context,
    internal val profileId: String,
    private val intents: DownloadsIntents,
) {
    /** What the job shows for [queue]: the book it fetches, with its [title], else what it waits for. */
    fun of(queue: QueueState, title: String?): Notification =
        queue.active?.let { progress(it, title) } ?: waiting(queue.waitReason)

    /**
     * The book being fetched, its formats and how far it is, with Cancel: in pages, the bar following them,
     * for a download made of pages; in bytes for any other. A null [title] is not known.
     */
    fun progress(entry: QueueEntry, title: String?): Notification {
        val done = entry.done()
        return ongoing(title ?: context.getString(R.string.downloads_notification_downloading))
            .setContentText(progressText(entry))
            .setProgress(ProgressMax, done ?: 0, done == null)
            .addAction(cancelAction(entry.bookId))
            .build()
    }

    /** Nothing is being fetched: waiting for Wi-Fi when that is why, otherwise only that downloads go on. */
    fun waiting(reason: WaitReason?): Notification =
        if (reason == WaitReason.UnmeteredOnly) {
            ongoing(context.getString(R.string.downloads_notification_waiting_wifi)).build()
        } else {
            ongoing(context.getString(R.string.downloads_notification_downloading))
                .setProgress(0, 0, true)
                .build()
        }

    private fun ongoing(title: String): Notification.Builder = quiet(title)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setCategory(Notification.CATEGORY_PROGRESS)

    /** A notification of the quiet channel, titled [title], that opens the Downloads destination of its profile. */
    internal fun quiet(title: String): Notification.Builder {
        val name = context.getString(R.string.downloads_channel)
        context.getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(NotificationChannel(ChannelId, name, NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(context, ChannelId)
            .setSmallIcon(R.drawable.ic_download_notification)
            .setContentTitle(title)
            .setContentIntent(intents.openDownloads(profileId))
    }

    private fun progressText(entry: QueueEntry): String {
        val formats = entry.formats.shown().joinToString { context.getString(nameRes(it, entry.textName)) }
        val pages = entry.pages?.let { context.resources.pagesOf(it) }
        val total = entry.totalBytes?.takeIf { it > 0 }
        val received = Formatter.formatShortFileSize(context, entry.receivedBytes)
        return when {
            pages != null -> context.getString(R.string.downloads_notification_received, formats, pages)
            total == null -> context.getString(R.string.downloads_notification_received, formats, received)
            else -> {
                val whole = Formatter.formatShortFileSize(context, total)
                context.getString(R.string.downloads_notification_progress, formats, received, whole)
            }
        }
    }

    private fun cancelAction(bookId: BookId): Notification.Action {
        val intent = Intent(context, DownloadCancelReceiver::class.java)
            // An intent holds no id: its text.
            .putExtra(ExtraBookId, bookId.value)
            .putExtra(ExtraProfileId, profileId)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        // One request per book: the extras alone do not tell two pending intents apart.
        val pending = PendingIntent.getBroadcast(context, bookId.value.hashCode(), intent, flags)
        val label = context.getString(R.string.downloads_notification_cancel)
        return Notification.Action.Builder(null as Icon?, label, pending).build()
    }
}

/** A narration brings its text: only the narration is named. */
private fun List<DownloadFormat>.shown(): List<DownloadFormat> =
    if (DownloadFormat.Narration in this) this - DownloadFormat.Text else this

/**
 * How far it is, out of [ProgressMax]: by its pages when it is made of pages, their bytes being counted
 * against an estimate; else by its bytes. Null while neither says.
 */
private fun QueueEntry.done(): Int? {
    pages?.let { return (it.kept.toLong() * ProgressMax / it.total).toInt() }
    val total = totalBytes?.takeIf { it > 0 } ?: return null
    return (receivedBytes.coerceIn(0, total) * ProgressMax / total).toInt()
}
