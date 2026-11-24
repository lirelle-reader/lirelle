package app.tfdn.lirelle.core.audio.position

import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.ReadingChanges
import app.tfdn.lirelle.core.library.model.NarrationPlace
import app.tfdn.lirelle.core.reader.sync.LocalNarration
import app.tfdn.lirelle.core.reader.sync.LocalPosition
import app.tfdn.lirelle.core.reader.sync.OutboxFlusher
import app.tfdn.lirelle.core.reader.sync.ReadingWrites
import app.tfdn.lirelle.core.testing.InMemoryOutbox
import com.google.common.truth.Truth.assertThat
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The reading and the listening of a read-along book made without network, sent when it is back to a
 * server that keeps both in step: what the user did last stands for the listening.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackAfterReadingTest : FlusherRig() {

    private val earlier = Instant.parse("2026-11-15T10:00:00Z")
    private val later = Instant.parse("2026-11-15T10:05:00Z")

    /** When the network is back, by the device's clock; the server's is 7 s ahead. */
    private val back = Instant.parse("2026-11-15T10:20:00Z")
    private val server = InStepServer(now = back.plusSeconds(7))
    private val readerOutbox = InMemoryOutbox()
    private val reading = ReadingWrites(readerOutbox)

    private fun narration(at: Instant) =
        LocalNarration(FileId("40"), NarrationPlace(310.0, "ch1.xhtml#s24", 2), 0.3, at)

    private fun listening(at: Instant) = samplePlayback(at, positionMs = 488_000)

    /** The network is back: both flushers are asked at once, the listening first. */
    private suspend fun TestScope.networkBack() {
        val listening = flusher(audio = server.audio).also { it.reading = reading }
        reading.onSent = { listening.kick() }
        val reader = OutboxFlusher(
            readerOutbox, server.reader, backgroundScope,
            Clock.fixed(back, ZoneOffset.UTC), ReadingChanges(), reading = reading,
        )
        listening.kick()
        reader.kick()
        runCurrent()
    }

    @Test
    fun `a narration, then the audiobook further on - the listening the user stopped at stands`() = runTest {
        readerOutbox.enqueuePosition(
            LocalPosition(FileId("41"), "epubcfi(/6/4!/4/2/1:0)", 0.24, earlier.minusSeconds(60)),
        )
        readerOutbox.enqueueNarration(narration(earlier))
        store.put(listening(later))
        outbox.enqueue(listening(later))

        networkBack()

        assertThat(server.calls)
            .containsExactly("reading", "reading", "listening refused", "listening 488000").inOrder()
        assertThat(server.state?.positionMs).isEqualTo(488_000)
        assertThat(server.state?.capturedAt).isEqualTo(later)
        assertThat(conflicts).isEmpty()
        assertThat(outbox.positions).isEmpty()
        assertThat(readerOutbox.narrations).isEmpty()
        assertThat(store.get(SampleBook)).isEqualTo(listening(later))
        assertThat(store.knownVersion(SampleBook)).isEqualTo("s3")
    }

    @Test
    fun `the audiobook, then a narration elsewhere - the listening follows the reading, and is offered`() = runTest {
        store.put(listening(earlier))
        outbox.enqueue(listening(earlier))
        readerOutbox.enqueueNarration(narration(later))

        networkBack()

        assertThat(server.calls).containsExactly("reading", "listening refused").inOrder()
        assertThat(server.state?.positionMs).isEqualTo(SentenceMs)
        // The older listening never overwrites it: the player offers the place read.
        assertThat(conflicts).containsExactly(SampleBook)
        assertThat(outbox.positions).isEmpty()
        assertThat(store.get(SampleBook)).isEqualTo(listening(earlier))
    }

    @Test
    fun `only the listening waited - it is written once, over its own version`() = runTest {
        outbox.enqueue(listening(later))

        networkBack()

        assertThat(server.calls).containsExactly("listening 488000")
        assertThat(conflicts).isEmpty()
        assertThat(store.knownVersion(SampleBook)).isEqualTo("s1")
    }

    @Test
    fun `only the reading waited - nothing is written for the listening`() = runTest {
        readerOutbox.enqueueNarration(narration(earlier))

        networkBack()

        assertThat(server.calls).containsExactly("reading")
        assertThat(server.state?.positionMs).isEqualTo(SentenceMs)
        assertThat(conflicts).isEmpty()
    }

    @Test
    fun `with a network, a reading sent as it is made still stands against the player's next write`() = runTest {
        readerOutbox.enqueueNarration(narration(back.minusSeconds(2)))
        outbox.enqueue(listening(back))

        networkBack()

        assertThat(server.calls).containsExactly("reading", "listening refused").inOrder()
        assertThat(server.state?.positionMs).isEqualTo(SentenceMs)
        assertThat(conflicts).containsExactly(SampleBook)
    }
}
