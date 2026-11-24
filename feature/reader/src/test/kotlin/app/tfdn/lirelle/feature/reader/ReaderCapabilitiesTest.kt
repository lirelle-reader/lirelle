package app.tfdn.lirelle.feature.reader

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.SourceCapabilities
import app.tfdn.lirelle.core.library.model.NextVolume
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.testing.InMemoryReaderSettingsStore
import app.tfdn.lirelle.core.testing.KomgaLikeCapabilities
import app.tfdn.lirelle.core.testing.MainDispatcherRule
import app.tfdn.lirelle.feature.reader.bridge.HostMessage
import app.tfdn.lirelle.feature.reader.bridge.PageDirection
import app.tfdn.lirelle.feature.reader.bridge.PageMessage
import app.tfdn.lirelle.feature.reader.settings.ReadingSettingsViewModel
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The EPUB reader of a Komga-like source: no narration, no corner bookmark, no rating at the end (spec 2c). */
class ReaderCapabilitiesTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    @get:Rule
    val folder = TemporaryFolder()

    private val next = NextVolume(bookId = BookId("13"), title = "The Next One", seriesIndex = "3")

    private fun TestScope.harness() = ReaderHarness(this, folder.root).apply {
        capabilities = KomgaLikeCapabilities
        repository.nextVolumeResult = ApiResult.Success(next)
    }

    @Test
    fun `the state says what the source can do`() = runTest(mainRule.dispatcher) {
        val viewModel = harness().reading()

        assertThat(viewModel.state.value.capabilities).isEqualTo(KomgaLikeCapabilities)
    }

    @Test
    fun `a book with a narration has none to offer without read-along`() = runTest(mainRule.dispatcher) {
        val viewModel = harness().reading()

        assertThat(viewModel.state.value.narration.available).isFalse()
    }

    @Test
    fun `opened to narrate without read-along, the reader starts no narration`() = runTest(mainRule.dispatcher) {
        val harness = harness()

        harness.narrating()
        runCurrent()

        assertThat(harness.engine.sent.filterIsInstance<HostMessage.NarrationFind>()).isEmpty()
        assertThat(harness.player.narrated).isEmpty()
    }

    @Test
    fun `the top-right corner turns the page without annotations, adding no bookmark`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            harness.reading()

            harness.engine.emit(PageMessage.Tap(x = 0.9, y = 0.05))

            assertThat(harness.marks.calls.none { it.startsWith("addBookmark") }).isTrue()
            assertThat(harness.engine.sent.last()).isEqualTo(HostMessage.Next)
        }

    @Test
    fun `no marks are read from the server without annotations`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()

        harness.engine.emit(PageMessage.Opened(SampleToc, PageDirection.Ltr, fixedLayout = false, title = null))
        viewModel.onResumed()
        viewModel.showSheet(ReaderSheet.Contents)
        runCurrent()

        assertThat(harness.marks.calls.filter { it.startsWith("refresh") }).isEmpty()
    }

    @Test
    fun `the reading settings offer no corner bookmark without annotations`() {
        val store = InMemoryReaderSettingsStore()

        assertThat(ReadingSettingsViewModel(store, KomgaLikeCapabilities).cornerBookmark).isFalse()
        assertThat(ReadingSettingsViewModel(store, SourceCapabilities.Everything).cornerBookmark).isTrue()
    }

    @Test
    fun `the end of the book offers no rating without ratings, and keeps the next volume`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            val viewModel = harness.reading()
            harness.engine.emit(relocated(cfi = ThirdCfi, fraction = 1.0, atEnd = true))

            harness.engine.emit(PageMessage.PastEnd)

            assertThat(viewModel.state.value.endPanel).isEqualTo(EndPanel(nextVolume = next, ratings = false))
        }

    @Test
    fun `without ratings no reading is asked nor rated, and the book closes at once`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            val viewModel = harness.reading()
            harness.engine.emit(relocated(cfi = ThirdCfi, fraction = 1.0, atEnd = true))
            harness.engine.emit(PageMessage.PastEnd)

            viewModel.setRating(4)
            viewModel.closeEndPanel()
            runCurrent()

            assertThat(harness.repository.calls.filter { it.startsWith("finishedReading") }).isEmpty()
            assertThat(harness.repository.calls.filter { it.startsWith("rate") }).isEmpty()
            assertThat(viewModel.state.value.endPanel).isNull()
        }
}
