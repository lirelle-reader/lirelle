package app.tfdn.lirelle.feature.player.controller

import app.tfdn.lirelle.core.audio.narration.HeardSentence
import app.tfdn.lirelle.core.audio.narration.SkippedNotice
import app.tfdn.lirelle.core.audio.timer.SleepChoice
import app.tfdn.lirelle.core.audio.timer.SleepState
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.feature.player.service.NarrationStart
import app.tfdn.lirelle.feature.player.service.PlayerCommand
import app.tfdn.lirelle.feature.player.service.PlayerSessionExtras
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** The narration as the controller shows it to the reader, and what it asks of the session for it. */
class NarrationControlsTest {

    private val heard = HeardSentence(BookId("42"), FileId("1"), 3, "s4", "text/three.xhtml", playing = true)

    @Test
    fun `a narration's state is the session's place and the sentence the cursor heard`() = runTest {
        val harness = PlayerHarness(this)
        val sleep = SleepState(SleepChoice.Minutes(15), 600_000, fading = false)
        val narration = harness.watchedNarration()

        harness.show(SessionNarrations.at(speed = 2f, extras = SessionNarrations.extras.copy(sleep = sleep)))
        harness.cursor.publish(heard)

        assertThat(narration.value).isEqualTo(
            NarrationUi(
                bookId = BookId("42"),
                fileId = FileId("1"),
                title = "The Long Road",
                sectionIndex = 3,
                // Without a title of its own: named by its place in the book, as the notification names it.
                sectionTitle = "Section 4",
                sectionNumber = 2,
                sectionCount = 2,
                elapsedMs = 4_000,
                sectionDurationMs = 10_000,
                bookPercent = 76.0,
                timeLeftMs = 3_000,
                playing = true,
                speed = 2f,
                sleep = sleep,
                sections = listOf(
                    ChapterRow("Opening", 15_000, heard = true, current = false),
                    ChapterRow("Section 4", 10_000, heard = false, current = true, startMs = 15_000),
                ),
                sentence = heard,
                finished = false,
            ),
        )
    }

    @Test
    fun `a section with a title shows it, and a paused narration does not play`() = runTest {
        val harness = PlayerHarness(this)
        val narration = harness.watchedNarration()

        harness.show(SessionNarrations.at(chapter = 0, positionMs = 1_000, playing = false))

        val shown = narration.value!!
        assertThat(shown.sectionTitle).isEqualTo("Opening")
        assertThat(shown.sectionIndex).isEqualTo(1)
        assertThat(shown.sectionNumber).isEqualTo(1)
        assertThat(shown.playing).isFalse()
    }

    @Test
    fun `there is no narration while an audiobook plays, nor without a session`() = runTest {
        val harness = PlayerHarness(this)
        val narration = harness.watchedNarration()

        harness.show(SessionBooks.at())
        assertThat(narration.value).isNull()

        harness.show(SessionSnapshot())
        assertThat(narration.value).isNull()
    }

    @Test
    fun `a narration's state carries the player settings, as they change`() = runTest {
        val harness = PlayerHarness(this)
        val narration = harness.watchedNarration()
        harness.show(SessionNarrations.at())

        harness.settings.update { it.copy(backSeconds = 10, forwardSeconds = 45, shakeToExtend = false) }

        val settings = narration.value!!.settings
        assertThat(settings.backSeconds).isEqualTo(10)
        assertThat(settings.forwardSeconds).isEqualTo(45)
        assertThat(settings.shakeToExtend).isFalse()
    }

    @Test
    fun `a sentence of another book is not this narration's`() = runTest {
        val harness = PlayerHarness(this)
        val narration = harness.watchedNarration()

        harness.show(SessionNarrations.at())
        harness.cursor.publish(heard.copy(bookId = BookId("43")))

        assertThat(narration.value!!.sentence).isNull()
    }

    @Test
    fun `the narration is finished when the session says so, or the cursor for this book`() = runTest {
        val harness = PlayerHarness(this)
        val narration = harness.watchedNarration()
        harness.show(SessionNarrations.at())

        harness.cursor.finish(BookId("43"))
        assertThat(narration.value!!.finished).isFalse()

        harness.cursor.finish(BookId("42"))
        assertThat(narration.value!!.finished).isTrue()

        harness.cursor.finish(null)
        harness.show(SessionNarrations.at(extras = SessionNarrations.extras.copy(narrationFinished = true)))
        assertThat(narration.value!!.finished).isTrue()
    }

    @Test
    fun `a narration is asked where it was left, or at a sentence`() = runTest {
        val harness = PlayerHarness(this)

        harness.controller.narrate(BookId("42"), FileId("1"), NarrationStart.Resume)
        harness.controller.narrate(BookId("42"), FileId("1"), NarrationStart.AtSentence(3, "s4"), play = false)

        assertThat(harness.connection.sent).containsExactly(
            PlayerCommand.LoadNarration(BookId("42"), FileId("1"), sectionIndex = null, fragment = null, play = true),
            PlayerCommand.LoadNarration(BookId("42"), FileId("1"), sectionIndex = 3, fragment = "s4", play = false),
        ).inOrder()
    }

    @Test
    fun `the sentence buttons, the speed and the pause are the session's commands`() = runTest {
        val harness = PlayerHarness(this)

        harness.controller.previousSentence()
        harness.controller.nextSentence()
        harness.controller.narrationSpeed(1.5f)
        harness.controller.pauseNarration()

        assertThat(harness.connection.sent).containsExactly(
            PlayerCommand.PreviousSentence,
            PlayerCommand.NextSentence,
            PlayerCommand.SetSpeed(1.5f),
            "pause",
        ).inOrder()
    }

    @Test
    fun `the sections skipped by the narration heard pass, another book's do not`() = runTest {
        val harness = PlayerHarness(this)
        val notices = mutableListOf<SkippedNotice>()
        harness.scope.launch { harness.controller.skipped.collect { notices += it } }

        // The ticker tells the sentence it reached before the sections it skipped to reach it.
        harness.cursor.publish(heard)
        harness.cursor.announce(SkippedNotice(BookId("42"), 2, "Interlude"))
        harness.cursor.announce(SkippedNotice(BookId("43"), 5, null))

        assertThat(notices).containsExactly(SkippedNotice(BookId("42"), 2, "Interlude"))
    }

    @Test
    fun `a narration asked forgets the audiobook asked before, so a failed narration offers no retry of it`() =
        runTest {
            val harness = PlayerHarness(this)
            val state = harness.watched()
            harness.controller.load(BookId("7"))

            harness.controller.narrate(BookId("42"), FileId("1"), NarrationStart.Resume)
            harness.show(SessionSnapshot(PlayerSessionExtras(loading = true)))
            harness.show(SessionSnapshot(PlayerSessionExtras(loadFailed = true)))
            harness.controller.retryLoad()

            assertThat(state.value.canRetryLoad).isFalse()
            assertThat(harness.connection.sent).containsExactly(
                PlayerCommand.LoadBook(BookId("7")),
                PlayerCommand.LoadNarration(BookId("42"), FileId("1")),
            ).inOrder()
        }
}
