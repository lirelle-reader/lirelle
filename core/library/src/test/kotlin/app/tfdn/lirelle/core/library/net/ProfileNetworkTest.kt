package app.tfdn.lirelle.core.library.net

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileNetworkTest {

    private val device = MutableStateFlow(NetworkState(online = true, unmetered = true))
    private val deviceReturns = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val signInNeeded = MutableStateFlow(false)

    private fun TestScope.network() = ProfileNetwork(device, deviceReturns, signInNeeded, backgroundScope)

    /** How many times the profile was told that it may reach its server again. */
    private fun TestScope.returnsOf(network: ProfileNetwork): () -> Int {
        var count = 0
        backgroundScope.launch { network.returns.collect { count++ } }
        return { count }
    }

    @Test
    fun `it follows the device while the session is good`() = runTest(UnconfinedTestDispatcher()) {
        val network = network()
        assertThat(network.online.value).isTrue()
        assertThat(network.state.value).isEqualTo(NetworkState(online = true, unmetered = true))

        device.value = NetworkState(online = false, unmetered = false)

        assertThat(network.online.value).isFalse()
        assertThat(network.isOnline).isFalse()
        assertThat(network.state.value).isEqualTo(NetworkState(online = false, unmetered = false))
    }

    @Test
    fun `it still says what the device has while the session waits for a sign-in`() = runTest {
        val network = network()
        signInNeeded.value = true

        assertThat(network.isOnline).isFalse()
        assertThat(network.deviceOnline).isTrue()

        device.value = NetworkState(online = false, unmetered = false)
        assertThat(network.deviceOnline).isFalse()
    }

    @Test
    fun `it says when it is the session, not the device, that keeps it from its server`() = runTest {
        val network = network()
        device.value = NetworkState(online = false, unmetered = false)
        assertThat(network.waitsForSignIn.value).isFalse()

        signInNeeded.value = true
        assertThat(network.waitsForSignIn.value).isTrue()

        signInNeeded.value = false
        assertThat(network.waitsForSignIn.value).isFalse()
    }

    @Test
    fun `a session that waits for a sign-in has no network, whatever the device has`() = runTest(
        UnconfinedTestDispatcher(),
    ) {
        val network = network()

        signInNeeded.value = true

        assertThat(network.online.value).isFalse()
        assertThat(network.isOnline).isFalse()
        // What the device's network costs is still the device's to say.
        assertThat(network.state.value).isEqualTo(NetworkState(online = false, unmetered = true))
        assertThat(network.unmetered).isTrue()
    }

    @Test
    fun `a profile that opens waiting for a sign-in starts without a network`() = runTest {
        signInNeeded.value = true

        // Nothing has run yet: known from the start, not once somebody followed.
        val network = network()

        assertThat(network.online.value).isFalse()
        assertThat(network.state.value.online).isFalse()
    }

    @Test
    fun `signing in again brings the device's network back, and says so once`() = runTest(UnconfinedTestDispatcher()) {
        signInNeeded.value = true
        val network = network()
        val returns = returnsOf(network)

        signInNeeded.value = false

        assertThat(network.online.value).isTrue()
        assertThat(returns()).isEqualTo(1)
    }

    @Test
    fun `signing in again without a network says nothing until the device has one`() = runTest(
        UnconfinedTestDispatcher(),
    ) {
        device.value = NetworkState(online = false, unmetered = false)
        signInNeeded.value = true
        val network = network()
        val returns = returnsOf(network)

        signInNeeded.value = false
        assertThat(network.online.value).isFalse()
        assertThat(returns()).isEqualTo(0)

        device.value = NetworkState(online = true, unmetered = true)
        deviceReturns.emit(Unit)
        assertThat(returns()).isEqualTo(1)
    }

    @Test
    fun `the device's network coming back is told only while the session is good`() = runTest(
        UnconfinedTestDispatcher(),
    ) {
        val network = network()
        val returns = returnsOf(network)

        deviceReturns.emit(Unit)
        assertThat(returns()).isEqualTo(1)

        signInNeeded.value = true
        deviceReturns.emit(Unit)
        assertThat(returns()).isEqualTo(1)
    }

    @Test
    fun `a session that expires says nothing of a network coming back`() = runTest(UnconfinedTestDispatcher()) {
        val network = network()
        val returns = returnsOf(network)

        signInNeeded.value = true

        assertThat(returns()).isEqualTo(0)
    }
}
