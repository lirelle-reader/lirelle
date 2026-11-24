package app.tfdn.lirelle.feature.player.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tfdn.lirelle.core.ids.BookId
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

/** The downloaded book loaded in the service is held, so that it is not deleted while it plays. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class DownloadHoldTest {

    private val holds = RecordingHolds()
    private val downloads = FakeDownloads()

    private fun TestScope.hold() = DownloadHold(KeptBooks(downloads, FakeKeptBookData(), holds), backgroundScope)

    private fun book(bookId: BookId, kept: Boolean = true) = LoadedBook(
        bookId, BookPlaylist.of(Manifests.twoFiles(), null, { "Part $it" }) { "https://b/$it" }!!, null, 0, 1f,
        kept = kept,
    )

    @Test
    fun `a downloaded book is held while it is loaded, and let go once when it is closed`() = runTest {
        val hold = hold()

        hold.opened(book(BookId("42")))
        assertThat(holds.calls).containsExactly("+42")
        assertThat(hold.holds(BookId("42"))).isTrue()

        hold.closed()
        hold.closed()
        assertThat(holds.calls).containsExactly("+42", "-42").inOrder()
        assertThat(hold.holds(BookId("42"))).isFalse()
    }

    @Test
    fun `a book that is not downloaded is not held`() = runTest {
        val hold = hold()

        hold.opened(book(BookId("42"), kept = false))
        hold.closed()

        assertThat(holds.calls).isEmpty()
        assertThat(hold.holds(BookId("42"))).isFalse()
    }

    @Test
    fun `another book taking its place lets the first one go`() = runTest {
        val hold = hold()
        hold.opened(book(BookId("42")))

        hold.opened(book(BookId("43")))
        assertThat(holds.held).containsExactly(BookId("43"))

        hold.opened(book(BookId("44"), kept = false))
        assertThat(holds.held).isEmpty()
        assertThat(holds.calls).containsExactly("+42", "+43", "-42", "-43").inOrder()

        hold.closed()
        assertThat(holds.calls).hasSize(4)
    }

    @Test
    fun `the same book loaded again is never let go in between, and is held once`() = runTest {
        val hold = hold()
        hold.opened(book(BookId("42")))

        hold.opened(book(BookId("42")))

        assertThat(holds.calls).containsExactly("+42", "+42", "-42").inOrder()
        assertThat(holds.held).containsExactly(BookId("42"))
        hold.closed()
        assertThat(holds.held).isEmpty()
    }

    @Test
    fun `each book loaded tells its download it was opened`() = runTest {
        val hold = hold()

        hold.opened(book(BookId("42")))
        hold.opened(book(BookId("43"), kept = false))
        runCurrent()

        assertThat(downloads.opened).containsExactly(BookId("42"), BookId("43")).inOrder()
    }
}
