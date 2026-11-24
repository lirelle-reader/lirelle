package app.tfdn.lirelle.feature.account

import app.tfdn.lirelle.core.audio.bookmarks.BookmarkChange
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.reader.marks.MarkChange
import app.tfdn.lirelle.core.reader.marks.MarkId
import app.tfdn.lirelle.core.library.net.NetworkState
import app.tfdn.lirelle.core.library.net.ProfileNetwork
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test

private val One = BookId("1")
private val Two = BookId("2")

class PendingWritesTest {

    private fun id(value: String) = MarkId(value)

    @Test
    fun `nothing waiting counts nothing`() {
        val counts = pendingCounts(positions = 0, sessions = 0, marks = emptyList(), audioBookmarks = emptyList())

        assertThat(counts).isEqualTo(PendingCounts())
        assertThat(counts.isEmpty).isTrue()
    }

    @Test
    fun `positions and sessions are counted as given`() {
        val counts = pendingCounts(positions = 3, sessions = 2, marks = emptyList(), audioBookmarks = emptyList())

        assertThat(counts).isEqualTo(PendingCounts(positions = 3, sessions = 2))
        assertThat(counts.isEmpty).isFalse()
    }

    @Test
    fun `the bookmarks of the text and of the audiobook are counted together, each once`() {
        val counts = pendingCounts(
            positions = 0,
            sessions = 0,
            marks = listOf(
                MarkChange.AddBookmark(One, id("local-1"), fileId = FileId("11"), cfi = "epubcfi(/6/4)", title = "One"),
                MarkChange.DeleteBookmark(One, id("b7")),
            ),
            audioBookmarks = listOf(
                BookmarkChange.Edit(Two, "a", title = "Two", note = null),
                BookmarkChange.Edit(Two, "a", title = "Two again", note = "A note"),
                BookmarkChange.Delete(Two, "b"),
                // The same id in another book is another bookmark.
                BookmarkChange.Delete(BookId("3"), "b"),
            ),
        )

        assertThat(counts).isEqualTo(PendingCounts(bookmarks = 5))
    }

    @Test
    fun `the changes of one highlight count as one highlight`() {
        val counts = pendingCounts(
            positions = 0,
            sessions = 0,
            marks = listOf(
                MarkChange.ChangeHighlight(One, id("h4"), color = "yellow", note = null),
                MarkChange.DeleteHighlight(One, id("h4")),
                MarkChange.RestoreHighlight(One, id("h9")),
                MarkChange.DeleteHighlight(Two, id("h4")),
            ),
            audioBookmarks = emptyList(),
        )

        assertThat(counts).isEqualTo(PendingCounts(highlights = 3))
    }

    @Test
    fun `with a network nothing is at risk, and nothing is read`() = runTest {
        var reads = 0
        val writes = PendingWrites(read = { reads++; PendingCounts(positions = 2) }, online = { true })

        assertThat(writes.atRisk()).isEqualTo(PendingCounts())
        assertThat(reads).isEqualTo(0)
    }

    @Test
    fun `without a network what waits is at risk`() = runTest {
        val writes = PendingWrites(read = { PendingCounts(positions = 2, bookmarks = 1) }, online = { false })

        assertThat(writes.atRisk()).isEqualTo(PendingCounts(positions = 2, bookmarks = 1))
    }

    /** The profile's network, over a device that is [deviceOnline], for a session that waits for a sign-in or not. */
    private fun TestScope.network(deviceOnline: Boolean, signInNeeded: Boolean) = ProfileNetwork(
        device = MutableStateFlow(NetworkState(online = deviceOnline, unmetered = true)),
        deviceReturns = emptyFlow(),
        signInNeeded = MutableStateFlow(signInNeeded),
        life = backgroundScope,
    )

    @Test
    fun `a profile that waits for a sign-in has what waits at risk, even with the device online`() = runTest {
        val waiting = PendingCounts(positions = 2, highlights = 1)
        val writes = PendingWrites(read = { waiting }, network = network(deviceOnline = true, signInNeeded = true))

        assertThat(writes.atRisk()).isEqualTo(waiting)
    }

    @Test
    fun `a profile whose session is good has nothing at risk with the device online`() = runTest {
        val writes = PendingWrites(
            read = { PendingCounts(positions = 2) },
            network = network(deviceOnline = true, signInNeeded = false),
        )

        assertThat(writes.atRisk()).isEqualTo(PendingCounts())
    }

    @Test
    fun `a profile whose session is good has what waits at risk with the device offline`() = runTest {
        val writes = PendingWrites(
            read = { PendingCounts(sessions = 3) },
            network = network(deviceOnline = false, signInNeeded = false),
        )

        assertThat(writes.atRisk()).isEqualTo(PendingCounts(sessions = 3))
    }
}
