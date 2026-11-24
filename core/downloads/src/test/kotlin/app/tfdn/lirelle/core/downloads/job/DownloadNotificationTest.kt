package app.tfdn.lirelle.core.downloads.job

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.text.format.Formatter
import app.tfdn.lirelle.core.downloads.QueueState
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.PageCount
import app.tfdn.lirelle.core.downloads.model.TextName
import app.tfdn.lirelle.core.downloads.model.WaitReason
import app.tfdn.lirelle.core.ids.BookId
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class DownloadNotificationTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val open = PendingIntent.getActivity(context, 0, Intent("open-downloads"), PendingIntent.FLAG_IMMUTABLE)
    private val openedFor = mutableListOf<String>()
    private val notifications = DownloadNotifications(context, "p1") { profileId ->
        openedFor += profileId
        open
    }

    private val Notification.title: String? get() = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
    private val Notification.text: String? get() = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
    private val Notification.progress: Int get() = extras.getInt(Notification.EXTRA_PROGRESS)
    private val Notification.progressMax: Int get() = extras.getInt(Notification.EXTRA_PROGRESS_MAX)
    private val Notification.indeterminate: Boolean
        get() = extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE)

    private fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)

    @Test
    fun `progress shows the book, its format and how far it is`() {
        val notification = notifications.progress(entry(), "The Orchard at Dusk")

        assertThat(notification.title).isEqualTo("The Orchard at Dusk")
        assertThat(notification.text).isEqualTo("Audiobook · ${size(124_000_000)} of ${size(412_000_000)}")
        assertThat(notification.progressMax).isEqualTo(1000)
        assertThat(notification.progress).isEqualTo(300)
        assertThat(notification.indeterminate).isFalse()
        assertThat(notification.channelId).isEqualTo("downloads")
        assertThat(notification.flags and Notification.FLAG_ONGOING_EVENT).isNotEqualTo(0)
        assertThat(notification.contentIntent).isEqualTo(open)
    }

    /** The downloads a tap leads to are those of whoever is open then: the tap says whose it meant. */
    @Test
    fun `a tap on any of the notifications asks for the downloads of the profile that posted it`() {
        notifications.progress(entry(), "The Orchard at Dusk")
        notifications.waiting(WaitReason.UnmeteredOnly)
        notifications.quiet("A notice of the queue")

        assertThat(openedFor).containsExactly("p1", "p1", "p1")
    }

    /** The same book id is another book, or none, in the queue of the account that signs in next. */
    @Test
    fun `Cancel is of the profile that posted it, and of no other`() {
        val action = notifications.progress(entry(), "The Orchard at Dusk").actions.single()
        val sent = shadowOf(action.actionIntent).savedIntent

        assertThat(sent.component?.className).isEqualTo(DownloadCancelReceiver::class.java.name)
        assertThat(cancelledBook(sent)).isEqualTo(entry().bookId)
        assertThat(sentByProfile(sent, "p1")).isTrue()
        assertThat(sentByProfile(sent, "p2")).isFalse()
    }

    @Test
    @Config(qualifiers = "fr")
    fun `progress in French`() {
        val notification = notifications.progress(entry(formats = listOf(DownloadFormat.Text)), "Le Verger au soir")

        assertThat(notification.text).isEqualTo("Texte · ${size(124_000_000)} sur ${size(412_000_000)}")
        assertThat(notification.actions.single().title.toString()).isEqualTo("Annuler")
    }

    @Test
    fun `each format has its name, and a narration says nothing of the text it brings`() {
        fun text(vararg formats: DownloadFormat) =
            notifications.progress(entry(formats = formats.toList()), "A").text.orEmpty().substringBefore(" · ")

        assertThat(text(DownloadFormat.Text)).isEqualTo("Text")
        assertThat(text(DownloadFormat.Text, DownloadFormat.Audiobook)).isEqualTo("Text, Audiobook")
        assertThat(text(DownloadFormat.Text, DownloadFormat.Narration)).isEqualTo("Narration")
    }

    @Test
    @Config(qualifiers = "fr")
    fun `the formats in French`() {
        val formats = listOf(DownloadFormat.Text, DownloadFormat.Audiobook, DownloadFormat.Narration)

        val text = notifications.progress(entry(formats = formats), "A").text

        assertThat(text).startsWith("Livre audio, Narration · ")
    }

    @Test
    fun `the text of a book read by pages is named by its file, alone or beside an audiobook`() {
        fun text(name: TextName, vararg formats: DownloadFormat) = notifications
            .progress(entry(formats = formats.toList()).copy(textName = name), "A").text.orEmpty()
            .substringBefore(" · ")

        val said = TextName.entries.map { text(it, DownloadFormat.Text) }
        assertThat(said).containsExactly("Text", "PDF", "CBZ", "CBR", "CB7", "DjVu").inOrder()
        assertThat(text(TextName.Pdf, DownloadFormat.Text, DownloadFormat.Audiobook)).isEqualTo("PDF, Audiobook")
        assertThat(text(TextName.Cbz, DownloadFormat.Audiobook)).isEqualTo("Audiobook")
    }

    @Test
    fun `a download made of pages says its pages, its bar following them and not the bytes`() {
        val album = entry(formats = listOf(DownloadFormat.Text), receivedBytes = 9_000_000, totalBytes = 9_400_000)
            .copy(pages = PageCount(12, 48), textName = TextName.Cbz)

        val notification = notifications.progress(album, "Blacksad")

        assertThat(notification.title).isEqualTo("Blacksad")
        assertThat(notification.text).isEqualTo("CBZ · 12 pages of 48")
        assertThat(notification.progressMax).isEqualTo(1000)
        assertThat(notification.progress).isEqualTo(250)
        assertThat(notification.indeterminate).isFalse()
    }

    @Test
    @Config(qualifiers = "fr")
    fun `the pages in French, and a bar that is known without a size`() {
        val album = entry(formats = listOf(DownloadFormat.Text), totalBytes = null)
            .copy(pages = PageCount(1, 1), textName = TextName.Djvu)

        val notification = notifications.progress(album, "A")

        assertThat(notification.text).isEqualTo("DjVu · 1 page sur 1")
        assertThat(notification.progress).isEqualTo(1000)
        assertThat(notification.indeterminate).isFalse()
    }

    @Test
    fun `a size not known yet shows what was received, without a bar that lies`() {
        val notification = notifications.progress(entry(totalBytes = null), null)

        assertThat(notification.title).isEqualTo("Downloading")
        assertThat(notification.text).isEqualTo("Audiobook · ${size(124_000_000)}")
        assertThat(notification.indeterminate).isTrue()
    }

    @Test
    fun `progress never goes past the end`() {
        val notification = notifications.progress(entry(receivedBytes = 500, totalBytes = 400), "A")

        assertThat(notification.progress).isEqualTo(1000)
    }

    @Test
    fun `Cancel asks the receiver to cancel that book`() {
        val action = notifications.progress(entry(bookId = BookId("7")), "A").actions.single()

        assertThat(action.title.toString()).isEqualTo("Cancel")
        val pending = shadowOf(action.actionIntent)
        assertThat(pending.isBroadcast).isTrue()
        assertThat(pending.savedIntent.component?.className).isEqualTo(DownloadCancelReceiver::class.java.name)
        assertThat(cancelledBook(pending.savedIntent)).isEqualTo(BookId("7"))
        assertThat(pending.savedIntent.getStringExtra(ExtraBookId)).isEqualTo("7")
        assertThat(cancelledBook(Intent())).isNull()
    }

    @Test
    fun `waiting for a network that is not metered says so`() {
        val notification = notifications.waiting(WaitReason.UnmeteredOnly)

        assertThat(notification.title).isEqualTo("Waiting for an unmetered network")
        assertThat(notification.actions).isNull()
    }

    @Test
    @Config(qualifiers = "fr")
    fun `waiting in French`() {
        assertThat(notifications.waiting(WaitReason.UnmeteredOnly).title).isEqualTo("En attente d'un réseau non limité")
        assertThat(notifications.waiting(null).title).isEqualTo("Téléchargement")
    }

    @Test
    fun `before the queue is known, only that it is downloading`() {
        val notification = notifications.waiting(null)

        assertThat(notification.title).isEqualTo("Downloading")
        assertThat(notification.indeterminate).isTrue()
    }

    @Test
    fun `the queue shows what it fetches, else what it waits for`() {
        val fetching = QueueState(entry(), emptyList(), null, 288_000_000)
        val waiting = QueueState(null, listOf(entry()), WaitReason.UnmeteredOnly, 288_000_000)

        assertThat(notifications.of(fetching, "The Orchard at Dusk").title).isEqualTo("The Orchard at Dusk")
        assertThat(notifications.of(waiting, null).title).isEqualTo("Waiting for an unmetered network")
    }

    @Test
    fun `the channel is made when a notification is, quiet`() {
        val manager = checkNotNull(context.getSystemService(NotificationManager::class.java))
        assertThat(manager.getNotificationChannel("downloads")).isNull()

        notifications.waiting(null)

        val channel = manager.getNotificationChannel("downloads")
        assertThat(channel.importance).isEqualTo(NotificationManager.IMPORTANCE_LOW)
        assertThat(channel.name.toString()).isEqualTo("Downloads")
    }
}
