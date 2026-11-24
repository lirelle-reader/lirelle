package app.tfdn.lirelle.core.downloads.engine

import app.tfdn.lirelle.core.downloads.model.DownloadFailure
import app.tfdn.lirelle.core.downloads.model.DownloadFormat.Audiobook
import app.tfdn.lirelle.core.downloads.model.DownloadFormat.Text
import app.tfdn.lirelle.core.downloads.model.FormatState
import app.tfdn.lirelle.core.downloads.model.PageCount
import app.tfdn.lirelle.core.downloads.model.TextName
import app.tfdn.lirelle.core.ids.BookId
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private const val PageBytes = FakeFetcher.UnknownSize

/** An album that failed, asked again: it goes on from the pages the device has. */
@OptIn(ExperimentalCoroutinesApi::class)
class PagedRetryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var root: File

    private val bookOne = BookId("1")

    private fun queueTest(test: suspend QueueFixture.(TestScope) -> Unit) = runTest {
        root = folder.newFolder("downloads")
        QueueFixture(this, root).test(this)
    }

    /** Book 1 is an album of four pages whose third page failed: two are kept. */
    private suspend fun QueueFixture.failedAtThirdPage() {
        serveAlbum(bookOne, 4)
        fetcher.on("2") { FetchResult.Refused(DownloadFailure.NotAnEpub) }
        queue.ask(bookOne, setOf(Text))
        runner.runUntilIdle()
        check(state(bookOne, Text) == FormatState.Failed(DownloadFailure.NotAnEpub, 2 * PageBytes))
        fetcher.fetched.clear()
    }

    @Test
    fun `a failed album retried fetches only the pages that are missing`() = queueTest {
        failedAtThirdPage()

        queue.retry(bookOne)

        assertThat(state(bookOne, Text)).isEqualTo(FormatState.Queued)
        assertThat(text(bookOne).pages).isEqualTo(PageCount(2, 4))
        assertThat(text(bookOne).textName).isEqualTo(TextName.Cbz)
        runner.runUntilIdle()
        assertThat(fetcher.fetched).containsExactly("1/Text/2", "1/Text/3").inOrder()
        assertThat(state(bookOne, Text)).isEqualTo(FormatState.Complete(4 * PageBytes))
        assertThat(text(bookOne).pages).isEqualTo(PageCount(4, 4))
        assertThat(keptPages(bookOne)).containsExactly("0", "1", "2", "3").inOrder()
    }

    @Test
    fun `a failed album asked again goes on from its pages as a retry does`() = queueTest {
        failedAtThirdPage()

        queue.ask(bookOne, setOf(Text), mapOf(Text to 5_500))

        assertThat(text(bookOne).pages).isEqualTo(PageCount(2, 4))
        assertThat(text(bookOne).expectedBytes).isEqualTo(5_500)
        runner.runUntilIdle()
        assertThat(fetcher.fetched).containsExactly("1/Text/2", "1/Text/3").inOrder()
        assertThat(state(bookOne, Text)).isEqualTo(FormatState.Complete(4 * PageBytes))
    }

    @Test
    fun `a retried album whose file changed meanwhile fetches every page of the new one`() = queueTest {
        failedAtThirdPage()
        serveAlbum(bookOne, 3, version = "v2")

        queue.retry(bookOne)
        runner.runUntilIdle()

        assertThat(fetcher.fetched).containsExactly("1/Text/0", "1/Text/1", "1/Text/2").inOrder()
        assertThat(text(bookOne).pages).isEqualTo(PageCount(3, 3))
        assertThat(text(bookOne).version).isEqualTo("v2")
        assertThat(keptPages(bookOne)).containsExactly("0", "1", "2").inOrder()
    }

    @Test
    fun `a retried album that lost pages meanwhile keeps none past its new count`() = queueTest {
        failedAtThirdPage()
        files.part(bookOne, Text, "3").writeText("what was received of a page that is no more")
        serveAlbum(bookOne, 1)

        queue.retry(bookOne)
        runner.runUntilIdle()

        assertThat(fetcher.fetched).isEmpty()
        assertThat(text(bookOne).pages).isEqualTo(PageCount(1, 1))
        assertThat(state(bookOne, Text)).isEqualTo(FormatState.Complete(PageBytes))
        assertThat(keptPages(bookOne)).containsExactly("0")
    }

    @Test
    fun `a retried album of a new version and fewer pages keeps none of the old one`() = queueTest {
        failedAtThirdPage()
        serveAlbum(bookOne, 1, version = "v2")

        queue.retry(bookOne)
        runner.runUntilIdle()

        assertThat(fetcher.fetched).containsExactly("1/Text/0")
        assertThat(keptPages(bookOne)).containsExactly("0")
    }

    @Test
    fun `a retried album whose text became a single file keeps no page`() = queueTest {
        failedAtThirdPage()
        serve(bookOne, Text, epub())

        queue.retry(bookOne)
        runner.runUntilIdle()

        assertThat(state(bookOne, Text)).isEqualTo(FormatState.Complete(40))
        assertThat(text(bookOne).pages).isNull()
        assertThat(keptPages(bookOne)).isEmpty()
        assertThat(file(bookOne, Text, "12.epub").isFile).isTrue()
    }

    @Test
    fun `a kept album found with a page missing fetches that page alone when retried`() = queueTest { scope ->
        serveAlbum(bookOne, 4)
        queue.ask(bookOne, setOf(Text))
        runner.runUntilIdle()
        file(bookOne, Text, "1").delete()
        // Another process over the same folder and what the registry said.
        val fresh = QueueFixture(scope, root)
        registry.books.first().forEach { book -> fresh.registry.update(book.bookId) { book } }
        fresh.serveAlbum(bookOne, 4)
        fresh.runner.runUntilIdle()
        check(fresh.state(bookOne, Text) == FormatState.Failed(DownloadFailure.Damaged, 3 * PageBytes))

        fresh.queue.retry(bookOne)
        assertThat(fresh.text(bookOne).pages).isEqualTo(PageCount(3, 4))
        fresh.runner.runUntilIdle()

        assertThat(fresh.fetcher.fetched).containsExactly("1/Text/1")
        assertThat(fresh.state(bookOne, Text)).isEqualTo(FormatState.Complete(4 * PageBytes))
    }

    @Test
    fun `a failed format made of single files starts again with nothing, as before`() = queueTest {
        serve(bookOne, Audiobook, audio("a1"), audio("a2"))
        fetcher.on("a2") { FetchResult.Refused(DownloadFailure.NotAnEpub) }
        queue.ask(bookOne, setOf(Audiobook))
        runner.runUntilIdle()
        fetcher.fetched.clear()

        queue.retry(bookOne)

        val record = checkNotNull(registry.book(bookOne)).formats.single()
        assertThat(record.files).isEmpty()
        assertThat(record.version).isNull()
        runner.runUntilIdle()
        assertThat(fetcher.fetched).containsExactly("1/Audiobook/a1", "1/Audiobook/a2").inOrder()
    }
}
