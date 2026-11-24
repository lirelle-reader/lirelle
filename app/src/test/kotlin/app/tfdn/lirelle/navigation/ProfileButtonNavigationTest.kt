package app.tfdn.lirelle.navigation

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.composable
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.designsystem.LocalWindowWidthClass
import app.tfdn.lirelle.core.designsystem.WindowWidthClass
import app.tfdn.lirelle.core.session.Credential
import app.tfdn.lirelle.core.session.Profile
import app.tfdn.lirelle.core.session.ProfileStore
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.core.session.Tokens
import app.tfdn.lirelle.core.session.UserProfile
import app.tfdn.lirelle.core.session.profile.FileProfileAvatars
import app.tfdn.lirelle.core.testing.bookOrbitSession
import app.tfdn.lirelle.core.ui.GripSheetTag
import app.tfdn.lirelle.feature.account.switcher.ProfileButtonTag
import app.tfdn.lirelle.feature.account.switcher.ProfileRowTagPrefix
import app.tfdn.lirelle.feature.account.switcher.ProfilesAddTag
import app.tfdn.lirelle.feature.account.switcher.ProfilesManageTag
import app.tfdn.lirelle.feature.account.switcher.ProfilesMenuTag
import app.tfdn.lirelle.feature.account.switcher.ProfilesSwitcherViewModel
import app.tfdn.lirelle.shell.Shell
import app.tfdn.lirelle.until
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val Phone = "w411dp-h891dp"
private const val Tablet = "w1280dp-h800dp"

private fun profile(id: String, username: String) = Profile(
    id = id,
    kind = ServerKind.BookOrbit,
    session = bookOrbitSession(
        serverUrl = "https://livres.example.org",
        sessionId = 1,
        tokens = Tokens("access-$id", Instant.MAX, "refresh-$id", Instant.MAX),
        user = UserProfile("1", username, "Name of $username", email = null),
    ),
)

/** The registry as the profile button reads it: the profiles listed, and the switch, which it records. */
private class ListedProfiles(vararg listed: Profile) : ProfileStore {
    override val profiles = MutableStateFlow(listed.toList())
    override val current = MutableStateFlow(listed.firstOrNull())
    val switchedTo = mutableListOf<String>()

    override suspend fun switchTo(id: String) {
        switchedTo += id
    }

    override suspend fun load() = Unit

    override suspend fun profile(id: String): Profile? = notAsked()

    override suspend fun updateCredential(id: String, credential: Credential, replacing: Credential): Profile? =
        notAsked()

    override suspend fun updateUser(id: String, user: UserProfile): Profile? = notAsked()

    override suspend fun needSignIn(id: String, refused: Credential): Profile? = notAsked()

    override suspend fun removeSleeping(id: String): Boolean = notAsked()

    override suspend fun beingRemoved(): List<String> = notAsked()

    override suspend fun beingMoved(): List<String> = notAsked()

    override suspend fun forget(id: String): Unit = notAsked()

    override suspend fun listedIds(): Set<String>? = notAsked()

    private fun notAsked(): Nothing = error("The profile button only lists the profiles and switches")
}

