package app.tfdn.lirelle.feature.downloads

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.plus
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class DownloadsUpdatingTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val downloads = FakeDownloads()
    private val updates = FakeDownloadUpdates()
    private val sources = DownloadsSources(FakeKeptBookData(), { null }, { Storage })

    private fun TestScope.viewModel(): DownloadsViewModel =
        DownloadsViewModel(downloads, FakeRemovals(), FakeDownloadSettings(), sources, updates)
            .also { it.state.launchIn(backgroundScope + mainRule.dispatcher) }

    @Test
    fun `the update of a book is asked for that book`() = runTest {
        viewModel().updating.update(BookId("7"))

        assertThat(updates.calls).containsExactly("update 7")
    }

    @Test
    fun `update all asks for every pending update`() = runTest {
        viewModel().updating.updateAll()

        assertThat(updates.calls).containsExactly("updateAll")
    }

    @Test
    fun `a pull runs the check whatever its age, and its indicator ends when the check returns`() = runTest {
        updates.checked = CompletableDeferred()
        val viewModel = viewModel()
        assertThat(viewModel.state.value.refreshing).isFalse()

        viewModel.updating.refresh()

        assertThat(updates.calls).containsExactly("check true")
        assertThat(viewModel.state.value.refreshing).isTrue()

        updates.checked.complete(Unit)

        assertThat(viewModel.state.value.refreshing).isFalse()
    }

    @Test
    fun `a check that returns at once, as it does offline, ends the indicator at once`() = runTest {
        val viewModel = viewModel()

        viewModel.updating.refresh()

        assertThat(updates.calls).containsExactly("check true")
        assertThat(viewModel.state.value.refreshing).isFalse()
    }

    @Test
    fun `a pull during a check does not start another`() = runTest {
        updates.checked = CompletableDeferred()
        val viewModel = viewModel()

        viewModel.updating.refresh()
        viewModel.updating.refresh()

        assertThat(updates.calls).containsExactly("check true")
    }
}
