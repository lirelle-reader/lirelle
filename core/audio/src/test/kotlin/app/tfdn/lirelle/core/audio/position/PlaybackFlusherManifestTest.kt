package app.tfdn.lirelle.core.audio.position

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.AudioRepository
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import com.google.common.truth.Truth.assertThat
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** A 412: the book's files changed, and the position is placed again by its book time. */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackFlusherManifestTest : FlusherRig() {

    @Test
    fun `a changed manifest places the position again by book time, and sends it at once`() = runTest {
        repository.saveResults = listOf(manifestChanged, saved("v1"))
        repository.manifestResult = ApiResult.Success(newFiles())
        // 90 s into the book: 30 s into the second file of the new set.
        outbox.enqueue(position)

        flusher().kick()
        runCurrent()

        assertThat(repository.calls)
            .containsExactly("savePlaybackState 12", "manifest 12", "savePlaybackState 12").inOrder()
        val moved = sentWrites.last()
        assertThat(moved.assetId).isEqualTo("aud_new2")
        assertThat(moved.positionMs).isEqualTo(30_000)
        assertThat(moved.contentVersion).isEqualTo(NewContent)
        assertThat(moved.capturedAt).isEqualTo(position.at)
        assertThat(moved.operationId).isNotEqualTo(position.operationId)
        assertThat(manifestChanges).containsExactly(SampleBook)
        assertThat(outbox.positions).isEmpty()
    }

    @Test
    fun `a changed manifest that cannot be read keeps the position for later`() = runTest {
        repository.saveResults = listOf(manifestChanged)
        repository.manifestResult = failed(ApiError.ServerUnreachable)
        outbox.enqueue(position)

        flusher().kick()
        runCurrent()

        assertThat(outbox.positions).containsExactly(position)
        assertThat(manifestChanges).containsExactly(SampleBook)

        repository.saveResults = listOf(manifestChanged, saved("v1"))
        repository.writes.clear()
        repository.manifestResult = ApiResult.Success(newFiles())
        advanceTimeBy(5.seconds)
        runCurrent()

        assertThat(outbox.positions).isEmpty()
    }

    @Test
    fun `a position past the end of a shorter book is dropped, not put at its end`() = runTest {
        repository.saveResults = listOf(manifestChanged, saved("v1"))
        // The new set ends exactly where the position is: at its end, the book would count as read.
        repository.manifestResult = ApiResult.Success(newFiles(count = 1, fileMs = 90_000))
        outbox.enqueue(position)

        flusher().kick()
        advanceTimeBy(600.seconds)
        runCurrent()

        assertThat(repository.writes).hasSize(1)
        assertThat(outbox.positions).isEmpty()
        assertThat(manifestChanges).containsExactly(SampleBook)
        // The state on the server may well be this device's own: no conflict is told to stand.
        assertThat(conflicts).isEmpty()
    }

    @Test
    fun `a book whose length is unknown takes the position where it is`() = runTest {
        repository.saveResults = listOf(manifestChanged, saved("v1"))
        val unknown = newFiles(count = 1).let { files ->
            files.copy(assets = files.assets.map { it.copy(durationMs = null) }, totalDurationMs = 0)
        }
        repository.manifestResult = ApiResult.Success(unknown)
        outbox.enqueue(position)

        flusher().kick()
        runCurrent()

        assertThat(sentWrites.last().assetId).isEqualTo("aud_new1")
        assertThat(sentWrites.last().positionMs).isEqualTo(90_000)
        assertThat(conflicts).isEmpty()
        assertThat(outbox.positions).isEmpty()
    }

    @Test
    fun `a manifest that cannot be fetched for a while does not count as changing`() = runTest {
        repository.saveResults = List(6) { manifestChanged } + saved("v1")
        var fetches = 0
        val slow = object : AudioRepository by repository {
            override suspend fun manifest(bookId: BookId) =
                if (++fetches <= 5) failed(ApiError.ServerUnreachable) else ApiResult.Success(newFiles())
        }
        outbox.enqueue(position)

        flusher(slow).kick()
        advanceTimeBy(600.seconds)
        runCurrent()

        assertThat(repository.writes).hasSize(7)
        assertThat(outbox.positions).isEmpty()
    }

    @Test
    fun `a manifest that keeps changing is given up after a few rounds, and the position kept`() = runTest {
        repository.saveResults = listOf(manifestChanged)
        repository.manifestResult = ApiResult.Success(newFiles())
        outbox.enqueue(position)

        flusher().kick()
        advanceTimeBy(600.seconds)
        runCurrent()

        assertThat(repository.writes).hasSize(4)
        assertThat(repository.calls.count { it.startsWith("manifest") }).isEqualTo(3)
        assertThat(outbox.positions).hasSize(1)
        assertThat(outbox.positions.single().assetId).isEqualTo("aud_new2")
    }

    @Test
    fun `a manifest that cannot be read for an unclear reason is given up, the changed files notwithstanding`() =
        runTest {
            repository.saveResults = listOf(manifestChanged)
            repository.manifestResult = failed(ApiError.Unexpected)
            outbox.enqueue(position)

            flusher().kick()
            advanceTimeBy(600.seconds)
            runCurrent()

            assertThat(repository.writes).hasSize(3)
            assertThat(outbox.positions).isEmpty()
        }

    @Test
    fun `a position saved after a change counts the rounds from zero again`() = runTest {
        repository.saveResults = listOf(manifestChanged, manifestChanged, manifestChanged, saved("v1"))
        repository.manifestResult = ApiResult.Success(newFiles())
        outbox.enqueue(position)
        val flusher = flusher()
        flusher.kick()
        advanceTimeBy(600.seconds)
        runCurrent()
        assertThat(outbox.positions).isEmpty()

        repository.saveResults = listOf(manifestChanged, manifestChanged, manifestChanged, manifestChanged, saved("v2"))
        repository.writes.clear()
        outbox.enqueue(samplePlayback(at = position.at.plusSeconds(5), positionMs = 95_000))
        flusher.kick()
        advanceTimeBy(600.seconds)
        runCurrent()

        assertThat(repository.writes).hasSize(4)
        assertThat(outbox.positions).hasSize(1)
    }
}
