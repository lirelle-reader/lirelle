package app.tfdn.lirelle.feature.settings.appearance

import android.content.Context
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.feature.settings.R
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import app.tfdn.lirelle.core.ui.R as UiR

@RunWith(RobolectricTestRunner::class)
class AppearancePageTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val chosen = mutableListOf<AppTheme>()

    private fun text(id: Int): String = context.getString(id)

    private fun show(theme: AppTheme?, onBack: (() -> Unit)? = {}) {
        compose.setContent {
            LirelleTheme { AppearancePage(theme = theme, onChoose = { chosen += it }, onBack = onBack) }
        }
    }

    @Test
    fun `the stored theme is the selected tile, with its sentence`() {
        show(AppTheme.Light)

        compose.onNodeWithTag(themeTileTag(AppTheme.Light)).assertIsSelected()
        compose.onNodeWithTag(themeTileTag(AppTheme.System)).assertIsNotSelected()
        compose.onNodeWithTag(themeTileTag(AppTheme.Dark)).assertIsNotSelected()
        compose.onNodeWithText(text(R.string.appearance_light_meaning)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.appearance_system_meaning)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.appearance_dark_meaning)).assertDoesNotExist()
    }

    @Test
    fun `each theme has its own sentence`() {
        show(AppTheme.Dark)

        compose.onNodeWithTag(themeTileTag(AppTheme.Dark)).assertIsSelected()
        compose.onNodeWithText(text(R.string.appearance_dark_meaning)).assertIsDisplayed()
    }

    @Test
    fun `nothing is marked before the store is read`() {
        show(theme = null)

        AppTheme.entries.forEach { compose.onNodeWithTag(themeTileTag(it)).assertIsNotSelected() }
        compose.onNodeWithText(text(R.string.appearance_system_meaning)).assertDoesNotExist()
    }

    @Test
    fun `a tap on a tile chooses its theme`() {
        show(AppTheme.System)

        compose.onNodeWithTag(themeTileTag(AppTheme.Dark)).performClick()
        compose.onNodeWithTag(themeTileTag(AppTheme.Light)).performClick()

        assertThat(chosen).containsExactly(AppTheme.Dark, AppTheme.Light).inOrder()
    }

    @Test
    fun `the tiles are radio buttons named after their themes`() {
        show(AppTheme.System)

        val radio = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)
        compose.onNodeWithTag(themeTileTag(AppTheme.System)).assert(radio)
        compose.onNodeWithTag(themeTileTag(AppTheme.Light)).assert(radio)
        compose.onNodeWithTag(themeTileTag(AppTheme.Dark)).assert(radio)
        compose.onNodeWithText(text(R.string.appearance_system)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.appearance_light)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.appearance_dark)).assertIsDisplayed()
    }

    @Test
    fun `the page says what the reading theme Auto does`() {
        show(AppTheme.System)

        compose.onNodeWithText(text(R.string.settings_appearance)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.appearance_app_theme)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.appearance_reading_note)).assertIsDisplayed()
    }

    @Test
    fun `on its own the page has an arrow that goes back`() {
        var backs = 0
        show(AppTheme.System, onBack = { backs++ })

        compose.onNodeWithContentDescription(text(UiR.string.settings_back)).performClick()

        assertThat(backs).isEqualTo(1)
    }

    @Test
    fun `in a pane the page has its title and no arrow`() {
        show(AppTheme.System, onBack = null)

        compose.onNodeWithText(text(R.string.settings_appearance)).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(UiR.string.settings_back)).assertDoesNotExist()
    }
}
