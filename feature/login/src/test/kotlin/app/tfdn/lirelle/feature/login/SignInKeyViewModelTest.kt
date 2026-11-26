package app.tfdn.lirelle.feature.login

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.network.OidcProvider
import app.tfdn.lirelle.core.network.OidcRedirectRelay
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.core.testing.FakeAuthRepository
import app.tfdn.lirelle.core.testing.MainDispatcherRule
import app.tfdn.lirelle.core.testing.TestUserAgent
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

private const val Remembered = "https://livres.example.com"
private const val Own = "https://own.example.com"
private const val Pasted = "k4v1t4-p4st3d-k3y"

/**
 * The sign-in with a key the user pasted: sent as typed — the server's kind tidies it — to the server and kind of
 * the step, with the progress, the errors and the end of a password sign-in. The key is held in memory only, and
 * is in nothing that is printed.
 */
class SignInKeyViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val auth = FakeAuthRepository().apply {
        serverUrl.value = Remembered
        serverKind.value = ServerKind.Kavita
    }
    private val relay = OidcRedirectRelay()
    private val provider = OidcProvider("oidc", "Authentik", "", "")

    /** The step of a first sign-in, and of a profile added: the remembered server. */
    private fun viewModel() = SignInViewModel(auth, relay, TestUserAgent, server = null, kind = null)

    private fun keySignIns() = auth.calls.filter { it.startsWith("signInWithKey") }

    @Test
    fun `the state names the kind of the server signed in to`() = runTest(mainRule.dispatcher) {
        assertThat(viewModel().state.value.kind).isEqualTo(ServerKind.Kavita)
        val step = SignInViewModel(auth, relay, TestUserAgent, Own, ServerKind.Komga)

        assertThat(step.state.value.kind).isEqualTo(ServerKind.Komga)
    }

    @Test
    fun `the key is sent as typed to the remembered server, as its kind`() = runTest(mainRule.dispatcher) {
        val viewModel = viewModel()
        viewModel.pastedKey.onChange(" $Pasted ")

        viewModel.pastedKey.signIn()

        assertThat(keySignIns()).containsExactly("signInWithKey $Remembered  $Pasted ")
        // The options, then the key.
        assertThat(auth.kinds).containsExactly(ServerKind.Kavita, ServerKind.Kavita)
    }

    @Test
    fun `a profile signing in again sends the key to its own server, as its own kind`() =
        runTest(mainRule.dispatcher) {
            auth.serverKind.value = ServerKind.BookOrbit
            val viewModel = SignInViewModel(auth, relay, TestUserAgent, server = Own, kind = ServerKind.Kavita)
            viewModel.pastedKey.onChange(Pasted)

            viewModel.pastedKey.signIn()

            assertThat(keySignIns()).containsExactly("signInWithKey $Own $Pasted")
            assertThat(auth.kinds).containsExactly(ServerKind.Kavita, ServerKind.Kavita)
        }

    @Test
    fun `a key can be sent once it is not blank`() = runTest(mainRule.dispatcher) {
        val viewModel = viewModel()
        assertThat(viewModel.state.value.canSubmitKey).isFalse()

        viewModel.pastedKey.onChange("  ")
        assertThat(viewModel.state.value.canSubmitKey).isFalse()
        viewModel.pastedKey.signIn()
        assertThat(keySignIns()).isEmpty()

        viewModel.pastedKey.onChange(Pasted)
        assertThat(viewModel.state.value.canSubmitKey).isTrue()
    }

    @Test
    fun `a key sign-in is busy while it is sent, and stays so once the server has accepted it`() =
        runTest(mainRule.dispatcher) {
            val viewModel = viewModel()
            viewModel.pastedKey.onChange(Pasted)
            auth.gate = CompletableDeferred()

            viewModel.pastedKey.signIn()
            assertThat(viewModel.state.value.isBusy).isTrue()
            assertThat(viewModel.state.value.canSubmitKey).isFalse()
            assertThat(viewModel.state.value.error).isNull()

            auth.gate?.complete(Unit)
            assertThat(viewModel.state.value.isBusy).isTrue()
        }

    @Test
    fun `nothing else is sent while a key sign-in is in flight`() = runTest(mainRule.dispatcher) {
        val viewModel = viewModel()
        viewModel.onUsernameChange("ann")
        viewModel.onPasswordChange("secret")
        viewModel.pastedKey.onChange(Pasted)
        auth.gate = CompletableDeferred()
        viewModel.pastedKey.signIn()

        viewModel.pastedKey.signIn()
        viewModel.onSignIn()
        viewModel.onOidc(provider)
        auth.gate?.complete(Unit)

        assertThat(keySignIns()).hasSize(1)
        assertThat(auth.calls.filter { it.startsWith("signIn ") || it.startsWith("beginOidc") }).isEmpty()
    }

    @Test
    fun `no key is sent while another sign-in is in flight`() = runTest(mainRule.dispatcher) {
        val viewModel = viewModel()
        viewModel.onUsernameChange("ann")
        viewModel.onPasswordChange("secret")
        viewModel.pastedKey.onChange(Pasted)
        auth.gate = CompletableDeferred()
        viewModel.onSignIn()

        viewModel.pastedKey.signIn()
        auth.gate?.complete(Unit)

        assertThat(keySignIns()).isEmpty()
    }

    @Test
    fun `a key the server takes is forgotten, and the sign-in is told`() = runTest(mainRule.dispatcher) {
        val viewModel = viewModel()
        viewModel.pastedKey.onChange(Pasted)

        viewModel.signedIn.test {
            viewModel.pastedKey.signIn()
            awaitItem()
        }

        assertThat(viewModel.state.value.key).isEmpty()
    }

    @Test
    fun `a password the server takes forgets a key typed beside it`() = runTest(mainRule.dispatcher) {
        val viewModel = viewModel()
        viewModel.onUsernameChange("ann")
        viewModel.onPasswordChange("secret")
        viewModel.pastedKey.onChange(Pasted)

        viewModel.onSignIn()

        assertThat(viewModel.state.value.key).isEmpty()
    }

    @Test
    fun `a key the server does not know says so, and is kept to be corrected`() = runTest(mainRule.dispatcher) {
        auth.signInWithKeyResult = ApiResult.Failure(ApiError.KeyRefused)
        val viewModel = viewModel()
        viewModel.pastedKey.onChange(Pasted)

        viewModel.signedIn.test {
            viewModel.pastedKey.signIn()
            expectNoEvents()
        }

        assertThat(viewModel.state.value.error).isEqualTo(ApiError.KeyRefused)
        assertThat(viewModel.state.value.isBusy).isFalse()
        assertThat(viewModel.state.value.key).isEqualTo(Pasted)
    }

    @Test
    fun `a server too old, or out of reach, is told as it is`() = runTest(mainRule.dispatcher) {
        val tooOld = ApiError.ServerTooOld(version = "0.8.6.25", minimum = "0.8.10.12")
        auth.signInWithKeyResult = ApiResult.Failure(tooOld)
        val viewModel = viewModel()
        viewModel.pastedKey.onChange(Pasted)

        viewModel.pastedKey.signIn()
        assertThat(viewModel.state.value.error).isEqualTo(tooOld)

        auth.signInWithKeyResult = ApiResult.Failure(ApiError.ServerUnreachable)
        viewModel.pastedKey.signIn()
        assertThat(viewModel.state.value.error).isEqualTo(ApiError.ServerUnreachable)
        assertThat(viewModel.state.value.isBusy).isFalse()
    }

    @Test
    fun `editing the key clears the error`() = runTest(mainRule.dispatcher) {
        auth.signInWithKeyResult = ApiResult.Failure(ApiError.KeyRefused)
        val viewModel = viewModel()
        viewModel.pastedKey.onChange(Pasted)
        viewModel.pastedKey.signIn()

        viewModel.pastedKey.onChange("another")

        assertThat(viewModel.state.value.error).isNull()
    }

    @Test
    fun `the key is in nothing the state prints`() = runTest(mainRule.dispatcher) {
        val viewModel = viewModel()
        assertThat(viewModel.state.value.toString()).contains("key=<empty>")

        viewModel.pastedKey.onChange(Pasted)

        assertThat(viewModel.state.value.toString()).doesNotContain(Pasted)
        assertThat(viewModel.state.value.toString()).contains("key=<redacted>")
    }

    /** What a view model is given to save survives the process: the step is given nothing of the kind. */
    @Test
    fun `the step has no saved state to keep the key in`() {
        val taken = SignInViewModel::class.java.declaredConstructors.flatMap { it.parameterTypes.toList() }
        val held = SignInViewModel::class.java.declaredFields.map { it.type }

        assertThat(taken + held).doesNotContain(SavedStateHandle::class.java)
    }
}
