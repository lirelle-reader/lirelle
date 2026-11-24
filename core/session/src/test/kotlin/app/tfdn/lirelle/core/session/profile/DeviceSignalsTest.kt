package app.tfdn.lirelle.core.session.profile

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceSignalsTest {

    private val signals = DeviceSignals()

    @Test
    fun `a network that comes back is told to who follows then, not to who comes later`() =
        runTest(UnconfinedTestDispatcher()) {
            var told = 0
            signals.onNetworkAvailable()

            backgroundScope.launch { signals.networkAvailable.collect { told++ } }
            assertThat(told).isEqualTo(0)

            signals.onNetworkAvailable()
            assertThat(told).isEqualTo(1)
        }

    @Test
    fun `who follows is counted for as long as it follows`() = runTest(UnconfinedTestDispatcher()) {
        assertThat(signals.followers).isEqualTo(0)

        val network = launch { signals.networkAvailable.collect { } }
        val front = launch { signals.inFront.collect { } }
        assertThat(signals.followers).isEqualTo(2)

        network.cancel()
        front.cancel()
        runCurrent()
        assertThat(signals.followers).isEqualTo(0)
    }

    @Test
    fun `the app is not in front until it is shown, and no longer once hidden`() {
        assertThat(signals.inFront.value).isFalse()

        signals.onShown()
        assertThat(signals.inFront.value).isTrue()

        signals.onHidden()
        assertThat(signals.inFront.value).isFalse()
    }
}
