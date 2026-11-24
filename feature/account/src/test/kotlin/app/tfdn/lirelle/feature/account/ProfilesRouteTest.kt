package app.tfdn.lirelle.feature.account

import android.content.Context
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.DownloadedBook
import app.tfdn.lirelle.core.downloads.model.FormatRecord
import app.tfdn.lirelle.core.downloads.model.FormatState
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.core.session.SignOutReason
import app.tfdn.lirelle.core.session.profile.FileProfileAvatars
import app.tfdn.lirelle.core.session.profile.ProfileSignIn
import app.tfdn.lirelle.core.testing.FakeAccountRepository
import app.tfdn.lirelle.core.testing.FakeAuthRepository
import app.tfdn.lirelle.feature.account.switcher.FakeProfiles
import app.tfdn.lirelle.feature.account.switcher.ProfileRowTagPrefix
import app.tfdn.lirelle.feature.account.switcher.profile
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The page wired to its two view models: what is asked of the registry, of the sign-out and of the ways into
 * the sign-in screens, from the page and from its confirmation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h740dp-xhdpi")
class ProfilesRouteTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val folder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val camille = profile("p0", "camille", "https://livres.example.org")
    private val sacha = profile("p1", "sacha", "https://bd.example.org")
    private val tester = profile("p2", "test", "https://livres.example.org", signInNeeded = true)
    private val store = FakeProfiles(camille, sacha, tester)
    private val auth = FakeAuthRepository()
    private val server = FakeAccountRepository()
    private val books = MutableStateFlow(emptyList<DownloadedBook>())
    private var atRisk = PendingCounts()
    private var added = 0
    private val signedInAgain = mutableListOf<String>()

    /** The mark of camille's profile as its own session has it: the open profile's view model is made in it. */
    private val signInNeeded = MutableStateFlow(false)

    /** Whether the page is on screen: the sign-in screens take its place, and it is disposed meanwhile. */
    private var onScreen by mutableStateOf(true)

    private val account by lazy {
        val signIn = ProfileSignIn("p0", ServerKind.BookOrbit, signInNeeded) { "camille" }
        AccountViewModel(auth, server, books, PendingWrites(read = { atRisk }, online = { false }), signIn)
    }
    private val profiles by lazy {
        // The pictures are read where they are asked for: nothing of the page waits for another thread.
        ProfilesPageViewModel(store, auth, FileProfileAvatars(folder.newFolder(), Dispatchers.Unconfined), context)
    }

    private fun text(id: Int) = context.getString(id)

    private fun show() {
        compose.setContent {
            LirelleTheme {
                if (onScreen) {
                    ProfilesRoute(
                        onBack = {},
                        onAddProfile = { added++ },
                        onSignInAgain = { signedInAgain += it },
                        account = account,
                        profiles = profiles,
                    )
                }
            }
        }
    }

    private fun book(id: String, bytes: Long) = DownloadedBook(
        bookId = BookId(id),
        formats = listOf(
            FormatRecord(DownloadFormat.Text, FormatState.Complete(bytes), emptyList(), null, null, Instant.EPOCH),
        ),
        downloadedAt = Instant.EPOCH,
        lastOpenedAt = null,
    )

    private fun until(what: String, condition: () -> Boolean) {
        // What was posted to the main thread meanwhile is run before each look.
        runCatching { compose.waitUntil { shadowOf(Looper.getMainLooper()).idle().let { condition() } } }
            .onFailure { throw AssertionError("Never happened: $what", it) }
    }

    private fun askToRemoveCurrent() {
        compose.onNodeWithTag(ProfilesPageRemoveTag).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.profiles_remove_title)).assertIsDisplayed()
    }

    private fun askToRemove(id: String) {
        compose.onNodeWithTag(ProfileActionsTagPrefix + id).performScrollTo().performClick()
        compose.onNodeWithTag(ProfileRemoveTag).performClick()
        compose.onNodeWithText(text(R.string.profiles_remove_title)).assertIsDisplayed()
    }

    private val remove get() = compose.onNodeWithText(text(R.string.profiles_remove_confirm))

    @Test
    fun `a tap on another profile's row asks the registry for the switch`() {
        show()

        compose.onNodeWithTag(ProfileRowTagPrefix + "p1").performScrollTo().performClick()

        until("sacha is the current profile") { store.current.value == sacha }
        assertThat(auth.calls).isEmpty()
    }

    @Test
    fun `Add a profile and Sign in again take the ways into the sign-in screens`() {
        show()

        compose.onNodeWithTag(ProfilesPageAddTag).performScrollTo().performClick()
        compose.onNodeWithTag(ProfileActionsTagPrefix + "p2").performScrollTo().performClick()
        compose.onNodeWithTag(ProfileSignInAgainTag).performClick()

        assertThat(added).isEqualTo(1)
        assertThat(signedInAgain).containsExactly("p2")
    }

    @Test
    fun `removing the current profile says what it takes, and Remove signs it out once`() {
        books.value = listOf(book("1", 3_000), book("2", 400))
        atRisk = PendingCounts(positions = 2, highlights = 1)
        show()
        askToRemoveCurrent()

        val lines = removalLines(context, RemovalCost.Counted(2, 3_400, atRisk))
        compose.onNodeWithText(checkNotNull(lines.kept)).assertIsDisplayed()
        compose.onNodeWithText(checkNotNull(lines.atRisk)).assertIsDisplayed()
        compose.onNodeWithTag(RemovalProfileTag).assertIsDisplayed()
        // The sign-out is still under way when the dialog has gone: the second answer finds no question.
        auth.gate = CompletableDeferred()
        remove.performClick()
        account.onRemove()

        until("the sign-out is asked") { auth.calls.isNotEmpty() }
        auth.gate?.complete(Unit)
        compose.onNodeWithText(text(R.string.profiles_remove_title)).assertDoesNotExist()
        assertThat(auth.calls).containsExactly("signOut p0")
        assertThat(store.removals).isEmpty()
    }

    @Test
    fun `the question about the current profile closes when another becomes current, and nobody is removed`() {
        show()
        askToRemoveCurrent()

        // A sign-in that ends meanwhile, in the browser: sacha's profile is the current one.
        store.signedIn(sacha)

        until("the question is closed") { account.removalQuestion.value == null }
        compose.onNodeWithText(text(R.string.profiles_remove_title)).assertDoesNotExist()
        account.onRemove()
        assertThat(auth.calls).isEmpty()
        assertThat(store.removals).isEmpty()
    }

    @Test
    fun `a question asked late, once another profile is current, is not shown for that one`() {
        show()
        store.signedIn(sacha)
        until("sacha is the current profile") { profiles.state.value.current?.row?.id == "p1" }

        // The second tap of a double tap, reaching the view model of the profile that was left.
        account.onAskRemove()

        until("the question is closed") { account.removalQuestion.value == null }
        compose.onNodeWithText(text(R.string.profiles_remove_title)).assertDoesNotExist()
        assertThat(auth.calls).isEmpty()
    }

    @Test
    fun `cancelling the removal of the current profile keeps it`() {
        show()
        askToRemoveCurrent()

        compose.onNodeWithText(text(R.string.profiles_remove_cancel)).performClick()

        compose.onNodeWithText(text(R.string.profiles_remove_title)).assertDoesNotExist()
        assertThat(auth.calls).isEmpty()
    }

    @Test
    fun `removing another profile says it without numbers, removes it from the registry and signs nobody out`() {
        show()
        askToRemove("p1")

        compose.onNodeWithText(text(R.string.profiles_remove_sleeping)).assertIsDisplayed()
        compose.onNodeWithTag(RemovalProfileTag).assertContentDescriptionEquals("sacha · BookOrbit, bd.example.org")
        remove.performClick()
        profiles.onRemove()

        until("the profile is removed") { store.removals.isNotEmpty() }
        assertThat(store.removals).containsExactly("p1" to SignOutReason.UserRequest)
        // Nobody signed out: its credential is handed to its kind, which ends it as it does (BookOrbit: not at all).
        until("its credential is handed over") { auth.calls.isNotEmpty() }
        assertThat(auth.calls)
            .containsExactly("endCredential BookOrbit https://bd.example.org ${sacha.session.credential}")
        // Its row is gone, the current profile and the other one stay.
        until("the row is gone") { profiles.state.value.others.map { it.id } == listOf("p2") }
        compose.onNodeWithTag(ProfileRowTagPrefix + "p1").assertDoesNotExist()
        compose.onNodeWithTag(ProfileRowTagPrefix + "p2").assertExists()
        compose.onNodeWithText(text(R.string.profiles_remove_title)).assertDoesNotExist()
        assertThat(store.current.value).isEqualTo(camille)
    }

    @Test
    fun `the question about a profile that disappears meanwhile closes, and nothing is removed`() {
        show()
        askToRemove("p1")

        store.profiles.value = listOf(camille, tester)

        until("the question is closed") { profiles.removal.value == null }
        compose.onNodeWithText(text(R.string.profiles_remove_title)).assertDoesNotExist()
        assertThat(store.removals).isEmpty()
        assertThat(auth.calls).isEmpty()
    }

    @Test
    fun `the page stands empty once its profile is gone, without asking anything`() {
        show()

        store.profiles.value = emptyList()
        store.current.value = null

        until("nobody is listed") { profiles.state.value == ProfilesPageState() }
        compose.onNodeWithText(text(R.string.profiles_title)).assertIsDisplayed()
        compose.onNodeWithTag(ProfilesPageRemoveTag).assertDoesNotExist()
        assertThat(auth.calls).isEmpty()
    }

    @Test
    fun `the server is asked again, once, when the profile signed in again while the page was left`() {
        val waiting = camille.copy(signInNeeded = true)
        store.profiles.value = listOf(waiting, sacha)
        store.current.value = waiting
        signInNeeded.value = true
        server.accountResult = ApiResult.Failure(ApiError.ServerUnreachable)
        show()
        until("the server was asked once") { server.calls == listOf("account") }
        compose.onNodeWithText(text(R.string.account_retry)).assertDoesNotExist()

        // "Sign in again": the sign-in screens take the page's place, and the mark falls behind them.
        onScreen = false
        compose.waitForIdle()
        server.accountResult = FakeAccountRepository().accountResult
        store.profiles.value = listOf(camille, sacha)
        store.current.value = camille
        signInNeeded.value = false
        onScreen = true

        until("the server's version is shown") { account.state.value is AccountUiState.Loaded }
        compose.onNodeWithText("v3.8.0").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.account_retry)).assertDoesNotExist()
        assertThat(server.calls).containsExactly("account", "account")
    }
}
