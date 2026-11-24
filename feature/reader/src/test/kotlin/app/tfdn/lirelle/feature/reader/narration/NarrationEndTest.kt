package app.tfdn.lirelle.feature.reader.narration

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.testing.MainDispatcherRule
import app.tfdn.lirelle.feature.reader.ReaderHarness
import app.tfdn.lirelle.feature.reader.ReaderViewModel
import app.tfdn.lirelle.feature.reader.bridge.PageMessage
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The end of the narration: its bar is one of the reader's controls, which it brings so that it is seen. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class NarrationEndTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    @get:Rule
    val folder = TemporaryFolder()

    private fun TestScope.harness() = ReaderHarness(this, folder.root)

    private val ReaderViewModel.narration get() = state.value.narration

    @Test
    fun `a narration heard to its end brings the controls, where its bar is`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()
        harness.player.narration.value = narrationUi()
        assertThat(viewModel.state.value.controlsShown).isFalse()

        harness.player.narration.value = narrationUi(playing = false, finished = true)

        assertThat(viewModel.state.value.controlsShown).isTrue()

        // Hidden afterwards, they stay hidden: the end is told once, whatever the ended narration says next.
        viewModel.toggleControls()
        harness.player.narration.value = narrationUi(playing = false, finished = true).copy(elapsedMs = 1)

        assertThat(viewModel.state.value.controlsShown).isFalse()
    }

    @Test
    fun `another book's narration heard to its end brings nothing here`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()
        harness.player.narration.value = narrationUi()

        harness.player.narration.value = narrationUi(playing = false, finished = true, bookId = BookId("99"))

        assertThat(viewModel.narration.ended).isFalse()
        assertThat(viewModel.state.value.controlsShown).isFalse()
    }

    @Test
    fun `a narration already ended when the book opens brings nothing`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        harness.player.narration.value = narrationUi(playing = false, finished = true)

        val viewModel = harness.reading()

        assertThat(viewModel.narration.ended).isTrue()
        assertThat(viewModel.state.value.controlsShown).isFalse()
    }

    @Test
    fun `the end of the narration brings no controls over a footnote`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()
        harness.player.narration.value = narrationUi()
        harness.engine.emit(PageMessage.NoteOpened(href = "notes.xhtml#n1", label = "1"))

        harness.player.narration.value = narrationUi(playing = false, finished = true)

        assertThat(viewModel.narration.ended).isTrue()
        assertThat(viewModel.state.value.controlsShown).isFalse()
    }
}
