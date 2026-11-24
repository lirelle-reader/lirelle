package app.tfdn.lirelle.feature.reader

import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.ReadingPosition
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.testing.MainDispatcherRule
import app.tfdn.lirelle.feature.reader.bridge.HostMessage
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The position and the reading session: what is sent, when, and in which order. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderSyncTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    @get:Rule
    val folder = TemporaryFolder()

    private fun TestScope.harness() = ReaderHarness(this, folder.root)

    private val elsewhere = ReadingPosition(FileId("1"), ThirdCfi, 80.0, Instant.parse("2026-11-14T11:00:00Z"))

    @Test
    fun `the place a book opens at is neither kept nor sent`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        harness.reading()

        advanceTimeBy(10.seconds)

        assertThat(harness.positions.positions).isEmpty()
        assertThat(harness.outbox.log).isEmpty()
        assertThat(harness.repository.savedPositions).isEmpty()
    }

    @Test
    fun `a move is kept at once and sent once the page has stayed still`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        harness.reading()

        harness.engine.emit(relocated(cfi = SecondCfi, fraction = 0.25))

        assertThat(harness.positions.positions[FileId("1")]?.cfi).isEqualTo(SecondCfi)
        advanceTimeBy(2.seconds - 1.milliseconds)
        assertThat(harness.repository.savedPositions).isEmpty()

        advanceTimeBy(2.milliseconds)

        assertThat(harness.repository.savedPositions)
            .containsExactly(ReadingPosition(FileId("1"), SecondCfi, 25.0, updatedAt = null))
    }

    @Test
    fun `the end of the book is the whole of it, whatever fraction the page gives`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()

        harness.engine.emit(relocated(cfi = ThirdCfi, fraction = 0.92, atEnd = true))
        advanceTimeBy(3.seconds)

        assertThat(viewModel.state.value.location?.fraction).isEqualTo(1.0)
        assertThat(harness.repository.savedPositions)
            .containsExactly(ReadingPosition(FileId("1"), ThirdCfi, 100.0, updatedAt = null))
    }

    @Test
    fun `going to the background sends the position, then the session so far`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()
        harness.clock.advance(30.seconds)
        harness.engine.emit(relocated(cfi = SecondCfi, fraction = 0.25))

        viewModel.onPaused()

        assertThat(harness.outbox.log).hasSize(2)
        assertThat(harness.outbox.log[0]).isEqualTo("position 1")
        assertThat(harness.outbox.log[1]).startsWith("session 1 ")
        val (fileId, session) = harness.repository.savedSessions.single()
        assertThat(fileId).isEqualTo(FileId("1"))
        assertThat(session.activeSeconds).isEqualTo(30)
        assertThat(session.endPercent).isEqualTo(25.0)
        assertThat(session.progressDelta).isWithin(0.0001).of(15.0)
        assertThat(harness.repository.calls.indexOf("savePosition 1"))
            .isLessThan(harness.repository.calls.indexOfFirst { it.startsWith("saveSession") })
    }

    @Test
    fun `a session too short to count is not sent`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()
        harness.clock.advance(5.seconds)
        harness.engine.emit(relocated(cfi = SecondCfi, fraction = 0.25))

        viewModel.onPaused()
        viewModel.onLeave()

        assertThat(harness.outbox.log).containsExactly("position 1")
    }

    @Test
    fun `back within five minutes the same session goes on, and leaving ends it`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()
        harness.clock.advance(30.seconds)
        harness.engine.emit(relocated(cfi = SecondCfi, fraction = 0.25))
        viewModel.onPaused()
        harness.clock.advance(2.minutes)

        viewModel.onResumed()
        harness.clock.advance(20.seconds)
        harness.engine.emit(relocated(cfi = ThirdCfi, fraction = 0.3))
        viewModel.onLeave()

        val sessions = harness.repository.savedSessions.map { it.second }
        assertThat(sessions).hasSize(2)
        assertThat(sessions[1].sessionId).isEqualTo(sessions[0].sessionId)
        assertThat(sessions[1].activeSeconds).isEqualTo(50)
    }

    @Test
    fun `back after five minutes the session is closed where it stopped and another starts`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            val viewModel = harness.reading()
            harness.clock.advance(30.seconds)
            harness.engine.emit(relocated(cfi = SecondCfi, fraction = 0.25))
            viewModel.onPaused()
            harness.clock.advance(6.minutes)

            viewModel.onResumed()
            harness.engine.emit(relocated(cfi = ThirdCfi, fraction = 0.3))
            harness.clock.advance(40.seconds)
            harness.engine.emit(relocated(cfi = SecondCfi, fraction = 0.35))
            viewModel.onLeave()

            val sessions = harness.repository.savedSessions.map { it.second }
            assertThat(sessions.map { it.activeSeconds }).containsExactly(30, 30, 40).inOrder()
            assertThat(sessions[1].sessionId).isEqualTo(sessions[0].sessionId)
            assertThat(sessions[2].sessionId).isNotEqualTo(sessions[0].sessionId)
        }

    @Test
    fun `leaving sends the position without waiting, then the session`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()
        harness.clock.advance(1.minutes)
        harness.engine.emit(relocated(cfi = SecondCfi, fraction = 0.25))

        viewModel.onLeave()

        assertThat(harness.outbox.log).hasSize(2)
        assertThat(harness.outbox.log[0]).isEqualTo("position 1")
        assertThat(harness.outbox.log[1]).startsWith("session 1 ")
        assertThat(harness.repository.savedPositions)
            .containsExactly(ReadingPosition(FileId("1"), SecondCfi, 25.0, updatedAt = null))
        assertThat(harness.repository.savedSessions.single().second.activeSeconds).isEqualTo(60)
        assertThat(harness.cache.released).containsExactly(harness.key)
    }

    @Test
    fun `nothing is written after the reader is left`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()
        harness.clock.advance(1.minutes)
        harness.engine.emit(relocated(cfi = SecondCfi, fraction = 0.25))
        viewModel.onLeave()
        val written = harness.outbox.log.size

        viewModel.onLeave()
        viewModel.onPaused()
        viewModel.onResumed()
        harness.engine.emit(relocated(cfi = ThirdCfi, fraction = 0.3))
        advanceTimeBy(10.seconds)

        assertThat(harness.outbox.log).hasSize(written)
        assertThat(harness.positions.positions[FileId("1")]?.cfi).isEqualTo(SecondCfi)
    }

    @Test
    fun `leaving a book that was only opened sends nothing`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()
        harness.clock.advance(1.minutes)

        viewModel.onLeave()

        assertThat(harness.outbox.log.filter { it.startsWith("position") }).isEmpty()
        assertThat(harness.repository.savedPositions).isEmpty()
    }

    @Test
    fun `a newer position on the server is offered when the reader comes back`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()
        assertThat(viewModel.state.value.newerPosition).isNull()
        harness.repository.positionResult = ApiResult.Success(elsewhere)

        viewModel.onResumed()

        assertThat(viewModel.state.value.newerPosition).isEqualTo(OfferedPosition(80.0, elsewhere.updatedAt))
        assertThat(harness.engine.sent).hasSize(1)
    }

    @Test
    fun `go there moves the page, drops what was to be sent and offers to go back`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            val viewModel = harness.reading()
            harness.engine.emit(relocated(cfi = SecondCfi, fraction = 0.25))
            harness.repository.positionResult = ApiResult.Success(elsewhere)
            viewModel.onResumed()

            viewModel.acceptNewerPosition()
            advanceTimeBy(10.seconds)

            assertThat(harness.engine.sent.last()).isEqualTo(HostMessage.GoTo.Cfi(ThirdCfi))
            assertThat(viewModel.state.value.newerPosition).isNull()
            assertThat(viewModel.state.value.goBackShown).isTrue()
            assertThat(harness.repository.savedPositions).isEmpty()
            assertThat(harness.positions.positions[FileId("1")]?.cfi).isEqualTo(ThirdCfi)

            viewModel.onResumed()
            assertThat(viewModel.state.value.newerPosition).isNull()
        }

    @Test
    fun `go there to a place known by its percentage only`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()
        harness.repository.positionResult = ApiResult.Success(elsewhere.copy(cfi = null))
        viewModel.onResumed()

        viewModel.acceptNewerPosition()

        assertThat(harness.engine.sent.last()).isEqualTo(HostMessage.GoTo.Fraction(0.8))
    }

    @Test
    fun `ignore dismisses the offer until a still newer position appears`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()
        harness.repository.positionResult = ApiResult.Success(elsewhere)
        viewModel.onResumed()

        viewModel.ignoreNewerPosition()
        assertThat(viewModel.state.value.newerPosition).isNull()
        assertThat(harness.engine.sent).hasSize(1)

        viewModel.onResumed()
        assertThat(viewModel.state.value.newerPosition).isNull()

        val later = elsewhere.copy(percent = 90.0, cfi = SecondCfi, updatedAt = Instant.parse("2026-11-14T12:00:00Z"))
        harness.repository.positionResult = ApiResult.Success(later)
        viewModel.onResumed()
        assertThat(viewModel.state.value.newerPosition).isEqualTo(OfferedPosition(90.0, later.updatedAt))
    }

    @Test
    fun `coming to the front before the book is there asks nothing`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        harness.cache.states = emptyList()
        val viewModel = harness.viewModel()

        viewModel.onResumed()
        viewModel.onPaused()
        viewModel.acceptNewerPosition()
        viewModel.ignoreNewerPosition()

        assertThat(harness.repository.calls).isEmpty()
    }
}
