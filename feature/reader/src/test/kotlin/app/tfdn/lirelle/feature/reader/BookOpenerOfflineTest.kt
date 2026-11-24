package app.tfdn.lirelle.feature.reader

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.offline.DownloadedFiles
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.reader.archive.EpubProblem
import app.tfdn.lirelle.core.reader.cache.CacheState
import app.tfdn.lirelle.core.reader.cache.DefaultReaderCache
import app.tfdn.lirelle.feature.reader.bridge.BookFormat
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** A downloaded book opens from what the device keeps: its page when the server cannot be reached, its file always. */
@OptIn(ExperimentalCoroutinesApi::class)
class BookOpenerOfflineTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val unreachable = ApiResult.Failure(ApiError.ServerUnreachable)

    private fun TestScope.harness() = ReaderHarness(this, folder.root).apply {
        library.bookResult = ApiResult.Success(detail)
    }

    private fun ReaderHarness.opener() = BookOpener(library, cache, { epub }, { webViewVersion }, kept, downloads)

    /** An opener over the real cache, which looks at the downloads first: they keep the harness's file of book 12. */
    private fun TestScope.downloadedOpener(harness: ReaderHarness, downloaded: Boolean = true): BookOpener {
        val files = object : DownloadedFiles {
            override suspend fun text(bookId: BookId, fileId: FileId): File? =
                harness.bookFile.takeIf { downloaded && bookId == BookId("12") && fileId == harness.key.fileId }

            override fun audio(url: String): File? = null
        }
        val cache = DefaultReaderCache(
            repository = harness.repository,
            directory = File(folder.root, "reader"),
            limitBytes = Long.MAX_VALUE,
            io = UnconfinedTestDispatcher(testScheduler),
            downloaded = files,
        )
        return BookOpener(harness.library, cache, { harness.epub }, { 130 }, harness.kept, harness.downloads)
    }

    @Test
    fun `a downloaded book opens with no request for its file`() = runTest {
        val harness = harness()

        val steps = downloadedOpener(harness).open(BookId("12")).toList()

        assertThat(steps.last()).isInstanceOf(OpeningStep.Ready::class.java)
        assertThat(harness.repository.downloads).isEmpty()
    }

    @Test
    fun `a downloaded book opens while the server cannot be reached, from the page kept`() = runTest {
        val harness = harness()
        harness.library.bookResult = unreachable
        harness.kept.details[BookId("12")] = harness.detail

        val steps = downloadedOpener(harness).open(BookId("12")).toList()

        assertThat(steps.first()).isEqualTo(OpeningStep.Found(harness.detail))
        assertThat((steps.last() as OpeningStep.Ready).book.detail).isEqualTo(harness.detail)
        assertThat(harness.repository.downloads).isEmpty()
    }

    @Test
    fun `a kept Kindle book opens whole with no network, from the page kept and the file the device keeps`() =
        runTest {
            val harness = harness()
            val mobi = harness.detail.files.first().copy(id = harness.key.fileId, format = "MOBI", isPrimary = true)
            val detail = harness.detail.copy(files = listOf(mobi))
            harness.library.bookResult = unreachable
            harness.repository.downloadResult = unreachable
            harness.kept.details[BookId("12")] = detail
            // Any check of an EPUB would refuse it: none is asked of a file of another format.
            harness.epub = EpubOpening.Refused(EpubProblem.NotAnArchive)

            val steps = downloadedOpener(harness).open(BookId("12")).toList()

            val book = (steps.last() as OpeningStep.Ready).book
            assertThat(book.content).isEqualTo(BookContent.Whole(harness.bookFile, BookFormat.Mobi))
            assertThat(book.detail).isEqualTo(detail)
            assertThat(harness.repository.downloads).isEmpty()
            assertThat(harness.downloads.opened).containsExactly(BookId("12"))
        }

    @Test
    fun `the kept copy opens when the server has another version of the file`() = runTest {
        val harness = harness()
        val files = harness.detail.files.map { it.copy(contentVersion = "what the server has now") }
        harness.library.bookResult = ApiResult.Success(harness.detail.copy(files = files))

        val steps = downloadedOpener(harness).open(BookId("12")).toList()

        assertThat(steps.last()).isInstanceOf(OpeningStep.Ready::class.java)
        assertThat(harness.repository.downloads).isEmpty()
    }

    @Test
    fun `a book gone from the server still opens from the page kept`() = runTest {
        val harness = harness()
        harness.library.bookResult = ApiResult.Failure(ApiError.NotFound)
        harness.kept.details[BookId("12")] = harness.detail

        val steps = downloadedOpener(harness).open(BookId("12")).toList()

        assertThat(steps.last()).isInstanceOf(OpeningStep.Ready::class.java)
    }

    @Test
    fun `a book that is not downloaded fails as before while the server cannot be reached`() = runTest {
        val harness = harness()
        harness.library.bookResult = unreachable

        val steps = harness.opener().open(BookId("12")).toList()

        assertThat(steps).containsExactly(OpeningStep.Failed(ReaderFailure.Download(ApiError.ServerUnreachable)))
        assertThat(harness.cache.opened).isEmpty()
    }

    @Test
    fun `a page kept without its file complete goes on to the cache and the server, which fail`() = runTest {
        val harness = harness()
        harness.library.bookResult = unreachable
        harness.kept.details[BookId("12")] = harness.detail
        harness.repository.downloadResult = unreachable

        val steps = downloadedOpener(harness, downloaded = false).open(BookId("12")).toList()

        assertThat(steps.last()).isEqualTo(OpeningStep.Failed(ReaderFailure.Download(ApiError.ServerUnreachable)))
        assertThat(harness.downloads.opened).isEmpty()
    }

    @Test
    fun `any other failure of the server is not answered by the page kept`() = runTest {
        val harness = harness()
        harness.library.bookResult = ApiResult.Failure(ApiError.SessionExpired)
        harness.kept.details[BookId("12")] = harness.detail

        val steps = harness.opener().open(BookId("12")).toList()

        assertThat(steps).containsExactly(OpeningStep.Failed(ReaderFailure.Download(ApiError.SessionExpired)))
    }

    @Test
    fun `the server's page is the one used when it answers`() = runTest {
        val harness = harness()
        harness.kept.details[BookId("12")] = harness.detail.copy(title = "An older title")

        val steps = harness.opener().open(BookId("12")).toList()

        assertThat(steps.first()).isEqualTo(OpeningStep.Found(harness.detail))
        assertThat(harness.kept.asked).isEmpty()
    }

    @Test
    fun `a book that comes out ready is marked as opened, one that fails is not`() = runTest {
        val ready = harness()
        ready.opener().open(BookId("12")).toList()
        assertThat(ready.downloads.opened).containsExactly(BookId("12"))

        val failed = harness()
        failed.cache.states = listOf(CacheState.Failed(ApiError.ServerUnreachable))
        failed.opener().open(BookId("12")).toList()
        assertThat(failed.downloads.opened).isEmpty()
    }
}
