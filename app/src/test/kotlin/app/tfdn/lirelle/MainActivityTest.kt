package app.tfdn.lirelle

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import app.tfdn.lirelle.feature.login.R as LoginR
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The real activity with the real Hilt graph, on a device that has never signed in. */
@RunWith(RobolectricTestRunner::class)
class MainActivityTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun `a first start shows the server screen with its Hilt view model`() {
        val title = compose.activity.getString(LoginR.string.server_title)

        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithText(title).assertIsDisplayed()
    }
}
