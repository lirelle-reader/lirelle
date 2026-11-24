package app.tfdn.lirelle.shell

import app.cash.turbine.test
import app.tfdn.lirelle.core.downloads.model.FormatState
import app.tfdn.lirelle.core.downloads.store.DownloadSettings
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class DownloadsSettingsViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val downloads = FakeDownloads()
    private val removals = FakeRemovals(downloads)
    private val settings = MemoryDownloadSettings()
    private var free = 41_200L

    private fun viewModel() = DownloadsSettingsViewModel(downloads, removals, settings, { free }, mainRule.dispatcher)

    @Test
    fun `nothing downloaded, mobile network off`() = runTest {
        viewModel().state.test {
            assertThat(awaitItem())
                .isEqualTo(DownloadsSettingsState(allowMetered = false, books = 0, usedBytes = 0, freeBytes = 41_200))
        }
    }

    @Test
    fun `the books kept or being fetched are counted, with what they hold on the device`() = runTest {
        downloads.books.value = listOf(
            downloaded(BookId("1"), FormatState.Complete(3_000)),
            downloaded(BookId("2"), FormatState.Downloading(400, 1_000)),
        )

        viewModel().state.test {
            assertThat(awaitItem()).isEqualTo(DownloadsSettingsState(false, books = 2, usedBytes = 3_400, 41_200))
        }
    }

    @Test
    fun `the switch writes the setting, and shows it`() = runTest {
        val viewModel = viewModel()
        viewModel.state.test {
            assertThat(awaitItem().allowMetered).isFalse()

            viewModel.onAllowMetered(true)
            assertThat(awaitItem().allowMetered).isTrue()
            assertThat(settings.settings.value).isEqualTo(DownloadSettings(allowMetered = true))

            viewModel.onAllowMetered(false)
            assertThat(awaitItem().allowMetered).isFalse()
        }
    }

    @Test
    fun `the switch leaves the other settings as they are`() = runTest {
        settings.settings.value = DownloadSettings(notificationsAsked = true)

        viewModel().onAllowMetered(true)

        assertThat(settings.settings.value).isEqualTo(DownloadSettings(allowMetered = true, notificationsAsked = true))
    }

    @Test
    fun `deleting everything goes through the removals, and the space is counted again`() = runTest {
        downloads.books.value = listOf(downloaded(BookId("1"), FormatState.Complete(3_000)))
        val viewModel = viewModel()
        viewModel.state.test {
            assertThat(awaitItem().books).isEqualTo(1)
            free = 44_200

            viewModel.onDeleteAll()

            assertThat(awaitItem()).isEqualTo(DownloadsSettingsState(false, books = 0, usedBytes = 0, 44_200))
            assertThat(removals.deletedAll).isEqualTo(1)
        }
    }
}
