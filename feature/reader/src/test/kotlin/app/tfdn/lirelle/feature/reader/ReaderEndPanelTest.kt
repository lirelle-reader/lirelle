package app.tfdn.lirelle.feature.reader

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.ids.ReadId
import app.tfdn.lirelle.core.library.model.NextVolume
import app.tfdn.lirelle.core.library.model.ReadingPosition
import app.tfdn.lirelle.core.library.model.SaveOutcome
import app.tfdn.lirelle.core.library.model.SavedPosition
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.testing.MainDispatcherRule
import app.tfdn.lirelle.feature.reader.bridge.FakeReaderEngine
import app.tfdn.lirelle.feature.reader.bridge.PageDirection
import app.tfdn.lirelle.feature.reader.bridge.PageMessage
import com.google.common.truth.Truth.assertThat
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** How long the page stays still before a position is sent. */
private val PositionQuiet = 2.seconds

/** A place close to the end of a book, where some libraries count it as finished and others not yet. */
private const val NearlyAtTheEnd = 0.985

/** The panel at the end of the book: at each turn past it, the rating after the last position, the next volume. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderEndPanelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    @get:Rule
    val folder = TemporaryFolder()

    private val next = NextVolume(bookId = BookId("13"), title = "The Next One", seriesIndex = "3")

    private fun TestScope.harness() = ReaderHarness(this, folder.root).apply {
        repository.nextVolumeResult = ApiResult.Success(next)
    }

    private fun ReaderHarness.atEnd(): ReaderViewModel {
        val viewModel = reading()
        engine.emit(relocated(cfi = ThirdCfi, fraction = 1.0, atEnd = true))
        engine.emit(PageMessage.PastEnd)
        return viewModel
    }

    private val ReaderViewModel.panel get() = state.value.endPanel

    /** Lets [time] pass, what is due at its very end included. */
    private fun TestScope.pass(time: Duration) {
        advanceTimeBy(time)
        runCurrent()
    }

    /** Every event the reader sends from now on, as it comes. */
    private fun TestScope.eventsOf(viewModel: ReaderViewModel): List<ReaderEvent> {
        val events = mutableListOf<ReaderEvent>()
        backgroundScope.launch { viewModel.events.collect { events += it } }
        runCurrent()
        return events
    }

    private fun ReaderHarness.callsFor(prefix: String) = repository.calls.filter { it.startsWith(prefix) }

    /** The times the finished reading was asked for a rating: all but the first, asked when the book opened. */
    private fun ReaderHarness.askedToRate() = callsFor("finishedReadingId").drop(1)

    @Test
    fun `turning past the last page opens the panel, with the next volume of the series`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            val viewModel = harness.reading()
            harness.engine.emit(relocated(cfi = ThirdCfi, fraction = 1.0, atEnd = true))
            viewModel.toggleControls()
            assertThat(viewModel.panel).isNull()

            harness.engine.emit(PageMessage.PastEnd)

            assertThat(viewModel.panel).isEqualTo(EndPanel(nextVolume = next))
            assertThat(viewModel.state.value.controlsShown).isFalse()
            assertThat(harness.repository.calls).contains("nextVolume 4 12")
        }

    @Test
    fun `arriving at the last page sends the final position and opens nothing`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()

        harness.engine.emit(relocated(cfi = ThirdCfi, fraction = 1.0, atEnd = true))
        pass(PositionQuiet)

        assertThat(viewModel.panel).isNull()
        assertThat(viewModel.state.value.location?.atEnd).isTrue()
        assertThat(harness.callsFor("nextVolume")).isEmpty()
        // The server marks the book as read from this position.
        assertThat(harness.repository.savedPositions)
            .containsExactly(ReadingPosition(FileId("1"), ThirdCfi, 100.0, updatedAt = null))
    }

    @Test
    fun `a book that reopens at its end opens the panel only when the page is turned past it`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            val viewModel = harness.viewModel()
            viewModel.onEngine(harness.engine)
            harness.engine.emit(PageMessage.Ready)
            harness.engine.emit(PageMessage.Opened(SampleToc, PageDirection.Ltr, fixedLayout = false, title = null))

            harness.engine.emit(relocated(cfi = ThirdCfi, fraction = 1.0, atEnd = true, moved = false))
            assertThat(viewModel.panel).isNull()

            harness.engine.emit(PageMessage.PastEnd)
            assertThat(viewModel.panel).isNotNull()
        }

    @Test
    fun `a book outside a series, or without a next volume, has none to offer`() = runTest(mainRule.dispatcher) {
        val alone = harness()
        alone.detail = alone.detail.copy(series = null)
        assertThat(alone.atEnd().panel).isEqualTo(EndPanel())
        assertThat(alone.callsFor("nextVolume")).isEmpty()

        val last = harness()
        last.repository.nextVolumeResult = ApiResult.Success(null)
        assertThat(last.atEnd().panel).isEqualTo(EndPanel())

        val unreachable = harness()
        unreachable.repository.nextVolumeResult = ApiResult.Failure(ApiError.ServerUnreachable)
        assertThat(unreachable.atEnd().panel).isEqualTo(EndPanel())
    }

    @Test
    fun `closing the book without stars sends nothing and leaves the reader, once`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.atEnd()
        val events = eventsOf(viewModel)

        viewModel.closeEndPanel()
        pass(FinishedReadingWait * FinishedReadingAttempts)

        assertThat(viewModel.panel).isNull()
        assertThat(harness.askedToRate()).isEmpty()
        assertThat(harness.callsFor("rate")).isEmpty()
        assertThat(events).containsExactly(ReaderEvent.Leave)
    }

    @Test
    fun `closing the book with stars rates it, and only then leaves the reader, once`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            val viewModel = harness.atEnd()
            val events = eventsOf(viewModel)
            harness.repository.finishedReadingResult = ApiResult.Success(ReadId("77"))
            viewModel.setRating(4)

            viewModel.closeEndPanel()
            // A second tap while the rating is on its way asks for nothing more.
            viewModel.closeEndPanel()

            assertThat(viewModel.panel?.sending).isTrue()
            assertThat(harness.callsFor("rate")).isEmpty()
            assertThat(events).isEmpty()

            pass(FinishedReadingWait)

            assertThat(harness.callsFor("rate")).containsExactly("rate 77 4")
            assertThat(viewModel.panel).isNull()
            assertThat(events).containsExactly(ReaderEvent.Leave)
        }

    @Test
    fun `closing the book on a rating that fails stays in the reader, and trying again leaves it`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            val viewModel = harness.atEnd()
            val events = eventsOf(viewModel)
            viewModel.setRating(4)

            viewModel.closeEndPanel()
            pass(FinishedReadingWait * FinishedReadingAttempts)

            assertThat(viewModel.panel).isEqualTo(EndPanel(rating = 4, nextVolume = next, failed = true))
            assertThat(harness.callsFor("rate")).isEmpty()
            assertThat(events).isEmpty()

            harness.repository.finishedReadingResult = ApiResult.Success(ReadId("77"))
            viewModel.closeEndPanel()
            pass(FinishedReadingWait)

            assertThat(harness.callsFor("rate")).containsExactly("rate 77 4")
            assertThat(viewModel.panel).isNull()
            assertThat(events).containsExactly(ReaderEvent.Leave)
        }

    @Test
    fun `stars can be chosen, changed and cleared, within one to five`() = runTest(mainRule.dispatcher) {
        val viewModel = harness().atEnd()

        viewModel.setRating(4)
        assertThat(viewModel.panel?.rating).isEqualTo(4)
        viewModel.setRating(9)
        assertThat(viewModel.panel?.rating).isEqualTo(5)
        viewModel.setRating(null)
        assertThat(viewModel.panel?.rating).isNull()
    }

    @Test
    fun `closing with stars sends the position, waits for the finished reading, then rates it`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            val viewModel = harness.atEnd()
            viewModel.setRating(4)

            viewModel.closeEndPanel()

            assertThat(viewModel.panel?.sending).isTrue()
            assertThat(harness.repository.savedPositions.single().percent).isEqualTo(100.0)
            assertThat(harness.askedToRate()).isEmpty()

            // The server has not closed the reading yet: asked once, then again.
            pass(FinishedReadingWait)
            assertThat(harness.askedToRate()).containsExactly("finishedReadingId 12")
            assertThat(viewModel.panel?.sending).isTrue()

            harness.repository.finishedReadingResult = ApiResult.Success(ReadId("77"))
            pass(FinishedReadingWait)

            assertThat(harness.callsFor("rate")).containsExactly("rate 77 4")
            assertThat(viewModel.panel).isNull()
            assertThat(harness.repository.calls.indexOf("savePosition 1"))
                .isLessThan(harness.repository.calls.lastIndexOf("finishedReadingId 12"))
        }

    @Test
    fun `the finished reading is asked once when the book opens, and not again for a new web view`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            val viewModel = harness.reading()
            assertThat(harness.callsFor("finishedReadingId")).containsExactly("finishedReadingId 12")

            val second = FakeReaderEngine(bookBase = "/book/second/")
            viewModel.onEngine(second)
            second.emit(PageMessage.Ready)
            second.emit(PageMessage.Opened(SampleToc, PageDirection.Ltr, fixedLayout = false, title = null))

            assertThat(harness.callsFor("finishedReadingId")).hasSize(1)
        }

    @Test
    fun `a first reading is rated as soon as the server lists it as finished`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.atEnd()
        harness.repository.finishedReadingResult = ApiResult.Success(ReadId("77"))
        viewModel.setRating(4)

        viewModel.closeEndPanel()
        pass(FinishedReadingWait)

        assertThat(harness.callsFor("rate")).containsExactly("rate 77 4")
        assertThat(viewModel.panel).isNull()
    }

    @Test
    fun `a reread is rated once its own reading is finished, not the one before`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        harness.repository.finishedReadingResult = ApiResult.Success(ReadId("70"))
        val viewModel = harness.atEnd()
        viewModel.setRating(4)

        viewModel.closeEndPanel()
        // The server still answers with the reading finished last year.
        pass(FinishedReadingWait * 2)
        assertThat(harness.askedToRate()).hasSize(2)
        assertThat(harness.callsFor("rate")).isEmpty()
        assertThat(viewModel.panel?.sending).isTrue()

        harness.repository.finishedReadingResult = ApiResult.Success(ReadId("77"))
        pass(FinishedReadingWait)

        assertThat(harness.askedToRate()).hasSize(3)
        assertThat(harness.callsFor("rate")).containsExactly("rate 77 4")
        assertThat(viewModel.panel).isNull()
    }

    @Test
    fun `when no new reading ever shows as finished, the rating goes to the one there was`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            harness.repository.finishedReadingResult = ApiResult.Success(ReadId("70"))
            val viewModel = harness.atEnd()
            viewModel.setRating(4)

            viewModel.closeEndPanel()
            pass(FinishedReadingWait * FinishedReadingAttempts - 1.milliseconds)
            assertThat(harness.callsFor("rate")).isEmpty()
            assertThat(viewModel.panel?.sending).isTrue()
            pass(1.milliseconds)

            assertThat(harness.askedToRate()).hasSize(FinishedReadingAttempts)
            assertThat(harness.callsFor("rate")).containsExactly("rate 70 4")
            assertThat(viewModel.panel).isNull()
        }

    /** A reader whose book opens at [fraction], as one reopened where it was left. */
    private fun ReaderHarness.reopenedAt(fraction: Double): ReaderViewModel {
        val viewModel = viewModel()
        viewModel.onEngine(engine)
        engine.emit(PageMessage.Ready)
        engine.emit(PageMessage.Opened(SampleToc, PageDirection.Ltr, fixedLayout = false, title = null))
        engine.emit(relocated(cfi = ThirdCfi, fraction = fraction, atEnd = fraction == 1.0, moved = false))
        return viewModel
    }

    @Test
    fun `a finished book reopened at its end is rated on the reading it already has, without waiting`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            harness.repository.finishedReadingResult = ApiResult.Success(ReadId("70"))
            val viewModel = harness.reopenedAt(1.0)
            harness.engine.emit(PageMessage.PastEnd)
            viewModel.setRating(4)

            viewModel.closeEndPanel()
            runCurrent()

            // No reading will be closed by this opening: there is none to wait for.
            assertThat(harness.askedToRate()).isEmpty()
            assertThat(harness.callsFor("rate")).containsExactly("rate 70 4")
            assertThat(viewModel.panel).isNull()
        }

    @Test
    fun `a finished book reopened just before its end, then finished, waits for its new reading`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            harness.repository.finishedReadingResult = ApiResult.Success(ReadId("70"))
            // Where the server counts a book as finished is not known: this place may be before it.
            val viewModel = harness.reopenedAt(NearlyAtTheEnd)
            harness.engine.emit(relocated(cfi = ThirdCfi, fraction = 1.0, atEnd = true))
            harness.engine.emit(PageMessage.PastEnd)
            viewModel.setRating(5)

            viewModel.closeEndPanel()
            pass(FinishedReadingWait * 2)
            assertThat(harness.callsFor("rate")).isEmpty()

            harness.repository.finishedReadingResult = ApiResult.Success(ReadId("77"))
            pass(FinishedReadingWait)
            assertThat(harness.callsFor("rate")).containsExactly("rate 77 5")
        }

    @Test
    fun `a finished book reopened at its end, then read again from before it, waits for its new reading`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            harness.repository.finishedReadingResult = ApiResult.Success(ReadId("70"))
            val viewModel = harness.reopenedAt(1.0)
            // Back into the book, then to the end again.
            harness.engine.emit(relocated(cfi = SecondCfi, fraction = NearlyAtTheEnd))
            harness.engine.emit(relocated(cfi = ThirdCfi, fraction = 1.0, atEnd = true))
            harness.engine.emit(PageMessage.PastEnd)
            viewModel.setRating(4)

            viewModel.closeEndPanel()
            pass(FinishedReadingWait * 2)
            // The old rating is not this reading's to change while a new one may still be closed.
            assertThat(harness.callsFor("rate")).isEmpty()

            harness.repository.finishedReadingResult = ApiResult.Success(ReadId("77"))
            pass(FinishedReadingWait)
            assertThat(harness.callsFor("rate")).containsExactly("rate 77 4")
        }

    @Test
    fun `a finished book paged back a little from its end, then finished again, is rated on the reading it has`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            harness.repository.finishedReadingResult = ApiResult.Success(ReadId("70"))
            val viewModel = harness.reopenedAt(1.0)
            // Less than ten points back: the server starts no new reading, and will close none.
            harness.engine.emit(relocated(cfi = SecondCfi, fraction = 0.95))
            harness.engine.emit(relocated(cfi = ThirdCfi, fraction = 1.0, atEnd = true))
            harness.engine.emit(PageMessage.PastEnd)
            viewModel.setRating(4)

            viewModel.closeEndPanel()
            pass(FinishedReadingWait * FinishedReadingAttempts)

            assertThat(harness.askedToRate()).hasSize(FinishedReadingAttempts)
            assertThat(harness.callsFor("rate")).containsExactly("rate 70 4")
            assertThat(viewModel.panel).isNull()
        }

    @Test
    fun `a book reopened at its end with no finished reading waits for one, whichever it is`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            val viewModel = harness.reopenedAt(1.0)
            harness.engine.emit(PageMessage.PastEnd)
            viewModel.setRating(4)

            viewModel.closeEndPanel()
            pass(FinishedReadingWait)
            assertThat(harness.askedToRate()).hasSize(1)
            assertThat(harness.callsFor("rate")).isEmpty()
            assertThat(viewModel.panel?.sending).isTrue()

            harness.repository.finishedReadingResult = ApiResult.Success(ReadId("77"))
            pass(FinishedReadingWait)

            assertThat(harness.callsFor("rate")).containsExactly("rate 77 4")
            assertThat(viewModel.panel).isNull()
        }

    @Test
    fun `a rating of the existing reading that the server refuses is a failure, which can be tried again`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            harness.repository.finishedReadingResult = ApiResult.Success(ReadId("70"))
            val viewModel = harness.reopenedAt(1.0)
            harness.engine.emit(PageMessage.PastEnd)
            viewModel.setRating(4)
            harness.repository.rateResult = ApiResult.Failure(ApiError.ServerUnreachable)
            val told = harness.changes.version.value

            viewModel.closeEndPanel()
            runCurrent()
            assertThat(viewModel.panel?.failed).isTrue()
            // A rating the server did not take changed nothing the other screens show.
            assertThat(harness.changes.version.value).isEqualTo(told)

            harness.repository.rateResult = ApiResult.Success(Unit)
            viewModel.closeEndPanel()
            runCurrent()

            assertThat(harness.changes.version.value).isEqualTo(told + 1)

            assertThat(harness.callsFor("rate")).containsExactly("rate 70 4", "rate 70 4")
            assertThat(viewModel.panel).isNull()
        }

    @Test
    fun `when the earlier reading could not be asked, it is asked before the final position is sent`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            harness.repository.finishedReadingResult = ApiResult.Failure(ApiError.ServerUnreachable)
            val viewModel = harness.reading()
            harness.engine.emit(relocated(cfi = ThirdCfi, fraction = 1.0, atEnd = true))
            harness.engine.emit(PageMessage.PastEnd)
            // A reread: the reading of last year is the finished one until the server closes this one.
            harness.repository.finishedReadingResult = ApiResult.Success(ReadId("70"))
            viewModel.setRating(4)

            viewModel.closeEndPanel()

            val calls = harness.repository.calls
            assertThat(calls.lastIndexOf("finishedReadingId 12")).isLessThan(calls.indexOf("savePosition 1"))
            pass(FinishedReadingWait * 2)
            assertThat(harness.callsFor("rate")).isEmpty()
            assertThat(viewModel.panel?.sending).isTrue()

            harness.repository.finishedReadingResult = ApiResult.Success(ReadId("77"))
            pass(FinishedReadingWait)

            assertThat(harness.callsFor("rate")).containsExactly("rate 77 4")
            assertThat(viewModel.panel).isNull()
        }

    @Test
    fun `when the earlier reading cannot be asked at all, nothing is rated, and it can be tried again`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            harness.repository.finishedReadingResult = ApiResult.Failure(ApiError.ServerUnreachable)
            val viewModel = harness.atEnd()
            viewModel.setRating(4)

            viewModel.closeEndPanel()
            pass(FinishedReadingWait * FinishedReadingAttempts)

            assertThat(viewModel.panel).isEqualTo(EndPanel(rating = 4, nextVolume = next, failed = true))
            assertThat(harness.callsFor("finishedReadingId")).hasSize(2)
            assertThat(harness.callsFor("rate")).isEmpty()

            harness.repository.finishedReadingResult = ApiResult.Success(null)
            viewModel.closeEndPanel()
            harness.repository.finishedReadingResult = ApiResult.Success(ReadId("77"))
            pass(FinishedReadingWait)

            assertThat(harness.callsFor("rate")).containsExactly("rate 77 4")
        }

    @Test
    fun `an answer about the earlier reading that comes once the rating has started is not trusted`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            val viewModel = harness.viewModel()
            viewModel.onEngine(harness.engine)
            harness.engine.emit(PageMessage.Ready)
            // Asked when the book opens, answered only once the rating has started.
            val gate = CompletableDeferred<Unit>()
            harness.repository.gate = gate
            harness.repository.finishedReadingResult = ApiResult.Success(ReadId("70"))
            harness.engine.emit(PageMessage.Opened(SampleToc, PageDirection.Ltr, fixedLayout = false, title = null))
            harness.engine.emit(relocated(moved = false))
            harness.engine.emit(relocated(cfi = ThirdCfi, fraction = 1.0, atEnd = true))
            harness.engine.emit(PageMessage.PastEnd)
            viewModel.setRating(4)
            viewModel.closeEndPanel()
            // Asked again for the rating, and nothing sent until that is answered.
            assertThat(harness.callsFor("finishedReadingId")).hasSize(2)
            assertThat(harness.callsFor("savePosition")).isEmpty()

            harness.repository.gate = null
            gate.complete(Unit)
            pass(FinishedReadingWait)
            assertThat(harness.callsFor("rate")).isEmpty()
            harness.repository.finishedReadingResult = ApiResult.Success(ReadId("77"))
            pass(FinishedReadingWait)

            assertThat(harness.callsFor("rate")).containsExactly("rate 77 4")
        }

    @Test
    fun `the finished reading is not asked for before the final position has reached the server`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            val viewModel = harness.atEnd()
            harness.repository.savePositionResult = ApiResult.Failure(ApiError.ServerUnreachable)
            harness.repository.finishedReadingResult = ApiResult.Success(ReadId("77"))
            viewModel.setRating(4)

            viewModel.closeEndPanel()
            pass(3.seconds)
            assertThat(harness.askedToRate()).isEmpty()
            assertThat(viewModel.panel?.sending).isTrue()

            // The outbox tries again, and the position lands.
            harness.repository.savePositionResult = ApiResult.Success(SavedPosition(SaveOutcome.Saved))
            pass(8.seconds)
            assertThat(harness.outbox.positions).isEmpty()

            assertThat(harness.callsFor("rate")).containsExactly("rate 77 4")
            assertThat(viewModel.panel).isNull()
        }

    @Test
    fun `a final position that cannot be sent is a failure, which can be tried again`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            val viewModel = harness.atEnd()
            harness.repository.savePositionResult = ApiResult.Failure(ApiError.ServerUnreachable)
            harness.repository.finishedReadingResult = ApiResult.Success(ReadId("77"))
            viewModel.setRating(4)

            viewModel.closeEndPanel()
            pass(FinishedReadingWait + PositionSentWait * PositionSentAttempts - 1.milliseconds)
            assertThat(viewModel.panel?.sending).isTrue()
            pass(1.milliseconds)

            assertThat(viewModel.panel).isEqualTo(EndPanel(rating = 4, nextVolume = next, failed = true))
            assertThat(harness.askedToRate()).isEmpty()
            assertThat(harness.callsFor("rate")).isEmpty()

            // Trying again has the position sent at once, without waiting for the outbox's next attempt.
            harness.repository.savePositionResult = ApiResult.Success(SavedPosition(SaveOutcome.Saved))
            viewModel.closeEndPanel()
            pass(FinishedReadingWait)

            assertThat(harness.callsFor("rate")).containsExactly("rate 77 4")
            assertThat(viewModel.panel).isNull()
        }

    @Test
    fun `the buttons wait while the rating is on its way`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.atEnd()
        viewModel.setRating(4)
        viewModel.closeEndPanel()

        viewModel.closeEndPanel()
        viewModel.openNextVolume()
        harness.repository.finishedReadingResult = ApiResult.Success(ReadId("77"))
        pass(FinishedReadingWait * FinishedReadingAttempts)

        assertThat(harness.callsFor("rate")).hasSize(1)
    }

    @Test
    fun `a reading the server never finishes is a failure, which can be tried again`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            val viewModel = harness.atEnd()
            viewModel.setRating(2)

            viewModel.closeEndPanel()
            pass(FinishedReadingWait * FinishedReadingAttempts - 1.milliseconds)
            assertThat(viewModel.panel?.sending).isTrue()
            pass(2.milliseconds)

            assertThat(viewModel.panel).isEqualTo(EndPanel(rating = 2, nextVolume = next, failed = true))
            assertThat(harness.askedToRate()).hasSize(FinishedReadingAttempts)
            assertThat(harness.callsFor("rate")).isEmpty()

            harness.repository.finishedReadingResult = ApiResult.Success(ReadId("77"))
            viewModel.closeEndPanel()
            assertThat(viewModel.panel?.failed).isFalse()
            pass(FinishedReadingWait)

            assertThat(harness.callsFor("rate")).containsExactly("rate 77 2")
            assertThat(viewModel.panel).isNull()
        }

    @Test
    fun `a server that cannot be asked, or refuses the rating, is a failure`() = runTest(mainRule.dispatcher) {
        val unreachable = harness()
        unreachable.repository.finishedReadingResult = ApiResult.Failure(ApiError.ServerUnreachable)
        val first = unreachable.atEnd()
        first.setRating(3)
        first.closeEndPanel()
        pass(FinishedReadingWait)
        assertThat(first.panel?.failed).isTrue()
        assertThat(unreachable.askedToRate()).hasSize(1)

        val refusing = harness()
        refusing.repository.rateResult = ApiResult.Failure(ApiError.Unexpected)
        val second = refusing.atEnd()
        refusing.repository.finishedReadingResult = ApiResult.Success(ReadId("77"))
        second.setRating(3)
        second.closeEndPanel()
        pass(FinishedReadingWait)
        assertThat(second.panel?.failed).isTrue()
        assertThat(second.panel?.sending).isFalse()
    }

    @Test
    fun `a server that stops answering while the finished reading is waited for is a failure`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            harness.repository.finishedReadingResult = ApiResult.Success(ReadId("70"))
            val viewModel = harness.atEnd()
            viewModel.setRating(3)
            harness.repository.finishedReadingResult = ApiResult.Failure(ApiError.ServerUnreachable)

            viewModel.closeEndPanel()
            pass(FinishedReadingWait)

            assertThat(viewModel.panel?.failed).isTrue()
            assertThat(harness.askedToRate()).hasSize(1)
            // Not knowing is not "no new reading": the one there was is left alone.
            assertThat(harness.callsFor("rate")).isEmpty()
        }

    @Test
    fun `choosing other stars after a failure withdraws the message`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        harness.repository.finishedReadingResult = ApiResult.Failure(ApiError.ServerUnreachable)
        val viewModel = harness.atEnd()
        viewModel.setRating(3)
        viewModel.closeEndPanel()
        pass(FinishedReadingWait)

        viewModel.setRating(null)
        assertThat(viewModel.panel?.failed).isFalse()

        viewModel.closeEndPanel()
        assertThat(viewModel.panel).isNull()
    }

    @Test
    fun `the next volume sends the stars chosen, then asks for that book's page only`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.atEnd()
        val events = eventsOf(viewModel)
        harness.repository.finishedReadingResult = ApiResult.Success(ReadId("77"))
        viewModel.setRating(5)

        viewModel.openNextVolume()
        assertThat(events).isEmpty()
        pass(FinishedReadingWait)

        assertThat(harness.callsFor("rate")).containsExactly("rate 77 5")
        assertThat(viewModel.panel).isNull()
        assertThat(events).containsExactly(ReaderEvent.OpenBook(BookId("13")))
    }

    @Test
    fun `the next volume without stars opens that book's page at once`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.atEnd()
        val events = eventsOf(viewModel)

        viewModel.openNextVolume()
        pass(FinishedReadingWait * FinishedReadingAttempts)

        assertThat(viewModel.panel).isNull()
        assertThat(harness.askedToRate()).isEmpty()
        assertThat(events).containsExactly(ReaderEvent.OpenBook(BookId("13")))
    }

    @Test
    fun `a rating that fails keeps the reader where it is`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        harness.repository.finishedReadingResult = ApiResult.Failure(ApiError.ServerUnreachable)
        val viewModel = harness.atEnd()
        viewModel.setRating(5)

        viewModel.openNextVolume()
        pass(FinishedReadingWait)
        viewModel.leave()

        assertThat(viewModel.panel?.failed).isTrue()
        assertThat(viewModel.events.first()).isEqualTo(ReaderEvent.Leave)
    }

    @Test
    fun `back sends the stars chosen and closes the panel only, not the book`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.atEnd()
        val events = eventsOf(viewModel)
        harness.repository.finishedReadingResult = ApiResult.Success(ReadId("77"))
        viewModel.setRating(4)

        assertThat(viewModel.onBack()).isTrue()
        assertThat(viewModel.panel?.sending).isTrue()
        // While the rating is on its way, back is taken and does nothing more.
        assertThat(viewModel.onBack()).isTrue()
        pass(FinishedReadingWait)

        assertThat(harness.callsFor("rate")).containsExactly("rate 77 4")
        assertThat(viewModel.panel).isNull()
        assertThat(events).isEmpty()
        assertThat(viewModel.state.value.location?.atEnd).isTrue()
        // The panel is gone: the next back is the reader's to leave by.
        assertThat(viewModel.onBack()).isFalse()
    }

    @Test
    fun `back on a panel without stars closes it and sends nothing`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.atEnd()
        val events = eventsOf(viewModel)

        assertThat(viewModel.onBack()).isTrue()
        pass(FinishedReadingWait * FinishedReadingAttempts)

        assertThat(viewModel.panel).isNull()
        assertThat(harness.askedToRate()).isEmpty()
        assertThat(harness.callsFor("rate")).isEmpty()
        assertThat(events).isEmpty()
        assertThat(viewModel.onBack()).isFalse()
    }

    @Test
    fun `back on a rating that failed tries again`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.atEnd()
        val events = eventsOf(viewModel)
        viewModel.setRating(4)
        viewModel.closeEndPanel()
        pass(FinishedReadingWait * FinishedReadingAttempts)
        assertThat(viewModel.panel?.failed).isTrue()
        harness.repository.finishedReadingResult = ApiResult.Success(ReadId("77"))

        assertThat(viewModel.onBack()).isTrue()
        pass(FinishedReadingWait)

        assertThat(harness.callsFor("rate")).containsExactly("rate 77 4")
        assertThat(viewModel.panel).isNull()
        // Back closed the panel: the "Close the book" that failed before it left nothing behind.
        assertThat(events).isEmpty()
    }
}
