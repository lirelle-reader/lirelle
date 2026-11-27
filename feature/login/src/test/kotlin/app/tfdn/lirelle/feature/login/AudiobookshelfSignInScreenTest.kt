package app.tfdn.lirelle.feature.login

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.LoginOptions
import app.tfdn.lirelle.core.network.OidcProvider
import app.tfdn.lirelle.core.network.OidcRedirectRelay
import app.tfdn.lirelle.core.network.ProviderFlow
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.core.testing.FakeAuthRepository
import app.tfdn.lirelle.core.testing.MainDispatcherRule
import app.tfdn.lirelle.core.testing.TestUserAgent
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The sign-in screen with an Audiobookshelf server: the screen itself is the same as for the other kinds. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AudiobookshelfSignInScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val openId = OidcProvider("openid", "Authentik", "", "", ProviderFlow.Browser)

    private fun state(passwords: Boolean, providers: List<OidcProvider>, error: ApiError? = null) = SignInUiState(
        serverUrl = "https://abs.example.com",
        kind = ServerKind.Audiobookshelf,
        isLoadingOptions = false,
        options = LoginOptions(passwordEnabled = passwords, oidcProviders = providers),
        error = error,
    )

    private fun show(state: SignInUiState, onOidc: (OidcProvider) -> Unit = {}) {
        compose.setContent {
            LirelleTheme {
                SignInScreen(
                    state = state,
                    onUsernameChange = {},
                    onPasswordChange = {},
                    onSignIn = {},
                    onOidc = onOidc,
                    onChangeServer = {},
                    onRetry = {},
                )
            }
        }
    }

    private fun text(id: Int, vararg args: Any) = context.getString(id, *args)

    @Test
    fun `a server with local accounts only shows the password form`() {
        show(state(passwords = true, providers = emptyList()))

        compose.onNodeWithText(text(R.string.sign_in_username)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.sign_in_password)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.sign_in_with_provider, "Authentik")).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.sign_in_key)).assertDoesNotExist()
    }

    @Test
    fun `a server with local accounts and OpenID shows both, and no key`() {
        show(state(passwords = true, providers = listOf(openId)))

        compose.onNodeWithText(text(R.string.sign_in_password)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.sign_in_with_provider, "Authentik")).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.sign_in_key)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.sign_in_with_key)).assertDoesNotExist()
    }

    @Test
    fun `a server with OpenID only shows the provider alone, with the server's button text`() {
        show(state(passwords = false, providers = listOf(openId)))

        compose.onNodeWithText(text(R.string.sign_in_with_provider, "Authentik")).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.sign_in_username)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.sign_in_password)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.sign_in_key)).assertDoesNotExist()
    }

    @Test
    fun `pressing the provider reports it`() {
        val chosen = mutableListOf<OidcProvider>()
        show(state(passwords = false, providers = listOf(openId)), onOidc = { chosen += it })

        compose.onNodeWithText(text(R.string.sign_in_with_provider, "Authentik")).performClick()

        assertThat(chosen).containsExactly(openId)
    }

    @Test
    fun `too many attempts shows its message`() {
        show(state(passwords = true, providers = emptyList(), error = ApiError.TooManyAttempts))

        compose.onNodeWithText(text(R.string.error_too_many_attempts)).assertIsDisplayed()
    }

    @Test
    fun `a server too old shows its message`() {
        val tooOld = ApiError.ServerTooOld(ServerKind.Audiobookshelf, version = "2.25.1", minimum = "2.26")
        show(state(passwords = true, providers = emptyList(), error = tooOld))

        compose.onNodeWithText(text(R.string.error_server_too_old_audiobookshelf, "2.25.1", "2.26"))
            .assertIsDisplayed()
    }

    @Test
    fun `the provider is begun on the Audiobookshelf kind and its address is sent to the browser`() =
        runTest(mainRule.dispatcher) {
            val auth = FakeAuthRepository().apply { serverUrl.value = "https://abs.example.com" }
            val viewModel = SignInViewModel(
                auth,
                OidcRedirectRelay(),
                TestUserAgent,
                server = "https://abs.example.com",
                kind = ServerKind.Audiobookshelf,
            )

            viewModel.browserUrls.test {
                viewModel.onOidc(openId)
                assertThat(awaitItem()).isEqualTo("https://auth.example.com/authorize?state=state-1")
            }

            assertThat(auth.calls).contains("beginOidc https://abs.example.com openid")
            assertThat(auth.kinds.last()).isEqualTo(ServerKind.Audiobookshelf)
        }
}
