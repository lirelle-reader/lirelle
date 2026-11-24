package app.tfdn.lirelle.core.downloads.job

import app.tfdn.lirelle.core.downloads.model.DownloadFailure
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.FormatRecord
import app.tfdn.lirelle.core.downloads.model.FormatState
import app.tfdn.lirelle.core.downloads.model.FormatUpdate
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BatchCountTest {
    private val count = BatchCount()

    private val queued = record(FormatState.Queued)
    private val fetching = record(FormatState.Downloading(1, 400))
    private val failed = record(FormatState.Failed(DownloadFailure.Damaged, 1))

    private fun whole(bytes: Long, format: DownloadFormat = DownloadFormat.Audiobook) =
        record(FormatState.Complete(bytes), format)

    private fun FormatRecord.updating(state: FormatState?, bytes: Long = 30) =
        copy(update = FormatUpdate(version = "2", files = emptyList(), bytes = bytes, state = state))

    @Test
    fun `nothing ended, nothing to tell`() {
        count.follow(listOf(book("1", queued)))
        count.follow(listOf(book("1", fetching)))

        assertThat(count.take()).isNull()
    }

    @Test
    fun `the books whose formats became whole are counted once, with their bytes`() {
        val text = record(FormatState.Queued, DownloadFormat.Text)
        count.follow(listOf(book("1", queued, text), book("2", queued), book("3", whole(999))))
        count.follow(listOf(book("1", whole(400), text), book("2", fetching), book("3", whole(999))))
        count.follow(listOf(book("1", whole(400), whole(50, DownloadFormat.Text)), book("2", whole(300))))

        assertThat(count.take()).isEqualTo(Batch(books = 2, bytes = 750, failed = 0))
    }

    @Test
    fun `what was whole before the count began is not counted`() {
        count.follow(listOf(book("1", whole(400)), book("2", failed)))

        assertThat(count.take()).isNull()
    }

    @Test
    fun `a book that failed is counted as failed`() {
        count.follow(listOf(book("1", queued), book("2", queued)))
        count.follow(listOf(book("1", whole(400)), book("2", failed)))

        assertThat(count.take()).isEqualTo(Batch(books = 1, bytes = 400, failed = 1))
    }

    @Test
    fun `a failure asked again that ends whole is no failure`() {
        count.follow(listOf(book("1", queued)))
        count.follow(listOf(book("1", failed)))
        count.follow(listOf(book("1", queued)))
        count.follow(listOf(book("1", whole(400))))

        assertThat(count.take()).isEqualTo(Batch(books = 1, bytes = 400, failed = 0))
    }

    @Test
    fun `a failure cancelled is not told, nor are cancellations`() {
        count.follow(listOf(book("1", queued), book("2", fetching)))
        count.follow(listOf(book("1", failed), book("2", fetching)))
        count.follow(emptyList())

        assertThat(count.take()).isNull()
    }

    @Test
    fun `an update swapped in counts as its book, with what it fetched`() {
        val kept = whole(400)
        count.follow(listOf(book("1", kept.updating(FormatState.Queued))))
        count.follow(listOf(book("1", kept.updating(FormatState.Downloading(10, 30)))))
        count.follow(listOf(book("1", whole(410).copy(version = "2"))))

        assertThat(count.take()).isEqualTo(Batch(books = 1, bytes = 30, failed = 0))
    }

    @Test
    fun `an update that went without a new version in its place fetched nothing, and is not told`() {
        val kept = whole(400)
        // The server was back on the kept version when the update was planned.
        count.follow(listOf(book("1", kept.updating(FormatState.Queued))))
        count.follow(listOf(book("1", kept)))

        assertThat(count.take()).isNull()
    }

    @Test
    fun `an update fetched whole that waits for its book to close counts when it is fetched, once`() {
        val kept = whole(400)
        count.follow(listOf(book("1", kept.updating(FormatState.Downloading(10, 30)))))
        count.follow(listOf(book("1", kept.updating(FormatState.Complete(30)))))
        assertThat(count.take()).isEqualTo(Batch(books = 1, bytes = 30, failed = 0))

        count.follow(listOf(book("1", whole(410).copy(version = "2"))))
        assertThat(count.take()).isNull()
    }

    @Test
    fun `an update that fails is a failure, one taken back is nothing`() {
        val kept = whole(400)
        count.follow(listOf(book("1", kept.updating(FormatState.Queued)), book("2", kept.updating(FormatState.Queued))))
        count.follow(
            listOf(
                book("1", kept.updating(FormatState.Failed(DownloadFailure.Damaged, 0))),
                book("2", kept.updating(null)),
            ),
        )

        assertThat(count.take()).isEqualTo(Batch(books = 0, bytes = 0, failed = 1))
    }

    @Test
    fun `a batch told starts another`() {
        count.follow(listOf(book("1", queued)))
        count.follow(listOf(book("1", whole(400))))
        assertThat(count.take()).isNotNull()

        count.follow(listOf(book("1", whole(400)), book("2", queued)))
        count.follow(listOf(book("1", whole(400)), book("2", whole(7))))

        assertThat(count.take()).isEqualTo(Batch(books = 1, bytes = 7, failed = 0))
    }
}
