package app.tfdn.lirelle.feature.downloads

import app.tfdn.lirelle.core.downloads.model.DownloadFailure
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.FormatState
import app.tfdn.lirelle.core.downloads.model.WaitReason
import app.tfdn.lirelle.core.ids.BookId
import com.google.common.truth.Truth.assertThat
import java.time.Duration
import org.junit.Test

class DownloadsUpdatesStateTest {

    private val kept = mapOf(
        BookId("1") to keptBook("Dune"),
        BookId("2") to keptBook("Red Mars"),
        BookId("3") to keptBook("Slow Horses"),
    )

    @Test
    fun `a book with an update that waits for the user is in the updates only, with what it would fetch`() {
        val books = listOf(updatable("1", bytes = 1_080 * Mb), downloaded("2"))

        val state = downloadsUiState(books, IdleQueue, kept, Storage)

        val update = state.updates.single()
        assertThat(update.book.bookId).isEqualTo(BookId("1"))
        assertThat(update.book.title).isEqualTo("Dune")
        assertThat(update.book.bytes).isEqualTo(4 * Mb)
        assertThat(update.updateBytes).isEqualTo(1_080 * Mb)
        assertThat(state.books.map { it.bookId }).containsExactly(BookId("2"))
        assertThat(state.updatesBytes).isEqualTo(1_080 * Mb)
        assertThat(state.storage.bookCount).isEqualTo(2)
    }

    @Test
    fun `the updates are in the order of the downloaded books`() {
        val books = listOf(updatable("1"), updatable("3").copy(lastOpenedAt = Day.plus(Duration.ofDays(1))))

        val state = downloadsUiState(books, IdleQueue, kept, Storage)

        assertThat(state.updates.map { it.book.bookId }).containsExactly(BookId("3"), BookId("1")).inOrder()
        assertThat(state.updatesBytes).isEqualTo(8 * Mb)
    }

    @Test
    fun `an update being fetched moves its book to those in progress, said as an update`() {
        val books = listOf(updatable("1", state = FormatState.Downloading(Mb, 4 * Mb)), downloaded("2"))
        val queue = IdleQueue.copy(active = updateEntry("1", receivedBytes = Mb))

        val state = downloadsUiState(books, queue, kept, Storage)

        assertThat(state.updates).isEmpty()
        assertThat(state.books.map { it.bookId }).containsExactly(BookId("2"))
        val row = state.inProgress.single()
        assertThat(row.update).isTrue()
        assertThat(row.status).isEqualTo(ProgressStatus.Active(Mb, 4 * Mb))
        assertThat(state.storage.bookCount).isEqualTo(2)
    }

    @Test
    fun `an update waiting for Wi-Fi and one that failed are in progress too`() {
        val books = listOf(
            updatable("1", state = FormatState.Waiting(WaitReason.UnmeteredOnly, 0, 4 * Mb)),
            updatable("2", state = FormatState.Failed(DownloadFailure.Damaged, 0)),
        )
        val failed = updateEntry("2").copy(failure = DownloadFailure.Damaged)
        val queue = IdleQueue.copy(waiting = listOf(updateEntry("1"), failed), waitReason = WaitReason.UnmeteredOnly)

        val state = downloadsUiState(books, queue, kept, Storage)

        assertThat(state.updates).isEmpty()
        assertThat(state.books).isEmpty()
        assertThat(state.inProgress.map { it.update }).containsExactly(true, true)
        assertThat(state.inProgress.map { it.status }).containsExactly(
            ProgressStatus.Waiting(WaitReason.UnmeteredOnly, 0, 4 * Mb),
            ProgressStatus.Failed(DownloadFailure.Damaged),
        ).inOrder()
    }

    @Test
    fun `a downloaded book fetching another format is not an update, and stays downloaded`() {
        val adding = record(DownloadFormat.Audiobook, FormatState.Queued)
        val book = downloaded("1").let { it.copy(formats = it.formats + adding) }

        val state = downloadsUiState(listOf(book), IdleQueue.copy(active = entry("1")), kept, Storage)

        assertThat(state.inProgress.single().update).isFalse()
        assertThat(state.books.map { it.bookId }).containsExactly(BookId("1"))
    }

    @Test
    fun `an update fetched while its book is open stays downloaded, with a note`() {
        val books = listOf(updatable("1", state = FormatState.Complete(4 * Mb)), downloaded("2"))

        val state = downloadsUiState(books, IdleQueue, kept, Storage)

        assertThat(state.updates).isEmpty()
        assertThat(state.books.map { it.note }).containsExactly(RowNote.UpdateReady, null).inOrder()
    }

    @Test
    fun `a book the server no longer has stays at its place, and says so`() {
        val gone = downloaded("2", downloadedAt = Day.plus(Duration.ofDays(1))).copy(gone = true)
        val books = listOf(downloaded("1"), gone, downloaded("3", downloadedAt = Day.plus(Duration.ofDays(2))))

        val state = downloadsUiState(books, IdleQueue, kept, Storage)

        assertThat(state.books.map { it.bookId }).containsExactly(BookId("3"), BookId("2"), BookId("1")).inOrder()
        assertThat(state.books.map { it.note }).containsExactly(null, RowNote.Gone, null).inOrder()
    }

    @Test
    fun `a book the server no longer has is offered no update`() {
        val books = listOf(updatable("1").copy(gone = true), updatable("2"))

        val state = downloadsUiState(books, IdleQueue, kept, Storage)

        assertThat(state.updates.map { it.book.bookId }).containsExactly(BookId("2"))
        assertThat(state.updatesBytes).isEqualTo(4 * Mb)
        assertThat(state.books.map { it.bookId to it.note }).containsExactly(BookId("1") to RowNote.Gone)
    }

    @Test
    fun `a book to delete when it closes is offered no update, and is not counted in the heading`() {
        val books = listOf(updatable("1").copy(deleteAtClose = true), updatable("2", bytes = 7 * Mb))

        val state = downloadsUiState(books, IdleQueue, kept, Storage)

        assertThat(state.updates.map { it.book.bookId }).containsExactly(BookId("2"))
        assertThat(state.updatesBytes).isEqualTo(7 * Mb)
        assertThat(state.books.map { it.bookId }).containsExactly(BookId("1"))
    }

    @Test
    fun `the downloaded books have their heading as soon as another section shows`() {
        val alone = downloadsUiState(listOf(downloaded("2")), IdleQueue, kept, Storage)
        val withUpdates = downloadsUiState(listOf(updatable("1"), downloaded("2")), IdleQueue, kept, Storage)
        val withProgress =
            downloadsUiState(listOf(fetching("1"), downloaded("2")), IdleQueue.copy(active = entry("1")), kept, Storage)
        val updatesOnly = downloadsUiState(listOf(updatable("1")), IdleQueue, kept, Storage)

        assertThat(alone.downloadedHeading).isFalse()
        assertThat(withUpdates.downloadedHeading).isTrue()
        assertThat(withProgress.downloadedHeading).isTrue()
        assertThat(updatesOnly.downloadedHeading).isFalse()
        assertThat(updatesOnly.isEmpty).isFalse()
    }
}
