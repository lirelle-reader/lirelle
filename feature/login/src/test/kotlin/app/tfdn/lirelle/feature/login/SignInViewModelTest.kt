package app.tfdn.lirelle.feature.login

import app.cash.turbine.test
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.network.LoginOptions
import app.tfdn.lirelle.core.network.OidcProvider
import app.tfdn.lirelle.core.network.OidcRedirectRelay
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.core.testing.FakeAuthRepository
import app.tfdn.lirelle.core.testing.MainDispatcherRule
import app.tfdn.lirelle.core.testing.TestUserAgent
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SignInViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val auth = FakeAuthRepository().apply {
        serverUrl.value = "https://books.example.com"
    }
    private val relay = OidcRedirectRelay()
    private val provider = OidcProvider("authentik", "Authentik", "client-id-1", "openid profile email")
    private val redirect = "bookorbit://oauth2-callback?code=code-1&state=state-1"

    private fun viewModel() = SignInViewModel(auth, relay, TestUserAgent, server = null, kind = null)

    @Test
    fun `the sign-in goes to the kind remembered with the address`() = runTest(mainRule.dispatcher) {
        // Refused, so that the form is free again for the provider.
        auth.signInResult = ApiResult.Failure(ApiError.InvalidCredentials)
        val viewModel = viewModel()
        viewModel.onUsernameChange("reader")
        viewModel.onPasswordChange("secret")

        viewModel.onSignIn()
        viewModel.onOidc(provider)

        // The options, the password, the provider.
        assertThat(auth.kinds).containsExactly(ServerKind.BookOrbit, ServerKind.BookOrbit, ServerKind.BookOrbit)
    }

    @Test
    fun `an address remembered with a kind this version does not know is to be checked again`() =
        runTest(mainRule.dispatcher) {
            auth.serverKind.value = null

            val state = viewModel().state.value

            assertThat(state.needsServer).isTrue()
            assertThat(auth.calls).isEmpty()
        }

    @Test
    fun `a profile signing in again does so on its server, as its own kind, whatever is remembered`() =
        runTest(mainRule.dispatcher) {
            auth.serverKind.value = null

            val state = SignInViewModel(
                auth,
                relay,
                TestUserAgent,
                server = "https://own.example.com",
                ServerKind.BookOrbit,
            ).state.value

            assertThat(state.needsServer).isFalse()
            assertThat(auth.calls).containsExactly("loginOptions https://own.example.com")
            assertThat(auth.kinds).containsExactly(ServerKind.BookOrbit)
        }

    @Test
    fun `loads the sign-in options of the remembered server`() = runTest(mainRule.dispatcher) {
        auth.loginOptionsResult =
            ApiResult.Success(LoginOptions(passwordEnabled = true, oidcProviders = listOf(provider)))

        val state = viewModel().state.value

        assertThat(auth.calls).containsExactly("loginOptions https://books.example.com")
        assertThat(state.serverUrl).isEqualTo("https://books.example.com")
        assertThat(state.isCleartext).isFalse()
        assertThat(state.isLoadingOptions).isFalse()
        assertThat(state.options?.oidcProviders).containsExactly(provider)
        assertThat(state.error).isNull()
    }

    @Test
    fun `flags an http server as cleartext`() = runTest(mainRule.dispatcher) {
        auth.serverUrl.value = "http://homeserver:3000"

        assertThat(viewModel().state.value.isCleartext).isTrue()
    }

    @Test
    fun `failing to load the options shows an error and can be retried`() = runTest(mainRule.dispatcher) {
        auth.loginOptionsResult = ApiResult.Failure(ApiError.ServerUnreachable)
        val viewModel = viewModel()
        assertThat(viewModel.state.value.error).isEqualTo(ApiError.ServerUnreachable)
        assertThat(viewModel.state.value.options).isNull()

        auth.loginOptionsResult =
            ApiResult.Success(LoginOptions(passwordEnabled = true, oidcProviders = emptyList()))
        viewModel.loadOptions()

        assertThat(viewModel.state.value.options).isNotNull()
        assertThat(viewModel.state.value.error).isNull()
    }

    @Test
    fun `password sign-in sends the trimmed username`() = runTest(mainRule.dispatcher) {
        val viewModel = viewModel()
        viewModel.onUsernameChange(" reader ")
        viewModel.onPasswordChange("secret")

        viewModel.onSignIn()

        assertThat(auth.calls).contains("signIn https://books.example.com reader secret")
        assertThat(viewModel.state.value.isBusy).isTrue()
        assertThat(viewModel.state.value.error).isNull()
    }

    @Test
    fun `the form can be submitted only with a username and a password`() = runTest(mainRule.dispatcher) {
        val viewModel = viewModel()
        assertThat(viewModel.state.value.canSubmit).isFalse()

        viewModel.onUsernameChange("reader")
        assertThat(viewModel.state.value.canSubmit).isFalse()

        viewModel.onPasswordChange("secret")
        assertThat(viewModel.state.value.canSubmit).isTrue()

        viewModel.onUsernameChange("   ")
        assertThat(viewModel.state.value.canSubmit).isFalse()
    }

    @Test
    fun `blank credentials are not sent`() = runTest(mainRule.dispatcher) {
        val viewModel = viewModel()
        viewModel.onUsernameChange("  ")
        viewModel.onPasswordChange("secret")

        viewModel.onSignIn()
        viewModel.onUsernameChange("reader")
        viewModel.onPasswordChange("")
        viewModel.onSignIn()

        assertThat(auth.calls).containsExactly("loginOptions https://books.example.com")
        assertThat(viewModel.state.value.error).isNull()
    }

    @Test
    fun `with no remembered server it asks for one and loads nothing`() = runTest(mainRule.dispatcher) {
        auth.serverUrl.value = null

        val state = viewModel().state.value

        assertThat(state.needsServer).isTrue()
        assertThat(state.isLoadingOptions).isFalse()
        assertThat(auth.calls).isEmpty()
    }

    @Test
    fun `with a remembered server it does not ask for one`() = runTest(mainRule.dispatcher) {
        assertThat(viewModel().state.value.needsServer).isFalse()
    }

    @Test
    fun `the sign-in methods are loading until the server answers`() = runTest(mainRule.dispatcher) {
        auth.gate = CompletableDeferred()

        val viewModel = viewModel()
        assertThat(viewModel.state.value.isLoadingOptions).isTrue()

        auth.gate?.complete(Unit)
        assertThat(viewModel.state.value.isLoadingOptions).isFalse()
    }

    @Test
    fun `a password sign-in stays busy once the server has accepted it`() = runTest(mainRule.dispatcher) {
        val viewModel = viewModel()
        viewModel.onUsernameChange("reader")
        viewModel.onPasswordChange("secret")
        auth.gate = CompletableDeferred()

        viewModel.onSignIn()
        assertThat(viewModel.state.value.isBusy).isTrue()

        // Signed in: the screen leaves once the profile is open, which may take a moment after a sign-out.
        // An idle form meanwhile would let the user sign in a second time.
        auth.gate?.complete(Unit)
        assertThat(viewModel.state.value.isBusy).isTrue()
        assertThat(viewModel.state.value.canSubmit).isFalse()

        viewModel.onSignIn()
        assertThat(auth.calls.filter { it.startsWith("signIn") }).hasSize(1)
    }

    @Test
    fun `signing in again while a password sign-in is in flight sends nothing more`() =
        runTest(mainRule.dispatcher) {
            val viewModel = viewModel()
            viewModel.onUsernameChange("reader")
            viewModel.onPasswordChange("secret")
            auth.gate = CompletableDeferred()
            viewModel.onSignIn()

            viewModel.onSignIn()
            viewModel.onOidc(provider)
            auth.gate?.complete(Unit)

            assertThat(auth.calls.filter { it.startsWith("signIn") }).hasSize(1)
            assertThat(auth.calls.filter { it.startsWith("beginOidc") }).isEmpty()
        }

    @Test
    fun `preparing oidc is busy until the server answers`() = runTest(mainRule.dispatcher) {
        auth.beginOidcResult = ApiResult.Failure(ApiError.ServerUnreachable)
        val viewModel = viewModel()
        auth.gate = CompletableDeferred()

        viewModel.onOidc(provider)
        assertThat(viewModel.state.value.isBusy).isTrue()

        auth.gate?.complete(Unit)
        assertThat(viewModel.state.value.isBusy).isFalse()
    }

    @Test
    fun `choosing a provider again while oidc is being prepared prepares nothing more`() =
        runTest(mainRule.dispatcher) {
            val viewModel = viewModel()
            viewModel.onUsernameChange("reader")
            viewModel.onPasswordChange("secret")
            auth.gate = CompletableDeferred()
            viewModel.onOidc(provider)

            viewModel.onOidc(provider)
            viewModel.onSignIn()
            auth.gate?.complete(Unit)

            assertThat(auth.calls.filter { it.startsWith("beginOidc") }).hasSize(1)
            assertThat(auth.calls.filter { it.startsWith("signIn") }).isEmpty()
        }

    @Test
    fun `a successful password sign-in forgets the password`() = runTest(mainRule.dispatcher) {
        val viewModel = viewModel()
        viewModel.onUsernameChange("reader")
        viewModel.onPasswordChange("secret")

        viewModel.onSignIn()

        assertThat(viewModel.state.value.password).isEmpty()
    }

    @Test
    fun `a refused password is kept so the user can correct it`() = runTest(mainRule.dispatcher) {
        auth.signInResult = ApiResult.Failure(ApiError.InvalidCredentials)
        val viewModel = viewModel()
        viewModel.onUsernameChange("reader")
        viewModel.onPasswordChange("wrong")

        viewModel.onSignIn()

        assertThat(viewModel.state.value.password).isEqualTo("wrong")
    }

    @Test
    fun `a refused password shows the error`() = runTest(mainRule.dispatcher) {
        auth.signInResult = ApiResult.Failure(ApiError.InvalidCredentials)
        val viewModel = viewModel()
        viewModel.onUsernameChange("reader")
        viewModel.onPasswordChange("wrong")

        viewModel.onSignIn()

        assertThat(viewModel.state.value.error).isEqualTo(ApiError.InvalidCredentials)
        assertThat(viewModel.state.value.isBusy).isFalse()
    }

    @Test
    fun `editing a field clears the error`() = runTest(mainRule.dispatcher) {
        auth.signInResult = ApiResult.Failure(ApiError.InvalidCredentials)
        val viewModel = viewModel()
        viewModel.onUsernameChange("reader")
        viewModel.onPasswordChange("wrong")
        viewModel.onSignIn()
        assertThat(viewModel.state.value.error).isEqualTo(ApiError.InvalidCredentials)

        viewModel.onPasswordChange("x")

        assertThat(viewModel.state.value.error).isNull()
    }

    @Test
    fun `choosing a provider asks to open the browser`() = runTest(mainRule.dispatcher) {
        val viewModel = viewModel()

        viewModel.browserUrls.test {
            viewModel.onOidc(provider)
            assertThat(awaitItem()).isEqualTo("https://auth.example.com/authorize?state=state-1")
        }

        assertThat(auth.calls).contains("beginOidc https://books.example.com authentik")
        assertThat(viewModel.state.value.isBusy).isTrue()
    }

    @Test
    fun `failing to prepare oidc shows the error`() = runTest(mainRule.dispatcher) {
        auth.beginOidcResult = ApiResult.Failure(ApiError.ServerUnreachable)
        val viewModel = viewModel()

        viewModel.browserUrls.test {
            viewModel.onOidc(provider)
            expectNoEvents()
        }

        assertThat(viewModel.state.value.error).isEqualTo(ApiError.ServerUnreachable)
        assertThat(viewModel.state.value.isBusy).isFalse()
    }

    @Test
    fun `the redirect completes the sign-in`() = runTest(mainRule.dispatcher) {
        val viewModel = viewModel()
        viewModel.onOidc(provider)

        relay.deliver(redirect)

        assertThat(auth.calls).contains("completeOidc $redirect")
        assertThat(viewModel.state.value.error).isNull()
    }

    @Test
    fun `a redirect that signs in forgets a password and a key typed beside it`() = runTest(mainRule.dispatcher) {
        val viewModel = viewModel()
        viewModel.onPasswordChange("s3cr3t")
        viewModel.pastedKey.onChange("p4st3d-k3y")
        viewModel.onOidc(provider)

        relay.deliver(redirect)

        assertThat(viewModel.state.value.password).isEmpty()
        assertThat(viewModel.state.value.key).isEmpty()
        assertThat(viewModel.state.value.isBusy).isTrue()
    }

    @Test
    fun `a redirect delivered before the screen existed is still used`() = runTest(mainRule.dispatcher) {
        // The app was killed while the browser was in front: the request is still on disk.
        auth.oidcPending = true
        relay.deliver(redirect)

        viewModel()

        assertThat(auth.calls).contains("completeOidc $redirect")
    }

    @Test
    fun `a redirect refused after the screen was recreated still shows its error`() =
        runTest(mainRule.dispatcher) {
            auth.oidcPending = true
            auth.completeOidcResult = ApiResult.Failure(ApiError.OidcRefused)
            relay.deliver(redirect)

            val viewModel = viewModel()

            assertThat(auth.calls).contains("completeOidc $redirect")
            assertThat(viewModel.state.value.error).isEqualTo(ApiError.OidcRefused)
        }

    @Test
    fun `a redirect nobody asked for is ignored`() = runTest(mainRule.dispatcher) {
        val viewModel = viewModel()

        relay.deliver(redirect)

        assertThat(auth.calls).doesNotContain("completeOidc $redirect")
        assertThat(viewModel.state.value.error).isNull()
        assertThat(viewModel.state.value.isBusy).isFalse()
    }

    @Test
    fun `a stale redirect kept by the relay is ignored by the next screen`() = runTest(mainRule.dispatcher) {
        relay.deliver(redirect)

        val viewModel = viewModel()

        assertThat(auth.calls).doesNotContain("completeOidc $redirect")
        assertThat(viewModel.state.value.error).isNull()
    }

    @Test
    fun `a redirect nobody asked for does not disturb a password sign-in`() = runTest(mainRule.dispatcher) {
        val viewModel = viewModel()
        viewModel.onUsernameChange("reader")
        viewModel.onPasswordChange("secret")
        auth.gate = CompletableDeferred()
        viewModel.onSignIn()

        relay.deliver(redirect)
        assertThat(viewModel.state.value.isBusy).isTrue()
        auth.gate?.complete(Unit)

        assertThat(auth.calls).doesNotContain("completeOidc $redirect")
        assertThat(viewModel.state.value.error).isNull()
    }

    @Test
    fun `a redirect arriving after the sign-in was cancelled keeps the cancellation notice`() =
        runTest(mainRule.dispatcher) {
            val viewModel = viewModel()
            viewModel.onOidc(provider)
            viewModel.onBrowserReturned()
            advanceUntilIdle()

            relay.deliver(redirect)

            assertThat(auth.calls).doesNotContain("completeOidc $redirect")
            assertThat(viewModel.state.value.error).isEqualTo(ApiError.OidcCancelled)
        }

    @Test
    fun `a refused redirect shows the error`() = runTest(mainRule.dispatcher) {
        auth.completeOidcResult = ApiResult.Failure(ApiError.OidcRefused)
        val viewModel = viewModel()
        viewModel.onOidc(provider)

        relay.deliver(redirect)

        assertThat(viewModel.state.value.error).isEqualTo(ApiError.OidcRefused)
        assertThat(viewModel.state.value.isBusy).isFalse()
    }

    @Test
    fun `returning from the browser without a redirect cancels the sign-in`() = runTest(mainRule.dispatcher) {
        val viewModel = viewModel()
        viewModel.onOidc(provider)

        viewModel.onBrowserReturned()
        advanceUntilIdle()

        assertThat(auth.calls).contains("cancelOidc")
        assertThat(viewModel.state.value.error).isEqualTo(ApiError.OidcCancelled)
        assertThat(viewModel.state.value.isBusy).isFalse()
    }

    @Test
    fun `a redirect arriving just after the return is not treated as a cancellation`() =
        runTest(mainRule.dispatcher) {
            val viewModel = viewModel()
            viewModel.onOidc(provider)

            viewModel.onBrowserReturned()
            advanceTimeBy(500)
            relay.deliver(redirect)
            advanceUntilIdle()

            assertThat(auth.calls).contains("completeOidc $redirect")
            assertThat(auth.calls).doesNotContain("cancelOidc")
            assertThat(viewModel.state.value.error).isNull()
        }

    @Test
    fun `a browser that cannot be opened cancels the sign-in and says why`() = runTest(mainRule.dispatcher) {
        val viewModel = viewModel()
        viewModel.onOidc(provider)

        viewModel.onBrowserUnavailable()

        assertThat(auth.calls).contains("cancelOidc")
        assertThat(viewModel.state.value.isBusy).isFalse()
        assertThat(viewModel.state.value.error).isEqualTo(ApiError.BrowserUnavailable)
    }

    @Test
    fun `a browser that cannot be opened is not reported as a cancellation later`() = runTest(mainRule.dispatcher) {
        val viewModel = viewModel()
        viewModel.onOidc(provider)
        viewModel.onBrowserUnavailable()

        viewModel.onBrowserReturned()
        advanceUntilIdle()

        assertThat(viewModel.state.value.error).isEqualTo(ApiError.BrowserUnavailable)
    }

    @Test
    fun `returning to the screen with no oidc in progress does nothing`() = runTest(mainRule.dispatcher) {
        val viewModel = viewModel()

        viewModel.onBrowserReturned()
        advanceUntilIdle()

        assertThat(auth.calls).doesNotContain("cancelOidc")
        assertThat(viewModel.state.value.error).isNull()
    }

    @Test
    fun `an auth tab redirect completes the sign-in`() = runTest(mainRule.dispatcher) {
        val viewModel = viewModel()
        viewModel.onOidc(provider)
        viewModel.onBrowserOpened(authTab = true)

        viewModel.onAuthTabResult(AuthTabResult.Redirected(redirect))

        assertThat(auth.calls).contains("completeOidc $redirect")
        assertThat(auth.calls).doesNotContain("cancelOidc")
        assertThat(viewModel.state.value.error).isNull()
        assertThat(viewModel.state.value.isBusy).isTrue()
    }

    @Test
    fun `an auth tab redirect the server refuses shows the error`() = runTest(mainRule.dispatcher) {
        auth.completeOidcResult = ApiResult.Failure(ApiError.OidcRefused)
        val viewModel = viewModel()
        viewModel.onOidc(provider)
        viewModel.onBrowserOpened(authTab = true)

        viewModel.onAuthTabResult(AuthTabResult.Redirected(redirect))

        assertThat(viewModel.state.value.error).isEqualTo(ApiError.OidcRefused)
        assertThat(viewModel.state.value.isBusy).isFalse()
    }

    @Test
    fun `closing the auth tab cancels the sign-in at once`() = runTest(mainRule.dispatcher) {
        val viewModel = viewModel()
        viewModel.onOidc(provider)
        viewModel.onBrowserOpened(authTab = true)

        viewModel.onAuthTabResult(AuthTabResult.Cancelled)

        // No time has passed: there is no grace delay on this path.
        assertThat(auth.calls).contains("cancelOidc")
        assertThat(auth.oidcPending).isFalse()
        assertThat(viewModel.state.value.error).isEqualTo(ApiError.OidcCancelled)
        assertThat(viewModel.state.value.isBusy).isFalse()
    }

    @Test
    fun `an auth tab that fails refuses the sign-in and forgets the request`() = runTest(mainRule.dispatcher) {
        val viewModel = viewModel()
        viewModel.onOidc(provider)
        viewModel.onBrowserOpened(authTab = true)

        viewModel.onAuthTabResult(AuthTabResult.Failed)

        assertThat(auth.calls).contains("cancelOidc")
        assertThat(auth.calls.filter { it.startsWith("completeOidc") }).isEmpty()
        assertThat(auth.oidcPending).isFalse()
        assertThat(viewModel.state.value.error).isEqualTo(ApiError.OidcRefused)
        assertThat(viewModel.state.value.isBusy).isFalse()
    }

    @Test
    fun `coming back to the screen while an auth tab is open cancels nothing`() = runTest(mainRule.dispatcher) {
        val viewModel = viewModel()
        viewModel.onOidc(provider)
        viewModel.onBrowserOpened(authTab = true)

        viewModel.onBrowserReturned()
        advanceUntilIdle()

        assertThat(auth.calls).doesNotContain("cancelOidc")
        assertThat(viewModel.state.value.error).isNull()
        assertThat(viewModel.state.value.isBusy).isTrue()

        // The answer is still welcome afterwards.
        viewModel.onAuthTabResult(AuthTabResult.Redirected(redirect))
        assertThat(auth.calls).contains("completeOidc $redirect")
    }

    @Test
    fun `coming back to the screen after an auth tab redirect cancels nothing`() = runTest(mainRule.dispatcher) {
        val viewModel = viewModel()
        viewModel.onOidc(provider)
        viewModel.onBrowserOpened(authTab = true)
        viewModel.onAuthTabResult(AuthTabResult.Redirected(redirect))

        viewModel.onBrowserReturned()
        advanceUntilIdle()

        assertThat(auth.calls).doesNotContain("cancelOidc")
        assertThat(viewModel.state.value.error).isNull()
    }

    @Test
    fun `coming back to the screen while an auth tab redirect is being used cancels nothing`() =
        runTest(mainRule.dispatcher) {
            val viewModel = viewModel()
            viewModel.onOidc(provider)
            viewModel.onBrowserOpened(authTab = true)
            auth.gate = CompletableDeferred()
            viewModel.onAuthTabResult(AuthTabResult.Redirected(redirect))

            viewModel.onBrowserReturned()
            advanceUntilIdle()
            assertThat(viewModel.state.value.isBusy).isTrue()
            auth.gate?.complete(Unit)

            assertThat(auth.calls).doesNotContain("cancelOidc")
            assertThat(viewModel.state.value.error).isNull()
            assertThat(viewModel.state.value.isBusy).isTrue()
        }

    @Test
    fun `a stale relay redirect after the auth tab was closed is ignored`() = runTest(mainRule.dispatcher) {
        val viewModel = viewModel()
        viewModel.onOidc(provider)
        viewModel.onBrowserOpened(authTab = true)
        viewModel.onAuthTabResult(AuthTabResult.Cancelled)

        relay.deliver(redirect)

        assertThat(auth.calls).doesNotContain("completeOidc $redirect")
        assertThat(viewModel.state.value.error).isEqualTo(ApiError.OidcCancelled)
        assertThat(viewModel.state.value.isBusy).isFalse()
    }

    @Test
    fun `the same redirect coming both ways is used once`() = runTest(mainRule.dispatcher) {
        val viewModel = viewModel()
        viewModel.onOidc(provider)
        viewModel.onBrowserOpened(authTab = true)
        auth.gate = CompletableDeferred()
        viewModel.onAuthTabResult(AuthTabResult.Redirected(redirect))

        // The first one is still being sent to the server when the second one arrives.
        relay.deliver(redirect)
        auth.gate?.complete(Unit)

        assertThat(auth.calls.filter { it.startsWith("completeOidc") }).hasSize(1)
        assertThat(viewModel.state.value.error).isNull()
        assertThat(viewModel.state.value.isBusy).isTrue()
    }

    @Test
    fun `an auth tab closing after the relay redirect was used changes nothing`() = runTest(mainRule.dispatcher) {
        val viewModel = viewModel()
        viewModel.onOidc(provider)
        viewModel.onBrowserOpened(authTab = true)
        relay.deliver(redirect)

        viewModel.onAuthTabResult(AuthTabResult.Cancelled)
        viewModel.onAuthTabResult(AuthTabResult.Failed)

        assertThat(auth.calls).contains("completeOidc $redirect")
        assertThat(auth.calls).doesNotContain("cancelOidc")
        assertThat(viewModel.state.value.error).isNull()
    }

    @Test
    fun `without an auth tab the relay redirect still completes the sign-in`() = runTest(mainRule.dispatcher) {
        val viewModel = viewModel()
        viewModel.onOidc(provider)
        viewModel.onBrowserOpened(authTab = false)

        viewModel.onBrowserReturned()
        advanceTimeBy(500)
        relay.deliver(redirect)
        advanceUntilIdle()

        assertThat(auth.calls).contains("completeOidc $redirect")
        assertThat(auth.calls).doesNotContain("cancelOidc")
        assertThat(viewModel.state.value.error).isNull()
        assertThat(viewModel.state.value.isBusy).isTrue()
    }

    @Test
    fun `without an auth tab coming back with no redirect still cancels after the grace delay`() =
        runTest(mainRule.dispatcher) {
            val viewModel = viewModel()
            viewModel.onOidc(provider)
            viewModel.onBrowserOpened(authTab = false)

            viewModel.onBrowserReturned()
            advanceTimeBy(1_000)
            assertThat(auth.calls).doesNotContain("cancelOidc")
            advanceUntilIdle()

            assertThat(auth.calls).contains("cancelOidc")
            assertThat(viewModel.state.value.error).isEqualTo(ApiError.OidcCancelled)
            assertThat(viewModel.state.value.isBusy).isFalse()
        }

    @Test
    fun `an auth tab redirect is used by a screen recreated while the browser was in front`() =
        runTest(mainRule.dispatcher) {
            // The app was killed while the browser was in front: the request is still on disk.
            auth.oidcPending = true
            val viewModel = viewModel()

            viewModel.onAuthTabResult(AuthTabResult.Redirected(redirect))

            assertThat(auth.calls).contains("completeOidc $redirect")
            assertThat(viewModel.state.value.error).isNull()
        }

    @Test
    fun `an auth tab closed while the app was gone still cancels the request left on disk`() =
        runTest(mainRule.dispatcher) {
            auth.oidcPending = true
            val viewModel = viewModel()

            viewModel.onAuthTabResult(AuthTabResult.Cancelled)

            assertThat(auth.calls).contains("cancelOidc")
            assertThat(auth.oidcPending).isFalse()
            assertThat(viewModel.state.value.error).isEqualTo(ApiError.OidcCancelled)
        }

    @Test
    fun `an auth tab answer nobody asked for is ignored`() = runTest(mainRule.dispatcher) {
        val viewModel = viewModel()

        viewModel.onAuthTabResult(AuthTabResult.Redirected(redirect))
        viewModel.onAuthTabResult(AuthTabResult.Cancelled)
        viewModel.onAuthTabResult(AuthTabResult.Failed)

        assertThat(auth.calls).containsExactly("loginOptions https://books.example.com")
        assertThat(viewModel.state.value.error).isNull()
        assertThat(viewModel.state.value.isBusy).isFalse()
    }

    @Test
    fun `a browser that cannot be opened leaves no auth tab to wait for`() = runTest(mainRule.dispatcher) {
        val viewModel = viewModel()
        viewModel.onOidc(provider)
        viewModel.onBrowserUnavailable()
        viewModel.onBrowserOpened(authTab = true)

        viewModel.onAuthTabResult(AuthTabResult.Cancelled)

        assertThat(viewModel.state.value.error).isEqualTo(ApiError.BrowserUnavailable)
    }

    @Test
    fun `it remembers which kind of tab it opened until the sign-in is settled`() = runTest(mainRule.dispatcher) {
        val viewModel = viewModel()
        // Nothing was opened by this view model: it cannot tell.
        assertThat(viewModel.openedInAuthTab).isNull()

        viewModel.onOidc(provider)
        viewModel.onBrowserOpened(authTab = true)
        assertThat(viewModel.openedInAuthTab).isTrue()

        viewModel.onAuthTabResult(AuthTabResult.Cancelled)
        assertThat(viewModel.openedInAuthTab).isNull()

        viewModel.onOidc(provider)
        viewModel.onBrowserOpened(authTab = false)
        assertThat(viewModel.openedInAuthTab).isFalse()
    }

    @Test
    fun `the printed state never shows the password`() = runTest(mainRule.dispatcher) {
        val viewModel = viewModel()
        viewModel.onUsernameChange("alice")
        viewModel.onPasswordChange("hunter2-secret")

        val printed = viewModel.state.value.toString()

        assertThat(printed).doesNotContain("hunter2-secret")
        assertThat(printed).contains("password=<redacted>")
        // The rest still helps reading a log or a failed assertion.
        assertThat(printed).contains("username=alice")
        assertThat(printed).contains("serverUrl=https://books.example.com")
        assertThat(printed).contains("isBusy=false")
    }

    @Test
    fun `the printed state tells an empty password from a typed one`() {
        assertThat(SignInUiState().toString()).contains("password=<empty>")
    }
}
