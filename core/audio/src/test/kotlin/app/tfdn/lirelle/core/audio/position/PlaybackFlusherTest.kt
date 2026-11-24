package app.tfdn.lirelle.core.audio.position

import app.tfdn.lirelle.core.library.model.PlaybackWrite
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import com.google.common.truth.Truth.assertThat
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackFlusherTest : FlusherRig() {

    @Test
    fun `nothing is sent before the first kick`() = runTest {
        outbox.enqueue(position)
        flusher()

        advanceTimeBy(60.seconds)

        assertThat(repository.calls).isEmpty()
    }

    @Test
    fun `a saved write leaves the outbox and its version becomes the known one`() = runTest {
        repository.saveResults = listOf(saved("v4"))
        outbox.enqueue(position.copy(baseVersion = "v3"))
        store.setKnownVersion(SampleBook, "v3")

        flusher().kick()
        runCurrent()

        assertThat(repository.writes).containsExactly(
            SampleBook to PlaybackWrite("aud_one", 90_000, position.at, "op-90000", "v3", SampleContent),
        )
        assertThat(outbox.positions).isEmpty()
        assertThat(store.knownVersion(SampleBook)).isEqualTo("v4")
    }

    @Test
    fun `a settled position is told to the screens once it is saved, not while it fails`() = runTest {
        val unreachable = failed(ApiError.ServerUnreachable)
        repository.saveResults = listOf(unreachable, saved("v1"))
        outbox.enqueue(position)
        val flusher = flusher()
        flusher.settle()
        runCurrent()
        assertThat(changes.version.value).isEqualTo(0)

        advanceTimeBy(5.seconds)
        runCurrent()
        assertThat(changes.version.value).isEqualTo(1)
    }

    @Test
    fun `a retry of the same position keeps its operation id, a new position has its own`() = runTest {
        val unreachable = failed(ApiError.ServerUnreachable)
        repository.saveResults = listOf(unreachable, unreachable, saved("v1"))
        outbox.enqueue(position)
        flusher().kick()
        runCurrent()
        advanceTimeBy(5.seconds)
        runCurrent()

        val later = samplePlayback(at = position.at.plusSeconds(5), positionMs = 95_000)
        outbox.enqueue(later)
        advanceTimeBy(10.seconds)
        runCurrent()

        assertThat(sentWrites.map { it.operationId }).containsExactly("op-90000", "op-90000", "op-95000").inOrder()
        assertThat(outbox.positions).isEmpty()
    }

    @Test
    fun `a position made while the one before was on its way is sent on top of it`() = runTest {
        val gate = CompletableDeferred<Unit>()
        repository.gate = gate
        repository.saveResults = listOf(saved("v1"), saved("v2"))
        outbox.enqueue(position)
        val flusher = flusher()
        flusher.kick()
        runCurrent()

        // Made before the server answered: it only knew of no version at all.
        val later = samplePlayback(at = position.at.plusSeconds(5), positionMs = 95_000)
        outbox.enqueue(later)
        repository.gate = null
        gate.complete(Unit)
        runCurrent()
        flusher.kick()
        runCurrent()

        assertThat(sentWrites.map { it.baseVersion }).containsExactly(null, "v1").inOrder()
        assertThat(store.knownVersion(SampleBook)).isEqualTo("v2")
        assertThat(outbox.positions).isEmpty()
    }

    @Test
    fun `a version written elsewhere is not skipped over`() = runTest {
        repository.saveResults = listOf(saved("v4"), conflict)
        // Another device wrote version v5 after our own write, later than it.
        repository.playbackStateResult = ApiResult.Success(state("v5", capturedAt = position.at.plusSeconds(2)))
        outbox.enqueue(position.copy(baseVersion = "v3"))
        val flusher = flusher()
        flusher.kick()
        runCurrent()

        // Made over v3 too, before our write was answered.
        outbox.enqueue(samplePlayback(at = position.at.plusSeconds(10), positionMs = 95_000).copy(baseVersion = "v3"))
        flusher.kick()
        runCurrent()

        assertThat(sentWrites.map { it.baseVersion }).containsExactly("v3", "v4").inOrder()
        assertThat(conflicts).containsExactly(SampleBook)
        assertThat(outbox.positions).isEmpty()
        assertThat(store.knownVersion(SampleBook)).isEqualTo("v4")
    }

    @Test
    fun `only the last eight of our own writes are followed`() = runTest {
        repository.saveResults = (1..11).map { saved("v$it") }
        val flusher = flusher()
        // Nine writes of ours: none → v1, v1 → v2… v8 → v9.
        val bases = listOf(null) + (1..8).map { "v$it" }
        for (base in bases) {
            outbox.enqueue(position.copy(baseVersion = base, operationId = "op-link-$base"))
            flusher.kick()
            runCurrent()
        }

        outbox.enqueue(position.copy(baseVersion = "v1", operationId = "op-from-1"))
        flusher.kick()
        runCurrent()
        outbox.enqueue(position.copy(baseVersion = null, operationId = "op-from-none"))
        flusher.kick()
        runCurrent()

        assertThat(sentWrites.takeLast(2).map { it.baseVersion }).containsExactly("v9", null).inOrder()
    }

    @Test
    fun `without network the position stays and is tried again after a growing wait`() = runTest {
        repository.saveResults = listOf(failed(ApiError.ServerUnreachable))
        outbox.enqueue(position)
        flusher().kick()
        runCurrent()

        advanceTimeBy(4.seconds)
        runCurrent()
        assertThat(repository.writes).hasSize(1)
        advanceTimeBy(1.seconds)
        runCurrent()
        assertThat(repository.writes).hasSize(2)
        advanceTimeBy(10.seconds)
        runCurrent()

        assertThat(repository.writes).hasSize(3)
        assertThat(outbox.positions).containsExactly(position)
    }

    @Test
    fun `a book that is gone drops its position at once`() = runTest {
        repository.saveResults = listOf(failed(ApiError.NotFound))
        outbox.enqueue(position)

        flusher().kick()
        advanceTimeBy(600.seconds)
        runCurrent()

        assertThat(repository.writes).hasSize(1)
        assertThat(outbox.positions).isEmpty()
    }

    @Test
    fun `a refused write is tried a few times, then dropped`() = runTest {
        repository.saveResults = listOf(failed(ApiError.Unexpected))
        outbox.enqueue(position)

        flusher().kick()
        advanceTimeBy(600.seconds)
        runCurrent()

        assertThat(repository.writes).hasSize(3)
        assertThat(outbox.positions).isEmpty()
        assertThat(changes.version.value).isEqualTo(0)
    }

    @Test
    fun `an ended session keeps the position and stops until the next kick`() = runTest {
        repository.saveResults = listOf(failed(ApiError.SessionExpired), saved("v1"))
        outbox.enqueue(position)
        val flusher = flusher()

        flusher.kick()
        advanceTimeBy(600.seconds)
        runCurrent()
        assertThat(repository.writes).hasSize(1)
        assertThat(outbox.positions).containsExactly(position)

        flusher.kick()
        runCurrent()
        assertThat(outbox.positions).isEmpty()
    }

    @Test
    fun `a kick during a flush does not start a second one`() = runTest {
        val gate = CompletableDeferred<Unit>()
        repository.gate = gate
        repository.saveResults = listOf(saved("v1"))
        outbox.enqueue(position)
        val flusher = flusher()

        flusher.kick()
        runCurrent()
        flusher.kick()
        flusher.kick()
        runCurrent()
        gate.complete(Unit)
        runCurrent()

        assertThat(repository.calls).containsExactly("savePlaybackState 12")
        assertThat(outbox.positions).isEmpty()
    }
}
