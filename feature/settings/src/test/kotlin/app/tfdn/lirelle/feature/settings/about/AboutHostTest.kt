package app.tfdn.lirelle.feature.settings.about

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.tfdn.lirelle.core.designsystem.LirelleTheme
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

/** About on a narrow window: About, then the list of licences, then the text of one. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class AboutHostTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var left = 0
    private var licences by mutableStateOf<Licences?>(
        licencesOf(licencesFixture()).copy(app = appComponent(licenceText = GplExcerpt)),
    )

    private fun text(id: Int): String = compose.activity.getString(id)

    @Composable
    private fun Host() {
        LirelleTheme { AboutContent(appVersion = Version, licences = licences, onBack = { left++ }) }
    }

    private fun show() = compose.setContent { Host() }

    private fun pressBack() {
        // What a tap changed is drawn first: the screen shown is what takes the system's Back.
        compose.waitForIdle()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun takesSystemBack(): Boolean {
        compose.waitForIdle()
        return compose.runOnUiThread<Boolean> { compose.activity.onBackPressedDispatcher.hasEnabledCallbacks() }
    }

    private fun tapBackArrow() =
        compose.onNodeWithContentDescription(text(UiR.string.settings_back)).performClick()

    private fun openList() = compose.onNodeWithTag(AboutLicencesRowTag).performClick()

    private fun openText(id: String) {
        openList()
        compose.onNodeWithTag(licenceRowTag(id)).performClick()
    }

    private fun rowTexts(id: String): List<String> =
        compose.onNodeWithTag(licenceRowTag(id)).fetchSemanticsNode().config[SemanticsProperties.Text].map { it.text }

    private fun top(tag: String): Float = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top

    private fun assertAboutShown() {
        compose.onNodeWithText(text(R.string.about_title)).assertIsDisplayed()
        compose.onNodeWithTag(AboutLicencesRowTag).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.licences_hint)).assertDoesNotExist()
    }

    private fun assertListShown() {
        compose.onNodeWithText(text(R.string.licences_hint)).assertIsDisplayed()
        compose.onNodeWithTag(licenceRowTag("org.example:zebra")).assertIsDisplayed()
        compose.onNodeWithTag(AboutLicencesRowTag).assertDoesNotExist()
    }

    private fun assertTextShown(name: String, licenceText: String) {
        compose.onNodeWithText(name).assertIsDisplayed()
        compose.onNodeWithText(licenceText).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.licences_hint)).assertDoesNotExist()
        compose.onNodeWithTag(AboutLicencesRowTag).assertDoesNotExist()
    }

    @Test
    fun `About tells the app's name, what it is and its version, and leads to the licences`() {
        show()

        assertAboutShown()
        compose.onNodeWithText(text(R.string.about_app_name)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.about_what)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.about_version)).assertIsDisplayed()
        compose.onNodeWithText(Version).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.about_licences), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.about_licences_about), useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `the list has the hint, then what is bundled, then the libraries, each by name`() {
        show()

        openList()

        assertListShown()
        compose.onNodeWithText(text(R.string.about_licences)).assertIsDisplayed()
        val tops = listOf(
            compose.onNodeWithText(text(R.string.licences_hint)).fetchSemanticsNode().boundsInRoot.top,
            compose.onNodeWithText(text(R.string.licences_bundled)).fetchSemanticsNode().boundsInRoot.top,
            top(licenceRowTag("bundled:engine")),
            top(licenceRowTag("bundled:reading-font")),
            compose.onNodeWithText(text(R.string.licences_libraries)).fetchSemanticsNode().boundsInRoot.top,
            top(licenceRowTag("org.example:alpha")),
            top(licenceRowTag("org.example:orphan")),
            top(licenceRowTag("org.example:zebra")),
        )
        assertThat(tops).isInStrictOrder()
    }

    @Test
    fun `a row shows the name, who made it, and the licence's short name`() {
        show()

        openList()

        assertThat(rowTexts("org.example:zebra")).containsExactly("zebra", "Zoe Zebra", "Apache-2.0").inOrder()
        assertThat(rowTexts("bundled:engine")).containsExactly("engine", "Ann Engineer", "MIT").inOrder()
    }

    @Test
    fun `a component nobody is named for shows its name alone, and several licences are counted`() {
        show()

        openList()

        assertThat(rowTexts("org.example:orphan")).containsExactly("Mango", "BSD-3-Clause").inOrder()
        assertThat(rowTexts("org.example:alpha"))
            .containsExactly("Alpha", "The Alpha Organisation", "Apache-2.0 +1").inOrder()
    }

    @Test
    fun `a tap on a row shows the licence's text under the component's name`() {
        show()

        openText("bundled:engine")

        assertTextShown("engine", "MIT License\n\nCopyright (c) 2022 Ann Engineer")
        compose.onNodeWithText("MIT License").assertIsDisplayed()
    }

    @Test
    fun `a component with two licences shows both, one after the other`() {
        show()

        openText("org.example:alpha")

        val tops = listOf("Apache License 2.0", "Apache License\nVersion 2.0, January 2004", "Example Terms")
            .map { compose.onNodeWithText(it).assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top }
        assertThat(tops).isInStrictOrder()
        compose.onNodeWithText("The terms of the example.").assertIsDisplayed()
    }

    @Test
    fun `a licence without a text shows its name alone`() {
        show()

        openText("org.example:orphan")

        compose.onNodeWithText("Mango").assertIsDisplayed()
        compose.onNodeWithText("BSD 3-Clause \"New\" or \"Revised\" License").assertIsDisplayed()
    }

    @Test
    fun `the arrows step back from the text to the list, then to About, then leave`() {
        show()
        openText("bundled:engine")

        tapBackArrow()
        assertListShown()

        tapBackArrow()
        assertAboutShown()
        assertThat(left).isEqualTo(0)

        tapBackArrow()
        assertThat(left).isEqualTo(1)
    }

    @Test
    fun `the system's Back steps back the same way, and is left alone on About`() {
        show()
        assertThat(takesSystemBack()).isFalse()
        openText("bundled:engine")

        pressBack()
        assertListShown()

        pressBack()
        assertAboutShown()
        assertThat(left).isEqualTo(0)
        // The system's Back is left to whoever shows About.
        assertThat(takesSystemBack()).isFalse()
    }

    @Test
    fun `the list has no row while it is read, and its rows once it is`() {
        licences = null
        show()

        openList()

        compose.onNodeWithText(text(R.string.about_licences)).assertIsDisplayed()
        compose.onNodeWithTag(licenceRowTag("org.example:zebra")).assertDoesNotExist()

        licences = licencesOf(licencesFixture())

        assertListShown()
    }

    @Test
    fun `what is shown survives the loss of the screen's state`() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { Host() }
        openText("org.example:zebra")

        restoration.emulateSavedInstanceStateRestore()

        assertTextShown("zebra", "Apache License\nVersion 2.0, January 2004")
        pressBack()
        assertListShown()
        pressBack()
        assertAboutShown()
    }

    @Test
    fun `About shows the copyright, and a Licence row above the open-source licences`() {
        show()

        compose.onNodeWithText(text(R.string.about_copyright)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.about_app_licence), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.about_app_licence_about), useUnmergedTree = true).assertIsDisplayed()
        assertThat(top(AboutAppLicenceRowTag)).isLessThan(top(AboutLicencesRowTag))
        assertThat(compose.onNodeWithText(Version).fetchSemanticsNode().boundsInRoot.top)
            .isLessThan(top(AboutAppLicenceRowTag))
    }

    private fun openAppLicence() = compose.onNodeWithTag(AboutAppLicenceRowTag).performClick()

    @Test
    fun `the Licence row shows the notice, then the GPL under its name`() {
        show()

        openAppLicence()

        val tops = listOf(
            text(R.string.app_licence_copyright),
            text(R.string.app_licence_free),
            text(R.string.app_licence_warranty),
            "GNU General Public License v3.0 or later",
            GplExcerpt,
        ).map { compose.onNodeWithText(it).assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top }
        assertThat(tops).isInStrictOrder()
        compose.onNodeWithTag(AboutAppLicenceRowTag).assertDoesNotExist()
    }

    @Test
    fun `the arrow and the system's Back return from the app's licence to About`() {
        show()
        openAppLicence()

        tapBackArrow()
        assertAboutShown()

        openAppLicence()
        pressBack()
        assertAboutShown()
        assertThat(left).isEqualTo(0)
    }

    @Test
    fun `the app's licence survives the loss of the screen's state`() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { Host() }
        openAppLicence()

        restoration.emulateSavedInstanceStateRestore()

        compose.onNodeWithText(GplExcerpt).assertIsDisplayed()
        pressBack()
        assertAboutShown()
    }
}
