package app.tfdn.lirelle.feature.reader

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
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

/** The whole reader over a server that cannot be reached: the book's page, its position, its marks, its narration. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ReaderOfflineTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    @get:Rule
    val folder = TemporaryFolder()

    /** Nothing answers: the page of the book, the position, the marks, the map. */
    private fun TestScope.harness() = ReaderHarness(this, folder.root).apply {
        bookAnswer = ApiResult.Failure(ApiError.ServerUnreachable)
        repository.positionResult = ApiResult.Failure(ApiError.ServerUnreachable)
        kept.details[BookId("12")] = detail
        maps.map = null
    }

    @Test
    fun `a downloaded book is shown with the server unreachable`() = runTest(mainRule.dispatcher) {
        val harness = harness()

        val viewModel = harness.reading()

        assertThat(viewModel.state.value.phase).isEqualTo(ReaderPhase.Reading(harness.content))
        assertThat(viewModel.state.value.title).isEqualTo(harness.detail.title)
        assertThat(harness.downloads.opened).containsExactly(BookId("12"))
    }

    @Test
    fun `the narration starts from the map the download kept when the device's maps have none`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            harness.kept.maps[BookId("12")] = sampleMap()
            val viewModel = harness.reading()

            viewModel.startHere()

            assertThat(viewModel.state.value.narration.unavailable).isFalse()
            assertThat(harness.engine.sent.filterIsInstance<HostMessage.NarrationFind>()).hasSize(1)
        }

    @Test
    fun `without a map kept either, the narration is unavailable`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()

        viewModel.startHere()

        assertThat(viewModel.state.value.narration.unavailable).isTrue()
    }
}
