package app.tfdn.lirelle.feature.account

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.designsystem.LocalWindowWidthClass
import app.tfdn.lirelle.core.designsystem.WindowWidthClass
import app.tfdn.lirelle.core.network.AccountInfo
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.ui.R as CoreR
import app.tfdn.lirelle.feature.account.switcher.ProfileRowTagPrefix
import app.tfdn.lirelle.feature.account.switcher.row
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val Phone = "w360dp-h740dp-xhdpi"
private const val Tablet = "w1280dp-h800dp-xhdpi"

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = Phone)
class ProfilesScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val loaded = AccountUiState.Loaded(
        AccountInfo("Camille Lecteur", "camille", "camille@example.org", "https://livres.example.org", "v3.8.0"),
    )
    private val sacha = row("p1", "sacha", "bd.example.org")
    private val tester = row("p2", "test", "livres.example.org", signInNeeded = true)

    private val switched = mutableListOf<String>()
    private val signedInAgain = mutableListOf<String>()
    private val removedOthers = mutableListOf<String>()
    private var added = 0
    private var removedCurrent = 0
    private var retried = 0
    private var backs = 0

    private fun text(id: Int) = context.getString(id)

    private fun current(signInNeeded: Boolean = false, email: String? = "camille@example.org") = CurrentProfileDetails(
        row = row("p0", "camille", "livres.example.org", current = true, signInNeeded = signInNeeded),
        name = "Camille Lecteur",
        email = email,
        serverUrl = "https://livres.example.org",
    )

    private fun show(
        state: ProfilesPageState,
        details: AccountUiState = loaded,
        width: WindowWidthClass = WindowWidthClass.Compact,
    ) {
        val actions = ProfilesPageActions(
            onBack = { backs++ },
            onRetry = { retried++ },
            onSwitch = { switched += it },
            onAdd = { added++ },
            onSignInAgain = { signedInAgain += it },
            onRemoveCurrent = { removedCurrent++ },
            onRemoveOther = { removedOthers += it },
        )
        compose.setContent {
            LirelleTheme {
                CompositionLocalProvider(LocalWindowWidthClass provides width) {
                    ProfilesScreen(state, details, actions = actions)
                }
            }
        }
    }

    private fun top(text: String) = compose.onNodeWithText(text).getUnclippedBoundsInRoot().top

    private fun bounds(tag: String) = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()

    /** The app's version has its place in More › About, not on this page, in either language. */
    private fun assertNoAppVersion() {
        listOf("App version", "Version de l'application").forEach {
            compose.onNodeWithText(it, substring = true).assertDoesNotExist()
        }
    }

    private val heading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)

    @Test
    fun `with one profile it is the account's page, with Add a profile and Remove this profile`() {
        show(ProfilesPageState(current()))

        compose.onNodeWithText(text(R.string.profiles_title)).assertIsDisplayed()
        listOf("Camille Lecteur", "camille · BookOrbit", "camille@example.org", "https://livres.example.org", "v3.8.0")
            .forEach { compose.onNodeWithText(it).performScrollTo().assertIsDisplayed() }
        compose.onNode(hasText(text(R.string.account_username)) and hasText("camille")).assertExists()
        assertNoAppVersion()
        compose.onNodeWithText(text(R.string.profiles_current)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.profiles_others)).assertDoesNotExist()
        compose.onNodeWithTag(OtherProfilesColumnTag).assertDoesNotExist()
        // As on the canvas: the username first, and "Add a profile" before "Remove this profile".
        assertThat(
            listOf(
                top(text(R.string.account_username)),
                top(text(R.string.account_email)),
                top(text(R.string.account_server)),
                top(text(R.string.account_server_version)),
                bounds(ProfilesPageAddTag).top,
                bounds(ProfilesPageRemoveTag).top,
            ),
        ).isInStrictOrder()
    }

    @Test
    fun `the e-mail row is left out when the account has none`() {
        show(ProfilesPageState(current(email = null)))

        compose.onNodeWithText(text(R.string.account_email)).assertDoesNotExist()
    }

    /** Komga tells no version to an account that is not its administrator. */
    @Test
    fun `the server version row is left out when the source gives none`() {
        val versionless = AccountUiState.Loaded(loaded.info.copy(serverVersion = null))
        show(ProfilesPageState(current()), details = versionless)

        compose.onNodeWithText(text(R.string.account_server)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.account_server_version)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.account_retry)).assertDoesNotExist()
    }

    @Test
    fun `Add a profile and Remove this profile report their clicks`() {
        show(ProfilesPageState(current()))

        compose.onNodeWithText(text(R.string.profiles_add)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.profiles_remove)).performScrollTo().performClick()

        assertThat(added).isEqualTo(1)
        assertThat(removedCurrent).isEqualTo(1)
    }

    @Test
    fun `with two profiles both sections have their label, and the other profile follows the current one`() {
        show(ProfilesPageState(current(), listOf(sacha)))

        compose.onNodeWithText(text(R.string.profiles_current)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.profiles_others)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("sacha · BookOrbit", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("bd.example.org", useUnmergedTree = true).assertExists()
        // The server first, as on the canvas, and nothing of today's page is dropped.
        assertThat(
            listOf(
                top(text(R.string.profiles_current)),
                top(text(R.string.account_server)),
                top(text(R.string.account_server_version)),
                top(text(R.string.account_email)),
                top(text(R.string.account_username)),
                bounds(ProfilesPageRemoveTag).top,
                top(text(R.string.profiles_others)),
                bounds(ProfileRowTagPrefix + "p1").top,
                bounds(ProfilesPageAddTag).top,
            ),
        ).isInStrictOrder()
        assertNoAppVersion()
    }

    @Test
    fun `with three profiles the others are in order of use, the marked one saying so`() {
        show(ProfilesPageState(current(), listOf(sacha, tester)))

        assertThat(bounds(ProfileRowTagPrefix + "p1").top).isLessThan(bounds(ProfileRowTagPrefix + "p2").top)
        compose.onNodeWithText(text(CoreR.string.sign_in_needed), useUnmergedTree = true).assertExists()
    }

    @Test
    fun `on a narrow screen the other profiles are under the current one, at the same width`() {
        show(ProfilesPageState(current(), listOf(sacha, tester)))

        val first = bounds(CurrentProfileColumnTag)
        val second = bounds(OtherProfilesColumnTag)
        assertThat(second.top).isAtLeast(first.bottom)
        assertThat(second.left).isEqualTo(first.left)
        assertThat(second.width).isEqualTo(first.width)
    }

    @Test
    @Config(qualifiers = "w700dp-h900dp-xhdpi")
    fun `on a medium screen there is one column still, no wider than 600 dp`() {
        show(ProfilesPageState(current(), listOf(sacha)), width = WindowWidthClass.Medium)

        val first = bounds(CurrentProfileColumnTag)
        assertThat(bounds(OtherProfilesColumnTag).top).isAtLeast(first.bottom)
        assertThat(first.width).isEqualTo(600.dp)
    }

    @Test
    @Config(qualifiers = Tablet)
    fun `on an expanded screen the current profile is at the start and the others beside it`() {
        show(ProfilesPageState(current(), listOf(sacha, tester)), width = WindowWidthClass.Expanded)

        val first = bounds(CurrentProfileColumnTag)
        val second = bounds(OtherProfilesColumnTag)
        assertThat(second.left).isAtLeast(first.right)
        assertThat(second.top).isEqualTo(first.top)
        assertThat(second.width).isEqualTo(first.width)
        // Both in a content no wider than 1040 dp, centred.
        assertThat(second.right - first.left).isAtMost(1040.dp)
        assertThat(first.left).isEqualTo(1280.dp - second.right)
        // Each column ends with its own button.
        assertThat(bounds(ProfilesPageRemoveTag).right).isAtMost(first.right)
        assertThat(bounds(ProfilesPageAddTag).left).isAtLeast(second.left)
    }

    @Test
    @Config(qualifiers = Tablet)
    fun `on an expanded screen the current profile's rows are in the tablet canvas's order`() {
        show(ProfilesPageState(current(), listOf(sacha, tester)), width = WindowWidthClass.Expanded)

        // The username first there, as with one profile: the server leads only in the phone's single column.
        assertThat(
            listOf(
                top(text(R.string.profiles_current)),
                top(text(R.string.account_username)),
                top(text(R.string.account_email)),
                top(text(R.string.account_server)),
                top(text(R.string.account_server_version)),
                bounds(ProfilesPageRemoveTag).top,
            ),
        ).isInStrictOrder()
        assertNoAppVersion()
    }

    @Test
    @Config(qualifiers = Tablet)
    fun `on an expanded screen one profile keeps the single centred column`() {
        show(ProfilesPageState(current()), width = WindowWidthClass.Expanded)

        val column = bounds(CurrentProfileColumnTag)
        assertThat(column.width).isEqualTo(600.dp)
        assertThat(column.left).isEqualTo(1280.dp - column.right)
        assertThat(bounds(ProfilesPageAddTag).top).isLessThan(bounds(ProfilesPageRemoveTag).top)
    }

    @Test
    fun `a current profile that waits for a sign-in shows what the registry knows, no error and no retry`() {
        show(
            ProfilesPageState(current(signInNeeded = true)),
            details = AccountUiState.Failed(ApiError.ServerUnreachable),
        )

        listOf("Camille Lecteur", "camille · BookOrbit", "camille@example.org", "https://livres.example.org")
            .forEach { compose.onNodeWithText(it).assertExists() }
        compose.onNodeWithText(text(CoreR.string.sign_in_needed)).assertExists()
        compose.onNodeWithText(text(R.string.account_error_unreachable)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.account_error_unexpected)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.account_retry)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.account_server_version)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.profiles_remove)).assertExists()
    }

    @Test
    fun `a current profile that waits for a sign-in shows no server version, even one loaded before`() {
        show(ProfilesPageState(current(signInNeeded = true)))

        compose.onNodeWithText(text(R.string.account_server_version)).assertDoesNotExist()
        compose.onNodeWithText("v3.8.0").assertDoesNotExist()
    }

    @Test
    fun `a current profile that waits for a sign-in signs in again from under who it is, at the full width`() {
        show(ProfilesPageState(current(signInNeeded = true), listOf(sacha)))

        val button = compose.onNodeWithTag(ProfilesPageSignInAgainTag)
        button.assertTextEquals(text(CoreR.string.sign_in_again)).assertHasClickAction()
        val placed = button.getUnclippedBoundsInRoot()
        assertThat(placed.top).isAtLeast(bounds(CurrentProfileHeaderTag).bottom)
        assertThat(placed.bottom).isAtMost(top(text(R.string.account_server)))
        assertThat(placed.width).isEqualTo(bounds(CurrentProfileColumnTag).width - 32.dp)
        button.performClick()

        assertThat(signedInAgain).containsExactly("p0")
    }

    @Test
    fun `the only profile, when it waits for a sign-in, signs in again from its page too`() {
        show(ProfilesPageState(current(signInNeeded = true)))

        compose.onNodeWithTag(ProfilesPageSignInAgainTag).performClick()

        assertThat(signedInAgain).containsExactly("p0")
    }

    @Test
    @Config(qualifiers = Tablet)
    fun `on an expanded screen Sign in again is at the start of its column, as wide as its label needs`() {
        show(ProfilesPageState(current(signInNeeded = true), listOf(sacha)), width = WindowWidthClass.Expanded)

        val column = bounds(CurrentProfileColumnTag)
        val placed = bounds(ProfilesPageSignInAgainTag)
        assertThat(placed.left).isEqualTo(column.left + 16.dp)
        assertThat(placed.width).isLessThan(column.width / 2)
        assertThat(placed.top).isAtLeast(bounds(CurrentProfileHeaderTag).bottom)
        assertThat(placed.bottom).isAtMost(top(text(R.string.account_username)))
        compose.onNodeWithTag(ProfilesPageSignInAgainTag).performClick()
        assertThat(signedInAgain).containsExactly("p0")
    }

    @Test
    fun `a current profile that needs no sign-in is not offered to sign in again`() {
        show(ProfilesPageState(current(), listOf(sacha, tester)))

        compose.onNodeWithTag(ProfilesPageSignInAgainTag).assertDoesNotExist()
        compose.onNodeWithText(text(CoreR.string.sign_in_again)).assertDoesNotExist()
    }

    @Test
    fun `a server that cannot be reached is said, announced, and may be asked again`() {
        show(ProfilesPageState(current()), details = AccountUiState.Failed(ApiError.ServerUnreachable))

        compose.onNodeWithText(text(R.string.account_error_unreachable), useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        compose.onNodeWithText(text(R.string.account_retry)).performScrollTo().performClick()

        assertThat(retried).isEqualTo(1)
        // What the registry knows is shown all the same.
        compose.onNodeWithText("https://livres.example.org").assertExists()
        compose.onNodeWithText(text(R.string.account_server_version)).assertDoesNotExist()
    }

    @Test
    fun `a tap on another profile's row switches to it`() {
        show(ProfilesPageState(current(), listOf(sacha, tester)))

        compose.onNodeWithTag(ProfileRowTagPrefix + "p2").performScrollTo().performClick()

        assertThat(switched).containsExactly("p2")
    }

    @Test
    fun `the menu of a profile that needs no sign-in only removes it`() {
        show(ProfilesPageState(current(), listOf(sacha, tester)))

        compose.onNodeWithTag(ProfileActionsTagPrefix + "p1").performScrollTo().performClick()

        compose.onNodeWithText(text(CoreR.string.sign_in_again)).assertDoesNotExist()
        compose.onNodeWithTag(ProfileRemoveTag).performClick()
        assertThat(removedOthers).containsExactly("p1")
        assertThat(removedCurrent).isEqualTo(0)
        // Choosing closed the menu.
        compose.onNodeWithTag(ProfileRemoveTag).assertDoesNotExist()
    }

    @Test
    fun `the menu of a profile that waits for a sign-in also signs it in again`() {
        show(ProfilesPageState(current(), listOf(sacha, tester)))

        compose.onNodeWithTag(ProfileActionsTagPrefix + "p2").performScrollTo().performClick()

        compose.onNodeWithTag(ProfileRemoveTag).assertIsDisplayed()
        compose.onNodeWithText(text(CoreR.string.sign_in_again)).performClick()
        assertThat(signedInAgain).containsExactly("p2")
        assertThat(removedOthers).isEmpty()
        compose.onNodeWithTag(ProfileSignInAgainTag).assertDoesNotExist()
    }

    @Test
    fun `the button at the end of a row names its profile`() {
        show(ProfilesPageState(current(), listOf(sacha, tester)))

        compose.onNodeWithTag(ProfileActionsTagPrefix + "p1")
            .assertContentDescriptionEquals("More actions for sacha, BookOrbit")
        compose.onNodeWithContentDescription("More actions for test, BookOrbit").assertExists()
    }

    @Test
    @Config(qualifiers = "fr-$Phone")
    fun `in French, the button at the end of a row names its profile`() {
        show(ProfilesPageState(current(), listOf(sacha)))

        compose.onNodeWithTag(ProfileActionsTagPrefix + "p1")
            .assertContentDescriptionEquals("Autres actions pour sacha, BookOrbit")
    }

    @Test
    fun `the title and the two labels are headings`() {
        show(ProfilesPageState(current(), listOf(sacha)))

        listOf(R.string.profiles_title, R.string.profiles_current, R.string.profiles_others).forEach {
            compose.onNodeWithText(text(it)).assert(heading)
        }
    }

    @Test
    fun `a row is read once, as one thing, and so is who the current profile is`() {
        show(ProfilesPageState(current(), listOf(sacha, tester)))

        compose.onNodeWithTag(ProfileRowTagPrefix + "p1")
            .assertContentDescriptionEquals("sacha · BookOrbit, bd.example.org")
        compose.onNodeWithTag(ProfileRowTagPrefix + "p2")
            .assertContentDescriptionEquals("test · BookOrbit, livres.example.org, sign-in needed")
        // Its texts are not nodes of their own for a screen reader: only the row is.
        compose.onNodeWithText("sacha · BookOrbit")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription))
        compose.onNodeWithTag(CurrentProfileHeaderTag)
            .assert(hasText("Camille Lecteur") and hasText("camille · BookOrbit"))
    }

    @Test
    fun `back reports the click`() {
        show(ProfilesPageState(current()))

        compose.onNodeWithContentDescription(text(R.string.account_back)).performClick()

        assertThat(backs).isEqualTo(1)
    }

    @Test
    fun `nothing of a profile is shown while there is no current one`() {
        show(ProfilesPageState(current = null, others = listOf(sacha)))

        compose.onNodeWithText(text(R.string.profiles_title)).assertIsDisplayed()
        compose.onNodeWithTag(ProfileRowTagPrefix + "p1").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.profiles_remove)).assertDoesNotExist()
    }
}
