package app.tfdn.lirelle.feature.book.download

import app.tfdn.lirelle.core.audio.narration.ReadAlongMap
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.DownloadFormat.Audiobook
import app.tfdn.lirelle.core.downloads.model.DownloadFormat.Narration
import app.tfdn.lirelle.core.downloads.model.DownloadFormat.Text
import app.tfdn.lirelle.core.downloads.model.FormatState
import app.tfdn.lirelle.core.downloads.model.WaitReason
import app.tfdn.lirelle.core.downloads.offline.KeptBookData
import app.tfdn.lirelle.core.downloads.offline.KeptReading
import app.tfdn.lirelle.core.downloads.offline.OwnProgress
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.AudioManifest
import app.tfdn.lirelle.core.library.model.BookDetail
import app.tfdn.lirelle.core.library.model.BookReading
import app.tfdn.lirelle.core.library.model.PlaybackState
import app.tfdn.lirelle.core.testing.sampleBookDetail
import app.tfdn.lirelle.core.testing.sampleComicDetail
import app.tfdn.lirelle.feature.book.Openings
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Test

class DownloadedPagesTest {

    private val detail = sampleBookDetail(id = "12")
    private val downloads = FakeDownloads()
    private val kept = object : KeptBookData {
        var details = mutableMapOf<BookId, BookDetail>()
        val keeps = mutableListOf<String>()

        override suspend fun keep(detail: BookDetail, readingPercent: Int?, readAt: Instant?) {
            keeps += "${detail.id} $readingPercent $readAt"
        }

        override suspend fun detail(bookId: BookId): BookDetail? = details[bookId]
        override suspend fun reading(bookId: BookId): KeptReading? = null
        override fun coverFile(bookId: BookId): File? = null
        override suspend fun keepManifest(manifest: AudioManifest) = Unit
        override suspend fun manifest(bookId: BookId): AudioManifest? = null
        override suspend fun keepMap(bookId: BookId, fileId: FileId, version: String?): Boolean = false
        override suspend fun map(bookId: BookId, fileId: FileId): ReadAlongMap? = null
        override suspend fun remove(bookId: BookId, format: DownloadFormat?) = Unit
    }
    private val listening =
        PlaybackState("aud_one", 14_000, 1.4, completed = false, capturedAt = null, version = "", null)
    private val refreshed = mutableListOf<BookId>()
    private val places = KeptPlaces(
        progress = { book -> 11.takeIf { book.bookId == BookId("12") } },
        listening = { book -> listening.takeIf { book.bookId == BookId("12") } },
        refresh = { refreshed += it.id },
    )
    private val pages = DownloadedPages(downloads, kept, places)

    @Test
    fun `the page of a downloaded book is what was kept, with its whole formats and the device's progress`() =
        runTest {
            kept.details[BookId("12")] = detail
            downloads.kept.value = downloaded(
                complete(Text),
                record(Audiobook, FormatState.Queued),
                record(Narration, FormatState.Complete(48 * Mb), removing = true),
            )

            assertThat(pages.page(BookId("12")))
                .isEqualTo(KeptPage(detail, complete = setOf(Text), percent = 11, listening = listening))
        }

    @Test
    fun `the page of a downloaded book says when the device made the progress it shows`() = runTest {
        val readOnDevice = Instant.parse("2026-11-23T09:00:00Z")
        val own = DownloadedPages(
            downloads,
            kept,
            KeptPlaces(progress = { 11 }, own = { OwnProgress(35, readOnDevice) }),
        )
        kept.details[BookId("12")] = detail
        downloads.kept.value = downloaded(complete(Text))

        assertThat(own.page(BookId("12")))
            .isEqualTo(KeptPage(detail, complete = setOf(Text), percent = 35, ownAt = readOnDevice))
        // What the server said last is no progress of the device's.
        assertThat(pages.page(BookId("12"))?.ownAt).isNull()
    }

    @Test
    fun `an album half fetched is not whole, and its page built without the server does not open it`() = runTest {
        val comic = sampleComicDetail(id = "12")
        kept.details[BookId("12")] = comic
        downloads.kept.value = downloaded(record(Text, FormatState.Downloading(40 * Mb, 96 * Mb)))

        val half = checkNotNull(pages.page(BookId("12")))
        assertThat(half.complete).isEmpty()
        assertThat(Openings(comic, half.complete).read).isFalse()

        downloads.kept.value = downloaded(complete(Text))

        val whole = checkNotNull(pages.page(BookId("12")))
        assertThat(whole.complete).containsExactly(Text)
        assertThat(Openings(comic, whole.complete).read).isTrue()
    }

    @Test
    fun `a book that is not downloaded has no page`() = runTest {
        kept.details[BookId("12")] = detail

        assertThat(pages.page(BookId("12"))).isNull()
    }

    @Test
    fun `a book whose page was not kept has none`() = runTest {
        downloads.kept.value = downloaded(complete(Text))

        assertThat(pages.page(BookId("12"))).isNull()
    }

    @Test
    fun `the page of a downloaded book is kept again, with where the server says the reading is`() = runTest {
        downloads.kept.value = downloaded(complete(Text))
        val readAt = Instant.parse("2026-03-01T20:00:00Z")

        pages.keep(detail, BookReading(42, readAt, totalSeconds = 7_200, sessionCount = 5))
        pages.keep(detail, reading = null)

        assertThat(kept.keeps).containsExactly("12 42 $readAt", "12 null null").inOrder()
    }

    @Test
    fun `each time its page is loaded, a downloaded book keeps where the server says the user is`() = runTest {
        downloads.kept.value = downloaded(complete(Text))

        pages.keep(detail, reading = null)
        pages.keep(detail, reading = null)

        assertThat(refreshed).containsExactly(BookId("12"), BookId("12"))
    }

    @Test
    fun `the page of a book only asked, that waits for Wi-Fi before its first byte, is kept`() = runTest {
        downloads.kept.value = downloaded(record(Audiobook, FormatState.Waiting(WaitReason.UnmeteredOnly, 0, 620 * Mb)))

        pages.keep(detail, BookReading(42, null, totalSeconds = 0, sessionCount = 0))

        assertThat(kept.keeps).containsExactly("12 42 null")
    }

    @Test
    fun `nothing is kept of a book that is not downloaded`() = runTest {
        pages.keep(detail, BookReading(42, null, totalSeconds = 0, sessionCount = 0))

        assertThat(kept.keeps).isEmpty()
        assertThat(refreshed).isEmpty()
    }
}
