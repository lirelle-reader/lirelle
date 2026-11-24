package app.tfdn.lirelle.feature.comics

import app.cash.turbine.test
import app.tfdn.lirelle.core.comics.ComicKey
import app.tfdn.lirelle.core.downloads.model.DeviceCopy
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.FormatState
import app.tfdn.lirelle.core.library.model.BookDetail
import app.tfdn.lirelle.core.library.model.PagedKind
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.testing.MainDispatcherRule
import app.tfdn.lirelle.core.testing.sampleComicDetail
import app.tfdn.lirelle.core.testing.samplePagedDetail
import com.google.common.truth.Truth.assertThat
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/**
 * A comic and a DjVu the device keeps, as a kept PDF: they open from the page kept when the server cannot give
 * the book's, what is read of them then waits to be sent, and the sheet at their end has the row of what the
 * device keeps.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ComicReaderKeptAlbumTest {

    @get:Rule
    val main = MainDispatcherRule()

    private val albums = listOf(sampleComicDetail(), samplePagedDetail("DJVU"))
    private val kept = DeviceCopy(listOf(DownloadFormat.Text), 1_000, deleting = false)

    /** The book's page is the one its download kept: the server cannot be reached. */
    private fun TestScope.offline(detail: BookDetail) = ComicHarness(this, detail = detail).also {
        it.library.bookResult = ApiResult.Failure(ApiError.ServerUnreachable)
        it.kept.details[AlbumBook] = detail
    }

    private fun atTheEnd(harness: ComicHarness): ComicReaderViewModel = harness.reader().also {
        it.goTo(Pages - 1)
        it.next()
    }

    // --- opening

    @Test
    fun `a kept comic and a kept DjVu open while the server cannot be reached, from the page kept`() = runTest {
        albums.forEach { detail ->
            val harness = offline(detail)
            val file = checkNotNull(detail.pagedFile)

            val state = harness.reader().state.value

            assertThat(state.phase).isEqualTo(ComicPhase.Ready)
            assertThat(state.kind).isEqualTo(detail.pagedKind)
            assertThat(state.title).isEqualTo(detail.title)
            assertThat(state.pageCount).isEqualTo(Pages)
            // All the pages' source needs to find the kept ones: the file, its kind, and the book it is of.
            assertThat(harness.opener.opened)
                .containsExactly(ComicKey(file.id, file.contentVersion, checkNotNull(detail.pagedKind), AlbumBook))
            assertThat(harness.downloads.opened).containsExactly(AlbumBook)
        }
    }

    @Test
    fun `a kept comic whose files the server cannot serve for now opens from the page kept, saying nothing`() =
        runTest {
            val detail = sampleComicDetail()
            val file = checkNotNull(detail.pagedFile)
            val harness = ComicHarness(this, detail = detail)
            // Komga analyses the comic's new file: it answers for the book, without its file.
            harness.library.bookResult = ApiResult.Success(detail.copy(files = emptyList(), filesUnavailable = true))
            harness.kept.details[AlbumBook] = detail
            harness.downloads.has(AlbumBook, DownloadFormat.Text)

            val state = harness.reader().state.value

            assertThat(state.phase).isEqualTo(ComicPhase.Ready)
            assertThat(harness.opener.opened)
                .containsExactly(ComicKey(file.id, file.contentVersion, PagedKind.Comic, AlbumBook))
        }

    @Test
    fun `a comic whose kept copy is not whole on the device, while the server cannot serve it, has no comic`() =
        runTest {
            val detail = sampleComicDetail()
            val unavailable = detail.copy(files = emptyList(), filesUnavailable = true)
            // A page kept, but its pages still on their way, being deleted, or none of them recorded.
            val records: List<(ComicHarness) -> Unit> = listOf(
                { it.downloads.has(AlbumBook, DownloadFormat.Text, FormatState.Queued) },
                { it.downloads.has(AlbumBook, DownloadFormat.Text, removing = true) },
                {},
            )
            records.forEach { record ->
                val harness = ComicHarness(this, detail = detail)
                harness.library.bookResult = ApiResult.Success(unavailable)
                harness.kept.details[AlbumBook] = detail
                record(harness)

                assertThat(harness.reader().state.value.phase).isEqualTo(ComicPhase.Failed(ComicFailure.NoComic))
                assertThat(harness.opener.opened).isEmpty()
            }
        }

    @Test
    fun `a comic not kept whose files the server cannot serve for now has no comic to read`() = runTest {
        val detail = sampleComicDetail()
        val harness = ComicHarness(this, detail = detail)
        harness.library.bookResult = ApiResult.Success(detail.copy(files = emptyList(), filesUnavailable = true))

        assertThat(harness.reader().state.value.phase).isEqualTo(ComicPhase.Failed(ComicFailure.NoComic))
        assertThat(harness.opener.opened).isEmpty()
    }

    @Test
    fun `the kinds opened are a comic's and a DjVu's`() {
        assertThat(albums.map { it.pagedKind }).containsExactly(PagedKind.Comic, PagedKind.Djvu).inOrder()
    }

    @Test
    fun `what is read of it without the server is kept on the device, and waits to be sent`() = runTest {
        val harness = offline(sampleComicDetail())
        val reader = harness.reader()
        harness.clocks.advance(40.seconds)

        reader.next()
        reader.events.test {
            reader.leave()

            assertThat(awaitItem()).isEqualTo(ComicReaderEvent.Leave)
        }

        assertThat(harness.store.positions[AlbumFile]?.page).isEqualTo(2)
        assertThat(harness.outbox.log.first()).isEqualTo("position $AlbumFile")
        assertThat(harness.outbox.log.last()).startsWith("session $AlbumFile")
    }

    // --- the row of the sheet at the end

    @Test
    fun `the sheet at the end of a kept comic or DjVu says what the device keeps of it`() = runTest {
        albums.forEach { detail ->
            val harness = ComicHarness(this, detail = detail)
            harness.downloads.has(AlbumBook, DownloadFormat.Text)

            val reader = atTheEnd(harness)

            assertThat(reader.state.value.endPanel).isEqualTo(ComicEndPanel(device = kept))
        }
    }

    @Test
    fun `an album half fetched has no row, until it is whole`() = runTest {
        val harness = ComicHarness(this)
        harness.downloads.has(AlbumBook, DownloadFormat.Text, state = FormatState.Downloading(400, 1_000))
        val reader = atTheEnd(harness)
        runCurrent()
        assertThat(reader.state.value.endPanel).isEqualTo(ComicEndPanel())
        reader.deleteFromDevice()
        runCurrent()
        assertThat(harness.deletions.asked).isEmpty()

        harness.downloads.has(AlbumBook, DownloadFormat.Text)
        runCurrent()

        assertThat(reader.state.value.endPanel?.device).isEqualTo(kept)
    }

    @Test
    fun `delete asks for the kept album to go when it closes, and undo keeps it after all`() = runTest {
        val harness = ComicHarness(this)
        harness.downloads.has(AlbumBook, DownloadFormat.Text)
        harness.deletions.held += AlbumBook
        val reader = atTheEnd(harness)

        reader.deleteFromDevice()
        runCurrent()
        assertThat(harness.deletions.asked).containsExactly("delete $AlbumBook")
        assertThat(reader.state.value.endPanel?.device).isEqualTo(kept.copy(deleting = true))

        reader.keepOnDevice()
        runCurrent()
        assertThat(harness.deletions.asked).containsExactly("delete $AlbumBook", "keep $AlbumBook").inOrder()
        assertThat(reader.state.value.endPanel?.device).isEqualTo(kept)
    }

    @Test
    fun `the row names whatever the device keeps whole of the book, as a PDF's does`() = runTest {
        val harness = ComicHarness(this)
        harness.downloads.has(AlbumBook, DownloadFormat.Audiobook)

        val reader = atTheEnd(harness)
        runCurrent()

        assertThat(reader.state.value.endPanel?.device?.formats).containsExactly(DownloadFormat.Audiobook)
    }
}
