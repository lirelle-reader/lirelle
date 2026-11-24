package app.tfdn.lirelle.feature.player

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.library.model.BookReading
import app.tfdn.lirelle.core.testing.FakeLibraryRepository
import app.tfdn.lirelle.core.testing.MainDispatcherRule
import app.tfdn.lirelle.feature.player.controller.PlayerHarness
import app.tfdn.lirelle.feature.player.controller.SessionBooks
import app.tfdn.lirelle.feature.player.controller.SessionNarrations
import app.tfdn.lirelle.feature.player.service.PlayerCommand
import app.tfdn.lirelle.feature.player.service.PlayerSessionExtras
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    private lateinit var harness: PlayerHarness
    private val library = FakeLibraryRepository()

    /** A view model watched by its screen, over a book playing at 1:10 (the second chapter). */
    private fun TestScope.viewModel(): PlayerViewModel {
        harness = PlayerHarness(this)
        harness.show(SessionBooks.at(chapter = 1, positionMs = 10_000))
        return harness.viewModel(library).also { viewModel ->
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.state.collect {} }
        }
    }

    /** Playback goes on from [fromMs] for [seconds], a look every half second. */
    private fun listen(fromMs: Long, seconds: Int) {
        repeat(seconds * 2 + 1) { step ->
            val time = fromMs + step * 500L
            val chapter = SessionBooks.chapters.indexOfLast { it.startMs <= time }
            harness.show(SessionBooks.at(chapter = chapter, positionMs = time - SessionBooks.chapters[chapter].startMs))
        }
    }

    @Test
    fun `a narration is not for the player screen, which leaves once`() = runTest {
        val viewModel = viewModel()
        val events = mutableListOf<PlayerEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.collect { events += it } }

        harness.show(SessionNarrations.at())
        harness.show(SessionNarrations.at(positionMs = 5_000))

        assertThat(events).containsExactly(PlayerEvent.Leave)
    }

    @Test
    fun `the screen's state is the controller's`() = runTest {
        val viewModel = viewModel()

        assertThat(viewModel.state.value.chapterTitle).isEqualTo("Middle")
        assertThat(viewModel.state.value.goBackShown).isFalse()
    }

    @Test
    fun `a chapter chosen offers to go back where the user was`() = runTest {
        val viewModel = viewModel()

        viewModel.goToChapter(2)
        assertThat(viewModel.state.value.goBackShown).isTrue()
        harness.show(SessionBooks.at(chapter = 2, positionMs = 0))

        viewModel.goBack()

        assertThat(harness.connection.sent).containsExactly("seekTo 2 0", PlayerCommand.SeekBookTime(70_000)).inOrder()
        assertThat(viewModel.state.value.goBackShown).isFalse()
    }

    @Test
    fun `going back twice returns through both jumps`() = runTest {
        val viewModel = viewModel()
        viewModel.goToChapter(2)
        harness.show(SessionBooks.at(chapter = 2, positionMs = 5_000))
        viewModel.goToChapter(0)
        harness.show(SessionBooks.at(chapter = 0, positionMs = 0))

        viewModel.goBack()
        assertThat(viewModel.state.value.goBackShown).isTrue()
        viewModel.goBack()

        assertThat(harness.connection.sent.takeLast(2))
            .containsExactly(PlayerCommand.SeekBookTime(105_000), PlayerCommand.SeekBookTime(70_000)).inOrder()
        assertThat(viewModel.state.value.goBackShown).isFalse()
    }

    @Test
    fun `two minutes listened after the jump, going back is no longer offered`() = runTest {
        val viewModel = viewModel()
        viewModel.goToChapter(0)

        listen(fromMs = 0, seconds = 119)
        assertThat(viewModel.state.value.goBackShown).isTrue()

        listen(fromMs = 119_500, seconds = 1)
        assertThat(viewModel.state.value.goBackShown).isFalse()
    }

    @Test
    fun `skips and pauses are not listening`() = runTest {
        val viewModel = viewModel()
        viewModel.goToChapter(2)

        repeat(10) { step -> harness.show(SessionBooks.at(chapter = 2, positionMs = step * 30_000L % 60_000)) }
        repeat(400) { harness.show(SessionBooks.at(chapter = 2, positionMs = 1_000, playing = false)) }

        assertThat(viewModel.state.value.goBackShown).isTrue()
    }

    @Test
    fun `another book forgets where to go back`() = runTest {
        val viewModel = viewModel()
        viewModel.goToChapter(2)

        harness.show(SessionBooks.at(extras = PlayerSessionExtras(SessionBooks.book.copy(id = BookId("43")))))

        assertThat(viewModel.state.value.goBackShown).isFalse()
        viewModel.goBack()
        assertThat(harness.connection.sent).containsExactly("seekTo 2 0")
    }

    @Test
    fun `a chapter that does not exist is not chosen`() = runTest {
        val viewModel = viewModel()

        viewModel.goToChapter(3)

        assertThat(harness.connection.sent).isEmpty()
        assertThat(viewModel.state.value.goBackShown).isFalse()
    }

    @Test
    fun `back at the front, a newer position is offered, and going there offers to go back`() = runTest {
        val viewModel = viewModel()
        val at = Instant.parse("2026-11-15T10:00:00Z")
        harness.audio.playbackStateResult = ApiResult.Success(SessionBooks.server(at))

        viewModel.onShown()
        assertThat(viewModel.state.value.newerPosition?.bookTimeMs).isEqualTo(120_000)

        viewModel.acceptNewerPosition()

        assertThat(harness.connection.sent).containsExactly(PlayerCommand.SeekBookTime(120_000))
        assertThat(viewModel.state.value.newerPosition).isNull()
        assertThat(viewModel.state.value.goBackShown).isTrue()
    }

    @Test
    fun `an ignored position stays where the player is`() = runTest {
        val viewModel = viewModel()
        harness.audio.playbackStateResult =
            ApiResult.Success(SessionBooks.server(Instant.parse("2026-11-15T10:00:00Z")))
        viewModel.onShown()

        viewModel.ignoreNewerPosition()

        assertThat(harness.connection.sent).isEmpty()
        assertThat(viewModel.state.value.newerPosition).isNull()
        assertThat(viewModel.state.value.goBackShown).isFalse()
    }

    @Test
    fun `the end panel closed, the screen leaves`() = runTest {
        val viewModel = viewModel()
        harness.show(SessionBooks.finished())
        val leave = backgroundScope.launch { viewModel.events.first() }

        viewModel.closeEndPanel()
        advanceTimeBy(1.minutes)

        assertThat(leave.isCompleted).isTrue()
        assertThat(harness.connection.sent).containsExactly(PlayerCommand.CloseBook)
    }

    @Test
    fun `the end panel dismissed, the screen stays`() = runTest {
        val viewModel = viewModel()
        harness.show(SessionBooks.finished())

        viewModel.dismissEndPanel()
        advanceTimeBy(1.minutes)

        assertThat(viewModel.state.value.finished?.panelSeen).isTrue()
        viewModel.openEndPanel()
        assertThat(viewModel.state.value.finished?.panelSeen).isFalse()
    }

    @Test
    fun `a finished book shows the time listened to it at once`() = runTest {
        library.readingResult = ApiResult.Success(BookReading(100, null, totalSeconds = 52_680, sessionCount = 9))
        val viewModel = viewModel()
        assertThat(viewModel.state.value.listenedSeconds).isNull()

        harness.show(SessionBooks.finished())
        harness.show(SessionBooks.finished().copy(positionMs = 59_000))

        assertThat(viewModel.state.value.listenedSeconds).isEqualTo(52_680)
        assertThat(library.calls.filter { it.startsWith("bookReading") }).containsExactly("bookReading 42")
    }

    @Test
    fun `the time listened is asked again until the last session counts in it`() = runTest {
        library.readingResult = ApiResult.Success(BookReading(100, null, totalSeconds = 600, sessionCount = 1))
        val viewModel = viewModel()
        harness.show(SessionBooks.finished())
        assertThat(viewModel.state.value.listenedSeconds).isEqualTo(600)

        library.readingResult = ApiResult.Success(BookReading(100, null, totalSeconds = 900, sessionCount = 2))
        advanceTimeBy(3.seconds)
        assertThat(viewModel.state.value.listenedSeconds).isEqualTo(900)

        // Grown: asked no more on its own.
        val asked = library.calls.count { it.startsWith("bookReading") }
        advanceTimeBy(2.minutes)
        assertThat(library.calls.count { it.startsWith("bookReading") }).isEqualTo(asked)
    }

    @Test
    fun `a first question that fails is asked again, and asked on until the total grows past the first figure`() =
        runTest {
            val viewModel = viewModel()
            harness.show(SessionBooks.finished())
            assertThat(viewModel.state.value.listenedSeconds).isNull()

            library.readingResult = ApiResult.Success(BookReading(100, null, totalSeconds = 600, sessionCount = 1))
            advanceTimeBy(3.seconds)
            assertThat(viewModel.state.value.listenedSeconds).isEqualTo(600)

            library.readingResult = ApiResult.Success(BookReading(100, null, totalSeconds = 900, sessionCount = 2))
            advanceTimeBy(6.seconds)
            assertThat(viewModel.state.value.listenedSeconds).isEqualTo(900)
        }

    @Test
    fun `an older, smaller total never replaces a larger one`() = runTest {
        library.readingResult = ApiResult.Success(BookReading(100, null, totalSeconds = 900, sessionCount = 2))
        val viewModel = viewModel()
        harness.show(SessionBooks.finished())

        library.readingResult = ApiResult.Success(BookReading(100, null, totalSeconds = 600, sessionCount = 1))
        harness.changes.changed()
        advanceTimeBy(2.minutes)

        assertThat(viewModel.state.value.listenedSeconds).isEqualTo(900)
    }

    @Test
    fun `the time listened is asked again when the server took what the device sent`() = runTest {
        library.readingResult = ApiResult.Success(BookReading(100, null, totalSeconds = 600, sessionCount = 1))
        val viewModel = viewModel()
        harness.show(SessionBooks.finished())
        advanceTimeBy(2.minutes)

        library.readingResult = ApiResult.Success(BookReading(100, null, totalSeconds = 1_500, sessionCount = 3))
        harness.changes.changed()

        assertThat(viewModel.state.value.listenedSeconds).isEqualTo(1_500)
    }

    @Test
    fun `without statistics, no time listened is shown`() = runTest {
        val viewModel = viewModel()

        harness.show(SessionBooks.finished())
        assertThat(viewModel.state.value.listenedSeconds).isNull()

        library.readingResult = ApiResult.Success(BookReading(null, null, totalSeconds = 0, sessionCount = 0))
        harness.show(SessionBooks.at())
        harness.show(SessionBooks.finished())
        assertThat(viewModel.state.value.listenedSeconds).isNull()
    }

    @Test
    fun `the time listened goes with the end of the book, and is read again at the next end`() = runTest {
        library.readingResult = ApiResult.Success(BookReading(100, null, totalSeconds = 600, sessionCount = 1))
        val viewModel = viewModel()
        harness.show(SessionBooks.finished())

        harness.show(SessionBooks.at())
        assertThat(viewModel.state.value.listenedSeconds).isNull()

        library.readingResult = ApiResult.Success(BookReading(100, null, totalSeconds = 1_200, sessionCount = 2))
        harness.show(SessionBooks.finished())
        assertThat(viewModel.state.value.listenedSeconds).isEqualTo(1_200)
    }
}
