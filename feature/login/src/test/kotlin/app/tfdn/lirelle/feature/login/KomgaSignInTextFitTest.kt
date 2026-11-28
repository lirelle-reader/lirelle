package app.tfdn.lirelle.feature.login

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.LoginOptions
import app.tfdn.lirelle.core.network.OidcProvider
import app.tfdn.lirelle.core.network.ProviderFlow
import app.tfdn.lirelle.core.network.SignInIdentifier
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.core.testing.DeviceRule
import app.tfdn.lirelle.core.testing.assertAllTextFits
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.annotation.GraphicsMode

private const val LongServer = "https://bandes-dessinees.bibliotheque-numerique.example-organisation.org:25600/komga"

/**
 * What signing in to a server of any kind added fits on a narrow phone and a tablet in landscape, in both
 * languages, at the default and the largest text size: the Server screen's title and its unknown server; the
 * sign-in screen that asks for an email address, with each message a Komga sign-in can end on — wrong email or
 * password, a sign-in the server would not finish, a provider's page in cleartext, an unknown server — and those of a
 * pasted key the server does not know, of a server too old (Kavita's and Audiobookshelf's) and of too many sign-in
 * attempts. Every text is
 * whole and breaks between words; only the server's address has no place to break.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Real font metrics: the default mode counts one pixel per character.
class KomgaSignInTextFitTest(
    screen: String,
    language: String,
    fontScale: Float,
) {

    @get:Rule(order = 0)
    val device = DeviceRule(qualifiers = "$language-$screen", fontScale = fontScale)

    @get:Rule(order = 1)
    val compose = createComposeRule()

    /** Read when a test asks for it: the device's language is set by its rule, after this class is made. */
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun text(id: Int, vararg args: Any) = context.getString(id, *args)

    private val byEmail = SignInUiState(
        serverUrl = LongServer,
        isLoadingOptions = false,
        options = LoginOptions(
            passwordEnabled = true,
            oidcProviders = listOf(OidcProvider("authentik", "Authentik", "", "", ProviderFlow.WebView)),
            identifier = SignInIdentifier.Email,
        ),
    )

    private fun showSignIn(error: ApiError) {
        compose.setContent {
            LirelleTheme {
                SignInScreen(
                    state = byEmail.copy(error = error),
                    onUsernameChange = {},
                    onPasswordChange = {},
                    onSignIn = {},
                    onOidc = {},
                    onChangeServer = {},
                    onRetry = {},
                )
            }
        }
    }

    /** The sign-in screen fits, with the email field, the [message] shown and its three buttons, each on one line. */
    private fun assertSignInFits(message: String) {
        val texts = compose.assertAllTextFits(unbreakable = setOf(LongServer))

        assertThat(texts.map { it.text }).containsAtLeast(text(R.string.sign_in_email), message)
        // Change server, Sign in, and the provider's.
        assertThat(texts.filter { it.isLabel }).hasSize(3)
    }

    @Test
    fun `the server screen fits with its title and an unknown server`() {
        compose.setContent {
            LirelleTheme {
                ServerScreen(
                    state = ServerUiState(address = LongServer, error = ServerError.Api(ApiError.UnknownServer)),
                    onAddressChange = {},
                    onContinue = {},
                    onCleartextConfirmed = {},
                    onCleartextDismissed = {},
                )
            }
        }

        val texts = compose.assertAllTextFits()

        assertThat(texts.map { it.text }).containsAtLeast(
            text(R.string.server_title),
            text(R.string.error_unknown_server),
        )
    }

    @Test
    fun `the email field fits with wrong email or password`() {
        showSignIn(ApiError.InvalidCredentials)

        assertSignInFits(text(R.string.error_invalid_email_credentials))
    }

    @Test
    fun `the email field fits with a sign-in the server would not finish`() {
        showSignIn(ApiError.SignInRefused)

        assertSignInFits(text(R.string.error_sign_in_refused))
    }

    @Test
    fun `the email field fits with a provider's page in cleartext`() {
        showSignIn(ApiError.UnencryptedSignInPage)

        assertSignInFits(text(R.string.error_unencrypted_sign_in_page))
    }

    @Test
    fun `the email field fits with an unknown server`() {
        showSignIn(ApiError.UnknownServer)

        assertSignInFits(text(R.string.error_unknown_server))
    }

    @Test
    fun `the email field fits with a pasted key the server does not know`() {
        showSignIn(ApiError.KeyRefused)

        assertSignInFits(text(R.string.error_key_refused))
    }

    @Test
    fun `the email field fits with a server too old`() {
        showSignIn(ApiError.ServerTooOld(ServerKind.Kavita, version = "0.8.6.25", minimum = "0.8.10.12"))

        assertSignInFits(text(R.string.error_server_too_old_kavita, "0.8.6.25", "0.8.10.12"))
    }

    @Test
    fun `the email field fits with an Audiobookshelf server too old`() {
        showSignIn(ApiError.ServerTooOld(ServerKind.Audiobookshelf, version = "2.25.1-beta", minimum = "2.26"))

        assertSignInFits(text(R.string.error_server_too_old_audiobookshelf, "2.25.1-beta", "2.26"))
    }

    @Test
    fun `the email field fits with too many sign-in attempts`() {
        showSignIn(ApiError.TooManyAttempts)

        assertSignInFits(text(R.string.error_too_many_attempts))
    }

    @Test
    fun `the server screen fits with an Audiobookshelf server too old`() {
        val tooOld = ApiError.ServerTooOld(ServerKind.Audiobookshelf, version = "2.25.1-beta", minimum = "2.26")
        compose.setContent {
            LirelleTheme {
                ServerScreen(
                    state = ServerUiState(address = LongServer, error = ServerError.Api(tooOld)),
                    onAddressChange = {},
                    onContinue = {},
                    onCleartextConfirmed = {},
                    onCleartextDismissed = {},
                )
            }
        }

        val texts = compose.assertAllTextFits()

        assertThat(texts.map { it.text })
            .contains(text(R.string.error_server_too_old_audiobookshelf, "2.25.1-beta", "2.26"))
    }

    companion object {
        @JvmStatic
        @Parameters(name = "{0}, {1}, font scale {2}")
        fun conditions(): List<Array<Any>> =
            listOf("w320dp-h640dp-xhdpi", "w1280dp-h800dp-xhdpi").flatMap { screen ->
                listOf("en", "fr").flatMap { language ->
                    listOf(1.0f, 2.0f).map { fontScale -> arrayOf(screen, language, fontScale) }
                }
            }
    }
}
