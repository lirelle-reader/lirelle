package app.tfdn.lirelle.feature.reader

import app.tfdn.lirelle.core.downloads.model.DeviceCopy
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.testing.MainDispatcherRule
import app.tfdn.lirelle.feature.reader.bridge.PageMessage
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The row of the panel at the end of the book that says what the device keeps of it, and deletes it. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderEndDeviceTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    @get:Rule
    val folder = TemporaryFolder()

    private val whole = DeviceCopy(listOf(DownloadFormat.Text, DownloadFormat.Audiobook), 2_000, deleting = false)

    /** A reader of the book 12, which the device keeps and the reader holds. */
    private fun TestScope.harness() = ReaderHarness(this, folder.root).apply {
        downloads.has(BookId("12"), DownloadFormat.Audiobook)
        downloads.has(BookId("12"), DownloadFormat.Text)
        deletions.held += BookId("12")
    }

    private fun ReaderHarness.atEnd(): ReaderViewModel {
        val viewModel = reading()
        engine.emit(relocated(cfi = ThirdCfi, fraction = 1.0, atEnd = true))
        engine.emit(PageMessage.PastEnd)
        return viewModel
    }

    private val ReaderViewModel.device get() = state.value.endPanel?.device

    @Test
    fun `a book the device does not keep has no row`() = runTest(mainRule.dispatcher) {
        val viewModel = ReaderHarness(this, folder.root).atEnd()
        runCurrent()

        assertThat(viewModel.state.value.endPanel).isEqualTo(EndPanel())

        viewModel.deleteFromDevice()
        runCurrent()
        assertThat(viewModel.state.value.endPanel).isEqualTo(EndPanel())
    }

    @Test
    fun `the panel says what the device keeps of the book`() = runTest(mainRule.dispatcher) {
        val viewModel = harness().atEnd()
        runCurrent()

        assertThat(viewModel.device).isEqualTo(whole)
    }

    @Test
    fun `delete is asked once and the row says so, undo takes it back`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.atEnd()
        runCurrent()

        viewModel.deleteFromDevice()
        viewModel.deleteFromDevice()
        runCurrent()
        viewModel.deleteFromDevice()
        runCurrent()
        assertThat(viewModel.device).isEqualTo(whole.copy(deleting = true))
        assertThat(harness.deletions.asked).containsExactly("delete 12")

        viewModel.keepOnDevice()
        runCurrent()
        assertThat(viewModel.device).isEqualTo(whole)
        assertThat(harness.deletions.asked).containsExactly("delete 12", "keep 12").inOrder()
    }

    @Test
    fun `the panel that comes back still says the book goes`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.atEnd()
        runCurrent()
        viewModel.deleteFromDevice()
        runCurrent()

        viewModel.dismissEndPanel()
        runCurrent()
        assertThat(viewModel.state.value.endPanel).isNull()
        harness.engine.emit(PageMessage.PastEnd)
        runCurrent()

        assertThat(viewModel.device).isEqualTo(whole.copy(deleting = true))
    }

    @Test
    fun `a book read from elsewhere than its copy goes at once, and the row with it`() = runTest(mainRule.dispatcher) {
        val harness = harness().apply { deletions.held.clear() }
        val viewModel = harness.atEnd()
        runCurrent()

        viewModel.deleteFromDevice()
        runCurrent()

        assertThat(viewModel.state.value.endPanel).isEqualTo(EndPanel())
        assertThat(harness.deletions.asked).containsExactly("delete 12")
    }

    @Test
    fun `the panel a finished narration opens has the row too`() = runTest(mainRule.dispatcher) {
        val viewModel = harness().reading()

        viewModel.finishBook()
        runCurrent()

        assertThat(viewModel.device).isEqualTo(whole)
    }
}
