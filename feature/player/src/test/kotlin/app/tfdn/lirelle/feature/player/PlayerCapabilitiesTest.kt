package app.tfdn.lirelle.feature.player

import app.tfdn.lirelle.core.testing.FakeLibraryRepository
import app.tfdn.lirelle.core.testing.KomgaLikeCapabilities
import app.tfdn.lirelle.core.testing.MainDispatcherRule
import app.tfdn.lirelle.feature.player.bookmarks.FakeAudioBookmarks
import app.tfdn.lirelle.feature.player.controller.PlayerHarness
import app.tfdn.lirelle.feature.player.controller.SessionBooks
import com.google.common.truth.Truth.assertThat
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/** The player's view models follow what the source can do (spec 2c). */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerCapabilitiesTest {

    @get:Rule
    val main = MainDispatcherRule()

    @Test
    fun `the player says what the source can do, and reads no bookmarks without them`() = runTest {
        val harness = PlayerHarness(this)
        harness.show(SessionBooks.at(chapter = 1, positionMs = 10_000))
        val bookmarks = FakeAudioBookmarks()
        val viewModel = harness.viewModel(FakeLibraryRepository(), bookmarks, AudiobooksAlone)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.state.collect {} }
        runCurrent()

        assertThat(viewModel.state.value.bookId).isNotNull()
        assertThat(viewModel.state.value.capabilities).isEqualTo(AudiobooksAlone)
        assertThat(bookmarks.refreshed).isEmpty()
        assertThat(viewModel.offered).isTrue()
    }

    @Test
    fun `the player is not offered without audiobooks`() = runTest {
        val viewModel = PlayerHarness(this).viewModel(capabilities = KomgaLikeCapabilities)

        assertThat(viewModel.offered).isFalse()
    }

    @Test
    fun `reaching the end without ratings asks the server for no finished reading`() = runTest {
        val harness = PlayerHarness(this).apply { capabilities = AudiobooksAlone }
        harness.watched()
        harness.show(SessionBooks.at())
        harness.show(SessionBooks.finished())
        advanceTimeBy(1.minutes)

        assertThat(harness.controller.state.value.finished).isNotNull()
        assertThat(harness.reader.calls.filter { it.startsWith("finishedReading") }).isEmpty()
    }

    @Test
    fun `the time listened is not asked at the end without reading sessions`() = runTest {
        val harness = PlayerHarness(this).apply { capabilities = AudiobooksAlone }
        harness.show(SessionBooks.at())
        val library = FakeLibraryRepository()
        val viewModel = harness.viewModel(library, FakeAudioBookmarks(), AudiobooksAlone)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.state.collect {} }

        harness.show(SessionBooks.finished())
        advanceTimeBy(2.minutes)

        assertThat(viewModel.state.value.finished).isNotNull()
        assertThat(library.calls.filter { it.startsWith("bookReading") }).isEmpty()
    }

    @Test
    fun `the mini-player says what the source can do`() = runTest {
        val harness = PlayerHarness(this)
        harness.show(SessionBooks.at(chapter = 1, positionMs = 10_000))
        val viewModel = MiniPlayerViewModel(harness.controller, MutableStateFlow(true), AudiobooksAlone)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.state.collect {} }
        runCurrent()

        assertThat(viewModel.state.value.bookId).isNotNull()
        assertThat(viewModel.state.value.capabilities).isEqualTo(AudiobooksAlone)
    }
}
