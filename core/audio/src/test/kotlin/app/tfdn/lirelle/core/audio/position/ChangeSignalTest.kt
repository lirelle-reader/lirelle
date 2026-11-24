package app.tfdn.lirelle.core.audio.position

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.AudioRepository
import app.tfdn.lirelle.core.library.model.PlaybackWrite
import app.tfdn.lirelle.core.network.ApiError
import com.google.common.truth.Truth.assertThat
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** When the flusher tells the screens that show the reading: not at every tick. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChangeSignalTest : FlusherRig() {

    private var ticks = 0

    /** A tick's position goes out, as one does every 5 s while playing. */
    private suspend fun TestScope.tick(flusher: PlaybackFlusher) {
        ticks++
        outbox.enqueue(samplePlayback(at = position.at.plusSeconds(ticks * 5L), positionMs = 90_000L + ticks * 5_000))
        flusher.kick()
        runCurrent()
        advanceTimeBy(5.seconds)
    }

    @Test
    fun `the positions of the ticks are not told`() = runTest {
        repository.saveResults = listOf(saved("v1"))
        val flusher = flusher()

        repeat(times = 11) { tick(flusher) }

        assertThat(repository.writes).hasSize(11)
        assertThat(changes.version.value).isEqualTo(0)
    }

    @Test
    fun `while playing, the screens are told at most once a minute`() = runTest {
        repository.saveResults = listOf(saved("v1"))
        val flusher = flusher()

        // Two minutes and a half of ticks.
        repeat(times = 30) { tick(flusher) }

        assertThat(changes.version.value).isEqualTo(2)
    }

    @Test
    fun `the first save after a pause is told at once`() = runTest {
        repository.saveResults = listOf(saved("v1"))
        val flusher = flusher()
        tick(flusher)

        outbox.enqueue(samplePlayback(at = position.at.plusSeconds(7), positionMs = 97_000))
        flusher.settle()
        runCurrent()

        assertThat(changes.version.value).isEqualTo(1)
    }

    @Test
    fun `a pause where the last tick was is told too, though nothing more is sent`() = runTest {
        repository.saveResults = listOf(saved("v1"))
        val flusher = flusher()
        tick(flusher)

        flusher.settle()
        runCurrent()

        assertThat(repository.writes).hasSize(1)
        assertThat(changes.version.value).isEqualTo(1)
    }

    @Test
    fun `a pause is told once, and the ticks after it wait a minute again`() = runTest {
        repository.saveResults = listOf(saved("v1"))
        val flusher = flusher()
        tick(flusher)
        flusher.settle()
        runCurrent()
        assertThat(changes.version.value).isEqualTo(1)

        repeat(times = 11) { tick(flusher) }

        assertThat(changes.version.value).isEqualTo(1)
    }

    @Test
    fun `a pause whose position could not be sent waits for the save, which is then told`() = runTest {
        // The session ended: the flush stops without saving anything.
        repository.saveResults = listOf(failed(ApiError.SessionExpired), saved("v1"))
        val flusher = flusher()
        outbox.enqueue(position)
        flusher.settle()
        runCurrent()
        assertThat(changes.version.value).isEqualTo(0)

        flusher.kick()
        runCurrent()

        assertThat(changes.version.value).isEqualTo(1)
    }

    @Test
    fun `while a pause waits on a book that cannot be sent, the others are still told once a minute`() = runTest {
        val failing = object : AudioRepository by repository {
            override suspend fun savePlaybackState(bookId: BookId, write: PlaybackWrite) =
                if (bookId == SampleBook) failed(ApiError.ServerUnreachable) else saved("v1")
        }
        val flusher = flusher(audio = failing)
        outbox.enqueue(position)
        flusher.settle()
        runCurrent()

        // Another book plays for two minutes and a half.
        repeat(times = 30) {
            val other = samplePlayback(at = position.at.plusSeconds(it * 5L), positionMs = 1_000L * it)
            outbox.enqueue(other.copy(bookId = OtherBook))
            flusher.kick()
            runCurrent()
            advanceTimeBy(5.seconds)
        }

        assertThat(changes.version.value).isEqualTo(2)
    }
}
