package app.tfdn.lirelle.core.ui

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.network.ApiError
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StateViewsTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `the error state shows the message for the error and retries`() {
        var retries = 0
        compose.setContent { LirelleTheme { ErrorState(ApiError.ServerUnreachable, onRetry = { retries++ }) } }

        compose.onNodeWithText(context.getString(R.string.library_error_unreachable)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.state_retry)).performClick()

        assertThat(retries).isEqualTo(1)
    }

    @Test
    fun `a failing server has its own message and can be retried`() {
        var retries = 0
        compose.setContent { LirelleTheme { ErrorState(ApiError.ServerFailed, onRetry = { retries++ }) } }

        compose.onNodeWithText(context.getString(R.string.library_error_server_failed)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.state_retry)).performClick()

        assertThat(retries).isEqualTo(1)
        assertThat(ApiError.ServerFailed.canRetry).isTrue()
    }

    @Test
    fun `a missing book has its own message and nothing to retry`() {
        compose.setContent { LirelleTheme { ErrorState(ApiError.NotFound, onRetry = {}) } }

        compose.onNodeWithText(context.getString(R.string.library_error_not_found)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.state_retry)).assertDoesNotExist()
    }

    @Test
    fun `a moved book that could not be followed reads as missing`() {
        compose.setContent { LirelleTheme { ErrorState(ApiError.Moved(BookId("3")), onRetry = {}) } }

        compose.onNodeWithText(context.getString(R.string.library_error_not_found)).assertIsDisplayed()
    }

    @Test
    fun `the empty message can carry an action`() {
        var clicks = 0
        compose.setContent {
            LirelleTheme { EmptyMessage(text = "Nothing", actionLabel = "Clear", onAction = { clicks++ }) }
        }

        compose.onNodeWithText("Nothing").assertIsDisplayed()
        compose.onNodeWithText("Clear").performClick()

        assertThat(clicks).isEqualTo(1)
    }
}
