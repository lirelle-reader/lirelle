package app.tfdn.lirelle.feature.reader

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.ReadId
import app.tfdn.lirelle.core.library.model.NextVolume
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.testing.MainDispatcherRule
import app.tfdn.lirelle.feature.reader.bridge.PageMessage
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The end panel dismissed by a swipe down or a tap above it, as by the system's back; every turn
 * past the end opens it again.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderEndPanelDismissTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    @get:Rule
    val folder = TemporaryFolder()

    private val next = NextVolume(bookId = BookId("13"), title = "Next", seriesIndex = "3")

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

    private fun TestScope.eventsOf(viewModel: ReaderViewModel): List<ReaderEvent> {
        val events = mutableListOf<ReaderEvent>()
        backgroundScope.launch { viewModel.events.collect { events += it } }
        runCurrent()
        return events
    }

    private fun TestScope.waitForTheRating() {
        advanceTimeBy(FinishedReadingWait * FinishedReadingAttempts)
        runCurrent()
    }

    private fun ReaderHarness.rated() = repository.calls.filter { it.startsWith("rate") }

    @Test
    fun `dismissing the panel sends the stars and closes the panel only, once`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.atEnd()
        val events = eventsOf(viewModel)
        harness.repository.finishedReadingResult = ApiResult.Success(ReadId("77"))
        viewModel.setRating(4)

        viewModel.dismissEndPanel()
        assertThat(viewModel.panel?.sending).isTrue()
        viewModel.dismissEndPanel()
        waitForTheRating()

        assertThat(harness.rated()).containsExactly("rate 77 4")
        assertThat(viewModel.panel).isNull()
        assertThat(events).isEmpty()
        assertThat(viewModel.state.value.location?.atEnd).isTrue()
    }

    @Test
    fun `a dismissed panel comes back at the next turn past the end, with its stars and the next volume`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            val viewModel = harness.atEnd()
            harness.repository.finishedReadingResult = ApiResult.Success(ReadId("77"))
            viewModel.setRating(4)
            viewModel.dismissEndPanel()
            waitForTheRating()
            assertThat(viewModel.panel).isNull()

            harness.engine.emit(PageMessage.PastEnd)
            runCurrent()

            assertThat(viewModel.panel).isEqualTo(EndPanel(rating = 4, nextVolume = next))
            assertThat(viewModel.state.value.controlsShown).isFalse()
            assertThat(harness.repository.calls.filter { it.startsWith("nextVolume") }).hasSize(1)
        }

    @Test
    fun `a panel closed by back comes back at the next turn past the end`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.atEnd()

        viewModel.onBack()
        runCurrent()
        assertThat(viewModel.panel).isNull()

        harness.engine.emit(relocated(cfi = SecondCfi, fraction = 0.9))
        harness.engine.emit(relocated(cfi = ThirdCfi, fraction = 1.0, atEnd = true))
        harness.engine.emit(PageMessage.PastEnd)

        assertThat(viewModel.panel).isEqualTo(EndPanel(nextVolume = next))
    }

    @Test
    fun `a turn past the end while the panel is shown changes nothing`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.atEnd()
        viewModel.setRating(3)

        harness.engine.emit(PageMessage.PastEnd)

        assertThat(viewModel.panel).isEqualTo(EndPanel(rating = 3, nextVolume = next))
    }

    @Test
    fun `a rating already sent is not sent again, a changed one is, at once`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.atEnd()
        val events = eventsOf(viewModel)
        harness.repository.finishedReadingResult = ApiResult.Success(ReadId("77"))
        viewModel.setRating(4)
        viewModel.dismissEndPanel()
        waitForTheRating()

        harness.engine.emit(PageMessage.PastEnd)
        viewModel.dismissEndPanel()
        waitForTheRating()
        assertThat(harness.rated()).containsExactly("rate 77 4")
        assertThat(viewModel.panel).isNull()

        harness.engine.emit(PageMessage.PastEnd)
        viewModel.setRating(2)
        viewModel.dismissEndPanel()
        runCurrent()
        assertThat(harness.rated()).containsExactly("rate 77 4", "rate 77 2").inOrder()
        assertThat(viewModel.panel).isNull()

        harness.engine.emit(PageMessage.PastEnd)
        viewModel.closeEndPanel()
        runCurrent()
        assertThat(harness.rated()).containsExactly("rate 77 4", "rate 77 2").inOrder()
        assertThat(events).containsExactly(ReaderEvent.Leave)
    }

    @Test
    fun `a panel dismissed without stars comes back without stars`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.atEnd()
        viewModel.dismissEndPanel()
        runCurrent()

        harness.engine.emit(PageMessage.PastEnd)

        assertThat(viewModel.panel).isEqualTo(EndPanel(nextVolume = next))
        assertThat(harness.rated()).isEmpty()
    }

    @Test
    fun `dismissing a panel without stars sends nothing`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.atEnd()
        val events = eventsOf(viewModel)

        viewModel.dismissEndPanel()
        waitForTheRating()

        assertThat(viewModel.panel).isNull()
        assertThat(harness.rated()).isEmpty()
        assertThat(events).isEmpty()
    }
}
