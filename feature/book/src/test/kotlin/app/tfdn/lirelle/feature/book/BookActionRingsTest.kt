package app.tfdn.lirelle.feature.book

import android.content.Context
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.library.model.BookDetail
import app.tfdn.lirelle.core.library.model.BookReading
import app.tfdn.lirelle.core.library.model.MediaProfile
import app.tfdn.lirelle.core.library.model.PlaybackState
import app.tfdn.lirelle.core.library.model.ReadState
import app.tfdn.lirelle.core.testing.sampleBookDetail
import app.tfdn.lirelle.core.ui.Loadable
import java.time.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What the rings of the book page's actions show of the book: how far it is, which mode was used last. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class BookActionRingsTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val earlier = Instant.parse("2026-11-10T08:00:00Z")
    private val later = Instant.parse("2026-11-12T21:30:00Z")

    /** A text and an audiobook. */
    private val book = sampleBookDetail()

    private fun readAt(at: Instant?, percent: Int = 62) =
        BookReading(percent, lastReadAt = later, totalSeconds = 0, sessionCount = 0, positionAt = at)

    private fun listenedAt(at: Instant?, percentage: Double = 12.0, completed: Boolean = false) =
        Loadable.Ready(PlaybackState("aud_1", 60_000, percentage, completed, at, version = "v1", contentVersion = null))

    private fun show(
        reading: BookReading?,
        listening: Loadable<PlaybackState?>,
        detail: BookDetail = book,
        download: DownloadPart = DownloadPart(),
    ) {
        val starts = BookStarts(onRead = { _, _ -> }, onListen = { _, _ -> }, onNarrate = {})
        compose.setContent { LirelleTheme { ActionsBlock(detail, reading, listening, starts, download) } }
    }

    private fun spoken(name: Int, status: Int? = null, vararg args: Any) = context.spokenAction(name, status, *args)

    private fun assertFilled(tag: String, filled: Boolean) =
        compose.onNodeWithTag(tag).assert(SemanticsMatcher.expectValue(ActionRingFilledKey, filled))

    private fun assertFraction(tag: String, fraction: Float) =
        compose.onNodeWithTag(tag).assert(SemanticsMatcher.expectValue(ActionRingFractionKey, fraction))

    // ---------- Which button is filled ----------

    @Test
    fun `read last, Read is the filled button`() {
        show(readAt(later), listenedAt(earlier))

        assertFilled(MainActionTag, true)
        assertFilled(ListenActionTag, false)
    }

    @Test
    fun `listened to last, Listen is the filled button`() {
        show(readAt(earlier), listenedAt(later))

        assertFilled(MainActionTag, false)
        assertFilled(ListenActionTag, true)
    }

    @Test
    fun `the date that counts is the reading position's, not the last session's`() {
        // The last session is the later of all, and may be a listening one: it does not fill Read.
        show(readAt(earlier).copy(lastReadAt = Instant.parse("2026-11-13T07:00:00Z")), listenedAt(later))

        assertFilled(ListenActionTag, true)
    }

    @Test
    fun `only listened to, Listen is the filled button`() {
        show(readAt(null, percent = 0), listenedAt(earlier))

        assertFilled(MainActionTag, false)
        assertFilled(ListenActionTag, true)
    }

    @Test
    fun `until the reading arrives, Read stays the filled button whatever the listening's date`() {
        // The listening arrived first, or the reading could not be loaded.
        show(reading = null, listenedAt(later))

        assertFilled(MainActionTag, true)
        assertFilled(ListenActionTag, false)
    }

    @Test
    fun `until the reading arrives, the only button of an audiobook is filled`() {
        show(reading = null, listenedAt(later), book.copy(media = MediaProfile.AudioOnly))

        assertFilled(ListenActionTag, true)
    }

    @Test
    fun `neither read nor listened to, Read is the filled button`() {
        show(reading = null, Loadable.Ready(null))

        assertFilled(MainActionTag, true)
        assertFilled(ListenActionTag, false)
    }

    @Test
    fun `while the listening position loads, Read is the filled button and Listen waits`() {
        show(readAt(earlier), Loadable.Loading)

        assertFilled(MainActionTag, true)
        compose.onNodeWithTag(ListenActionTag)
            .assertIsNotEnabled()
            .assertContentDescriptionEquals(spoken(R.string.action_listen))
        assertFilled(ListenActionTag, false)
        assertFraction(ListenActionTag, 0f)
    }

    @Test
    fun `listened to last but the audiobook is not on the device, Read is the filled button`() {
        val offline = DownloadPart(kept = setOf(DownloadFormat.Text))

        show(readAt(earlier), listenedAt(later), download = offline)

        assertFilled(MainActionTag, true)
        assertFilled(ListenActionTag, false)
    }

    @Test
    fun `the only button of an audiobook is filled`() {
        show(reading = null, Loadable.Ready(null), book.copy(media = MediaProfile.AudioOnly))

        assertFilled(ListenActionTag, true)
    }

    @Test
    fun `the only button of a text is filled`() {
        show(reading = null, Loadable.Ready(null), sampleBookDetail(media = MediaProfile.TextOnly))

        assertFilled(MainActionTag, true)
    }

    // ---------- What the rings show ----------

    @Test
    fun `each ring is filled as far as its own position`() {
        show(readAt(later), listenedAt(earlier))

        assertFraction(MainActionTag, 0.62f)
        assertFraction(ListenActionTag, 0.12f)
    }

    @Test
    fun `a book never started has empty rings`() {
        show(reading = null, Loadable.Ready(null))

        assertFraction(MainActionTag, 0f)
        assertFraction(ListenActionTag, 0f)
    }

    @Test
    fun `a finished book and a completed listening have full rings`() {
        show(readAt(later, percent = 40), listenedAt(earlier, completed = true), book.copy(readState = ReadState.Read))

        assertFraction(MainActionTag, 1f)
        assertFraction(ListenActionTag, 1f)
        compose.onNodeWithTag(MainActionTag)
            .assertContentDescriptionEquals(
                context.spokenAction(R.string.action_read, R.string.status_read_again_spoken, narrated = true),
            )
        compose.onNodeWithTag(ListenActionTag)
            .assertContentDescriptionEquals(spoken(R.string.action_listen, R.string.status_listen_again_spoken))
    }

    // ---------- A page built without the server ----------

    @Test
    fun `without the network, the format the device does not have says it is offline, its ring empty`() {
        show(readAt(earlier), listenedAt(later), download = DownloadPart(kept = setOf(DownloadFormat.Text)))

        compose.onNodeWithTag(ListenActionTag)
            .assertIsNotEnabled()
            .assertContentDescriptionEquals(spoken(R.string.action_listen, R.string.status_offline_spoken))
        assertFraction(ListenActionTag, 0f)
        compose.onNodeWithTag(MainActionTag).assertIsEnabled()
        assertFraction(MainActionTag, 0.62f)
    }

    @Test
    fun `without the network, both formats kept and started, Read is the filled button`() {
        // The page built without the server knows how far the reading is, not when it was made.
        val deviceReading = BookReading(62, lastReadAt = null, totalSeconds = 0, sessionCount = 0)
        val both = DownloadPart(kept = setOf(DownloadFormat.Text, DownloadFormat.Audiobook))

        show(deviceReading, listenedAt(later), download = both)

        compose.onNodeWithTag(ListenActionTag).assertIsEnabled()
        assertFilled(MainActionTag, true)
        assertFilled(ListenActionTag, false)
    }

    @Test
    fun `the book being gone from the server, the format the device does not have says it is unavailable`() {
        val gone = DownloadPart(kept = setOf(DownloadFormat.Audiobook), gone = true)

        show(readAt(later), listenedAt(earlier), download = gone)

        // The book is a read-along one, but the reader cannot open it: Read's icon is the plain book, and
        // nothing is said of a narration it cannot play.
        compose.onNodeWithTag(MainActionTag)
            .assertIsNotEnabled()
            .assertContentDescriptionEquals(
                context.spokenAction(R.string.action_read, R.string.status_unavailable_spoken, narrated = false),
            )
        assertFraction(MainActionTag, 0f)
        compose.onNodeWithTag(ListenActionTag)
            .assertIsEnabled()
            .assertContentDescriptionEquals(spoken(R.string.action_listen, R.string.status_resume_percent_spoken, 12))
        assertFilled(ListenActionTag, true)
    }
}
