package app.tfdn.lirelle.core.audio.position

import app.tfdn.lirelle.core.library.model.PlaybackState
import com.google.common.truth.Truth.assertThat
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ListeningConflictsTest {

    private val start = Instant.parse("2026-11-15T10:00:00Z")
    private val now = Instant.parse("2026-11-15T12:00:00Z")
    private val store = InMemoryPlaybackStore()
    private val outbox = InMemoryPlaybackOutbox()
    private var kicks = 0
    private val conflicts = ListeningConflicts(store, outbox, Clock.fixed(now, ZoneOffset.UTC)) { kicks++ }

    /** Someone else's state of book 12, in [version]. */
    private fun elsewhere(version: String = "v5") =
        PlaybackState("aud_two", 30_000, 50.0, false, start.minusSeconds(600), version, SampleContent)

    /** This device's write made [seconds] after [start], dropped on [server]. */
    private suspend fun droppedAt(seconds: Long, server: PlaybackState? = elsewhere()) {
        val position = samplePlayback(at = start.plusSeconds(seconds), positionMs = 90_000 + seconds * 1_000)
        store.put(position)
        conflicts.dropped(position, server)
    }

    @Test
    fun `a dropped write leaves the server's state standing for the book`() = runTest {
        droppedAt(0)

        assertThat(conflicts.standing.value).containsExactly(SampleBook, elsewhere())
    }

    @Test
    fun `a write of ours the server took ends the conflict`() = runTest {
        droppedAt(0)

        conflicts.written(samplePlayback(at = start.plusSeconds(5)), acknowledged = true)

        assertThat(conflicts.standing.value).isEmpty()
    }

    @Test
    fun `a write whose answer was lost does not end it, but counts as written`() = runTest {
        droppedAt(0)
        val lost = samplePlayback(at = start.plusSeconds(5))

        conflicts.written(lost, acknowledged = false)

        assertThat(conflicts.standing.value).containsKey(SampleBook)
        assertThat(conflicts.ownWrites(SampleBook).first()).isEqualTo(lost)
    }

    @Test
    fun `the last write is the latest the server took or may have taken`() = runTest {
        val first = samplePlayback(at = start)
        val second = samplePlayback(at = start.plusSeconds(5), positionMs = 95_000)

        conflicts.written(second, acknowledged = true)
        conflicts.written(first, acknowledged = false)

        assertThat(conflicts.ownWrites(SampleBook).first()).isEqualTo(second)
        assertThat(conflicts.ownWrites(OtherBook)).isEmpty()
    }

    @Test
    fun `after a restart, the last writes are their places, and when the last was captured`() = runTest {
        store.setLastWritten(SampleBook, start)

        store.setLastWritten(SampleBook, start, WrittenPlace("aud_two", 30_000))

        val writes = conflicts.ownWrites(SampleBook)

        assertThat(writes.map { it.assetId to it.positionMs })
            .containsExactly("aud_two" to 30_000L, "" to -1L).inOrder()
        assertThat(writes.map { it.at }.distinct()).containsExactly(start)
    }

    @Test
    fun `keeping ours makes the version known and writes the latest position at once over it`() = runTest {
        droppedAt(0)

        conflicts.keepOurs(SampleBook, version = "v5")

        assertThat(store.knownVersion(SampleBook)).isEqualTo("v5")
        val written = outbox.positions.single()
        assertThat(written.baseVersion).isEqualTo("v5")
        assertThat(written.positionMs).isEqualTo(90_000)
        assertThat(written.at).isEqualTo(now)
        assertThat(written.operationId).isNotEqualTo(samplePlayback().operationId)
        assertThat(kicks).isEqualTo(1)
        assertThat(conflicts.standing.value).isEmpty()
    }

    @Test
    fun `following the server makes its version known and writes nothing`() = runTest {
        droppedAt(0)

        conflicts.followServer(SampleBook, version = "v5")

        assertThat(store.knownVersion(SampleBook)).isEqualTo("v5")
        assertThat(outbox.positions).isEmpty()
        assertThat(conflicts.standing.value).isEmpty()
    }

    /** The player ticks every 5 s while playing, from [fromSeconds] to [toSeconds], storing its position. */
    private suspend fun playing(fromSeconds: Long, toSeconds: Long, every: Long = 5) {
        (fromSeconds..toSeconds step every).forEach { seconds ->
            store.put(samplePlayback(at = start.plusSeconds(seconds), positionMs = 90_000 + seconds * 1_000))
            conflicts.ticked(SampleBook, seconds * 1_000)
        }
    }

    @Test
    fun `a known state written elsewhere holds the book's positions back, an unread one does not`() = runTest {
        assertThat(conflicts.holds(SampleBook)).isFalse()
        droppedAt(0, server = null)
        assertThat(conflicts.holds(SampleBook)).isFalse()

        droppedAt(5)

        assertThat(conflicts.holds(SampleBook)).isTrue()
        assertThat(conflicts.holds(OtherBook)).isFalse()
    }

    @Test
    fun `two minutes of listening while it stands keep ours, with the latest position`() = runTest {
        droppedAt(0)
        playing(0, 115)
        assertThat(conflicts.standing.value).containsKey(SampleBook)

        playing(120, 120)

        assertThat(conflicts.standing.value).isEmpty()
        assertThat(conflicts.holds(SampleBook)).isFalse()
        assertThat(store.knownVersion(SampleBook)).isEqualTo("v5")
        val written = outbox.positions.single()
        assertThat(written.baseVersion).isEqualTo("v5")
        assertThat(written.positionMs).isEqualTo(210_000)
    }

    @Test
    fun `ticks further apart, a stalled player that played on, count all the listening between them`() = runTest {
        droppedAt(0)
        playing(0, 100, every = 20)
        assertThat(conflicts.standing.value).containsKey(SampleBook)

        playing(120, 120)

        assertThat(conflicts.standing.value).isEmpty()
    }

    @Test
    fun `a pause adds nothing to the listening`() = runTest {
        droppedAt(0)
        playing(0, 60)
        // Paused for an hour: the stretch ends; the ticks start again after it.
        conflicts.ticked(SampleBook, null)
        playing(3_600, 3_655)
        assertThat(conflicts.standing.value).containsKey(SampleBook)

        playing(3_660, 3_660)

        assertThat(conflicts.standing.value).isEmpty()
    }

    @Test
    fun `listening before the state stood does not count`() = runTest {
        playing(0, 600)
        droppedAt(600)
        playing(605, 715)

        assertThat(conflicts.standing.value).containsKey(SampleBook)
    }

    @Test
    fun `another state written elsewhere starts the count again`() = runTest {
        droppedAt(0)
        playing(0, 60)
        droppedAt(60, elsewhere(version = "v6"))
        playing(65, 120)

        assertThat(conflicts.standing.value).containsExactly(SampleBook, elsewhere(version = "v6"))
    }

    @Test
    fun `without the server's state nothing is kept by itself`() = runTest {
        droppedAt(0, server = null)
        playing(0, 300)

        assertThat(conflicts.standing.value).containsKey(SampleBook)
        assertThat(outbox.positions).isEmpty()
    }

    @Test
    fun `a state in the known version is not written elsewhere`() = runTest {
        // This device's own last write, remembered across a restart by the known version.
        store.setKnownVersion(SampleBook, "v5")

        droppedAt(0)

        assertThat(conflicts.standing.value).isEmpty()
        assertThat(outbox.positions).isEmpty()
    }

    @Test
    fun `a state in another version than the known one was written elsewhere, and stands`() = runTest {
        // Versions are not ordered: whatever the text, a version that is not the known one was written elsewhere
        // (with BookOrbit's numbers, a lower one is a state created again, a higher one a later write).
        store.setKnownVersion(SampleBook, "v9")

        droppedAt(0)

        assertThat(conflicts.standing.value).containsKey(SampleBook)

        conflicts.forget(SampleBook)
        store.setKnownVersion(SampleBook, "v4")

        droppedAt(5)

        assertThat(conflicts.standing.value).containsKey(SampleBook)
    }

    @Test
    fun `after ignore, a write dropped on the ignored state is made again on it, and nothing stands`() = runTest {
        droppedAt(0)
        conflicts.keepOurs(SampleBook, version = "v5")
        outbox.markSent(outbox.positions.single())

        // Made over the version before "Ignore", or already on its way: dropped against the same state.
        droppedAt(5)

        assertThat(conflicts.standing.value).isEmpty()
        val again = outbox.positions.single()
        assertThat(again.baseVersion).isEqualTo("v5")
        assertThat(again.positionMs).isEqualTo(95_000)
        assertThat(kicks).isEqualTo(2)
    }

    @Test
    fun `after go there, a write dropped on the followed state neither stands nor is made again`() = runTest {
        droppedAt(0)
        conflicts.followServer(SampleBook, version = "v5")

        droppedAt(5)

        assertThat(conflicts.standing.value).isEmpty()
        assertThat(outbox.positions).isEmpty()
    }

    @Test
    fun `after ignore, a position made over an older base is sent over the ignored version`() = runTest {
        droppedAt(0)
        conflicts.keepOurs(SampleBook, version = "v5")

        // Older: not the known version (made before the answer, over what was known then, or over none).
        assertThat(conflicts.baseFor(samplePlayback(), base = "v3")).isEqualTo("v5")
        assertThat(conflicts.baseFor(samplePlayback(), base = null)).isEqualTo("v5")
        assertThat(conflicts.baseFor(samplePlayback().copy(bookId = OtherBook), base = "v3")).isEqualTo("v3")
        // This device's own write over it got the next version, now the known one: positions go over that one.
        store.setKnownVersion(SampleBook, "v6")
        assertThat(conflicts.baseFor(samplePlayback(), base = "v6")).isEqualTo("v6")
        assertThat(conflicts.baseFor(samplePlayback(), base = "v3")).isEqualTo("v5")
    }

    @Test
    fun `after go there, an older base is not replaced`() = runTest {
        droppedAt(0)
        conflicts.followServer(SampleBook, version = "v5")

        assertThat(conflicts.baseFor(samplePlayback(), base = "v3")).isEqualTo("v3")
    }

    @Test
    fun `a newer state written elsewhere after ignore stands again`() = runTest {
        droppedAt(0)
        conflicts.keepOurs(SampleBook, version = "v5")

        droppedAt(5, elsewhere(version = "v6"))

        assertThat(conflicts.standing.value).containsExactly(SampleBook, elsewhere(version = "v6"))
        assertThat(conflicts.baseFor(samplePlayback(), base = "v3")).isEqualTo("v3")
    }

    @Test
    fun `forgetting a book drops what stands for it`() = runTest {
        droppedAt(0)
        conflicts.written(samplePlayback(at = start.minusSeconds(60)), acknowledged = false)

        conflicts.forget(SampleBook)

        assertThat(conflicts.standing.value).isEmpty()
    }
}
