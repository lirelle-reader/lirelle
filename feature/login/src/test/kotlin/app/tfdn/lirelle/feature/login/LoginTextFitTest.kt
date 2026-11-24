package app.tfdn.lirelle.feature.login

import androidx.compose.ui.test.junit4.createComposeRule
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.LoginOptions
import app.tfdn.lirelle.core.network.OidcProvider
import app.tfdn.lirelle.core.testing.DeviceRule
import app.tfdn.lirelle.core.testing.assertAllTextFits
import com.google.common.truth.Truth.assertThat
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.annotation.GraphicsMode

/**
 * No text of the Server and Sign-in screens overflows, is clipped or breaks mid-word on a
 * narrow phone, in both languages, at the default and at the largest text size.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Real font metrics: the default mode counts one pixel per character.
class LoginTextFitTest(
    language: String,
    private val fontScale: Float,
) {

    @get:Rule(order = 0)
    val device = DeviceRule(qualifiers = "$language-w320dp-h640dp-xhdpi", fontScale = fontScale)

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val longServerUrl = "https://bibliotheque-numerique.intranet.example-organisation.org:8443"
    private val providers = listOf(
        OidcProvider("authentik", "Authentik", "client-id-1", "openid"),
        OidcProvider("sso", "Example Organisation Single Sign-On", "client-id-2", "openid"),
    )
    private val signIn = SignInUiState(
        serverUrl = longServerUrl,
        isLoadingOptions = false,
        options = LoginOptions(passwordEnabled = true, oidcProviders = providers),
    )

    private fun showServer(state: ServerUiState) {
        compose.setContent {
            LirelleTheme {
                ServerScreen(
                    state = state,
                    onAddressChange = {},
                    onContinue = {},
                    onCleartextConfirmed = {},
                    onCleartextDismissed = {},
                )
            }
        }
    }

    private fun showSignIn(state: SignInUiState) {
        compose.setContent {
            LirelleTheme {
                SignInScreen(
                    state = state,
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

    @Test
    fun `the server screen fits`() {
        showServer(ServerUiState())

        compose.assertAllTextFits()
    }

    @Test
    fun `the server screen fits with an error while checking`() {
        showServer(
            ServerUiState(
                address = "books.example.com",
                isChecking = true,
                error = ServerError.Api(ApiError.DefaultPasswordMustChange),
            ),
        )

        compose.assertAllTextFits()
    }

    @Test
    fun `the cleartext dialog fits with a long address`() {
        val address = longServerUrl.replace("https", "http")
        showServer(ServerUiState(address = address, cleartextAddress = address))

        val labels = compose.assertAllTextFits().filter { it.isLabel }

        // Continue, and the two buttons of the dialog.
        assertThat(labels).hasSize(3)
    }

    @Test
    fun `the sign-in screen fits with a long server address and long provider names`() {
        showSignIn(signIn.copy(isCleartext = true, error = ApiError.AccountLocked(retryAfterSeconds = 120)))

        val texts = compose.assertAllTextFits(unbreakable = setOf(longServerUrl))

        // Change server, Sign in, and one button per provider.
        assertThat(texts.filter { it.isLabel }).hasSize(4)
    }

    @Test
    fun `a long server address and the Change server button do not squeeze each other`() {
        showSignIn(signIn)

        val texts = compose.assertAllTextFits(unbreakable = setOf(longServerUrl))
        val address = texts.single { it.text == longServerUrl }.boundsInRoot
        val button = texts.first { it.isLabel }.boundsInRoot

        // Stacked: each has the width of the form rather than a share of one line.
        assertThat(button.top).isAtLeast(address.bottom)
        assertThat(address.width).isGreaterThan(button.width / 2)
    }

    @Test
    fun `a short server address shares its line with the Change server button`() {
        Assume.assumeTrue(fontScale == 1.0f)
        showSignIn(signIn.copy(serverUrl = "https://b.example"))

        val texts = compose.assertAllTextFits()
        val address = texts.single { it.text == "https://b.example" }.boundsInRoot
        val button = texts.first { it.isLabel }.boundsInRoot

        assertThat(button.left).isAtLeast(address.right)
        assertThat(button.top).isLessThan(address.bottom)
    }

    @Test
    fun `the sign-in screen fits when the options could not be loaded`() {
        showSignIn(signIn.copy(options = null, error = ApiError.ServerUnreachable))

        compose.assertAllTextFits(unbreakable = setOf(longServerUrl))
    }

    @Test
    fun `the sign-in screen fits when the server offers no usable method`() {
        showSignIn(signIn.copy(options = LoginOptions(passwordEnabled = false, oidcProviders = emptyList())))

        compose.assertAllTextFits(unbreakable = setOf(longServerUrl))
    }

    companion object {
        @JvmStatic
        @Parameters(name = "{0}, font scale {1}")
        fun conditions(): List<Array<Any>> =
            listOf("en", "fr").flatMap { language ->
                listOf(1.0f, 2.0f).map { fontScale -> arrayOf(language, fontScale) }
            }
    }
}
