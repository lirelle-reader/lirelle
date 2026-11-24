package app.tfdn.lirelle.core.downloads.offline

import app.tfdn.lirelle.core.downloads.engine.MemoryRegistry
import app.tfdn.lirelle.core.downloads.model.DownloadFailure
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.DownloadedBook
import app.tfdn.lirelle.core.downloads.model.FormatRecord
import app.tfdn.lirelle.core.downloads.model.FormatState
import app.tfdn.lirelle.core.downloads.model.FormatUpdate
import app.tfdn.lirelle.core.downloads.model.WaitReason
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.offline.DownloadMark
import app.tfdn.lirelle.core.library.offline.DownloadMarks
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RegistryDownloadMarksTest {
    private val registry = MemoryRegistry()
    private val marks = RegistryDownloadMarks(registry)
    private val complete = FormatState.Complete(10)
    private val failed = FormatState.Failed(DownloadFailure.Damaged, 4)

    private fun update(state: FormatState? = null) = FormatUpdate("v2", emptyList(), bytes = 5, state = state)

    private fun record(
        state: FormatState,
        format: DownloadFormat = DownloadFormat.Text,
        update: FormatUpdate? = null,
        removing: Boolean = false,
    ) = FormatRecord(format, state, emptyList(), null, "v1", Instant.EPOCH, removing = removing, update = update)

    private fun book(vararg formats: FormatRecord) = DownloadedBook(BookId("1"), formats.toList(), Instant.EPOCH, null)

    private suspend fun markOf(book: DownloadedBook): DownloadMark? {
        registry.update(book.bookId) { book }
        return marks.marks.first()[book.bookId]
    }

    @Test
    fun `a book with a complete format is kept`() = runTest {
        assertThat(markOf(book(record(complete)))).isEqualTo(DownloadMark.Kept)
    }

    @Test
    fun `a book with a format queued, downloading or waiting is in progress`() = runTest {
        val running = listOf(
            FormatState.Queued,
            FormatState.Downloading(1, 10),
            FormatState.Waiting(WaitReason.NoNetwork, 1, 10),
            FormatState.Waiting(WaitReason.UnmeteredOnly, 0, null),
        )

        running.forEach { assertThat(markOf(book(record(it)))).isEqualTo(DownloadMark.InProgress) }
    }

    @Test
    fun `a book whose update is being fetched is in progress`() = runTest {
        assertThat(markOf(book(record(complete, update = update(FormatState.Downloading(1, 5))))))
            .isEqualTo(DownloadMark.InProgress)
    }

    @Test
    fun `a book with an update that waits for the user has the update mark`() = runTest {
        assertThat(markOf(book(record(complete, update = update())))).isEqualTo(DownloadMark.Update)
    }

    @Test
    fun `in progress comes before an update, and an update before kept`() = runTest {
        val updatable = record(complete, update = update())
        val fetching = record(FormatState.Queued, DownloadFormat.Audiobook)
        val kept = record(complete, DownloadFormat.Audiobook)

        assertThat(markOf(book(updatable, fetching))).isEqualTo(DownloadMark.InProgress)
        assertThat(markOf(book(updatable, kept))).isEqualTo(DownloadMark.Update)
        assertThat(markOf(book(kept, record(failed)))).isEqualTo(DownloadMark.Kept)
    }

    @Test
    fun `a book whose update failed is kept as it is`() = runTest {
        assertThat(markOf(book(record(complete, update = update(failed))))).isEqualTo(DownloadMark.Kept)
    }

    @Test
    fun `a book with only failures, or with nothing, has no mark`() = runTest {
        assertThat(markOf(book(record(failed), record(failed, DownloadFormat.Audiobook)))).isNull()
        assertThat(markOf(book())).isNull()
        assertThat(marks.marks.first()).isEmpty()
    }

    @Test
    fun `a format being deleted does not count`() = runTest {
        assertThat(markOf(book(record(complete, removing = true)))).isNull()
        assertThat(markOf(book(record(FormatState.Queued, removing = true)))).isNull()
        assertThat(markOf(book(record(complete, update = update(), removing = true)))).isNull()
        assertThat(markOf(book(record(complete, removing = true), record(complete, DownloadFormat.Audiobook))))
            .isEqualTo(DownloadMark.Kept)
    }

    @Test
    fun `a book to delete at close has no mark`() = runTest {
        assertThat(markOf(book(record(complete)).copy(deleteAtClose = true))).isNull()
        assertThat(markOf(book(record(complete, update = update())).copy(deleteAtClose = true))).isNull()
    }

    @Test
    fun `the marks follow the registry`() = runTest {
        val seen = mutableListOf<Map<BookId, DownloadMark>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { marks.marks.toList(seen) }

        registry.update(BookId("1")) { book(record(FormatState.Queued)) }
        registry.update(BookId("1")) { book(record(complete)) }
        registry.update(BookId("2")) { book(record(complete, update = update())) }
        registry.update(BookId("1")) { null }

        assertThat(seen).containsExactly(
            emptyMap<BookId, DownloadMark>(),
            mapOf(BookId("1") to DownloadMark.InProgress),
            mapOf(BookId("1") to DownloadMark.Kept),
            mapOf(BookId("1") to DownloadMark.Kept, BookId("2") to DownloadMark.Update),
            mapOf(BookId("2") to DownloadMark.Update),
        ).inOrder()
    }

    @Test
    fun `without downloads there is no mark`() = runTest {
        assertThat(DownloadMarks.None.marks.first()).isEmpty()
    }
}
