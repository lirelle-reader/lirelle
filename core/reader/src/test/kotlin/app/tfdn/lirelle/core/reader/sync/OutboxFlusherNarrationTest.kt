package app.tfdn.lirelle.core.reader.sync

import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.ReadingChanges
import app.tfdn.lirelle.core.library.model.NarrationPlace
import app.tfdn.lirelle.core.library.model.ReadingSessionReport
import app.tfdn.lirelle.core.library.model.SaveOutcome
import app.tfdn.lirelle.core.library.model.SavedPosition
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.testing.FakeReaderRepository
import app.tfdn.lirelle.core.testing.InMemoryOutbox
import com.google.common.truth.Truth.assertThat
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** The narrations of read-along books, sent beside the text positions. */
@OptIn(ExperimentalCoroutinesApi::class)
class OutboxFlusherNarrationTest {

    private val clocks = TestClocks()
    private val at = clocks.now
    private val place = NarrationPlace(95.25, "ch1.xhtml#s3", 2)
    private val file12 = FileId("12")
    private val narration = LocalNarration(file12, place, 12.0, at)
    private val position = LocalPosition(file12,"epubcfi(/6/4!/4/2/1:0)", 12.5, at)
    private val session = ReadingSessionReport("s-1", at, at.plusSeconds(600), 540, 3.5, 16.0)
    private val repository = FakeReaderRepository()
    private val outbox = InMemoryOutbox()
    private val changes = ReadingChanges()
    private val policy = RetryPolicy(firstWait = 5.seconds, longestWait = 40.seconds, unclearAttempts = 3)

    private fun TestScope.flusher() = OutboxFlusher(
        outbox, repository, backgroundScope, clocks.wall, changes, policy, clocks.monotonic,
    )

    @Test
    fun `a narration is sent with its place and leaves the outbox`() = runTest {
        outbox.enqueueNarration(narration)

        flusher().kick()
        runCurrent()

        assertThat(repository.calls).containsExactly("saveNarration 12")
        assertThat(repository.savedNarrations).containsExactly(file12 to place)
        assertThat(outbox.narrations).isEmpty()
    }

    @Test
    fun `the narration of every tick for 3 minutes tells the screens at most once a minute`() = runTest {
        val flusher = flusher()

        repeat(times = 36) { tick ->
            outbox.enqueueNarration(narration.copy(percent = 12.0 + tick, at = clocks.now))
            flusher.kick()
            runCurrent()
            clocks.advance(5.seconds)
            advanceTimeBy(5.seconds)
        }

        assertThat(repository.savedNarrations).hasSize(36)
        assertThat(changes.version.value).isIn(1L..3L)
    }

    @Test
    fun `a narration that settles, after a pause, tells the screens once it is sent`() = runTest {
        val flusher = flusher()
        outbox.enqueueNarration(narration)
        flusher.kick()
        runCurrent()
        assertThat(changes.version.value).isEqualTo(0)

        clocks.advance(3.seconds)
        outbox.enqueueNarration(narration.copy(percent = 12.5, at = clocks.now))
        flusher.settleNarration()
        runCurrent()

        assertThat(changes.version.value).isEqualTo(1)
    }

    @Test
    fun `a settling whose narration could not be sent tells nothing until it is`() = runTest {
        repository.saveNarrationResult = ApiResult.Failure(ApiError.ServerUnreachable)
        val flusher = flusher()
        outbox.enqueueNarration(narration)
        flusher.settleNarration()
        runCurrent()
        assertThat(changes.version.value).isEqualTo(0)

        repository.saveNarrationResult = ApiResult.Success(SavedPosition(SaveOutcome.Saved))
        advanceTimeBy(6.seconds)
        runCurrent()

        assertThat(changes.version.value).isEqualTo(1)
    }

    @Test
    fun `positions go first, then narrations, then sessions`() = runTest {
        outbox.enqueueSession(file12, session)
        outbox.enqueueNarration(narration)
        outbox.enqueuePosition(position)

        flusher().kick()
        runCurrent()

        assertThat(repository.calls)
            .containsExactly("savePosition 12", "saveNarration 12", "saveSession 12 s-1")
            .inOrder()
        assertThat(outbox.pending().isEmpty).isTrue()
    }

    @Test
    fun `a fresh narration is sent without its time, one that waited with it`() = runTest {
        outbox.enqueueNarration(narration)
        clocks.advance(60.seconds)
        val flusher = flusher()
        flusher.kick()
        runCurrent()

        val next = narration.copy(percent = 13.0, at = clocks.now)
        outbox.enqueueNarration(next)
        clocks.advance(61.seconds)
        flusher.kick()
        runCurrent()

        assertThat(repository.savedNarrationEditedAts).containsExactly(null, next.at).inOrder()
    }

    @Test
    fun `a superseded narration sent late leaves the outbox`() = runTest {
        repository.saveNarrationResult = ApiResult.Success(SavedPosition(SaveOutcome.Superseded))
        outbox.enqueueNarration(narration)
        clocks.advance(2.hours)

        flusher().kick()
        advanceTimeBy(600.seconds)
        runCurrent()

        assertThat(repository.calls).containsExactly("saveNarration 12")
        assertThat(repository.savedNarrationEditedAts).containsExactly(at)
        assertThat(outbox.narrations).isEmpty()
    }

