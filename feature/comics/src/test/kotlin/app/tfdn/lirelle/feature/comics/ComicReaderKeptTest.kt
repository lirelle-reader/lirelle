package app.tfdn.lirelle.feature.comics

import app.tfdn.lirelle.core.downloads.model.DeviceCopy
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.FormatState
import app.tfdn.lirelle.core.library.model.PagedKind
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.testing.MainDispatcherRule
import app.tfdn.lirelle.core.testing.samplePagedDetail
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/**
 * A PDF the device keeps: it opens from the page kept when the server cannot give the book's, and the sheet at
 * its end has the row of what the device keeps, to delete it from there. [ComicReaderKeptAlbumTest] says the
 * same of a comic and of a DjVu.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ComicReaderKeptTest {

    @get:Rule
    val main = MainDispatcherRule()

    private val pdf = samplePagedDetail("PDF")
    private val kept = DeviceCopy(listOf(DownloadFormat.Text), 1_000, deleting = false)

    /** A reader of [harness]'s book, turned past its last page: the sheet at its end is open. */
    private fun atTheEnd(harness: ComicHarness): ComicReaderViewModel = harness.reader().also {
        it.goTo(Pages - 1)
        it.next()
    }

    // --- opening

    @Test
    fun `a kept PDF opens while the server cannot be reached, from the page kept`() = runTest {
        val harness = ComicHarness(this, detail = pdf)
        harness.library.bookResult = ApiResult.Failure(ApiError.ServerUnreachable)
        harness.kept.details[AlbumBook] = pdf

        val state = harness.reader().state.value

        assertThat(state.phase).isEqualTo(ComicPhase.Ready)
        assertThat(state.kind).isEqualTo(PagedKind.Pdf)
        assertThat(state.title).isEqualTo(pdf.title)
        assertThat(harness.opener.opened.single().fileId).isEqualTo(checkNotNull(pdf.pagedFile).id)
    }

    @Test
    fun `a book gone from the server still opens from the page kept`() = runTest {
        val harness = ComicHarness(this, detail = pdf)
        harness.library.bookResult = ApiResult.Failure(ApiError.NotFound)
        harness.kept.details[AlbumBook] = pdf

        assertThat(harness.reader().state.value.phase).isEqualTo(ComicPhase.Ready)
    }

    @Test
    fun `a book whose page is not kept fails as before while the server cannot be reached`() = runTest {
        val harness = ComicHarness(this, detail = pdf)
        harness.library.bookResult = ApiResult.Failure(ApiError.ServerUnreachable)

        val state = harness.reader().state.value

        assertThat(state.phase).isEqualTo(ComicPhase.Failed(ComicFailure.Load(ApiError.ServerUnreachable)))
        assertThat(harness.opener.opened).isEmpty()
    }

    @Test
    fun `any other failure of the server is not answered by the page kept`() = runTest {
        val harness = ComicHarness(this, detail = pdf)
        harness.library.bookResult = ApiResult.Failure(ApiError.SessionExpired)
        harness.kept.details[AlbumBook] = pdf

        val state = harness.reader().state.value

        assertThat(state.phase).isEqualTo(ComicPhase.Failed(ComicFailure.Load(ApiError.SessionExpired)))
    }

    @Test
    fun `the server's page is the one used when it answers`() = runTest {
        val harness = ComicHarness(this, detail = pdf)
        harness.kept.details[AlbumBook] = pdf.copy(title = "An older title")

        assertThat(harness.reader().state.value.title).isEqualTo(pdf.title)
        assertThat(harness.kept.asked).isEmpty()
    }

    @Test
    fun `a book that opens is marked as opened, one that does not is not`() = runTest {
        val opened = ComicHarness(this, detail = pdf)
        opened.reader()
        assertThat(opened.downloads.opened).containsExactly(AlbumBook)

        val failed = ComicHarness(this, detail = pdf)
        failed.library.bookResult = ApiResult.Failure(ApiError.ServerUnreachable)
        failed.reader()
        assertThat(failed.downloads.opened).isEmpty()
    }

    // --- the row of the sheet at the end

    @Test
    fun `the sheet at the end of a kept PDF says what the device keeps of it`() = runTest {
        val harness = ComicHarness(this, detail = pdf)
        harness.downloads.has(AlbumBook, DownloadFormat.Text)

        val reader = atTheEnd(harness)

        assertThat(reader.state.value.endPanel).isEqualTo(ComicEndPanel(device = kept))
    }

    @Test
    fun `a PDF the device does not keep has no row, until it is kept`() = runTest {
        val harness = ComicHarness(this, detail = pdf)
        val reader = atTheEnd(harness)
        assertThat(reader.state.value.endPanel).isEqualTo(ComicEndPanel())

        harness.downloads.has(AlbumBook, DownloadFormat.Text, state = FormatState.Queued)
        runCurrent()
        assertThat(reader.state.value.endPanel?.device).isNull()

        harness.downloads.has(AlbumBook, DownloadFormat.Text)
        runCurrent()
        assertThat(reader.state.value.endPanel?.device).isEqualTo(kept)
    }

    @Test
    fun `delete asks for the book to go when it closes, and undo keeps it after all`() = runTest {
        val harness = ComicHarness(this, detail = pdf)
        harness.downloads.has(AlbumBook, DownloadFormat.Text)
        harness.deletions.held += AlbumBook
        val reader = atTheEnd(harness)

        reader.deleteFromDevice()
        runCurrent()
        assertThat(harness.deletions.asked).containsExactly("delete $AlbumBook")
        assertThat(reader.state.value.endPanel?.device).isEqualTo(kept.copy(deleting = true))

        reader.deleteFromDevice()
        runCurrent()
        assertThat(harness.deletions.asked).containsExactly("delete $AlbumBook")

        reader.keepOnDevice()
        runCurrent()
        assertThat(harness.deletions.asked).containsExactly("delete $AlbumBook", "keep $AlbumBook").inOrder()
        assertThat(reader.state.value.endPanel?.device).isEqualTo(kept)
    }

    @Test
    fun `the row comes back with the sheet, as the device then is`() = runTest {
        val harness = ComicHarness(this, detail = pdf)
        harness.downloads.has(AlbumBook, DownloadFormat.Text)
        harness.deletions.held += AlbumBook
        val reader = atTheEnd(harness)
        reader.deleteFromDevice()
        runCurrent()

        reader.dismissEndPanel()
        reader.next()

        assertThat(reader.state.value.endPanel?.device).isEqualTo(kept.copy(deleting = true))
    }
}
