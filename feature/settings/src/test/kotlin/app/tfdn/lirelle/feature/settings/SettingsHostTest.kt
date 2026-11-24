package app.tfdn.lirelle.feature.settings

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.feature.settings.appearance.AppTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import app.tfdn.lirelle.core.ui.R as UiR

/** The settings on a narrow window: the list, or the page chosen from it. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class SettingsHostTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var left = 0
    private var appTheme by mutableStateOf<AppTheme?>(AppTheme.System)

    private fun text(id: Int): String = compose.activity.getString(id)

    @Composable
    private fun Host() {
        LirelleTheme {
            SettingsHost(
                appTheme = appTheme,
                page = { page, onBack -> StubPage(page, onBack) },
                onBack = { left++ },
            )
        }
    }

    private fun show() {
        compose.setContent { Host() }
    }

    private fun pressBack() {
        // What a tap changed is drawn first: the page shown is what takes the system's Back.
        compose.waitForIdle()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun takesSystemBack(): Boolean {
        compose.waitForIdle()
        return compose.runOnUiThread<Boolean> { compose.activity.onBackPressedDispatcher.hasEnabledCallbacks() }
    }

    private fun assertListShown() {
        SettingsPage.entries.forEach { compose.onNodeWithTag(settingsRowTag(it)).assertIsDisplayed() }
        SettingsPage.entries.forEach { compose.onNodeWithTag(pageTag(it)).assertDoesNotExist() }
    }

    private fun assertAlone(page: SettingsPage) {
        compose.onNodeWithTag(pageTag(page)).assertIsDisplayed()
        SettingsPage.entries.forEach { compose.onNodeWithTag(settingsRowTag(it)).assertDoesNotExist() }
    }

    @Test
    fun `the list has the five pages, in order, and the note about the readers`() {
        show()

        assertListShown()
        val tops = SettingsPage.entries.map {
            compose.onNodeWithTag(settingsRowTag(it)).fetchSemanticsNode().boundsInRoot.top
        }
        assertThat(tops).isInStrictOrder()
        assertThat(SettingsPage.entries.map { it.name })
            .containsExactly("Appearance", "Reading", "Comics", "Player", "Downloads").inOrder()
        compose.onNodeWithText(text(R.string.settings_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.settings_readers_note)).assertIsDisplayed()
    }

    @Test
    fun `each row says what its page holds`() {
        show()

        listOf(
            R.string.settings_appearance to R.string.settings_appearance_theme_system,
            R.string.settings_reading to R.string.settings_reading_about,
            R.string.settings_comics to R.string.settings_comics_about,
            R.string.settings_player to R.string.settings_player_about,
            R.string.settings_downloads to R.string.settings_downloads_about,
        ).forEach { (name, about) ->
            compose.onNodeWithText(text(name), useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithText(text(about), useUnmergedTree = true).assertIsDisplayed()
        }
    }

    @Test
    fun `the line of Appearance follows the app's theme`() {
        show()
        compose.onNodeWithText(text(R.string.settings_appearance_theme_system), useUnmergedTree = true)
            .assertIsDisplayed()

        appTheme = AppTheme.Light
        compose.onNodeWithText(text(R.string.settings_appearance_theme_light), useUnmergedTree = true)
            .assertIsDisplayed()

        appTheme = AppTheme.Dark
        compose.onNodeWithText(text(R.string.settings_appearance_theme_dark), useUnmergedTree = true)
            .assertIsDisplayed()

        // Not read yet: no theme is claimed.
        appTheme = null
        compose.onNodeWithText(text(R.string.settings_appearance_theme_dark), useUnmergedTree = true)
            .assertDoesNotExist()
        compose.onNodeWithText(text(R.string.settings_appearance_theme_system), useUnmergedTree = true)
            .assertDoesNotExist()
    }

    @Test
    fun `a tap on a row shows its page alone`() {
        show()

        SettingsPage.entries.forEach { page ->
            compose.onNodeWithTag(settingsRowTag(page)).performClick()

            assertAlone(page)

            compose.onNodeWithTag(PageBackTag).performClick()
        }
    }

    @Test
    fun `the page's back returns to the list`() {
        show()
        compose.onNodeWithTag(settingsRowTag(SettingsPage.Reading)).performClick()

        compose.onNodeWithTag(PageBackTag).performClick()

        assertListShown()
        assertThat(left).isEqualTo(0)
    }

    @Test
    fun `the system's Back returns to the list`() {
        show()
        compose.onNodeWithTag(settingsRowTag(SettingsPage.Comics)).performClick()

        pressBack()

        assertListShown()
        assertThat(left).isEqualTo(0)
    }

    @Test
    fun `Back on the list leaves the settings`() {
        show()

        compose.onNodeWithContentDescription(text(UiR.string.settings_back)).performClick()

        assertThat(left).isEqualTo(1)
        // The system's Back is left to whoever shows the settings.
        assertThat(takesSystemBack()).isFalse()
    }

    @Test
    fun `the system's Back is the list's again once a page was left`() {
        show()
        compose.onNodeWithTag(settingsRowTag(SettingsPage.Player)).performClick()
        assertThat(takesSystemBack()).isTrue()

        pressBack()

        assertThat(takesSystemBack()).isFalse()
    }

    @Test
    fun `the page shown survives the loss of the screen's state`() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { Host() }
        compose.onNodeWithTag(settingsRowTag(SettingsPage.Downloads)).performClick()

        restoration.emulateSavedInstanceStateRestore()

        assertAlone(SettingsPage.Downloads)
        pressBack()
        assertListShown()
    }
}
