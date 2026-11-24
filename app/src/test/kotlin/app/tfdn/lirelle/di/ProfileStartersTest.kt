package app.tfdn.lirelle.di

import app.tfdn.lirelle.AccountRefresher
import app.tfdn.lirelle.KeptBooksChecker
import app.tfdn.lirelle.OutboxKicker
import app.tfdn.lirelle.core.library.net.NetworkState
import app.tfdn.lirelle.core.library.net.ProfileNetwork
import app.tfdn.lirelle.core.session.profile.DeviceSignals
import app.tfdn.lirelle.core.testing.FakeAccountRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** What starts with a profile, wired to the device's signals as the application's graph wires it. */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileStartersTest {

    private val signals = DeviceSignals()
    private val device = MutableStateFlow(NetworkState(online = true, unmetered = true))
    private val signInNeeded = MutableStateFlow(false)
    private val account = FakeAccountRepository()
    private var kicks = 0
    private var checks = 0
    private val refreshes get() = account.calls.count { it == "refreshAccount" }

    /** A profile opens: what the module provides is started in its scope. */
    private fun open(scope: CoroutineScope) {
        val network = ProfileNetwork(device, signals.networkAvailable, signInNeeded, scope)
        ProfileStartersModule.outbox(OutboxKicker({ kicks++ }), network).start(scope)
        ProfileStartersModule.account(AccountRefresher(account), signals, network).start(scope)
        ProfileStartersModule.keptBooks(KeptBooksChecker { checks++ }, signals, network).start(scope)
    }

    @Test
    fun `a profile that opens behind another app sends what waits and refreshes its account, once`() =
        runTest(UnconfinedTestDispatcher()) {
            open(backgroundScope)

            assertThat(kicks).isEqualTo(1)
            assertThat(refreshes).isEqualTo(1)
            assertThat(checks).isEqualTo(0)
        }

    @Test
    fun `the network coming back kicks the outbox, refreshes the account and checks the kept books`() =
        runTest(UnconfinedTestDispatcher()) {
            signals.onShown()
            open(backgroundScope)
            val checkedAtOpening = checks

            signals.onNetworkAvailable()

            assertThat(kicks).isEqualTo(2)
            assertThat(refreshes).isEqualTo(2)
            assertThat(checks).isEqualTo(checkedAtOpening + 1)
        }

    @Test
    fun `while its session waits for a sign-in, the device's network coming back starts nothing`() =
        runTest(UnconfinedTestDispatcher()) {
            signals.onShown()
            open(backgroundScope)
            val checkedAtOpening = checks
            signInNeeded.value = true

            signals.onNetworkAvailable()

            assertThat(kicks).isEqualTo(1)
            assertThat(refreshes).isEqualTo(1)
            assertThat(checks).isEqualTo(checkedAtOpening)
        }

    /** No mechanism of its own: the sign-in is, for what starts with the profile, a network that came back. */
    @Test
    fun `signing in again kicks the outbox, refreshes the account and checks the kept books`() =
        runTest(UnconfinedTestDispatcher()) {
            signals.onShown()
            signInNeeded.value = true
            open(backgroundScope)
            val checkedAtOpening = checks

            signInNeeded.value = false

            assertThat(kicks).isEqualTo(2)
            assertThat(refreshes).isEqualTo(2)
            assertThat(checks).isEqualTo(checkedAtOpening + 1)
        }

    @Test
    fun `coming to the front refreshes the account and checks the kept books`() =
        runTest(UnconfinedTestDispatcher()) {
            open(backgroundScope)

            signals.onShown()

            assertThat(refreshes).isEqualTo(2)
            assertThat(checks).isEqualTo(1)
            // Nothing waits to be sent because the app is looked at.
            assertThat(kicks).isEqualTo(1)
        }

    /** Starting has just refreshed: where the app stands as the profile opens is not a coming to the front. */
    @Test
    fun `a profile that opens in front refreshes once, and checks the kept books at once`() =
        runTest(UnconfinedTestDispatcher()) {
            signals.onShown()

            open(backgroundScope)

            assertThat(refreshes).isEqualTo(1)
            assertThat(checks).isEqualTo(1)
        }

    @Test
    fun `it refreshes again each time it comes back to the front`() = runTest(UnconfinedTestDispatcher()) {
        signals.onShown()
        open(backgroundScope)

        signals.onHidden()
        assertThat(refreshes).isEqualTo(1)
        signals.onShown()

        assertThat(refreshes).isEqualTo(2)
        assertThat(checks).isEqualTo(2)
    }

    @Test
    fun `hidden, a network that comes back checks no kept book, but sends and refreshes`() =
        runTest(UnconfinedTestDispatcher()) {
            signals.onShown()
            open(backgroundScope)
            signals.onHidden()

            signals.onNetworkAvailable()

            assertThat(checks).isEqualTo(1)
            assertThat(kicks).isEqualTo(2)
            assertThat(refreshes).isEqualTo(2)
        }

    @Test
    fun `a profile that closed hears nothing more`() = runTest(UnconfinedTestDispatcher()) {
        val life = Job()
        open(CoroutineScope(life + UnconfinedTestDispatcher(testScheduler)))
        life.cancel()

        signals.onNetworkAvailable()
        signals.onShown()

        assertThat(kicks).isEqualTo(1)
        assertThat(refreshes).isEqualTo(1)
        assertThat(checks).isEqualTo(0)
    }
}
