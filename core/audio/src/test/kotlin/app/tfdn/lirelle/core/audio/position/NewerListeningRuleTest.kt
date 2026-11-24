package app.tfdn.lirelle.core.audio.position

import app.tfdn.lirelle.core.audio.timeline.BookTimeline
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.AudioAsset
import app.tfdn.lirelle.core.library.model.AudioManifest
import app.tfdn.lirelle.core.library.model.PlaybackState
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import org.junit.Test

class NewerListeningRuleTest {

    private val t0 = Instant.parse("2026-11-15T10:00:00Z")
    private val rule = NewerListeningRule()

    /** The server's state: [positionMs] into [assetId], captured [afterSeconds] after t0. */
    private fun server(assetId: String, positionMs: Long, afterSeconds: Long?, version: String = "v2") =
        PlaybackState(assetId, positionMs, 0.0, false, afterSeconds?.let(t0::plusSeconds), version, SampleContent)

    private fun own(positionMs: Long, afterSeconds: Long) =
        samplePlayback(at = t0.plusSeconds(afterSeconds), positionMs = positionMs)

    @Test
    fun `a newer state far enough from the player is offered`() {
        rule.onOwnWrite(own(positionMs = 10_000, afterSeconds = 0))

        assertThat(rule.isOffered(server("aud_two", 600_000, afterSeconds = 60), Timeline, 10_000)).isTrue()
    }

    @Test
    fun `elsewhere is more than 5 seconds away, whatever the dates`() {
        rule.onOwnWrite(own(positionMs = 10_000, afterSeconds = 120))

        assertThat(rule.isElsewhere(server("aud_one", 15_001, afterSeconds = 60), Timeline, 10_000)).isTrue()
        assertThat(rule.isElsewhere(server("aud_one", 15_000, afterSeconds = 60), Timeline, 10_000)).isFalse()
        assertThat(rule.isElsewhere(server("aud_none", 600_000, afterSeconds = 60), Timeline, 10_000)).isFalse()
    }

    @Test
    fun `it is offered again as long as it was not answered`() {
        rule.onOwnWrite(own(positionMs = 10_000, afterSeconds = 0))
        val newer = server("aud_two", 600_000, afterSeconds = 60)

        assertThat(rule.isOffered(newer, Timeline, 10_000)).isTrue()
        assertThat(rule.isOffered(newer, Timeline, 10_000)).isTrue()
    }

    @Test
    fun `a state within 5 seconds of the player is not offered`() {
        rule.onOwnWrite(own(positionMs = 10_000, afterSeconds = 0))

        assertThat(rule.isOffered(server("aud_one", 15_000, afterSeconds = 60), Timeline, 10_000)).isFalse()
        assertThat(rule.isOffered(server("aud_one", 15_001, afterSeconds = 60), Timeline, 10_000)).isTrue()
    }

    @Test
    fun `book time decides, across files`() {
        // The end of the first file and the start of the second are the same place in the book.
        val firstFileEnd = Timeline.totalMs - SecondFileMs

        assertThat(rule.isOffered(server("aud_two", 2_000, afterSeconds = 60), Timeline, firstFileEnd)).isFalse()
    }

    @Test
    fun `a state older than this device's last write is not offered`() {
        rule.onOwnWrite(own(positionMs = 10_000, afterSeconds = 60))

        assertThat(rule.isOffered(server("aud_two", 600_000, afterSeconds = 30), Timeline, 10_000)).isFalse()
    }

    @Test
    fun `our own write given back is not offered`() {
        val written = own(positionMs = 400_000, afterSeconds = 60)
        rule.onOwnWrite(written)
        rule.onOwnWrite(own(positionMs = 10_000, afterSeconds = 30))

        // The player has gone back since; the server still holds the earlier write, with a later
        // version and even a later date.
        val echoed = server("aud_one", 400_000, afterSeconds = 90, version = "v9")
        assertThat(rule.isOffered(echoed, Timeline, 10_000)).isFalse()
    }

    @Test
    fun `an ignored state is not offered again, a newer one is`() {
        rule.onOwnWrite(own(positionMs = 10_000, afterSeconds = 0))
        val ignored = server("aud_two", 600_000, afterSeconds = 60)

        rule.ignore(ignored)

        assertThat(rule.isOffered(ignored, Timeline, 10_000)).isFalse()
        assertThat(rule.isOffered(server("aud_two", 700_000, afterSeconds = 60), Timeline, 10_000)).isFalse()
        assertThat(rule.isOffered(server("aud_two", 700_000, afterSeconds = 120), Timeline, 10_000)).isTrue()
    }

    @Test
    fun `the state read at opening is not offered`() {
        val read = server("aud_two", 600_000, afterSeconds = 60)

        rule.onRead(read)

        assertThat(rule.isOffered(read, Timeline, 10_000)).isFalse()
    }

    @Test
    fun `a state in a file the player does not know is not offered`() {
        assertThat(rule.isOffered(server("aud_gone", 600_000, afterSeconds = 60), Timeline, 10_000)).isFalse()
    }

    @Test
    fun `a state without a date elsewhere is someone else's, at one of our places it is ours`() {
        rule.onOwnWrite(own(positionMs = 10_000, afterSeconds = 120))

        assertThat(rule.isOffered(server("aud_two", 600_000, afterSeconds = null), Timeline, 10_000)).isTrue()
        rule.onOwnWrite(samplePlayback(at = t0.plusSeconds(130)).copy(assetId = "aud_two", positionMs = 600_000))
        assertThat(rule.isOffered(server("aud_two", 600_000, afterSeconds = null), Timeline, 10_000)).isFalse()
    }

    @Test
    fun `with nothing read or written yet, a state elsewhere is offered`() {
        assertThat(rule.isOffered(server("aud_two", 600_000, afterSeconds = 60), Timeline, 10_000)).isTrue()
        assertThat(rule.isOffered(server("aud_one", 60_000, afterSeconds = 60), Timeline, null)).isTrue()
        assertThat(rule.isOffered(server("aud_one", 4_000, afterSeconds = 60), Timeline, null)).isFalse()
    }

    private companion object {
        const val SecondFileMs = 3_600_000L
        val Timeline = BookTimeline(
            AudioManifest(
                bookId = SampleBook,
                contentVersion = SampleContent,
                title = "A Sample Book",
                authors = listOf("Ann Author"),
                narrators = emptyList(),
                hasCover = false,
                assets = listOf(
                    AudioAsset("aud_one", FileId("101"), 1, "mp3", 1_800_000, null, null),
                    AudioAsset("aud_two", FileId("102"), 2, "mp3", SecondFileMs, null, null),
                ),
                chapters = emptyList(),
                totalDurationMs = 5_400_000,
            ),
        )
    }
}
