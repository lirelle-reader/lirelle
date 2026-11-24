package app.tfdn.lirelle.feature.reader

import app.tfdn.lirelle.core.testing.MainDispatcherRule
import app.tfdn.lirelle.feature.reader.bridge.HostMessage
import app.tfdn.lirelle.feature.reader.bridge.PageDirection
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Page turns asked by the screen itself, for a screen reader: no finger touches the page. */
class ReaderPageTurnTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `next page and previous page turn one page and hide the controls`() = runTest(mainRule.dispatcher) {
        val harness = ReaderHarness(this, folder.root)
        val viewModel = harness.reading()
        viewModel.toggleControls()

        viewModel.nextPage()

        assertThat(harness.engine.sent.last()).isEqualTo(HostMessage.Next)
        assertThat(viewModel.state.value.controlsShown).isFalse()

        viewModel.previousPage()

        assertThat(harness.engine.sent.last()).isEqualTo(HostMessage.Prev)
    }

    @Test
    fun `next page goes forward in a right-to-left book too`() = runTest(mainRule.dispatcher) {
        val harness = ReaderHarness(this, folder.root)
        val viewModel = harness.reading(PageDirection.Rtl)

        viewModel.nextPage()

        assertThat(harness.engine.sent.last()).isEqualTo(HostMessage.Next)
    }

    @Test
    fun `nothing is turned while the book is not on screen`() = runTest(mainRule.dispatcher) {
        val harness = ReaderHarness(this, folder.root)
        val viewModel = harness.viewModel()
        viewModel.onEngine(harness.engine)

        viewModel.nextPage()
        viewModel.previousPage()

        assertThat(harness.engine.sent).isEmpty()
    }
}
