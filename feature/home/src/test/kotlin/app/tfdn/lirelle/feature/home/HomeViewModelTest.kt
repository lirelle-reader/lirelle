package app.tfdn.lirelle.feature.home

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.LibraryRepository
import app.tfdn.lirelle.core.library.MonotonicClock
import app.tfdn.lirelle.core.library.ReadingChanges
import app.tfdn.lirelle.core.library.SourceCapabilities
import app.tfdn.lirelle.core.library.model.AudioManifest
import app.tfdn.lirelle.core.library.model.BookSummary
import app.tfdn.lirelle.core.library.model.HomeRow
import app.tfdn.lirelle.core.library.model.HomeRowKind
import app.tfdn.lirelle.core.library.model.MediaProfile
import app.tfdn.lirelle.core.library.model.PlaybackState
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.core.session.profile.ProfileSignIn
import app.tfdn.lirelle.core.testing.FakeAudioRepository
import app.tfdn.lirelle.core.testing.FakeLibraryRepository
import app.tfdn.lirelle.core.testing.KomgaLikeCapabilities
import app.tfdn.lirelle.core.testing.MainDispatcherRule
import app.tfdn.lirelle.core.testing.sampleBook
import app.tfdn.lirelle.core.ui.Loadable
import com.google.common.truth.Truth.assertThat
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class HomeViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private var now = 0L
    private val fake = FakeLibraryRepository()
    private val changes = ReadingChanges()

    private fun row(kind: HomeRowKind, vararg ids: String, hasMore: Boolean = false) =
        ApiResult.Success(HomeRow(kind, ids.map { sampleBook(it) }, hasMore))

    private var wallNow = Instant.parse("2026-11-13T12:00:00Z")
    private val wallClock = object : Clock() {
        override fun instant(): Instant = wallNow
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }

    private val audio = FakeAudioRepository()

    /** A profile whose session is good, for all of these tests: what one that expired does is another test's. */
    private val signIn = ProfileSignIn("p1", ServerKind.BookOrbit, MutableStateFlow(false)) { "camille" }

    /** Home as it is made on screen: in front at once. */
    private fun viewModel(
        repository: LibraryRepository = fake,
        capabilities: SourceCapabilities = SourceCapabilities.Everything,
    ) = HomeViewModel(repository, audio, MonotonicClock { now }, wallClock, changes, signIn, capabilities)
        .also { it.onShown() }

    @Test
    fun `a source with fewer rows is asked only those, and Home has only those, even while they load`() =
        runTest(mainRule.dispatcher) {
            fake.homeRows = HomeRowKind.entries.associateWith { row(it, "1") }
            val gate = CompletableDeferred<Unit>().also { fake.gate = it }
            val offered = listOf(HomeRowKind.Continue, HomeRowKind.UpNext, HomeRowKind.RecentlyAdded)

            val viewModel = viewModel(capabilities = KomgaLikeCapabilities)
            assertThat(viewModel.state.value.rows.keys).containsExactlyElementsIn(offered).inOrder()

            gate.complete(Unit)
            assertThat(viewModel.state.value.rows.keys).containsExactlyElementsIn(offered).inOrder()
            assertThat(viewModel.state.value.rows.values.all { it is Loadable.Ready }).isTrue()
            assertThat(fake.calls.filter { it.startsWith("homeRow") })
                .containsExactly("homeRow Continue Row", "homeRow UpNext Row", "homeRow RecentlyAdded Row")

            viewModel.refresh()
            assertThat(fake.calls.filter { it.startsWith("homeRow WantToRead") }).isEmpty()
            assertThat(viewModel.state.value.rows.keys).containsExactlyElementsIn(offered).inOrder()
        }

    @Test
    fun `a change while Home is not in front reloads nothing, then once when it is shown again`() =
        runTest(mainRule.dispatcher) {
            fake.homeRows = HomeRowKind.entries.associateWith { row(it, "1") }
            val viewModel = viewModel()
            viewModel.onHidden()
            val before = fake.calls.size

            repeat(times = 3) { changes.changed() }
            assertThat(fake.calls).hasSize(before)

            viewModel.onShown()
            assertThat(fake.calls.size - before).isEqualTo(HomeRowKind.entries.size)
        }

    private val heardAt = Instant.parse("2026-11-13T08:00:00Z")

    private fun playback(percentage: Double) = ApiResult.Success(
        PlaybackState("aud_1", 60_000, percentage, completed = false, heardAt, version = "v2", contentVersion = "r"),
    )

    private fun manifest(bookId: String, totalMs: Long) = ApiResult.Success(
        AudioManifest(
            BookId(bookId),
            "r",
            "Book $bookId",
            emptyList(),
            emptyList(),
            false,
            emptyList(),
            emptyList(),
            totalMs,
        ),
    )

    private fun continueWith(vararg books: BookSummary) {
        val row = HomeRow(HomeRowKind.Continue, books.toList(), hasMore = false)
        fake.homeRows = fake.homeRows + (HomeRowKind.Continue to ApiResult.Success(row))
    }

    @Test
    fun `the listening of the books of the continue row fills in after the row is shown`() =
        runTest(mainRule.dispatcher) {
            continueWith(sampleBook("1"), sampleBook("2", media = MediaProfile.AudioOnly))
            audio.playbackStates = mapOf(BookId("2") to playback(25.0))
            audio.manifests = mapOf(BookId("2") to manifest("2", totalMs = 4 * 3_600_000L))
            val gate = CompletableDeferred<Unit>()
            audio.gate = gate

            val viewModel = viewModel()
            assertThat(viewModel.state.value.idsOf(HomeRowKind.Continue))
                .containsExactly(BookId("1"), BookId("2")).inOrder()
            assertThat(viewModel.state.value.listening).isEmpty()

            gate.complete(Unit)

            // Three quarters of four hours are left.
            assertThat(viewModel.state.value.listening)
                .containsExactly(BookId("2"), BookListening(timeLeftSeconds = 3 * 3_600L, listenedAt = heardAt))
            // A book without audio is not asked about.
            assertThat(audio.calls).containsExactly("playbackState 2", "manifest 2")
        }

    @Test
    fun `the playback states the row brought are not asked again`() = runTest(mainRule.dispatcher) {
        val books = listOf(
            sampleBook("2", media = MediaProfile.AudioOnly),
            sampleBook("3", media = MediaProfile.ReadAlong).copy(hasAudioFiles = true),
        )
        val brought = mapOf(BookId("2") to playback(25.0).value, BookId("3") to null)
        fake.homeRows = fake.homeRows +
            (HomeRowKind.Continue to ApiResult.Success(HomeRow(HomeRowKind.Continue, books, false, brought)))
        audio.manifests = mapOf(
            BookId("2") to manifest("2", totalMs = 4 * 3_600_000L),
            BookId("3") to manifest("3", totalMs = 3_600_000L),
        )

        val state = viewModel().state.value

        assertThat(audio.calls.filter { it.startsWith("playbackState") }).isEmpty()
        assertThat(state.listening).containsExactly(
            BookId("2"), BookListening(timeLeftSeconds = 3 * 3_600L, listenedAt = heardAt),
            BookId("3"), BookListening(timeLeftSeconds = 3_600L, listenedAt = null),
        )
    }

    @Test
    fun `a read-along book without an audio file is not asked about listening`() = runTest(mainRule.dispatcher) {
        continueWith(sampleBook("2", media = MediaProfile.ReadAlong), sampleBook("3"))

        val state = viewModel().state.value

        assertThat(audio.calls).isEmpty()
        assertThat(state.listening).isEmpty()
    }

    @Test
    fun `a book never listened to has the whole of it left`() = runTest(mainRule.dispatcher) {
        continueWith(sampleBook("2", media = MediaProfile.TextAndAudio))
        audio.manifests = mapOf(BookId("2") to manifest("2", totalMs = 90 * 60_000L))

        val state = viewModel().state.value

        assertThat(state.listening)
            .containsExactly(BookId("2"), BookListening(timeLeftSeconds = 90 * 60L, listenedAt = null))
    }

    @Test
    fun `a book listened to the end has no time left, wherever its position stopped`() = runTest(mainRule.dispatcher) {
        continueWith(sampleBook("2", media = MediaProfile.AudioOnly))
        audio.playbackStates = mapOf(BookId("2") to ApiResult.Success(playback(96.0).value.copy(completed = true)))
        audio.manifests = mapOf(BookId("2") to manifest("2", totalMs = 4 * 3_600_000L))

        val state = viewModel().state.value

        assertThat(state.listening)
            .containsExactly(BookId("2"), BookListening(timeLeftSeconds = null, listenedAt = heardAt))
    }

    @Test
    fun `a length that cannot be had leaves the time left unknown`() = runTest(mainRule.dispatcher) {
        continueWith(sampleBook("2", media = MediaProfile.AudioOnly))
        audio.playbackStates = mapOf(BookId("2") to playback(25.0))

        val state = viewModel().state.value

        assertThat(state.listening)
            .containsExactly(BookId("2"), BookListening(timeLeftSeconds = null, listenedAt = heardAt))
    }

    @Test
    fun `a playback state that cannot be had leaves the book without listening`() = runTest(mainRule.dispatcher) {
        continueWith(sampleBook("2", media = MediaProfile.AudioOnly))
        audio.playbackStateResult = ApiResult.Failure(ApiError.ServerUnreachable)
        audio.manifests = mapOf(BookId("2") to manifest("2", totalMs = 60_000L))

        assertThat(viewModel().state.value.listening).isEmpty()
    }

    @Test
    fun `the length of a book is asked once, its position each time the row comes again`() =
        runTest(mainRule.dispatcher) {
            continueWith(sampleBook("2", media = MediaProfile.AudioOnly))
            audio.playbackStates = mapOf(BookId("2") to playback(25.0))
            audio.manifests = mapOf(BookId("2") to manifest("2", totalMs = 4 * 3_600_000L))
            val viewModel = viewModel()

            audio.playbackStates = mapOf(BookId("2") to playback(50.0))
            viewModel.refresh()

            assertThat(audio.calls.count { it == "manifest 2" }).isEqualTo(1)
            assertThat(audio.calls.count { it == "playbackState 2" }).isEqualTo(2)
            assertThat(viewModel.state.value.listening.getValue(BookId("2")).timeLeftSeconds).isEqualTo(2 * 3_600L)
        }

    @Test
    fun `what is known of the listening stays while the row is loaded again`() = runTest(mainRule.dispatcher) {
        continueWith(sampleBook("2", media = MediaProfile.AudioOnly))
        audio.playbackStates = mapOf(BookId("2") to playback(25.0))
        audio.manifests = mapOf(BookId("2") to manifest("2", totalMs = 4 * 3_600_000L))
        val viewModel = viewModel()
        fake.gate = CompletableDeferred()

        viewModel.load()

        assertThat(viewModel.state.value.rows.getValue(HomeRowKind.Continue)).isEqualTo(Loadable.Loading)
        assertThat(viewModel.state.value.listening.getValue(BookId("2")).timeLeftSeconds).isEqualTo(3 * 3_600L)
    }

    private fun HomeUiState.idsOf(kind: HomeRowKind) =
        (rows.getValue(kind) as Loadable.Ready).value.books.map { it.id }

    @Test
    fun `loads the four rows`() = runTest(mainRule.dispatcher) {
        fake.homeRows = HomeRowKind.entries.associateWith { row(it, "1", "2") }

        val state = viewModel().state.value

        assertThat(state.rows.keys).containsExactlyElementsIn(HomeRowKind.entries).inOrder()
        assertThat(state.rows.values.all { it is Loadable.Ready }).isTrue()
        assertThat(fake.calls).contains("homeRow Continue Row")
    }

    @Test
    fun `a row that fails does not hide the others`() = runTest(mainRule.dispatcher) {
        fake.homeRows = fake.homeRows + (HomeRowKind.UpNext to ApiResult.Failure(ApiError.ServerUnreachable)) +
            (HomeRowKind.Continue to row(HomeRowKind.Continue, "1"))

        val state = viewModel().state.value

        assertThat(state.rows.getValue(HomeRowKind.Continue)).isInstanceOf(Loadable.Ready::class.java)
        assertThat(state.failure).isEqualTo(ApiError.ServerUnreachable)
        assertThat(state.allFailed).isFalse()
    }

    @Test
    fun `everything failing is one error`() = runTest(mainRule.dispatcher) {
        fake.homeRows = HomeRowKind.entries.associateWith { ApiResult.Failure(ApiError.ServerUnreachable) }

        assertThat(viewModel().state.value.allFailed).isTrue()
    }

    @Test
    fun `no book anywhere is the empty state`() = runTest(mainRule.dispatcher) {
        assertThat(viewModel().state.value.isEmpty).isTrue()
    }

    @Test
    fun `refresh keeps the rows, replaces those that answer and reports a failure`() = runTest(mainRule.dispatcher) {
        fake.homeRows = HomeRowKind.entries.associateWith { row(it, "1") }
        val viewModel = viewModel()
        fake.homeRows = fake.homeRows + (HomeRowKind.Continue to row(HomeRowKind.Continue, "5")) +
            (HomeRowKind.UpNext to ApiResult.Failure(ApiError.ServerUnreachable))

        viewModel.refresh()

        val state = viewModel.state.value
        assertThat(state.idsOf(HomeRowKind.Continue)).containsExactly(BookId("5"))
        assertThat(state.idsOf(HomeRowKind.UpNext)).containsExactly(BookId("1"))
        assertThat(state.refreshError).isEqualTo(ApiError.ServerUnreachable)
        assertThat(state.refreshing).isFalse()
    }

    @Test
    fun `a refresh that works forgets the failure of the one before`() = runTest(mainRule.dispatcher) {
        val rows = HomeRowKind.entries.associateWith { row(it, "1") }
        fake.homeRows = rows
        val viewModel = viewModel()
        fake.homeRows = rows + (HomeRowKind.UpNext to ApiResult.Failure(ApiError.ServerUnreachable))
        viewModel.refresh()
        fake.homeRows = rows

        viewModel.refresh()

        assertThat(viewModel.state.value.refreshError).isNull()
    }

    @Test
    fun `coming back reloads quietly, and only after two minutes`() = runTest(mainRule.dispatcher) {
        fake.homeRows = HomeRowKind.entries.associateWith { row(it, "1") }
        val viewModel = viewModel()
        val calls = fake.calls.size

        now = 119_000
        viewModel.onShown()
        assertThat(fake.calls).hasSize(calls)

        fake.homeRows = HomeRowKind.entries.associateWith { ApiResult.Failure(ApiError.ServerUnreachable) }
        now = 121_000
        viewModel.onShown()

        assertThat(fake.calls.size).isEqualTo(calls + HomeRowKind.entries.size)
        assertThat(viewModel.state.value.refreshError).isNull()
        assertThat(viewModel.state.value.rows.values.all { it is Loadable.Ready }).isTrue()
    }

    @Test
    fun `coming back with newer books shows them`() = runTest(mainRule.dispatcher) {
        fake.homeRows = HomeRowKind.entries.associateWith { row(it, "1") }
        val viewModel = viewModel()
        fake.homeRows = fake.homeRows + (HomeRowKind.Continue to row(HomeRowKind.Continue, "7"))

        now = 121_000
        viewModel.onShown()

        assertThat(viewModel.state.value.idsOf(HomeRowKind.Continue)).containsExactly(BookId("7"))
        assertThat(viewModel.state.value.refreshing).isFalse()
    }

    @Test
    fun `being shown while the first load runs asks nothing more`() = runTest(mainRule.dispatcher) {
        val gate = CompletableDeferred<Unit>()
        fake.gate = gate
        val viewModel = viewModel()
        val calls = fake.calls.size

        now = 500_000
        viewModel.onShown()

        assertThat(fake.calls).hasSize(calls)
        assertThat(viewModel.state.value.isLoading).isTrue()
        gate.complete(Unit)
        assertThat(viewModel.state.value.isLoading).isFalse()
    }

    @Test
    fun `a refresh during the first load leaves no row loading when it fails`() = runTest(mainRule.dispatcher) {
        val gate = CompletableDeferred<Unit>()
        fake.gate = gate
        val viewModel = viewModel()
        fake.homeRows = HomeRowKind.entries.associateWith { ApiResult.Failure(ApiError.ServerUnreachable) }

        viewModel.refresh()
        gate.complete(Unit)

        val state = viewModel.state.value
        assertThat(state.isLoading).isFalse()
        assertThat(state.allFailed).isTrue()
        assertThat(state.refreshing).isFalse()
    }

    @Test
    fun `coming back to a server that answers again takes the refresh error away`() = runTest(mainRule.dispatcher) {
        val rows = HomeRowKind.entries.associateWith { row(it, "1") }
        fake.homeRows = rows + (HomeRowKind.UpNext to ApiResult.Failure(ApiError.ServerUnreachable))
        val viewModel = viewModel()
        fake.homeRows = rows
        viewModel.load()
        fake.homeRows = rows + (HomeRowKind.UpNext to ApiResult.Failure(ApiError.ServerUnreachable))
        viewModel.refresh()
        assertThat(viewModel.state.value.refreshError).isEqualTo(ApiError.ServerUnreachable)
        fake.homeRows = rows

        now = 121_000
        viewModel.onShown()

        assertThat(viewModel.state.value.refreshError).isNull()
    }

    @Test
    fun `a quiet reload that fails keeps the refresh error`() = runTest(mainRule.dispatcher) {
        val rows = HomeRowKind.entries.associateWith { row(it, "1") }
        fake.homeRows = rows
        val viewModel = viewModel()
        fake.homeRows = rows + (HomeRowKind.UpNext to ApiResult.Failure(ApiError.ServerUnreachable))
        viewModel.refresh()

        now = 121_000
        viewModel.onShown()

        assertThat(viewModel.state.value.refreshError).isEqualTo(ApiError.ServerUnreachable)
    }

    @Test
    fun `a reading that changed on the server reloads quietly, even with fresh rows`() = runTest(mainRule.dispatcher) {
        fake.homeRows = HomeRowKind.entries.associateWith { row(it, "1") }
        val viewModel = viewModel()
        val calls = fake.calls.size
        fake.homeRows = fake.homeRows + (HomeRowKind.Continue to row(HomeRowKind.Continue, "7"))
        val gate = CompletableDeferred<Unit>()
        fake.gate = gate

        changes.changed()

        // On its way: the rows stay as they are, with no sign of a load or a refresh.
        assertThat(fake.calls.size).isEqualTo(calls + HomeRowKind.entries.size)
        assertThat(viewModel.state.value.isLoading).isFalse()
        assertThat(viewModel.state.value.refreshing).isFalse()
        assertThat(viewModel.state.value.idsOf(HomeRowKind.Continue)).containsExactly(BookId("1"))

        gate.complete(Unit)

        assertThat(viewModel.state.value.idsOf(HomeRowKind.Continue)).containsExactly(BookId("7"))
        assertThat(viewModel.state.value.refreshing).isFalse()
        assertThat(viewModel.state.value.refreshError).isNull()
    }

    @Test
    fun `a change from before Home was made reloads nothing`() = runTest(mainRule.dispatcher) {
        changes.changed()
        val viewModel = viewModel()
        val calls = fake.calls.size

        now = 60_000
        viewModel.onShown()

        assertThat(calls).isEqualTo(HomeRowKind.entries.size)
        assertThat(fake.calls).hasSize(calls)
    }

    @Test
    fun `a change during the first load is followed once the load ends`() = runTest(mainRule.dispatcher) {
        fake.homeRows = HomeRowKind.entries.associateWith { row(it, "1") }
        val gate = CompletableDeferred<Unit>()
        fake.gate = gate
        val viewModel = viewModel()
        val calls = fake.calls.size

        changes.changed()
        fake.homeRows = fake.homeRows + (HomeRowKind.Continue to row(HomeRowKind.Continue, "7"))

        // The load is left alone: its rows still show as loading, and nothing more is asked yet.
        assertThat(fake.calls).hasSize(calls)
        assertThat(viewModel.state.value.isLoading).isTrue()

        gate.complete(Unit)

        assertThat(fake.calls.size).isEqualTo(calls + HomeRowKind.entries.size)
        assertThat(viewModel.state.value.idsOf(HomeRowKind.Continue)).containsExactly(BookId("7"))
        assertThat(viewModel.state.value.idsOf(HomeRowKind.UpNext)).containsExactly(BookId("1"))
    }

    @Test
    fun `a change during a pull to refresh is followed once the refresh ends`() = runTest(mainRule.dispatcher) {
        fake.homeRows = HomeRowKind.entries.associateWith { row(it, "1") }
        val viewModel = viewModel()
        val gate = CompletableDeferred<Unit>()
        fake.gate = gate
        viewModel.refresh()
        val calls = fake.calls.size

        changes.changed()
        fake.homeRows = fake.homeRows + (HomeRowKind.Continue to row(HomeRowKind.Continue, "7"))

        assertThat(fake.calls).hasSize(calls)
        assertThat(viewModel.state.value.refreshing).isTrue()

        gate.complete(Unit)

        assertThat(fake.calls.size).isEqualTo(calls + HomeRowKind.entries.size)
        assertThat(viewModel.state.value.refreshing).isFalse()
        assertThat(viewModel.state.value.idsOf(HomeRowKind.Continue)).containsExactly(BookId("7"))
    }

    @Test
    fun `changes that come during a quiet reload are followed by one more`() = runTest(mainRule.dispatcher) {
        fake.homeRows = HomeRowKind.entries.associateWith { row(it, "1") }
        val viewModel = viewModel()
        val calls = fake.calls.size
        val gate = CompletableDeferred<Unit>()
        fake.gate = gate
        changes.changed()

        changes.changed()
        changes.changed()
        fake.homeRows = fake.homeRows + (HomeRowKind.Continue to row(HomeRowKind.Continue, "7"))
        gate.complete(Unit)

        assertThat(fake.calls.size).isEqualTo(calls + 2 * HomeRowKind.entries.size)
        assertThat(viewModel.state.value.idsOf(HomeRowKind.Continue)).containsExactly(BookId("7"))
    }

    @Test
    fun `a quiet reload after a change that fails keeps the rows and says nothing`() = runTest(mainRule.dispatcher) {
        fake.homeRows = HomeRowKind.entries.associateWith { row(it, "1") }
        val viewModel = viewModel()
        fake.homeRows = HomeRowKind.entries.associateWith { ApiResult.Failure(ApiError.ServerUnreachable) }

        changes.changed()

        assertThat(viewModel.state.value.idsOf(HomeRowKind.Continue)).containsExactly(BookId("1"))
        assertThat(viewModel.state.value.refreshError).isNull()
    }

    @Test
    fun `being shown again counts the relative times from now, even with fresh rows`() = runTest(mainRule.dispatcher) {
        val viewModel = viewModel()
        assertThat(viewModel.state.value.now).isEqualTo(wallNow)
        val calls = fake.calls.size

        wallNow = wallNow.plusSeconds(60)
        viewModel.onShown()

        assertThat(viewModel.state.value.now).isEqualTo(wallNow)
        assertThat(fake.calls).hasSize(calls)
    }

    @Test
    fun `a load and a refresh count the relative times from now`() = runTest(mainRule.dispatcher) {
        val viewModel = viewModel()

        wallNow = wallNow.plusSeconds(60)
        viewModel.refresh()
        assertThat(viewModel.state.value.now).isEqualTo(wallNow)

        wallNow = wallNow.plusSeconds(60)
        viewModel.load()
        assertThat(viewModel.state.value.now).isEqualTo(wallNow)
    }

    @Test
    fun `retrying after a failure shows the rows as loading, then loaded`() = runTest(mainRule.dispatcher) {
        fake.homeRows = HomeRowKind.entries.associateWith { ApiResult.Failure(ApiError.ServerUnreachable) }
        val viewModel = viewModel()
        fake.homeRows = HomeRowKind.entries.associateWith { row(it, "3") }

        viewModel.load()

        assertThat(viewModel.state.value.allFailed).isFalse()
        assertThat(viewModel.state.value.idsOf(HomeRowKind.RecentlyAdded)).containsExactly(BookId("3"))
    }

    @Test
    fun `the see-all list asks for the long row and exposes its books`() = runTest(mainRule.dispatcher) {
        fake.homeRows = fake.homeRows + (HomeRowKind.UpNext to row(HomeRowKind.UpNext, "4", "5"))

        val state = HomeRowViewModel(HomeRowKind.UpNext, fake).state.value

        assertThat(fake.calls).containsExactly("homeRow UpNext SeeAll")
        assertThat((state as Loadable.Ready).value.books.map { it.id })
            .containsExactly(BookId("4"), BookId("5")).inOrder()
    }

    @Test
    fun `the see-all list that fails can be asked again`() = runTest(mainRule.dispatcher) {
        fake.homeRows = fake.homeRows + (HomeRowKind.UpNext to ApiResult.Failure(ApiError.ServerUnreachable))
        val viewModel = HomeRowViewModel(HomeRowKind.UpNext, fake)
        assertThat(viewModel.state.value).isEqualTo(Loadable.Failed(ApiError.ServerUnreachable))
        fake.homeRows = fake.homeRows + (HomeRowKind.UpNext to row(HomeRowKind.UpNext, "4"))

        viewModel.load()

        assertThat(viewModel.state.value).isInstanceOf(Loadable.Ready::class.java)
    }
}
