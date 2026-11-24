package app.tfdn.lirelle

import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.testing.FakeAccountRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountRefresherTest {

    private val account = FakeAccountRepository()
    private val refresher = AccountRefresher(account)

    @Test
    fun `the account is refreshed at start-up`() = runTest(UnconfinedTestDispatcher()) {
        refresher.start(backgroundScope)

        assertThat(account.calls).containsExactly("refreshAccount")
    }

    @Test
    fun `it is refreshed again each time the network comes back`() = runTest(UnconfinedTestDispatcher()) {
        refresher.start(backgroundScope)

        refresher.onNetworkAvailable()
        refresher.onNetworkAvailable()

        assertThat(account.calls).containsExactly("refreshAccount", "refreshAccount", "refreshAccount")
    }

    /** A permission given back on the server is seen without restarting the application. */
    @Test
    fun `it is refreshed again each time the application comes to the front`() = runTest(UnconfinedTestDispatcher()) {
        refresher.start(backgroundScope)

        refresher.onShown()

        assertThat(account.calls.count { it == "refreshAccount" }).isEqualTo(2)
    }

    @Test
    fun `a refresh that fails does not prevent the next one`() = runTest(UnconfinedTestDispatcher()) {
        account.refreshAccountResult = ApiResult.Failure(ApiError.ServerUnreachable)
        refresher.start(backgroundScope)

        refresher.onNetworkAvailable()

        assertThat(account.calls).hasSize(2)
    }

    @Test
    fun `a network that comes back before start-up asks nothing`() = runTest(UnconfinedTestDispatcher()) {
        refresher.onNetworkAvailable()

        assertThat(account.calls).isEmpty()
    }
}
