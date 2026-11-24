package app.tfdn.lirelle.core.ui

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
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

/**
 * What takes the place of a screen that failed to load: without a network, the way to the downloads where there is one.
 */
@RunWith(RobolectricTestRunner::class)
class FailedStateTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var retries = 0
    private var opened = 0

    private fun show(error: ApiError, inShell: Boolean, online: MutableStateFlow<Boolean>? = null) {
        compose.setContent {
            LirelleTheme {
                CompositionLocalProvider(
                    LocalOpenDownloads provides if (inShell) ({ opened++ }) else null,
                    LocalDeviceOnline provides online,
                ) { FailedState(error, onRetry = { retries++ }) }
            }
        }
    }

    @Test
    fun `without a network, inside the shell, it leads to the downloads or tries again`() {
        show(ApiError.ServerUnreachable, inShell = true)

        compose.onNodeWithTag(NoNetworkStateTag).assertIsDisplayed()
        compose.onNodeWithTag(NoNetworkDownloadsTag).performClick()
        compose.onNodeWithTag(NoNetworkRetryTag).performClick()

        assertThat(opened).isEqualTo(1)
        assertThat(retries).isEqualTo(1)
    }

    @Test
    fun `without a network, outside the shell, it is the error with its retry`() {
        show(ApiError.ServerUnreachable, inShell = false)

        compose.onAllNodesWithTag(NoNetworkStateTag).assertCountEquals(0)
        compose.onNodeWithText(context.getString(R.string.library_error_unreachable)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.state_retry)).performClick()
        assertThat(retries).isEqualTo(1)
    }

    @Test
    fun `another failure is the error, inside the shell too`() {
        show(ApiError.ServerFailed, inShell = true)

        compose.onAllNodesWithTag(NoNetworkStateTag).assertCountEquals(0)
        compose.onNodeWithText(context.getString(R.string.library_error_server_failed)).assertIsDisplayed()
    }

    @Test
    fun `it tries again by itself when the network is back`() {
        val online = MutableStateFlow(false)
        show(ApiError.ServerUnreachable, inShell = true, online = online)
        compose.waitForIdle()
        assertThat(retries).isEqualTo(0)

        online.value = true
        compose.waitForIdle()

        assertThat(retries).isEqualTo(1)
    }

    @Test
    fun `the error of another failure does not try again by itself`() {
        val online = MutableStateFlow(false)
        show(ApiError.ServerFailed, inShell = true, online = online)

        online.value = true
        compose.waitForIdle()

        assertThat(retries).isEqualTo(0)
    }
}
