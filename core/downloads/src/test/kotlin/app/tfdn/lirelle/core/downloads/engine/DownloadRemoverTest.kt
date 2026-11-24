package app.tfdn.lirelle.core.downloads.engine

import app.tfdn.lirelle.core.downloads.DeleteResult
import app.tfdn.lirelle.core.downloads.model.DownloadFormat.Audiobook
import app.tfdn.lirelle.core.downloads.model.DownloadFormat.Narration
import app.tfdn.lirelle.core.downloads.model.DownloadFormat.Text
import app.tfdn.lirelle.core.downloads.model.DownloadFailure
import app.tfdn.lirelle.core.downloads.model.FormatState
import app.tfdn.lirelle.core.ids.BookId
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadRemoverTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val bookOne = BookId("1")
    private val bookTwo = BookId("2")

    private fun queueTest(test: suspend QueueFixture.(TestScope) -> Unit) = runTest {
        QueueFixture(this, folder.newFolder("downloads")).test(this)
    }

    /** Book 1 with its text and its audiobook complete. */
    private suspend fun QueueFixture.downloaded() {
        serveBook(bookOne)
        queue.ask(bookOne, setOf(Text, Audiobook))
        runner.runUntilIdle()
    }

    @Test
    fun `a deleted format is hidden at once, and its files go after the undo window`() = queueTest {
        downloaded()

        assertThat(remover.delete(bookOne, setOf(Audiobook))).isEqualTo(DeleteResult.Deleted)

        assertThat(checkNotNull(queue.book(bookOne).first()).formats.map { it.format }).containsExactly(Text)
        assertThat(file(bookOne, Audiobook, "a1").exists()).isTrue()
        it.advanceTimeBy(9_999)
        it.runCurrent()
        assertThat(file(bookOne, Audiobook, "a1").exists()).isTrue()
        it.advanceTimeBy(1)
        it.runCurrent()
        assertThat(file(bookOne, Audiobook, "a1").exists()).isFalse()
        assertThat(checkNotNull(registry.book(bookOne)).formats.map { it.format }).containsExactly(Text)
        assertThat(kept.removed).containsExactly(bookOne to Audiobook)
        assertThat(file(bookOne, Text, "1.epub").exists()).isTrue()
    }

    @Test
    fun `undoing brings the format back with its files`() = queueTest {
        downloaded()
        remover.delete(bookOne, setOf(Audiobook))
        it.advanceTimeBy(3_000)

        remover.undoDelete(bookOne)
        it.advanceTimeBy(10_000)

        assertThat(checkNotNull(queue.book(bookOne).first()).formats.map { it.format })
            .containsExactly(Text, Audiobook)
        assertThat(state(bookOne, Audiobook)).isEqualTo(FormatState.Complete(100))
        assertThat(file(bookOne, Audiobook, "a1").exists()).isTrue()
        assertThat(kept.removed).isEmpty()
    }

    @Test
    fun `the last format takes the book and what was kept of it`() = queueTest {
        downloaded()

        remover.delete(bookOne, setOf(Text, Audiobook))
        assertThat(queue.books.first()).isEmpty()
        it.advanceTimeBy(10_000)
        it.runCurrent()

        assertThat(registry.book(bookOne)).isNull()
        assertThat(files.bookDir(bookOne).exists()).isFalse()
        assertThat(kept.removed).contains(bookOne to null)
    }

    @Test
    fun `a book in use is not deleted, until it is released by all that hold it`() = queueTest {
        downloaded()
        remover.hold(bookOne)
        remover.hold(bookOne)
        remover.release(bookOne)

        assertThat(remover.delete(bookOne, setOf(Text, Audiobook))).isEqualTo(DeleteResult.InUse)
        it.advanceTimeBy(10_000)
        assertThat(checkNotNull(queue.book(bookOne).first()).formats).hasSize(2)
        assertThat(file(bookOne, Text, "1.epub").exists()).isTrue()

        remover.release(bookOne)
        assertThat(remover.delete(bookOne, setOf(Audiobook))).isEqualTo(DeleteResult.Deleted)
    }

    @Test
    fun `the EPUB stays while the narration that uses it does`() = queueTest {
        serve(bookOne, Text, epub())
        serve(bookOne, Narration, epub(), audio("t1"))
        queue.ask(bookOne, setOf(Narration))
        runner.runUntilIdle()

        remover.delete(bookOne, setOf(Text))
        it.advanceTimeBy(10_000)
        it.runCurrent()

        assertThat(file(bookOne, Narration, "12.epub").exists()).isTrue()
        assertThat(file(bookOne, Narration, "t1").exists()).isTrue()
    }

    @Test
    fun `a deletion pending when the app starts is finished at once`() = queueTest {
        registry.update(bookOne) {
            val going = queued(Audiobook, now()).copy(state = FormatState.Complete(3), removing = true)
            asked(null, bookOne, setOf(Text), now()).let { it.copy(formats = it.formats + going) }
        }
        file(bookOne, Audiobook, "a1").apply { checkNotNull(parentFile).mkdirs() }.writeText("abc")
        network.value = NoNetwork

        runner.runUntilIdle()

        assertThat(checkNotNull(registry.book(bookOne)).formats.map { it.format }).containsExactly(Text)
        assertThat(file(bookOne, Audiobook, "a1").exists()).isFalse()
    }

    @Test
    fun `everything goes at once, the queue too`() = queueTest {
        downloaded()
        network.value = NoNetwork
        queue.ask(bookTwo, setOf(Text))

        remover.deleteAll()

        assertThat(registry.books.first()).isEmpty()
        assertThat(files.bookDir(bookOne).exists()).isFalse()
    }

    @Test
    fun `a complete format whose file is missing or cut short when the app starts is to download again`() =
        queueTest {
            downloaded()
            serveBook(bookTwo)
            queue.ask(bookTwo, setOf(Text, Audiobook))
            runner.runUntilIdle()
            file(bookOne, Audiobook, "a1").delete()
            file(bookTwo, Text, "2.epub").writeText("short")
            val restarted = QueueFixture(it, files.bookDir(BookId("0")).parentFile!!).also { fresh ->
                registry.books.first().forEach { book -> fresh.registry.update(book.bookId) { book } }
                fresh.network.value = NoNetwork
            }

            restarted.runner.runUntilIdle()

            assertThat(restarted.state(bookOne, Audiobook)).isEqualTo(FormatState.Failed(DownloadFailure.Damaged, 0))
            assertThat(restarted.state(bookOne, Text)).isEqualTo(FormatState.Complete(40))
            assertThat(restarted.state(bookTwo, Text)).isEqualTo(FormatState.Failed(DownloadFailure.Damaged, 5))
            assertThat(restarted.state(bookTwo, Audiobook)).isEqualTo(FormatState.Complete(100))
        }

    @Test
    fun `everything goes at once, what an update fetched beside the kept files too`() = queueTest {
        downloaded()
        serve(bookOne, Audiobook, audio("a1", bytes = 70, version = "v2"), version = "v2")
        remover.hold(bookOne)
        updates.checkBook(bookOne)
        updates.update(bookOne)
        runner.runUntilIdle()
        assertThat(files.updates.file(bookOne, Audiobook, "a1").length()).isEqualTo(70)

        remover.deleteAll()

        assertThat(registry.books.first()).isEmpty()
        assertThat(files.bookDir(bookOne).exists()).isFalse()
    }

    @Test
    fun `a deleted format takes the update it was fetching with it`() = queueTest {
        downloaded()
        serve(bookOne, Audiobook, audio("a1", bytes = 70, version = "v2"), version = "v2")
        updateFetcher.on("a1") { fetch ->
            store(fetch)
            awaitCancellation()
        }
        updates.checkBook(bookOne)
        updates.update(bookOne)
        keepRunning()
        it.runCurrent()
        assertThat(files.updates.file(bookOne, Audiobook, "a1").exists()).isTrue()

        remover.delete(bookOne, setOf(Audiobook))
        it.advanceTimeBy(10_000)
        it.runCurrent()

        assertThat(checkNotNull(registry.book(bookOne)).formats.map { it.format }).containsExactly(Text)
        assertThat(files.updates.file(bookOne, Audiobook, "a1").exists()).isFalse()
        assertThat(file(bookOne, Audiobook, "a1").exists()).isFalse()
    }
}
