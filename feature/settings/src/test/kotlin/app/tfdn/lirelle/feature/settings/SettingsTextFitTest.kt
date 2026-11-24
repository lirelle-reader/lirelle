package app.tfdn.lirelle.feature.settings

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.designsystem.LocalWindowWidthClass
import app.tfdn.lirelle.core.designsystem.WindowWidthClass
import app.tfdn.lirelle.core.testing.DeviceRule
import app.tfdn.lirelle.core.testing.assertAllTextFits
import app.tfdn.lirelle.feature.settings.appearance.AppTheme
import app.tfdn.lirelle.feature.settings.appearance.AppearancePage
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.annotation.GraphicsMode

/** The settings with the real Appearance page, the other pages being stubs, at a width class. */
@Composable
private fun Settings(appTheme: AppTheme, widthClass: WindowWidthClass) {
    LirelleTheme {
        CompositionLocalProvider(LocalWindowWidthClass provides widthClass) {
            SettingsHost(
                appTheme = appTheme,
                page = { page, onBack ->
                    when (page) {
                        SettingsPage.Appearance -> AppearancePage(appTheme, onChoose = {}, onBack = onBack)
                        else -> StubPage(page, onBack)
                    }
                },
                onBack = {},
            )
        }
    }
}

/**
 * No text of the settings' list overflows, is clipped or breaks mid-word, on a narrow phone, a phone and a
 * tablet, in both languages, at the default and the largest text size.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Real font metrics: the default mode counts one pixel per character.
class SettingsTextFitTest(
    private val language: String,
    private val width: Int,
    private val fontScale: Float,
) {

    @get:Rule(order = 0)
    val device = DeviceRule(qualifiers = "$language-w${width}dp-h800dp-xhdpi", fontScale = fontScale)

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `the list fits on its own`() {
        compose.setContent { Settings(AppTheme.System, WindowWidthClass.Compact) }

        val texts = compose.assertAllTextFits()

        assertThat(texts.filter { it.isEllipsized }).isEmpty()
    }

    @Test
    fun `the list fits with the line of another theme`() {
        compose.setContent { Settings(AppTheme.Light, WindowWidthClass.Compact) }

        compose.assertAllTextFits()
    }

    @Test
    fun `the rows keep their icons wherever the names stay whole beside them`() {
        compose.setContent { Settings(AppTheme.System, WindowWidthClass.Compact) }

        val row = compose.onNodeWithTag(settingsRowTag(SettingsPage.Downloads)).fetchSemanticsNode().boundsInRoot
        val name = compose.assertAllTextFits()
            .single { it.text == context.getString(R.string.settings_downloads) }
            .boundsInRoot
        val nameStart = with(compose.density) { (name.left - row.left).toDp() }

        // "Téléchargements" beside an icon would be cut in two on the narrow phone at the largest size.
        if (language == "fr" && width == 320 && fontScale == 2.0f) {
            assertThat(nameStart.value).isWithin(0.5f).of(16f)
        } else {
            assertThat(nameStart.value).isWithin(0.5f).of(56f)
        }
    }

    companion object {
        @JvmStatic
        @Parameters(name = "{0}, {1} dp, font scale {2}")
        fun conditions(): List<Array<Any>> =
            listOf("en", "fr").flatMap { language ->
                listOf(320, 411, 1280).flatMap { width ->
                    listOf(1.0f, 2.0f).map { fontScale -> arrayOf(language, width, fontScale) }
                }
            }
    }
}

/**
 * No text of the two panes of the wide layout overflows, is clipped or breaks mid-word, at 1280 dp, in both
 * languages, at the default and the largest text size.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Real font metrics: the default mode counts one pixel per character.
class SettingsWideTextFitTest(language: String, fontScale: Float) {

    @get:Rule(order = 0)
    val device = DeviceRule(qualifiers = "$language-w1280dp-h800dp-xhdpi", fontScale = fontScale)

    @get:Rule(order = 1)
    val compose = createComposeRule()

    @Test
    fun `the two panes fit, Appearance beside the list`() {
        compose.setContent { Settings(AppTheme.System, WindowWidthClass.Expanded) }

        val texts = compose.assertAllTextFits()

        assertThat(texts.filter { it.isEllipsized }).isEmpty()
        // Both panes are there: the list's rows and the page's tiles.
        assertThat(texts.size).isAtLeast(SettingsPage.entries.size * 2 + AppTheme.entries.size)
    }

    @Test
    fun `the two panes fit with another row marked`() {
        compose.setContent { Settings(AppTheme.Dark, WindowWidthClass.Expanded) }

        compose.onNodeWithTag(settingsRowTag(SettingsPage.Downloads)).performClick()

        compose.assertAllTextFits()
    }

    companion object {
        @JvmStatic
        @Parameters(name = "{0}, font scale {1}")
        fun conditions(): List<Array<Any>> =
            listOf("en", "fr").flatMap { language ->
                listOf(1.0f, 2.0f).map { fontScale -> arrayOf<Any>(language, fontScale) }
            }
    }
}
