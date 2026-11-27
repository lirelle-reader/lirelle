package app.tfdn.lirelle.feature.reader

import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.SourceCapabilities
import app.tfdn.lirelle.core.library.model.BookFileKind
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.reader.cache.CacheState
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** A read-along book is fetched without its audio, which the server only gives to an account that may download. */
class BookOpenerPermissionTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun TestScope.harness(error: ApiError, readAlong: Boolean = true) = ReaderHarness(this, folder.root).apply {
        val kind = if (readAlong) BookFileKind.ReadAlongEpub else BookFileKind.Text
        detail = detail.copy(files = detail.files.map { if (it.id == key.fileId) it.copy(kind = kind) else it })
        library.bookResult = ApiResult.Success(detail)
        cache.states = listOf(CacheState.Failed(error))
    }

    private suspend fun ReaderHarness.failure(mayDownload: Boolean): ReaderFailure {
        val opener = BookOpener(library, cache, { epub }, { webViewVersion }, kept, downloads, { mayDownload })
        return (opener.open(BookId("12")).toList().last() as OpeningStep.Failed).reason
    }

    @Test
    fun `a read-along book the server refuses to an account that may not download says so`() = runTest {
        val harness = harness(ApiError.ServerFailed)

        assertThat(harness.failure(mayDownload = false)).isEqualTo(ReaderFailure.DownloadNotAllowed)
        assertThat(harness.cache.opened).containsExactly(harness.key to true)
        assertThat(harness.cache.held).isEmpty()
    }

    @Test
    fun `a read-along book that fails for an account that may download is a failed download`() = runTest {
        val harness = harness(ApiError.ServerFailed)

        assertThat(harness.failure(mayDownload = true)).isEqualTo(ReaderFailure.Download(ApiError.ServerFailed))
    }

    @Test
    fun `a server that cannot be reached is said as such, whatever the account may do`() = runTest {
        val harness = harness(ApiError.ServerUnreachable)

        assertThat(harness.failure(mayDownload = false)).isEqualTo(ReaderFailure.Download(ApiError.ServerUnreachable))
    }

    @Test
    fun `a book without narration needs no permission, and fails as a download`() = runTest {
        val harness = harness(ApiError.ServerFailed, readAlong = false)

        assertThat(harness.failure(mayDownload = false)).isEqualTo(ReaderFailure.Download(ApiError.ServerFailed))
        assertThat(harness.cache.opened).containsExactly(harness.key to false)
    }

    /** A plain EPUB, opened by a source whose files need the right to download, as Kavita's. */
    private fun TestScope.plainEpub() = ReaderHarness(this, folder.root).apply {
        library.bookResult = ApiResult.Success(detail)
    }

    private suspend fun ReaderHarness.opening(downloadToRead: Boolean, mayDownload: Boolean): OpeningStep {
        val capabilities = SourceCapabilities.Everything.copy(downloadToRead = downloadToRead)
        val opener = BookOpener(
            library, cache, { epub }, { webViewVersion }, kept, downloads, { mayDownload }, capabilities,
        )
        return opener.open(BookId("12")).toList().last()
    }

    @Test
    fun `a source that needs the right to download refuses a text to an account without it, asking nothing`() =
        runTest {
            val harness = plainEpub()

            val last = harness.opening(downloadToRead = true, mayDownload = false)

            assertThat(last).isEqualTo(OpeningStep.Failed(ReaderFailure.DownloadNotAllowed))
            assertThat(harness.cache.opened).isEmpty()
        }

    @Test
    fun `a text the device keeps opens for an account without the right, whatever the source`() = runTest {
        val harness = plainEpub()
        harness.downloads.has(BookId("12"), DownloadFormat.Text)

        assertThat(harness.opening(downloadToRead = true, mayDownload = false))
            .isInstanceOf(OpeningStep.Ready::class.java)
    }

    @Test
    fun `a text opens for an account that may download, whatever the source`() = runTest {
        val harness = plainEpub()

        assertThat(harness.opening(downloadToRead = true, mayDownload = true))
            .isInstanceOf(OpeningStep.Ready::class.java)
    }

    @Test
    fun `a source that does not need the right serves a plain EPUB to an account without it`() = runTest {
        val harness = plainEpub()

        assertThat(harness.opening(downloadToRead = false, mayDownload = false))
            .isInstanceOf(OpeningStep.Ready::class.java)
    }
}
