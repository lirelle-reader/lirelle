package app.tfdn.lirelle.feature.home

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.MonotonicClock
import app.tfdn.lirelle.core.library.ReadingChanges
import app.tfdn.lirelle.core.library.SourceCapabilities
import app.tfdn.lirelle.core.library.model.HomeRow
import app.tfdn.lirelle.core.library.model.HomeRowKind
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.core.session.profile.ProfileSignIn
import app.tfdn.lirelle.core.testing.FakeAudioRepository
import app.tfdn.lirelle.core.testing.FakeLibraryRepository
import app.tfdn.lirelle.core.testing.MainDispatcherRule
import app.tfdn.lirelle.core.testing.sampleBook
import app.tfdn.lirelle.core.ui.Loadable
import com.google.common.truth.Truth.assertThat
import java.time.Clock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/** Home knows when its profile waits for a sign-in, says whose it is, and loads again once it no longer does. */
class HomeSignInViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val fake = FakeLibraryRepository()
    private val needed = MutableStateFlow(false)
    private var username = "camille"
    private val signIn = ProfileSignIn("p7", ServerKind.BookOrbit, needed) { username }

    private fun viewModel() =
        HomeViewModel(
            fake,
            FakeAudioRepository(),
            MonotonicClock { 0 },
            Clock.systemUTC(),
            ReadingChanges(),
            signIn,
            SourceCapabilities.Everything,
        ).also { it.onShown() }

    private fun rows(vararg ids: String) =
        HomeRowKind.entries.associateWith { ApiResult.Success(HomeRow(it, ids.map(::sampleBook), hasMore = false)) }

    /** What the server answers a profile whose session it no longer takes: nothing, as without a network. */
    private val refused = HomeRowKind.entries.associateWith { ApiResult.Failure(ApiError.ServerUnreachable) }

    private fun HomeViewModel.ids(kind: HomeRowKind) =
        (state.value.rows.getValue(kind) as Loadable.Ready).value.books.map { it.id }

    @Test
    fun `a profile whose session is good has nothing to say`() = runTest(mainRule.dispatcher) {
        assertThat(viewModel().state.value.expired).isNull()
    }

    @Test
    fun `a profile that waits for a sign-in when Home is made is named, in every state of Home`() =
        runTest(mainRule.dispatcher) {
            needed.value = true
            fake.homeRows = refused
            val gate = CompletableDeferred<Unit>().also { fake.gate = it }
            val expected = ExpiredSession("p7", "camille", ServerKind.BookOrbit)

            val viewModel = viewModel()
            // While the rows are asked for.
            assertThat(viewModel.state.value.isLoading).isTrue()
            assertThat(viewModel.state.value.expired).isEqualTo(expected)

            gate.complete(Unit)
            assertThat(viewModel.state.value.allFailed).isTrue()
            assertThat(viewModel.state.value.expired).isEqualTo(expected)

            // Loading again from nothing keeps saying it; so does a pull.
            viewModel.load()
            assertThat(viewModel.state.value.expired).isEqualTo(expected)
            viewModel.refresh()
            assertThat(viewModel.state.value.expired).isEqualTo(expected)
        }

    @Test
    fun `a session that expires while Home is shown is said at once, over the rows Home has`() =
        runTest(mainRule.dispatcher) {
            fake.homeRows = rows("1")
            val viewModel = viewModel()
            val asked = fake.calls.size
            // The account was renamed on the server meanwhile: the profile is named as it is now.
            username = "camille.b"

            needed.value = true

            assertThat(viewModel.state.value.expired)
                .isEqualTo(ExpiredSession("p7", "camille.b", ServerKind.BookOrbit))
            assertThat(viewModel.ids(HomeRowKind.Continue)).containsExactly(BookId("1"))
            // Nothing is asked of a server that would refuse it.
            assertThat(fake.calls).hasSize(asked)
        }

    @Test
    fun `once the account signed in again the notice goes and Home loads what it could not`() =
        runTest(mainRule.dispatcher) {
            needed.value = true
            fake.homeRows = refused
            val viewModel = viewModel()
            assertThat(viewModel.state.value.allFailed).isTrue()
            val asked = fake.calls.size
            fake.homeRows = rows("1", "2")

            needed.value = false

            assertThat(viewModel.state.value.expired).isNull()
            assertThat(fake.calls.size - asked).isEqualTo(HomeRowKind.entries.size)
            assertThat(viewModel.state.value.failure).isNull()
            assertThat(viewModel.ids(HomeRowKind.Continue)).containsExactly(BookId("1"), BookId("2")).inOrder()
        }

    @Test
    fun `once the account signed in again the rows Home kept are loaded again, staying on screen meanwhile`() =
        runTest(mainRule.dispatcher) {
            fake.homeRows = rows("1")
            val viewModel = viewModel()
            needed.value = true
            val asked = fake.calls.size
            fake.homeRows = rows("1", "2")
            val gate = CompletableDeferred<Unit>().also { fake.gate = it }

            needed.value = false

            assertThat(viewModel.state.value.expired).isNull()
            assertThat(fake.calls.size - asked).isEqualTo(HomeRowKind.entries.size)
            // No placeholders in place of what was there.
            assertThat(viewModel.state.value.isLoading).isFalse()
            assertThat(viewModel.ids(HomeRowKind.Continue)).containsExactly(BookId("1"))

            gate.complete(Unit)
            assertThat(viewModel.ids(HomeRowKind.Continue)).containsExactly(BookId("1"), BookId("2")).inOrder()
        }

    @Test
    fun `a refresh that failed while the profile waited is not said once the account signed in again`() =
        runTest(mainRule.dispatcher) {
            fake.homeRows = rows("1")
            val viewModel = viewModel()
            needed.value = true
            // A pull while the profile waits: its server is not asked, the refresh fails as without a network.
            fake.homeRows = refused
            viewModel.refresh()
            assertThat(viewModel.state.value.refreshError).isEqualTo(ApiError.ServerUnreachable)
            fake.homeRows = rows("1", "2")
            val gate = CompletableDeferred<Unit>().also { fake.gate = it }

            needed.value = false

            // Before the reload answers: the notice hid that failure, and nothing shows it in its place.
            assertThat(viewModel.state.value.expired).isNull()
            assertThat(viewModel.state.value.refreshError).isNull()
            assertThat(viewModel.ids(HomeRowKind.Continue)).containsExactly(BookId("1"))

            gate.complete(Unit)
            assertThat(viewModel.state.value.refreshError).isNull()
            assertThat(viewModel.ids(HomeRowKind.Continue)).containsExactly(BookId("1"), BookId("2")).inOrder()
        }

    @Test
    fun `a session that was good all along reloads nothing by itself`() = runTest(mainRule.dispatcher) {
        fake.homeRows = rows("1")
        viewModel()

        assertThat(fake.calls).hasSize(HomeRowKind.entries.size)
    }
}
