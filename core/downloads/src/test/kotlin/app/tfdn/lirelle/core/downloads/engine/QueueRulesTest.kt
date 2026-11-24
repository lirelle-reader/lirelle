package app.tfdn.lirelle.core.downloads.engine

import app.tfdn.lirelle.core.downloads.QueueEntry
import app.tfdn.lirelle.core.downloads.model.DownloadFailure
import app.tfdn.lirelle.core.downloads.model.DownloadFormat.Audiobook
import app.tfdn.lirelle.core.downloads.model.DownloadFormat.Narration
import app.tfdn.lirelle.core.downloads.model.DownloadFormat.Text
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.DownloadedBook
import app.tfdn.lirelle.core.downloads.model.FileKind
import app.tfdn.lirelle.core.downloads.model.FileSource
import app.tfdn.lirelle.core.downloads.model.FormatRecord
import app.tfdn.lirelle.core.downloads.model.FormatState
import app.tfdn.lirelle.core.downloads.model.FormatUpdate
import app.tfdn.lirelle.core.downloads.model.PlannedFile
import app.tfdn.lirelle.core.downloads.model.WaitReason
import app.tfdn.lirelle.core.ids.BookId
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import org.junit.Test

private val OnWifi = Conditions(Wifi, allowMetered = false)
private val OnMobile = Conditions(Mobile, allowMetered = false)
private val WithoutNetwork = Conditions(NoNetwork, allowMetered = false)

private fun at(second: Long): Instant = Instant.ofEpochSecond(second)

private fun record(
    format: DownloadFormat,
    state: FormatState = FormatState.Queued,
    askedAt: Long = 0,
    anyNetwork: Boolean = false,
    removing: Boolean = false,
) = FormatRecord(format, state, emptyList(), null, null, at(askedAt), anyNetwork, removing)

/** A complete format whose update, of thirty bytes, is in [state]: null while it waits for the user. */
private fun updating(format: DownloadFormat, state: FormatState?, askedAt: Long = 0) =
    record(format, FormatState.Complete(10), askedAt).copy(update = FormatUpdate("v2", emptyList(), 30, state))

private fun planned(name: String, bytes: Long?) =
    PlannedFile(name, FileSource.AudioAsset(name), "v1", bytes, resumable = true, kind = FileKind.Audio)

private fun book(id: String, vararg formats: FormatRecord) = DownloadedBook(BookId(id), formats.toList(), at(0), null)

class QueueRulesTest {

    @Test
    fun `a download runs on an unmetered network, and waits for one on a metered network`() {
        assertThat(waitReason(record(Text), OnWifi)).isNull()
        assertThat(waitReason(record(Text), OnMobile)).isEqualTo(WaitReason.UnmeteredOnly)
    }

    @Test
    fun `the setting, or the user's word for this download, lets it use a metered network`() {
        assertThat(waitReason(record(Text), OnMobile.copy(allowMetered = true))).isNull()
        assertThat(waitReason(record(Text, anyNetwork = true), OnMobile)).isNull()
    }

    @Test
    fun `without a network every download waits for one`() {
        assertThat(waitReason(record(Text, anyNetwork = true), WithoutNetwork.copy(allowMetered = true)))
            .isEqualTo(WaitReason.NoNetwork)
    }

    @Test
    fun `within a book the text comes first, then the narration's EPUB, then the audio`() {
        val all = book("1", record(Narration), record(Audiobook), record(Text))

        assertThat(steps(all)).containsExactly(
            Step(Text, StepFiles.All),
            Step(Narration, StepFiles.EpubOnly),
            Step(Audiobook, StepFiles.All),
            Step(Narration, StepFiles.All),
        ).inOrder()
    }

    @Test
    fun `only what is still to fetch has steps`() {
        val some = book(
            "1",
            record(Text, FormatState.Complete(10)),
            record(Audiobook, FormatState.Failed(DownloadFailure.Damaged, 0)),
            record(Narration, FormatState.Waiting(WaitReason.NoNetwork, 3, 10), removing = true),
        )

        assertThat(steps(some)).isEmpty()
        assertThat(steps(book("1", record(Audiobook, FormatState.Downloading(1, 2)))))
            .containsExactly(Step(Audiobook, StepFiles.All))
    }

