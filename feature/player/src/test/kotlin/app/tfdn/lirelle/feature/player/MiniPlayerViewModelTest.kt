package app.tfdn.lirelle.feature.player

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.testing.MainDispatcherRule
import app.tfdn.lirelle.feature.player.controller.PlayerHarness
import app.tfdn.lirelle.feature.player.controller.SessionBooks
import app.tfdn.lirelle.feature.player.controller.SessionNarrations
import app.tfdn.lirelle.feature.player.controller.SessionSnapshot
import app.tfdn.lirelle.feature.player.service.PlayerCommand
import com.google.common.truth.Truth.assertThat
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MiniPlayerViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    private lateinit var harness: PlayerHarness

    /** Whether the playback service runs. */
    private val running = MutableStateFlow(true)

    /** A mini-player watched by the shell, over a book playing in its second chapter. */
    private fun TestScope.viewModel(): MiniPlayerViewModel {
        harness = PlayerHarness(this)
        harness.show(SessionBooks.at(chapter = 1, positionMs = 10_000))
        return MiniPlayerViewModel(harness.controller, running).also { viewModel ->
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.state.collect {} }
        }
    }

    @Test
    fun `its state is the app's player's`() = runTest {
        val viewModel = viewModel()

        assertThat(viewModel.state.value.title).isEqualTo("The Long Road")
        assertThat(viewModel.state.value.chapterTitle).isEqualTo("Middle")
    }

    @Test
    fun `while the service does not run, nothing is shown and nothing connects to it`() = runTest {
        running.value = false
        val viewModel = viewModel()

        assertThat(viewModel.state.value.bookId).isNull()
        assertThat(harness.connection.snapshot.subscriptionCount.value).isEqualTo(0)
    }

    @Test
    fun `the service starting connects to it, and its book shows`() = runTest {
        running.value = false
        val viewModel = viewModel()

        running.value = true

        assertThat(harness.connection.snapshot.subscriptionCount.value).isGreaterThan(0)
        assertThat(viewModel.state.value.title).isEqualTo("The Long Road")
    }

    @Test
    fun `the service ending stops the watching, and nothing is shown`() = runTest {
        val viewModel = viewModel()

        running.value = false
        // The player's own state is followed a little longer: a screen turned, not left.
        advanceTimeBy(6.seconds)

        assertThat(viewModel.state.value.bookId).isNull()
        assertThat(harness.connection.snapshot.subscriptionCount.value).isEqualTo(0)
    }

    @Test
    fun `play, pause and back command the session`() = runTest {
        val viewModel = viewModel()

        viewModel.playPause()
        viewModel.back()

        assertThat(harness.connection.sent).containsExactly("playPause", PlayerCommand.Back).inOrder()
    }

    @Test
    fun `rate opens the end panel the player had dismissed`() = runTest {
        val viewModel = viewModel()
        harness.show(SessionBooks.finished())
        harness.controller.dismissEndPanel()
        assertThat(viewModel.state.value.finished?.panelSeen).isTrue()

        viewModel.openEndPanel()

        assertThat(viewModel.state.value.finished?.panelSeen).isFalse()
    }

    @Test
    fun `the narration loaded shows, and none while the service does not run`() = runTest {
        val viewModel = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.narration.collect {} }
        assertThat(viewModel.narration.value).isNull()

        harness.show(SessionNarrations.at())
        assertThat(viewModel.narration.value?.sectionTitle).isEqualTo("Section 4")

        running.value = false
        advanceTimeBy(6.seconds)
        assertThat(viewModel.narration.value).isNull()
    }

    @Test
    fun `a narration stopped is narrated again on undo, its narration not even watched`() = runTest {
        val viewModel = viewModel()
        harness.show(SessionNarrations.at(playing = true))

        viewModel.stopListening()
        viewModel.undoStop()

        assertThat(harness.connection.sent).containsExactly(
            PlayerCommand.CloseBook,
            PlayerCommand.LoadNarration(BookId("42"), FileId("1"), play = true),
        ).inOrder()
    }

    @Test
    fun `a book closed by the session, at sign-out for one, leaves nothing loaded`() = runTest {
        val viewModel = viewModel()

        // The service closes its book, then ends: the session goes away.
        harness.show(SessionSnapshot())

        assertThat(viewModel.state.value.bookId).isNull()
    }
}
