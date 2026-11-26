package app.tfdn.lirelle.feature.player.service

import android.os.Bundle
import androidx.media3.common.MediaMetadata
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tfdn.lirelle.core.audio.timer.SleepChoice
import app.tfdn.lirelle.core.audio.timer.SleepState
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.NextVolume
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayerSessionContractTest {

    private val commands = listOf(
        PlayerCommand.Back,
        PlayerCommand.Forward,
        PlayerCommand.PreviousChapter,
        PlayerCommand.NextChapter,
        PlayerCommand.SetSpeed(1.25f),
        PlayerCommand.NextSpeed,
        PlayerCommand.StartSleep(SleepChoice.Minutes(15)),
        PlayerCommand.StartSleep(SleepChoice.EndOfChapter),
        PlayerCommand.ExtendSleep,
        PlayerCommand.CancelSleep,
        PlayerCommand.LoadBook(BookId("1485"), fromStart = true, play = false),
        PlayerCommand.LoadBook(BookId("1486")),
        PlayerCommand.SeekBookTime(4_984_000),
        PlayerCommand.ListenToNext,
        PlayerCommand.CloseBook,
        PlayerCommand.LoadNarration(
            BookId("1451"), FileId("1452"), sectionIndex = 6, fragment = "p005-s3", play = false,
        ),
        PlayerCommand.LoadNarration(BookId("1451"), FileId("1452")),
        PlayerCommand.LoadNarration(BookId("1451"), FileId("1452"), sectionIndex = 6),
        PlayerCommand.PreviousSentence,
        PlayerCommand.NextSentence,
    )

    @Test
    fun `every command comes back from its session command and args`() {
        commands.forEach { command ->
            assertThat(PlayerCommand.of(command.sessionCommand.customAction, command.args())).isEqualTo(command)
        }
    }

    @Test
    fun `a narration starts at the sentence asked, at its section's first without one, else where it was left`() {
        val book = BookId("1")
        assertThat(PlayerCommand.LoadNarration(book, FileId("2"), sectionIndex = 6, fragment = "p005-s3").start)
            .isEqualTo(NarrationStart.AtSentence(6, "p005-s3"))
        assertThat(PlayerCommand.LoadNarration(book, FileId("2"), sectionIndex = 6).start)
            .isEqualTo(NarrationStart.AtSentence(6, ""))
        assertThat(PlayerCommand.LoadNarration(book, FileId("2"), fragment = "p005-s3").start)
            .isEqualTo(NarrationStart.Resume)
        assertThat(PlayerCommand.LoadNarration(book, FileId("2")).start).isEqualTo(NarrationStart.Resume)
    }

    @Test
    fun `every command is among the actions the session accepts`() {
        assertThat(PlayerSessionContract.actions).containsAtLeastElementsIn(commands.map { it.action }.toSet())
    }

    @Test
    fun `an unknown action or a command without its args is nothing`() {
        assertThat(PlayerCommand.of("elsewhere.SOMETHING", Bundle.EMPTY)).isNull()
        assertThat(PlayerCommand.of(PlayerSessionContract.ACTION_LOAD_BOOK, Bundle.EMPTY)).isNull()
        assertThat(PlayerCommand.of(PlayerSessionContract.ACTION_SET_SPEED, Bundle.EMPTY)).isNull()
        val bookAlone = Bundle().apply { putString(PlayerSessionContract.ARG_BOOK_ID, "1451") }
        assertThat(PlayerCommand.of(PlayerSessionContract.ACTION_LOAD_NARRATION, bookAlone)).isNull()
    }

    @Test
    fun `the extras come back from their bundle`() {
        val extras = PlayerSessionExtras(
            book = SessionBook(
                id = BookId("7"),
                title = "A Title",
                authors = listOf("Anne Verlaine", "Paul Ardent"),
                narrators = listOf("Thomas"),
                coverUrl = "https://c/7",
                totalMs = 9_000,
                durationsKnown = false,
                artworkUrl = "https://c/7/small",
                inParts = true,
            ),
            offline = true,
            unreachable = true,
            sleep = SleepState(SleepChoice.Minutes(30), remainingMs = 61_000, fading = false),
            finished = FinishedBook(NextVolume(BookId("8"), "Next One", "2")),
        )

        assertThat(PlayerSessionExtras.from(extras.toBundle())).isEqualTo(extras)
    }

    @Test
    fun `a book is in chapters unless its extras say it is in parts`() {
        val book = SessionBook(BookId("7"), "A Title", emptyList(), emptyList(), null, 9_000)

        assertThat(PlayerSessionExtras.from(PlayerSessionExtras(book).toBundle()).book?.inParts).isFalse()
    }

    @Test
    fun `a narration's extras come back from their bundle, an audiobook has none`() {
        val narration = PlayerSessionExtras(narrationFileId = FileId("1452"), narrationFinished = true)

        assertThat(PlayerSessionExtras.from(narration.toBundle())).isEqualTo(narration)
        assertThat(PlayerSessionExtras.from(PlayerSessionExtras().toBundle()).narrationFileId).isNull()
        assertThat(PlayerSessionExtras.from(PlayerSessionExtras().toBundle()).narrationFinished).isFalse()
    }

    @Test
    fun `empty extras are no book, no timer, not finished`() {
        assertThat(PlayerSessionExtras.from(Bundle())).isEqualTo(PlayerSessionExtras())
        val endOfChapter = PlayerSessionExtras(sleep = SleepState(SleepChoice.EndOfChapter, null, false))
        assertThat(PlayerSessionExtras.from(endOfChapter.toBundle())).isEqualTo(endOfChapter)
        val finishedAlone = PlayerSessionExtras(finished = FinishedBook(null))
        assertThat(PlayerSessionExtras.from(finishedAlone.toBundle())).isEqualTo(finishedAlone)
    }

    @Test
    fun `a chapter's start is read from its metadata`() {
        val extras = Bundle().apply { putLong(PlayerSessionContract.EXTRA_CHAPTER_START_MS, 797_650) }

        assertThat(PlayerSessionContract.chapterStartMs(MediaMetadata.Builder().setExtras(extras).build()))
            .isEqualTo(797_650)
        assertThat(PlayerSessionContract.chapterStartMs(MediaMetadata.EMPTY)).isEqualTo(0)
    }

    @Test
    fun `the last book is offered for resumption as an item that names it`() {
        val item = PlayerSessionContract.resumeItem(LastBook(BookId("42"), "The Long Road", "https://c/42"))

        assertThat(item.mediaId).isEqualTo("resume/42")
        assertThat(PlayerSessionContract.resumedBookId(item.mediaId)).isEqualTo(BookId("42"))
        assertThat(item.mediaMetadata.title.toString()).isEqualTo("The Long Road")
        assertThat(item.mediaMetadata.isPlayable).isTrue()
        assertThat(PlayerSessionContract.resumedBookId("42/3")).isNull()
        assertThat(PlayerSessionContract.resumedBookId("resume/")).isNull()
    }
}
