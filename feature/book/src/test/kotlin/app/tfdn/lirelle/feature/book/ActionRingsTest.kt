package app.tfdn.lirelle.feature.book

import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.library.model.PlaybackState
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.ui.Loadable
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.time.Duration
import java.time.Instant
import org.junit.Test

/** What each reading action's ring shows: the rows of the design's table, and which button is filled. */
class ActionRingsTest {

    private val earlier = Instant.parse("2026-11-10T08:00:00Z")
    private val later = Instant.parse("2026-11-12T21:30:00Z")

    private fun listening(percentage: Double, positionMs: Long = 60_000, completed: Boolean = false) =
        Loadable.Ready(PlaybackState("aud_1", positionMs, percentage, completed, later, "v3", "rev"))

    private fun read(enabled: Boolean = true, status: RingStatus = RingStatus.Start) =
        ActionRingUi(ActionKind.Read, enabled, status, ringFraction(status))

    private fun listen(enabled: Boolean = true) = ActionRingUi(ActionKind.Listen, enabled, RingStatus.Start, 0f)

    // ---------- Read ----------

    @Test
    fun `a book never started starts, its ring empty`() {
        val status = readStatus(canRead = true, finished = false, percent = 0)

        assertThat(status).isEqualTo(RingStatus.Start)
        assertThat(ringFraction(status)).isEqualTo(0f)
    }

    @Test
    fun `a book started resumes at its percentage, its ring filled that far`() {
        val status = readStatus(canRead = true, finished = false, percent = 62)

        assertThat(status).isEqualTo(RingStatus.ResumeAt(62))
        assertThat(ringFraction(status)).isWithin(0.0001f).of(0.62f)
    }

    @Test
    fun `a book at its end the server does not say is read still resumes, its ring full`() {
        val status = readStatus(canRead = true, finished = false, percent = 100)

        assertThat(status).isEqualTo(RingStatus.ResumeAt(100))
        assertThat(ringFraction(status)).isEqualTo(1f)
    }

    @Test
    fun `a book started that no reader opens resumes without a percentage, its ring empty`() {
        val status = readStatus(canRead = false, finished = false, percent = 62)

        assertThat(status).isEqualTo(RingStatus.Resume)
        assertThat(ringFraction(status)).isEqualTo(0f)
    }

    @Test
    fun `a finished book is read again, its ring full, wherever it stopped`() {
        assertThat(readStatus(canRead = true, finished = true, percent = 40)).isEqualTo(RingStatus.Again)
        assertThat(readStatus(canRead = true, finished = true, percent = 0)).isEqualTo(RingStatus.Again)
        assertThat(ringFraction(RingStatus.Again)).isEqualTo(1f)
    }

    // ---------- Listen ----------

    @Test
    fun `an audiobook never started starts`() {
        assertThat(listenStatus(playable = true, Loadable.Ready(null))).isEqualTo(RingStatus.Start)
        assertThat(listenStatus(playable = true, listening(0.0, positionMs = 0))).isEqualTo(RingStatus.Start)
    }

    @Test
    fun `an audiobook started resumes at its percentage`() {
        assertThat(listenStatus(playable = true, listening(42.7))).isEqualTo(RingStatus.ResumeAt(42))
    }

    @Test
    fun `an audiobook started under one percent resumes without a percentage`() {
        assertThat(listenStatus(playable = true, listening(0.3))).isEqualTo(RingStatus.Resume)
        assertThat(listenStatus(playable = true, listening(0.0, positionMs = 5_000))).isEqualTo(RingStatus.Resume)
    }

    @Test
    fun `a completed listening is done again, wherever it stopped`() {
        assertThat(listenStatus(playable = true, listening(60.0, completed = true))).isEqualTo(RingStatus.Again)
    }

