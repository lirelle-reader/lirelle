package app.tfdn.lirelle.feature.account

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.network.AuthRepository
import app.tfdn.lirelle.core.session.Credential
import app.tfdn.lirelle.core.session.ProfileStore
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.core.session.SignOutReason
import app.tfdn.lirelle.core.session.profile.FileProfileAvatars
import app.tfdn.lirelle.core.testing.FakeAuthRepository
import app.tfdn.lirelle.core.testing.MainDispatcherRule
import app.tfdn.lirelle.feature.account.switcher.FakeProfiles
import app.tfdn.lirelle.feature.account.switcher.komgaProfile
import app.tfdn.lirelle.feature.account.switcher.profile
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Robolectric for the texts of a row: the name of the kind of server is a resource. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ProfilesPageViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    @get:Rule
    val folder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val camille = profile("p0", "camille", "https://livres.example.org", email = "camille@example.org")
    private val sacha = profile("p1", "sacha", "https://bd.example.org:8443")
    private val tester = profile("p2", "test", "http://nas.local/books/", signInNeeded = true)
    private val komga = komgaProfile("p3", "ann@example.com", "https://bd.example.org")
    private val auth = FakeAuthRepository()

    /** A view model whose state is followed, as the page follows it, for the length of the test. */
    private fun TestScope.followed(store: FakeProfiles): ProfilesPageViewModel {
        val avatars = FileProfileAvatars(folder.newFolder(), UnconfinedTestDispatcher(testScheduler))
        val viewModel = ProfilesPageViewModel(store, auth, avatars, context)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.state.collect {} }
        return viewModel
    }

    @Test
    fun `with one profile there is the current one, as the registry knows it, and no other`() = runTest {
        val state = followed(FakeProfiles(camille)).state.value

        val current = checkNotNull(state.current)
        assertThat(current.row.line).isEqualTo("camille · BookOrbit")
        assertThat(current.name).isEqualTo("Name of camille")
        assertThat(current.email).isEqualTo("camille@example.org")
        assertThat(current.serverUrl).isEqualTo("https://livres.example.org")
        assertThat(state.others).isEmpty()
    }

    @Test
    fun `the other profiles follow in order of use, with their marks`() = runTest {
        val state = followed(FakeProfiles(camille, sacha, tester)).state.value

        assertThat(state.current?.row?.id).isEqualTo("p0")
        assertThat(state.others.map { it.id }).containsExactly("p1", "p2").inOrder()
        assertThat(state.others.map { it.signInNeeded }).containsExactly(false, true).inOrder()
        assertThat(state.others.map { it.current }).containsExactly(false, false)
    }

    @Test
    fun `a profile is told to sign in with the user's own key only when its key was pasted`() = runTest {
        val made = komga.copy(id = "p4", kind = ServerKind.Kavita)
        val key = made.session.credential as Credential.ApiKey
        val pasted = made.copy(id = "p5", session = made.session.copy(credential = key.copy(ownedByApp = false)))

        val state = followed(FakeProfiles(camille, komga, made, pasted)).state.value

        assertThat(state.others.map { it.line })
            .containsExactly("ann@example.com · Komga", "ann@example.com · Kavita", "ann@example.com · Kavita")
        assertThat(state.others.map { it.usersOwnKey }).containsExactly(false, false, true).inOrder()
        assertThat(state.current?.row?.usersOwnKey).isFalse()
    }

    @Test
    fun `the profiles are there at once, before anything follows them`() {
        val store = FakeProfiles(camille, sacha)
        val state = ProfilesPageViewModel(store, auth, FileProfileAvatars(folder.newFolder()), context).state.value

        assertThat(state.current?.row?.id).isEqualTo("p0")
        assertThat(state.others.map { it.id }).containsExactly("p1")
    }

    @Test
    fun `the current profile that waits for a sign-in is shown as the registry keeps it`() = runTest {
        val state = followed(FakeProfiles(tester, camille)).state.value

        val current = checkNotNull(state.current)
        assertThat(current.row.signInNeeded).isTrue()
        assertThat(current.name).isEqualTo("Name of test")
        assertThat(current.serverUrl).isEqualTo("http://nas.local/books/")
    }

    @Test
    fun `nobody signed in gives no current profile`() = runTest {
        assertThat(followed(FakeProfiles()).state.value).isEqualTo(ProfilesPageState())
    }

    @Test
    fun `switching asks the registry, and the page follows`() = runTest {
        val store = FakeProfiles(camille, sacha, tester)
        val viewModel = followed(store)

        viewModel.switchTo("p1")

        assertThat(store.current.value).isEqualTo(sacha)
        assertThat(viewModel.state.value.current?.row?.id).isEqualTo("p1")
        assertThat(viewModel.state.value.others.map { it.id }).containsExactly("p0", "p2").inOrder()
    }

    @Test
    fun `asking to remove a profile that sleeps only asks`() = runTest {
        val store = FakeProfiles(camille, sacha)
        val viewModel = followed(store)
        assertThat(viewModel.removal.value).isNull()

        viewModel.onAskRemove("p1")

        assertThat(viewModel.removal.value).isEqualTo("p1")
        assertThat(store.removals).isEmpty()
    }

    @Test
    fun `cancelling keeps the profile`() = runTest {
        val store = FakeProfiles(camille, sacha)
        val viewModel = followed(store)
        viewModel.onAskRemove("p1")

        viewModel.onDismissRemove()
        viewModel.onRemove()

        assertThat(viewModel.removal.value).isNull()
        assertThat(store.removals).isEmpty()
    }

    @Test
    fun `confirming removes the sleeping profile from the registry, and the current one is untouched`() = runTest {
        val store = FakeProfiles(camille, sacha, tester)
        val viewModel = followed(store)
        viewModel.onAskRemove("p1")

        viewModel.onRemove()

        assertThat(store.removals).containsExactly("p1" to SignOutReason.UserRequest)
        assertThat(viewModel.removal.value).isNull()
        assertThat(store.current.value).isEqualTo(camille)
        assertThat(viewModel.state.value.others.map { it.id }).containsExactly("p2")
    }

    @Test
    fun `a sleeping profile removed hands its credential to its kind, once it is removed`() = runTest {
        // One log for both, written as each is asked: it tells which came first.
        val events = mutableListOf<String>()
        val store = FakeProfiles(camille, komga, sacha)
        val logged = object : ProfileStore by store {
            override suspend fun removeSleeping(id: String): Boolean =
                store.removeSleeping(id).also { removed -> if (removed) events += "removed $id" }
        }
        val ending = object : AuthRepository by auth {
            override suspend fun endCredential(kind: ServerKind, serverUrl: String, credential: Credential) {
                events += "endCredential $kind $serverUrl $credential"
                auth.endCredential(kind, serverUrl, credential)
            }
        }
        val avatars = FileProfileAvatars(folder.newFolder(), UnconfinedTestDispatcher(testScheduler))
        val viewModel = ProfilesPageViewModel(logged, ending, avatars, context)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.state.collect {} }
        auth.gate = CompletableDeferred()
        // Its row says its kind: the confirmation says what its kind does with its credential.
        assertThat(viewModel.state.value.others.first { it.id == "p3" }.kind).isEqualTo(ServerKind.Komga)
        viewModel.onAskRemove("p3")

        viewModel.onRemove()

        // Removed before its server is asked anything: a server that does not answer keeps nothing here.
        assertThat(events).containsExactly(
            "removed p3",
            "endCredential Komga https://bd.example.org ApiKey(key=<redacted>, keyId=key-id-p3)",
        ).inOrder()
        assertThat(store.removals).containsExactly("p3" to SignOutReason.UserRequest)
        auth.gate?.complete(Unit)
    }

    /** BookOrbit sends nothing for it, as before: its source is asked, and ends nothing. */
    @Test
    fun `a sleeping BookOrbit profile removed is handed to its kind too`() = runTest {
        val store = FakeProfiles(camille, sacha)
        val viewModel = followed(store)
        viewModel.onAskRemove("p1")

        viewModel.onRemove()

        assertThat(auth.calls.single()).startsWith("endCredential BookOrbit https://bd.example.org:8443 ")
    }

    @Test
    fun `nothing is ended for a profile that was not removed`() = runTest {
        val store = FakeProfiles(camille, komga)
        val viewModel = followed(store)
        viewModel.onAskRemove("p3")
        val held = CompletableDeferred<Unit>()
        store.gate = held

        viewModel.onRemove()
        store.switchTo("p3")
        held.complete(Unit)

        assertThat(store.removals).isEmpty()
        assertThat(auth.calls).isEmpty()
    }

    @Test
    fun `a second tap on Remove removes once`() = runTest {
        val store = FakeProfiles(camille, sacha, tester)
        val viewModel = followed(store)
        viewModel.onAskRemove("p1")

        viewModel.onRemove()
        viewModel.onRemove()

        assertThat(store.removals).hasSize(1)
    }

    @Test
    fun `a profile that disappeared meanwhile closes the question, and nothing is removed`() = runTest {
        val store = FakeProfiles(camille, sacha)
        val viewModel = followed(store)
        viewModel.onAskRemove("p1")

        store.profiles.value = listOf(camille)

        assertThat(viewModel.removal.value).isNull()
        viewModel.onRemove()
        assertThat(store.removals).isEmpty()
    }

    @Test
    fun `a profile that became the current one meanwhile is not removed from here`() = runTest {
        val store = FakeProfiles(camille, sacha)
        val viewModel = followed(store)
        viewModel.onAskRemove("p1")

        store.switchTo("p1")

        assertThat(viewModel.removal.value).isNull()
        viewModel.onRemove()
        assertThat(store.removals).isEmpty()
    }

    @Test
    fun `a switch that lands after the answer leaves the profile, by then the current one, where it is`() = runTest {
        val store = FakeProfiles(camille, sacha)
        val viewModel = followed(store)
        viewModel.onAskRemove("p1")
        // The removal waits behind the switch, as in the registry: it sleeps when the answer is looked at.
        val held = CompletableDeferred<Unit>()
        store.gate = held

        viewModel.onRemove()
        store.switchTo("p1")
        held.complete(Unit)

        assertThat(store.removals).isEmpty()
        assertThat(store.current.value).isEqualTo(sacha)
        assertThat(store.profiles.value).containsExactly(sacha, camille).inOrder()
    }
}
