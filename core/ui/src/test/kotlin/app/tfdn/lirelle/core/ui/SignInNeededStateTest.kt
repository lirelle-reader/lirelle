package app.tfdn.lirelle.core.ui

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.session.ServerKind
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A screen without its server while the current profile waits for a sign-in: the server is not asked, so
 * there is nothing to try again and no network to wait for. It says so, names the profile, and offers to sign
 * in again; the way to the downloaded books stays. Without a network on the device, nothing of that is said.
 */
@RunWith(RobolectricTestRunner::class)
class SignInNeededStateTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val camille = SignInNeeded(profileId = "p0", username = "camille", kind = ServerKind.BookOrbit)
    private val signedInAgain = mutableListOf<String>()
    private var retries = 0
    private var opened = 0

    private fun text(id: Int, vararg args: Any) = context.getString(id, *args)

    private fun show(
        needed: SignInNeeded? = camille,
        inShell: Boolean = true,
        online: MutableStateFlow<Boolean>? = null,
        content: @Composable () -> Unit = {
            FailedState(ApiError.ServerUnreachable, onRetry = { retries++ })
        },
    ) {
        compose.setContent {
            LirelleTheme {
                CompositionLocalProvider(
                    LocalOpenDownloads provides if (inShell) ({ opened++ }) else null,
                    LocalDeviceOnline provides online,
                    LocalSignInNeeded provides needed,
                    LocalSignInAgain provides { signedInAgain += it },
                    content = content,
                )
            }
        }
    }

    @Test
    fun `it says that the profile needs to sign in, and signs it in again`() {
        show()

        compose.onNodeWithTag(SignInNeededStateTag).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.sign_in_needed)).assertIsDisplayed()
        compose.onNodeWithText("The session of camille · BookOrbit has expired.").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.no_network_title)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.no_network_message)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.state_retry)).assertDoesNotExist()
        compose.onNodeWithTag(SignInNeededAgainTag).assertIsDisplayed()
            .assertTextIs(text(R.string.sign_in_again)).performClick()

        assertThat(signedInAgain).containsExactly("p0")
        assertThat(retries).isEqualTo(0)
    }

    @Test
    fun `the way to the downloaded books stays`() {
        show()

        compose.onNodeWithTag(SignInNeededDownloadsTag)
            .assertTextIs(text(R.string.no_network_open_downloads)).performClick()

        assertThat(opened).isEqualTo(1)
        assertThat(signedInAgain).isEmpty()
    }

    @Test
    fun `where the downloaded books cannot be reached it only offers to sign in again`() {
        show(inShell = false)

        compose.onNodeWithTag(SignInNeededStateTag).assertIsDisplayed()
        compose.onNodeWithTag(SignInNeededDownloadsTag).assertDoesNotExist()
        compose.onNodeWithTag(SignInNeededAgainTag).assertExists()
    }

    @Test
    fun `without a network on the device it is the screen without network, as before`() {
        show(needed = null)

        compose.onNodeWithTag(NoNetworkStateTag).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.no_network_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.no_network_message)).assertIsDisplayed()
        compose.onNodeWithTag(NoNetworkRetryTag).performClick()
        compose.onNodeWithTag(SignInNeededStateTag).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.sign_in_again)).assertDoesNotExist()
        assertThat(retries).isEqualTo(1)
    }

    @Test
    fun `another failure is the error, whatever the profile waits for`() {
        show { FailedState(ApiError.ServerFailed, onRetry = { retries++ }) }

        compose.onNodeWithText(text(R.string.library_error_server_failed)).assertIsDisplayed()
        compose.onNodeWithTag(SignInNeededStateTag).assertDoesNotExist()
    }

    @Test
    fun `the screen without network shown by itself says it too`() {
        // As the book page does, which shows that screen without going through the failure of a load.
        show { NoNetworkState(onRetry = { retries++ }, onOpenDownloads = { opened++ }) }

        compose.onNodeWithTag(SignInNeededStateTag).assertIsDisplayed()
        compose.onNodeWithTag(NoNetworkRetryTag).assertDoesNotExist()
        compose.onNodeWithTag(SignInNeededAgainTag).performClick()
        assertThat(signedInAgain).containsExactly("p0")
    }

    @Test
    fun `once the account has signed in again the page loads by itself, once`() {
        var needed by mutableStateOf<SignInNeeded?>(camille)
        val online = MutableStateFlow(false)
        compose.setContent {
            LirelleTheme {
                CompositionLocalProvider(
                    LocalOpenDownloads provides { opened++ },
                    LocalDeviceOnline provides online,
                    LocalSignInNeeded provides needed,
                    LocalSignInAgain provides { signedInAgain += it },
                ) { FailedState(ApiError.ServerUnreachable, onRetry = { retries++ }) }
            }
        }
        compose.waitForIdle()

        // The mark falls and the profile's network follows the device's again, in one go.
        needed = null
        online.value = true
        compose.waitForIdle()

        assertThat(retries).isEqualTo(1)
    }

    @Test
    fun `the bar of a failed reload says it and signs in again, without trying again`() {
        show { ServerFailureBar(ApiError.ServerUnreachable, onRetry = { retries++ }) }

        compose.onNodeWithTag(SignInNeededBarTag).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.sign_in_needed)).assertIsDisplayed()
        compose.onNodeWithText("The session of camille · BookOrbit has expired.").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.library_error_unreachable)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.state_retry)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.sign_in_again)).performClick()

        assertThat(signedInAgain).containsExactly("p0")
        assertThat(retries).isEqualTo(0)
    }

    /** A key never expires: the server refused it, and the screen says so rather than a session that ended. */
    @Test
    fun `a Komga profile's refused key is no longer valid, on the screen as on the bar`() {
        val komga = SignInNeeded(profileId = "p3", username = "camille@example.org", kind = ServerKind.Komga)
        show(needed = komga) {
            Column {
                FailedState(ApiError.ServerUnreachable, onRetry = { retries++ })
                ServerFailureBar(ApiError.ServerUnreachable, onRetry = { retries++ })
            }
        }

        val message = "The sign-in of camille@example.org · Komga is no longer valid."
        compose.onAllNodesWithText(message).assertCountEquals(2)
        compose.onNodeWithText("has expired", substring = true).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "fr")
    fun `in French, a Komga profile's refused key is no longer valid`() {
        show(needed = SignInNeeded(profileId = "p3", username = "camille@example.org", kind = ServerKind.Komga))

        compose.onNodeWithText("La connexion de camille@example.org · Komga n'est plus valable.").assertIsDisplayed()
    }

    @Test
    fun `a Kavita profile is named Kavita, and its refused key is no longer valid, as Komga's`() {
        show(needed = SignInNeeded(profileId = "p4", username = "camille", kind = ServerKind.Kavita))

        compose.onNodeWithText("The sign-in of camille · Kavita is no longer valid.").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "fr")
    fun `in French, a Kavita profile's refused key is no longer valid`() {
        show(needed = SignInNeeded(profileId = "p4", username = "camille", kind = ServerKind.Kavita))

        compose.onNodeWithText("La connexion de camille · Kavita n'est plus valable.").assertIsDisplayed()
    }

    @Test
    fun `without a network on the device the bar is as before`() {
        show(needed = null) { ServerFailureBar(ApiError.ServerUnreachable, onRetry = { retries++ }) }

        compose.onNodeWithTag(PagingErrorBarTag).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.library_error_unreachable)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.state_retry)).performClick()
        compose.onNodeWithTag(SignInNeededBarTag).assertDoesNotExist()
        assertThat(retries).isEqualTo(1)
    }

    @Test
    fun `the bar of another failure is as before, whatever the profile waits for`() {
        show { ServerFailureBar(ApiError.ServerFailed, onRetry = { retries++ }) }

        compose.onNodeWithText(text(R.string.library_error_server_failed)).assertIsDisplayed()
        compose.onNodeWithTag(SignInNeededBarTag).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "fr")
    fun `in French`() {
        show()

        compose.onNodeWithText("Reconnexion nécessaire").assertIsDisplayed()
        compose.onNodeWithText("La session de camille · BookOrbit a expiré.").assertIsDisplayed()
        compose.onNodeWithText("Se reconnecter").assertExists()
        compose.onNodeWithText("Voir mes livres hors ligne").assertExists()
    }
}

private fun SemanticsNodeInteraction.assertTextIs(expected: String) = assert(hasText(expected))
