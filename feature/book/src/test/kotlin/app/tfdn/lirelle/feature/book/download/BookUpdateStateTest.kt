package app.tfdn.lirelle.feature.book.download

import app.tfdn.lirelle.core.downloads.model.DownloadFailure
import app.tfdn.lirelle.core.downloads.model.DownloadFormat.Audiobook
import app.tfdn.lirelle.core.downloads.model.DownloadFormat.Narration
import app.tfdn.lirelle.core.downloads.model.DownloadFormat.Text
import app.tfdn.lirelle.core.downloads.model.FormatRecord
import app.tfdn.lirelle.core.downloads.model.FormatState
import app.tfdn.lirelle.core.downloads.model.PageCount
import app.tfdn.lirelle.core.downloads.model.WaitReason
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BookUpdateStateTest {

    private val fetching = FormatState.Downloading(receivedBytes = 324 * Mb, totalBytes = 1_080 * Mb)
    private val waitingForWifi =
        FormatState.Waiting(WaitReason.UnmeteredOnly, receivedBytes = 324 * Mb, totalBytes = 1_080 * Mb)
    private val failed = FormatState.Failed(DownloadFailure.NotAnEpub, receivedBytes = 0)

    private fun update(vararg formats: FormatRecord) =
        situation(offered = ThreeFormats, book = downloaded(*formats)).update()

    @Test
    fun `a book kept as the server has it has no update`() {
        assertThat(update(complete(Text))).isNull()
        assertThat(situation().update()).isNull()
    }

    @Test
    fun `an update that waits for the user says what it would fetch, of every format`() {
        val formats = arrayOf(updatable(Text, bytes = 3 * Mb), updatable(Narration, bytes = 1_077 * Mb))

        assertThat(update(*formats, complete(Audiobook)))
            .isEqualTo(UpdateState.Available(bytes = 1_080 * Mb))
    }

    @Test
    fun `an update being fetched says its bytes, and the tile stays downloaded`() {
        val situation = situation(book = downloaded(updatable(Audiobook, fetching)))

        assertThat(situation.tile()).isEqualTo(TileState.Downloaded)
        assertThat(situation.update())
            .isEqualTo(UpdateState.Updating(percent = 30, receivedBytes = 324 * Mb, totalBytes = 1_080 * Mb))
    }

    @Test
    fun `the update of an album says its pages, its percentage following them`() {
        val fetched = albumUpdated(FormatState.Downloading(receivedBytes = 8 * Mb, totalBytes = 9 * Mb), kept = 12)

        assertThat(update(fetched))
            .isEqualTo(UpdateState.Updating(percent = 25, 8 * Mb, 9 * Mb, PageCount(12, 48)))
    }

    @Test
    fun `the update of an album that waits says the pages it reached`() {
        fun waiting(reason: WaitReason) =
            albumUpdated(FormatState.Waiting(reason, receivedBytes = 8 * Mb, totalBytes = 9 * Mb), kept = 12)

        assertThat(update(waiting(WaitReason.UnmeteredOnly)))
            .isEqualTo(UpdateState.WaitingForWifi(percent = 25, leftBytes = Mb, PageCount(12, 48)))
        assertThat(update(waiting(WaitReason.NoNetwork)))
            .isEqualTo(UpdateState.Queued(percent = 25, noNetwork = true, leftBytes = Mb, PageCount(12, 48)))
    }

    @Test
    fun `the update of an album that waits beside another says the bytes left`() {
        val album = albumUpdated(FormatState.Waiting(WaitReason.NoNetwork, 8 * Mb, 9 * Mb), kept = 12)
        val audiobook = updatable(Audiobook, FormatState.Queued, bytes = 91 * Mb)

        // A quarter of the album's 9 MB, of 100 MB.
        assertThat(update(album, audiobook))
            .isEqualTo(UpdateState.Queued(percent = 2, noNetwork = true, leftBytes = 92 * Mb))
    }

    @Test
    fun `an update whose download does not say its size counts for what the check found`() {
        val state = FormatState.Downloading(receivedBytes = 270 * Mb, totalBytes = null)

        assertThat(update(updatable(Audiobook, state)))
            .isEqualTo(UpdateState.Updating(percent = 25, receivedBytes = 270 * Mb, totalBytes = 1_080 * Mb))
    }

    @Test
    fun `an update stopped by the network rule waits for Wi-Fi and says what is left`() {
        assertThat(update(updatable(Audiobook, waitingForWifi)))
            .isEqualTo(UpdateState.WaitingForWifi(percent = 30, leftBytes = 756 * Mb))
    }

    @Test
    fun `an update queued while the queue waits for Wi-Fi waits for Wi-Fi too`() {
        val book = downloaded(updatable(Audiobook, FormatState.Queued))
        val queue = EmptyQueue.copy(waitReason = WaitReason.UnmeteredOnly)

        assertThat(situation(book = book, queue = queue, network = Mobile).update())
            .isEqualTo(UpdateState.WaitingForWifi(percent = 0, leftBytes = 1_080 * Mb))
    }

    @Test
    fun `an update queued waits for its turn, or for a network`() {
        val book = downloaded(updatable(Audiobook, FormatState.Queued))

        assertThat(situation(book = book).update())
            .isEqualTo(UpdateState.Queued(percent = 0, noNetwork = false, leftBytes = 1_080 * Mb))
        assertThat(situation(book = book, network = NoNetwork).update())
            .isEqualTo(UpdateState.Queued(percent = 0, noNetwork = true, leftBytes = 1_080 * Mb))
    }

    @Test
    fun `an update that failed says why`() {
        assertThat(update(updatable(Text, failed))).isEqualTo(UpdateState.Failed(DownloadFailure.NotAnEpub))
    }

    @Test
    fun `an update fetched while the book is open is ready`() {
        assertThat(update(updatable(Text, FormatState.Complete(3 * Mb)))).isEqualTo(UpdateState.Ready)
    }

    @Test
    fun `what is fetched comes before what waits, then what failed, then what is available`() {
        val all = arrayOf(updatable(Text), updatable(Audiobook, failed), updatable(Narration, waitingForWifi))

        assertThat(update(*all)).isInstanceOf(UpdateState.WaitingForWifi::class.java)
        assertThat(update(updatable(Text, fetching), all[1], all[2])).isInstanceOf(UpdateState.Updating::class.java)
        assertThat(update(all[0], all[1])).isInstanceOf(UpdateState.Failed::class.java)
        // One that is ready has nothing to do: what can be started is offered first.
        assertThat(update(all[0], updatable(Audiobook, FormatState.Complete(Mb))))
            .isEqualTo(UpdateState.Available(bytes = 1_080 * Mb))
    }

    @Test
    fun `an ordinary download wins over every update`() {
        val pending = listOf(
            FormatState.Queued,
            FormatState.Downloading(receivedBytes = Mb, totalBytes = 412 * Mb),
            waitingForWifi,
            failed,
        )

        pending.forEach { state ->
            val situation = situation(book = downloaded(updatable(Text, fetching), record(Audiobook, state)))

            assertThat(situation.tile()).isNotEqualTo(TileState.Downloaded)
            assertThat(situation.update()).isNull()
        }
    }

    @Test
    fun `a format being deleted has no update, and an account that may not download is offered none`() {
        val removing = updatable(Text).copy(removing = true)

        assertThat(update(removing, complete(Audiobook))).isNull()
        assertThat(situation(book = downloaded(updatable(Text)), canDownload = false).update()).isNull()
    }

    @Test
    fun `a book to delete when it closes is offered no update`() {
        val book = downloaded(updatable(Text), complete(Audiobook)).copy(deleteAtClose = true)

        assertThat(situation(book = book).update()).isNull()
    }
}