    @Test
    fun `books are fetched in the order they were asked`() {
        val books = listOf(
            book("1", record(Text, askedAt = 20)),
            book("2", record(Text, FormatState.Complete(5), askedAt = 1), record(Audiobook, askedAt = 10)),
            book("3", record(Text, FormatState.Complete(5), askedAt = 2)),
        )

        assertThat(queueOrder(books).map { it.bookId }).containsExactly(BookId("2"), BookId("1")).inOrder()
        assertThat(nextBook(books, OnWifi)?.bookId).isEqualTo(BookId("2"))
    }

    @Test
    fun `of books asked at the same time, the one first downloaded comes first, whatever its id`() {
        val books = listOf(
            book("1", record(Text, askedAt = 5)).copy(downloadedAt = at(3)),
            book("2", record(Text, askedAt = 5)).copy(downloadedAt = at(1)),
        )

        assertThat(queueOrder(books).map { it.bookId }).containsExactly(BookId("2"), BookId("1")).inOrder()
    }

    @Test
    fun `a book that waits for an unmetered network lets one that may use any network go`() {
        val books = listOf(
            book("1", record(Text, askedAt = 1)),
            book("2", record(Text, askedAt = 2, anyNetwork = true)),
        )

        assertThat(nextBook(books, OnMobile)?.bookId).isEqualTo(BookId("2"))
        assertThat(nextBook(books, WithoutNetwork)).isNull()
    }

    @Test
    fun `a failed book does not hold the next one back`() {
        val books = listOf(
            book("1", record(Text, FormatState.Failed(DownloadFailure.NotAnEpub, 0), askedAt = 1)),
            book("2", record(Text, askedAt = 2)),
        )

        assertThat(nextBook(books, OnWifi)?.bookId).isEqualTo(BookId("2"))
    }

    @Test
    fun `a cut download is tried again after 5 s, 30 s, 2 min, then every 5 min`() {
        assertThat((1..6).map { backOff(it) })
            .containsExactly(5.seconds, 30.seconds, 2.minutes, 5.minutes, 5.minutes, 5.minutes).inOrder()
    }

    @Test
    fun `what cannot run is marked as waiting, with what it has received`() {
        val before = book(
            "1",
            record(Text, FormatState.Downloading(4, 10)),
            record(Audiobook),
            record(Narration, FormatState.Complete(7)),
        )

        val after = before.waitingFor(OnMobile)

        assertThat(after.formats.map { it.state }).containsExactly(
            FormatState.Waiting(WaitReason.UnmeteredOnly, 4, 10),
            FormatState.Waiting(WaitReason.UnmeteredOnly, 0, null),
            FormatState.Complete(7),
        ).inOrder()
        assertThat(before.waitingFor(OnWifi)).isEqualTo(before)
    }

    @Test
    fun `a download that waits before its first byte says the size it was asked with`() {
        val before = book("1", record(Audiobook).copy(expectedBytes = 412), record(Text))

        val after = before.waitingFor(OnMobile)

        assertThat(after.formats.map { it.state }).containsExactly(
            FormatState.Waiting(WaitReason.UnmeteredOnly, 0, 412),
            FormatState.Waiting(WaitReason.UnmeteredOnly, 0, null),
        ).inOrder()
        val queue = queueState(listOf(after))
        assertThat(queue.leftBytes).isEqualTo(412)
        assertThat(queue.waitReason).isEqualTo(WaitReason.UnmeteredOnly)
    }

    @Test
    fun `once planned, a download that waits before its first byte says the size of its files`() {
        val files = listOf(planned("a", 100), planned("b", 250))
        val before = book("1", record(Audiobook).copy(files = files, expectedBytes = 412))
        val unsized = book("2", record(Audiobook).copy(files = files + planned("c", null), expectedBytes = 412))

        assertThat(before.waitingFor(WithoutNetwork).formats.single().state)
            .isEqualTo(FormatState.Waiting(WaitReason.NoNetwork, 0, 350))
        assertThat(unsized.waitingFor(WithoutNetwork).formats.single().state)
            .isEqualTo(FormatState.Waiting(WaitReason.NoNetwork, 0, 412))
    }

