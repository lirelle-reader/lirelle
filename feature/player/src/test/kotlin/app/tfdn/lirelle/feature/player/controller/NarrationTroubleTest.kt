package app.tfdn.lirelle.feature.player.controller

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.feature.player.service.NarrationStart
import app.tfdn.lirelle.feature.player.service.PlayerCommand
import app.tfdn.lirelle.feature.player.service.PlayerSessionExtras
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** What goes wrong with a narration, as the reader is to show it (the 3a player's messages), and "Try again". */
class NarrationTroubleTest {

    private val resume =
        PlayerCommand.LoadNarration(BookId("42"), FileId("1"), sectionIndex = null, fragment = null, play = true)

    @Test
    fun `a narration paused for the network, or a server out of reach, says so`() = runTest {
        val harness = PlayerHarness(this)
        val narration = harness.watchedNarration()

        harness.show(SessionNarrations.at(extras = SessionNarrations.extras.copy(offline = true)))
        assertThat(narration.value!!.offline).isTrue()
        assertThat(narration.value!!.unreachable).isFalse()

        harness.show(SessionNarrations.at(extras = SessionNarrations.extras.copy(unreachable = true)))
        assertThat(narration.value!!.offline).isFalse()
        assertThat(narration.value!!.unreachable).isTrue()
    }

    @Test
    fun `an engine error that stopped the narration says so, unless it is the network's`() = runTest {
        val harness = PlayerHarness(this)
        val narration = harness.watchedNarration()

        harness.show(SessionNarrations.at(playing = false).copy(error = true))
        assertThat(narration.value!!.error).isTrue()

        harness.show(
            SessionNarrations.at(playing = false, extras = SessionNarrations.extras.copy(unreachable = true))
                .copy(error = true),
        )
        assertThat(narration.value!!.error).isFalse()
        assertThat(narration.value!!.unreachable).isTrue()
    }

    @Test
    fun `a narration that could not be loaded says so, for its book and file, with nothing else loaded`() =
        runTest {
            val harness = PlayerHarness(this)
            val narration = harness.watchedNarration()

            harness.controller.narrate(BookId("42"), FileId("1"), NarrationStart.AtSentence(3, "s4"))
            harness.show(SessionSnapshot(PlayerSessionExtras(loading = true)))
            assertThat(narration.value).isNull()
            harness.show(SessionSnapshot(PlayerSessionExtras(loadFailed = true)))

            val failed = narration.value!!
            assertThat(failed.bookId).isEqualTo(BookId("42"))
            assertThat(failed.fileId).isEqualTo(FileId("1"))
            assertThat(failed.loadFailed).isTrue()
            assertThat(failed.playing).isFalse()
        }

    @Test
    fun `a narration that could not be loaded over an audiobook is the narration's failure`() = runTest {
        val harness = PlayerHarness(this)
        val narration = harness.watchedNarration()
        harness.show(SessionBooks.at())

        val failure = harness.watchedFailure()

        harness.controller.narrate(BookId("42"), FileId("1"), NarrationStart.Resume)
        harness.show(SessionBooks.at(extras = PlayerSessionExtras(SessionBooks.book, loadFailed = true)))

        assertThat(narration.value!!.loadFailed).isTrue()
        assertThat(narration.value!!.fileId).isEqualTo(FileId("1"))
        assertThat(failure.value).isEqualTo(NarrationFailure(BookId("42"), FileId("1")))
    }

    @Test
    fun `another narration that could not be loaded leaves the one held as it is, and is told apart`() = runTest {
        val harness = PlayerHarness(this)
        val narration = harness.watchedNarration()
        val failure = harness.watchedFailure()
        harness.show(SessionNarrations.at())

        harness.controller.narrate(BookId("43"), FileId("2"), NarrationStart.Resume)
        harness.show(SessionNarrations.at(extras = SessionNarrations.extras.copy(loading = true)))
        harness.show(SessionNarrations.at(extras = SessionNarrations.extras.copy(loadFailed = true)))

        val held = narration.value!!
        assertThat(held.bookId).isEqualTo(BookId("42"))
        assertThat(held.sectionTitle).isEqualTo("Section 4")
        assertThat(held.loadFailed).isFalse()
        assertThat(failure.value).isEqualTo(NarrationFailure(BookId("43"), FileId("2")))
    }

    @Test
    fun `an audiobook that could not be loaded is not the narration's failure`() = runTest {
        val harness = PlayerHarness(this)
        val narration = harness.watchedNarration()
        val failure = harness.watchedFailure()
        harness.show(SessionNarrations.at())

        harness.controller.load(BookId("7"))
        harness.show(SessionNarrations.at(extras = SessionNarrations.extras.copy(loadFailed = true)))

        assertThat(narration.value!!.loadFailed).isFalse()
        assertThat(failure.value).isNull()
    }

    @Test
    fun `the narration held that could not be loaded again is its own failure`() = runTest {
        val harness = PlayerHarness(this)
        val narration = harness.watchedNarration()
        val failure = harness.watchedFailure()
        harness.show(SessionNarrations.at(playing = false).copy(error = true))

        harness.controller.retryNarration()
        harness.show(SessionNarrations.at(playing = false, extras = SessionNarrations.extras.copy(loadFailed = true)))

        assertThat(narration.value!!.loadFailed).isTrue()
        assertThat(failure.value).isNull()
    }

    @Test
    fun `a failure of another narration is forgotten once an audiobook is asked`() =
        runTest {
            val harness = PlayerHarness(this)
            val failure = harness.watchedFailure()
            harness.controller.narrate(BookId("43"), FileId("2"), NarrationStart.Resume)
            harness.show(SessionSnapshot(PlayerSessionExtras(loadFailed = true)))
            assertThat(failure.value).isEqualTo(NarrationFailure(BookId("43"), FileId("2")))

            harness.controller.load(BookId("7"))
            assertThat(failure.value).isNull()
        }

    @Test
    fun `play on the narration only plays`() = runTest {
        val harness = PlayerHarness(this)

        harness.controller.playNarration()
        harness.controller.playNarration()

        assertThat(harness.connection.sent).containsExactly("play", "play")
    }

    @Test
    fun `try again reloads the narration that could not be, where it was left`() = runTest {
        val harness = PlayerHarness(this)
        harness.watchedNarration()
        harness.controller.narrate(BookId("42"), FileId("1"), NarrationStart.AtSentence(3, "s4"))
        harness.show(SessionSnapshot(PlayerSessionExtras(loadFailed = true)))

        harness.controller.retryNarration()

        assertThat(harness.connection.sent.last()).isEqualTo(resume)
    }

    @Test
    fun `try again after an engine error reloads the narration loaded, where it was left`() = runTest {
        val harness = PlayerHarness(this)
        harness.watchedNarration()
        harness.show(SessionNarrations.at(playing = false).copy(error = true))

        harness.controller.retryNarration()

        assertThat(harness.connection.sent).containsExactly(resume)
    }

    @Test
    fun `once loaded, or once an audiobook is asked, a later failure is not the narration's`() = runTest {
        val harness = PlayerHarness(this)
        val narration = harness.watchedNarration()
        harness.controller.narrate(BookId("42"), FileId("1"), NarrationStart.Resume)
        harness.show(SessionNarrations.at())

        harness.controller.load(BookId("7"))
        harness.show(SessionSnapshot(PlayerSessionExtras(loadFailed = true)))

        assertThat(narration.value).isNull()
        harness.controller.retryNarration()
        assertThat(harness.connection.sent.filterIsInstance<PlayerCommand.LoadNarration>()).hasSize(1)
    }
}
