package app.tfdn.lirelle.core.downloads.job

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.text.format.Formatter
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class QueueNotificationsTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val open = PendingIntent.getActivity(context, 0, Intent("open-downloads"), PendingIntent.FLAG_IMMUTABLE)
    private val notifications = QueueNotifications(context, DownloadNotifications(context, "p1") { open })

    private val Notification.title: String? get() = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
    private val Notification.text: String? get() = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

    private fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)

    @Test
    fun `waiting says how many books and how much is left, and opens the downloads`() {
        val notification = notifications.waiting(books = 2, leftBytes = 666_000_000)

        assertThat(notification.title).isEqualTo("Waiting for an unmetered network")
        assertThat(notification.text).isEqualTo("2 books · ${size(666_000_000)} left")
        assertThat(notification.channelId).isEqualTo("downloads")
        assertThat(notification.contentIntent).isEqualTo(open)
        assertThat(notification.flags and Notification.FLAG_ONGOING_EVENT).isEqualTo(0)
    }

    @Test
    fun `waiting for one book, and with no size known`() {
        assertThat(notifications.waiting(1, 5_000_000).text).isEqualTo("1 book · ${size(5_000_000)} left")
        assertThat(notifications.waiting(3, null).text).isEqualTo("3 books")
        assertThat(notifications.waiting(3, 0).text).isEqualTo("3 books")
    }

    @Test
    @Config(qualifiers = "fr")
    fun `waiting in French`() {
        val notification = notifications.waiting(2, 666_000_000)

        assertThat(notification.title).isEqualTo("En attente d'un réseau non limité")
        assertThat(notification.text).isEqualTo("2 livres · ${size(666_000_000)} restants")
        assertThat(notification.actions.single().title.toString()).isEqualTo("Télécharger maintenant")
        assertThat(notifications.waiting(1, null).text).isEqualTo("1 livre")
    }

    @Test
    fun `Download now asks its receiver`() {
        val action = notifications.waiting(2, 666_000_000).actions.single()

        assertThat(action.title.toString()).isEqualTo("Download now")
        val pending = shadowOf(action.actionIntent)
        assertThat(pending.isBroadcast).isTrue()
        assertThat(pending.savedIntent.component?.className).isEqualTo(DownloadNowReceiver::class.java.name)
    }

    /** The notification is taken away when its profile closes; a button pressed just before is still sent. */
    @Test
    fun `Download now is of the profile that posted it, and of no other`() {
        val sent = shadowOf(notifications.waiting(2, 666_000_000).actions.single().actionIntent).savedIntent

        assertThat(sentByProfile(sent, "p1")).isTrue()
        assertThat(sentByProfile(sent, "p2")).isFalse()
    }

    @Test
    fun `the button of the next profile's notification is not sent with the id of the one before`() {
        notifications.waiting(2, 666_000_000)
        val next = QueueNotifications(context, DownloadNotifications(context, "p2") { open })

        val sent = shadowOf(next.waiting(1, null).actions.single().actionIntent).savedIntent

        assertThat(sentByProfile(sent, "p2")).isTrue()
        assertThat(sentByProfile(sent, "p1")).isFalse()
    }

    @Test
    fun `an intent that names no profile is of none`() {
        assertThat(sentByProfile(Intent(context, DownloadNowReceiver::class.java), "p1")).isFalse()
        assertThat(sentByProfile(null, "p1")).isFalse()
    }

    @Test
    fun `done says what is on the device, opens the downloads and goes when tapped`() {
        val notification = notifications.done(Batch(books = 3, bytes = 1_500_000_000, failed = 0))

        assertThat(notification.title).isEqualTo("Downloads finished")
        assertThat(notification.text).isEqualTo("3 books downloaded · ${size(1_500_000_000)}")
        assertThat(notification.channelId).isEqualTo("downloads")
        assertThat(notification.contentIntent).isEqualTo(open)
        assertThat(notification.flags and Notification.FLAG_AUTO_CANCEL).isNotEqualTo(0)
        assertThat(notification.flags and Notification.FLAG_ONGOING_EVENT).isEqualTo(0)
        assertThat(notification.actions).isNull()
    }

    @Test
    fun `done with failures counts them in place of the size`() {
        fun text(books: Int, failed: Int) = notifications.done(Batch(books, 1_000, failed)).text

        assertThat(text(2, 1)).isEqualTo("2 books downloaded · 1 failed")
        assertThat(text(1, 2)).isEqualTo("1 book downloaded · 2 failed")
        assertThat(text(1, 0)).isEqualTo("1 book downloaded · ${size(1_000)}")
        assertThat(text(0, 1)).isEqualTo("1 failed")
    }

    @Test
    @Config(qualifiers = "fr")
    fun `done in French`() {
        fun done(books: Int, failed: Int) = notifications.done(Batch(books, 1_500_000_000, failed))

        assertThat(done(3, 0).title).isEqualTo("Téléchargements terminés")
        assertThat(done(3, 0).text).isEqualTo("3 livres téléchargés · ${size(1_500_000_000)}")
        assertThat(done(2, 1).text).isEqualTo("2 livres téléchargés · 1 en échec")
        assertThat(done(1, 0).text).isEqualTo("1 livre téléchargé · ${size(1_500_000_000)}")
        assertThat(done(0, 2).text).isEqualTo("2 en échec")
    }
}
