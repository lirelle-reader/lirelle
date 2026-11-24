package app.tfdn.lirelle.core.reader.sync

import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.ReadingChanges
import app.tfdn.lirelle.core.library.model.ReadingSessionReport
import app.tfdn.lirelle.core.library.model.SaveOutcome
import app.tfdn.lirelle.core.library.model.SavedPosition
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.testing.FakeReaderRepository
import app.tfdn.lirelle.core.testing.InMemoryOutbox
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OutboxFlusherTest {

    private val clocks = TestClocks()
    private val at = clocks.now
    private val file12 = FileId("12")
    private val file13 = FileId("13")
    private val position = LocalPosition(file12,"epubcfi(/6/4!/4/2/1:0)", 12.5, at)
    private val session = ReadingSessionReport("s-1", at, at.plusSeconds(600), 540, 3.5, 16.0)
    private val repository = FakeReaderRepository()
    private val outbox = InMemoryOutbox()
    private val changes = ReadingChanges()
    private val policy = RetryPolicy(firstWait = 5.seconds, longestWait = 40.seconds, unclearAttempts = 3)

    private fun TestScope.flusher() =
        OutboxFlusher(outbox, repository, backgroundScope, clocks.wall, changes, policy)

    private fun failWith(error: ApiError) {
        repository.savePositionResult = ApiResult.Failure(error)
        repository.saveSessionResult = ApiResult.Failure(error)
    }

    private fun succeed() {
        repository.savePositionResult = ApiResult.Success(SavedPosition(SaveOutcome.Saved))
        repository.saveSessionResult = ApiResult.Success(Unit)
    }

    @Test
    fun `nothing is sent before the first kick`() = runTest {
        outbox.enqueuePosition(position)
        flusher()

        advanceTimeBy(60.seconds)

        assertThat(repository.calls).isEmpty()
    }

    @Test
    fun `positions go first, then sessions, and the outbox ends empty`() = runTest {
        outbox.enqueueSession(file12, session)
        outbox.enqueueSession(file13, session.copy(sessionId = "s-2"))
        outbox.enqueuePosition(position.copy(fileId = file13))
        outbox.enqueuePosition(position)

        flusher().kick()
        runCurrent()

        assertThat(repository.calls)
            .containsExactly("savePosition 13", "savePosition 12", "saveSession 12 s-1", "saveSession 13 s-2")
            .inOrder()
        assertThat(repository.savedPositions.map { it.cfi to it.percent })
            .containsExactly(position.cfi to 12.5, position.cfi to 12.5)
        assertThat(repository.savedSessions).contains(file12 to session)
        assertThat(outbox.pending().isEmpty).isTrue()
    }

    @Test
    fun `a superseded position counts as sent`() = runTest {
        repository.savePositionResult = ApiResult.Success(SavedPosition(SaveOutcome.Superseded))
        outbox.enqueuePosition(position)

        flusher().kick()
        runCurrent()

        assertThat(outbox.positions).isEmpty()
    }

    @Test
    fun `a superseded position sent late leaves the outbox`() = runTest {
        repository.savePositionResult = ApiResult.Success(SavedPosition(SaveOutcome.Superseded))
        outbox.enqueuePosition(position)
        clocks.advance(2.hours)

        flusher().kick()
        advanceTimeBy(600.seconds)
        runCurrent()

        assertThat(repository.calls).containsExactly("savePosition 12")
        assertThat(repository.savedEditedAts).containsExactly(at)
        assertThat(outbox.positions).isEmpty()
    }

    @Test
    fun `a fresh position is sent without its time, so a late clock cannot have it refused`() = runTest {
        outbox.enqueuePosition(position)
        clocks.advance(60.seconds)

        flusher().kick()
        runCurrent()

        assertThat(repository.savedEditedAts).containsExactly(null)
    }

    @Test
    fun `a position that waited is sent with its time, so it cannot overwrite a newer one`() = runTest {
        outbox.enqueuePosition(position)
        clocks.advance(61.seconds)

        flusher().kick()
        runCurrent()

        assertThat(repository.savedEditedAts).containsExactly(at)
    }

    @Test
    fun `a position becomes a late one while it is being retried`() = runTest {
        failWith(ApiError.ServerUnreachable)
        outbox.enqueuePosition(position)
        flusher().kick()
        runCurrent()

        clocks.advance(5.minutes)
        advanceTimeBy(5.seconds)
        runCurrent()

        assertThat(repository.savedEditedAts).containsExactly(null, at).inOrder()
    }

    @Test
    fun `a late position is never dated before this device's last write as the server stamped it`() = runTest {
        // The server's clock is ten minutes ahead of the device's.
        val stamped = at.plusSeconds(600)
        repository.savePositionResult = ApiResult.Success(SavedPosition(SaveOutcome.Saved, stamped))
        outbox.enqueuePosition(position)
        val flusher = flusher()
        flusher.kick()
        runCurrent()
        clocks.advance(30.seconds)
        val next = position.copy(percent = 14.0, at = clocks.now)
        outbox.enqueuePosition(next)
        clocks.advance(2.minutes)

        flusher.kick()
        runCurrent()

        assertThat(repository.savedEditedAts).containsExactly(null, stamped.plusMillis(1)).inOrder()
        assertThat(outbox.positions).isEmpty()
    }

    @Test
    fun `a late position made after the server's stamp keeps its own time`() = runTest {
        repository.savePositionResult = ApiResult.Success(SavedPosition(SaveOutcome.Saved, at.minusSeconds(600)))
        outbox.enqueuePosition(position)
        val flusher = flusher()
        flusher.kick()
        runCurrent()
        clocks.advance(30.seconds)
        val next = position.copy(percent = 14.0, at = clocks.now)
        outbox.enqueuePosition(next)
        clocks.advance(2.minutes)

        flusher.kick()
        runCurrent()

        assertThat(repository.savedEditedAts).containsExactly(null, next.at).inOrder()
    }

    @Test
    fun `the time of a position the server kept from elsewhere, or of another file, dates nothing`() = runTest {
        val stamped = at.plusSeconds(600)
        repository.savePositionResult = ApiResult.Success(SavedPosition(SaveOutcome.Superseded, stamped))
        outbox.enqueuePosition(position)
        val flusher = flusher()
        flusher.kick()
        runCurrent()
        repository.savePositionResult = ApiResult.Success(SavedPosition(SaveOutcome.Saved, stamped))
        outbox.enqueuePosition(position.copy(fileId = file13))
        flusher.kick()
        runCurrent()
        clocks.advance(30.seconds)
        val next = position.copy(percent = 14.0, at = clocks.now)
        outbox.enqueuePosition(next)
        clocks.advance(2.minutes)

        flusher.kick()
        runCurrent()

        assertThat(repository.savedEditedAts.last()).isEqualTo(next.at)
    }

    @Test
    fun `without network the items stay and are tried again after a growing wait`() = runTest {
        failWith(ApiError.ServerUnreachable)
        outbox.enqueuePosition(position)
        flusher().kick()
        runCurrent()
        assertThat(repository.calls).hasSize(1)

        advanceTimeBy(4.seconds)
        runCurrent()
        assertThat(repository.calls).hasSize(1)
        advanceTimeBy(1.seconds)
        runCurrent()
        assertThat(repository.calls).hasSize(2)

        advanceTimeBy(9.seconds)
        runCurrent()
        assertThat(repository.calls).hasSize(2)
        advanceTimeBy(1.seconds)
        runCurrent()
        assertThat(repository.calls).hasSize(3)
        assertThat(outbox.positions).containsExactly(position)

        succeed()
        advanceTimeBy(20.seconds)
        runCurrent()
        assertThat(outbox.positions).isEmpty()
    }

    @Test
    fun `the wait stops growing at the longest one`() = runTest {
        failWith(ApiError.ServerUnreachable)
        outbox.enqueuePosition(position)
        flusher().kick()
        // Attempts at 0, 5, 15 and 35 seconds; from there one every 40 seconds.
        advanceTimeBy(36.seconds)
        runCurrent()
        assertThat(repository.calls).hasSize(4)

        advanceTimeBy(120.seconds)
        runCurrent()

        assertThat(repository.calls).hasSize(7)
    }

    @Test
    fun `a session waits for the position of its file, not for another file's`() = runTest {
        repository.savePositionResult = ApiResult.Failure(ApiError.ServerUnreachable)
        outbox.enqueuePosition(position)
        outbox.enqueueSession(file12, session)
        outbox.enqueueSession(file13, session.copy(sessionId = "s-2"))

        flusher().kick()
        runCurrent()

        assertThat(repository.calls).containsExactly("savePosition 12", "saveSession 13 s-2").inOrder()
        assertThat(outbox.sessions).containsExactly(PendingSession(file12, session))

        succeed()
        advanceTimeBy(5.seconds)
        runCurrent()
        assertThat(repository.calls.drop(2)).containsExactly("savePosition 12", "saveSession 12 s-1").inOrder()
        assertThat(outbox.pending().isEmpty).isTrue()
    }

    @Test
    fun `a file that is gone drops its items at once, so the queue cannot jam`() = runTest {
        failWith(ApiError.NotFound)
        outbox.enqueuePosition(position)
        outbox.enqueueSession(file12, session)

        flusher().kick()
        advanceTimeBy(600.seconds)
        runCurrent()

        assertThat(repository.calls).containsExactly("savePosition 12", "saveSession 12 s-1").inOrder()
        assertThat(outbox.pending().isEmpty).isTrue()
    }

    @Test
    fun `a failure that does not say why is tried a few times, then the item is dropped`() = runTest {
        failWith(ApiError.Unexpected)
        outbox.enqueuePosition(position)

        flusher().kick()
        advanceTimeBy(600.seconds)
        runCurrent()

        assertThat(repository.calls).hasSize(3)
        assertThat(outbox.positions).isEmpty()
    }

    @Test
    fun `a failing server never drops an item, and the item is sent once the server answers`() = runTest {
        failWith(ApiError.ServerFailed)
        outbox.enqueuePosition(position)
        outbox.enqueueSession(file12, session)

        flusher().kick()
        advanceTimeBy(3600.seconds)
        runCurrent()

        // Far more attempts than an unclear failure gets, at the longest wait.
        assertThat(repository.calls.size).isGreaterThan(80)
        assertThat(repository.calls.toSet()).containsExactly("savePosition 12")
        assertThat(outbox.positions).containsExactly(position)
        assertThat(outbox.sessions).containsExactly(PendingSession(file12, session))

        succeed()
        advanceTimeBy(40.seconds)
        runCurrent()

        assertThat(repository.calls.takeLast(2)).containsExactly("savePosition 12", "saveSession 12 s-1").inOrder()
        assertThat(outbox.pending().isEmpty).isTrue()
    }

    @Test
    fun `an unclear failure followed by a success sends the item`() = runTest {
        failWith(ApiError.Unexpected)
        outbox.enqueuePosition(position)
        flusher().kick()
        runCurrent()
        assertThat(outbox.positions).containsExactly(position)

        succeed()
        advanceTimeBy(5.seconds)
        runCurrent()

        assertThat(repository.calls).hasSize(2)
        assertThat(outbox.positions).isEmpty()
    }

    @Test
    fun `an ended session keeps everything and stops until the next kick`() = runTest {
        failWith(ApiError.SessionExpired)
        outbox.enqueuePosition(position)
        outbox.enqueueSession(file12, session)
        val flusher = flusher()

        flusher.kick()
        advanceTimeBy(600.seconds)
        runCurrent()

        assertThat(repository.calls).containsExactly("savePosition 12")
        assertThat(outbox.positions).containsExactly(position)
        assertThat(outbox.sessions).containsExactly(PendingSession(file12, session))

        succeed()
        flusher.kick()
        runCurrent()
        assertThat(outbox.pending().isEmpty).isTrue()
    }

    @Test
    fun `a kick during a flush does not start a second one`() = runTest {
        val gate = CompletableDeferred<Unit>()
        repository.gate = gate
        outbox.enqueuePosition(position)
        val flusher = flusher()

        flusher.kick()
        runCurrent()
        flusher.kick()
        flusher.kick()
        runCurrent()
        assertThat(repository.calls).containsExactly("savePosition 12")

        gate.complete(Unit)
        runCurrent()
        assertThat(repository.calls).containsExactly("savePosition 12")
        assertThat(outbox.positions).isEmpty()
    }

    @Test
    fun `a kick cuts the wait short`() = runTest {
        failWith(ApiError.ServerUnreachable)
        outbox.enqueuePosition(position)
        val flusher = flusher()
        flusher.kick()
        runCurrent()

        succeed()
        flusher.kick()
        runCurrent()

        assertThat(outbox.positions).isEmpty()
    }

    @Test
    fun `a position enqueued while the older one is being sent is neither lost nor marked sent`() = runTest {
        val gate = CompletableDeferred<Unit>()
        repository.gate = gate
        val later = position.copy(cfi = "epubcfi(/6/6!/4/2/1:0)", percent = 14.0, at = at.plusSeconds(5))
        outbox.enqueuePosition(position)
        val flusher = flusher()
        flusher.kick()
        runCurrent()

        outbox.enqueuePosition(later)
        gate.complete(Unit)
        runCurrent()
        assertThat(outbox.positions).containsExactly(later)

        flusher.kick()
        runCurrent()
        assertThat(repository.savedPositions.map { it.percent }).containsExactly(12.5, 14.0).inOrder()
        assertThat(outbox.positions).isEmpty()
    }

    @Test
    fun `a session enqueued during a flush is sent by the kick that follows it`() = runTest {
        val gate = CompletableDeferred<Unit>()
        repository.gate = gate
        outbox.enqueuePosition(position)
        val flusher = flusher()
        flusher.kick()
        runCurrent()

        outbox.enqueueSession(file12, session)
        flusher.kick()
        gate.complete(Unit)
        runCurrent()

        assertThat(repository.calls).containsExactly("savePosition 12", "saveSession 12 s-1").inOrder()
        assertThat(outbox.pending().isEmpty).isTrue()
    }

    @Test
    fun `a flush that breaks does not fail its scope, and the next kick flushes again`() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val breaking = BreakingOutbox(outbox)
        val flusher = OutboxFlusher(breaking, repository, scope, clocks.wall, changes, policy)
        outbox.enqueuePosition(position)

        flusher.kick()
        runCurrent()
        assertThat(scope.coroutineContext[Job]!!.isActive).isTrue()
        assertThat(repository.calls).isEmpty()

        breaking.broken = false
        flusher.kick()
        runCurrent()
        assertThat(outbox.positions).isEmpty()
        scope.coroutineContext[Job]!!.cancel()
    }

    @Test
    fun `a position the server stored tells the screens that the reading changed`() = runTest {
        outbox.enqueuePosition(position)

        flusher().kick()
        runCurrent()

        assertThat(changes.version.value).isEqualTo(1)
    }

    @Test
    fun `a position the server set aside tells it too, the server holding another than the screens show`() = runTest {
        repository.savePositionResult = ApiResult.Success(SavedPosition(SaveOutcome.Superseded))
        outbox.enqueuePosition(position)

        flusher().kick()
        runCurrent()

        assertThat(changes.version.value).isEqualTo(1)
    }

    @Test
    fun `a session the server took tells the screens that the reading changed`() = runTest {
        outbox.enqueueSession(file12, session)

        flusher().kick()
        runCurrent()

        assertThat(changes.version.value).isEqualTo(1)
    }

    @Test
    fun `nothing is told while nothing is sent, nor when a send fails or is given up`() = runTest {
        outbox.enqueuePosition(position)
        outbox.enqueueSession(file13, session)
        val flusher = flusher()
        advanceTimeBy(60.seconds)
        assertThat(changes.version.value).isEqualTo(0)

        failWith(ApiError.ServerUnreachable)
        flusher.kick()
        runCurrent()
        assertThat(repository.calls).isNotEmpty()
        assertThat(changes.version.value).isEqualTo(0)

        // The file is gone: both items are dropped, and the server holds nothing new.
        failWith(ApiError.NotFound)
        flusher.kick()
        runCurrent()
        assertThat(outbox.pending().isEmpty).isTrue()
        assertThat(changes.version.value).isEqualTo(0)
    }

    @Test
    fun `a position that lands after waiting for the network is told when it lands`() = runTest {
        failWith(ApiError.ServerUnreachable)
        outbox.enqueuePosition(position)
        flusher().kick()
        runCurrent()
        assertThat(changes.version.value).isEqualTo(0)

        succeed()
        advanceTimeBy(policy.firstWait)
        runCurrent()

        assertThat(outbox.positions).isEmpty()
        assertThat(changes.version.value).isEqualTo(1)
    }

    /** An outbox whose reads throw while [broken]. */
    private class BreakingOutbox(private val real: Outbox) : Outbox by real {
        var broken = true

        override suspend fun pending(): OutboxContent = if (broken) error("broken") else real.pending()
    }
}
