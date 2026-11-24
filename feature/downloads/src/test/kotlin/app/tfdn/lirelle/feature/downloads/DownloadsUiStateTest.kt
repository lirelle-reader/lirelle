package app.tfdn.lirelle.feature.downloads

import app.tfdn.lirelle.core.downloads.QueueState
import app.tfdn.lirelle.core.downloads.model.DownloadFailure
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.FormatState
import app.tfdn.lirelle.core.downloads.model.PageCount
import app.tfdn.lirelle.core.downloads.model.TextName
import app.tfdn.lirelle.core.downloads.model.WaitReason
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.network.ApiError
import com.google.common.truth.Truth.assertThat
import java.time.Duration
import org.junit.Test

class DownloadsUiStateTest {

    private val kept = mapOf(
        BookId("1") to keptBook("Dune", "Frank Writer", percent = 11),
        BookId("2") to keptBook("Red Mars"),
        BookId("3") to keptBook("Slow Horses"),
    )

    @Test
    fun `the downloaded books are in the order of their last activity, the most recent first`() {
        val books = listOf(
            downloaded("1", downloadedAt = Day),
            downloaded("2", downloadedAt = Day.plus(Duration.ofDays(2))),
            // Downloaded first, opened last.
            downloaded("3", downloadedAt = Day.minus(Duration.ofDays(5)), lastOpenedAt = Day.plus(Duration.ofDays(3))),
        )

        val state = downloadsUiState(books, IdleQueue, kept, Storage)

        assertThat(state.books.map { it.bookId }).containsExactly(BookId("3"), BookId("2"), BookId("1")).inOrder()
    }

    @Test
    fun `a row says what is kept of the book, its size and its progress when it has one`() {
        val books = listOf(
            downloaded("1", DownloadFormat.Text to 4 * Mb, DownloadFormat.Audiobook to 900 * Mb),
            downloaded("2", downloadedAt = Day.minusSeconds(1)),
        )

        val state = downloadsUiState(books, IdleQueue, kept, Storage)

        val dune = state.books[0]
        assertThat(dune.title).isEqualTo("Dune")
        assertThat(dune.authors).isEqualTo("Frank Writer")
        assertThat(dune.formats).containsExactly(DownloadFormat.Text, DownloadFormat.Audiobook).inOrder()
        assertThat(dune.bytes).isEqualTo(904 * Mb)
        assertThat(dune.percent).isEqualTo(11)
        assertThat(state.books[1].percent).isNull()
    }

    @Test
    fun `a row says what its kept text is named by, and so do the formats offered to delete`() {
        val album = downloaded("1", DownloadFormat.Text to 4 * Mb, DownloadFormat.Audiobook to 900 * Mb).let { book ->
            book.copy(formats = book.formats.map { it.copy(textName = TextName.Cbz) })
        }

        val state = downloadsUiState(listOf(album, downloaded("2")), IdleQueue, kept, Storage)

        assertThat(state.books.map { it.textName }).containsExactly(TextName.Cbz, TextName.Text).inOrder()
        assertThat(album.complete()).containsExactly(
            DeleteOption(DownloadFormat.Text, 4 * Mb, TextName.Cbz),
            DeleteOption(DownloadFormat.Audiobook, 900 * Mb),
        ).inOrder()
    }

    @Test
    fun `a download made of pages is in progress with its pages and the name of its text`() {
        val queue = IdleQueue.copy(
            active = albumEntry("3", kept = 12, total = 48),
            waiting = listOf(albumEntry("4", kept = 0, total = 9, name = TextName.Djvu), entry("5")),
        )

        val state = downloadsUiState(listOf(fetching("3"), fetching("4"), fetching("5")), queue, kept, Storage)

        val (album, document, audiobook) = state.inProgress
        assertThat(album.pages).isEqualTo(PageCount(12, 48))
        assertThat(album.textName).isEqualTo(TextName.Cbz)
        // The bytes go on being counted, against the estimate: the totals use them.
        assertThat(album.status).isEqualTo(ProgressStatus.Active(12 * Mb, 9 * Mb))
        assertThat(document.pages).isEqualTo(PageCount(0, 9))
        assertThat(document.textName).isEqualTo(TextName.Djvu)
        assertThat(audiobook.pages).isNull()
        assertThat(audiobook.textName).isEqualTo(TextName.Text)
    }

    @Test
    fun `the banner and the header go on counting bytes for a download made of pages`() {
        val queue = QueueState(null, listOf(albumEntry("3", 12, 48)), WaitReason.UnmeteredOnly, leftBytes = 6 * Mb)
        val waiting = FormatState.Waiting(WaitReason.UnmeteredOnly, 3 * Mb, 9 * Mb)
        val fetchingAlbum = fetching("3").copy(formats = listOf(record(DownloadFormat.Text, waiting)))

        val state = downloadsUiState(listOf(fetchingAlbum), queue, kept, Storage)

        assertThat(state.wifi).isEqualTo(WifiBannerUi(bookCount = 1, leftBytes = 6 * Mb))
        assertThat(state.storage.downloadsBytes).isEqualTo(3 * Mb)
    }

