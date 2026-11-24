package app.tfdn.lirelle.feature.settings

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.designsystem.LocalWindowWidthClass
import app.tfdn.lirelle.core.designsystem.WindowWidthClass
import app.tfdn.lirelle.feature.settings.appearance.AppTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import app.tfdn.lirelle.core.ui.R as UiR

/**
 * The settings from the expanded width: the list and the chosen page side by side, and what a change of width
 * keeps.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp")
class SettingsHostWideTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var left = 0
    private var widthClass by mutableStateOf(WindowWidthClass.Expanded)

    private fun text(id: Int): String = compose.activity.getString(id)

    @Composable
    private fun Host() {
        LirelleTheme {
            CompositionLocalProvider(LocalWindowWidthClass provides widthClass) {
                SettingsHost(
                    appTheme = AppTheme.Dark,
                    page = { page, onBack -> StubPage(page, onBack) },
                    onBack = { left++ },
                )
            }
        }
    }

    private fun show() = compose.setContent { Host() }

    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private fun assertSideBySide(page: SettingsPage) {
        SettingsPage.entries.forEach { row ->
            val node = compose.onNodeWithTag(settingsRowTag(row)).assertIsDisplayed()
            if (row == page) node.assertIsSelected() else node.assertIsNotSelected()
        }
        compose.onNodeWithTag(pageTag(page)).assertIsDisplayed()
        SettingsPage.entries.filter { it != page }.forEach { compose.onNodeWithTag(pageTag(it)).assertDoesNotExist() }
        // In a pane a page has no way back: the list is beside it.
        compose.onNodeWithTag(PageBackTag).assertDoesNotExist()
    }

    private fun assertAlone(page: SettingsPage) {
        compose.onNodeWithTag(pageTag(page)).assertIsDisplayed()
        compose.onNodeWithTag(PageBackTag).assertIsDisplayed()
        SettingsPage.entries.forEach { compose.onNodeWithTag(settingsRowTag(it)).assertDoesNotExist() }
    }

    @Test
    fun `the list and Appearance show together when nothing was chosen`() {
        show()

        assertSideBySide(SettingsPage.Appearance)
        compose.onNodeWithText(text(R.string.settings_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.settings_appearance_theme_dark), useUnmergedTree = true)
            .assertIsDisplayed()
        compose.onNodeWithText(text(R.string.settings_readers_note)).assertIsDisplayed()
    }

    @Test
    fun `a tap on a row shows its page beside the list and marks the row`() {
        show()

        compose.onNodeWithTag(settingsRowTag(SettingsPage.Comics)).performClick()
        assertSideBySide(SettingsPage.Comics)

        compose.onNodeWithTag(settingsRowTag(SettingsPage.Downloads)).performClick()
        assertSideBySide(SettingsPage.Downloads)

        compose.onNodeWithTag(settingsRowTag(SettingsPage.Appearance)).performClick()
        assertSideBySide(SettingsPage.Appearance)
    }

    @Test
    fun `the list is a pane of 340 dp at the start, the page takes the rest`() {
        show()

        val paneEnd = with(compose.density) { 340.dp.toPx() }
        SettingsPage.entries.forEach { assertThat(bounds(settingsRowTag(it)).right).isAtMost(paneEnd) }
        assertThat(bounds(pageTag(SettingsPage.Appearance)).left).isAtLeast(paneEnd)
    }

    @Test
    fun `Back leaves the settings, whatever page is shown`() {
        show()
        compose.onNodeWithTag(settingsRowTag(SettingsPage.Player)).performClick()

        compose.onNodeWithContentDescription(text(UiR.string.settings_back)).performClick()

        assertThat(left).isEqualTo(1)
        // The system's Back is left to whoever shows the settings.
        compose.waitForIdle()
        val takesSystemBack = compose.runOnUiThread<Boolean> {
            compose.activity.onBackPressedDispatcher.hasEnabledCallbacks()
        }
        assertThat(takesSystemBack).isFalse()
    }

    @Test
    fun `a page chosen on a narrow window is the marked row once the window is wide`() {
        widthClass = WindowWidthClass.Medium
        show()
        compose.onNodeWithTag(settingsRowTag(SettingsPage.Reading)).performClick()
        assertAlone(SettingsPage.Reading)

        widthClass = WindowWidthClass.Expanded

        assertSideBySide(SettingsPage.Reading)
    }

    @Test
    fun `a page chosen on a wide window is shown alone once the window is narrow, and Back returns to the list`() {
        show()
        compose.onNodeWithTag(settingsRowTag(SettingsPage.Comics)).performClick()

        widthClass = WindowWidthClass.Compact

        assertAlone(SettingsPage.Comics)
        compose.onNodeWithTag(PageBackTag).performClick()
        SettingsPage.entries.forEach { compose.onNodeWithTag(settingsRowTag(it)).assertIsDisplayed() }
        assertThat(left).isEqualTo(0)
    }

    @Test
    fun `with nothing chosen, a wide window made narrow shows the list`() {
        show()

        widthClass = WindowWidthClass.Compact

        SettingsPage.entries.forEach { compose.onNodeWithTag(settingsRowTag(it)).assertIsDisplayed() }
        SettingsPage.entries.forEach { compose.onNodeWithTag(pageTag(it)).assertDoesNotExist() }
    }

    @Test
    fun `the medium width keeps the single pane`() {
        widthClass = WindowWidthClass.Medium
        show()

        SettingsPage.entries.forEach { compose.onNodeWithTag(pageTag(it)).assertDoesNotExist() }
    }

    @Test
    fun `the page shown survives the loss of the screen's state`() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { Host() }
        compose.onNodeWithTag(settingsRowTag(SettingsPage.Player)).performClick()

        restoration.emulateSavedInstanceStateRestore()

        assertSideBySide(SettingsPage.Player)
    }
}
