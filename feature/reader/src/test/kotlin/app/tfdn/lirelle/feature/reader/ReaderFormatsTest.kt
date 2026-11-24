package app.tfdn.lirelle.feature.reader

import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.BookFile
import app.tfdn.lirelle.core.library.model.BookFileKind
import app.tfdn.lirelle.core.testing.MainDispatcherRule
import app.tfdn.lirelle.feature.reader.bridge.BookFormat
import app.tfdn.lirelle.feature.reader.bridge.HostMessage
import app.tfdn.lirelle.feature.reader.bridge.PageErrorCode
import app.tfdn.lirelle.feature.reader.bridge.PageMessage
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The reader with a book that is not an EPUB: a Kindle book or a FictionBook, one file to the page. */
class ReaderFormatsTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    @get:Rule
    val folder = TemporaryFolder()

    private fun TestScope.harness(format: String) = ReaderHarness(this, folder.newFolder()).apply {
        val file = BookFile(
            id = FileId("1"),
            kind = BookFileKind.Text,
            format = format,
            sizeBytes = 900_000,
            isPrimary = true,
            narrationSeconds = null,
            chapterCount = null,
        )
        detail = detail.copy(files = listOf(file))
    }

    @Test
    fun `the page is given the whole file and told its format`() = runTest(mainRule.dispatcher) {
        mapOf("MOBI" to BookFormat.Mobi, "AZW3" to BookFormat.Mobi, "FB2" to BookFormat.Fb2).forEach { (format, told) ->
            val harness = harness(format)
            val viewModel = harness.viewModel()

            assertThat(viewModel.state.value.phase)
                .isEqualTo(ReaderPhase.Reading(BookContent.Whole(harness.bookFile, told)))

            viewModel.onEngine(harness.engine)
            harness.engine.emit(PageMessage.Ready)

            assertThat(harness.engine.sent.filterIsInstance<HostMessage.Open>()).containsExactly(
                HostMessage.Open("/book/first/", cfi = null, fraction = 0.0, harness.pageSettings, format = told),
            )
        }
    }

    @Test
    fun `an EPUB is told as one`() = runTest(mainRule.dispatcher) {
        val harness = ReaderHarness(this, folder.newFolder())
        val viewModel = harness.viewModel()
        viewModel.onEngine(harness.engine)
        harness.engine.emit(PageMessage.Ready)

        assertThat(harness.engine.sent.filterIsInstance<HostMessage.Open>().single().format)
            .isEqualTo(BookFormat.Epub)
    }

    @Test
    fun `a file the page cannot read says the book cannot be opened in this format`() =
        runTest(mainRule.dispatcher) {
            val harness = harness("AZW3")
            val viewModel = harness.viewModel()
            viewModel.onEngine(harness.engine)
            harness.engine.emit(PageMessage.Ready)

            harness.engine.emit(PageMessage.Error(PageErrorCode.CannotOpen, "The book is protected"))

            assertThat(viewModel.state.value.phase).isEqualTo(ReaderPhase.Failed(ReaderFailure.CannotOpen))
            assertThat(viewModel.state.value.opened).isFalse()
        }

    @Test
    fun `a page that fails for another reason is still an engine failure`() = runTest(mainRule.dispatcher) {
        val harness = harness("MOBI")
        val viewModel = harness.viewModel()
        viewModel.onEngine(harness.engine)
        harness.engine.emit(PageMessage.Ready)

        harness.engine.emit(PageMessage.Error(PageErrorCode.OpenFailed, "Book file not available (404)"))

        assertThat(viewModel.state.value.phase).isEqualTo(ReaderPhase.Failed(ReaderFailure.Engine))
    }

    @Test
    fun `the narration is not offered`() = runTest(mainRule.dispatcher) {
        listOf("MOBI", "FB2").forEach { format ->
            val harness = harness(format)

            val viewModel = harness.reading()

            assertThat(viewModel.state.value.opened).isTrue()
            assertThat(viewModel.state.value.narration.available).isFalse()
        }
    }

    @Test
    fun `the place the page gives is where the reader is`() = runTest(mainRule.dispatcher) {
        val harness = harness("FB2")
        val viewModel = harness.reading()

        harness.engine.emit(relocated(cfi = SecondCfi, fraction = 0.4))

        assertThat(viewModel.state.value.location?.fraction).isEqualTo(0.4)
    }
}
