package app.tfdn.lirelle.feature.account

import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.DownloadedBook
import app.tfdn.lirelle.core.downloads.model.FormatRecord
import app.tfdn.lirelle.core.downloads.model.FormatState
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.network.AccountInfo
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.core.session.profile.ProfileSignIn
import app.tfdn.lirelle.core.testing.FakeAccountRepository
import app.tfdn.lirelle.core.testing.FakeAuthRepository
import app.tfdn.lirelle.core.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class AccountViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val auth = FakeAuthRepository()
    private val account = FakeAccountRepository()
    private val books = MutableStateFlow(emptyList<DownloadedBook>())
    private var atRisk = PendingCounts()

    /** The mark of the profile the view model is made in, p0. */
    private val signInNeeded = MutableStateFlow(false)
    private val signIn = ProfileSignIn("p0", ServerKind.BookOrbit, signInNeeded) { "reader" }

    private fun viewModel() =
        AccountViewModel(auth, account, books, PendingWrites(read = { atRisk }, online = { false }), signIn)

    private fun book(id: String, bytes: Long) = DownloadedBook(
        bookId = BookId(id),
        formats = listOf(
            FormatRecord(DownloadFormat.Text, FormatState.Complete(bytes), emptyList(), null, null, Instant.EPOCH),
        ),
        downloadedAt = Instant.EPOCH,
        lastOpenedAt = null,
    )

    private val info = AccountInfo("Ann Reader", "reader", "ann@example.com", "https://books.example.com", "v3.8.0")

    @Test
    fun `loads the account`() = runTest {
        val viewModel = viewModel()

        assertThat(viewModel.state.value).isEqualTo(AccountUiState.Loaded(info))
    }

    @Test
    fun `the account is loading until the server answers`() = runTest {
        account.gate = CompletableDeferred()

        val viewModel = viewModel()
        assertThat(viewModel.state.value).isEqualTo(AccountUiState.Loading)

        account.gate?.complete(Unit)
        assertThat(viewModel.state.value).isEqualTo(AccountUiState.Loaded(info))
    }

    @Test
    fun `a retry shows loading again`() = runTest {
        account.accountResult = ApiResult.Failure(ApiError.ServerUnreachable)
        val viewModel = viewModel()
        account.gate = CompletableDeferred()

        viewModel.load()

        assertThat(viewModel.state.value).isEqualTo(AccountUiState.Loading)
    }

    @Test
    fun `a failure is shown`() = runTest {
        account.accountResult = ApiResult.Failure(ApiError.ServerUnreachable)

        assertThat(viewModel().state.value).isEqualTo(AccountUiState.Failed(ApiError.ServerUnreachable))
    }

    @Test
    fun `retry loads again`() = runTest {
        account.accountResult = ApiResult.Failure(ApiError.ServerUnreachable)
        val viewModel = viewModel()
        account.accountResult = ApiResult.Success(info)

        viewModel.load()

        assertThat(viewModel.state.value).isEqualTo(AccountUiState.Loaded(info))
        assertThat(account.calls).containsExactly("account", "account")
    }

    @Test
    fun `the account is loaded again, once, when its profile stops waiting for a sign-in`() = runTest {
        signInNeeded.value = true
        account.accountResult = ApiResult.Failure(ApiError.ServerUnreachable)
        val viewModel = viewModel()
        assertThat(viewModel.state.value).isEqualTo(AccountUiState.Failed(ApiError.ServerUnreachable))
        account.accountResult = ApiResult.Success(info)

        // Nothing shows the page: the sign-in screens are in front of it.
        signInNeeded.value = false

        assertThat(viewModel.state.value).isEqualTo(AccountUiState.Loaded(info))
        assertThat(account.calls).containsExactly("account", "account")
    }

    @Test
    fun `a profile that never waited for a sign-in is asked once`() = runTest {
        viewModel()

        assertThat(account.calls).containsExactly("account")
    }

    @Test
    fun `a session that expires asks nothing, and each sign-in asks once`() = runTest {
        viewModel()

        signInNeeded.value = true
        assertThat(account.calls).containsExactly("account")
        signInNeeded.value = false
        assertThat(account.calls).containsExactly("account", "account")
        signInNeeded.value = true
        signInNeeded.value = false
        assertThat(account.calls).containsExactly("account", "account", "account")
    }

    @Test
    fun `an answer that comes after a newer load was asked is dropped`() = runTest {
        val first = CompletableDeferred<Unit>()
        account.gate = first
        val viewModel = viewModel()
        account.gate = null

        viewModel.load()
        // What the first one would answer, late: the fake reads its answer when it is let go.
        account.accountResult = ApiResult.Failure(ApiError.ServerUnreachable)
        first.complete(Unit)

        assertThat(viewModel.state.value).isEqualTo(AccountUiState.Loaded(info))
    }

    @Test
    fun `asking to remove the profile only asks, naming it and saying there is nothing on the device`() = runTest {
        val viewModel = viewModel()
        assertThat(viewModel.removalQuestion.value).isNull()

        viewModel.onAskRemove()

        assertThat(viewModel.removalQuestion.value).isEqualTo(RemovalQuestion("p0", RemovalCost.Counted()))
        assertThat(auth.calls).isEmpty()
    }

    @Test
    fun `the question counts the downloads and what would be lost`() = runTest {
        books.value = listOf(book("1", 3_000), book("2", 400))
        atRisk = PendingCounts(positions = 2, bookmarks = 1)
        val viewModel = viewModel()

        viewModel.onAskRemove()

        assertThat(viewModel.removalQuestion.value?.cost)
            .isEqualTo(RemovalCost.Counted(books = 2, bytes = 3_400, PendingCounts(positions = 2, bookmarks = 1)))
    }

    @Test
    fun `cancelling keeps the profile`() = runTest {
        val viewModel = viewModel()
        viewModel.onAskRemove()

        viewModel.onDismissRemove()

        assertThat(viewModel.removalQuestion.value).isNull()
        assertThat(auth.calls).isEmpty()
    }

    @Test
    fun `confirming signs out of the profile the question was asked about`() = runTest {
        val viewModel = viewModel()
        viewModel.onAskRemove()

        viewModel.onRemove()

        assertThat(viewModel.removalQuestion.value).isNull()
        assertThat(auth.calls).containsExactly("signOut p0")
    }

    @Test
    fun `a second tap on Remove signs out once`() = runTest {
        val viewModel = viewModel()
        viewModel.onAskRemove()
        // The first sign-out is still under way when the second tap lands.
        auth.gate = CompletableDeferred()

        viewModel.onRemove()
        viewModel.onRemove()
        auth.gate?.complete(Unit)

        assertThat(auth.calls).containsExactly("signOut p0")
    }
}