    @Test
    fun `a listening position still loading, or that cannot be had, has no status`() {
        assertThat(listenStatus(playable = true, Loadable.Loading)).isEqualTo(RingStatus.None)
        assertThat(listenStatus(playable = true, Loadable.Failed(ApiError.ServerUnreachable)))
            .isEqualTo(RingStatus.None)
        assertThat(ringFraction(RingStatus.None)).isEqualTo(0f)
    }

    @Test
    fun `listening that opens the reader's narration has no status, whatever position is known`() {
        assertThat(listenStatus(playable = false, listening(42.7))).isEqualTo(RingStatus.None)
        assertThat(listenStatus(playable = false, Loadable.Loading)).isEqualTo(RingStatus.None)
    }

    // ---------- Switched off, on a page built without the server ----------

    @Test
    fun `on a page the server gave, a status stays what it is`() {
        assertThat(switchedOff(RingStatus.Resume, kept = null, gone = false)).isEqualTo(RingStatus.Resume)
    }

    @Test
    fun `without the network, a format the device does not have is offline`() {
        val kept = setOf(DownloadFormat.Text)

        assertThat(switchedOff(RingStatus.ResumeAt(12), kept, gone = false)).isEqualTo(RingStatus.Offline)
        assertThat(ringFraction(RingStatus.Offline)).isEqualTo(0f)
    }

    @Test
    fun `the book being gone from the server, a format the device does not have is unavailable`() {
        val kept = setOf(DownloadFormat.Text)

        assertThat(switchedOff(RingStatus.ResumeAt(12), kept, gone = true)).isEqualTo(RingStatus.Unavailable)
        assertThat(ringFraction(RingStatus.Unavailable)).isEqualTo(0f)
    }

    // ---------- The description of a button ----------

    @Test
    fun `a button that opens shows its status and its fraction`() {
        val ring = actionRing(ActionKind.Read, opens = true, RingStatus.ResumeAt(50), kept = null)

        assertThat(ring).isEqualTo(ActionRingUi(ActionKind.Read, enabled = true, RingStatus.ResumeAt(50), 0.5f))
    }

    @Test
    fun `a button that waits for its position is switched off and keeps its status`() {
        val ring = actionRing(ActionKind.Listen, opens = true, RingStatus.None, kept = null).waiting()

        assertThat(ring).isEqualTo(ActionRingUi(ActionKind.Listen, enabled = false, RingStatus.None, 0f))
    }

    @Test
    fun `a button that does not open, on a page built without the server, says why and shows no progress`() {
        val kept = setOf(DownloadFormat.Text)

        val offline = actionRing(ActionKind.Listen, opens = false, RingStatus.ResumeAt(50), kept)
        val gone = actionRing(ActionKind.Listen, opens = false, RingStatus.ResumeAt(50), kept, gone = true)

        assertThat(offline).isEqualTo(ActionRingUi(ActionKind.Listen, enabled = false, RingStatus.Offline, 0f))
        assertThat(gone).isEqualTo(ActionRingUi(ActionKind.Listen, enabled = false, RingStatus.Unavailable, 0f))
    }

    @Test
    fun `a button that opens on a page built without the server keeps its status`() {
        val ring = actionRing(ActionKind.Read, opens = true, RingStatus.ResumeAt(50), kept = setOf(DownloadFormat.Text))

        assertThat(ring.status).isEqualTo(RingStatus.ResumeAt(50))
        assertThat(ring.enabled).isTrue()
    }

    // ---------- Which button is filled ----------

    @Test
    fun `the reading being the later, Read is filled`() {
        assertThat(filledKind(read(), listen(), readAt = later, listenAt = earlier)).isEqualTo(ActionKind.Read)
    }

    @Test
    fun `the listening being the later, Listen is filled`() {
        assertThat(filledKind(read(), listen(), readAt = earlier, listenAt = later)).isEqualTo(ActionKind.Listen)
    }

