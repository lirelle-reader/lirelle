package app.tfdn.lirelle.feature.settings.about

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.designsystem.LocalWindowWidthClass
import app.tfdn.lirelle.core.designsystem.WindowWidthClass
import app.tfdn.lirelle.feature.settings.R
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import app.tfdn.lirelle.core.ui.R as UiR

private const val Version = "0.1.42"
private const val GplExcerpt = "GNU GENERAL PUBLIC LICENSE\nVersion 3, 29 June 2007"
private const val Engine = "bundled:engine"
private const val EngineText = "MIT License\n\nCopyright (c) 2022 Ann Engineer"

/**
 * About from the expanded width: About at the start, the licences or a licence's text beside it, and what a
 * change of width keeps.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp")
class AboutHostWideTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var left = 0
    private var widthClass by mutableStateOf(WindowWidthClass.Expanded)
    private val paneEnd: Float get() = with(compose.density) { 340.dp.toPx() }

    private fun text(id: Int): String = compose.activity.getString(id)

    @Composable
    private fun Host() {
        LirelleTheme {
            CompositionLocalProvider(LocalWindowWidthClass provides widthClass) {
                AboutContent(
                    appVersion = Version,
                    licences = licencesOf(licencesFixture()).copy(app = appComponent(licenceText = GplExcerpt)),
                    onBack = { left++ },
                )
            }
        }
    }

    private fun show() = compose.setContent { Host() }

    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private fun takesSystemBack(): Boolean {
        compose.waitForIdle()
        return compose.runOnUiThread<Boolean> { compose.activity.onBackPressedDispatcher.hasEnabledCallbacks() }
    }

    /** The arrow of the pane beside About: the one that returns from a text to the list. */
    private fun tapPaneBackArrow() {
        val arrows = compose.onAllNodesWithContentDescription(text(UiR.string.settings_back))
        val index = arrows.fetchSemanticsNodes().indexOfFirst { it.boundsInRoot.left >= paneEnd }
        arrows[index].performClick()
    }

    private fun assertAboutAtTheStart() {
        compose.onNodeWithText(text(R.string.about_title)).assertIsDisplayed()
        val version = compose.onNodeWithText(Version).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertThat(version.right).isAtMost(paneEnd)
        // The licences are beside: no row leads to them.
        compose.onNodeWithTag(AboutLicencesRowTag).assertDoesNotExist()
    }

    private fun assertListBeside() {
        assertAboutAtTheStart()
        compose.onNodeWithText(text(R.string.licences_hint)).assertIsDisplayed()
        compose.onNodeWithTag(licenceRowTag(Engine)).assertIsDisplayed()
        assertThat(bounds(licenceRowTag(Engine)).left).isAtLeast(paneEnd)
    }

    private fun assertTextBeside(licenceText: String) {
        assertAboutAtTheStart()
        val shown = compose.onNodeWithText(licenceText).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertThat(shown.left).isAtLeast(paneEnd)
        compose.onNodeWithText(text(R.string.licences_hint)).assertDoesNotExist()
    }

    private fun assertTextAlone(licenceText: String) {
        compose.onNodeWithText(licenceText).assertIsDisplayed()
        compose.onNodeWithText(Version).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.licences_hint)).assertDoesNotExist()
    }

    @Test
    fun `About and the licences show together`() {
        show()

        assertListBeside()
        compose.onNodeWithText(text(R.string.about_app_name)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.about_licences)).assertIsDisplayed()
        // One arrow only, About's: the list has nothing to go back to.
        compose.onNodeWithContentDescription(text(UiR.string.settings_back)).assertIsDisplayed()
        assertThat(takesSystemBack()).isFalse()
    }

    @Test
    fun `the rows are on two columns, the groups' titles on the whole width`() {
        show()

        val engine = bounds(licenceRowTag(Engine))
        val font = bounds(licenceRowTag("bundled:reading-font"))
        assertThat(font.top).isEqualTo(engine.top)
        assertThat(font.left).isAtLeast(engine.right)
        // The libraries start a line of their own, under their title.
        val title = compose.onNodeWithText(text(R.string.licences_libraries)).fetchSemanticsNode().boundsInRoot
        val alpha = bounds(licenceRowTag("org.example:alpha"))
        assertThat(title.top).isAtLeast(engine.bottom)
        assertThat(alpha.top).isAtLeast(title.bottom)
        assertThat(alpha.left).isEqualTo(engine.left)
    }

    @Test
    fun `a tap on a row shows the text in the pane, with an arrow back to the list`() {
        show()

        compose.onNodeWithTag(licenceRowTag(Engine)).performClick()
        assertTextBeside(EngineText)

        tapPaneBackArrow()

        assertListBeside()
        assertThat(left).isEqualTo(0)
    }

    @Test
    fun `the system's Back returns from the text to the list, and is left alone there`() {
        show()
        compose.onNodeWithTag(licenceRowTag(Engine)).performClick()
        assertThat(takesSystemBack()).isTrue()

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

        assertListBeside()
        assertThat(takesSystemBack()).isFalse()
    }

    @Test
    fun `About's arrow leaves, whatever the pane shows`() {
        show()
        compose.onNodeWithTag(licenceRowTag(Engine)).performClick()

        val arrows = compose.onAllNodesWithContentDescription(text(UiR.string.settings_back))
        val index = arrows.fetchSemanticsNodes().indexOfFirst { it.boundsInRoot.right <= paneEnd }
        arrows[index].performClick()

        assertThat(left).isEqualTo(1)
    }

    @Test
    fun `a text read on a narrow window is the one in the pane once the window is wide`() {
        widthClass = WindowWidthClass.Medium
        show()
        compose.onNodeWithTag(AboutLicencesRowTag).performClick()
        compose.onNodeWithTag(licenceRowTag(Engine)).performClick()
        assertTextAlone(EngineText)

        widthClass = WindowWidthClass.Expanded

        assertTextBeside(EngineText)
    }

    @Test
    fun `a text chosen on a wide window is read alone once the window is narrow, and Back steps to the list`() {
        show()
        compose.onNodeWithTag(licenceRowTag(Engine)).performClick()

        widthClass = WindowWidthClass.Compact

        assertTextAlone(EngineText)
        compose.onNodeWithContentDescription(text(UiR.string.settings_back)).performClick()
        compose.onNodeWithText(text(R.string.licences_hint)).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(UiR.string.settings_back)).performClick()
        compose.onNodeWithTag(AboutLicencesRowTag).assertIsDisplayed()
        assertThat(left).isEqualTo(0)
    }

    @Test
    fun `with nothing opened, a wide window made narrow shows About alone`() {
        show()

        widthClass = WindowWidthClass.Compact

        compose.onNodeWithTag(AboutLicencesRowTag).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.licences_hint)).assertDoesNotExist()
    }

    @Test
    fun `the medium width keeps the single screens`() {
        widthClass = WindowWidthClass.Medium
        show()

        compose.onNodeWithTag(AboutLicencesRowTag).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.licences_hint)).assertDoesNotExist()
    }

    @Test
    fun `the text shown survives the loss of the screen's state`() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { Host() }
        compose.onNodeWithTag(licenceRowTag(Engine)).performClick()

        restoration.emulateSavedInstanceStateRestore()

        assertTextBeside(EngineText)
    }

    @Test
    fun `About in its pane has the copyright and the Licence row`() {
        show()

        compose.onNodeWithText(text(R.string.about_copyright)).assertIsDisplayed()
        compose.onNodeWithTag(AboutAppLicenceRowTag).assertIsDisplayed()
        val row = bounds(AboutAppLicenceRowTag)
        assertThat(row.right).isAtMost(paneEnd)
    }

    @Test
    fun `the Licence row shows the app's licence in the pane, and the arrow returns to the list`() {
        show()

        compose.onNodeWithTag(AboutAppLicenceRowTag).performClick()
        assertTextBeside(GplExcerpt)
        compose.onNodeWithText(text(R.string.app_licence_free)).assertIsDisplayed()

        tapPaneBackArrow()

        assertListBeside()
        assertThat(left).isEqualTo(0)
    }

    @Test
    fun `the system's Back returns from the app's licence to the list`() {
        show()
        compose.onNodeWithTag(AboutAppLicenceRowTag).performClick()

        compose.waitForIdle()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

        assertListBeside()
        assertThat(left).isEqualTo(0)
    }

    @Test
    fun `the app's licence in the pane shows alone when the window turns narrow, and back returns to About`() {
        show()
        compose.onNodeWithTag(AboutAppLicenceRowTag).performClick()
        assertTextBeside(GplExcerpt)

        widthClass = WindowWidthClass.Compact

        assertTextAlone(GplExcerpt)
        compose.waitForIdle()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag(AboutAppLicenceRowTag).assertIsDisplayed()
        compose.onNodeWithText(GplExcerpt).assertDoesNotExist()
        assertThat(left).isEqualTo(0)
    }

    @Test
    fun `the app's licence read alone shows in the pane when the window turns wide, and back returns to the list`() {
        widthClass = WindowWidthClass.Medium
        show()
        compose.onNodeWithTag(AboutAppLicenceRowTag).performClick()
        assertTextAlone(GplExcerpt)

        widthClass = WindowWidthClass.Expanded

        assertTextBeside(GplExcerpt)
        compose.waitForIdle()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        assertListBeside()
        assertThat(left).isEqualTo(0)
    }
}