    @Test
    fun `a late narration is never dated before this device's last narration as the server stamped it`() = runTest {
        val stamped = at.plusSeconds(600)
        repository.saveNarrationResult = ApiResult.Success(SavedPosition(SaveOutcome.Saved, stamped))
        outbox.enqueueNarration(narration)
        val flusher = flusher()
        flusher.kick()
        runCurrent()
        clocks.advance(30.seconds)
        val next = narration.copy(percent = 14.0, at = clocks.now)
        outbox.enqueueNarration(next)
        clocks.advance(2.minutes)

        flusher.kick()
        runCurrent()

        assertThat(repository.savedNarrationEditedAts).containsExactly(null, stamped.plusMillis(1)).inOrder()
    }

    @Test
    fun `the server's stamp of a text position does not date a narration`() = runTest {
        val stamped = at.plusSeconds(600)
        repository.savePositionResult = ApiResult.Success(SavedPosition(SaveOutcome.Saved, stamped))
        outbox.enqueuePosition(position)
        val flusher = flusher()
        flusher.kick()
        runCurrent()
        clocks.advance(30.seconds)
        val next = narration.copy(at = clocks.now)
        outbox.enqueueNarration(next)
        clocks.advance(2.minutes)

        flusher.kick()
        runCurrent()

        assertThat(repository.savedNarrationEditedAts).containsExactly(next.at)
    }

    @Test
    fun `without network a narration stays and is tried again after a growing wait`() = runTest {
        repository.saveNarrationResult = ApiResult.Failure(ApiError.ServerUnreachable)
        outbox.enqueueNarration(narration)
        flusher().kick()
        runCurrent()

        advanceTimeBy(4.seconds)
        runCurrent()
        assertThat(repository.calls).hasSize(1)
        advanceTimeBy(1.seconds)
        runCurrent()
        assertThat(repository.calls).hasSize(2)
        advanceTimeBy(10.seconds)
        runCurrent()
        assertThat(repository.calls).hasSize(3)
        assertThat(outbox.narrations).containsExactly(narration)

        repository.saveNarrationResult = ApiResult.Success(SavedPosition(SaveOutcome.Saved))
        advanceTimeBy(20.seconds)
        runCurrent()
        assertThat(outbox.narrations).isEmpty()
    }

    @Test
    fun `a narration that failed offline and was replaced by a newer one is retried with the newer only`() = runTest {
        repository.saveNarrationResult = ApiResult.Failure(ApiError.ServerUnreachable)
        outbox.enqueueNarration(narration)
        flusher().kick()
        runCurrent()

        val newer = narration.copy(place = NarrationPlace(99.5, "ch1.xhtml#s4", 2), at = at.plusSeconds(5))
        outbox.enqueueNarration(newer)
        repository.saveNarrationResult = ApiResult.Success(SavedPosition(SaveOutcome.Saved))
        advanceTimeBy(5.seconds)
        runCurrent()

        assertThat(repository.savedNarrations.map { it.second }).containsExactly(place, newer.place).inOrder()
        assertThat(outbox.narrations).isEmpty()
    }

    @Test
    fun `a narration waiting for the network does not hold the session of its file`() = runTest {
        repository.saveNarrationResult = ApiResult.Failure(ApiError.ServerUnreachable)
        outbox.enqueueNarration(narration)
        outbox.enqueueSession(file12, session)

        flusher().kick()
        runCurrent()

        assertThat(repository.calls).containsExactly("saveNarration 12", "saveSession 12 s-1").inOrder()
        assertThat(outbox.narrations).containsExactly(narration)
    }

    @Test
    fun `a narration whose file is gone is dropped at once`() = runTest {
        repository.saveNarrationResult = ApiResult.Failure(ApiError.NotFound)
        outbox.enqueueNarration(narration)

        flusher().kick()
        advanceTimeBy(600.seconds)
        runCurrent()

        assertThat(repository.calls).containsExactly("saveNarration 12")
        assertThat(outbox.narrations).isEmpty()
        assertThat(changes.version.value).isEqualTo(0)
    }

    @Test
    fun `a narration failing without saying why is tried a few times, then dropped`() = runTest {
        repository.saveNarrationResult = ApiResult.Failure(ApiError.Unexpected)
        outbox.enqueueNarration(narration)

        flusher().kick()
        advanceTimeBy(600.seconds)
        runCurrent()

        assertThat(repository.calls).hasSize(3)
        assertThat(outbox.narrations).isEmpty()
    }

    @Test
    fun `an ended session keeps the narration and stops until the next kick`() = runTest {
        repository.saveNarrationResult = ApiResult.Failure(ApiError.SessionExpired)
        outbox.enqueueNarration(narration)
        outbox.enqueueSession(file12, session)
        val flusher = flusher()

        flusher.kick()
        advanceTimeBy(600.seconds)
        runCurrent()

        assertThat(repository.calls).containsExactly("saveNarration 12")
        assertThat(outbox.narrations).containsExactly(narration)
        assertThat(outbox.sessions).containsExactly(PendingSession(file12, session))
    }
}
