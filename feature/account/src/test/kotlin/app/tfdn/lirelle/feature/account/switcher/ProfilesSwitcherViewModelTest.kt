package app.tfdn.lirelle.feature.account.switcher

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.session.profile.FileProfileAvatars
import app.tfdn.lirelle.core.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
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
class ProfilesSwitcherViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    @get:Rule
    val folder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val camille = profile("p0", "camille", "https://livres.example.org")
    private val sacha = profile("p1", "sacha", "https://bd.example.org:8443")
    private val tester = profile("p2", "test", "http://nas.local/books/", signInNeeded = true)
    private val store = FakeProfiles(camille, sacha, tester)

    private fun TestScope.avatars() = FileProfileAvatars(folder.newFolder(), UnconfinedTestDispatcher(testScheduler))

    /** A view model whose state is followed, as a screen follows it, for the length of the test. */
    private fun TestScope.followed(avatars: FileProfileAvatars = avatars()): ProfilesSwitcherViewModel {
        val viewModel = ProfilesSwitcherViewModel(store, avatars, context)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.state.collect {} }
        return viewModel
    }

    @Test
    fun `the profiles are listed in order of use, each with its line`() = runTest {
        val rows = followed().state.value.rows

        assertThat(rows.map { it.id }).containsExactly("p0", "p1", "p2").inOrder()
        assertThat(rows.map { it.line })
            .containsExactly("camille · BookOrbit", "sacha · BookOrbit", "test · BookOrbit").inOrder()
        assertThat(rows.map { it.username }).containsExactly("camille", "sacha", "test").inOrder()
        assertThat(rows.map { it.kindName }.toSet()).containsExactly("BookOrbit")
    }

    @Test
    fun `the profiles are there at once, before anything follows them`() {
        val state = ProfilesSwitcherViewModel(store, FileProfileAvatars(folder.newFolder()), context).state.value

        assertThat(state.rows.map { it.id }).containsExactly("p0", "p1", "p2").inOrder()
        assertThat(state.current?.id).isEqualTo("p0")
    }

    @Test
    fun `the server is shown without its scheme, with its port or its path`() = runTest {
        val rows = followed().state.value.rows

        assertThat(rows.map { it.server })
            .containsExactly("livres.example.org", "bd.example.org:8443", "nas.local/books").inOrder()
        assertThat(serverShown("HTTPS://Livres.Example.org/")).isEqualTo("Livres.Example.org")
        assertThat(serverShown("https://x.example/a/b")).isEqualTo("x.example/a/b")
        assertThat(serverShown("x.example")).isEqualTo("x.example")
    }

    @Test
    fun `the current profile and the one that waits for a sign-in are told`() = runTest {
        val state = followed().state.value

        assertThat(state.rows.map { it.current }).containsExactly(true, false, false).inOrder()
        assertThat(state.rows.map { it.signInNeeded }).containsExactly(false, false, true).inOrder()
        assertThat(state.rows.map { it.avatar.signInNeeded }).containsExactly(false, false, true).inOrder()
        assertThat(state.current?.id).isEqualTo("p0")
        assertThat(state.severalProfiles).isTrue()
    }

    @Test
    fun `one profile is not several`() = runTest {
        store.profiles.value = listOf(camille)

        val state = followed().state.value

        assertThat(state.rows).hasSize(1)
        assertThat(state.severalProfiles).isFalse()
    }

    @Test
    fun `switching makes the other profile the current one, first in the list`() = runTest {
        val viewModel = followed()

        viewModel.switchTo("p1")

        assertThat(store.current.value?.id).isEqualTo("p1")
        val state = viewModel.state.value
        assertThat(state.rows.map { it.id }).containsExactly("p1", "p0", "p2").inOrder()
        assertThat(state.current?.id).isEqualTo("p1")
    }

    @Test
    fun `the avatar of a profile follows the picture the device keeps, also of one that sleeps`() = runTest {
        val avatars = avatars()
        val viewModel = followed(avatars)
        assertThat(viewModel.state.value.rows.map { it.avatar.picture }).containsExactly(null, null, null)

        avatars.keep("p1", "/avatar?v=1", byteArrayOf(1))

        val pictures = viewModel.state.value.rows.map { it.avatar.picture }
        assertThat(pictures[0]).isNull()
        assertThat(pictures[1]?.readBytes()).isEqualTo(byteArrayOf(1))
        assertThat(viewModel.state.value.rows[1].avatar.initial).isEqualTo("S")
    }

    @Test
    fun `a profile added or removed is followed`() = runTest {
        val viewModel = followed()

        store.profiles.value = listOf(camille, tester)

        assertThat(viewModel.state.value.rows.map { it.id }).containsExactly("p0", "p2").inOrder()
    }
}
