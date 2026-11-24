package app.tfdn.lirelle.core.audio.position

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.ids.ReadId
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.testing.FakeReaderRepository
import com.google.common.truth.Truth.assertThat
import app.tfdn.lirelle.core.library.ReaderRepository
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** The finished reading from before is captured before the first position of a book goes; and a sign-out mid-flight. */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackFlusherBaselineTest : FlusherRig() {

    private val reader = FakeReaderRepository()
    private val baselines = ReadingBaselines(reader)

    @Test
    fun `the first position of a book captures its finished reading from before, once`() = runTest {
        reader.finishedReadingResult = ApiResult.Success(ReadId("3"))
        repository.saveResults = listOf(saved("v1"), saved("v2"))
        outbox.enqueue(position)
        val flusher = flusher(baselines = baselines)
        flusher.kick()
        runCurrent()

        reader.finishedReadingResult = ApiResult.Success(ReadId("9"))
        outbox.enqueue(samplePlayback(at = position.at.plusSeconds(5), positionMs = 95_000))
        flusher.kick()
        runCurrent()

        assertThat(baselines.of(SampleBook)).isEqualTo(ReadingBaseline(ReadId("3")))
        assertThat(reader.calls).containsExactly("finishedReadingId 12")
        assertThat(repository.writes).hasSize(2)
    }

    @Test
    fun `without the network the baseline waits, and so does the position`() = runTest {
        reader.finishedReadingResult = ApiResult.Failure(ApiError.ServerUnreachable)
        repository.saveResults = listOf(saved("v1"))
        outbox.enqueue(position)
        val flusher = flusher(baselines = baselines)
        flusher.kick()
        runCurrent()

        assertThat(repository.writes).isEmpty()
        assertThat(outbox.positions).containsExactly(position)
        assertThat(baselines.of(SampleBook)).isNull()

        reader.finishedReadingResult = ApiResult.Success(null)
        flusher.kick()
        runCurrent()

        assertThat(baselines.of(SampleBook)).isEqualTo(ReadingBaseline(null))
        assertThat(repository.writes).hasSize(1)
        assertThat(outbox.positions).isEmpty()
    }

    @Test
    fun `without the network the position is tried again by itself`() = runTest {
        reader.finishedReadingResult = ApiResult.Failure(ApiError.ServerUnreachable)
        repository.saveResults = listOf(saved("v1"))
        outbox.enqueue(position)
        flusher(baselines = baselines).kick()
        runCurrent()

        reader.finishedReadingResult = ApiResult.Success(ReadId("3"))
        advanceTimeBy(6.seconds)
        runCurrent()

        assertThat(repository.writes).hasSize(1)
    }

    @Test
    fun `another failure lets the position go without a baseline`() = runTest {
        reader.finishedReadingResult = ApiResult.Failure(ApiError.NotFound)
        repository.saveResults = listOf(saved("v1"))
        outbox.enqueue(position)

        flusher(baselines = baselines).kick()
        runCurrent()

        assertThat(repository.writes).hasSize(1)
        assertThat(baselines.of(SampleBook)).isNull()
    }

    @Test
    fun `a readings route that keeps failing lets the position and the sessions go after a few attempts`() = runTest {
        reader.finishedReadingResult = ApiResult.Failure(ApiError.ServerFailed)
        repository.saveResults = listOf(saved("v1"))
        outbox.enqueue(position)
        sessions.enqueue(FileId("50"), sampleSession())
        flusher(baselines = baselines).kick()
        runCurrent()
        assertThat(repository.writes).isEmpty()

        advanceTimeBy(10.minutes)
        runCurrent()

        assertThat(reader.calls.count { it == "finishedReadingId 12" }).isEqualTo(BaselineAttempts)
        assertThat(repository.writes).hasSize(1)
        assertThat(outbox.positions).isEmpty()
        assertThat(repository.savedSessions).hasSize(1)
        assertThat(baselines.of(SampleBook)).isNull()
    }

    @Test
    fun `a book given up on is not held again`() = runTest {
        reader.finishedReadingResult = ApiResult.Failure(ApiError.NotFound)
        repository.saveResults = listOf(saved("v1"), saved("v2"))
        outbox.enqueue(position)
        val flusher = flusher(baselines = baselines)
        flusher.kick()
        runCurrent()

        outbox.enqueue(samplePlayback(at = position.at.plusSeconds(5), positionMs = 95_000))
        flusher.kick()
        runCurrent()

        assertThat(reader.calls).containsExactly("finishedReadingId 12")
        assertThat(repository.writes).hasSize(2)
    }

    @Test
    fun `a book waiting for its baseline holds back neither the other books nor the sessions`() = runTest {
        val perBook = object : ReaderRepository by reader {
            override suspend fun finishedReadingId(bookId: BookId): ApiResult<ReadId?> =
                if (bookId == SampleBook) ApiResult.Failure(ApiError.ServerUnreachable) else ApiResult.Success(null)
        }
        repository.saveResults = listOf(saved("v1"))
        outbox.enqueue(position)
        outbox.enqueue(position.copy(bookId = OtherBook))
        sessions.enqueue(FileId("50"), sampleSession())

        flusher(baselines = ReadingBaselines(perBook)).kick()
        runCurrent()

        assertThat(repository.writes.map { it.first }).containsExactly(OtherBook)
        assertThat(repository.savedSessions).hasSize(1)
        assertThat(outbox.positions.map { it.bookId }).containsExactly(SampleBook)
    }

    @Test
    fun `a baseline cleared is captured again before the next position`() = runTest {
        reader.finishedReadingResult = ApiResult.Success(ReadId("3"))
        repository.saveResults = listOf(saved("v1"), saved("v2"))
        outbox.enqueue(position)
        val flusher = flusher(baselines = baselines)
        flusher.kick()
        runCurrent()

        baselines.clear(SampleBook)
        reader.finishedReadingResult = ApiResult.Success(ReadId("9"))
        outbox.enqueue(samplePlayback(at = position.at.plusSeconds(5), positionMs = 95_000))
        flusher.kick()
        runCurrent()

        assertThat(baselines.of(SampleBook)).isEqualTo(ReadingBaseline(ReadId("9")))
    }
}