/**
 * Where the app puts the profile button, and what it is wired to: in the title of Home and of the Library with
 * the bottom bar, where the profiles open in a sheet; at the bottom of the rail on wide screens, where they
 * open in a menu. "Add a profile" takes the shell's way into the sign-in screens, "Manage profiles" opens the
 * page of More, a profile chosen is switched to.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = Phone)
class ProfileButtonNavigationTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val folder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val store = ListedProfiles(profile("p0", "camille"), profile("p1", "sacha"))
    private val viewModel by lazy {
        ProfilesSwitcherViewModel(store, FileProfileAvatars(folder.newFolder()), context)
    }
    private var added = 0
    private val entrances = ProfileEntrances(addProfile = { added++ }, signInAgain = {})

    private val button get() = compose.onNodeWithTag(ProfileButtonTag)

    /** The shell as the app wires it, with stand-ins for the screens: each title has its action. */
    private fun show(width: WindowWidthClass) {
        compose.setContent {
            LirelleTheme {
                CompositionLocalProvider(LocalWindowWidthClass provides width) {
                    Shell(
                        account = { Text("account-page") },
                        home = { nav ->
                            composable<HomeDestination> {
                                Text("home-root")
                                TitleProfileButton(entrances, nav) { viewModel }
                            }
                        },
                        library = { nav ->
                            composable<LibraryDestination> {
                                Text("library-root")
                                TitleProfileButton(entrances, nav) { viewModel }
                            }
                        },
                        search = { composable<SearchDestination> { Text("search-root") } },
                        railProfiles = { onManage -> RailProfileButton(entrances, onManage, viewModel) },
                    )
                }
            }
        }
    }

    @Test
    fun `with the bottom bar the button is in the title of Home and of the Library, and opens a sheet`() {
        show(WindowWidthClass.Compact)

        button.assertIsDisplayed().assertContentDescriptionEquals("Profiles: camille, BookOrbit")
        compose.onNodeWithTag("nav-library").performClick()
        compose.onNodeWithText("library-root").assertIsDisplayed()
        button.assertIsDisplayed().performClick()

        compose.onNodeWithTag(GripSheetTag).assertIsDisplayed()
        compose.onNodeWithTag(ProfileRowTagPrefix + "p1").assert(hasAnyAncestor(hasTestTag(GripSheetTag)))
        compose.onNodeWithTag(ProfilesMenuTag).assertDoesNotExist()
    }

    @Test
    fun `the other tabs have no button`() {
        show(WindowWidthClass.Compact)

        compose.onNodeWithTag("nav-search").performClick()

        compose.onNodeWithText("search-root").assertIsDisplayed()
        button.assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = Tablet)
    fun `on a wide screen the one button is the rail's, and opens a menu`() {
        show(WindowWidthClass.Expanded)

        // One: a second, in Home's title, would make this ambiguous.
        button.assertIsDisplayed().performClick()

        compose.onNodeWithTag(ProfilesMenuTag).assertIsDisplayed()
        compose.onNodeWithTag(GripSheetTag).assertDoesNotExist()
    }

    @Test
    fun `with one profile there is no button anywhere`() {
        store.profiles.value = store.profiles.value.take(1)
        show(WindowWidthClass.Compact)

        compose.onNodeWithText("home-root").assertIsDisplayed()
        button.assertDoesNotExist()
    }

    @Test
    fun `a profile chosen in the sheet is switched to`(): Unit = runBlocking {
        show(WindowWidthClass.Compact)

        button.performClick()
        compose.onNodeWithTag(ProfileRowTagPrefix + "p1").performClick()

        until("the switch is asked of the registry") { store.switchedTo == listOf("p1") }
        compose.onNodeWithTag(GripSheetTag).assertDoesNotExist()
    }

    @Test
    fun `Add a profile takes the shell's way into the sign-in screens`() {
        show(WindowWidthClass.Compact)

        button.performClick()
        compose.onNodeWithTag(ProfilesAddTag).performClick()

        assertThat(added).isEqualTo(1)
        compose.onNodeWithTag(GripSheetTag).assertDoesNotExist()
    }

    @Test
    fun `Manage profiles, from Home's title, opens the page of More`() {
        show(WindowWidthClass.Compact)

        button.performClick()
        compose.onNodeWithTag(ProfilesManageTag).performClick()

        compose.onNodeWithText("account-page").assertIsDisplayed()
        compose.onNodeWithTag("nav-more").assertIsSelected()
        compose.onNodeWithTag(GripSheetTag).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = Tablet)
    fun `Manage profiles, from the rail, opens the page of More`() {
        show(WindowWidthClass.Expanded)

        button.performClick()
        compose.onNodeWithTag(ProfilesManageTag).performClick()

        compose.onNodeWithText("account-page").assertIsDisplayed()
        compose.onNodeWithTag("nav-more").assertIsSelected()
        compose.onNodeWithTag(ProfilesMenuTag).assertDoesNotExist()
    }
}
