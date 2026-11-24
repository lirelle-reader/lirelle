package app.tfdn.lirelle.core.audio.position

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.AudioRepository
import app.tfdn.lirelle.core.library.model.AudioAsset
import app.tfdn.lirelle.core.library.model.AudioManifest
import app.tfdn.lirelle.core.library.model.PlaybackState
import app.tfdn.lirelle.core.network.ApiResult
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** After a conflict: our own state told by its place as well as its date, a second reading, the same place. */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackFlusherOwnStateTest : FlusherRig() {

    /** The server's state is each of [states] in turn, the last one repeating. */
    private fun statesInTurn(vararg states: PlaybackState?) = object : AudioRepository by repository {
        private var read = 0

        override suspend fun playbackState(bookId: BookId): ApiResult<PlaybackState?> {
            repository.playbackState(bookId)
            return ApiResult.Success(states[read.coerceAtMost(states.lastIndex)]).also { read++ }
        }
    }

    /** The book of the samples: one file of 30 minutes, its manifest the one the positions were made with. */
    private val oneFile = AudioManifest(
        bookId = SampleBook,
        contentVersion = SampleContent,
        title = "A Sample Book",
        authors = listOf("Ann Author"),
        narrators = emptyList(),
        hasCover = false,
        assets = listOf(AudioAsset("aud_one", FileId("101"), 1, "mp3", 1_800_000, null, null)),
        chapters = emptyList(),
        totalDurationMs = 1_800_000,
    )

    private fun stateAt(version: String, positionMs: Long, capturedAt: Instant? = position.at.plusSeconds(60)) =
        PlaybackState("aud_one", positionMs, 1.0, false, capturedAt, version, SampleContent)

    @Test
    fun `the server moving between the reading and the resend is read once more, and the position saved on it`() =
        runTest {
            repository.saveResults = listOf(conflict, conflict, saved("v3"))
            // Our own states, the second written by our own earlier write landing late.
            val audio = statesInTurn(state("v1", capturedAt = position.at), state("v2", position.at))
            outbox.enqueue(position)

            flusher(audio).kick()
            runCurrent()

            assertThat(sentWrites.map { it.baseVersion }).containsExactly(null, "v1", "v2").inOrder()
            assertThat(conflicts).isEmpty()
            assertThat(outbox.positions).isEmpty()
            assertThat(store.knownVersion(SampleBook)).isEqualTo("v3")
        }

    @Test
    fun `a state at one of our places is ours, whatever its date says`() = runTest {
        store.setLastWritten(SampleBook, position.at.minusSeconds(600), WrittenPlace("aud_one", 600_000))
        repository.saveResults = listOf(conflict, saved("v5"))
        // Dated long after our position: its time was rewritten, or the clocks are apart.
        repository.playbackStateResult = ApiResult.Success(stateAt("v4", positionMs = 600_000))
        outbox.enqueue(position)

        flusher().kick()
        runCurrent()

        assertThat(sentWrites.map { it.baseVersion }).containsExactly(null, "v4").inOrder()
        assertThat(conflicts).isEmpty()
    }

    @Test
    fun `a state without a date at one of our places is ours, at another place it is not`() = runTest {
        store.setLastWritten(SampleBook, position.at.minusSeconds(600), WrittenPlace("aud_one", 600_000))
        repository.saveResults = listOf(conflict, saved("v5"), conflict)
        repository.playbackStateResult =
            ApiResult.Success(stateAt("v4", positionMs = 600_000, capturedAt = null))
        outbox.enqueue(position)
        val flusher = flusher()
        flusher.kick()
        runCurrent()
        assertThat(conflicts).isEmpty()

        repository.playbackStateResult =
            ApiResult.Success(stateAt("v6", positionMs = 900_000, capturedAt = null))
        outbox.enqueue(samplePlayback(at = position.at.plusSeconds(5), positionMs = 95_000))
        flusher.kick()
        runCurrent()

        assertThat(conflicts).containsExactly(SampleBook)
    }

    @Test
    fun `the places of our writes taken, or maybe taken, are kept`() = runTest {
        repository.saveResults = listOf(saved("v1"))
        outbox.enqueue(position)

        flusher().kick()
        runCurrent()

        assertThat(store.writtenPlaces(SampleBook)).containsExactly(WrittenPlace("aud_one", 90_000))
    }

    @Test
    fun `a state written elsewhere within 5 s of the position is followed, with no screen and no offer`() = runTest {
        repository.manifestResult = ApiResult.Success(oneFile)
        repository.saveResults = listOf(conflict, saved("v5"))
        // Another device, 3 s further, after our position.
        repository.playbackStateResult = ApiResult.Success(stateAt("v4", positionMs = 93_000))
        outbox.enqueue(position)

        flusher().kick()
        runCurrent()

        assertThat(sentWrites.map { it.baseVersion }).containsExactly(null, "v4").inOrder()
        assertThat(conflicts).isEmpty()
        assertThat(store.knownVersion(SampleBook)).isEqualTo("v5")
    }

    @Test
    fun `a state written elsewhere further than 5 s stands`() = runTest {
        repository.manifestResult = ApiResult.Success(oneFile)
        repository.saveResults = listOf(conflict)
        repository.playbackStateResult = ApiResult.Success(stateAt("v4", positionMs = 97_000))
        outbox.enqueue(position)

        flusher().kick()
        runCurrent()

        assertThat(repository.writes).hasSize(1)
        assertThat(conflicts).containsExactly(SampleBook)
    }

    @Test
    fun `without the position's manifest, a state elsewhere is not taken for the same place`() = runTest {
        repository.manifestResult = ApiResult.Success(oneFile.copy(contentVersion = NewContent))
        repository.saveResults = listOf(conflict)
        repository.playbackStateResult = ApiResult.Success(stateAt("v4", positionMs = 93_000))
        outbox.enqueue(position)

        flusher().kick()
        runCurrent()

        assertThat(conflicts).containsExactly(SampleBook)
    }
}