    @Test
    fun `a download already waiting without a size takes the one it was asked with`() {
        val waiting = record(Audiobook, FormatState.Waiting(WaitReason.NoNetwork, 0, null)).copy(expectedBytes = 412)
        val sized = record(Text, FormatState.Waiting(WaitReason.NoNetwork, 5, 40)).copy(expectedBytes = 99)

        assertThat(book("1", waiting, sized).waitingFor(OnMobile).formats.map { it.state }).containsExactly(
            FormatState.Waiting(WaitReason.UnmeteredOnly, 0, 412),
            FormatState.Waiting(WaitReason.UnmeteredOnly, 5, 40),
        ).inOrder()
    }

    @Test
    fun `what is asked is recorded with the sizes the page announced`() {
        val known = Known(mapOf(Audiobook to 412L, Narration to 98L))
        val asked = asked(null, BookId("7"), setOf(Narration, Audiobook), at(5), known)

        assertThat(asked.formats.map { it.format to it.expectedBytes })
            .containsExactly(Text to null, Audiobook to 412L, Narration to 98L)
    }

    @Test
    fun `there is work when a download may run, or does not say yet what it waits for`() {
        val queued = listOf(book("1", record(Text)))
        val waiting = listOf(book("1", record(Text, FormatState.Waiting(WaitReason.UnmeteredOnly, 0, null))))

        assertThat(hasWork(queued, OnWifi)).isTrue()
        assertThat(hasWork(queued, OnMobile)).isTrue()
        assertThat(hasWork(waiting, OnMobile)).isFalse()
        assertThat(hasWork(waiting, WithoutNetwork)).isTrue()
        assertThat(hasWork(waiting, OnWifi)).isTrue()
        assertThat(hasWork(listOf(book("1", record(Text, FormatState.Complete(1)))), OnWifi)).isFalse()
    }

    @Test
    fun `asking for the narration records the text too, once`() {
        val asked = asked(null, BookId("7"), setOf(Narration), at(5))

        assertThat(asked.bookId).isEqualTo(BookId("7"))
        assertThat(asked.downloadedAt).isEqualTo(at(5))
        assertThat(asked.formats).containsExactly(record(Text, askedAt = 5), record(Narration, askedAt = 5))

        assertThat(asked(asked, BookId("7"), setOf(Narration, Text), at(9))).isEqualTo(asked)
    }

    @Test
    fun `asking again leaves what is complete or on its way, and queues again what failed or was going`() {
        val before = book(
            "7",
            record(Text, FormatState.Complete(10), askedAt = 1),
            record(Audiobook, FormatState.Failed(DownloadFailure.Damaged, 3), askedAt = 1, anyNetwork = true),
            record(Narration, FormatState.Complete(4), askedAt = 1, removing = true),
        )

        val after = asked(before, BookId("7"), setOf(Text, Audiobook, Narration), at(9))

        assertThat(after.formats).containsExactly(
            record(Text, FormatState.Complete(10), askedAt = 1),
            record(Audiobook, askedAt = 9),
            record(Narration, FormatState.Complete(4), askedAt = 1),
        )
    }

    @Test
    fun `the queue shows the book being fetched, then the others in order, the failed ones among them`() {
        val books = listOf(
            book("3", record(Text, FormatState.Failed(DownloadFailure.NotAnEpub, 0), askedAt = 3)),
            book("2", record(Audiobook, askedAt = 2)),
            book(
                "1",
                record(Text, FormatState.Complete(50), askedAt = 1),
                record(Narration, FormatState.Downloading(20, 100), askedAt = 1),
                record(Audiobook, FormatState.Waiting(WaitReason.NoNetwork, 5, 30), askedAt = 1),
            ),
            book("4", record(Text, FormatState.Complete(9))),
        )

        val queue = queueState(books)

        assertThat(queue.active).isEqualTo(QueueEntry(BookId("1"), listOf(Audiobook, Narration), 25, 130, null))
        assertThat(queue.waiting).containsExactly(
            QueueEntry(BookId("2"), listOf(Audiobook), 0, null, null),
            QueueEntry(BookId("3"), listOf(Text), 0, null, DownloadFailure.NotAnEpub),
        ).inOrder()
        assertThat(queue.waitReason).isNull()
        assertThat(queue.leftBytes).isEqualTo(105)
    }

