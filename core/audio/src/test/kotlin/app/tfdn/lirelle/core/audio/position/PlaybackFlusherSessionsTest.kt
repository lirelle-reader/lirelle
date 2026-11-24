package app.tfdn.lirelle.core.audio.position

import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import com.google.common.truth.Truth.assertThat
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackFlusherSessionsTest : FlusherRig() {

    @Test
    fun `listening sessions are declared after the positions, then leave the queue`() = runTest {
        repository.saveResults = listOf(saved("v1"))
        outbox.enqueue(position)
        sessions.enqueue(FileId("50"), sampleSession())
        val flusher = flusher()

        flusher.kick()
        runCurrent()

        assertThat(repository.calls.map { it.substringBefore(' ') })
            .containsExactly("savePlaybackState", "saveListeningSession").inOrder()
        assertThat(repository.savedSessions.single().first).isEqualTo(FileId("50"))
        assertThat(sessions.pending()).isEmpty()
    }

    @Test
    fun `while a position cannot be sent, the sessions wait behind it`() = runTest {
        repository.saveResults = listOf(failed(ApiError.ServerUnreachable), saved("v1"))
        outbox.enqueue(position)
        sessions.enqueue(FileId("50"), sampleSession())
        val flusher = flusher()

        flusher.kick()
        runCurrent()
        assertThat(repository.savedSessions).isEmpty()

        advanceTimeBy(6.seconds)
        runCurrent()
        assertThat(repository.savedSessions).hasSize(1)
    }

    @Test
    fun `a session that cannot be sent is tried again, one the server does not know is given up`() = runTest {
        sessions.enqueue(FileId("50"), sampleSession())
        sessions.enqueue(FileId("51"), sampleSession(id = "gone"))
        repository.saveSessionResult = ApiResult.Failure(ApiError.ServerUnreachable)
        val flusher = flusher()

        flusher.kick()
        runCurrent()
        assertThat(sessions.pending()).hasSize(2)

        repository.saveSessionResult = ApiResult.Failure(ApiError.NotFound)
        advanceTimeBy(6.seconds)
        runCurrent()
        assertThat(sessions.pending()).isEmpty()
    }
}
