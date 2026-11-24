package app.tfdn.lirelle.feature.comics.settings

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.comics.settings.Background
import app.tfdn.lirelle.core.comics.settings.ComicSettings
import app.tfdn.lirelle.core.comics.settings.Direction
import app.tfdn.lirelle.core.comics.settings.Fit
import app.tfdn.lirelle.core.comics.settings.TwoPages
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.feature.comics.R
import app.tfdn.lirelle.feature.comics.page.drawn
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import app.tfdn.lirelle.core.ui.R as UiR

/** The Comics page of the settings: its three groups, what a choice asks for, and the note on the direction. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class ComicSettingsPageTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val heading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
    private var settings: ComicSettings? by mutableStateOf(ComicSettings())
    private val calls = mutableListOf<String>()
    private val changes = object : ComicSettingsChanges {
        override fun setFit(fit: Fit) {
            calls += "fit $fit"
        }

        override fun setTwoPages(twoPages: TwoPages) {
            calls += "twoPages $twoPages"
        }

        override fun setBackground(background: Background) {
            calls += "background $background"
        }

        override fun setWholePageFirst(first: Boolean) {
            calls += "wholePageFirst $first"
        }
    }

    private fun text(id: Int): String = context.getString(id)

    private fun show(onBack: (() -> Unit)? = {}) {
        compose.setContent {
            LirelleTheme(darkTheme = false) { ComicSettingsPage(settings, changes, onBack) }
        }
    }

    @Test
    fun `the page is named Comics and has the three groups, with the note on two pages`() {
        show()

        compose.onNode(hasText("Comics") and heading).assertIsDisplayed()
        listOf("Fit", "Two pages side by side", "Background").forEach {
            compose.onNode(hasText(it) and heading).assertExists()
        }
        compose.onNodeWithText(text(R.string.comics_two_pages_note)).assertExists()
    }

    @Test
    fun `each choice asks for its setting at once`() {
        show()

        val choices = listOf(
            "Screen width" to "fit Width",
            "Whole page" to "fit Page",
            "Always" to "twoPages Always",
            "Never" to "twoPages Never",
            "Automatic" to "twoPages Automatic",
            "Grey" to "background Grey",
            "White" to "background White",
            "Black" to "background Black",
        )
        choices.forEach { (label, _) -> compose.onNodeWithText(label).performScrollTo().performClick() }

        assertThat(calls).containsExactlyElementsIn(choices.map { it.second }).inOrder()
    }

    @Test
    fun `what is stored shows as selected, in targets of 48 dp`() {
        show()

        assertSelected("Whole page", "Automatic", "Black")
        assertNotSelected("Screen width", "Always", "Never", "Grey", "White")

        settings = ComicSettings(Fit.Width, TwoPages.Never, Direction.RightToLeft, Background.Grey)
        compose.waitForIdle()

        assertSelected("Screen width", "Never", "Grey")
        assertNotSelected("Whole page", "Automatic", "Always", "Black", "White")
    }

    @Test
    fun `the direction is a note saying where it is set, with nothing to choose`() {
        show()

        compose.onNode(hasText("Reading direction") and heading).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("It is set for each series, or for a single album, from the comics reader.")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("Left to right").assertDoesNotExist()
        compose.onNodeWithText("Right to left").assertDoesNotExist()
    }

    @Test
    fun `the panels have their group after the background's, with what is said of them`() {
        show()

        val group = compose.onNode(hasText("Panel by panel") and heading).performScrollTo().drawn()
        assertThat(group.top).isAtLeast(compose.onNodeWithText("Black").drawn().bottom)
        compose.onNodeWithText("Each page is shown whole before its first panel.", substring = true).assertExists()
        val note = compose.onNodeWithText(text(R.string.comics_panels_note)).performScrollTo().drawn()
        assertThat(note.top).isAtLeast(group.bottom)
        val direction = compose.onNode(hasText("Reading direction") and heading).performScrollTo().drawn()
        assertThat(direction.top).isAtLeast(note.bottom)
    }

    @Test
    fun `the switch of the whole page says what is stored, and asks for the other`() {
        show()

        val switch = compose.onNodeWithText("Whole page first", substring = true).performScrollTo()
        switch.assertIsOn().assertHeightIsAtLeast(48.dp).performClick()
        assertThat(calls).containsExactly("wholePageFirst false")

        settings = ComicSettings(wholePageFirst = false)
        compose.waitForIdle()

        compose.onNodeWithText("Whole page first", substring = true).assertIsOff().performClick()
        assertThat(calls).containsExactly("wholePageFirst false", "wholePageFirst true").inOrder()
    }

    @Test
    fun `nothing is chosen before the store is read`() {
        settings = null
        show()

        compose.onNode(hasText("Comics") and heading).assertIsDisplayed()
        compose.onNodeWithText("Whole page").assertDoesNotExist()
        compose.onNodeWithText("Reading direction").assertDoesNotExist()
    }

    @Test
    fun `on its own the page has an arrow that goes back`() {
        var backs = 0
        show(onBack = { backs++ })

        compose.onNodeWithContentDescription(text(UiR.string.settings_back)).performClick()

        assertThat(backs).isEqualTo(1)
    }

    @Test
    fun `in a pane the page has its title and no arrow`() {
        show(onBack = null)

        compose.onNode(hasText("Comics") and heading).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(UiR.string.settings_back)).assertDoesNotExist()
    }

    private fun assertSelected(vararg labels: String) = labels.forEach {
        compose.onNodeWithText(it).assertIsSelected().assertHeightIsAtLeast(48.dp)
    }

    private fun assertNotSelected(vararg labels: String) = labels.forEach {
        compose.onNodeWithText(it).assertIsNotSelected().assertHeightIsAtLeast(48.dp)
    }
}
