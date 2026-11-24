package app.tfdn.lirelle.core.audio.position

import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** What the flusher tells of this device's writes: taken, maybe taken, dropped on someone else's state. */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackFlusherWriteEventsTest : FlusherRig() {

    @Test
    fun `a saved write is told as acknowledged`() = runTest {
        repository.saveResults = listOf(saved("v1"))
        outbox.enqueue(position)

        flusher().kick()
        runCurrent()

        assertThat(writeEvents).containsExactly("written op-90000 true")
    }

    @Test
    fun `a write without an answer is told as maybe taken`() = runTest {
        repository.saveResults = listOf(failed(ApiError.ServerUnreachable))
        outbox.enqueue(position)

        flusher().kick()
        runCurrent()

        assertThat(writeEvents).contains("written op-90000 false")
    }

    @Test
    fun `a dropped write is told with the state that stands`() = runTest {
        repository.saveResults = listOf(conflict)
        repository.playbackStateResult = ApiResult.Success(state("v4", capturedAt = position.at.plusSeconds(1)))
        outbox.enqueue(position)

        flusher().kick()
        runCurrent()

        assertThat(writeEvents).contains("dropped op-90000 v4")
        assertThat(conflicts).containsExactly(SampleBook)
    }

    @Test
    fun `a write dropped on a third conflict is told with the state read again`() = runTest {
        repository.saveResults = listOf(conflict, conflict, conflict)
        repository.playbackStateResult = ApiResult.Success(state("v2", capturedAt = position.at))
        outbox.enqueue(position)

        flusher().kick()
        runCurrent()

        assertThat(writeEvents).contains("dropped op-90000 v2")
        assertThat(repository.calls.count { it == "playbackState 12" }).isEqualTo(3)
    }
}
