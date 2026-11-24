package app.tfdn.lirelle

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.net.NetworkState
import app.tfdn.lirelle.core.library.net.ProfileNetwork
import app.tfdn.lirelle.core.library.offline.DownloadMark
import app.tfdn.lirelle.core.library.offline.DownloadMarks
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.core.session.profile.ProfileSignIn
import app.tfdn.lirelle.core.testing.MainDispatcherRule
import app.tfdn.lirelle.core.ui.SignInNeeded
import coil3.ImageLoader
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SignedInViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val registry = MutableStateFlow(mapOf(BookId("1") to DownloadMark.Kept))
    private val downloadMarks = object : DownloadMarks {
        override val marks: Flow<Map<BookId, DownloadMark>> = registry
    }

    private val signInNeeded = MutableStateFlow(false)
    private val profile = TestScope(UnconfinedTestDispatcher())
    private val device = MutableStateFlow(NetworkState(online = true, unmetered = true))
    private val network = ProfileNetwork(
        device = device,
        deviceReturns = emptyFlow(),
        signInNeeded = signInNeeded,
        life = profile.backgroundScope,
    )

    private val signIn = ProfileSignIn("p7", ServerKind.BookOrbit, signInNeeded) { "camille" }

    private fun viewModel() = SignedInViewModel(
        downloadMarks,
        ImageLoader(ApplicationProvider.getApplicationContext<Context>()),
        network,
        signIn,
    )

    /** What the screens without their server say, and whom "Sign in again" is for. */
    @Test
    fun `the profile is named to its screens while it waits for a sign-in, and only then`() {
        val viewModel = viewModel()
        assertThat(viewModel.signInNeeded.value).isNull()

        signInNeeded.value = true
        assertThat(viewModel.signInNeeded.value).isEqualTo(SignInNeeded("p7", "camille", ServerKind.BookOrbit))

        signInNeeded.value = false
        assertThat(viewModel.signInNeeded.value).isNull()
    }

    @Test
    fun `a profile that already waits for a sign-in is named from the start`() {
        signInNeeded.value = true

        assertThat(viewModel().signInNeeded.value).isEqualTo(SignInNeeded("p7", "camille", ServerKind.BookOrbit))
    }

    /** What the screens are told: the profile's network, not the device's. */
    @Test
    fun `the signed-in app has no network while its profile's session waits for a sign-in`() {
        val viewModel = viewModel()
        assertThat(viewModel.online.value).isTrue()

        signInNeeded.value = true
        assertThat(viewModel.online.value).isFalse()

        signInNeeded.value = false
        assertThat(viewModel.online.value).isTrue()
    }

    /** Where the signed-in app starts: on what it keeps only when the device has no network. */
    @Test
    fun `a device without a network is offline, as before`() {
        device.value = NetworkState(online = false, unmetered = false)

        assertThat(viewModel().deviceOffline).isTrue()
    }

    @Test
    fun `a profile that waits for a sign-in on a device that has a network is not offline`() {
        signInNeeded.value = true
        val viewModel = viewModel()

        assertThat(viewModel.online.value).isFalse()
        assertThat(viewModel.deviceOffline).isFalse()
    }

    @Test
    fun `a profile that waits for a sign-in on a device without a network is offline`() {
        signInNeeded.value = true
        device.value = NetworkState(online = false, unmetered = false)

        assertThat(viewModel().deviceOffline).isTrue()
    }

    @Test
    fun `no book is marked before the marks are followed`() {
        assertThat(viewModel().marks.value).isEmpty()
    }

    @Test
    fun `the marks follow the books kept on the device`() = runTest {
        viewModel().marks.test {
            assertThat(awaitItem()).containsExactly(BookId("1"), DownloadMark.Kept)

            registry.value = mapOf(BookId("1") to DownloadMark.Update, BookId("2") to DownloadMark.InProgress)

            assertThat(awaitItem())
                .containsExactly(BookId("1"), DownloadMark.Update, BookId("2"), DownloadMark.InProgress)
        }
    }
}
