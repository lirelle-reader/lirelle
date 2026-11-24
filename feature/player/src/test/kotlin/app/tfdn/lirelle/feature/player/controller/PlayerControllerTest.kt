package app.tfdn.lirelle.feature.player.controller

import app.tfdn.lirelle.core.audio.timer.SleepChoice
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.model.NextVolume
import app.tfdn.lirelle.feature.player.service.FinishedBook
import app.tfdn.lirelle.feature.player.service.PlayerCommand
import app.tfdn.lirelle.feature.player.service.SessionBook
import app.tfdn.lirelle.feature.player.service.PlayerSessionExtras
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PlayerControllerTest {

    @Test
    fun `the buttons are the session's commands`() = runTest {
        val harness = PlayerHarness(this)
        val controller = harness.controller

        controller.back()
        controller.forward()
        controller.previousChapter()
        controller.nextChapter()
        controller.setSpeed(1.25f)
        controller.startSleep(SleepChoice.Minutes(15))
        controller.startSleep(SleepChoice.EndOfChapter)
        controller.cancelSleep()

        assertThat(harness.connection.sent).containsExactly(
            PlayerCommand.Back,
            PlayerCommand.Forward,
            PlayerCommand.PreviousChapter,
            PlayerCommand.NextChapter,
            PlayerCommand.SetSpeed(1.25f),
            PlayerCommand.StartSleep(SleepChoice.Minutes(15)),
            PlayerCommand.StartSleep(SleepChoice.EndOfChapter),
            PlayerCommand.CancelSleep,
        ).inOrder()
    }

    @Test
    fun `play and pause is left to the session, which knows whether it plays`() = runTest {
        val harness = PlayerHarness(this)

        harness.controller.playPause()

        assertThat(harness.connection.sent).containsExactly("playPause")
    }

    @Test
    fun `dragging the chapter bar seeks inside the chapter`() = runTest {
        val harness = PlayerHarness(this)
        harness.show(SessionBooks.at(chapter = 1))

        harness.controller.seekInChapter(0.5f)
        harness.controller.seekInChapter(2f)

        assertThat(harness.connection.sent).containsExactly("seekTo 1 20000", "seekTo 1 40000").inOrder()
    }

    @Test
    fun `a chapter of unknown duration cannot be dragged`() = runTest {
        val harness = PlayerHarness(this)
        val snapshot = SessionBooks.at(chapter = 2)
        harness.show(snapshot.copy(chapters = snapshot.chapters.dropLast(1) + SessionChapter("Closing", 100_000, null)))

        harness.controller.seekInChapter(0.5f)

        assertThat(harness.connection.sent).isEmpty()
    }

    @Test
    fun `a chapter is played from its start, a place of the book by its time`() = runTest {
        val harness = PlayerHarness(this)
        harness.show(SessionBooks.at())

        harness.controller.goToChapter(2)
        harness.controller.seekBookTime(12_345)

        assertThat(harness.connection.sent).containsExactly("seekTo 2 0", PlayerCommand.SeekBookTime(12_345)).inOrder()
    }

    @Test
    fun `a book is loaded and played, from its position or its start`() = runTest {
        val harness = PlayerHarness(this)

        harness.controller.load(BookId("7"))
        harness.controller.load(BookId("8"), fromStart = true)

        assertThat(harness.connection.sent).containsExactly(
            PlayerCommand.LoadBook(BookId("7"), fromStart = false, play = true),
            PlayerCommand.LoadBook(BookId("8"), fromStart = true, play = true),
        ).inOrder()
    }

    @Test
    fun `the book the session shows is asked of the service all the same, which decides`() = runTest {
        val harness = PlayerHarness(this)
        val book = PlayerSessionExtras(SessionBooks.book)
        val snapshots = listOf(
            SessionBooks.at(playing = true),
            SessionBooks.at(playing = false),
            SessionBooks.finished(),
            SessionBooks.at(playing = false, extras = book.copy(loading = true)),
            SessionBooks.at(playing = false, extras = book.copy(loadFailed = true)),
        )
        snapshots.forEach { snapshot ->
            harness.show(snapshot)
            harness.controller.load(BookId("42"))
        }
        harness.controller.load(BookId("42"), fromStart = true)

        val fromPosition = PlayerCommand.LoadBook(BookId("42"), fromStart = false, play = true)
        val fromStart = PlayerCommand.LoadBook(BookId("42"), fromStart = true, play = true)
        assertThat(harness.connection.sent)
            .containsExactlyElementsIn(List(snapshots.size) { fromPosition } + fromStart)
            .inOrder()
    }

    @Test
    fun `trying again loads the last book asked for, as it was asked`() = runTest {
        val harness = PlayerHarness(this)
        harness.controller.retryLoad()
        assertThat(harness.connection.sent).isEmpty()

        harness.controller.load(BookId("7"), fromStart = true)
        harness.show(SessionBooks.at(playing = false, extras = PlayerSessionExtras(book = null, loadFailed = true)))
        harness.controller.retryLoad()

        assertThat(harness.connection.sent).containsExactly(
            PlayerCommand.LoadBook(BookId("7"), fromStart = true, play = true),
            PlayerCommand.LoadBook(BookId("7"), fromStart = true, play = true),
        ).inOrder()
    }

    @Test
    fun `trying again reloads even the book the session shows`() = runTest {
        val harness = PlayerHarness(this)
        harness.show(SessionBooks.at())
        harness.controller.load(BookId("42"))
        harness.show(SessionBooks.at(extras = PlayerSessionExtras(SessionBooks.book, loadFailed = true)))

        harness.controller.retryLoad()

        val load = PlayerCommand.LoadBook(BookId("42"), fromStart = false, play = true)
        assertThat(harness.connection.sent).containsExactly(load, load)
    }

    private fun loading(book: SessionBook? = null) =
        SessionBooks.at(playing = false, extras = PlayerSessionExtras(book, loading = true))

    private fun failed(book: SessionBook? = null) =
        SessionBooks.at(playing = false, extras = PlayerSessionExtras(book, loadFailed = true))

    @Test
    fun `trying again after "Listen to the next one" loads the next volume`() = runTest {
        val harness = PlayerHarness(this)
        val state = harness.watched()
        val next = NextVolume(BookId("43"), "The Long Road Back", "2")
        harness.show(
            SessionBooks.at(
                chapter = 2,
                positionMs = 60_000,
                playing = false,
                extras = PlayerSessionExtras(SessionBooks.book, finished = FinishedBook(next)),
            ),
        )

        harness.controller.listenToNext()
        harness.show(loading(SessionBooks.book))
        harness.show(failed(SessionBooks.book))
        assertThat(state.value.canRetryLoad).isTrue()
        harness.controller.retryLoad()

        assertThat(harness.connection.sent).containsExactly(
            PlayerCommand.ListenToNext,
            PlayerCommand.LoadBook(BookId("43"), fromStart = false, play = true),
        ).inOrder()
    }

    @Test
    fun `a load this app did not ask for offers nothing to try again`() = runTest {
        val harness = PlayerHarness(this)
        val state = harness.watched()

        harness.show(loading())
        harness.show(failed())
        harness.controller.retryLoad()

        assertThat(state.value.loadFailed).isTrue()
        assertThat(state.value.canRetryLoad).isFalse()
        assertThat(harness.connection.sent).isEmpty()
    }

    @Test
    fun `a load the app did not ask for, after one it did, forgets the request`() = runTest {
        val harness = PlayerHarness(this)
        val state = harness.watched()
        harness.controller.load(BookId("42"))
        harness.show(loading())
        harness.show(SessionBooks.at())
        assertThat(state.value.canRetryLoad).isTrue()

        // The book's files changed on the server: the service reloads it, and fails.
        harness.show(loading(SessionBooks.book))
        harness.show(failed(SessionBooks.book))
        harness.controller.retryLoad()

        assertThat(state.value.canRetryLoad).isFalse()
        assertThat(harness.connection.sent)
            .containsExactly(PlayerCommand.LoadBook(BookId("42"), fromStart = false, play = true))
    }

    @Test
    fun `a failure seen without its loading is still the load asked for`() = runTest {
        val harness = PlayerHarness(this)
        val state = harness.watched()
        harness.controller.load(BookId("7"), fromStart = true)

        harness.show(failed())

        assertThat(state.value.canRetryLoad).isTrue()
    }

    @Test
    fun `the state follows the session`() = runTest {
        val harness = PlayerHarness(this)
        val state = harness.watched()

        harness.show(SessionBooks.at(chapter = 0, positionMs = 5_000))
        assertThat(state.value.chapterTitle).isEqualTo("Opening")

        harness.show(SessionBooks.at(chapter = 2, positionMs = 1_000, extras = PlayerSessionExtras(SessionBooks.book)))
        assertThat(state.value.chapterTitle).isEqualTo("Closing")
        assertThat(state.value.bookTimeMs).isEqualTo(101_000)
    }

    @Test
    fun `the settings are in the state`() = runTest {
        val harness = PlayerHarness(this)
        val state = harness.watched()
        harness.show(SessionBooks.at())

        harness.settings.update { it.copy(backSeconds = 20) }

        assertThat(state.value.settings.backSeconds).isEqualTo(20)
    }

    @Test
    fun `shaking to extend the sleep timer is turned off and on in the settings`() = runTest {
        val harness = PlayerHarness(this)

        harness.controller.setShakeToExtend(false)
        assertThat(harness.settings.settings.value.shakeToExtend).isFalse()
        harness.controller.setShakeToExtend(true)
        assertThat(harness.settings.settings.value.shakeToExtend).isTrue()
    }
}
