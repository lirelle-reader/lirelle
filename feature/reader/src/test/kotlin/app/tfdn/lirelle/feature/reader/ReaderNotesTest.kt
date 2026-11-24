package app.tfdn.lirelle.feature.reader

import app.tfdn.lirelle.core.testing.MainDispatcherRule
import app.tfdn.lirelle.feature.reader.bridge.HostMessage
import app.tfdn.lirelle.feature.reader.bridge.PageBounds
import app.tfdn.lirelle.feature.reader.bridge.PageDirection
import app.tfdn.lirelle.feature.reader.bridge.PageMessage
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private val NoteOpened = PageMessage.NoteOpened(href = "notes.xhtml#n3", label = "3")

/** The panel of a footnote, which the page shows and the reader only knows about. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderNotesTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    @get:Rule
    val folder = TemporaryFolder()

    private fun TestScope.harness() = ReaderHarness(this, folder.root)

    @Test
    fun `a note shown by the page hides the controls`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()
        viewModel.toggleControls()

        harness.engine.emit(NoteOpened)

        assertThat(viewModel.state.value.noteOpen).isTrue()
        assertThat(viewModel.state.value.controlsShown).isFalse()
    }

    @Test
    fun `the page closing the note says so`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()
        harness.engine.emit(NoteOpened)

        harness.engine.emit(PageMessage.NoteClosed)

        assertThat(viewModel.state.value.noteOpen).isFalse()
    }

    @Test
    fun `back closes the note first`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()
        viewModel.seekTo(0.5)
        harness.engine.emit(relocated(cfi = ThirdCfi, fraction = 0.5))
        harness.engine.emit(NoteOpened)

        assertThat(viewModel.onBack()).isTrue()

        assertThat(harness.engine.sent.last()).isEqualTo(HostMessage.CloseNote)
        assertThat(viewModel.state.value.noteOpen).isFalse()
        assertThat(viewModel.state.value.goBackShown).isTrue()

        harness.engine.emit(PageMessage.NoteClosed)
        assertThat(viewModel.onBack()).isTrue()
        assertThat(harness.engine.sent.last()).isEqualTo(HostMessage.GoTo.Cfi(StartCfi))
    }

    @Test
    fun `back closes the note before a selection under it`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()
        harness.engine.emit(
            PageMessage.Selection(
                cfi = "epubcfi(/6/4!/4/2,/1:0,/1:15)",
                text = "A fine sentence",
                truncated = false,
                sectionIndex = 1,
                bounds = PageBounds(0.1, 0.2, 0.6, 0.3),
                highlightIds = emptyList(),
                exactId = null,
            ),
        )
        harness.engine.emit(NoteOpened)

        assertThat(viewModel.onBack()).isTrue()

        assertThat(harness.engine.sent.last()).isEqualTo(HostMessage.CloseNote)
        assertThat(viewModel.state.value.selection).isNotNull()
    }

    @Test
    fun `following the link of a note offers to go back, and closes the note`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()
        harness.engine.emit(NoteOpened)

        harness.engine.emit(PageMessage.Link(href = "notes.xhtml#n3", external = false))
        harness.engine.emit(relocated(cfi = ThirdCfi, fraction = 0.9))

        assertThat(viewModel.state.value.noteOpen).isFalse()
        assertThat(viewModel.state.value.goBackShown).isTrue()
        viewModel.goBack()
        assertThat(harness.engine.sent.last()).isEqualTo(HostMessage.GoTo.Cfi(StartCfi))
    }

    @Test
    fun `a page opened again shows no note`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()
        harness.engine.emit(NoteOpened)

        harness.engine.emit(PageMessage.Opened(SampleToc, PageDirection.Ltr, fixedLayout = false, title = null))

        assertThat(viewModel.state.value.noteOpen).isFalse()
        assertThat(viewModel.onBack()).isFalse()
    }

    @Test
    fun `back with a web link's question over a note closes the question only`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()
        harness.engine.emit(NoteOpened)
        harness.engine.emit(PageMessage.Link(href = "https://example.org/", external = true))

        assertThat(viewModel.onBack()).isTrue()

        assertThat(viewModel.state.value.externalLink).isNull()
        assertThat(viewModel.state.value.noteOpen).isTrue()
        assertThat(harness.engine.sent).doesNotContain(HostMessage.CloseNote)
    }

    @Test
    fun `volume keys are left alone under a note`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        harness.settings.update { it.copy(volumeKeys = true) }
        val viewModel = harness.reading()
        harness.engine.emit(NoteOpened)

        assertThat(viewModel.onVolumeKey(volumeDown = true)).isFalse()
        assertThat(harness.engine.sent).doesNotContain(HostMessage.Next)

        harness.engine.emit(PageMessage.NoteClosed)
        assertThat(viewModel.onVolumeKey(volumeDown = true)).isTrue()
    }
}