    @Test
    fun `a book fetched for the first time is in progress only`() {
        val queue = IdleQueue.copy(active = entry("3", receivedBytes = 124 * Mb), leftBytes = 288 * Mb)

        val state = downloadsUiState(listOf(fetching("3")), queue, kept, Storage)

        assertThat(state.books).isEmpty()
        val row = state.inProgress.single()
        assertThat(row.bookId).isEqualTo(BookId("3"))
        assertThat(row.title).isEqualTo("Slow Horses")
        assertThat(row.formats).containsExactly(DownloadFormat.Audiobook)
        assertThat(row.status).isEqualTo(ProgressStatus.Active(124 * Mb, 412 * Mb))
    }

    @Test
    fun `a downloaded book fetching another format is in both lists, with what is complete in the second`() {
        val adding = record(DownloadFormat.Audiobook, FormatState.Queued)
        val book = downloaded("1").let { it.copy(formats = it.formats + adding) }
        val queue = IdleQueue.copy(active = entry("1"))

        val state = downloadsUiState(listOf(book), queue, kept, Storage)

        assertThat(state.inProgress.map { it.bookId }).containsExactly(BookId("1"))
        assertThat(state.books.single().formats).containsExactly(DownloadFormat.Text)
        assertThat(state.books.single().bytes).isEqualTo(4 * Mb)
    }

    @Test
    fun `the active download comes first, then the queued and the failed ones in the queue's order`() {
        val failed = entry("2").copy(failure = DownloadFailure.Server(ApiError.ServerUnreachable))
        val queue = IdleQueue.copy(active = entry("3"), waiting = listOf(entry("1", totalBytes = null), failed))

        val state = downloadsUiState(listOf(fetching("1"), fetching("2"), fetching("3")), queue, kept, Storage)

        assertThat(state.inProgress.map { it.bookId })
            .containsExactly(BookId("3"), BookId("1"), BookId("2")).inOrder()
        assertThat(state.inProgress.map { it.status }).containsExactly(
            ProgressStatus.Active(0, 412 * Mb),
            ProgressStatus.Queued(null),
            ProgressStatus.Failed(DownloadFailure.Server(ApiError.ServerUnreachable)),
        ).inOrder()
    }

    @Test
    fun `the download stopped by the wait for Wi-Fi keeps what it received, those behind it are queued`() {
        val paused = entry("3", receivedBytes = 11 * Mb, totalBytes = 98 * Mb)
        val queue = QueueState(null, listOf(paused, entry("1")), WaitReason.UnmeteredOnly, leftBytes = 499 * Mb)

        val state = downloadsUiState(listOf(fetching("3"), fetching("1")), queue, kept, Storage)

        assertThat(state.inProgress.map { it.status }).containsExactly(
            ProgressStatus.Waiting(WaitReason.UnmeteredOnly, 11 * Mb, 98 * Mb),
            ProgressStatus.Queued(412 * Mb),
        ).inOrder()
    }

    @Test
    fun `the download stopped by the wait for a network says which wait it is`() {
        val queue = QueueState(null, listOf(entry("3", totalBytes = null)), WaitReason.NoNetwork, leftBytes = null)

        val state = downloadsUiState(listOf(fetching("3")), queue, kept, Storage)

        assertThat(state.inProgress.single().status).isEqualTo(ProgressStatus.Waiting(WaitReason.NoNetwork, 0, null))
    }

    @Test
    fun `the one that waits is the first that did not fail`() {
        val failed = entry("2").copy(failure = DownloadFailure.NotAnEpub)
        val queue =
            QueueState(null, listOf(failed, entry("1"), entry("3")), WaitReason.UnmeteredOnly, leftBytes = null)

        val state = downloadsUiState(listOf(fetching("2"), fetching("1"), fetching("3")), queue, kept, Storage)

        assertThat(state.inProgress.map { it.status }).containsExactly(
            ProgressStatus.Failed(DownloadFailure.NotAnEpub),
            ProgressStatus.Waiting(WaitReason.UnmeteredOnly, 0, 412 * Mb),
            ProgressStatus.Queued(412 * Mb),
        ).inOrder()
    }

    @Test
    fun `a queue that waits for nothing has no waiting row, even between two downloads`() {
        val queue = IdleQueue.copy(waiting = listOf(entry("1"), entry("3")))

        val state = downloadsUiState(listOf(fetching("1"), fetching("3")), queue, kept, Storage)

        assertThat(state.inProgress.map { it.status })
            .containsExactly(ProgressStatus.Queued(412 * Mb), ProgressStatus.Queued(412 * Mb))
    }

    @Test
    fun `a queue waiting for Wi-Fi shows the banner with what is left`() {
        val queue = QueueState(null, listOf(entry("1"), entry("3")), WaitReason.UnmeteredOnly, leftBytes = 666 * Mb)

        val state = downloadsUiState(listOf(fetching("1"), fetching("3")), queue, kept, Storage)

        assertThat(state.wifi).isEqualTo(WifiBannerUi(bookCount = 2, leftBytes = 666 * Mb))
        assertThat(state.waitingForNetwork).isFalse()
    }

