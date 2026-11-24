package app.tfdn.lirelle.core.downloads.job

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import app.tfdn.lirelle.core.downloads.Downloads
import app.tfdn.lirelle.core.downloads.QueueState
import app.tfdn.lirelle.core.downloads.model.DownloadedBook
import app.tfdn.lirelle.core.downloads.model.WaitReason
import app.tfdn.lirelle.core.session.profile.ProfileScoped
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** What the notification of a queue that waits for Wi-Fi says. */
private data class WifiWait(val books: Int, val leftBytes: Long?)

/**
 * The wait for Wi-Fi of [this], null when it is not what the queue does: something is being fetched, nothing is
 * to fetch (what failed waits for the user), or no network is there at all.
 */
private fun QueueState.wifiWait(): WifiWait? {
    if (active != null || waitReason != WaitReason.UnmeteredOnly) return null
    return WifiWait(waiting.count { it.failure == null }.coerceAtLeast(1), leftBytes)
}

/**
 * The two notifications the queue of a profile posts as a whole, for as long as that profile is open: one
 * while it waits for Wi-Fi, one when it is empty again after something ended. Without the permission nothing
 * is posted. Both go when the profile closes ([close]): they say what an account downloads, and their tap and
 * their button would act for whoever signs in next.
 *
 * One per profile: what closes it is what posts.
 */
@ProfileScoped
class QueueNotices internal constructor(
    private val context: Context,
    private val queue: Flow<QueueState>,
    private val books: Flow<List<DownloadedBook>>,
    private val notifications: QueueNotifications,
) {
    @Inject
    constructor(@ApplicationContext context: Context, downloads: Downloads, notifications: QueueNotifications) :
        this(context, downloads.queue, downloads.books, notifications)

    private var closed = false

    /**
     * The profile closes: both notifications are taken away, and none is posted any more, though the queue
     * is followed a moment longer, until the profile's scope ends.
     */
    fun close() = synchronized(this) {
        closed = true
        context.getSystemService(NotificationManager::class.java)?.run {
            cancel(WaitingNotificationId)
            cancel(DoneNotificationId)
        }
    }

    /** Follows the queue until cancelled. */
    suspend fun run() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        coroutineScope {
            launch { manager.followWait() }
            launch { manager.followBatches() }
        }
    }

    /**
     * Posted when the queue starts to wait for Wi-Fi and taken away when it stops. Swiped away meanwhile, it is
     * left so: a change of what is left only changes a notification that is still shown.
     */
    private suspend fun NotificationManager.followWait() {
        var waiting = false
        queue.map { it.wifiWait() }.distinctUntilChanged().collect { wait ->
            when {
                wait == null -> cancel(WaitingNotificationId)
                !waiting || shows(WaitingNotificationId) ->
                    post(WaitingNotificationId, notifications.waiting(wait.books, wait.leftBytes))
            }
            waiting = wait != null
        }
    }

    /** One notification each time the queue is empty again, when something ended since it last was. */
    private suspend fun NotificationManager.followBatches() {
        val count = BatchCount()
        books.collect { books ->
            count.follow(books)
            if (books.nothingLeft()) count.take()?.let { post(DoneNotificationId, notifications.done(it)) }
        }
    }

    private fun NotificationManager.post(id: Int, notification: Notification) = synchronized(this@QueueNotices) {
        if (!closed && areNotificationsEnabled()) notify(id, notification)
    }

    private fun NotificationManager.shows(id: Int): Boolean = activeNotifications.any { it.id == id }
}
