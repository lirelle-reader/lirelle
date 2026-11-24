package app.tfdn.lirelle.core.audio.position

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.AudioRepository
import app.tfdn.lirelle.core.library.MonotonicClock
import app.tfdn.lirelle.core.library.ReadingChanges
import app.tfdn.lirelle.core.library.model.PlaybackState
import app.tfdn.lirelle.core.library.model.PlaybackWrite
import app.tfdn.lirelle.core.library.model.PlaybackWriteResult
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.testing.FakeAudioRepository
import com.google.common.truth.Truth.assertThat
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The real flusher, outbox, store and conflicts together, against a fake server: a state written
 * elsewhere, "Ignore", and the writes made or on their way while the user answers.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackConflictFlowTest {

    private val t0 = Instant.parse("2026-11-15T10:00:00Z")
    private val now = Instant.parse("2026-11-15T12:00:00Z")
    private val repository = FakeAudioRepository()
    private val outbox = InMemoryPlaybackOutbox()
    private val store = InMemoryPlaybackStore()
    private lateinit var flusher: PlaybackFlusher
    private val conflicts = ListeningConflicts(store, outbox, Clock.fixed(now, ZoneOffset.UTC)) { flusher.kick() }

    /** Written elsewhere after our positions: version v5. */
    private val elsewhere = PlaybackState("aud_two", 30_000, 50.0, false, t0.plusSeconds(3_600), "v5", SampleContent)

    private fun saved(version: String) =
        ApiResult.Success(PlaybackWriteResult.Saved(elsewhere.copy(version = version, capturedAt = now)))

    private val conflict = ApiResult.Success(PlaybackWriteResult.Overtaken)

    private fun TestScope.start() {
        flusher = PlaybackFlusher(outbox, store, repository, backgroundScope, ReadingChanges())
            .also { it.writes = conflicts }
    }

    /** This device's position [seconds] after t0, stored and queued as the writer does, on [base]. */
    private suspend fun tick(seconds: Long, base: String? = null): LocalPlayback {
        val position = samplePlayback(at = t0.plusSeconds(seconds), positionMs = 90_000 + seconds * 1_000)
            .copy(operationId = "op-$seconds", baseVersion = base)
        store.put(position)
        outbox.enqueue(position)
        flusher.kick()
        return position
    }

    private fun TestScope.conflicted() {
        start()
        repository.saveResults = listOf(conflict)
        repository.playbackStateResult = ApiResult.Success(elsewhere)
    }

    @Test
    fun `a state written elsewhere stands, and ignore makes the device's position win`() = runTest {
        conflicted()
        tick(0)
        runCurrent()
        assertThat(conflicts.standing.value).containsExactly(SampleBook, elsewhere)

        repository.saveResults = listOf(saved("v6"))
        conflicts.keepOurs(SampleBook, version = "v5")
        runCurrent()

        assertThat(repository.writes.map { it.second.baseVersion }).containsExactly(null, "v5").inOrder()
        assertThat(conflicts.standing.value).isEmpty()
        assertThat(store.knownVersion(SampleBook)).isEqualTo("v6")
        assertThat(outbox.positions).isEmpty()
    }

    @Test
    fun `a position made over the version before ignore is sent over the ignored one`() = runTest {
        conflicted()
        tick(0)
        runCurrent()
        conflicts.keepOurs(SampleBook, version = "v5")
        // The writer read the known version before "Ignore", and its position replaces the one queued.
        repository.saveResults = listOf(saved("v6"))
        tick(5, base = null)
        runCurrent()

        assertThat(repository.writes.last().second.baseVersion).isEqualTo("v5")
        assertThat(repository.writes.last().second.operationId).isEqualTo("op-5")
        assertThat(conflicts.standing.value).isEmpty()
        assertThat(outbox.positions).isEmpty()
    }

    @Test
    fun `a write on its way when the user ignores does not bring the conflict back`() = runTest {
        conflicted()
        tick(0)
        runCurrent()
        assertThat(conflicts.standing.value).containsKey(SampleBook)

        // A new position goes out, held on its way...
        val gate = CompletableDeferred<Unit>()
        repository.gate = gate
        tick(5)
        runCurrent()
        // ...when the user ignores the offer.
        conflicts.keepOurs(SampleBook, version = "v5")
        repository.saveResults = listOf(conflict, saved("v6"))
        repository.gate = null
        gate.complete(Unit)
        runCurrent()

        assertThat(conflicts.standing.value).isEmpty()
        assertThat(repository.writes.last().second.baseVersion).isEqualTo("v5")
        assertThat(store.knownVersion(SampleBook)).isEqualTo("v6")
        assertThat(outbox.positions).isEmpty()
    }

    @Test
    fun `while the state stands nothing is sent, and after go there the position held is not written over it`() =
        runTest {
            conflicted()
            tick(0)
            runCurrent()

            tick(5)
            runCurrent()
            assertThat(repository.writes).hasSize(1)
            assertThat(outbox.positions.single().operationId).isEqualTo("op-5")

            conflicts.followServer(SampleBook, version = "v5")
            flusher.kick()
            runCurrent()

            assertThat(conflicts.standing.value).isEmpty()
            assertThat(repository.writes.map { it.second.baseVersion }).containsExactly(null, null)
            assertThat(outbox.positions).isEmpty()
        }

    /** The app's writer over this flusher. */
    private suspend fun TestScope.writer(): PositionWriter =
        PositionWriter(store, outbox, flusher, MonotonicClock { currentTime })

    /** The player plays on for [seconds], ticking every 5 s as the session loop does. */
    private suspend fun TestScope.play(writer: PositionWriter, seconds: Int) {
        repeat(seconds / 5) {
            advanceTimeBy(5_000)
            val at = currentTime
            writer.onTick(samplePlayback(at = t0.plusMillis(at), positionMs = 90_000 + at), tickedAt = at)
            runCurrent()
        }
    }

    @Test
    fun `while the state stands nothing is sent, and two minutes of playing send the latest position on it`() =
        runTest {
            conflicted()
            tick(0)
            runCurrent()
            val writer = writer()

            // 24 ticks: 115 s of listening between the first and the last.
            play(writer, 120)
            assertThat(repository.writes).hasSize(1)
            assertThat(conflicts.standing.value).containsKey(SampleBook)

            repository.saveResults = listOf(conflict, saved("v6"))
            play(writer, 5)

            assertThat(repository.writes).hasSize(2)
            assertThat(repository.writes.last().second.baseVersion).isEqualTo("v5")
            assertThat(repository.writes.last().second.positionMs).isEqualTo(90_000 + currentTime)
            assertThat(conflicts.standing.value).isEmpty()
            assertThat(store.knownVersion(SampleBook)).isEqualTo("v6")
        }

    @Test
    fun `a pause while the state stands adds nothing to the two minutes`() = runTest {
        conflicted()
        tick(0)
        runCurrent()
        val writer = writer()

        play(writer, 60)
        writer.flushNow(samplePlayback(at = t0.plusMillis(currentTime), positionMs = 90_000 + currentTime))
        advanceTimeBy(3_600_000)
        play(writer, 55)

        assertThat(repository.writes).hasSize(1)
        assertThat(conflicts.standing.value).containsKey(SampleBook)
    }

    @Test
    fun `the two minutes count while a failing session keeps the flusher waiting`() = runTest {
        start()
        flusher.sessions = InMemorySessionQueue().apply { enqueue(FileId("50"), sampleSession()) }
        repository.saveSessionResult = ApiResult.Failure(ApiError.ServerUnreachable)
        repository.saveResults = listOf(conflict)
        repository.playbackStateResult = ApiResult.Success(elsewhere)
        tick(0)
        runCurrent()
        val writer = writer()

        repository.saveResults = listOf(conflict, saved("v6"))
        play(writer, 125)

        assertThat(repository.writes.map { it.second.baseVersion }).containsExactly(null, "v5").inOrder()
        assertThat(conflicts.standing.value).isEmpty()
    }

    @Test
    fun `ignore answered while a resend goes over the same state - the resend's version is known, no round trip`() =
        runTest {
            // The offered state S, this device's own (captured when its position was): an overtaken position is
            // resent over it. While the resend is on its way, the user keeps theirs on S (the offer made at the
            // front): S becomes the known version and a next write waits over S. The resend saved as V
            // necessarily overwrote S: V is the newer, and the next write goes over it at once.
            val offered = PlaybackState("aud_one", 90_000, 1.0, false, t0, "S", SampleContent)
            repository.playbackStateResult = ApiResult.Success(offered)
            repository.saveResults = listOf(conflict, saved("V"), saved("W"))
            val answering = object : AudioRepository by repository {
                override suspend fun savePlaybackState(bookId: BookId, write: PlaybackWrite) =
                    repository.savePlaybackState(bookId, write).also {
                        if (write.baseVersion == "S" && repository.writes.size == 2) conflicts.keepOurs(bookId, "S")
                    }
            }
            flusher = PlaybackFlusher(outbox, store, answering, backgroundScope, ReadingChanges())
                .also { it.writes = conflicts }

            tick(0)
            runCurrent()

            assertThat(repository.writes.map { it.second.baseVersion }).containsExactly(null, "S", "V").inOrder()
            assertThat(store.knownVersion(SampleBook)).isEqualTo("W")
            assertThat(outbox.positions).isEmpty()
            assertThat(conflicts.standing.value).isEmpty()
        }
}
