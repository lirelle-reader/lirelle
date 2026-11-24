package app.tfdn.lirelle.feature.settings.appearance

import app.cash.turbine.test
import app.tfdn.lirelle.core.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class AppearanceViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val store = InMemoryAppearanceStore()
    private val applied = mutableListOf<AppTheme>()
    private val nightMode = NightMode { theme ->
        // Stored first: the system may recreate the screen as soon as it is told.
        assertThat(store.stored).isEqualTo(theme)
        applied += theme
    }

    @Test
    fun `it shows the stored theme and follows its changes`() = runTest {
        store.setTheme(AppTheme.Light)
        val viewModel = AppearanceViewModel(store, nightMode)

        viewModel.theme.test {
            assertThat(expectMostRecentItem()).isEqualTo(AppTheme.Light)

            store.setTheme(AppTheme.Dark)
            assertThat(awaitItem()).isEqualTo(AppTheme.Dark)
        }
    }

    @Test
    fun `a choice is stored, then given to the system`() = runTest {
        val viewModel = AppearanceViewModel(store, nightMode)

        viewModel.choose(AppTheme.Dark)

        assertThat(store.stored).isEqualTo(AppTheme.Dark)
        assertThat(applied).containsExactly(AppTheme.Dark)
    }

    @Test
    fun `each choice replaces the one before`() = runTest {
        val viewModel = AppearanceViewModel(store, nightMode)

        viewModel.choose(AppTheme.Dark)
        viewModel.choose(AppTheme.System)

        assertThat(store.stored).isEqualTo(AppTheme.System)
        assertThat(applied).containsExactly(AppTheme.Dark, AppTheme.System).inOrder()
    }

    @Test
    fun `a choice that cannot be stored is not given to the system`() = runTest {
        val viewModel = AppearanceViewModel(store, nightMode)
        viewModel.choose(AppTheme.Light)
        store.writable = false

        viewModel.choose(AppTheme.Dark)

        // The store and the system agree: both keep the theme of before.
        assertThat(store.stored).isEqualTo(AppTheme.Light)
        assertThat(applied).containsExactly(AppTheme.Light)
    }

    @Test
    fun `nothing is given to the system before a choice`() = runTest {
        AppearanceViewModel(store, nightMode).theme.test {
            assertThat(expectMostRecentItem()).isEqualTo(AppTheme.System)
        }

        assertThat(applied).isEmpty()
    }
}
