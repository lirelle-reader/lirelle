package app.tfdn.lirelle.shell

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasParent
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.R
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.designsystem.LocalWindowWidthClass
import app.tfdn.lirelle.core.designsystem.WindowWidthClass
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.core.testing.assertIsWhollyShown
import app.tfdn.lirelle.core.ui.profileAvatarModel
import app.tfdn.lirelle.feature.account.switcher.ProfileButtonTag
import app.tfdn.lirelle.feature.account.switcher.ProfileRow
import app.tfdn.lirelle.feature.account.switcher.ProfileRowTagPrefix
import app.tfdn.lirelle.feature.account.switcher.ProfileSwitcher
import app.tfdn.lirelle.feature.account.switcher.ProfilesAddTag
import app.tfdn.lirelle.feature.account.switcher.ProfilesHost
import app.tfdn.lirelle.feature.account.switcher.ProfilesManageTag
import app.tfdn.lirelle.feature.account.switcher.ProfilesMenuTag
import app.tfdn.lirelle.feature.account.switcher.SwitcherState
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val SystemNavigation = 48
private const val StatusBar = 24

private fun row(id: String, username: String, current: Boolean = false) = ProfileRow(
    id = id,
    line = "$username · BookOrbit",
    username = username,
    kindName = "BookOrbit",
    kind = ServerKind.BookOrbit,
    usersOwnKey = false,
    server = "livres.example.org",
    avatar = profileAvatarModel(id, username.take(1).uppercase(), ServerKind.BookOrbit, null, signInNeeded = false),
    current = current,
    signInNeeded = false,
)

/**
 * The profile button in the shell: at the bottom of the rail on wide screens, nowhere of the shell's own with
 * the bottom bar, and what its menu leads to.
 */
