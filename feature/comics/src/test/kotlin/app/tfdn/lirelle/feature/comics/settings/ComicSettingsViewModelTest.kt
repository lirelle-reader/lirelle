package app.tfdn.lirelle.feature.comics.settings

import app.cash.turbine.test
import app.tfdn.lirelle.core.comics.settings.Background
import app.tfdn.lirelle.core.comics.settings.ComicSettings
import app.tfdn.lirelle.core.comics.settings.Direction
import app.tfdn.lirelle.core.comics.settings.DirectionScope
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.SeriesId
import app.tfdn.lirelle.core.comics.settings.Fit
import app.tfdn.lirelle.core.comics.settings.TwoPages
import app.tfdn.lirelle.core.testing.InMemoryComicSettingsStore
import app.tfdn.lirelle.core.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class ComicSettingsViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    private val series = DirectionScope.Series(SeriesId("7"))

    @Test
    fun `it shows what the store holds and follows its changes`() = runTest {
        val store = InMemoryComicSettingsStore(ComicSettings(fit = Fit.Width, background = Background.Grey))
        val viewModel = ComicSettingsViewModel(store)

        viewModel.settings.test {
            assertThat(expectMostRecentItem()).isEqualTo(ComicSettings(fit = Fit.Width, background = Background.Grey))

            store.setTwoPages(TwoPages.Always)
            assertThat(awaitItem())
                .isEqualTo(ComicSettings(Fit.Width, TwoPages.Always, Direction.LeftToRight, Background.Grey))
        }
    }

    @Test
    fun `each choice is written to the store, where a reader finds it`() = runTest {
        val store = InMemoryComicSettingsStore()
        val viewModel = ComicSettingsViewModel(store)

        viewModel.setFit(Fit.Width)
        viewModel.setTwoPages(TwoPages.Never)
        viewModel.setBackground(Background.White)

        assertThat(store.settings(series).first())
            .isEqualTo(ComicSettings(Fit.Width, TwoPages.Never, Direction.LeftToRight, Background.White))
    }

    @Test
    fun `whether the whole page comes first is shown and written`() = runTest {
        val store = InMemoryComicSettingsStore()
        val viewModel = ComicSettingsViewModel(store)

        viewModel.settings.test {
            assertThat(expectMostRecentItem()?.wholePageFirst).isTrue()

            viewModel.setWholePageFirst(false)
            assertThat(awaitItem()).isEqualTo(ComicSettings(wholePageFirst = false))
        }
        assertThat(store.settings(series).first().wholePageFirst).isFalse()
    }

    @Test
    fun `the direction of a series is neither shown nor touched`() = runTest {
        val store = InMemoryComicSettingsStore(directions = mapOf(series to Direction.RightToLeft))
        val viewModel = ComicSettingsViewModel(store)

        viewModel.settings.test {
            assertThat(expectMostRecentItem()).isEqualTo(ComicSettings())

            store.setDirection(DirectionScope.Book(BookId("3")), Direction.RightToLeft)
            viewModel.setFit(Fit.Width)

            // The next thing shown is the fit: the direction showed nothing.
            assertThat(awaitItem()).isEqualTo(ComicSettings(fit = Fit.Width))
        }
        assertThat(store.directions)
            .containsExactly(series, Direction.RightToLeft, DirectionScope.Book(BookId("3")), Direction.RightToLeft)
    }
}