    @Test
    fun `a listening dated within the window after the reading comes from that reading, Read is filled`() {
        listOf(Duration.ofMillis(1), Duration.ofSeconds(4), Duration.ofSeconds(5)).forEach { after ->
            assertWithMessage("the listening dated $after after the reading")
                .that(filledKind(read(), listen(), readAt = earlier, listenAt = earlier + after))
                .isEqualTo(ActionKind.Read)
        }
    }

    @Test
    fun `a listening dated beyond the window after the reading, Listen is filled`() {
        val justOver = earlier + Duration.ofSeconds(5) + Duration.ofMillis(1)

        assertThat(filledKind(read(), listen(), readAt = earlier, listenAt = justOver)).isEqualTo(ActionKind.Listen)
    }

    @Test
    fun `the reading alone has a date, Read is filled`() {
        assertThat(filledKind(read(), listen(), readAt = earlier, listenAt = null)).isEqualTo(ActionKind.Read)
    }

    @Test
    fun `a book never read that was listened to, Listen is filled`() {
        assertThat(filledKind(read(), listen(), readAt = null, listenAt = earlier)).isEqualTo(ActionKind.Listen)
    }

    @Test
    fun `a reading started or finished whose date is unknown does not lose to a dated listening`() {
        listOf(RingStatus.Resume, RingStatus.ResumeAt(62), RingStatus.Again).forEach { status ->
            assertWithMessage("Read saying $status")
                .that(filledKind(read(status = status), listen(), readAt = null, listenAt = later))
                .isEqualTo(ActionKind.Read)
        }
    }

    @Test
    fun `a reading started whose date is known and older, Listen is filled`() {
        val started = read(status = RingStatus.ResumeAt(62))

        assertThat(filledKind(started, listen(), readAt = earlier, listenAt = later)).isEqualTo(ActionKind.Listen)
    }

    @Test
    fun `until the reading arrives, Read stays filled whatever the listening's date`() {
        assertThat(filledKind(read(), listen(), readAt = null, listenAt = later, readingArrived = false))
            .isEqualTo(ActionKind.Read)
        assertThat(filledKind(null, listen(), readAt = null, listenAt = later, readingArrived = false))
            .isEqualTo(ActionKind.Listen)
    }

    @Test
    fun `no date, Read is filled`() {
        assertThat(filledKind(read(), listen(), readAt = null, listenAt = null)).isEqualTo(ActionKind.Read)
    }

    @Test
    fun `the listening position still loading has no date, Read is filled`() {
        val waiting = actionRing(ActionKind.Listen, opens = true, RingStatus.None, kept = null).waiting()

        assertThat(filledKind(read(), waiting, readAt = null, listenAt = null)).isEqualTo(ActionKind.Read)
        assertThat(filledKind(read(), waiting, readAt = earlier, listenAt = null)).isEqualTo(ActionKind.Read)
    }

    @Test
    fun `the mode used last being switched off, the other button is filled`() {
        assertThat(filledKind(read(), listen(enabled = false), readAt = earlier, listenAt = later))
            .isEqualTo(ActionKind.Read)
        assertThat(filledKind(read(enabled = false), listen(), readAt = later, listenAt = earlier))
            .isEqualTo(ActionKind.Listen)
    }

    @Test
    fun `a single button is filled, whatever the dates`() {
        assertThat(filledKind(read(), null, readAt = earlier, listenAt = later)).isEqualTo(ActionKind.Read)
        assertThat(filledKind(null, listen(), readAt = later, listenAt = null)).isEqualTo(ActionKind.Listen)
        assertThat(filledKind(null, listen(), readAt = null, listenAt = null)).isEqualTo(ActionKind.Listen)
    }

    @Test
    fun `a switched-off button is never filled`() {
        assertThat(filledKind(read(enabled = false), listen(enabled = false), later, earlier)).isNull()
        assertThat(filledKind(read(enabled = false), null, later, null)).isNull()
        assertThat(filledKind(null, null, null, null)).isNull()
    }
}