    @Test
    fun `a queue that waits says for what`() {
        val books = listOf(
            book("1", record(Text, FormatState.Waiting(WaitReason.UnmeteredOnly, 2, 10), askedAt = 1)),
            book("2", record(Text, FormatState.Waiting(WaitReason.UnmeteredOnly, 0, null), askedAt = 2)),
        )

        val queue = queueState(books)

        assertThat(queue.active).isNull()
        assertThat(queue.waiting.map { it.bookId }).containsExactly(BookId("1"), BookId("2")).inOrder()
        assertThat(queue.waitReason).isEqualTo(WaitReason.UnmeteredOnly)
        assertThat(queue.leftBytes).isEqualTo(8)
        assertThat(queueState(emptyList()).leftBytes).isNull()
    }

    @Test
    fun `what is being deleted is not shown, and a book with nothing else is not either`() {
        val books = listOf(
            book(
                "1",
                record(Text, FormatState.Complete(5)),
                record(Audiobook, FormatState.Complete(9), removing = true),
            ),
            book("2", record(Text, FormatState.Complete(5), removing = true)),
        )

        assertThat(books.visible()).containsExactly(book("1", record(Text, FormatState.Complete(5))))
        assertThat(queueState(books).waiting).isEmpty()
    }

    @Test
    fun `the updates of a book come after its downloads`() {
        val waiting = FormatState.Waiting(WaitReason.NoNetwork, 0, 30)
        val some = book("1", updating(Narration, FormatState.Queued), record(Audiobook), updating(Text, waiting))

        assertThat(steps(some)).containsExactly(
            Step(Audiobook, StepFiles.All),
            Step(Text, StepFiles.All, update = true),
            Step(Narration, StepFiles.All, update = true),
        ).inOrder()
    }

    @Test
    fun `an update not asked, failed, fetched whole or of a format being deleted has no step`() {
        val failed = FormatState.Failed(DownloadFailure.NotAnEpub, 0)
        val whole = FormatState.Complete(30)
        val none = book("1", updating(Text, null), updating(Audiobook, failed), updating(Narration, whole))
        val removing = book("2", updating(Text, FormatState.Queued).copy(removing = true))

        assertThat(steps(none)).isEmpty()
        assertThat(steps(removing)).isEmpty()
        assertThat(nextBook(listOf(none, removing), OnWifi)).isNull()
    }

    @Test
    fun `an update follows the rule of the network, and says what it waits for`() {
        val books = listOf(book("1", updating(Text, FormatState.Queued)))

        assertThat(nextBook(books, OnWifi)?.bookId).isEqualTo(BookId("1"))
        assertThat(nextBook(books, OnMobile)).isNull()
        assertThat(hasWork(books, OnMobile)).isTrue()
        val waiting = books.map { it.waitingFor(OnMobile) }
        assertThat(waiting.single().formats.single().update?.state)
            .isEqualTo(FormatState.Waiting(WaitReason.UnmeteredOnly, 0, 30))
        assertThat(waiting.single().formats.single().state).isEqualTo(FormatState.Complete(10))
        assertThat(hasWork(waiting, OnMobile)).isFalse()
    }

    @Test
    fun `the user's word for an update lets it use a metered network`() {
        val allowed = updating(Text, FormatState.Queued).let { it.copy(update = it.update?.copy(anyNetwork = true)) }

        assertThat(nextBook(listOf(book("1", allowed)), OnMobile)?.bookId).isEqualTo(BookId("1"))
    }

    @Test
    fun `the queue counts the updates asked as work, the failed ones among them`() {
        val failure = DownloadFailure.NoSpace(1)
        val books = listOf(
            book("4", updating(Text, FormatState.Complete(30), askedAt = 0)),
            book("3", updating(Text, null, askedAt = 0)),
            book(
                "2",
                updating(Audiobook, FormatState.Failed(failure, 0), askedAt = 2),
                record(Narration, askedAt = 2),
            ),
            book("1", updating(Text, FormatState.Downloading(5, 30), askedAt = 1)),
        )

        val queue = queueState(books)

        assertThat(queue.active).isEqualTo(QueueEntry(BookId("1"), listOf(Text), 5, 30, null, update = true))
        assertThat(queue.waiting)
            .containsExactly(QueueEntry(BookId("2"), listOf(Audiobook, Narration), 0, null, failure))
        assertThat(queue.leftBytes).isEqualTo(25)
    }
}