    @Test
    fun `the banner counts what is left of the download stopped half-way`() {
        val paused = entry("3", DownloadFormat.Narration, receivedBytes = 11 * Mb, totalBytes = 209 * Mb)
        val queue = QueueState(null, listOf(paused), WaitReason.UnmeteredOnly, leftBytes = 198 * Mb)

        val state = downloadsUiState(listOf(fetching("3", DownloadFormat.Narration)), queue, kept, Storage)

        assertThat(state.wifi).isEqualTo(WifiBannerUi(bookCount = 1, leftBytes = 198 * Mb))
    }

    @Test
    fun `a failed download is not counted among those waiting for Wi-Fi`() {
        val failed = entry("2").copy(failure = DownloadFailure.NotAnEpub)
        val queue = QueueState(null, listOf(entry("1"), failed), WaitReason.UnmeteredOnly, leftBytes = null)

        val state = downloadsUiState(listOf(fetching("1"), fetching("2")), queue, kept, Storage)

        assertThat(state.wifi).isEqualTo(WifiBannerUi(bookCount = 1, leftBytes = null))
    }

    @Test
    fun `a queue waiting for any network says so, without the banner`() {
        val queue = QueueState(null, listOf(entry("1")), WaitReason.NoNetwork, leftBytes = 412 * Mb)

        val state = downloadsUiState(listOf(fetching("1")), queue, kept, Storage)

        assertThat(state.wifi).isNull()
        assertThat(state.waitingForNetwork).isTrue()
    }

    @Test
    fun `a queue that waits for nothing has neither`() {
        val state = downloadsUiState(listOf(fetching("3")), IdleQueue.copy(active = entry("3")), kept, Storage)

        assertThat(state.wifi).isNull()
        assertThat(state.waitingForNetwork).isFalse()
    }

    @Test
    fun `the storage counts every byte on the device and the books that have something complete`() {
        val partial = fetching("3").let {
            it.copy(formats = listOf(record(DownloadFormat.Audiobook, FormatState.Downloading(100 * Mb, 412 * Mb))))
        }
        val books = listOf(
            downloaded("1", DownloadFormat.Text to 4 * Mb),
            downloaded("2", DownloadFormat.Audiobook to Gb),
            partial,
        )

        val state = downloadsUiState(books, IdleQueue.copy(active = entry("3")), kept, Storage)

        assertThat(state.storage).isEqualTo(
            StorageUi(
                downloadsBytes = Gb + 104 * Mb,
                bookCount = 2,
                freeBytes = 41 * Gb,
                totalBytes = 128 * Gb,
                queued = true,
            ),
        )
    }

    @Test
    fun `the storage says whether something is still asked, running, waiting or failed`() {
        val failed = entry("2").copy(failure = DownloadFailure.NotAnEpub)
        fun queued(queue: QueueState) = downloadsUiState(listOf(downloaded("1")), queue, kept, Storage).storage.queued

        val waiting = QueueState(null, listOf(entry("3")), WaitReason.NoNetwork, leftBytes = null)

        assertThat(queued(IdleQueue)).isFalse()
        assertThat(queued(IdleQueue.copy(active = entry("3")))).isTrue()
        assertThat(queued(waiting)).isTrue()
        assertThat(queued(IdleQueue.copy(waiting = listOf(failed)))).isTrue()
    }

    @Test
    fun `nothing downloaded and nothing asked is empty`() {
        val state = downloadsUiState(emptyList(), IdleQueue, emptyMap(), Storage)

        assertThat(state.isEmpty).isTrue()
        assertThat(state.loaded).isTrue()
        assertThat(state.storage).isEqualTo(StorageUi(0, 0, 41 * Gb, 128 * Gb))
    }

    @Test
    fun `a download in progress alone is not empty`() {
        val state = downloadsUiState(listOf(fetching("3")), IdleQueue.copy(active = entry("3")), kept, Storage)

        assertThat(state.isEmpty).isFalse()
    }

    @Test
    fun `a book whose page was not kept still has its row, without a title of its own`() {
        val state = downloadsUiState(listOf(downloaded("9")), IdleQueue, kept, Storage)

        assertThat(state.books.single().title).isNull()
    }

    @Test
    fun `a download whose page was not kept, or kept without a title, still has its row, without a title`() {
        val queue = IdleQueue.copy(waiting = listOf(entry("9")), waitReason = WaitReason.UnmeteredOnly)

        val missing = downloadsUiState(listOf(fetching("9")), queue, kept, Storage)
        val blank = downloadsUiState(listOf(fetching("9")), queue, mapOf(BookId("9") to KeptBook.NoPage), Storage)

        assertThat(missing.inProgress.single().title).isNull()
        assertThat(blank.inProgress.single().title).isNull()
    }
}
