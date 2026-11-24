package app.tfdn.lirelle.feature.library.series

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.core.testing.sampleSeries
import app.tfdn.lirelle.core.ui.Loadable
import app.tfdn.lirelle.core.ui.LocalDeviceOnline
import app.tfdn.lirelle.core.ui.LocalOpenDownloads
import app.tfdn.lirelle.core.ui.LocalSignInAgain
import app.tfdn.lirelle.core.ui.LocalSignInNeeded
import app.tfdn.lirelle.core.ui.NoNetworkStateTag
import app.tfdn.lirelle.core.ui.R as UiR
import app.tfdn.lirelle.core.ui.SignInNeeded
import app.tfdn.lirelle.core.ui.SignInNeededAgainTag
import app.tfdn.lirelle.core.ui.SignInNeededDownloadsTag
import app.tfdn.lirelle.core.ui.SignInNeededStateTag
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Library's series while the current profile waits for a sign-in: what failed to load says so, offers to
 * sign in again and keeps the way to the downloaded books; once the account has signed in again, the series
 * load without a tap.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class SeriesTabSignInTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val camille = SignInNeeded("p0", "camille", ServerKind.BookOrbit)
    private val signedInAgain = mutableListOf<String>()
    private val events = mutableListOf<String>()

    private val failed = SeriesListUiState(series = Loadable.Failed(ApiError.ServerUnreachable))
    private val ready = SeriesListUiState(series = Loadable.Ready(listOf(sampleSeries("1", name = "Dawn"))))

    private var state by mutableStateOf(failed)
    private var needed by mutableStateOf<SignInNeeded?>(camille)

    /** The profile's network, as the shell gives it: false while the profile waits for a sign-in. */
    private val online = MutableStateFlow(false)

    private fun text(id: Int, vararg args: Any) = context.getString(id, *args)

    /** As in the shell; a retry loads the series, as the view model does once the server answers again. */
    private fun show() {
        compose.setContent {
            LirelleTheme {
                CompositionLocalProvider(
                    LocalOpenDownloads provides { events += "downloads" },
                    LocalDeviceOnline provides online,
                    LocalSignInNeeded provides needed,
                    LocalSignInAgain provides { signedInAgain += it },
                ) {
                    SeriesTabContent(
                        state = state,
                        onOpenSeries = {},
                        onSort = {},
                        onFilter = {},
                        onRetry = {
                            events += "retry"
                            state = ready
                        },
                        onRefresh = {},
                    )
                }
            }
        }
    }

    @Test
    fun `series that could not be loaded say that the profile needs to sign in, and sign it in again`() {
        show()

        compose.onNodeWithTag(SignInNeededStateTag).assertIsDisplayed()
        compose.onNodeWithText(text(UiR.string.sign_in_needed)).assertIsDisplayed()
        compose.onNodeWithText(text(UiR.string.sign_in_needed_message, "camille · BookOrbit")).assertIsDisplayed()
        compose.onNodeWithTag(NoNetworkStateTag).assertDoesNotExist()
        compose.onNodeWithText(text(UiR.string.state_retry)).assertDoesNotExist()
        compose.onNodeWithTag(SignInNeededAgainTag).performScrollTo().performClick()
        compose.onNodeWithTag(SignInNeededDownloadsTag).performScrollTo().performClick()

        assertThat(signedInAgain).containsExactly("p0")
        assertThat(events).containsExactly("downloads")
    }

    @Test
    fun `once the account has signed in again the series load without a tap`() {
        show()
        compose.onNodeWithTag(SignInNeededStateTag).assertIsDisplayed()

        // The mark falls: the profile's network follows the device's again.
        needed = null
        online.value = true

        compose.waitUntil { compose.onAllNodesWithText("Dawn").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Dawn").assertIsDisplayed()
        compose.onNodeWithTag(SignInNeededStateTag).assertDoesNotExist()
        assertThat(events).containsExactly("retry")
    }

    @Test
    fun `without a network on the device the series say so, as before`() {
        needed = null
        show()

        compose.onNodeWithTag(NoNetworkStateTag).assertIsDisplayed()
        compose.onNodeWithText(text(UiR.string.no_network_title)).assertIsDisplayed()
        compose.onNodeWithTag(SignInNeededStateTag).assertDoesNotExist()
    }
}
