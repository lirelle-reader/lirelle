package app.tfdn.lirelle.feature.login

import android.content.Context
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.LoginOptions
import app.tfdn.lirelle.core.network.OidcProvider
import app.tfdn.lirelle.core.network.SignInIdentifier
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class SignInScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val provider = OidcProvider("authentik", "Authentik", "client-id-1", "openid profile email")
    private val loaded = SignInUiState(
        serverUrl = "https://books.example.com",
        isLoadingOptions = false,
        options = LoginOptions(passwordEnabled = true, oidcProviders = listOf(provider)),
    )

    private fun show(
        state: SignInUiState,
        onOidc: (OidcProvider) -> Unit = {},
        onChangeServer: () -> Unit = {},
        onRetry: () -> Unit = {},
    ) {
        compose.setContent {
            LirelleTheme {
                SignInScreen(
                    state = state,
                    onUsernameChange = {},
                    onPasswordChange = {},
                    onSignIn = {},
                    onOidc = onOidc,
                    onChangeServer = onChangeServer,
                    onRetry = onRetry,
                )
            }
        }
    }

    private fun text(id: Int, vararg args: Any) = context.getString(id, *args)

    @Test
    fun `shows the server, the password form and the provider`() {
        show(loaded)

        compose.onNodeWithText("https://books.example.com").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.sign_in_username)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.sign_in_password)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.sign_in_with_provider, "Authentik")).assertIsDisplayed()
    }

    @Test
    fun `hides the password form when the server disables it`() {
        show(loaded.copy(options = LoginOptions(passwordEnabled = false, oidcProviders = listOf(provider))))

        compose.onNodeWithText(text(R.string.sign_in_username)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.sign_in_with_provider, "Authentik")).assertIsDisplayed()
    }

    @Test
    fun `choosing a provider reports it`() {
        val chosen = mutableListOf<OidcProvider>()
        show(loaded, onOidc = { chosen += it })

        compose.onNodeWithText(text(R.string.sign_in_with_provider, "Authentik")).performClick()

        assertThat(chosen).containsExactly(provider)
    }

    @Test
    fun `says so when no sign-in method is available`() {
        show(loaded.copy(options = LoginOptions(passwordEnabled = false, oidcProviders = emptyList())))

        compose.onNodeWithText(text(R.string.sign_in_no_method)).assertIsDisplayed()
    }

    @Test
    fun `warns on an unencrypted connection`() {
        show(loaded.copy(serverUrl = "http://homeserver:3000", isCleartext = true))

        compose.onNodeWithText(text(R.string.sign_in_cleartext_notice)).assertIsDisplayed()
    }

    @Test
    fun `does not warn on an encrypted connection`() {
        show(loaded)

        compose.onNodeWithText(text(R.string.sign_in_cleartext_notice)).assertDoesNotExist()
    }

    @Test
    fun `shows the error message`() {
        show(loaded.copy(error = ApiError.InvalidCredentials))

        compose.onNodeWithText(text(R.string.error_invalid_credentials)).assertIsDisplayed()
    }

    @Test
    fun `the error message is announced`() {
        show(loaded.copy(error = ApiError.InvalidCredentials))

        compose.onNodeWithText(text(R.string.error_invalid_credentials), useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
    }

    @Test
    fun `offers a retry when the options could not be loaded`() {
        var retries = 0
        show(loaded.copy(options = null, error = ApiError.ServerUnreachable), onRetry = { retries++ })

        compose.onNodeWithText(text(R.string.error_server_unreachable)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.sign_in_retry)).performClick()

        assertThat(retries).isEqualTo(1)
    }

    @Test
    fun `a failing server is told apart from an unexpected answer, and can be retried`() {
        var retries = 0
        show(loaded.copy(options = null, error = ApiError.ServerFailed), onRetry = { retries++ })

        compose.onNodeWithText(text(R.string.error_server_failed)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.sign_in_retry)).performClick()

        assertThat(retries).isEqualTo(1)
    }

    @Test
    fun `disables the actions while busy`() {
        show(loaded.copy(isBusy = true))

        // The title reads the same as the submit button in English: match the button.
        compose.onNode(hasText(text(R.string.sign_in_submit)) and hasClickAction()).assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.sign_in_with_provider, "Authentik")).assertIsNotEnabled()
    }

    @Test
    fun `the submit button is disabled until a username and a password are typed`() {
        show(loaded.copy(username = "reader"))

        compose.onNode(hasText(text(R.string.sign_in_submit)) and hasClickAction()).assertIsNotEnabled()
    }

    @Test
    fun `the submit button is enabled once a username and a password are typed`() {
        show(loaded.copy(username = "reader", password = "secret"))

        compose.onNode(hasText(text(R.string.sign_in_submit)) and hasClickAction()).assertIsEnabled()
    }

    @Test
    fun `the server cannot be changed while a sign-in is in flight`() {
        show(loaded.copy(isBusy = true))

        compose.onNodeWithText(text(R.string.sign_in_change_server)).assertIsNotEnabled()
    }

    @Test
    fun `the fields tell autofill what they hold`() {
        show(loaded)

        compose.onNodeWithText(text(R.string.sign_in_username))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentType, ContentType.Username))
        compose.onNodeWithText(text(R.string.sign_in_password))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentType, ContentType.Password))
    }

    private val byEmail = loaded.copy(options = loaded.options?.copy(identifier = SignInIdentifier.Email))

    @Test
    fun `a server that takes a username asks for one`() {
        show(loaded)

        compose.onNodeWithText("Username").assertIsDisplayed()
        compose.onNodeWithText("Email address").assertDoesNotExist()
    }

    @Test
    fun `a server that takes an email address asks for one, and tells autofill`() {
        show(byEmail)

        compose.onNodeWithText("Email address")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentType, ContentType.EmailAddress))
        compose.onNodeWithText("Username").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "fr")
    fun `in French, the field says what the server takes`() {
        show(byEmail)
        compose.onNodeWithText("Adresse e-mail").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "fr")
    fun `in French, a username is asked for as an identifier`() {
        show(loaded)
        compose.onNodeWithText("Identifiant").assertIsDisplayed()
    }

    @Test
    fun `wrong credentials name what the server takes`() {
        show(loaded.copy(error = ApiError.InvalidCredentials))
        compose.onNodeWithText("Wrong username or password.").assertIsDisplayed()
    }

    @Test
    fun `wrong credentials of a server that takes an email address name the email address`() {
        show(byEmail.copy(error = ApiError.InvalidCredentials))

        compose.onNodeWithText("Wrong email address or password.").assertIsDisplayed()
        compose.onNodeWithText("Wrong username or password.").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "fr")
    fun `in French, wrong credentials name what the server takes`() {
        show(byEmail.copy(error = ApiError.InvalidCredentials))
        compose.onNodeWithText("Adresse e-mail ou mot de passe incorrect.").assertIsDisplayed()
    }

    /** The account signed in, but the server would not finish: no key for the device, nothing saved. */
    @Test
    fun `a sign-in the server would not finish says it did not succeed`() {
        show(byEmail.copy(error = ApiError.SignInRefused))
        compose.onNodeWithText("The sign-in did not succeed.").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "fr")
    fun `in French, a sign-in the server would not finish says it did not succeed`() {
        show(byEmail.copy(error = ApiError.SignInRefused))
        compose.onNodeWithText("La connexion n'a pas abouti.").assertIsDisplayed()
    }

    /** The provider's flow led to a sign-in page in cleartext, which the app does not show. */
    @Test
    fun `a sign-in page in cleartext says why it was not shown`() {
        show(byEmail.copy(error = ApiError.UnencryptedSignInPage))
        compose.onNodeWithText(
            "This sign-in page is not encrypted (http). Lirelle only opens encrypted sign-in pages.",
        ).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "fr")
    fun `in French, a sign-in page in cleartext says why it was not shown`() {
        show(byEmail.copy(error = ApiError.UnencryptedSignInPage))
        compose.onNodeWithText(
            "Cette page de connexion n'est pas chiffrée (http). Lirelle n'ouvre que des pages de connexion chiffrées.",
        ).assertIsDisplayed()
    }

    @Test
    fun `offers nothing to retry while the server address is missing`() {
        show(SignInUiState(serverUrl = "", isLoadingOptions = false))

        compose.onNodeWithText(text(R.string.sign_in_retry)).assertDoesNotExist()
    }

    @Test
    fun `change server reports the click`() {
        var clicks = 0
        show(loaded, onChangeServer = { clicks++ })

        compose.onNodeWithText(text(R.string.sign_in_change_server)).performClick()

        assertThat(clicks).isEqualTo(1)
    }
}