@RunWith(RobolectricTestRunner::class)
class ShellProfilesTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var profiles by mutableStateOf(
        SwitcherState(listOf(row("p0", "camille", current = true), row("p1", "sacha"))),
    )
    private val switched = mutableListOf<String>()
    private var added = 0

    private val button get() = compose.onNodeWithTag(ProfileButtonTag)
    private val moreAccount: String =
        ApplicationProvider.getApplicationContext<Context>().getString(R.string.more_account)

    private fun show(width: WindowWidthClass) {
        compose.setContent {
            LirelleTheme {
                CompositionLocalProvider(LocalWindowWidthClass provides width) {
                    Shell(
                        account = { onBack ->
                            Text("account", Modifier.testTag("account-slot").clickable(onClick = onBack))
                        },
                        home = { stubHome(it) },
                        library = { stubLibrary(it) },
                        search = { stubSearch(it) },
                        downloads = { stubDownloads(it) },
                        railProfiles = { onManage ->
                            ProfileSwitcher(
                                state = profiles,
                                host = ProfilesHost.Menu(24.dp),
                                onSwitch = { switched += it },
                                onAddProfile = { added++ },
                                onManageProfiles = onManage,
                            )
                        },
                    )
                }
            }
        }
    }

    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private fun window(): Rect = compose.onRoot().fetchSemanticsNode().boundsInRoot

    private fun pressBack() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun `in the rail the button is at the bottom, under the destinations, centred as they are`() {
        show(WindowWidthClass.Expanded)

        val placed = button.assertIsWhollyShown().fetchSemanticsNode().boundsInRoot
        val more = bounds("nav-more")
        assertThat(placed.top).isAtLeast(more.bottom)
        assertThat(placed.center.x).isWithin(1f).of(more.center.x)
        // At the bottom of the rail, not right under the last destination.
        assertThat(window().bottom - placed.bottom).isLessThan(placed.height)
        assertThat(placed.top - more.bottom).isGreaterThan(placed.height)
        // The destinations are where they were: a column from the top, in their order.
        val tops = TopLevelDestination.entries.map { bounds(it.testTag).top }
        assertThat(tops).isInStrictOrder()
        assertThat(TopLevelDestination.entries.map { bounds(it.testTag).left }.toSet()).hasSize(1)
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun `the rail with a button is the rail without one, down to the last destination`() {
        show(WindowWidthClass.Expanded)
        val with = TopLevelDestination.entries.map { bounds(it.testTag) }
        val rail = bounds("nav-home").right

        profiles = SwitcherState(profiles.rows.take(1))

        button.assertDoesNotExist()
        assertThat(TopLevelDestination.entries.map { bounds(it.testTag) }).isEqualTo(with)
        assertThat(bounds("nav-home").right).isEqualTo(rail)
    }

    @Test
    @Config(qualifiers = "w700dp-h1000dp")
    fun `the destinations of the rail are still selected by a tap, with the button there`() {
        show(WindowWidthClass.Medium)

        compose.onNodeWithTag("nav-library").performClick()

        compose.onNodeWithTag("nav-library").assertIsSelected()
        compose.onNodeWithText("library-root").assertIsDisplayed()
        button.assertIsDisplayed()
    }

    /** What lets a screen reader say "2 of 5" of a destination: the group is what holds the items themselves. */
    @Test
    @Config(qualifiers = "w700dp-h1000dp")
    fun `the destinations of the rail are one group to choose from, which the button is not part of`() {
        show(WindowWidthClass.Medium)

        val group = SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup)
        TopLevelDestination.entries.forEach { compose.onNodeWithTag(it.testTag).assert(hasParent(group)) }
        button.assert(hasParent(group).not())
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `with the bottom bar the shell shows no button of its own`() {
        show(WindowWidthClass.Compact)

        button.assertDoesNotExist()
        compose.onNodeWithTag(ShellRailFooterTag).assertDoesNotExist()
        assertThat(bounds("nav-home").top).isEqualTo(bounds("nav-more").top)
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun `with one profile the rail has no button`() {
        profiles = SwitcherState(profiles.rows.take(1))
        show(WindowWidthClass.Expanded)

        button.assertDoesNotExist()
        compose.onNodeWithTag("nav-more").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun `the button stays above the system's navigation bar`() {
        show(WindowWidthClass.Expanded)

        compose.runOnUiThread {
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, StatusBar, 0, 0))
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, SystemNavigation))
                .build()
            ViewCompat.dispatchApplyWindowInsets(compose.activity.window.decorView, insets)
        }
        compose.waitForIdle()

        assertThat(button.fetchSemanticsNode().boundsInRoot.bottom).isAtMost(window().bottom - SystemNavigation)
        assertThat(bounds("nav-home").top).isAtLeast(StatusBar.toFloat())
    }

    @Test
    @Config(qualifiers = "w700dp-h360dp")
    fun `in a window too low for everything the button is whole, and the destinations scroll above it`() {
        show(WindowWidthClass.Medium)

        val placed = button.assertIsWhollyShown().fetchSemanticsNode().boundsInRoot
        assertThat(placed.bottom).isAtMost(window().bottom)

        compose.onNodeWithTag("nav-more").performScrollTo().assertIsWhollyShown().performClick()

        compose.onNodeWithTag("nav-more").assertIsSelected()
        assertThat(bounds("nav-more").bottom).isAtMost(button.fetchSemanticsNode().boundsInRoot.top)
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun `the button opens the profiles in a menu, and a profile chosen there is switched to`() {
        show(WindowWidthClass.Expanded)

        button.performClick()
        compose.onNodeWithTag(ProfilesMenuTag).assertIsDisplayed()
        compose.onNode(hasTestTag(ProfileRowTagPrefix + "p1") and hasAnyAncestor(hasTestTag(ProfilesMenuTag)))
            .performClick()

        assertThat(switched).containsExactly("p1")
        compose.onNodeWithTag(ProfilesMenuTag).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun `Add a profile of the menu asks for the sign-in screens`() {
        show(WindowWidthClass.Expanded)

        button.performClick()
        compose.onNodeWithTag(ProfilesAddTag).performClick()

        assertThat(added).isEqualTo(1)
        compose.onNodeWithTag(ProfilesMenuTag).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun `Manage profiles opens the page of More, and Back from it leads to More`() {
        show(WindowWidthClass.Expanded)
        compose.onNodeWithTag("nav-library").performClick()

        button.performClick()
        compose.onNodeWithTag(ProfilesManageTag).performClick()

        compose.onNodeWithTag(ProfilesMenuTag).assertDoesNotExist()
        compose.onNodeWithTag("account-slot").assertIsDisplayed()
        compose.onNodeWithTag("nav-more").assertIsSelected()

        pressBack()

        compose.onNodeWithTag("account-slot").assertDoesNotExist()
        compose.onNodeWithTag("nav-more").assertIsSelected()
        compose.onNodeWithText(moreAccount).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun `Manage profiles from the page itself, or asked twice, opens it once`() {
        show(WindowWidthClass.Expanded)

        repeat(2) {
            button.performClick()
            compose.onNodeWithTag(ProfilesManageTag).performClick()
        }
        compose.onNodeWithTag("account-slot").assertIsDisplayed()

        pressBack()

        compose.onNodeWithTag("account-slot").assertDoesNotExist()
        compose.onNodeWithText(moreAccount).assertIsDisplayed()
    }
}
