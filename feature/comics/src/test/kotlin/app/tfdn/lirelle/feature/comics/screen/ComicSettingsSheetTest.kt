package app.tfdn.lirelle.feature.comics.screen

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.comics.settings.Background
import app.tfdn.lirelle.core.comics.settings.ComicSettings
import app.tfdn.lirelle.core.comics.settings.Direction
import app.tfdn.lirelle.core.comics.settings.DirectionScope
import app.tfdn.lirelle.core.comics.settings.Fit
import app.tfdn.lirelle.core.comics.settings.TwoPages
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.model.PagedKind
import app.tfdn.lirelle.feature.comics.ComicSettingsButtonTag
import app.tfdn.lirelle.feature.comics.PanelReading
import app.tfdn.lirelle.feature.comics.page.drawn
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The sheet of the reading settings: its groups, what is chosen, and what a choice asks for. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class ComicSettingsSheetTest {

    @get:Rule
    val compose = createComposeRule()

    private val screen = ScreenUnderTest()
    private var settingsName = "Reading settings"
    private val heading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)

    @Test
    fun `the sheet is closed until the settings button opens it`() {
        compose.setContent { screen.Content() }
        compose.onNode(hasText("Reading settings")).assertDoesNotExist()

        open()

        compose.onNode(hasText("Reading settings") and heading).assertExists()
        listOf("Fit", "Two pages side by side", "Background", "Reading direction").forEach {
            compose.onNode(hasText(it) and heading).assertExists()
        }
        compose.onNodeWithText("Automatic: on a tablet in landscape. The cover and double spreads stay alone.")
            .assertExists()
    }

    @Test
    fun `each choice asks for its setting at once`() {
        compose.setContent { screen.Content() }
        open()

        val choices = listOf(
            "Screen width" to "fit Width",
            "Whole page" to "fit Page",
            "Always" to "twoPages Always",
            "Never" to "twoPages Never",
            "Automatic" to "twoPages Automatic",
            "Grey" to "background Grey",
            "White" to "background White",
            "Black" to "background Black",
            "Right to left" to "direction RightToLeft",
            "Left to right" to "direction LeftToRight",
        )
        choices.forEach { (label, _) -> compose.onNodeWithText(label).performScrollTo().performClick() }

        assertThat(screen.actions.calls).containsExactlyElementsIn(choices.map { it.second }).inOrder()
    }

    @Test
    fun `what is chosen shows as selected, in targets of 48 dp`() {
        compose.setContent { screen.Content() }
        open()

        assertSelected("Whole page", "Automatic", "Black", "Left to right")
        assertNotSelected("Screen width", "Always", "Never", "Grey", "White", "Right to left")

        screen.state = openAlbum(
            settings = ComicSettings(Fit.Width, TwoPages.Never, Direction.RightToLeft, Background.Grey),
        )
        compose.waitForIdle()

        assertSelected("Screen width", "Never", "Grey", "Right to left")
        assertNotSelected("Whole page", "Automatic", "Always", "Black", "White", "Left to right")
    }

    @Test
    fun `the direction is said to be the series's, or the album's when it has none`() {
        compose.setContent { screen.Content() }
        open()
        compose.onNodeWithText("For every album of the series “Blacksad”").assertExists()
        compose.onNodeWithText("For this album").assertDoesNotExist()

        screen.state = openAlbum().copy(series = null, directionScope = DirectionScope.Book(BookId("14")))
        compose.waitForIdle()

        compose.onNodeWithText("For this album").assertExists()
    }

    @Test
    fun `a comic is offered three directions, the vertical one among them`() {
        compose.setContent { screen.Content() }
        open()

        val places = listOf("Left to right", "Right to left", "Vertical").map {
            compose.onNodeWithText(it).performScrollTo().assertHeightIsAtLeast(48.dp).drawn()
        }
        assertThat(places.map { it.top }.distinct()).hasSize(1)
        assertThat(places.map { it.left }).isInStrictOrder()

        compose.onNodeWithText("Vertical").performClick()

        assertThat(screen.actions.calls).containsExactly("direction Vertical")
    }

    @Test
    fun `an album read vertically has neither the fit nor the two pages, and is told what vertical is`() {
        val note = "Vertical: the pages follow one another without a break, for webtoons."
        screen.state = openAlbum(settings = ComicSettings(direction = Direction.Vertical))
        compose.setContent { screen.Content() }
        open()

        listOf("Fit", "Two pages side by side").forEach {
            compose.onNode(hasText(it) and heading).assertDoesNotExist()
        }
        listOf("Whole page", "Screen width", "Automatic", "Always", "Never").forEach {
            compose.onNodeWithText(it).assertDoesNotExist()
        }
        compose.onNode(hasText("Background") and heading).assertExists()
        compose.onNodeWithText("Vertical").assertIsSelected().assertHeightIsAtLeast(48.dp)
        // Under the choices, over what the direction is remembered for.
        val told = compose.onNodeWithText(note).performScrollTo().drawn()
        assertThat(told.top).isAtLeast(compose.onNodeWithText("Vertical").drawn().bottom)
        val scope = compose.onNodeWithText("For every album of the series “Blacksad”").performScrollTo().drawn()
        assertThat(scope.top).isAtLeast(told.bottom)

        screen.state = openAlbum()
        compose.waitForIdle()

        compose.onNodeWithText(note).assertDoesNotExist()
        listOf("Fit", "Two pages side by side").forEach {
            compose.onNode(hasText(it) and heading).assertExists()
        }
    }

    @Test
    fun `a document is offered the two directions its pages are turned in, not the vertical one`() {
        screen.state = openAlbum().copy(kind = PagedKind.Pdf)
        compose.setContent { screen.Content() }
        open()

        listOf(PagedKind.Pdf, PagedKind.Djvu).forEach { kind ->
            screen.state = openAlbum().copy(kind = kind)
            compose.waitForIdle()

            compose.onNodeWithText("Left to right").assertExists()
            compose.onNodeWithText("Right to left").assertExists()
            compose.onNodeWithText("Vertical").assertDoesNotExist()
        }
    }

    @Test
    fun `the choices of a setting stand side by side when their labels fit`() {
        compose.setContent { screen.Content() }
        open()

        val places = listOf("Automatic", "Always", "Never").map { compose.onNodeWithText(it).drawn() }

        assertThat(places.map { it.top }.distinct()).hasSize(1)
        // To a pixel: the width of the group is shared out in whole pixels.
        places.forEach { assertThat(it.width).isWithin(1f).of(places[0].width) }
        assertThat(places.map { it.left }).isInStrictOrder()
    }

    @Test
    @Config(qualifiers = "fr-w320dp-h640dp", fontScale = 2f)
    fun `they go one under the other when a label would be cut`() {
        settingsName = "Réglages de lecture"
        compose.setContent { screen.Content() }
        open()

        val places = listOf("Automatique", "Toujours", "Jamais").map {
            compose.onNodeWithText(it).performScrollTo().drawn()
        }

        assertThat(places.map { it.left }.distinct()).hasSize(1)
        assertThat(places.map { it.width }.distinct()).hasSize(1)
        // Each as wide as the group, not a third of it: the window is 320 pixels wide.
        assertThat(places[0].width).isGreaterThan(240f)
    }

    @Test
    fun `an album that is not offered the panels has no group for them`() {
        compose.setContent { screen.Content() }
        open()

        compose.onNodeWithText("Panel by panel").assertDoesNotExist()
        compose.onNodeWithText("Whole page first", substring = true).assertDoesNotExist()
    }

    @Test
    fun `an album offered the panels has their group between the background and the direction`() {
        screen.state = offered()
        compose.setContent { screen.Content() }
        open()

        val group = compose.onNode(hasText("Panel by panel") and heading).performScrollTo().drawn()
        assertThat(group.top).isAtLeast(compose.onNodeWithText("Black").drawn().bottom)
        val note = compose
            .onNodeWithText("Panels are found automatically. A page that does not lend itself to it is shown whole.")
            .performScrollTo()
            .drawn()
        assertThat(note.top).isAtLeast(group.bottom)
        val direction = compose.onNode(hasText("Reading direction") and heading).performScrollTo().drawn()
        assertThat(direction.top).isAtLeast(note.bottom)
    }

    @Test
    fun `the switch of the whole page says the setting, and asks for the other`() {
        screen.state = offered()
        compose.setContent { screen.Content() }
        open()

        val switch = compose.onNodeWithText("Whole page first", substring = true).performScrollTo()
        switch.assertIsOn().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("Each page is shown whole before its first panel.", substring = true).assertExists()
        switch.performClick()
        assertThat(screen.actions.calls).containsExactly("wholePageFirst false")

        screen.state = offered(ComicSettings(wholePageFirst = false))
        compose.waitForIdle()

        compose.onNodeWithText("Whole page first", substring = true).assertIsOff().performClick()
        assertThat(screen.actions.calls).containsExactly("wholePageFirst false", "wholePageFirst true").inOrder()
    }

    @Test
    fun `with the panels' group, the switch of the zones stays last`() {
        val debugged = ScreenUnderTest(offered(), debuggable = true)
        compose.setContent { debugged.Content() }
        open()

        val zones = compose.onNodeWithText("Zones (debug)").performScrollTo().drawn()
        val panels = compose.onNodeWithText("Whole page first", substring = true).performScrollTo().drawn()
        assertThat(zones.top).isAtLeast(panels.bottom)
    }

    private fun offered(settings: ComicSettings = ComicSettings()) =
        openAlbum(settings = settings).copy(panels = PanelReading(offered = true))

    @Test
    fun `a build that cannot be debugged has no switch for the zones`() {
        compose.setContent { screen.Content() }
        open()

        compose.onNodeWithText("Zones (debug)").assertDoesNotExist()
    }

    @Test
    fun `a build that can be debugged has a switch for the zones, last, off until turned on`() {
        val debugged = ScreenUnderTest(debuggable = true)
        compose.setContent { debugged.Content() }
        open()

        val switch = compose.onNodeWithText("Zones (debug)").performScrollTo()
        switch.assertIsOff().assertHeightIsAtLeast(48.dp)
        val note = compose.onNodeWithText("For every album of the series “Blacksad”").drawn()
        assertThat(switch.drawn().top).isAtLeast(note.bottom)

        switch.performClick()
        assertThat(debugged.actions.calls).containsExactly("zones true")

        debugged.state = openAlbum().copy(zonesShown = true)
        compose.waitForIdle()
        compose.onNodeWithText("Zones (debug)").assertIsOn().performClick()
        assertThat(debugged.actions.calls).containsExactly("zones true", "zones false").inOrder()
    }

    private fun open() {
        compose.onNodeWithTag(ComicSettingsButtonTag).assertContentDescriptionEquals(settingsName).performClick()
        compose.waitForIdle()
    }

    private fun assertSelected(vararg labels: String) = labels.forEach {
        compose.onNodeWithText(it).assertIsSelected().assertHeightIsAtLeast(48.dp)
    }

    private fun assertNotSelected(vararg labels: String) = labels.forEach {
        compose.onNodeWithText(it).assertIsNotSelected().assertHeightIsAtLeast(48.dp)
    }
}
