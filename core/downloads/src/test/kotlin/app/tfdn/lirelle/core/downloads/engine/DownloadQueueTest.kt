package app.tfdn.lirelle.core.downloads.engine

import app.tfdn.lirelle.core.downloads.AskRefusal
import app.tfdn.lirelle.core.downloads.model.DownloadFormat.Audiobook
import app.tfdn.lirelle.core.downloads.model.DownloadFormat.Narration
import app.tfdn.lirelle.core.downloads.model.DownloadFormat.Text
import app.tfdn.lirelle.core.downloads.model.FormatState
import app.tfdn.lirelle.core.downloads.model.WaitReason
import app.tfdn.lirelle.core.ids.BookId
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadQueueTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val bookOne = BookId("1")
    private val bookTwo = BookId("2")

    private fun queueTest(test: suspend QueueFixture.(kotlinx.coroutines.test.TestScope) -> Unit) = runTest {
        QueueFixture(this, folder.newFolder("downloads")).test(this)
    }

    @Test
    fun `an account that may not download is refused, and nothing is recorded`() = queueTest {
        canDownload.value = false

        assertThat(queue.ask(bookOne, setOf(Text))).isEqualTo(AskRefusal.NotAllowed)

        assertThat(registry.book(bookOne)).isNull()
        assertThat(queue.books.first()).isEmpty()
    }

    @Test
    fun `what is asked is queued, the text with the narration`() = queueTest {
        assertThat(queue.ask(bookOne, setOf(Narration))).isNull()

        val book = checkNotNull(queue.book(bookOne).first())
        assertThat(book.formats.map { it.format to it.state })
            .containsExactly(Text to FormatState.Queued, Narration to FormatState.Queued)
        assertThat(queue.queue.first().waiting.single().formats).containsExactly(Text, Narration).inOrder()
    }

    @Test
    fun `asked on a metered network and never started, a download knows how much is left`() = queueTest {
        network.value = Mobile
        serveBook(bookOne)
        queue.ask(bookOne, setOf(Audiobook), mapOf(Audiobook to 412L))

        runner.runUntilIdle()

        assertThat(state(bookOne, Audiobook)).isEqualTo(FormatState.Waiting(WaitReason.UnmeteredOnly, 0, 412))
        val waiting = queue.queue.first()
        assertThat(waiting.waitReason).isEqualTo(WaitReason.UnmeteredOnly)
        assertThat(waiting.leftBytes).isEqualTo(412)
        assertThat(waiting.waiting.single().totalBytes).isEqualTo(412)
        assertThat(plans.asked).isEmpty()
    }

    @Test
    fun `a failed download asked again keeps the size it was asked with`() = queueTest {
        serveBook(bookOne)
        queue.ask(bookOne, setOf(Audiobook), mapOf(Audiobook to 412L))
        fetcher.on("a1") { FetchResult.Refused(app.tfdn.lirelle.core.downloads.model.DownloadFailure.NotAnEpub) }
        runner.runUntilIdle()

        queue.retry(bookOne)

        assertThat(registry.book(bookOne)?.formats?.single()?.expectedBytes).isEqualTo(412)
    }

    @Test
    fun `the downloads go in the order asked, the text of each before its audio`() = queueTest {
        serveBook(bookOne)
        serveBook(bookTwo)
        queue.ask(bookTwo, setOf(Audiobook, Text))
        it.advanceTimeBy(1_000)
        queue.ask(bookOne, setOf(Text, Audiobook))

        runner.runUntilIdle()

        assertThat(fetcher.fetched)
            .containsExactly("2/Text/2.epub", "2/Audiobook/a2", "1/Text/1.epub", "1/Audiobook/a1").inOrder()
        assertThat(state(bookOne, Text)).isEqualTo(FormatState.Complete(40))
        assertThat(state(bookTwo, Audiobook)).isEqualTo(FormatState.Complete(100))
        assertThat(queue.queue.first().waiting).isEmpty()
    }

    @Test
    fun `cancelling in the middle of a file stops it, deletes what was received, and the next book goes on`() =
        queueTest {
            serveBook(bookOne)
            serveBook(bookTwo)
            val started = CompletableDeferred<Unit>()
            fetcher.on("a1") { fetch ->
                files.part(bookOne, Audiobook, fetch.file.name)
                    .apply { checkNotNull(parentFile).mkdirs() }
                    .writeText("abc")
                started.complete(Unit)
                awaitCancellation()
            }
            queue.ask(bookOne, setOf(Audiobook))
            it.advanceTimeBy(1)
            queue.ask(bookTwo, setOf(Audiobook))
            keepRunning()
            started.await()

            queue.cancel(bookOne)
            it.runCurrent()

            assertThat(registry.book(bookOne)).isNull()
            assertThat(files.bookDir(bookOne).exists()).isFalse()
            assertThat(kept.removed).contains(bookOne to null)
            assertThat(state(bookTwo, Audiobook)).isEqualTo(FormatState.Complete(100))
        }

    @Test
    fun `cancelling a download that never started takes the page kept when it was asked with it`() = queueTest {
        network.value = Mobile
        serveBook(bookOne)
        queue.ask(bookOne, setOf(Audiobook), mapOf(Audiobook to 412L))
        files.kept(bookOne, "detail.json").apply { checkNotNull(parentFile).mkdirs() }.writeText("{}")
        runner.runUntilIdle()

        queue.cancel(bookOne)
        it.runCurrent()

        assertThat(plans.asked).isEmpty()
        assertThat(registry.book(bookOne)).isNull()
        assertThat(files.bookDir(bookOne).exists()).isFalse()
        assertThat(kept.removed).containsExactly(bookOne to Audiobook, bookOne to null).inOrder()
    }

    @Test
    fun `cancelling one format leaves the others, and what is complete`() = queueTest {
        serveBook(bookOne)
        queue.ask(bookOne, setOf(Text))
        runner.runUntilIdle()
        network.value = NoNetwork
        queue.ask(bookOne, setOf(Audiobook))

        queue.cancel(bookOne)

        assertThat(checkNotNull(registry.book(bookOne)).formats.map { it.format }).containsExactly(Text)
        assertThat(file(bookOne, Text, "1.epub").exists()).isTrue()
        assertThat(kept.removed).containsExactly(bookOne to Audiobook)
    }

    @Test
    fun `cancelling the text takes the narration that is not complete with it`() = queueTest {
        queue.ask(bookOne, setOf(Narration, Audiobook))

        queue.cancel(bookOne, Text)

        assertThat(checkNotNull(registry.book(bookOne)).formats.map { it.format }).containsExactly(Audiobook)
    }

    @Test
    fun `retry queues the failed formats of the book again`() = queueTest {
        serveBook(bookOne)
        queue.ask(bookOne, setOf(Text, Audiobook))
        fetcher.on("a1") { FetchResult.Refused(app.tfdn.lirelle.core.downloads.model.DownloadFailure.NotAnEpub) }
        runner.runUntilIdle()
        assertThat(state(bookOne, Audiobook)).isInstanceOf(FormatState.Failed::class.java)

        queue.retry(bookOne)
        assertThat(state(bookOne, Audiobook)).isEqualTo(FormatState.Queued)
        runner.runUntilIdle()

        assertThat(state(bookOne, Audiobook)).isEqualTo(FormatState.Complete(100))
        assertThat(state(bookOne, Text)).isEqualTo(FormatState.Complete(40))
    }

    @Test
    fun `the downloads queued when the user allows any network use it, not one asked later`() = queueTest {
        serveBook(bookOne)
        serveBook(bookTwo)
        network.value = Mobile
        queue.ask(bookOne, setOf(Text))
        keepRunning()
        it.runCurrent()
        assertThat(state(bookOne, Text)).isEqualTo(FormatState.Waiting(WaitReason.UnmeteredOnly, 0, null))
        assertThat(queue.queue.first().waitReason).isEqualTo(WaitReason.UnmeteredOnly)
        assertThat(fetcher.fetched).isEmpty()

        queue.allowAnyNetworkNow()
        it.runCurrent()
        queue.ask(bookTwo, setOf(Text))
        it.runCurrent()

        assertThat(state(bookOne, Text)).isEqualTo(FormatState.Complete(40))
        assertThat(state(bookTwo, Text)).isEqualTo(FormatState.Waiting(WaitReason.UnmeteredOnly, 0, null))
    }

    @Test
    fun `when the account loses the permission, the queue is cancelled and what is complete stays`() = queueTest {
        serveBook(bookOne)
        serveBook(bookTwo)
        queue.ask(bookOne, setOf(Text))
        runner.runUntilIdle()
        network.value = Mobile
        queue.ask(bookOne, setOf(Audiobook))
        queue.ask(bookTwo, setOf(Text))
        keepRunning()
        it.runCurrent()

        canDownload.value = false
        it.runCurrent()

        assertThat(checkNotNull(registry.book(bookOne)).formats.map { it.format to it.state })
            .containsExactly(Text to FormatState.Complete(40))
        assertThat(registry.book(bookTwo)).isNull()
        assertThat(file(bookOne, Text, "1.epub").exists()).isTrue()
    }

    @Test
    fun `opening a book is its last activity`() = queueTest {
        queue.ask(bookOne, setOf(Text))
        it.advanceTimeBy(5_000)

        queue.markOpened(bookOne)

        val book = checkNotNull(registry.book(bookOne))
        assertThat(book.lastOpenedAt).isEqualTo(now())
        assertThat(book.lastActivity).isEqualTo(now())
        assertThat(book.downloadedAt).isNotEqualTo(now())
    }

    @Test
    fun `asking another format keeps that the book is gone, and takes back its deletion at close`() = queueTest {
        serveBook(bookOne)
        queue.ask(bookOne, setOf(Text))
        runner.runUntilIdle()
        registry.update(bookOne) { it?.copy(gone = true, deleteAtClose = true) }

        queue.ask(bookOne, setOf(Audiobook))

        val book = checkNotNull(registry.book(bookOne))
        assertThat(book.gone).isTrue()
        assertThat(book.deleteAtClose).isFalse()
        assertThat(book.formats.map { it.format }).containsExactly(Text, Audiobook)
    }

    @Test
    fun `asking nothing new of a book leaves it gone`() = queueTest {
        serveBook(bookOne)
        queue.ask(bookOne, setOf(Text))
        runner.runUntilIdle()
        registry.update(bookOne) { it?.copy(gone = true) }

        queue.ask(bookOne, setOf(Text))

        assertThat(checkNotNull(registry.book(bookOne)).gone).isTrue()
    }
}
