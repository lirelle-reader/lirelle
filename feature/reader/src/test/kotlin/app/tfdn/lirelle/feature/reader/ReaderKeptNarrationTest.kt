package app.tfdn.lirelle.feature.reader

import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.FormatState
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.testing.MainDispatcherRule
import app.tfdn.lirelle.feature.reader.bridge.HostMessage
import app.tfdn.lirelle.feature.reader.narration.sampleMap
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The narration of a book whose narration is downloaded starts from the map the download kept, as the player
 * plays it, with the server there too.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ReaderKeptNarrationTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    @get:Rule
    val folder = TemporaryFolder()

    /** The download kept a map; the device's own maps have another one to give. */
    private fun TestScope.harness() =
        ReaderHarness(this, folder.root).apply { kept.maps[BookId("12")] = sampleMap() }

    private fun ReaderHarness.finds() = engine.sent.filterIsInstance<HostMessage.NarrationFind>()

    @Test
    fun `a downloaded narration starts from the map kept, not the device's`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        harness.downloads.has(BookId("12"), DownloadFormat.Narration)
        val viewModel = harness.reading()

        viewModel.startHere()
        viewModel.startHere()

        assertThat(harness.maps.asked).isEmpty()
        assertThat(harness.kept.mapsAsked).isEqualTo(1)
        assertThat(viewModel.state.value.narration.unavailable).isFalse()
        assertThat(harness.finds()).hasSize(2)
    }

    @Test
    fun `a download without its map starts from the device's`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        harness.kept.maps.clear()
        harness.downloads.has(BookId("12"), DownloadFormat.Narration)
        val viewModel = harness.reading()

        viewModel.startHere()

        assertThat(harness.maps.asked).hasSize(1)
        assertThat(harness.finds()).hasSize(1)
    }

    @Test
    fun `a narration that is not downloaded whole, or is being deleted, starts from the device's map`() =
        runTest(mainRule.dispatcher) {
            val partial = harness()
            partial.downloads.has(BookId("12"), DownloadFormat.Text)
            partial.downloads.has(BookId("12"), DownloadFormat.Narration, FormatState.Downloading(10, 100))
            partial.reading().startHere()
            val removing = harness()
            removing.downloads.has(BookId("12"), DownloadFormat.Narration, removing = true)
            removing.reading().startHere()

            assertThat(partial.maps.asked).hasSize(1)
            assertThat(partial.kept.mapsAsked).isEqualTo(0)
            assertThat(removing.maps.asked).hasSize(1)
            assertThat(removing.kept.mapsAsked).isEqualTo(0)
        }
}
