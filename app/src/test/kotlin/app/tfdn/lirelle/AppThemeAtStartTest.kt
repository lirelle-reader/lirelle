package app.tfdn.lirelle

import app.tfdn.lirelle.feature.settings.appearance.AppTheme
import app.tfdn.lirelle.feature.settings.appearance.AppearanceStore
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppThemeAtStartTest {

    private val store = FixedAppearanceStore(AppTheme.System)
    private val applied = mutableListOf<AppTheme>()
    private val atStart = AppThemeAtStart(store, applied::add)

    @Test
    fun `the theme stored is given to the system as the application starts`() = runTest(UnconfinedTestDispatcher()) {
        store.theme.value = AppTheme.Dark

        atStart.start(backgroundScope)

        assertThat(applied).containsExactly(AppTheme.Dark)
    }

    @Test
    fun `an app that never stored a theme tells the system to choose`() = runTest(UnconfinedTestDispatcher()) {
        atStart.start(backgroundScope)

        assertThat(applied).containsExactly(AppTheme.System)
    }

    @Test
    fun `a theme chosen later is not applied from here`() = runTest(UnconfinedTestDispatcher()) {
        atStart.start(backgroundScope)

        store.theme.value = AppTheme.Light

        assertThat(applied).containsExactly(AppTheme.System)
    }
}

/** An appearance store whose theme the test sets directly. */
private class FixedAppearanceStore(initial: AppTheme) : AppearanceStore {
    override val theme = MutableStateFlow(initial)

    override suspend fun setTheme(theme: AppTheme): Boolean {
        this.theme.value = theme
        return true
    }
}
