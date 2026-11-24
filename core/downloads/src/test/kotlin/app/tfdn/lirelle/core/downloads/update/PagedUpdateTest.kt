package app.tfdn.lirelle.core.downloads.update

import app.tfdn.lirelle.core.downloads.engine.AlbumBytes
import app.tfdn.lirelle.core.downloads.engine.AlbumFile
import app.tfdn.lirelle.core.downloads.engine.FakeFetcher
import app.tfdn.lirelle.core.downloads.engine.FetchResult
import app.tfdn.lirelle.core.downloads.engine.QueueFixture
import app.tfdn.lirelle.core.downloads.engine.album
import app.tfdn.lirelle.core.downloads.engine.keptPages
import app.tfdn.lirelle.core.downloads.engine.pageFiles
import app.tfdn.lirelle.core.downloads.engine.serveAlbum
import app.tfdn.lirelle.core.downloads.engine.text
import app.tfdn.lirelle.core.downloads.engine.written
import app.tfdn.lirelle.core.downloads.model.DownloadFailure
import app.tfdn.lirelle.core.downloads.model.DownloadFormat.Text
import app.tfdn.lirelle.core.downloads.model.FormatState
import app.tfdn.lirelle.core.downloads.model.PageCount
import app.tfdn.lirelle.core.downloads.model.TextName
import app.tfdn.lirelle.core.downloads.model.WaitReason
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.PagedKind
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import com.google.common.truth.Truth.assertThat
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

private val TheBook = BookId("1")

