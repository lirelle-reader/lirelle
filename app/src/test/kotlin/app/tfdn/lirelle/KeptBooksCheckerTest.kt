package app.tfdn.lirelle

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class KeptBooksCheckerTest {

    private var checks = 0
    private val checker = KeptBooksChecker { checks++ }

    @Test
    fun `the kept books are checked each time the application comes to the front`() =
        runTest(UnconfinedTestDispatcher()) {
            checker.start(backgroundScope)

            checker.onShown()
            checker.onHidden()
            checker.onShown()

            assertThat(checks).isEqualTo(2)
        }

    @Test
    fun `starting checks nothing, the application is not in front yet`() = runTest(UnconfinedTestDispatcher()) {
        checker.start(backgroundScope)

        assertThat(checks).isEqualTo(0)
    }

    /** The front arrival that found no network checked nothing: the network coming back is its second chance. */
    @Test
    fun `they are checked when the network comes back with the application in front`() =
        runTest(UnconfinedTestDispatcher()) {
            checker.start(backgroundScope)
            checker.onShown()

            checker.onNetworkAvailable()

            assertThat(checks).isEqualTo(2)
        }

    @Test
    fun `a network that comes back while the application is not in front checks nothing`() =
        runTest(UnconfinedTestDispatcher()) {
            checker.start(backgroundScope)
            checker.onNetworkAvailable()
            checker.onShown()
            checker.onHidden()

            checker.onNetworkAvailable()

            assertThat(checks).isEqualTo(1)
        }

    @Test
    fun `nothing is checked once the profile has closed`() = runTest(UnconfinedTestDispatcher()) {
        val profile = CoroutineScope(coroutineContext + Job())
        checker.start(profile)
        profile.cancel()

        checker.onShown()
        checker.onNetworkAvailable()

        assertThat(checks).isEqualTo(0)
    }

    @Test
    fun `coming to the front before start-up checks nothing`() = runTest(UnconfinedTestDispatcher()) {
        checker.onShown()

        assertThat(checks).isEqualTo(0)
    }
}
