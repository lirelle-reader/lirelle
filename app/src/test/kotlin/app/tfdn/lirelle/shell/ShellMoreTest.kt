package app.tfdn.lirelle.shell

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.composable
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.R
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.navigation.AboutDestination
import app.tfdn.lirelle.navigation.SettingsDestination
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The rows of More that open the settings and About: destinations of More's graph, given to the shell. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class ShellMoreTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun show(history: Boolean = true) {
        compose.setContent {
            LirelleTheme {
                Shell(
                    historyOffered = { history },
                    account = {},
                    home = { stubHome(it) },
                    library = { stubLibrary(it) },
                    search = { stubSearch(it) },
                    more = { navController ->
                        composable<SettingsDestination> {
                            Text("settings-slot", Modifier.clickable { navController.popBackStack() })
                        }
                        composable<AboutDestination> { Text("about-slot") }
                    },
                )
            }
        }
        compose.onNodeWithTag("nav-more").performClick()
    }

    private fun row(id: Int) = compose.onNodeWithText(context.getString(id))

    /** Two clicks with no frame in between, as a fast double tap lands on the device. */
    private fun doubleTap(node: SemanticsNodeInteraction) {
        val click = checkNotNull(node.fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        compose.runOnUiThread {
            click()
            click()
        }
        compose.waitForIdle()
    }

    private fun pressBack() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    @Test
    fun `More leads to the settings, inside its tab`() {
        show()

        row(R.string.more_settings).performClick()

        compose.onNodeWithText("settings-slot").assertIsDisplayed()
        compose.onNodeWithTag("nav-more").assertIsDisplayed().assertIsSelected()
        row(R.string.more_account).assertDoesNotExist()
    }

    @Test
    fun `More has no History row when the source keeps no history`() {
        show(history = false)

        row(R.string.more_history).assertDoesNotExist()
        row(R.string.more_settings).assertIsDisplayed()
    }

    @Test
    fun `More leads to About, inside its tab`() {
        show()

        row(R.string.more_about).performClick()

        compose.onNodeWithText("about-slot").assertIsDisplayed()
        compose.onNodeWithTag("nav-more").assertIsDisplayed().assertIsSelected()
    }

    @Test
    fun `tapping the Settings row twice opens the settings once`() {
        show()

        doubleTap(row(R.string.more_settings))
        compose.onNodeWithText("settings-slot").assertIsDisplayed()
        pressBack()

        row(R.string.more_settings).assertIsDisplayed()
    }

    @Test
    fun `tapping the About row twice opens About once`() {
        show()

        doubleTap(row(R.string.more_about))
        compose.onNodeWithText("about-slot").assertIsDisplayed()
        pressBack()

        row(R.string.more_about).assertIsDisplayed()
    }

    @Test
    fun `the settings are kept when the user leaves More and comes back`() {
        show()
        row(R.string.more_settings).performClick()

        compose.onNodeWithTag("nav-home").performClick()
        compose.onNodeWithTag("nav-more").performClick()

        compose.onNodeWithText("settings-slot").assertIsDisplayed()
    }
}
