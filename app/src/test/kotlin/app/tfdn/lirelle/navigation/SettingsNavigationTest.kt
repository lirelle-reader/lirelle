package app.tfdn.lirelle.navigation

import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.tfdn.lirelle.MainActivity
import app.tfdn.lirelle.R
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.designsystem.LocalWindowWidthClass
import app.tfdn.lirelle.core.designsystem.WindowWidthClass
import app.tfdn.lirelle.core.session.SessionState
import app.tfdn.lirelle.core.session.Tokens
import app.tfdn.lirelle.core.session.UserProfile
import app.tfdn.lirelle.core.testing.bookOrbitSession
import app.tfdn.lirelle.core.testing.save
import app.tfdn.lirelle.di.AccountDataOnDevice
import app.tfdn.lirelle.feature.reader.settings.ReadingPreviewTag
import app.tfdn.lirelle.shell.Shell
import app.tfdn.lirelle.shell.stubHome
import app.tfdn.lirelle.shell.stubLibrary
import app.tfdn.lirelle.shell.stubSearch
import app.tfdn.lirelle.signout.FakeAndroidKeystore
import app.tfdn.lirelle.until
import dagger.hilt.android.EntryPointAccessors
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import app.tfdn.lirelle.feature.comics.R as ComicsR
import app.tfdn.lirelle.feature.player.R as PlayerR
import app.tfdn.lirelle.feature.settings.R as SettingsR

private const val Version = "0.1.42"

/** The row of the reading engine in the list of licences: a component the app bundles, so near the top. */
private const val FoliateRowTag = "licence-row-bundled:foliate-js"

private const val LoadMs = 5_000L

