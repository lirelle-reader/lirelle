package app.tfdn.lirelle.feature.home

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.library.model.HomeRow
import app.tfdn.lirelle.core.library.model.HomeRowKind
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.core.testing.sampleBook
import app.tfdn.lirelle.core.ui.Loadable
import app.tfdn.lirelle.core.ui.LocalOpenDownloads
import app.tfdn.lirelle.core.ui.NoNetworkStateTag
import app.tfdn.lirelle.core.ui.PagingErrorBarTag
import app.tfdn.lirelle.core.ui.R as CoreR
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private val Camille = ExpiredSession("p7", "camille", ServerKind.BookOrbit)
private val Komga = ExpiredSession("p8", "camille@example.org", ServerKind.Komga)

private fun rows(row: (HomeRowKind) -> Loadable<HomeRow>) = HomeUiState(HomeRowKind.entries.associateWith(row))

private val Loaded = rows { Loadable.Ready(HomeRow(it, listOf(sampleBook(it.name)), hasMore = false)) }
private val Empty = rows { Loadable.Ready(HomeRow(it, emptyList(), hasMore = false)) }
private val Loading = rows { Loadable.Loading }

/** What a profile that asks its server nothing gets from it: as without a network. */
private val AllFailed = rows { Loadable.Failed(ApiError.ServerUnreachable) }
private val PartlyFailed =
    Loaded.copy(rows = Loaded.rows + (HomeRowKind.UpNext to Loadable.Failed(ApiError.ServerUnreachable)))
private val RefreshFailed = Loaded.copy(refreshError = ApiError.ServerUnreachable)

