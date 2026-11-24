package app.tfdn.lirelle.feature.reader.settings

import app.cash.turbine.test
import app.tfdn.lirelle.core.library.SourceCapabilities
import app.tfdn.lirelle.core.reader.settings.FontSizesPx
import app.tfdn.lirelle.core.reader.settings.ReaderSettings
import app.tfdn.lirelle.core.reader.settings.ReaderTheme
import app.tfdn.lirelle.core.testing.InMemoryReaderSettingsStore
import app.tfdn.lirelle.core.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class ReadingSettingsViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val store = InMemoryReaderSettingsStore(ReaderSettings(theme = ReaderTheme.Sepia))

    @Test
    fun `nothing is shown before the store is read`() {
        assertThat(ReadingSettingsViewModel(store, SourceCapabilities.Everything).settings.value).isNull()
    }

    @Test
    fun `it shows what is stored and follows a change made elsewhere`() = runTest {
        val viewModel = ReadingSettingsViewModel(store, SourceCapabilities.Everything)

        viewModel.settings.test {
            assertThat(expectMostRecentItem()).isEqualTo(ReaderSettings(theme = ReaderTheme.Sepia))

            // As the reader's own sheet does while a book is open.
            store.update { it.copy(theme = ReaderTheme.Black) }
            assertThat(awaitItem()?.theme).isEqualTo(ReaderTheme.Black)
        }
    }

    @Test
    fun `a change is written to the store and shown`() = runTest {
        val viewModel = ReadingSettingsViewModel(store, SourceCapabilities.Everything)

        viewModel.settings.test {
            viewModel.change { it.copy(hyphenate = false) }

            assertThat(expectMostRecentItem()?.hyphenate).isFalse()
        }
        assertThat(store.current).isEqualTo(ReaderSettings(theme = ReaderTheme.Sepia, hyphenate = false))
        assertThat(store.writes).isEqualTo(1)
    }

    @Test
    fun `each change is applied to what is stored when it is handled`() = runTest {
        val viewModel = ReadingSettingsViewModel(store, SourceCapabilities.Everything)
        val start = store.current.fontSizeStep

        // Three fast presses on plus, the page not yet drawn anew between them.
        repeat(3) { viewModel.change { it.copy(fontSizeStep = it.fontSizeStep + 1) } }

        assertThat(store.current.fontSizeStep).isEqualTo(start + 3)
    }

    @Test
    fun `a change is kept within the bounds, as the store keeps them`() = runTest {
        val viewModel = ReadingSettingsViewModel(store, SourceCapabilities.Everything)

        viewModel.change { it.copy(fontSizeStep = FontSizesPx.size + 4) }

        assertThat(store.current.fontSizeStep).isEqualTo(FontSizesPx.lastIndex)
    }
}
