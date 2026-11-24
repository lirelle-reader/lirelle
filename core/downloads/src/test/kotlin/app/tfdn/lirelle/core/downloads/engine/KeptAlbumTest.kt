package app.tfdn.lirelle.core.downloads.engine

import app.tfdn.lirelle.core.downloads.DeleteResult
import app.tfdn.lirelle.core.downloads.model.DownloadFailure
import app.tfdn.lirelle.core.downloads.model.DownloadFormat.Audiobook
import app.tfdn.lirelle.core.downloads.model.DownloadFormat.Text
import app.tfdn.lirelle.core.downloads.model.FileKind
import app.tfdn.lirelle.core.downloads.model.FileSource
import app.tfdn.lirelle.core.downloads.model.FormatState
import app.tfdn.lirelle.core.downloads.model.PageCount
import app.tfdn.lirelle.core.downloads.model.PlannedFile
import app.tfdn.lirelle.core.downloads.model.TextName
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.network.ApiResult
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private const val PageBytes = FakeFetcher.UnknownSize

/** An album once kept: what a start finds of it, and what its deletions leave. */
@OptIn(ExperimentalCoroutinesApi::class)
class KeptAlbumTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var root: File

    private val bookOne = BookId("1")

    private fun queueTest(test: suspend QueueFixture.(TestScope) -> Unit) = runTest {
        root = folder.newFolder("downloads")
        QueueFixture(this, root).test(this)
    }

    /** Book 1 keeps an album of four pages. */
    private suspend fun QueueFixture.kept() {
        serveAlbum(bookOne, 4)
        queue.ask(bookOne, setOf(Text))
        runner.runUntilIdle()
        check(state(bookOne, Text) == FormatState.Complete(4 * PageBytes))
    }

    /** Another process over the same folder and what the registry said, with no network. */
    private suspend fun QueueFixture.restarted(scope: TestScope): QueueFixture {
        val fresh = QueueFixture(scope, root)
        registry.books.first().forEach { book -> fresh.registry.update(book.bookId) { book } }
        fresh.network.value = NoNetwork
        return fresh
    }

    private fun filesLeft(): List<File> = root.walkTopDown().filter { it.isFile }.toList()

    @Test
    fun `a complete album with a page missing when the app starts is to download again`() = queueTest {
        kept()
        file(bookOne, Text, "2").delete()
        val fresh = restarted(it)

        fresh.runner.runUntilIdle()

        assertThat(fresh.state(bookOne, Text)).isEqualTo(FormatState.Failed(DownloadFailure.Damaged, 3 * PageBytes))
        assertThat(fresh.text(bookOne).pages).isEqualTo(PageCount(3, 4))
    }

    @Test
    fun `a complete album with all its pages is left as it is when the app starts`() = queueTest {
        kept()
        val fresh = restarted(it)

        fresh.runner.runUntilIdle()

        assertThat(fresh.state(bookOne, Text)).isEqualTo(FormatState.Complete(4 * PageBytes))
        assertThat(fresh.text(bookOne).pages).isEqualTo(PageCount(4, 4))
    }

    @Test
    fun `a damaged album asked again is fetched again, under the name it had`() = queueTest {
        kept()
        file(bookOne, Text, "2").delete()
        val fresh = restarted(it)
        fresh.runner.runUntilIdle()
        fresh.serveAlbum(bookOne, 4)

        fresh.queue.retry(bookOne)
        assertThat(fresh.text(bookOne).textName).isEqualTo(TextName.Cbz)
        assertThat(fresh.text(bookOne).expectedBytes).isEqualTo(AlbumBytes)
        fresh.network.value = Wifi
        fresh.runner.runUntilIdle()

        assertThat(fresh.keptPages(bookOne)).containsExactly("0", "1", "2", "3").inOrder()
        assertThat(fresh.text(bookOne).pages).isEqualTo(PageCount(4, 4))
        assertThat(fresh.state(bookOne, Text)).isEqualTo(FormatState.Complete(4 * PageBytes))
    }

    @Test
    fun `the text of a single file takes its name from the plan too`() = queueTest {
        val pdf = PlannedFile("12.pdf", FileSource.Whole(FileId("12")), "v1", 900, resumable = true, FileKind.Pdf)
        plans.answers[bookOne to Text] = ApiResult.Success(FormatPlan(FileId("12"), "v1", listOf(pdf), TextName.Pdf))
        queue.ask(bookOne, setOf(Text), mapOf(Text to 900))

        runner.runUntilIdle()

        assertThat(text(bookOne).textName).isEqualTo(TextName.Pdf)
        assertThat(text(bookOne).expectedBytes).isEqualTo(900)
        assertThat(text(bookOne).pages).isNull()
        assertThat(queue.queue.first().active).isNull()
    }

    @Test
    fun `a deleted album takes its pages' folder with it`() = queueTest {
        kept()
        serve(bookOne, Audiobook, audio("a1"))
        queue.ask(bookOne, setOf(Audiobook))
        runner.runUntilIdle()

        assertThat(remover.delete(bookOne, setOf(Text))).isEqualTo(DeleteResult.Deleted)
        it.advanceTimeBy(10_000)
        it.runCurrent()

        assertThat(File(files.bookDir(bookOne), "pages").exists()).isFalse()
        assertThat(filesLeft().map { file -> file.name }).containsExactly("a1")
        assertThat(checkNotNull(registry.book(bookOne)).formats.map { record -> record.format })
            .containsExactly(Audiobook)
    }

    @Test
    fun `an album deleted as its book closes, or with everything, leaves no page`() = queueTest {
        kept()
        closing.deleteAtClose(bookOne)
        assertThat(filesLeft()).isEmpty()

        kept()
        remover.deleteAll()
        assertThat(filesLeft()).isEmpty()
        assertThat(registry.books.first()).isEmpty()
    }

    @Test
    fun `an album whose download is cancelled leaves no page`() = queueTest {
        serveAlbum(bookOne, 4)
        fetcher.on("2") { FetchResult.Refused(DownloadFailure.NotAnEpub) }
        queue.ask(bookOne, setOf(Text))
        runner.runUntilIdle()
        assertThat(keptPages(bookOne)).containsExactly("0", "1")

        queue.cancel(bookOne)

        assertThat(filesLeft()).isEmpty()
    }
}
