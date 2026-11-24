package app.tfdn.lirelle.feature.reader

import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.BookFile
import app.tfdn.lirelle.core.library.model.BookFileKind
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.reader.cache.CacheState
import app.tfdn.lirelle.core.reader.cache.CachedBookKey
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A read-along book that also has a plain EPUB: an account that may not download is given the plain one, which
 * the server refuses to nobody, chosen before anything is asked of the server.
 */
class BookOpenerPlainFileTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val plain = BookFile(
        id = FileId("3"),
        kind = BookFileKind.Text,
        format = "EPUB",
        sizeBytes = 900_000,
        isPrimary = false,
        narrationSeconds = null,
        chapterCount = null,
        contentVersion = "plain-1",
    )
    private val plainKey = CachedBookKey(fileId = FileId("3"), contentVersion = "plain-1")

    /**
     * The sample book, whose primary file is a read-along EPUB, with a plain EPUB beside it: the one its source
     * gives an account that may not download.
     */
    private fun TestScope.harness() = ReaderHarness(this, folder.root).apply {
        detail = detail.copy(files = detail.files + plain, readerFileWithoutDownload = plain)
        library.bookResult = ApiResult.Success(detail)
    }

    private suspend fun ReaderHarness.open(mayDownload: Boolean): OpeningStep {
        val opener = BookOpener(library, cache, { epub }, { webViewVersion }, kept, downloads, { mayDownload })
        return opener.open(BookId("12")).toList().last()
    }

    @Test
    fun `an account that may not download opens the plain file, whole`() = runTest {
        val harness = harness()

        val book = (harness.open(mayDownload = false) as OpeningStep.Ready).book

        assertThat(book.file).isEqualTo(plain)
        assertThat(book.file.hasReadAlong).isFalse()
        assertThat(harness.cache.opened).containsExactly(plainKey to false)
        assertThat(harness.cache.held).containsExactly(plainKey)
    }

    @Test
    fun `a text that is not an EPUB is the file opened when the server prefers it, whoever asks`() = runTest {
        val mobi = plain.copy(id = FileId("4"), format = "MOBI", isPrimary = true, contentVersion = "mobi-1")
        listOf(true, false).forEach { mayDownload ->
            val harness = harness()
            // The source gives no other file for a book whose text is not a read-along EPUB.
            harness.detail = harness.detail.copy(files = listOf(mobi, plain), readerFileWithoutDownload = null)
            harness.library.bookResult = ApiResult.Success(harness.detail)

            val book = (harness.open(mayDownload) as OpeningStep.Ready).book

            assertThat(book.file).isEqualTo(mobi)
            assertThat(book.content).isInstanceOf(BookContent.Whole::class.java)
            assertThat(harness.cache.opened).containsExactly(CachedBookKey(FileId("4"), "mobi-1") to false)
        }
    }

    @Test
    fun `an account that may download opens the read-along file, without its audio`() = runTest {
        val harness = harness()

        val book = (harness.open(mayDownload = true) as OpeningStep.Ready).book

        assertThat(book.file.id).isEqualTo(harness.key.fileId)
        assertThat(harness.cache.opened).containsExactly(harness.key to true)
    }

    @Test
    fun `the plain file that fails is a failed download, not a refusal`() = runTest {
        val harness = harness()
        harness.cache.states = listOf(CacheState.Failed(ApiError.ServerFailed))

        assertThat(harness.open(mayDownload = false))
            .isEqualTo(OpeningStep.Failed(ReaderFailure.Download(ApiError.ServerFailed)))
    }

    @Test
    fun `a read-along text the device keeps is opened as before, whatever the account may do now`() = runTest {
        val harness = harness()
        harness.downloads.has(BookId("12"), DownloadFormat.Text)

        val book = (harness.open(mayDownload = false) as OpeningStep.Ready).book

        assertThat(book.file.id).isEqualTo(harness.key.fileId)
        assertThat(harness.cache.opened).containsExactly(harness.key to true)
    }

    @Test
    fun `a text being deleted is not one the device keeps`() = runTest {
        val harness = harness()
        harness.downloads.has(BookId("12"), DownloadFormat.Text, removing = true)

        assertThat((harness.open(mayDownload = false) as OpeningStep.Ready).book.file).isEqualTo(plain)
    }
}
