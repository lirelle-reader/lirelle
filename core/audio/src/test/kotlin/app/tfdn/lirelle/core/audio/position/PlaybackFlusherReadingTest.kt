package app.tfdn.lirelle.core.audio.position

import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.NarrationPlace
import app.tfdn.lirelle.core.library.model.SaveOutcome
import app.tfdn.lirelle.core.library.model.SavedPosition
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.reader.sync.LocalNarration
import app.tfdn.lirelle.core.reader.sync.LocalPosition
import app.tfdn.lirelle.core.reader.sync.ReadingWrites
import app.tfdn.lirelle.core.testing.InMemoryOutbox
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** The listening positions wait behind the reading, and go over what the server made of an older reading. */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackFlusherReadingTest : FlusherRig() {

    private val readerOutbox = InMemoryOutbox()
    private val reading = ReadingWrites(readerOutbox)
    private val narration = LocalNarration(FileId("40"), NarrationPlace(310.0, "ch1.xhtml#s24", 2), 0.3, position.at)

    /** When the server stored the reading that waited, by its clock: after everything this device wrote. */
    private val storedAt = position.at.plusSeconds(900)

    private fun TestScope.readingFlusher() = flusher().also { flusher ->
        flusher.reading = reading
        reading.onSent = { flusher.kick() }
    }

    private fun readingStored(madeAt: Instant) =
        reading.landedLate(madeAt, ApiResult.Success(SavedPosition(SaveOutcome.Saved, storedAt)))

    @Test
    fun `a listening position waits while a narration does, and goes once it has left`() = runTest {
        repository.saveResults = listOf(saved("v1"))
        readerOutbox.enqueueNarration(narration)
        outbox.enqueue(position)
        readingFlusher().kick()
        runCurrent()
        assertThat(sentWrites).isEmpty()
        assertThat(outbox.positions).containsExactly(position)

        readerOutbox.markSent(narration)
        reading.sent()
        runCurrent()

        assertThat(sentWrites.map { it.operationId }).containsExactly("op-90000")
        assertThat(outbox.positions).isEmpty()
    }

    @Test
    fun `it waits while a text position does, and is tried again by itself`() = runTest {
        repository.saveResults = listOf(saved("v1"))
        val text = LocalPosition(FileId("41"), "epubcfi(/6/4!/4/2/1:0)", 0.24, position.at)
        readerOutbox.enqueuePosition(text)
        outbox.enqueue(position)
        readingFlusher().kick()
        runCurrent()
        assertThat(sentWrites).isEmpty()

        readerOutbox.markSent(text)
        advanceTimeBy(5.seconds)
        runCurrent()

        assertThat(sentWrites).hasSize(1)
    }

    @Test
    fun `a reading session that waits does not hold the listening back`() = runTest {
        repository.saveResults = listOf(saved("v1"))
        readerOutbox.enqueueSession(FileId("40"), sampleSession("s-1"))
        outbox.enqueue(position)

        readingFlusher().kick()
        runCurrent()

        assertThat(sentWrites).hasSize(1)
    }

    @Test
    fun `the state the server made of an older reading that waited is not someone else's`() = runTest {
        repository.saveResults = listOf(conflict, saved("v3"))
        repository.playbackStateResult = ApiResult.Success(state("v2", capturedAt = storedAt))
        readingStored(madeAt = position.at.minusSeconds(300))
        outbox.enqueue(position)

        readingFlusher().kick()
        runCurrent()

        assertThat(sentWrites.map { it.baseVersion }).containsExactly(null, "v2").inOrder()
        assertThat(sentWrites.map { it.operationId }).containsExactly("op-90000", "op-90000")
        assertThat(conflicts).isEmpty()
        assertThat(outbox.positions).isEmpty()
        assertThat(store.knownVersion(SampleBook)).isEqualTo("v3")
    }

    @Test
    fun `the state the server made of a reading made after the position stands, and is offered`() = runTest {
        repository.saveResults = listOf(conflict)
        repository.playbackStateResult = ApiResult.Success(state("v2", capturedAt = storedAt))
        readingStored(madeAt = position.at.plusSeconds(300))
        outbox.enqueue(position)

        readingFlusher().kick()
        runCurrent()

        assertThat(sentWrites).hasSize(1)
        assertThat(conflicts).containsExactly(SampleBook)
    }

    @Test
    fun `a state captured at another time than the reading was stored is someone else's`() = runTest {
        repository.saveResults = listOf(conflict)
        repository.playbackStateResult = ApiResult.Success(state("v2", capturedAt = storedAt.plusSeconds(60)))
        readingStored(madeAt = position.at.minusSeconds(300))
        outbox.enqueue(position)

        readingFlusher().kick()
        runCurrent()

        assertThat(sentWrites).hasSize(1)
        assertThat(conflicts).containsExactly(SampleBook)
    }
}
