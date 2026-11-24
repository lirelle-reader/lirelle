package app.tfdn.lirelle.feature.player.controller

import app.tfdn.lirelle.feature.player.service.PlayerCommand
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** A section chosen in the narration offers "Go back", as a chapter does in the 3a player (spec 6.3). */
class NarrationGoBackTest {

    /** A long narration of book 42 (sections like the 3a book's chapters: 0–60 s, 60–100 s, 100–160 s). */
    private fun at(chapter: Int, positionMs: Long, playing: Boolean = true) = SessionBooks.at(
        chapter = chapter,
        positionMs = positionMs,
        playing = playing,
        extras = SessionNarrations.extras.copy(book = SessionBooks.book),
    )

    private fun TestScope.watching(): PlayerHarness = PlayerHarness(this).also {
        it.watchedNarration()
        it.show(at(chapter = 1, positionMs = 10_000))
    }

    @Test
    fun `a section chosen goes to its start, and offers to go back to where the narration was`() = runTest {
        val harness = watching()

        harness.controller.goToSection(2)

        assertThat(harness.connection.sent).containsExactly("seekTo 2 0")
        assertThat(harness.controller.narration.value!!.goBackShown).isTrue()
    }

    @Test
    fun `go back returns to where the section was chosen from, and is no longer offered`() = runTest {
        val harness = watching()
        harness.controller.goToSection(2)
        harness.show(at(chapter = 2, positionMs = 0))

        harness.controller.goBackInNarration()

        assertThat(harness.connection.sent.last()).isEqualTo(PlayerCommand.SeekBookTime(70_000))
        assertThat(harness.controller.narration.value!!.goBackShown).isFalse()
    }

    @Test
    fun `nothing is offered before a section is chosen, and go back then does nothing`() = runTest {
        val harness = watching()

        harness.controller.goBackInNarration()

        assertThat(harness.controller.narration.value!!.goBackShown).isFalse()
        assertThat(harness.connection.sent).isEmpty()
    }

    @Test
    fun `two minutes listened after the jump forget it`() = runTest {
        val harness = watching()
        harness.controller.goToSection(0)

        // Listening on from the start of the first section, a look every half second, for 2 min 1 s.
        for (time in 0L..121_000L step 500) {
            val chapter = SessionBooks.chapters.indexOfLast { it.startMs <= time }
            harness.show(at(chapter, time - SessionBooks.chapters[chapter].startMs))
        }

        assertThat(harness.controller.narration.value!!.goBackShown).isFalse()
    }

    @Test
    fun `paused, the time does not count`() = runTest {
        val harness = watching()
        harness.controller.goToSection(0)

        for (time in 0L..121_000L step 500) {
            harness.show(at(chapter = 0, positionMs = time.coerceAtMost(59_000), playing = false))
        }

        assertThat(harness.controller.narration.value!!.goBackShown).isTrue()
    }

    @Test
    fun `another book, or the book's audiobook, forgets the jump`() = runTest {
        val harness = watching()
        harness.controller.goToSection(2)

        harness.show(SessionBooks.at())
        harness.show(at(chapter = 2, positionMs = 0))

        assertThat(harness.controller.narration.value!!.goBackShown).isFalse()
    }

    @Test
    fun `a section is not chosen while no narration is loaded`() = runTest {
        val harness = PlayerHarness(this)
        harness.watchedNarration()
        harness.show(SessionBooks.at())

        harness.controller.goToSection(1)

        assertThat(harness.connection.sent).isEmpty()
    }
}
