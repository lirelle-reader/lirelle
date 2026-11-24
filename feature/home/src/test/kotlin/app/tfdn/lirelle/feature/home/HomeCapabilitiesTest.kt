package app.tfdn.lirelle.feature.home

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.designsystem.LocalWindowWidthClass
import app.tfdn.lirelle.core.designsystem.WindowWidthClass
import app.tfdn.lirelle.core.library.MonotonicClock
import app.tfdn.lirelle.core.library.ReadingChanges
import app.tfdn.lirelle.core.library.model.HomeRow
import app.tfdn.lirelle.core.library.model.HomeRowKind
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.core.session.profile.ProfileSignIn
import app.tfdn.lirelle.core.testing.FakeAudioRepository
import app.tfdn.lirelle.core.testing.FakeLibraryRepository
import app.tfdn.lirelle.core.testing.KomgaLikeCapabilities
import app.tfdn.lirelle.core.testing.MainDispatcherRule
import app.tfdn.lirelle.core.testing.sampleBook
import java.time.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Home shows only the rows its source offers, on a phone as on a tablet (spec 2c). */
@RunWith(RobolectricTestRunner::class)
class HomeCapabilitiesTest {

    @get:Rule(order = 0)
    val mainRule = MainDispatcherRule()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val repository = FakeLibraryRepository().apply {
        homeRows = HomeRowKind.entries.associateWith {
            ApiResult.Success(HomeRow(it, listOf(sampleBook("${it.ordinal}", "Book of ${it.name}")), hasMore = false))
        }
    }

    private fun showKomgaLike(width: WindowWidthClass) {
        val viewModel = HomeViewModel(
            repository,
            FakeAudioRepository(),
            MonotonicClock { 0 },
            Clock.systemUTC(),
            ReadingChanges(),
            ProfileSignIn("p1", ServerKind.BookOrbit, MutableStateFlow(false)) { "camille" },
            KomgaLikeCapabilities,
        )
        compose.setContent {
            LirelleTheme {
                CompositionLocalProvider(LocalWindowWidthClass provides width) {
                    val state by viewModel.state.collectAsStateWithLifecycle()
                    HomeContent(
                        state = state,
                        onRetry = {},
                        onRefresh = {},
                        onOpenBook = {},
                        onRead = {},
                        onListen = {},
                        onNarrate = {},
                        onSeeAll = {},
                        onSignInAgain = {},
                        onOpenDownloads = {},
                        onReadComic = {},
                    )
                }
            }
        }
    }

    private fun assertOnlyTheOfferedRows() {
        listOf(R.string.home_row_continue, R.string.home_row_up_next, R.string.home_row_recently_added).forEach {
            compose.onNodeWithText(context.getString(it)).performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithText(context.getString(R.string.home_row_want_to_read)).assertDoesNotExist()
        compose.onNodeWithText("Book of WantToRead").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `on a phone, a Komga-like source shows only its three rows`() {
        showKomgaLike(WindowWidthClass.Compact)

        assertOnlyTheOfferedRows()
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun `on a tablet in landscape, a Komga-like source shows only its three rows`() {
        showKomgaLike(WindowWidthClass.Expanded)

        assertOnlyTheOfferedRows()
    }
}
