package app.tfdn.lirelle.core.downloads.engine

import app.tfdn.lirelle.core.downloads.model.DownloadFormat.Audiobook
import app.tfdn.lirelle.core.downloads.model.DownloadFormat.Text
import app.tfdn.lirelle.core.downloads.model.FormatState
import app.tfdn.lirelle.core.downloads.offline.RegistryDownloadedFiles
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** A book deleted from where it is open goes when it closes. */
@OptIn(ExperimentalCoroutinesApi::class)
class ClosingDeletionTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val bookOne = BookId("1")

    private fun queueTest(test: suspend QueueFixture.(TestScope) -> Unit) = runTest {
        QueueFixture(this, folder.newFolder("downloads")).test(this)
    }

    /** Book 1 has its text and its audiobook whole. */
    private suspend fun QueueFixture.downloaded() {
        serveBook(bookOne)
        queue.ask(bookOne, setOf(Text, Audiobook))
        runner.runUntilIdle()
    }

    private suspend fun QueueFixture.assertGone() {
        assertThat(registry.book(bookOne)).isNull()
        assertThat(files.bookDir(bookOne).exists()).isFalse()
        assertThat(kept.removed).contains(bookOne to null)
    }

    private suspend fun QueueFixture.assertKept(deleteAtClose: Boolean) {
        val book = checkNotNull(queue.book(bookOne).first())
        assertThat(book.deleteAtClose).isEqualTo(deleteAtClose)
        assertThat(book.formats.map { it.format to it.state })
            .containsExactly(Text to FormatState.Complete(40), Audiobook to FormatState.Complete(100))
        assertThat(file(bookOne, Text, "1.epub").exists()).isTrue()
        assertThat(file(bookOne, Audiobook, "a1").exists()).isTrue()
        assertThat(kept.removed).isEmpty()
    }

    @Test
    fun `a book nobody holds is deleted at once, every format with what was kept of it`() = queueTest {
        downloaded()

        closing.deleteAtClose(bookOne)

        assertGone()
        assertThat(registry.books.first()).isEmpty()
    }

    @Test
    fun `a deletion whose asker goes away as it starts is still carried to its end`() = queueTest {
        downloaded()
        val disposal = Disposal(registry, files, kept, StandardTestDispatcher(it.testScheduler))
        val flagged = FlaggedBooks(registry, disposal, remover::unlessHeld)
        // The reader closes, and its scope with it, while the first step runs.
        lateinit var asked: Job
        val deletion = ClosingDeletion(registry, flagged) { asked.cancel() }

        asked = it.launch { deletion.deleteAtClose(bookOne) }
        it.runCurrent()

        assertThat(asked.isCancelled).isTrue()
        assertGone()
    }

    @Test
    fun `a book that is open stays until it is let go of, and opens from its copy meanwhile`() = queueTest {
        downloaded()
        remover.hold(bookOne)

        closing.deleteAtClose(bookOne)
        it.runCurrent()

        assertKept(deleteAtClose = true)
        val downloaded = RegistryDownloadedFiles(registry, files, locations)
        downloaded.start(it.backgroundScope)
        it.runCurrent()
        assertThat(downloaded.text(bookOne, FileId("1"))).isNotNull()
        assertThat(downloaded.audio("https://books.example/audio/1/a1")).isNotNull()

        remover.release(bookOne)
        it.runCurrent()

        assertGone()
    }

    @Test
    fun `a book held twice goes when the last holder lets go`() = queueTest {
        downloaded()
        remover.hold(bookOne)
        remover.hold(bookOne)
        closing.deleteAtClose(bookOne)

        remover.release(bookOne)
        it.runCurrent()

        assertKept(deleteAtClose = true)

        remover.release(bookOne)
        it.runCurrent()

        assertGone()
    }

    @Test
    fun `a deletion taken back before the book closes deletes nothing`() = queueTest {
        downloaded()
        remover.hold(bookOne)
        closing.deleteAtClose(bookOne)

        closing.keepAfterAll(bookOne)
        remover.release(bookOne)
        it.runCurrent()

        assertKept(deleteAtClose = false)
    }

    @Test
    fun `taking back the deletion of a book that is not there records nothing`() = queueTest {
        closing.keepAfterAll(bookOne)
        closing.deleteAtClose(bookOne)

        assertThat(registry.books.first()).isEmpty()
    }

    @Test
    fun `a book left to delete by a process that ended while it was open is deleted at the next start`() =
        queueTest {
            downloaded()
            remover.hold(bookOne)
            closing.deleteAtClose(bookOne)
            val restarted = QueueFixture(it, files.bookDir(BookId("0")).parentFile!!).also { fresh ->
                registry.books.first().forEach { book -> fresh.registry.update(book.bookId) { book } }
                fresh.network.value = NoNetwork
            }

            restarted.runner.runUntilIdle()

            restarted.assertGone()
        }

    @Test
    fun `the downloads and the updates in progress are cancelled, and are not started again`() = queueTest {
        serveBook(bookOne)
        queue.ask(bookOne, setOf(Text))
        runner.runUntilIdle()
        serve(bookOne, Text, epub("1.epub", bytes = 55, version = "v2"), version = "v2")
        updates.checkBook(bookOne)
        updates.update(bookOne)
        network.value = NoNetwork
        queue.ask(bookOne, setOf(Audiobook))
        remover.hold(bookOne)

        closing.deleteAtClose(bookOne)

        val flagged = checkNotNull(registry.book(bookOne))
        assertThat(flagged.deleteAtClose).isTrue()
        assertThat(flagged.formats.map { it.format to it.state }).containsExactly(Text to FormatState.Complete(40))
        assertThat(flagged.formats.single().update?.state).isNull()
        assertThat(queue.queue.first().waiting).isEmpty()

        closing.keepAfterAll(bookOne)
        network.value = Wifi
        runner.runUntilIdle()

        val kept = checkNotNull(registry.book(bookOne))
        assertThat(kept.deleteAtClose).isFalse()
        assertThat(kept.formats.map { it.format }).containsExactly(Text)
        assertThat(kept.formats.single().version).isEqualTo("v1")
        assertThat(fetcher.fetched).containsExactly("1/Text/1.epub")
        assertThat(updateFetcher.fetched).isEmpty()
    }

    @Test
    fun `a book whose only format was still on its way is simply taken out of the queue`() = queueTest {
        network.value = NoNetwork
        serveBook(bookOne)
        queue.ask(bookOne, setOf(Text))

        closing.deleteAtClose(bookOne)

        assertThat(registry.book(bookOne)).isNull()
        assertThat(files.bookDir(bookOne).exists()).isFalse()
    }

    @Test
    fun `nothing is swapped into a book that goes as it closes`() = queueTest {
        downloaded()
        serve(bookOne, Audiobook, audio("a1", bytes = 70, version = "v2"), version = "v2")
        remover.hold(bookOne)
        updates.checkBook(bookOne)
        updates.update(bookOne)
        runner.runUntilIdle()
        assertThat(files.updates.file(bookOne, Audiobook, "a1").length()).isEqualTo(70)
        // As a process that ended between the two would leave it: flagged, its update whole and waiting.
        registry.update(bookOne) { book -> book?.copy(deleteAtClose = true) }
        registry.states.clear()

        remover.release(bookOne)
        it.runCurrent()

        assertGone()
        assertThat(registry.states).doesNotContain(Audiobook to FormatState.Complete(70))
    }

    @Test
    fun `at the start a book to delete goes before the update that waited is swapped into it`() = queueTest {
        downloaded()
        serve(bookOne, Audiobook, audio("a1", bytes = 70, version = "v2"), version = "v2")
        remover.hold(bookOne)
        updates.checkBook(bookOne)
        updates.update(bookOne)
        runner.runUntilIdle()
        registry.update(bookOne) { book -> book?.copy(deleteAtClose = true) }
        val restarted = QueueFixture(it, files.bookDir(BookId("0")).parentFile!!).also { fresh ->
            registry.books.first().forEach { book -> fresh.registry.update(book.bookId) { book } }
            fresh.network.value = NoNetwork
        }
        restarted.registry.states.clear()

        restarted.runner.runUntilIdle()

        restarted.assertGone()
        assertThat(restarted.registry.states).doesNotContain(Audiobook to FormatState.Complete(70))
    }
}
