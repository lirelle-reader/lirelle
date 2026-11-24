package app.tfdn.lirelle.core.reader.sync

import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.ReadingChanges
import app.tfdn.lirelle.core.library.model.NarrationPlace
import app.tfdn.lirelle.core.library.model.SaveOutcome
import app.tfdn.lirelle.core.library.model.SavedPosition
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.testing.FakeReaderRepository
import app.tfdn.lirelle.core.testing.InMemoryOutbox
import com.google.common.truth.Truth.assertThat
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** What the flusher tells [ReadingWrites], for the listening positions that wait behind the reading. */
@OptIn(ExperimentalCoroutinesApi::class)
class OutboxFlusherReadingWritesTest {

    private val clocks = TestClocks()
    private val madeAt = clocks.now
    private val narration = LocalNarration(FileId("12"), NarrationPlace(95.25, "ch1.xhtml#s3", 2), 12.0, madeAt)
    private val position = LocalPosition(FileId("12"), "epubcfi(/6/4!/4/2/1:0)", 12.5, madeAt)
    private val repository = FakeReaderRepository()
    private val outbox = InMemoryOutbox()
    private val reading = ReadingWrites(outbox)
    private var told = 0

    /** When the server says it stored the writes: its clock is not the device's. */
    private val storedAt = madeAt.plusSeconds(3_600)
    private val stored = ApiResult.Success(SavedPosition(SaveOutcome.Saved, storedAt))

    private fun TestScope.flusher() = OutboxFlusher(
        outbox, repository, backgroundScope, clocks.wall, ReadingChanges(), reading = reading,
    ).also { reading.onSent = { told++ } }

    @Test
    fun `a narration that waited is remembered with the time the server stored it`() = runTest {
        repository.saveNarrationResult = stored
        outbox.enqueueNarration(narration)
        clocks.advance(10.minutes)

        flusher().kick()
        runCurrent()

        assertThat(reading.movedListeningBefore(storedAt, madeAt.plusSeconds(60))).isTrue()
        // The listening made before that narration is not the later word.
        assertThat(reading.movedListeningBefore(storedAt, madeAt.minusSeconds(60))).isFalse()
        // Another time: not what this write made.
        assertThat(reading.movedListeningBefore(storedAt.plusSeconds(60), madeAt.plusSeconds(60))).isFalse()
    }

    @Test
    fun `a text position that waited is remembered too`() = runTest {
        repository.savePositionResult = stored
        outbox.enqueuePosition(position)
        clocks.advance(10.minutes)

        flusher().kick()
        runCurrent()

        assertThat(reading.movedListeningBefore(storedAt, madeAt.plusSeconds(60))).isTrue()
    }

    @Test
    fun `a reading sent as it is made is not remembered`() = runTest {
        repository.savePositionResult = stored
        repository.saveNarrationResult = stored
        outbox.enqueuePosition(position)
        outbox.enqueueNarration(narration)

        flusher().kick()
        runCurrent()

        assertThat(reading.movedListeningBefore(storedAt, madeAt.plusSeconds(60))).isFalse()
    }

    @Test
    fun `a late write the server set aside is not remembered`() = runTest {
        repository.saveNarrationResult = ApiResult.Success(SavedPosition(SaveOutcome.Superseded, storedAt))
        outbox.enqueueNarration(narration)
        clocks.advance(10.minutes)

        flusher().kick()
        runCurrent()

        assertThat(reading.movedListeningBefore(storedAt, madeAt.plusSeconds(60))).isFalse()
    }

    @Test
    fun `a later reading made of the same state keeps the listening from going over it`() = runTest {
        repository.savePositionResult = stored
        repository.saveNarrationResult = stored
        outbox.enqueuePosition(position)
        outbox.enqueueNarration(narration.copy(at = madeAt.plusSeconds(300)))
        clocks.advance(10.minutes)

        flusher().kick()
        runCurrent()

        assertThat(reading.movedListeningBefore(storedAt, madeAt.plusSeconds(60))).isFalse()
        assertThat(reading.movedListeningBefore(storedAt, madeAt.plusSeconds(400))).isTrue()
    }

    @Test
    fun `it is told once each position and narration has left the outbox, not while they wait`() = runTest {
        repository.savePositionResult = ApiResult.Failure(ApiError.ServerUnreachable)
        repository.saveNarrationResult = ApiResult.Failure(ApiError.ServerUnreachable)
        outbox.enqueuePosition(position)
        outbox.enqueueNarration(narration)
        val flusher = flusher()
        flusher.kick()
        runCurrent()
        assertThat(told).isEqualTo(0)
        assertThat(reading.waiting()).isTrue()

        repository.savePositionResult = stored
        repository.saveNarrationResult = stored
        flusher.kick()
        runCurrent()

        assertThat(told).isEqualTo(2)
        assertThat(reading.waiting()).isFalse()
    }
}
