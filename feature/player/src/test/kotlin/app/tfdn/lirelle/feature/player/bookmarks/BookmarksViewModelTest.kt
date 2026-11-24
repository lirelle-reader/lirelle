package app.tfdn.lirelle.feature.player.bookmarks

import app.tfdn.lirelle.core.audio.bookmarks.AddOutcome
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.testing.MainDispatcherRule
import app.tfdn.lirelle.feature.player.PlayerViewModel
import app.tfdn.lirelle.feature.player.bookmarks.FakeAudioBookmarks.Companion.bookmark
import app.tfdn.lirelle.feature.player.controller.PlayerHarness
import app.tfdn.lirelle.feature.player.controller.SessionBooks
import app.tfdn.lirelle.feature.player.controller.SessionChapter
import app.tfdn.lirelle.feature.player.controller.SessionNarrations
import app.tfdn.lirelle.feature.player.controller.SessionSnapshot
import app.tfdn.lirelle.feature.player.service.PlayerCommand
import app.tfdn.lirelle.feature.player.service.PlayerSessionExtras
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/** The player's audio bookmarks, through its view model: adding, editing, deleting, going to one, reading again. */
@OptIn(ExperimentalCoroutinesApi::class)
class BookmarksViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    private lateinit var harness: PlayerHarness
    private val bookmarks = FakeAudioBookmarks()

    /** A view model watched by its screen, over a book playing at 1:10 (0:10 into "Middle", chapter c2). */
    private fun TestScope.viewModel(snapshot: SessionSnapshot = SessionBooks.at(chapter = 1)): PlayerViewModel {
        harness = PlayerHarness(this)
        harness.show(snapshot)
        return harness.viewModel(bookmarks = bookmarks)
            .also { viewModel ->
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.state.collect {} }
            }
    }

    @Test
    fun `a bookmark is added at the place playing, titled with the chapter and the time in it`() = runTest {
        val viewModel = viewModel()

        val outcome = viewModel.addBookmark()

        assertThat(bookmarks.asked).containsExactly("add 42 70000 c2 Middle · 0:10")
        assertThat(outcome).isInstanceOf(AddOutcome.Added::class.java)
        assertThat(viewModel.state.value.bookmarks.map { it.title }).containsExactly("Middle · 0:10")
    }

    @Test
    fun `an hour into a part shown as such, the time has its hours, and a chapter without an id gives none`() =
        runTest {
            // The playlist titles parts by their position ("Part 1"); a book without chapters has one, without an id.
            val part = listOf(SessionChapter("Part 1", 0, null))
            val viewModel = viewModel(SessionSnapshot(PlayerSessionExtras(SessionBooks.book), part, 0, 3_723_000))

            viewModel.addBookmark()

            assertThat(bookmarks.asked).containsExactly("add 42 3723000 null Part 1 · 1:02:03")
        }

    @Test
    fun `a chapter without a title gives the time in the book`() = runTest {
        val chapters = listOf(SessionChapter("Opening", 0, 60_000, chapterId = "c1"), SessionChapter("", 60_000, null))
        val viewModel = viewModel(SessionSnapshot(PlayerSessionExtras(SessionBooks.book), chapters, 1, 5_000))

        viewModel.addBookmark()

        assertThat(bookmarks.asked).containsExactly("add 42 65000 null 1:05")
    }

    @Test
    fun `a bookmark already at that second is what the add answers`() = runTest {
        val there = bookmark("b1", 70_400)
        bookmarks.answer = { AddOutcome.Existing(there) }
        val viewModel = viewModel()

        assertThat(viewModel.addBookmark()).isEqualTo(AddOutcome.Existing(there))
        bookmarks.answer = { AddOutcome.Taken(it.positionMs) }
        assertThat(viewModel.addBookmark()).isEqualTo(AddOutcome.Taken(70_000))
    }

    @Test
    fun `an add that fails is no bookmark, not a crash`() = runTest {
        bookmarks.addFailure = IOException("disk full")
        val viewModel = viewModel()

        assertThat(viewModel.addBookmark()).isNull()
        assertThat(viewModel.state.value.bookmarks).isEmpty()
    }

    @Test
    fun `a deletion cut short does not keep the bookmark hidden`() = runTest {
        val b1 = bookmark("b1", 10_000)
        bookmarks.put(BookId("42")) { listOf(b1) }
        bookmarks.deleteFailure = CancellationException("the app went")
        val viewModel = viewModel()

        viewModel.deleteBookmark(b1)
        viewModel.confirmDelete(b1)

        assertThat(viewModel.state.value.bookmarks).containsExactly(b1)
    }

    @Test
    fun `nothing is added without an audiobook`() = runTest {
        val viewModel = viewModel(SessionNarrations.at())

        assertThat(viewModel.addBookmark()).isNull()
        assertThat(bookmarks.asked).isEmpty()
    }

    @Test
    fun `the list is the book's, read again when a book is loaded and when asked`() = runTest {
        bookmarks.put(BookId("42")) { listOf(bookmark("b1", 10_000), bookmark("b2", 90_000)) }
        bookmarks.put(BookId("43")) { listOf(bookmark("b3", 5_000, bookId = BookId("43"))) }
        val viewModel = viewModel()

        assertThat(viewModel.state.value.bookmarks.map { it.id }).containsExactly("b1", "b2").inOrder()
        assertThat(bookmarks.refreshed).containsExactly(BookId("42"))

        harness.show(SessionBooks.at(chapter = 2))
        viewModel.refreshBookmarks()
        harness.show(SessionBooks.at(extras = PlayerSessionExtras(SessionBooks.book.copy(id = BookId("43")))))

        assertThat(bookmarks.refreshed).containsExactly(BookId("42"), BookId("42"), BookId("43")).inOrder()
        assertThat(viewModel.state.value.bookmarks.map { it.id }).containsExactly("b3")
    }

    @Test
    fun `a narration has neither bookmarks nor reading again`() = runTest {
        bookmarks.put(BookId("42")) { listOf(bookmark("b1", 10_000)) }
        val viewModel = viewModel(SessionNarrations.at())

        assertThat(viewModel.state.value.bookmarks).isEmpty()
        assertThat(bookmarks.refreshed).isEmpty()
    }

    @Test
    fun `an edit is sent for the bookmark's book`() = runTest {
        val viewModel = viewModel()

        viewModel.editBookmark(bookmark("b1", 10_000), "The ferry", "Wind at the bow")

        assertThat(bookmarks.asked).containsExactly("edit 42 b1 The ferry Wind at the bow")
    }

    @Test
    fun `a bookmark deleted leaves the list at once, and is deleted only once the snackbar went`() = runTest {
        val b1 = bookmark("b1", 10_000)
        bookmarks.put(BookId("42")) { listOf(b1, bookmark("b2", 90_000)) }
        val viewModel = viewModel()

        viewModel.deleteBookmark(b1)
        assertThat(viewModel.state.value.bookmarks.map { it.id }).containsExactly("b2")
        assertThat(bookmarks.asked).isEmpty()

        viewModel.confirmDelete(b1)

        assertThat(bookmarks.asked).containsExactly("delete 42 b1")
        assertThat(viewModel.state.value.bookmarks.map { it.id }).containsExactly("b2")
    }

    @Test
    fun `undo brings the bookmark back, and nothing is sent`() = runTest {
        val b1 = bookmark("b1", 10_000)
        bookmarks.put(BookId("42")) { listOf(b1) }
        val viewModel = viewModel()

        viewModel.deleteBookmark(b1)
        viewModel.undoDelete(b1)
        viewModel.confirmDelete(b1)

        assertThat(viewModel.state.value.bookmarks).containsExactly(b1)
        assertThat(bookmarks.asked).isEmpty()
    }

    @Test
    fun `going to a bookmark offers to go back where the user was`() = runTest {
        val viewModel = viewModel()

        viewModel.goToBookmark(bookmark("b1", 130_000))
        assertThat(viewModel.state.value.goBackShown).isTrue()
        harness.show(SessionBooks.at(chapter = 2, positionMs = 30_000))
        viewModel.goBack()

        assertThat(harness.connection.sent)
            .containsExactly(PlayerCommand.SeekBookTime(130_000), PlayerCommand.SeekBookTime(70_000)).inOrder()
        assertThat(viewModel.state.value.goBackShown).isFalse()
    }
}
