package app.tfdn.lirelle.core.downloads

import app.tfdn.lirelle.core.downloads.model.DeviceCopy
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.DownloadedBook
import app.tfdn.lirelle.core.downloads.model.FormatRecord
import app.tfdn.lirelle.core.downloads.model.FormatState
import app.tfdn.lirelle.core.ids.BookId
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** The books of the device, and the deletions asked: a held book is flagged, another goes at once. */
private class Device : Downloads, ClosingDeletions {
    val kept = MutableStateFlow<Map<BookId, DownloadedBook>>(emptyMap())
    val held = mutableSetOf<BookId>()
    val asked = mutableListOf<String>()

    /** When set, a deletion waits for it. */
    var gate: CompletableDeferred<Unit>? = null
    var failure: IOException? = null

    fun keep(bookId: BookId, vararg formats: DownloadFormat) {
        val whole = FormatState.Complete(500)
        val records = formats.map { FormatRecord(it, whole, emptyList(), null, null, Instant.EPOCH) }
        kept.update { it + (bookId to DownloadedBook(bookId, records, Instant.EPOCH, lastOpenedAt = null)) }
    }

    override val books: Flow<List<DownloadedBook>> = kept.map { it.values.toList() }
    override val queue: Flow<QueueState> = flowOf(QueueState(null, emptyList(), null, null))

    override fun book(bookId: BookId): Flow<DownloadedBook?> = kept.map { it[bookId] }

    override suspend fun ask(bookId: BookId, formats: Set<DownloadFormat>): AskRefusal? = null

    override suspend fun cancel(bookId: BookId, format: DownloadFormat?) = Unit

    override suspend fun retry(bookId: BookId) = Unit

    override suspend fun allowAnyNetworkNow() = Unit

    override suspend fun markOpened(bookId: BookId) = Unit

    override suspend fun deleteAtClose(bookId: BookId) {
        asked += "delete $bookId"
        gate?.await()
        failure?.let { throw it }
        kept.update { books ->
            val book = books[bookId]?.takeIf { bookId in held }
            if (book == null) books - bookId else books + (bookId to book.copy(deleteAtClose = true))
        }
    }

    override suspend fun keepAfterAll(bookId: BookId) {
        asked += "keep $bookId"
        kept.update { books -> books[bookId]?.let { books + (bookId to it.copy(deleteAtClose = false)) } ?: books }
    }
}

/** The end panels' row: what the device keeps of the book shown, "Delete" and "Undo". */
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceCopyControlTest {

    private val device = Device().apply {
        keep(BookId("7"), DownloadFormat.Text, DownloadFormat.Audiobook)
        held += BookId("7")
    }

    /** What the control's scope was left with. */
    private val uncaught = mutableListOf<Throwable>()

    private fun TestScope.control(): DeviceCopyControl {
        val context = backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler) +
            CoroutineExceptionHandler { _, failure -> uncaught += failure }
        return DeviceCopyControl(DeviceCopySources(device, device), CoroutineScope(context))
    }

    private val whole = DeviceCopy(listOf(DownloadFormat.Text, DownloadFormat.Audiobook), 1_000, deleting = false)

    @Test
    fun `nothing is shown before a book is, nor for a book the device does not keep`() = runTest {
        val control = control()
        assertThat(control.copy.value).isNull()

        control.show(BookId("8"))
        assertThat(control.copy.value).isNull()

        control.deleteFromDevice()
        control.keepOnDevice()
        assertThat(device.asked).isEmpty()
    }

    @Test
    fun `the copy of the book shown, then of the one that takes its place`() = runTest {
        val control = control()

        control.show(BookId("7"))
        assertThat(control.copy.value).isEqualTo(whole)

        device.keep(BookId("9"), DownloadFormat.Narration)
        control.show(BookId("9"))
        assertThat(control.copy.value).isEqualTo(DeviceCopy(listOf(DownloadFormat.Narration), 500, deleting = false))

        control.show(null)
        assertThat(control.copy.value).isNull()
    }

    @Test
    fun `delete flags the held book, undo takes it back`() = runTest {
        val control = control().apply { show(BookId("7")) }

        control.deleteFromDevice()
        assertThat(control.copy.value).isEqualTo(whole.copy(deleting = true))

        control.keepOnDevice()
        assertThat(control.copy.value).isEqualTo(whole)
        assertThat(device.asked).containsExactly("delete 7", "keep 7").inOrder()
    }

    @Test
    fun `a book that nothing holds is gone at once`() = runTest {
        device.held.clear()
        val control = control().apply { show(BookId("7")) }

        control.deleteFromDevice()

        assertThat(control.copy.value).isNull()
        control.keepOnDevice()
        assertThat(device.asked).containsExactly("delete 7")
    }

    @Test
    fun `the deletion is asked once, however many taps`() = runTest {
        device.gate = CompletableDeferred()
        val control = control().apply { show(BookId("7")) }

        control.deleteFromDevice()
        control.deleteFromDevice()
        device.gate?.complete(Unit)
        control.deleteFromDevice()

        assertThat(device.asked).containsExactly("delete 7")
        assertThat(control.copy.value?.deleting).isTrue()
    }

    @Test
    fun `undo is not asked of a book that stays`() = runTest {
        val control = control().apply { show(BookId("7")) }

        control.keepOnDevice()

        assertThat(device.asked).isEmpty()
    }

    @Test
    fun `a deletion that could not be written can be asked again`() = runTest {
        device.failure = IOException("no room for the registry")
        val control = control().apply { show(BookId("7")) }

        control.deleteFromDevice()
        assertThat(control.copy.value).isEqualTo(whole)
        assertThat(uncaught).isEmpty()

        device.failure = null
        control.deleteFromDevice()
        assertThat(device.asked).containsExactly("delete 7", "delete 7")
        assertThat(control.copy.value?.deleting).isTrue()
    }
}
