package app.tfdn.lirelle.feature.library

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.ids.LibraryId
import app.tfdn.lirelle.core.library.LibraryChoiceState
import app.tfdn.lirelle.core.library.model.ServerLibrary
import app.tfdn.lirelle.core.testing.assertIsWhollyShown
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val ProfileTag = "profile"
private const val AllTag = "library-choice-all"

/** The Library's title chooses the library shown, among those of its server, once there are two. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Real font metrics: places and heights are compared.
@Config(qualifiers = "w411dp-h891dp")
class LibrarySelectorTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val comics = ServerLibrary(LibraryId("1"), "Comics")
    private val manga = ServerLibrary(LibraryId("2"), "Manga")
    private val novels = ServerLibrary(LibraryId("3"), "Novels")
    private val three = listOf(comics, manga, novels)

    private var libraries by mutableStateOf(LibraryChoiceState())
    private val chosen = mutableListOf<ServerLibrary?>()

    private val title = context.getString(R.string.library_title)
    private val all = context.getString(R.string.library_all)
    private val selector get() = compose.onNodeWithTag(LibrarySelectorTag)

    private fun line(library: ServerLibrary) = compose.onNodeWithTag("library-choice-${library.id}")

    private fun described(shown: String) = context.getString(R.string.library_selector_description, shown)

    private fun show() {
        compose.setContent {
            LirelleTheme {
                LibraryScreen(
                    libraries = libraries,
                    onChooseLibrary = { chosen += it },
                    titleAction = { Box(Modifier.size(48.dp).testTag(ProfileTag)) },
                    page = { tab -> Text("page of ${tab.name}") },
                )
            }
        }
    }

    private fun assertPlainTitle() {
        selector.assertDoesNotExist()
        compose.onNodeWithText(title)
            .assertIsDisplayed()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
            .performClick()
        compose.onNodeWithTag(AllTag).assertDoesNotExist()
        compose.onNode(isPopup()).assertDoesNotExist()
    }

    @Test
    fun `without a library the title is the plain Library, with no arrow and no menu`() {
        show()

        assertPlainTitle()
    }

    @Test
    fun `with one library the title is the plain Library, with no arrow and no menu`() {
        libraries = LibraryChoiceState(listOf(comics))
        show()

        assertPlainTitle()
    }

    @Test
    fun `with three libraries and none chosen the button says Library and the menu has all libraries checked`() {
        libraries = LibraryChoiceState(three)
        show()

        selector
            .assertTextEquals(title)
            .assertContentDescriptionEquals(context.getString(R.string.library_selector_description_all))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            .assert(hasClickAction())
        compose.onNodeWithTag(AllTag).assertDoesNotExist()

        selector.performClick()

        compose.onNodeWithTag(AllTag).assertIsDisplayed().assertTextEquals(all).assertIsSelected()
        assertChecked(AllTag, true)
        three.forEach { library ->
            line(library).assertIsDisplayed().assertTextEquals(library.name).assertIsNotSelected()
            assertChecked("library-choice-${library.id}", false)
        }
        // "All libraries" first, then the server's order.
        val tops = (listOf(compose.onNodeWithTag(AllTag)) + three.map(::line)).map { it.getUnclippedBoundsInRoot().top }
        assertThat(tops).isInStrictOrder()
    }

    @Test
    fun `the libraries are listed in the server's order, not by name`() {
        libraries = LibraryChoiceState(listOf(novels, comics, manga))
        show()

        selector.performClick()

        val tops = listOf(novels, comics, manga).map { line(it).getUnclippedBoundsInRoot().top }
        assertThat(tops).isInStrictOrder()
    }

    @Test
    fun `with a chosen library the button says its name and its line is the one checked`() {
        libraries = LibraryChoiceState(three, chosen = manga)
        show()

        selector.assertTextEquals("Manga").assertContentDescriptionEquals(described("Manga"))
        compose.onNodeWithText(title).assertDoesNotExist()

        selector.performClick()

        line(manga).assertIsSelected()
        assertChecked("library-choice-2", true)
        compose.onNodeWithTag(AllTag).assertIsNotSelected()
        assertChecked(AllTag, false)
        line(comics).assertIsNotSelected()
        line(novels).assertIsNotSelected()
    }

    @Test
    fun `the lines of the menu are the options of one choice`() {
        libraries = LibraryChoiceState(three, chosen = manga)
        show()
        selector.performClick()

        (listOf(compose.onNodeWithTag(AllTag)) + three.map(::line)).forEach { line ->
            line.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
                .assert(hasClickAction())
        }
    }

    @Test
    fun `choosing a library says which and closes the menu`() {
        libraries = LibraryChoiceState(three)
        show()
        selector.performClick()

        line(manga).performClick()

        assertThat(chosen).containsExactly(manga)
        compose.onNodeWithTag(AllTag).assertDoesNotExist()
        line(manga).assertDoesNotExist()
    }

    @Test
    fun `choosing all libraries says none and closes the menu`() {
        libraries = LibraryChoiceState(three, chosen = manga)
        show()
        selector.performClick()

        compose.onNodeWithTag(AllTag).performClick()

        assertThat(chosen).containsExactly(null)
        compose.onNodeWithTag(AllTag).assertDoesNotExist()
    }

    @Test
    fun `the tab shown stays when the library changes`() {
        libraries = LibraryChoiceState(three)
        show()
        compose.onNodeWithTag("tab-Authors").performClick()
        compose.onNodeWithText("page of Authors").assertIsDisplayed()

        libraries = libraries.copy(chosen = manga)

        selector.assertTextEquals("Manga")
        compose.onNodeWithTag("tab-Authors").assertIsSelected()
        compose.onNodeWithText("page of Authors").assertIsDisplayed()
    }

    @Test
    fun `nothing of the Library moves when the title becomes a button, 48 dp high and clear of the tabs`() {
        show()
        val heading = compose.onNodeWithText(title).getUnclippedBoundsInRoot()
        val tabs = compose.onNodeWithTag("tab-Books").getUnclippedBoundsInRoot()
        val profile = compose.onNodeWithTag(ProfileTag).getUnclippedBoundsInRoot()

        libraries = LibraryChoiceState(three)

        val button = selector.assertIsWhollyShown().getUnclippedBoundsInRoot()
        val label = compose.onNodeWithText(title, useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertThat(label.top).isEqualTo(heading.top)
        assertThat(label.left).isEqualTo(heading.left)
        assertThat(compose.onNodeWithTag("tab-Books").getUnclippedBoundsInRoot().top).isEqualTo(tabs.top)
        assertThat(compose.onNodeWithTag(ProfileTag).getUnclippedBoundsInRoot()).isEqualTo(profile)
        assertThat(button.height).isAtLeast(48.dp)
        assertThat(button.top).isAtLeast(0.dp)
        assertThat(button.bottom).isAtMost(tabs.top)
        assertThat(button.right).isAtMost(profile.left)
    }

    @Test
    fun `the button is tapped over its whole height, past the title's line`() {
        libraries = LibraryChoiceState(three)
        show()

        selector.performTouchInput { click(Offset(centerX, 1f)) }
        compose.onNodeWithTag(AllTag).assertIsDisplayed().performClick()
        selector.performTouchInput { click(Offset(centerX, height - 2f)) }

        compose.onNodeWithTag(AllTag).assertIsDisplayed()
        assertThat(chosen).containsExactly(null)
    }

    @Test
    fun `a name too long for the row stays on one line and ends before the profile button`() {
        val long = ServerLibrary(LibraryId("9"), "Bandes dessinées franco-belges pour tous les publics")
        libraries = LibraryChoiceState(listOf(long, manga), chosen = long)
        show()

        val button = selector.assertIsWhollyShown().getUnclippedBoundsInRoot()
        val profile = compose.onNodeWithTag(ProfileTag).assertIsWhollyShown().getUnclippedBoundsInRoot()
        assertThat(button.right).isAtMost(profile.left)
        assertThat(button.bottom).isAtMost(compose.onNodeWithTag("tab-Books").getUnclippedBoundsInRoot().top)
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun `on a tablet in landscape the menu opens within the screen, under the title`() {
        libraries = LibraryChoiceState(three, chosen = comics)
        show()

        selector.performClick()

        val menu = compose.onNode(isPopup()).fetchSemanticsNode()
        val button = selector.fetchSemanticsNode()
        val screen = context.resources.displayMetrics
        assertThat(menu.boundsInWindow.width).isGreaterThan(0f)
        assertThat(menu.positionOnScreen.x).isAtLeast(0f)
        // Under the title, whose line ends a little above the button's lower edge.
        assertThat(menu.positionOnScreen.y).isGreaterThan(button.positionOnScreen.y + button.size.height / 2)
        assertThat(menu.positionOnScreen.y).isAtMost(button.positionOnScreen.y + button.size.height)
        assertThat(menu.positionOnScreen.x + menu.size.width).isAtMost(screen.widthPixels.toFloat())
        assertThat(menu.positionOnScreen.y + menu.size.height).isAtMost(screen.heightPixels.toFloat())
        (listOf(compose.onNodeWithTag(AllTag)) + three.map(::line)).forEach { it.assertIsWhollyShown() }
    }

    @Test
    @Config(qualifiers = "w411dp-h320dp")
    fun `ten libraries make a menu that scrolls to its last one`() {
        val ten = (1..10).map { ServerLibrary(LibraryId("$it"), "Library number $it") }
        libraries = LibraryChoiceState(ten)
        show()

        selector.performClick()

        val last = line(ten.last())
        last.assert(hasAnyAncestor(hasScrollAction()))
        last.assertIsNotDisplayed()
        last.performScrollTo().assertIsWhollyShown()
        last.performClick()
        assertThat(chosen).containsExactly(ten.last())
    }

    /** Whether the line with this tag shows the check mark. */
    private fun assertChecked(tag: String, checked: Boolean) {
        val marks = hasTestTag(LibraryCheckTag) and hasAnyAncestor(hasTestTag(tag))
        assertThat(compose.onAllNodes(marks, useUnmergedTree = true).fetchSemanticsNodes())
            .hasSize(if (checked) 1 else 0)
    }
}
