package app.tfdn.lirelle.navigation

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.feature.login.SignInOverProfile
import app.tfdn.lirelle.feature.login.SignInOverProfileViewModel
import app.tfdn.lirelle.feature.login.SignInPurpose
import app.tfdn.lirelle.signout.FakeAndroidKeystore
import app.tfdn.lirelle.signout.TestDevice
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The system's Back over the sign-in steps shown over a profile, which have a navigation of their own, as the
 * sign-in level has: from the second step too, Back returns to the profile rather than to the first step.
 */
@RunWith(RobolectricTestRunner::class)
class SignInStepsBackTest {

    @get:Rule(order = 0)
    val keystore = FakeAndroidKeystore()

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var left = 0

    private fun show() {
        val registry = TestDevice(ApplicationProvider.getApplicationContext()).data.profileStore()
        val viewModel = SignInOverProfileViewModel(registry, SignInPurpose.Add)
        compose.setContent {
            LirelleTheme {
                SignInOverProfile(SignInPurpose.Add, onLeave = { left++ }, viewModel = viewModel) { _, _ ->
                    // Two steps, the second opened over the first: what the real steps are to the back button.
                    val steps = rememberNavController()
                    NavHost(steps, startDestination = ServerDestination) {
                        composable<ServerDestination> {
                            Text("server-step", Modifier.clickable { steps.navigate(SignInDestination) })
                        }
                        composable<SignInDestination> { Text("sign-in-step") }
                    }
                }
            }
        }
    }

    private fun pressBack() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    @Test
    fun `Back from the first step returns to the profile`() {
        show()

        pressBack()

        assertThat(left).isEqualTo(1)
    }

    @Test
    fun `Back from the second step returns to the profile, not to the first step`() {
        show()
        compose.onNodeWithText("server-step").performClick()
        compose.onNodeWithText("sign-in-step").assertIsDisplayed()

        pressBack()

        assertThat(left).isEqualTo(1)
        compose.onNodeWithText("sign-in-step").assertIsDisplayed()
    }
}
