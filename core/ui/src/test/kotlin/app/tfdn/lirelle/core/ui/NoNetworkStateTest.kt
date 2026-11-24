package app.tfdn.lirelle.core.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.network.ApiError
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NoNetworkStateTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `it says why and leads to the downloads or tries again`() {
        var retries = 0
        var opened = 0
        compose.setContent {
            LirelleTheme { NoNetworkState(onRetry = { retries++ }, onOpenDownloads = { opened++ }) }
        }

        compose.onNodeWithTag(NoNetworkStateTag).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.no_network_title)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.no_network_message)).assertIsDisplayed()

        compose.onNodeWithTag(NoNetworkDownloadsTag).performClick()
        assertThat(opened).isEqualTo(1)
        assertThat(retries).isEqualTo(0)

        compose.onNodeWithTag(NoNetworkRetryTag).performClick()
        assertThat(retries).isEqualTo(1)
        assertThat(opened).isEqualTo(1)
    }

    @Test
    fun `it tries again once each time the network comes back, and not while it stays`() {
        var retries = 0
        val online = MutableStateFlow(true)
        compose.setContent {
            LirelleTheme { NoNetworkState(onRetry = { retries++ }, onOpenDownloads = {}, online = online) }
        }
        // Shown while the device says it is online (the server alone is out of reach): nothing to wait for.
        compose.waitForIdle()
        assertThat(retries).isEqualTo(0)

        online.value = false
        compose.waitForIdle()
        assertThat(retries).isEqualTo(0)

        online.value = true
        compose.waitForIdle()
        assertThat(retries).isEqualTo(1)

        online.value = true
        compose.waitForIdle()
        assertThat(retries).isEqualTo(1)
    }

    @Test
    fun `a screen left while offline and come back to once online tries again`() {
        var retries = 0
        var shown by mutableStateOf(true)
        val online = MutableStateFlow(false)
        compose.setContent {
            // What a screen under the sign-in screens goes through: its composition goes, its saved state stays.
            val screens = rememberSaveableStateHolder()
            LirelleTheme {
                if (shown) {
                    screens.SaveableStateProvider("screen") {
                        NoNetworkState(onRetry = { retries++ }, onOpenDownloads = {}, online = online)
                    }
                }
            }
        }
        compose.waitForIdle()

        shown = false
        compose.waitForIdle()
        online.value = true
        compose.waitForIdle()
        shown = true
        compose.waitForIdle()

        assertThat(retries).isEqualTo(1)
    }

    @Test
    fun `the retry of the moment is the one called`() {
        var first = 0
        var second = 0
        var useSecond by mutableStateOf(false)
        val online = MutableStateFlow(false)
        compose.setContent {
            val retry: () -> Unit = if (useSecond) ({ second++ }) else ({ first++ })
            LirelleTheme { NoNetworkState(onRetry = retry, onOpenDownloads = {}, online = online) }
        }

        useSecond = true
        compose.waitForIdle()
        online.value = true
        compose.waitForIdle()

        assertThat(first).isEqualTo(0)
        assertThat(second).isEqualTo(1)
    }

    @Test
    fun `without anything to tell whether the device is online, it only waits for a tap`() {
        var retries = 0
        compose.setContent { LirelleTheme { NoNetworkState(onRetry = { retries++ }, onOpenDownloads = {}) } }
        compose.waitForIdle()

        assertThat(retries).isEqualTo(0)
    }

    @Test
    fun `only an unreachable server is a lack of network`() {
        assertThat(ApiError.ServerUnreachable.isNoNetwork).isTrue()
        assertThat(ApiError.ServerFailed.isNoNetwork).isFalse()
        assertThat(ApiError.NotFound.isNoNetwork).isFalse()
        assertThat(ApiError.Unexpected.isNoNetwork).isFalse()
    }
}