/**
 * The settings and About under More, with the real pages and the real Hilt graph, in the shell: the activity is
 * the app's own, for its view models, and the shell takes the place of what it showed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class SettingsNavigationTest {

    @get:Rule(order = 0)
    val keystore = FakeAndroidKeystore()

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    private fun text(id: Int): String = compose.activity.getString(id)

    /**
     * The pages are the signed-in app's: their view models take the objects of the open profile, so there
     * must be one, opened as a sign-in opens it.
     */
    private fun signIn() = runBlocking {
        val device = EntryPointAccessors.fromApplication(compose.activity.application, AccountDataOnDevice::class.java)
        val tokens = Tokens("access", Instant.MAX, "refresh", Instant.MAX)
        val user = UserProfile("1", "reader", "Ann Reader", email = null)
        device.sessions().save(bookOrbitSession("https://books.example", sessionId = 1, tokens = tokens, user = user))
        until("the profile signed in to is shown") { device.openProfiles().shown.value.state is SessionState.SignedIn }
    }

    private fun show(width: WindowWidthClass = WindowWidthClass.Compact) {
        signIn()
        val modes = BookModes(onRead = {}, onListen = { _, _ -> }, onReadComic = {})
        // Through the activity, not the rule: the rule refuses an activity that has set its content already.
        compose.runOnUiThread {
            compose.activity.setContent {
                LirelleTheme {
                    CompositionLocalProvider(LocalWindowWidthClass provides width) {
                        Shell(
                            account = {},
                            home = { stubHome(it) },
                            library = { stubLibrary(it) },
                            search = { stubSearch(it) },
                            more = { moreDestinations(it, modes, Version) },
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun pressBack() {
        // What a tap changed is drawn first: the screen shown is what takes the system's Back.
        compose.waitForIdle()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun tap(id: Int) = compose.onNodeWithText(text(id)).performClick()

    private fun openMore() = compose.onNodeWithTag("nav-more").performClick()

    private fun assertShown(id: Int) = compose.onNodeWithText(text(id)).assertIsDisplayed()

    private fun assertGone(id: Int) = compose.onNodeWithText(text(id)).assertDoesNotExist()

    private fun assertMoreShown() {
        assertShown(R.string.more_account)
        assertShown(R.string.more_about)
    }

    private fun assertSettingsListShown() {
        assertShown(SettingsR.string.settings_reading_about)
        assertShown(SettingsR.string.settings_readers_note)
        assertGone(R.string.more_account)
    }

    private fun waitForText(id: Int) = compose.waitUntil(LoadMs) {
        compose.onAllNodesWithText(text(id)).fetchSemanticsNodes().isNotEmpty()
    }

    /** The reading page shows its preview once the settings are read. */
    private fun assertReadingShown() {
        compose.waitUntil(LoadMs) { compose.onAllNodesWithTag(ReadingPreviewTag).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(ReadingPreviewTag).assertIsDisplayed()
    }

    @Test
    fun `Settings opens Reading, and Back returns to the list, then to More`() {
        show()
        openMore()

        tap(R.string.more_settings)
        assertSettingsListShown()

        tap(SettingsR.string.settings_reading)
        assertReadingShown()
        assertGone(SettingsR.string.settings_readers_note)

        pressBack()
        assertSettingsListShown()

        pressBack()
        assertMoreShown()
        compose.onNodeWithTag("nav-more").assertIsDisplayed()
    }

    /**
     * A row of the list opens its page alone, and the system's Back returns to the list.
     *
     * @param row a text of the row.
     * @param onPage a text only the page shows, once its settings are read.
     */
    private fun assertOpensAndBackReturns(row: Int, onPage: Int) {
        show()
        openMore()
        tap(R.string.more_settings)
        assertSettingsListShown()

        tap(row)
        waitForText(onPage)
        assertShown(onPage)
        assertGone(SettingsR.string.settings_readers_note)

        pressBack()
        assertSettingsListShown()
        assertGone(onPage)
    }

    @Test
    fun `Settings opens Comics, and Back returns to the list`() =
        assertOpensAndBackReturns(SettingsR.string.settings_comics_about, ComicsR.string.comics_direction_where)

    @Test
    fun `Settings opens Player, and Back returns to the list`() =
        assertOpensAndBackReturns(SettingsR.string.settings_player_about, PlayerR.string.player_settings_skip_back)

    @Test
    fun `Settings opens Downloads, and Back returns to the list`() =
        assertOpensAndBackReturns(SettingsR.string.settings_downloads_about, R.string.more_downloads_metered)

    @Test
    fun `the row of Appearance tells the app's theme`() {
        show()
        openMore()

        tap(R.string.more_settings)

        waitForText(SettingsR.string.settings_appearance_theme_system)
        assertShown(SettingsR.string.settings_appearance_theme_system)
    }

    @Test
    fun `About shows the version and leads to the licences, to the text of one, and back`() {
        show()
        openMore()

        tap(R.string.more_about)
        assertShown(SettingsR.string.about_what)
        compose.onNodeWithText(Version, substring = true).assertIsDisplayed()

        tap(SettingsR.string.about_licences)
        assertShown(SettingsR.string.licences_hint)
        // The list generated at build time, read from the app's resources.
        compose.waitUntil(LoadMs) { compose.onAllNodesWithTag(FoliateRowTag).fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithTag(FoliateRowTag).performClick()
        compose.onNodeWithText("Copyright (c) 2022 John Factotum", substring = true).assertIsDisplayed()
        assertGone(SettingsR.string.licences_hint)

        pressBack()
        assertShown(SettingsR.string.licences_hint)
        pressBack()
        assertShown(SettingsR.string.about_what)
        pressBack()
        assertMoreShown()
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun `on a wide window Settings shows its list and Appearance together`() {
        show(WindowWidthClass.Expanded)
        openMore()

        tap(R.string.more_settings)

        // The list, with its rows and the note at its foot.
        assertShown(SettingsR.string.settings_reading_about)
        assertShown(SettingsR.string.settings_readers_note)
        // The page of Appearance beside it.
        assertShown(SettingsR.string.appearance_app_theme)

        pressBack()
        assertMoreShown()
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun `on a wide window a row puts its page beside the list`() {
        show(WindowWidthClass.Expanded)
        openMore()
        tap(R.string.more_settings)

        compose.onNodeWithText(text(SettingsR.string.settings_reading_about)).performClick()

        assertReadingShown()
        assertShown(SettingsR.string.settings_reading_about)
        assertGone(SettingsR.string.appearance_app_theme)
    }
}
