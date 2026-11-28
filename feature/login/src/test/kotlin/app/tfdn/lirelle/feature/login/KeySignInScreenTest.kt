package app.tfdn.lirelle.feature.login

import android.content.Context
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.network.LoginOptions
import app.tfdn.lirelle.core.network.OidcProvider
import app.tfdn.lirelle.core.network.OidcRedirectRelay
import app.tfdn.lirelle.core.network.ProviderFlow
import app.tfdn.lirelle.core.network.SignInIdentifier
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.core.testing.FakeAuthRepository
import app.tfdn.lirelle.core.testing.TestUserAgent
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The key a server lets the user paste to sign in, under what the sign-in screen already offers, which does not
 * move: the identifier, the password and their button, then the provider's; then "or", the key's field, where to
 * make one on a server of that kind, and the key's button. Nothing of it on a server that takes no pasted key. What
 * went wrong with the key is said on its field, where the user looks, and not also above the form.
 */
@RunWith(RobolectricTestRunner::class)
class KeySignInScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val provider = OidcProvider("oidc", "Authentik", "", "", ProviderFlow.WebView)

    private val kavitaTooOld = ApiError.ServerTooOld(ServerKind.Kavita, version = "0.8.6.25", minimum = "0.8.10.12")

    private fun kavita(passwords: Boolean = true, providers: List<OidcProvider> = emptyList()) = SignInUiState(
        serverUrl = "https://livres.example.com",
        kind = ServerKind.Kavita,
        isLoadingOptions = false,
        options = LoginOptions(passwordEnabled = passwords, oidcProviders = providers, keyEntry = true),
    )

    private fun show(state: SignInUiState, onKeyChange: (String) -> Unit = {}, onKeySignIn: () -> Unit = {}) {
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
                    onKeyChange = onKeyChange,
                    onKeySignIn = onKeySignIn,
                )
            }
        }
    }

    private fun text(id: Int, vararg args: Any) = context.getString(id, *args)

    private fun or() = compose.onNodeWithText(text(R.string.sign_in_or))
    private fun keyField() = compose.onNode(hasText(text(R.string.sign_in_key)) and hasSetTextAction())
    /** The sentence itself, under the field it belongs to: a screen reader says it with the field. */
    private fun sentence() =
        compose.onNodeWithText(text(R.string.sign_in_key_where_kavita), useUnmergedTree = true)
    private fun keyButton() = compose.onNode(hasText(text(R.string.sign_in_with_key)) and hasClickAction())
    private fun identifier() = compose.onNodeWithText(text(R.string.sign_in_username))
    private fun password() = compose.onNodeWithText(text(R.string.sign_in_password))
    private fun signIn() = compose.onNode(hasText(text(R.string.sign_in_submit)) and hasClickAction())
    private fun providerButton() = compose.onNodeWithText(text(R.string.sign_in_with_provider, "Authentik"))

    private fun SemanticsNodeInteraction.top(): Float = fetchSemanticsNode().positionInRoot.y

    private fun assertNoKeyBlock() {
        or().assertDoesNotExist()
        keyField().assertDoesNotExist()
        sentence().assertDoesNotExist()
        keyButton().assertDoesNotExist()
    }

    @Test
    fun `a server that signs in with a session offers no key`() {
        val bookOrbit = LoginOptions(passwordEnabled = true, oidcProviders = listOf(provider))
        show(kavita().copy(kind = ServerKind.BookOrbit, options = bookOrbit))

        assertNoKeyBlock()
        identifier().assertExists()
        providerButton().assertExists()
    }

    @Test
    fun `a Komga server offers no key`() {
        val komga = LoginOptions(
            passwordEnabled = true,
            oidcProviders = listOf(provider),
            identifier = SignInIdentifier.Email,
        )
        show(kavita().copy(kind = ServerKind.Komga, options = komga))

        assertNoKeyBlock()
        compose.onNodeWithText(text(R.string.sign_in_email)).assertExists()
    }

    @Test
    fun `without a provider, the key comes under the password`() {
        show(kavita())

        identifier().assertExists()
        password().assertExists()
        signIn().assertExists()
        providerButton().assertDoesNotExist()
        or().assertExists()
        keyField().assertExists()
        sentence().assertExists()
        keyButton().assertExists()
    }

    @Test
    fun `everything is in its order, the key last`() {
        show(kavita(providers = listOf(provider)))

        val nodes =
            listOf(identifier(), password(), signIn(), providerButton(), or(), keyField(), sentence(), keyButton())
        val tops = nodes.map { it.top() }

        assertThat(tops).isInStrictOrder()
    }

    @Test
    fun `with passwords disabled, only the provider and the key are offered`() {
        show(kavita(passwords = false, providers = listOf(provider)))

        identifier().assertDoesNotExist()
        password().assertDoesNotExist()
        signIn().assertDoesNotExist()
        val tops = listOf(providerButton(), or(), keyField(), sentence(), keyButton()).map { it.top() }
        assertThat(tops).isInStrictOrder()
    }

    @Test
    fun `a key alone has no line above it`() {
        show(kavita(passwords = false))

        or().assertDoesNotExist()
        compose.onNodeWithText(text(R.string.sign_in_no_method)).assertDoesNotExist()
        keyField().assertExists()
        sentence().assertExists()
        keyButton().assertExists()
    }

    @Test
    fun `a kind with no sentence yet shows none`() {
        show(kavita().copy(kind = ServerKind.Komga))

        keyField().assertExists()
        sentence().assertDoesNotExist()
        keyButton().assertExists()
    }

    @Test
    fun `the key's button is disabled until a key is typed`() {
        show(kavita())
        keyButton().assertIsNotEnabled()
    }

    @Test
    fun `a blank key does not enable its button`() {
        show(kavita().copy(key = "   "))
        keyButton().assertIsNotEnabled()
    }

    @Test
    fun `the key's button is enabled once a key is typed, whatever the password form holds`() {
        show(kavita().copy(key = "k3y"))

        keyButton().assertIsEnabled()
        signIn().assertIsNotEnabled()
    }

    @Test
    fun `the key's button is disabled while a sign-in is in progress`() {
        show(kavita().copy(key = "k3y", isBusy = true))
        keyButton().assertIsNotEnabled()
    }

    @Test
    fun `typing the key and pressing its button report them`() {
        val typed = mutableListOf<String>()
        var signIns = 0
        show(kavita().copy(key = "k3y"), onKeyChange = { typed += it }, onKeySignIn = { signIns++ })

        keyField().performScrollTo().performTextInput("x")
        keyButton().performScrollTo().performClick()

        assertThat(typed).hasSize(1)
        assertThat(signIns).isEqualTo(1)
    }

    @Test
    fun `the key is shown as typed, and the keyboard's Done sends it`() {
        var signIns = 0
        show(kavita().copy(key = "k3y"), onKeySignIn = { signIns++ })

        keyField().assert(hasText("k3y"))
        val config = keyField().fetchSemanticsNode().config
        assertThat(config.getOrNull(SemanticsProperties.Password)).isNull()
        assertThat(config.getOrNull(SemanticsProperties.ContentType)).isNull()
        keyField().assert(SemanticsMatcher.expectValue(SemanticsProperties.ImeAction, ImeAction.Done))
        keyField().performScrollTo().performImeAction()
        assertThat(signIns).isEqualTo(1)
    }

    @Test
    fun `the key is asked of a keyboard that neither corrects, nor suggests, nor capitalises`() {
        assertThat(KeyKeyboard.autoCorrectEnabled).isFalse()
        assertThat(KeyKeyboard.capitalization).isEqualTo(KeyboardCapitalization.None)
        assertThat(KeyKeyboard.keyboardType).isEqualTo(KeyboardType.Ascii)
        assertThat(KeyKeyboard.imeAction).isEqualTo(ImeAction.Done)
    }

    @Test
    fun `in English, the key's words`() {
        show(kavita())

        compose.onNodeWithText("or").assertExists()
        compose.onNodeWithText("Auth key").assertExists()
        compose.onNodeWithText("Make one in Kavita: Settings, then “Auth Keys / OPDS”.").assertExists()
        compose.onNodeWithText("Sign in with the key").assertExists()
    }

    @Test
    @Config(qualifiers = "fr")
    fun `in French, the key's words`() {
        show(kavita())

        compose.onNodeWithText("ou").assertExists()
        compose.onNodeWithText("Clé d'accès").assertExists()
        compose.onNodeWithText("À créer dans Kavita : Paramètres, puis « Clé d'authentification / OPDS ».")
            .assertExists()
        compose.onNodeWithText("Se connecter avec la clé").assertExists()
    }

    /** Every text that says [message], wherever it is on the screen. */
    private fun saying(message: String) = compose.onAllNodesWithText(message, useUnmergedTree = true)

    private val inError = SemanticsMatcher.keyIsDefined(SemanticsProperties.Error)

    @Test
    fun `what went wrong with the key is said on its field, in place of where to make one, and nowhere else`() {
        show(kavita(providers = listOf(provider)).copy(key = "k3y", keyError = ApiError.KeyRefused))
        val message = "The server does not know this auth key."

        saying(message).assertCountEquals(1)
        val said = saying(message).onFirst()
        assertThat(listOf(providerButton().top(), keyField().top(), said.top(), keyButton().top())).isInStrictOrder()
        sentence().assertDoesNotExist()
        keyField().assert(inError)
        // Announced when it appears, as the screen's other errors are.
        said.assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
    }

    @Test
    fun `what went wrong with a password or a provider stays above the form, and the key's field is as it was`() {
        show(kavita().copy(error = ApiError.InvalidCredentials))
        val message = text(R.string.error_invalid_credentials)

        saying(message).assertCountEquals(1)
        assertThat(saying(message).onFirst().top()).isLessThan(identifier().top())
        sentence().assertExists()
        keyField().assert(!inError)
    }

    @Test
    fun `the key's error goes, and where to make one comes back, once the key changes`() {
        val auth = FakeAuthRepository().apply {
            serverUrl.value = "https://livres.example.com"
            serverKind.value = ServerKind.Kavita
            loginOptionsResult = ApiResult.Success(checkNotNull(kavita().options))
            signInWithKeyResult = ApiResult.Failure(ApiError.KeyRefused)
        }
        val viewModel = SignInViewModel(auth, OidcRedirectRelay(), TestUserAgent, server = null, kind = null)
        compose.setContent { LirelleTheme { SignInRoute(onChangeServer = {}, viewModel = viewModel) } }
        val message = text(R.string.error_key_refused)

        keyField().performScrollTo().performTextInput("k3y")
        keyButton().performScrollTo().performClick()
        saying(message).assertCountEquals(1)
        keyField().assert(inError)

        keyField().performScrollTo().performTextInput("4")

        saying(message).assertCountEquals(0)
        sentence().assertExists()
        keyField().assert(!inError)
    }

    @Test
    fun `a server too old says the version it runs and the one needed`() {
        show(kavita().copy(keyError = kavitaTooOld))
        compose.onNodeWithText(
            "This Kavita server runs version 0.8.6.25. Lirelle needs version 0.8.10.12 or later.",
            useUnmergedTree = true,
        ).assertExists()
    }

    @Test
    @Config(qualifiers = "fr")
    fun `in French, a key the server does not know says so`() {
        show(kavita().copy(keyError = ApiError.KeyRefused))
        compose.onNodeWithText("Cette clé d'accès n'est pas reconnue par le serveur.", useUnmergedTree = true)
            .assertExists()
    }

    @Test
    @Config(qualifiers = "fr")
    fun `in French, a server too old says the version it runs and the one needed`() {
        show(kavita().copy(keyError = kavitaTooOld))
        compose.onNodeWithText(
            "Ce serveur Kavita est en version 0.8.6.25. Lirelle a besoin de la version 0.8.10.12 ou plus récente.",
            useUnmergedTree = true,
        ).assertExists()
    }
}