/**
 * The notice of Home while its profile waits for a sign-in: there in every state of Home, in place of whatever
 * Home would say of a server that does not answer — the notice is why, and its button is what helps.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class HomeSignInNoticeTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val signedInAgain = mutableListOf<String>()
    private var retries = 0
    private var downloadsOpened = 0
    private var state by mutableStateOf(Loaded)

    private val notice get() = compose.onNodeWithTag(SignInNoticeTag)
    private val offlineBooks get() = compose.onNodeWithTag(SignInOfflineBooksTag)
    private val body get() = compose.onNodeWithTag(SignInNoticeBodyTag, useUnmergedTree = true)
    private val button get() = compose.onNodeWithTag(SignInAgainTag)

    private fun text(id: Int) = context.getString(id)

    /** In the shell, where a failure for lack of network is the screen that leads to the downloads. */
    private fun show(shown: HomeUiState) {
        state = shown
        compose.setContent {
            LirelleTheme {
                CompositionLocalProvider(LocalOpenDownloads provides {}) {
                    HomeContent(
                        state = state,
                        onRetry = { retries++ },
                        onRefresh = {},
                        onOpenBook = {},
                        onRead = {},
                        onListen = {},
                        onNarrate = {},
                        onSeeAll = {},
                        onSignInAgain = { signedInAgain += it },
                        onOpenDownloads = { downloadsOpened++ },
                    )
                }
            }
        }
    }

    private fun assertNoFailureIsSaid() {
        compose.onNodeWithTag(NoNetworkStateTag).assertDoesNotExist()
        compose.onNodeWithTag(PagingErrorBarTag).assertDoesNotExist()
        compose.onNodeWithText(text(CoreR.string.state_retry)).assertDoesNotExist()
        compose.onNodeWithText(text(CoreR.string.library_error_unreachable)).assertDoesNotExist()
    }

    @Test
    fun `it stands under the title and above the rows, which stay`() {
        show(Loaded.copy(expired = Camille))

        val title = compose.onNodeWithText(text(R.string.home_title)).assertIsDisplayed().getUnclippedBoundsInRoot()
        val card = notice.assertIsDisplayed().getUnclippedBoundsInRoot()
        val row = compose.onNodeWithText(text(R.string.home_row_continue)).assertIsDisplayed()
        assertThat(card.top).isAtLeast(title.bottom)
        assertThat(row.getUnclippedBoundsInRoot().top).isAtLeast(card.bottom)
        // The screen's edge padding at both sides.
        assertThat(card.left).isEqualTo(16.dp)
        assertThat(card.right).isEqualTo(411.dp - 16.dp)
    }

    @Test
    fun `it names the profile, in English`() {
        show(Loaded.copy(expired = Camille))

        compose.onNodeWithText("Sign-in needed").assertIsDisplayed()
        body.assertTextEquals(
            "The session of camille · BookOrbit has expired. Your downloaded books stay readable; " +
                "what you read will be sent once you sign in again.",
        )
        button.assertTextEquals("Sign in again")
    }

    @Test
    @Config(qualifiers = "fr-w411dp-h891dp")
    fun `it names the profile, in French`() {
        show(Loaded.copy(expired = Camille))

        compose.onNodeWithText("Reconnexion nécessaire").assertIsDisplayed()
        body.assertTextEquals(
            "La session de camille · BookOrbit a expiré. Vos livres téléchargés restent lisibles ; " +
                "ce que vous lisez sera envoyé après la reconnexion.",
        )
        button.assertTextEquals("Se reconnecter")
    }

    /** A key never expires: the server refused it, and the notice says so rather than a session that ended. */
    @Test
    fun `for a Komga profile it says its sign-in is no longer valid, in English`() {
        show(Loaded.copy(expired = Komga))

        body.assertTextEquals(
            "The sign-in of camille@example.org · Komga is no longer valid. Your downloaded books stay readable; " +
                "what you read will be sent once you sign in again.",
        )
    }

    @Test
    @Config(qualifiers = "fr-w411dp-h891dp")
    fun `for a Komga profile it says its sign-in is no longer valid, in French`() {
        show(Loaded.copy(expired = Komga))

        body.assertTextEquals(
            "La connexion de camille@example.org · Komga n'est plus valable. Vos livres téléchargés restent " +
                "lisibles ; ce que vous lisez sera envoyé après la reconnexion.",
        )
    }

    @Test
    fun `its button asks to sign in again to the profile Home is shown for`() {
        show(Loaded.copy(expired = Camille))

        button.performClick()

        assertThat(signedInAgain).containsExactly("p7")
    }

    @Test
    fun `it is there with an empty library, which is still said`() {
        show(Empty.copy(expired = Camille))

        notice.assertIsDisplayed()
        compose.onNodeWithText(text(R.string.home_empty)).assertIsDisplayed()
        assertNoFailureIsSaid()
    }

    @Test
    fun `it is there while the rows are asked for`() {
        show(Loading.copy(expired = Camille))

        notice.assertIsDisplayed()
        assertNoFailureIsSaid()
    }

    @Test
    fun `when nothing could be loaded it stands for the failure, and the way to the offline books is under it`() {
        show(AllFailed.copy(expired = Camille))

        notice.assertIsDisplayed()
        compose.onNodeWithText(text(R.string.home_title)).assertIsDisplayed()
        assertNoFailureIsSaid()
        assertThat(retries).isEqualTo(0)
        // The action of the screen shown for lack of network, by the same words.
        offlineBooks.assertIsDisplayed()
            .assertTextEquals(text(CoreR.string.no_network_open_downloads))
            .assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        val card = notice.getUnclippedBoundsInRoot()
        val action = offlineBooks.getUnclippedBoundsInRoot()
        assertThat(action.top).isAtLeast(card.bottom)
        assertThat(action.left).isEqualTo(card.left)
        assertThat(action.right).isAtMost(card.right)
        assertThat(action.height).isAtLeast(48.dp)

        offlineBooks.performClick()

        assertThat(downloadsOpened).isEqualTo(1)
        assertThat(signedInAgain).isEmpty()
    }

    @Test
    fun `an empty library has no row either, and offers the offline books above what it says`() {
        show(Empty.copy(expired = Camille))

        val action = offlineBooks.assertIsDisplayed().getUnclippedBoundsInRoot()
        val empty = compose.onNodeWithText(text(R.string.home_empty)).getUnclippedBoundsInRoot()
        assertThat(empty.top).isAtLeast(action.bottom)
    }

    @Test
    fun `with rows to show, or rows being asked for, the offline books are not offered`() {
        show(Loaded.copy(expired = Camille))

        listOf(Loaded, Loading, PartlyFailed, RefreshFailed).forEach { shown ->
            state = shown.copy(expired = Camille)
            compose.waitForIdle()
            notice.assertIsDisplayed()
            offlineBooks.assertDoesNotExist()
        }
    }

    @Test
    fun `a profile whose session is good is never offered the offline books by the notice's action`() {
        show(Loaded)

        listOf(Loaded, Empty, Loading, AllFailed, PartlyFailed, RefreshFailed).forEach { shown ->
            state = shown
            compose.waitForIdle()
            offlineBooks.assertDoesNotExist()
        }
    }

    @Test
    fun `a row that failed and a refresh that failed say nothing beside it, and the rows stay`() {
        show(PartlyFailed.copy(expired = Camille))
        notice.assertIsDisplayed()
        assertNoFailureIsSaid()
        compose.onNodeWithText(text(R.string.home_row_continue)).assertIsDisplayed()

        state = RefreshFailed.copy(expired = Camille)

        notice.assertIsDisplayed()
        assertNoFailureIsSaid()
        compose.onNodeWithText(text(R.string.home_row_continue)).assertIsDisplayed()
    }

    @Test
    fun `a profile whose session is good never shows it, whatever Home's state`() {
        show(Loaded)

        listOf(Loaded, Empty, Loading, AllFailed, PartlyFailed, RefreshFailed).forEach { shown ->
            state = shown
            compose.waitForIdle()
            notice.assertDoesNotExist()
            button.assertDoesNotExist()
        }
        // And the failure is said as it always was.
        compose.onNodeWithTag(PagingErrorBarTag).assertIsDisplayed()
        state = AllFailed
        compose.onNodeWithTag(NoNetworkStateTag).assertIsDisplayed()
    }

    @Test
    fun `it goes as soon as the profile no longer waits, and the failure it stood for is said again if it lasts`() {
        show(AllFailed.copy(expired = Camille))
        notice.assertIsDisplayed()

        state = AllFailed

        notice.assertDoesNotExist()
        compose.onNodeWithTag(NoNetworkStateTag).assertIsDisplayed()
    }

    @Test
    fun `a screen reader is told of it when it appears, finds its title as a heading and a button to press`() {
        show(Loaded)
        state = Loaded.copy(expired = Camille)

        body.assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        compose.onNodeWithText(text(CoreR.string.sign_in_needed))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        button.assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        assertThat(button.getUnclippedBoundsInRoot().height).isAtLeast(48.dp)
        // The icon says nothing: the title beside it does.
        val described = SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription)
        compose.onAllNodes(described.and(hasAnyAncestor(hasTestTag(SignInNoticeTag))), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .let { assertThat(it).isEmpty() }
    }
}
