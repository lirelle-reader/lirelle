package app.tfdn.lirelle.feature.reader

import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.ids.ReadId
import app.tfdn.lirelle.core.library.model.ReadingPosition
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.reader.sync.LocalPosition
import app.tfdn.lirelle.core.testing.MainDispatcherRule
import app.tfdn.lirelle.feature.reader.bridge.HostMessage
import app.tfdn.lirelle.feature.reader.bridge.PageMessage
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** A finished book read again: opened at its beginning, whatever position it was left at. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderReadAgainTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    @get:Rule
    val folder = TemporaryFolder()

    /** The end of the book, where the server holds it. */
    private val finished = ReadingPosition(FileId("1"), ThirdCfi, 100.0, Instant.parse("2026-11-14T09:00:00Z"))

    private fun TestScope.harness() = ReaderHarness(this, folder.root)

    @Test
    fun `a book read again opens at its beginning, whatever the server and the device hold`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            harness.repository.positionResult = ApiResult.Success(finished)
            harness.positions.put(LocalPosition(FileId("1"), ThirdCfi, 100.0, checkNotNull(finished.updatedAt)))

            harness.readingAgain()
            advanceTimeBy(10.seconds)

            assertThat(harness.engine.sent)
                .containsExactly(HostMessage.Open("/book/first/", cfi = null, fraction = 0.0, harness.pageSettings))
            // Opening is not reading: the end stays the position until a page is turned.
            assertThat(harness.positions.positions[FileId("1")]?.cfi).isEqualTo(ThirdCfi)
            assertThat(harness.outbox.log).isEmpty()
            assertThat(harness.repository.savedPositions).isEmpty()
        }

    @Test
    fun `a book read again is not offered the end it was left at`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        harness.repository.positionResult = ApiResult.Success(finished)
        val viewModel = harness.readingAgain()

        viewModel.onResumed()

        assertThat(viewModel.state.value.newerPosition).isNull()
    }

    @Test
    fun `a page turned in a book read again is sent as any other`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        harness.repository.positionResult = ApiResult.Success(finished)
        val viewModel = harness.readingAgain()

        harness.engine.emit(relocated(cfi = SecondCfi, fraction = 0.02))
        advanceTimeBy(3.seconds)
        viewModel.onResumed()

        assertThat(harness.repository.savedPositions)
            .containsExactly(ReadingPosition(FileId("1"), SecondCfi, 2.0, updatedAt = null))
        assertThat(viewModel.state.value.newerPosition).isNull()
    }

    @Test
    fun `a book opened as usual still opens at the end the server holds`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        harness.repository.positionResult = ApiResult.Success(finished)
        harness.viewModel().onEngine(harness.engine)

        harness.engine.emit(PageMessage.Ready)

        assertThat(harness.engine.sent)
            .containsExactly(HostMessage.Open("/book/first/", cfi = ThirdCfi, fraction = 1.0, harness.pageSettings))
    }

    @Test
    fun `a finished book read again from its beginning waits for its new reading`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        harness.repository.finishedReadingResult = ApiResult.Success(ReadId("70"))
        harness.repository.positionResult = ApiResult.Success(finished)
        val viewModel = harness.readingAgain()
        harness.engine.emit(relocated(cfi = ThirdCfi, fraction = 1.0, atEnd = true))
        harness.engine.emit(PageMessage.PastEnd)
        viewModel.setRating(4)

        viewModel.closeEndPanel()
        advanceTimeBy(FinishedReadingWait * 2)
        runCurrent()
        // Still the reading of last time, whose rating stays as it is.
        assertThat(harness.repository.calls.filter { it.startsWith("rate") }).isEmpty()
        assertThat(viewModel.state.value.endPanel?.sending).isTrue()

        harness.repository.finishedReadingResult = ApiResult.Success(ReadId("77"))
        advanceTimeBy(FinishedReadingWait)
        runCurrent()
        assertThat(harness.repository.calls.filter { it.startsWith("rate") }).containsExactly("rate 77 4")
    }
}
