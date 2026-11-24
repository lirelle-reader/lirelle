package app.tfdn.lirelle.feature.login

import app.cash.turbine.test
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.testing.FakeAuthRepository
import app.tfdn.lirelle.core.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class ServerViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val auth = FakeAuthRepository()

    @Test
    fun `starts with the last server used`() = runTest {
        auth.serverUrl.value = "https://books.example.com"

        assertThat(ServerViewModel(auth).state.value.address).isEqualTo("https://books.example.com")
    }

    @Test
    fun `starts empty when no server was used`() = runTest {
        assertThat(ServerViewModel(auth).state.value.address).isEmpty()
    }

    @Test
    fun `an invalid address shows an error and calls nothing`() = runTest {
        val viewModel = ServerViewModel(auth)
        viewModel.onAddressChange("ftp://books.example.com")

        viewModel.onContinue()

        assertThat(viewModel.state.value.error).isEqualTo(ServerError.InvalidAddress)
        assertThat(auth.calls).isEmpty()
    }

    @Test
    fun `an https address is normalised, checked, then reported as connected`() = runTest {
        val viewModel = ServerViewModel(auth)
        viewModel.onAddressChange(" books.example.com/ ")

        viewModel.connected.test {
            viewModel.onContinue()
            awaitItem()
        }

        assertThat(auth.calls).containsExactly("connect https://books.example.com")
        assertThat(viewModel.state.value.isChecking).isFalse()
    }

    @Test
    fun `the check is in flight until the server answers`() = runTest {
        auth.gate = CompletableDeferred()
        val viewModel = ServerViewModel(auth)
        viewModel.onAddressChange("books.example.com")

        viewModel.onContinue()
        assertThat(viewModel.state.value.isChecking).isTrue()

        auth.gate?.complete(Unit)
        assertThat(viewModel.state.value.isChecking).isFalse()
    }

    @Test
    fun `continuing again while the check is in flight does not check twice`() = runTest {
        auth.gate = CompletableDeferred()
        val viewModel = ServerViewModel(auth)
        viewModel.onAddressChange("books.example.com")
        viewModel.onContinue()

        viewModel.onContinue()
        auth.gate?.complete(Unit)

        assertThat(auth.calls).containsExactly("connect https://books.example.com")
    }

    @Test
    fun `an http address waits for confirmation`() = runTest {
        val viewModel = ServerViewModel(auth)
        viewModel.onAddressChange("http://homeserver:3000")

        viewModel.onContinue()

        assertThat(viewModel.state.value.cleartextAddress).isEqualTo("http://homeserver:3000")
        assertThat(auth.calls).isEmpty()
    }

    @Test
    fun `confirming the warning connects over http`() = runTest {
        val viewModel = ServerViewModel(auth)
        viewModel.onAddressChange("http://homeserver:3000")
        viewModel.onContinue()

        viewModel.connected.test {
            viewModel.onCleartextConfirmed()
            awaitItem()
        }

        assertThat(auth.calls).containsExactly("connect http://homeserver:3000")
        assertThat(viewModel.state.value.cleartextAddress).isNull()
    }

    @Test
    fun `dismissing the warning connects to nothing`() = runTest {
        val viewModel = ServerViewModel(auth)
        viewModel.onAddressChange("http://homeserver:3000")
        viewModel.onContinue()

        viewModel.onCleartextDismissed()

        assertThat(viewModel.state.value.cleartextAddress).isNull()
        assertThat(auth.calls).isEmpty()
    }

    @Test
    fun `a failed check shows the error and does not connect`() = runTest {
        auth.connectResult = ApiResult.Failure(ApiError.UnknownServer)
        val viewModel = ServerViewModel(auth)
        viewModel.onAddressChange("books.example.com")

        viewModel.connected.test {
            viewModel.onContinue()
            expectNoEvents()
        }

        assertThat(viewModel.state.value.error).isEqualTo(ServerError.Api(ApiError.UnknownServer))
        assertThat(viewModel.state.value.isChecking).isFalse()
    }

    @Test
    fun `editing the address clears the error`() = runTest {
        val viewModel = ServerViewModel(auth)
        viewModel.onAddressChange("ftp://x")
        viewModel.onContinue()

        viewModel.onAddressChange("books.example.com")

        assertThat(viewModel.state.value.error).isNull()
    }
}
