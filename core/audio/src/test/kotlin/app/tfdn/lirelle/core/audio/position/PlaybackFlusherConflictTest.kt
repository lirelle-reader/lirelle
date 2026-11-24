package app.tfdn.lirelle.core.audio.position

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.AudioRepository
import app.tfdn.lirelle.core.library.model.PlaybackWrite
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import com.google.common.truth.Truth.assertThat
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** A 409: the server's state is read again, and decides whether our position is resent or dropped. */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackFlusherConflictTest : FlusherRig() {

    private val later = samplePlayback(at = position.at.plusSeconds(5), positionMs = 95_000)

    @Test
    fun `a write that landed though its answer was lost does not make the next one a conflict`() = runTest {
        // The server stored the first write as version v1, but the answer never came back.
        repository.saveResults = listOf(failed(ApiError.ServerUnreachable), conflict, saved("v2"))
        repository.playbackStateResult = ApiResult.Success(state("v1", capturedAt = position.at))
        outbox.enqueue(position)
        flusher().kick()
        runCurrent()

        outbox.enqueue(later)
        advanceTimeBy(5.seconds)
        runCurrent()

        assertThat(sentWrites.map { it.operationId }).containsExactly("op-90000", "op-95000", "op-95000").inOrder()
        assertThat(sentWrites.map { it.baseVersion }).containsExactly(null, null, "v1").inOrder()
        assertThat(conflicts).isEmpty()
        assertThat(outbox.positions).isEmpty()
        assertThat(store.knownVersion(SampleBook)).isEqualTo("v2")
        assertThat(changes.version.value).isEqualTo(0)
    }

    @Test
    fun `a write whose answer was lost after an acknowledged one counts as ours`() = runTest {
        // The server stored the second write as version v2, but the answer never came back.
        repository.saveResults = listOf(saved("v1"), failed(ApiError.ServerFailed), conflict, saved("v3"))
        val lost = samplePlayback(at = position.at.plusSeconds(5), positionMs = 95_000)
        repository.playbackStateResult = ApiResult.Success(state("v2", capturedAt = lost.at))
        outbox.enqueue(position)
        val flusher = flusher()
        flusher.kick()
        runCurrent()
        outbox.enqueue(lost)
        flusher.kick()
        runCurrent()

        outbox.enqueue(samplePlayback(at = position.at.plusSeconds(10), positionMs = 100_000))
        advanceTimeBy(5.seconds)
        runCurrent()

        assertThat(sentWrites.map { it.baseVersion }).containsExactly(null, "v1", "v1", "v2").inOrder()
        assertThat(conflicts).isEmpty()
        assertThat(outbox.positions).isEmpty()
        assertThat(store.knownVersion(SampleBook)).isEqualTo("v3")
    }

    @Test
    fun `a state captured when our last write was is ours, and the position goes on top of it`() = runTest {
        repository.saveResults = listOf(saved("v1"), conflict, saved("v3"))
        repository.playbackStateResult = ApiResult.Success(state("v2", capturedAt = position.at))
        outbox.enqueue(position)
        val flusher = flusher()
        flusher.kick()
        runCurrent()

        outbox.enqueue(later)
        flusher.kick()
        runCurrent()

        assertThat(sentWrites.drop(1).map { it.baseVersion }).containsExactly("v1", "v2").inOrder()
        assertThat(sentWrites.drop(1).map { it.operationId }).containsExactly("op-95000", "op-95000")
        assertThat(conflicts).isEmpty()
        assertThat(store.knownVersion(SampleBook)).isEqualTo("v3")
        // Ticks: the screens are told at most once a minute while playing, and not at the first one.
        assertThat(changes.version.value).isEqualTo(0)
    }

    @Test
    fun `a version learnt while a resend was on its way stays the known one`() = runTest {
        repository.saveResults = listOf(conflict, saved("v3"))
        repository.playbackStateResult = ApiResult.Success(state("v2", capturedAt = position.at))
        // Learnt from the server while the resend was on its way (an answer to an offer, a book opened): later
        // than what the resend's answer says. Versions are not ordered: what was learnt meanwhile is what stays.
        val learning = object : AudioRepository by repository {
            override suspend fun savePlaybackState(bookId: BookId, write: PlaybackWrite) =
                repository.savePlaybackState(bookId, write).also {
                    if (write.baseVersion == "v2") store.setKnownVersion(SampleBook, "v9")
                }
        }
        outbox.enqueue(position)

        flusher(audio = learning).kick()
        runCurrent()

        assertThat(sentWrites.map { it.baseVersion }).containsExactly(null, "v2").inOrder()
        assertThat(store.knownVersion(SampleBook)).isEqualTo("v9")
    }

    @Test
    fun `a state newer than our last acknowledged write is someone else's, and is offered`() = runTest {
        repository.saveResults = listOf(saved("v1"), conflict)
        // Written elsewhere after our first write, though before the queued position was made.
        repository.playbackStateResult = ApiResult.Success(state("v2", capturedAt = position.at.plusSeconds(2)))
        outbox.enqueue(position)
        val flusher = flusher()
        flusher.kick()
        runCurrent()

        outbox.enqueue(later)
        flusher.kick()
        advanceTimeBy(600.seconds)
        runCurrent()

        assertThat(repository.writes).hasSize(2)
        assertThat(conflicts).containsExactly(SampleBook)
        assertThat(outbox.positions).isEmpty()
        assertThat(store.knownVersion(SampleBook)).isEqualTo("v1")
    }

    @Test
    fun `without a write acknowledged since the start, the queued position's own time decides`() = runTest {
        repository.saveResults = listOf(conflict)
        repository.playbackStateResult = ApiResult.Success(state("v2", capturedAt = position.at.plusMillis(1)))
        outbox.enqueue(position)

        flusher().kick()
        runCurrent()

        assertThat(repository.writes).hasSize(1)
        assertThat(conflicts).containsExactly(SampleBook)
    }

    @Test
    fun `a state without a date is not taken for ours`() = runTest {
        repository.saveResults = listOf(conflict)
        repository.playbackStateResult = ApiResult.Success(state("v2", capturedAt = null))
        outbox.enqueue(position)

        flusher().kick()
        runCurrent()

        assertThat(repository.writes).hasSize(1)
        assertThat(conflicts).containsExactly(SampleBook)
        assertThat(outbox.positions).isEmpty()
    }

    @Test
    fun `a state reset elsewhere is created again`() = runTest {
        repository.saveResults = listOf(conflict, saved("n1"), saved("n2"))
        repository.playbackStateResult = ApiResult.Success(null)
        store.setKnownVersion(SampleBook, "v7")
        outbox.enqueue(position.copy(baseVersion = "v7"))
        val flusher = flusher()
        flusher.kick()
        runCurrent()

        // Created again: made over no version.
        assertThat(sentWrites.map { it.baseVersion }).containsExactly("v7", null).inOrder()
        assertThat(sentWrites.map { it.operationId }.distinct()).containsExactly("op-90000")
        assertThat(store.knownVersion(SampleBook)).isEqualTo("n1")
        assertThat(conflicts).isEmpty()

        // What follows is made over the new version, and the old ones lead nowhere any more.
        outbox.enqueue(later.copy(baseVersion = "n1"))
        flusher.kick()
        runCurrent()
        assertThat(sentWrites.last().baseVersion).isEqualTo("n1")
    }

    @Test
    fun `when the state cannot be read, the position stays and is retried with its operation id`() = runTest {
        repository.saveResults = listOf(conflict, saved("v1"))
        repository.playbackStateResult = failed(ApiError.ServerUnreachable)
        outbox.enqueue(position)
        flusher().kick()
        runCurrent()

        assertThat(outbox.positions).containsExactly(position)
        assertThat(conflicts).isEmpty()

        advanceTimeBy(5.seconds)
        runCurrent()

        assertThat(sentWrites.map { it.operationId }).containsExactly("op-90000", "op-90000")
        assertThat(outbox.positions).isEmpty()
    }

    @Test
    fun `a third conflict drops the position, and nothing loops`() = runTest {
        repository.saveResults = listOf(conflict)
        repository.playbackStateResult = ApiResult.Success(state("v1", capturedAt = position.at))
        outbox.enqueue(position)

        flusher().kick()
        advanceTimeBy(600.seconds)
        runCurrent()

        assertThat(repository.writes).hasSize(3)
        // Twice to recover, once for the state that stands against the dropped position.
        assertThat(repository.calls.count { it.startsWith("playbackState") }).isEqualTo(3)
        assertThat(conflicts).containsExactly(SampleBook)
        assertThat(outbox.positions).isEmpty()
    }

    @Test
    fun `after a restart, a queued position does not overwrite a state written elsewhere since our last write`() =
        runTest {
            // 10:00: our write is taken. Offline until 10:50, that position queued; another device writes at 10:30.
            // Each write's answer in turn: ours taken, then the queued one meets the other device's.
            repository.saveResults = listOf(saved("v1"), conflict, saved("v9"))
            outbox.enqueue(position)
            flusher().kick()
            runCurrent()
            val queued = samplePlayback(at = position.at.plusSeconds(3_000), positionMs = 120_000)
            outbox.enqueue(queued)

            // The app is killed and started again: a new flusher, only what was kept on the device.
            repository.playbackStateResult =
                ApiResult.Success(state("v2", capturedAt = position.at.plusSeconds(1_800)))
            val restarted = PlaybackFlusher(outbox, store, repository, backgroundScope, changes)
                .also { it.writes = writes }
            restarted.kick()
            runCurrent()

            assertThat(repository.writes).hasSize(2)
            assertThat(conflicts).containsExactly(SampleBook)
            assertThat(outbox.positions).isEmpty()
            assertThat(store.knownVersion(SampleBook)).isEqualTo("v1")
        }

    @Test
    fun `a write without an answer is kept as maybe taken across a restart`() = runTest {
        repository.saveResults = listOf(failed(ApiError.ServerUnreachable))
        outbox.enqueue(position)
        flusher().kick()
        runCurrent()

        assertThat(store.lastWritten(SampleBook)).isEqualTo(position.at)
    }

    @Test
    fun `a write that could not even connect is not one the server may have stored`() = runTest {
        // 10:00 taken; offline from then on, the attempts never leave; another device writes at 10:30.
        repository.saveResults = listOf(saved("v1"), ApiResult.Failure.neverSent(ApiError.ServerUnreachable), conflict)
        repository.playbackStateResult =
            ApiResult.Success(state("v2", capturedAt = position.at.plusSeconds(1_800)))
        outbox.enqueue(position)
        val flusher = flusher()
        flusher.kick()
        runCurrent()
        outbox.enqueue(samplePlayback(at = position.at.plusSeconds(2_700), positionMs = 120_000))
        flusher.kick()
        runCurrent()
        assertThat(store.lastWritten(SampleBook)).isEqualTo(position.at)

        // The network is back: the 10:45 position meets the other device's 10:30.
        advanceTimeBy(6.seconds)
        runCurrent()

        assertThat(repository.writes).hasSize(3)
        assertThat(conflicts).containsExactly(SampleBook)
        assertThat(outbox.positions).isEmpty()
    }

    @Test
    fun `a write that could not even connect is not one the server may have stored, after a restart too`() =
        runTest {
            repository.saveResults =
                listOf(saved("v1"), ApiResult.Failure.neverSent(ApiError.ServerUnreachable), conflict)
            repository.playbackStateResult =
                ApiResult.Success(state("v2", capturedAt = position.at.plusSeconds(1_800)))
            outbox.enqueue(position)
            val flusher = flusher()
            flusher.kick()
            runCurrent()
            outbox.enqueue(samplePlayback(at = position.at.plusSeconds(2_700), positionMs = 120_000))
            flusher.kick()
            runCurrent()

            // The app is killed offline and started again once the network is back.
            backgroundScope.coroutineContext.cancelChildren()
            val restarted = PlaybackFlusher(outbox, store, repository, backgroundScope, changes)
                .also { it.writes = writes }
            restarted.kick()
            runCurrent()

            assertThat(repository.writes).hasSize(3)
            assertThat(conflicts).containsExactly(SampleBook)
            assertThat(outbox.positions).isEmpty()
        }
}
