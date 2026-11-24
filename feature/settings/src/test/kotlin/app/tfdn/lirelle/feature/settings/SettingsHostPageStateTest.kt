package app.tfdn.lirelle.feature.settings

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.designsystem.LocalWindowWidthClass
import app.tfdn.lirelle.core.designsystem.WindowWidthClass
import app.tfdn.lirelle.feature.settings.appearance.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val CounterTag = "counter"

/**
 * What a page holds of its own (an open dialog, an unfolded choice, how far it was scrolled) through a change
 * of width: the page is the same one, alone or in the pane.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp")
class SettingsHostPageStateTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var widthClass by mutableStateOf(WindowWidthClass.Expanded)

    /** Stands for a page that holds a state of its own: it counts the taps on it, and tells where it is drawn. */
    @Composable
    private fun CountingPage(page: SettingsPage, onBack: (() -> Unit)?) {
        var taps by rememberSaveable { mutableIntStateOf(0) }
        val place = if (onBack == null) "pane" else "alone"
        Text("${page.name} $taps $place", Modifier.testTag(CounterTag).clickable { taps++ })
    }

    private fun show() = compose.setContent {
        LirelleTheme {
            CompositionLocalProvider(LocalWindowWidthClass provides widthClass) {
                SettingsHost(
                    appTheme = AppTheme.Dark,
                    page = { page, onBack -> CountingPage(page, onBack) },
                    onBack = {},
                )
            }
        }
    }

    private fun tapPage(times: Int) = repeat(times) { compose.onNodeWithTag(CounterTag).performClick() }

    private fun assertPageShows(text: String) =
        compose.onNodeWithTag(CounterTag).assertIsDisplayed().assertTextEquals(text)

    @Test
    fun `a page in the pane keeps its state when the window turns narrow, and wide again`() {
        show()
        compose.onNodeWithTag(settingsRowTag(SettingsPage.Downloads)).performClick()
        tapPage(times = 2)
        assertPageShows("Downloads 2 pane")

        widthClass = WindowWidthClass.Compact
        assertPageShows("Downloads 2 alone")
        tapPage(times = 1)

        widthClass = WindowWidthClass.Expanded
        assertPageShows("Downloads 3 pane")
    }

    @Test
    fun `a page shown alone keeps its state when the window turns wide, and narrow again`() {
        widthClass = WindowWidthClass.Medium
        show()
        compose.onNodeWithTag(settingsRowTag(SettingsPage.Reading)).performClick()
        tapPage(times = 3)
        assertPageShows("Reading 3 alone")

        widthClass = WindowWidthClass.Expanded
        assertPageShows("Reading 3 pane")

        widthClass = WindowWidthClass.Medium
        assertPageShows("Reading 3 alone")
    }

    @Test
    fun `another page starts with a state of its own`() {
        show()
        compose.onNodeWithTag(settingsRowTag(SettingsPage.Downloads)).performClick()
        tapPage(times = 2)

        compose.onNodeWithTag(settingsRowTag(SettingsPage.Comics)).performClick()

        assertPageShows("Comics 0 pane")
    }
}
