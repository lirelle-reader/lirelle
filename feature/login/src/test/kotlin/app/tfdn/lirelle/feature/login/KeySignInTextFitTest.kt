package app.tfdn.lirelle.feature.login

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.LoginOptions
import app.tfdn.lirelle.core.network.OidcProvider
import app.tfdn.lirelle.core.network.ProviderFlow
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

private const val KavitaServer = "https://livres.bibliotheque-numerique.example-organisation.org:5000/kavita"

/**
 * The key a Kavita server lets the user paste fits on a narrow phone and a tablet in landscape, in both languages,
 * at the default and the largest text size, in each of the server's three situations: "or", the field's name, the
 * sentence that says where to make a key — whole, on as many lines as it needs — and the key's button, whole too,
 * on two lines when one is too short.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Real font metrics: the default mode counts one pixel per character.
class KeySignInTextFitTest(
    private val screen: String,
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

    private val provider = OidcProvider("oidc", "Authentik", "", "", ProviderFlow.WebView)

    private fun show(passwords: Boolean, providers: List<OidcProvider>, keyError: ApiError? = null) {
        compose.setContent {
            LirelleTheme {
                SignInScreen(
                    state = SignInUiState(
                        serverUrl = KavitaServer,
                        kind = ServerKind.Kavita,
                        isLoadingOptions = false,
                        options = LoginOptions(passwordEnabled = passwords, oidcProviders = providers, keyEntry = true),
                        keyError = keyError,
                    ),
                    onUsernameChange = {},
                    onPasswordChange = {},
                    onSignIn = {},
                    onOidc = {},
                    onChangeServer = {},
                    onRetry = {},
                    onKeyChange = {},
                    onKeySignIn = {},
                )
            }
        }
    }

    /** Everything fits, with the key's texts among it; [or] tells whether the line above the key is there. */
    private fun assertKeyFits(or: Boolean) {
        val keyButton = text(R.string.sign_in_with_key)
        val texts = compose.assertAllTextFits(unbreakable = setOf(KavitaServer), wrappingLabels = setOf(keyButton))

        val shown = texts.map { it.text }
        assertThat(shown).containsAtLeast(
            text(R.string.sign_in_key),
            text(R.string.sign_in_key_where_kavita),
            keyButton,
        )
        assertThat(text(R.string.sign_in_or) in shown).isEqualTo(or)
    }

    @Test
    fun `the key fits under the password`() {
        show(passwords = true, providers = emptyList())

        assertKeyFits(or = true)
    }

    @Test
    fun `the key fits under the password and the provider`() {
        show(passwords = true, providers = listOf(provider))

        assertKeyFits(or = true)
    }

    @Test
    fun `the key fits under the provider alone`() {
        show(passwords = false, providers = listOf(provider))

        assertKeyFits(or = true)
    }

    @Test
    fun `the key fits alone`() {
        show(passwords = false, providers = emptyList())

        assertKeyFits(or = false)
    }

    /** The longest thing the key's field says: whole under it, on as many lines as it needs. */
    @Test
    fun `what went wrong with the key fits under its field`() {
        show(
            passwords = true,
            providers = listOf(provider),
            keyError = ApiError.ServerTooOld(version = "0.8.8.3-beta", minimum = "0.8.10.12"),
        )

        val keyButton = text(R.string.sign_in_with_key)
        val texts = compose.assertAllTextFits(unbreakable = setOf(KavitaServer), wrappingLabels = setOf(keyButton))

        val said = texts.single { it.text == text(R.string.error_server_too_old, "0.8.8.3-beta", "0.8.10.12") }
        if (screen.startsWith("w320dp")) assertThat(said.lineCount).isGreaterThan(1)
        assertThat(texts.map { it.text }).doesNotContain(text(R.string.sign_in_key_where_kavita))
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