/** The update of a kept album: every page of the newer version, beside the kept ones, then in their place. */
@OptIn(ExperimentalCoroutinesApi::class)
class PagedUpdateTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun queueTest(test: suspend QueueFixture.(TestScope) -> Unit) = runTest {
        QueueFixture(this, folder.newFolder("downloads")).test(this)
    }

    /** Book 1 keeps an album of four pages; the server then has [count] pages of another version of its file. */
    private suspend fun QueueFixture.albumChanged(count: Int, fileId: FileId = AlbumFile) {
        serveAlbum(TheBook, 4)
        queue.ask(TheBook, setOf(Text))
        runner.runUntilIdle()
        check(state(TheBook, Text) == FormatState.Complete(4 * PageBytes))
        serveAlbum(TheBook, count, version = "v2", fileId = fileId)
    }

    @Test
    fun `a file replaced on the server is an update of the album, about as large as the file`() = queueTest {
        albumChanged(count = 3)

        updates.checkBook(TheBook)

        val update = checkNotNull(update(TheBook, Text))
        assertThat(update.version).isEqualTo("v2")
        assertThat(update.bytes).isEqualTo(AlbumBytes)
        assertThat(update.pages).isEqualTo(PageCount(0, 3))
        assertThat(update.state).isNull()
        assertThat(written(TheBook)).doesNotContain(""""source"""")
    }

    @Test
    fun `every page is fetched again, beside the kept ones, which go on being the album`() = queueTest {
        albumChanged(count = 4)
        remover.hold(TheBook)
        askUpdate(TheBook)

        runner.runUntilIdle()

        assertThat(updateFetcher.fetched).containsExactly("1/Text/0", "1/Text/1", "1/Text/2", "1/Text/3").inOrder()
        assertThat(besideNames(TheBook)).containsExactly("0", "1", "2", "3")
        assertThat(beside(TheBook, Text, "0").parentFile?.name).isEqualTo("pages")
        assertThat(keptPages(TheBook)).containsExactly("0", "1", "2", "3").inOrder()
        assertThat(text(TheBook).version).isEqualTo("v1")
        assertThat(text(TheBook).pages).isEqualTo(PageCount(4, 4))
        assertThat(update(TheBook, Text)?.pages).isEqualTo(PageCount(4, 4))
        assertThat(update(TheBook, Text)?.state).isEqualTo(FormatState.Complete(4 * PageBytes))
    }

    @Test
    fun `an album that lost pages keeps none past the new count once swapped, its record a count again`() =
        queueTest {
            albumChanged(count = 3)
            remover.hold(TheBook)
            askUpdate(TheBook)
            runner.runUntilIdle()
            file(TheBook, Text, "0").writeText("the kept page")

            remover.release(TheBook)
            it.runCurrent()

            assertThat(keptPages(TheBook)).containsExactly("0", "1", "2").inOrder()
            assertThat(file(TheBook, Text, "0").length()).isEqualTo(PageBytes)
            assertThat(besideNames(TheBook)).isEmpty()
            val record = text(TheBook)
            assertThat(record.version).isEqualTo("v2")
            assertThat(record.files).isEqualTo(pageFiles(AlbumFile, PagedKind.Comic, 3, "v2"))
            assertThat(record.state).isEqualTo(FormatState.Complete(3 * PageBytes))
            assertThat(record.pages).isEqualTo(PageCount(3, 3))
            assertThat(record.keptPages).isEqualTo(3)
            assertThat(record.update).isNull()
            assertThat(record.textName).isEqualTo(TextName.Cbz)
            assertThat(written(TheBook)).contains(""""pages":{"count":3,"fileId":"$AlbumFile","paged":"Comic"}""")
            assertThat(written(TheBook)).doesNotContain(""""source"""")
        }

    @Test
    fun `an album that gained pages has them all once swapped`() = queueTest {
        albumChanged(count = 6)
        askUpdate(TheBook)

        runner.runUntilIdle()

        assertThat(keptPages(TheBook)).containsExactly("0", "1", "2", "3", "4", "5").inOrder()
        assertThat(text(TheBook).pages).isEqualTo(PageCount(6, 6))
        assertThat(text(TheBook).state).isEqualTo(FormatState.Complete(6 * PageBytes))
        assertThat(written(TheBook)).contains(""""pages":{"count":6,""")
    }

    @Test
    fun `an update cut goes on from the page it had reached, and says its pages meanwhile`() = queueTest {
        albumChanged(count = 3)
        updateFetcher.on("1") { FetchResult.Cut(0, ApiError.ServerUnreachable) }
        askUpdate(TheBook)
        keepRunning()
        it.runCurrent()

        val waiting = checkNotNull(update(TheBook, Text))
        assertThat(waiting.state).isEqualTo(FormatState.Waiting(WaitReason.NoNetwork, PageBytes, AlbumBytes))
        assertThat(waiting.pages).isEqualTo(PageCount(1, 3))
        val entry = queue.queue.first().waiting.single()
        assertThat(entry.update).isTrue()
        assertThat(entry.pages).isEqualTo(PageCount(1, 3))
        assertThat(entry.textName).isEqualTo(TextName.Cbz)
        assertThat(text(TheBook).pages).isEqualTo(PageCount(4, 4))

        it.advanceTimeBy(5_000)
        it.runCurrent()

        assertThat(updateFetcher.fetched).containsExactly("1/Text/0", "1/Text/1", "1/Text/1", "1/Text/2").inOrder()
        assertThat(text(TheBook).version).isEqualTo("v2")
        assertThat(text(TheBook).pages).isEqualTo(PageCount(3, 3))
    }

    @Test
    fun `an update taken back has none of its pages any more, and says so`() = queueTest {
        albumChanged(count = 3)
        updateFetcher.on("2") { FetchResult.Refused(DownloadFailure.NotAnEpub) }
        askUpdate(TheBook)
        runner.runUntilIdle()
        check(update(TheBook, Text)?.pages == PageCount(2, 3))

        queue.cancel(TheBook)

        assertThat(besideNames(TheBook)).isEmpty()
        val offered = checkNotNull(update(TheBook, Text))
        assertThat(offered.state).isNull()
        assertThat(offered.keptPages).isEqualTo(0)
        assertThat(offered.pages).isEqualTo(PageCount(0, 3))
        assertThat(text(TheBook).pages).isEqualTo(PageCount(4, 4))
    }

    @Test
    fun `an album whose file is another one on the server is kept under that file once swapped`() = queueTest {
        albumChanged(count = 4, fileId = FileId("9"))
        askUpdate(TheBook)

        runner.runUntilIdle()

        assertThat(text(TheBook).version).isEqualTo("v2")
        assertThat(text(TheBook).fileId).isEqualTo(FileId("9"))
        assertThat(written(TheBook)).contains(""""pages":{"count":4,"fileId":"9","paged":"Comic"}""")
    }

    @Test
    fun `an album replaced by a file of another format is named by it once swapped, not before`() = queueTest {
        albumChanged(count = 4)
        plans.answers[TheBook to Text] =
            ApiResult.Success(album(4, version = "v2", name = TextName.Cbr))
        remover.hold(TheBook)
        updates.checkBook(TheBook)
        assertThat(update(TheBook, Text)?.textName).isEqualTo(TextName.Cbr)
        updates.update(TheBook)
        runner.runUntilIdle()
        assertThat(text(TheBook).textName).isEqualTo(TextName.Cbz)

        remover.release(TheBook)
        it.runCurrent()

        assertThat(text(TheBook).version).isEqualTo("v2")
        assertThat(text(TheBook).textName).isEqualTo(TextName.Cbr)
    }

    @Test
    fun `the format an update was found in is the one its run says when the file changed again`() = queueTest {
        albumChanged(count = 4)
        updates.checkBook(TheBook)
        assertThat(update(TheBook, Text)?.textName).isEqualTo(TextName.Cbz)
        plans.answers[TheBook to Text] =
            ApiResult.Success(album(4, version = "v3", name = TextName.Cb7))
        updates.update(TheBook)

        runner.runUntilIdle()

        assertThat(text(TheBook).version).isEqualTo("v3")
        assertThat(text(TheBook).textName).isEqualTo(TextName.Cb7)
    }
}
