package app.tfdn.lirelle.feature.book.download

import app.tfdn.lirelle.core.downloads.model.DownloadFailure
import app.tfdn.lirelle.core.downloads.model.DownloadFormat.Audiobook
import app.tfdn.lirelle.core.downloads.model.DownloadFormat.Text
import app.tfdn.lirelle.core.downloads.model.FormatState
import app.tfdn.lirelle.core.downloads.model.PageCount
import app.tfdn.lirelle.core.downloads.model.TextName
import app.tfdn.lirelle.core.downloads.model.WaitReason
import app.tfdn.lirelle.core.network.ApiError
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BookDownloadStateTest {

    private val wifiQueue = EmptyQueue.copy(waitReason = WaitReason.UnmeteredOnly)

    @Test
    fun `nothing asked is not downloaded`() {
        assertThat(situation().tile()).isEqualTo(TileState.NotDownloaded)
    }

    @Test
    fun `a format being deleted is no longer there`() {
        val book = downloaded(record(Text, FormatState.Complete(3 * Mb), removing = true))

        assertThat(situation(book = book).tile()).isEqualTo(TileState.NotDownloaded)
    }

    @Test
    fun `a format being fetched says its own bytes, and the percentage of everything asked`() {
        val book = downloaded(
            complete(Text, 3 * Mb),
            record(Audiobook, FormatState.Downloading(receivedBytes = 124 * Mb, totalBytes = 412 * Mb)),
        )

        // 127 of 415.
        assertThat(situation(book = book).tile())
            .isEqualTo(TileState.Downloading(percent = 30, Audiobook, receivedBytes = 124 * Mb, totalBytes = 412 * Mb))
    }

    @Test
    fun `a format still queued counts for the size the book page announced`() {
        val book = downloaded(
            record(Text, FormatState.Downloading(receivedBytes = Mb, totalBytes = 3 * Mb)),
            record(Audiobook, FormatState.Queued),
        )

        assertThat(situation(book = book).tile())
            .isEqualTo(TileState.Downloading(percent = 0, Text, receivedBytes = Mb, totalBytes = 3 * Mb))
    }

    @Test
    fun `a size that is not known gives no percentage rather than a wrong one`() {
        val book = downloaded(record(Text, FormatState.Downloading(receivedBytes = Mb, totalBytes = null)))

        assertThat(situation(offered = ThreeFormats, book = book).tile())
            .isEqualTo(TileState.Downloading(percent = null, Text, receivedBytes = Mb, totalBytes = null))
    }

    @Test
    fun `an album being fetched says its pages and its name, its percentage following the pages`() {
        val book = downloaded(album(FormatState.Downloading(receivedBytes = 8 * Mb, totalBytes = 9 * Mb), kept = 12))

        // 12 pages of 48, whatever the bytes say of the estimate.
        assertThat(situation(offered = Comic, book = book).tile()).isEqualTo(
            TileState.Downloading(25, Text, 8 * Mb, 9 * Mb, TextName.Cbz, PageCount(12, 48)),
        )
    }

    @Test
    fun `an album whose size is not known still has the percentage of its pages`() {
        val fetched = album(FormatState.Downloading(receivedBytes = 8 * Mb, totalBytes = null), kept = 36)
            .copy(expectedBytes = null)

        val tile = situation(offered = emptyList(), book = downloaded(fetched)).tile()

        assertThat(tile).isEqualTo(TileState.Downloading(75, Text, 8 * Mb, null, TextName.Cbz, PageCount(36, 48)))
    }

    @Test
    fun `an album that waits says the pages it reached`() {
        fun tile(reason: WaitReason) = situation(
            offered = Comic,
            book = downloaded(album(FormatState.Waiting(reason, receivedBytes = 8 * Mb, totalBytes = 9 * Mb), 12)),
            queue = EmptyQueue.copy(waitReason = reason),
        ).tile()

        assertThat(tile(WaitReason.UnmeteredOnly))
            .isEqualTo(TileState.WaitingForWifi(percent = 25, leftBytes = Mb, PageCount(12, 48)))
        assertThat(tile(WaitReason.NoNetwork))
            .isEqualTo(TileState.Queued(percent = 25, noNetwork = true, leftBytes = Mb, PageCount(12, 48)))
    }

    @Test
    fun `an album asked and not planned yet waits as any download, with the size announced`() {
        val asked = record(Text, FormatState.Waiting(WaitReason.UnmeteredOnly, 0, 9 * Mb)).copy(textName = TextName.Cbz)

        assertThat(situation(offered = Comic, book = downloaded(asked), queue = wifiQueue).tile())
            .isEqualTo(TileState.WaitingForWifi(percent = 0, leftBytes = 9 * Mb))
    }

    @Test
    fun `an album that waits beside an audiobook says the bytes left, its pages counted in the percentage`() {
        val noNetwork = WaitReason.NoNetwork
        val book = downloaded(
            album(FormatState.Waiting(noNetwork, receivedBytes = 8 * Mb, totalBytes = 9 * Mb), kept = 12),
            record(Audiobook, FormatState.Waiting(noNetwork, receivedBytes = 0, totalBytes = 91 * Mb)),
        )

        // A quarter of the album's 9 MB, of 100 MB.
        assertThat(situation(book = book).tile())
            .isEqualTo(TileState.Queued(percent = 2, noNetwork = true, leftBytes = 92 * Mb))
    }

    @Test
    fun `a PDF being fetched is named, and counted in bytes as any file`() {
        val fetched = record(Text, FormatState.Downloading(receivedBytes = 3 * Mb, totalBytes = 9 * Mb))
            .copy(textName = TextName.Pdf)

        assertThat(situation(offered = PdfAndAudiobook, book = downloaded(fetched)).tile())
            .isEqualTo(TileState.Downloading(33, Text, 3 * Mb, 9 * Mb, TextName.Pdf))
    }

    @Test
    fun `the text of the book is named as its record says, else as it is offered`() {
        val planned = record(Text, FormatState.Complete(Mb)).copy(textName = TextName.Cbr)

        assertThat(situation(offered = Comic).textName).isEqualTo(TextName.Cbz)
        assertThat(situation(offered = emptyList(), book = downloaded(planned)).textName).isEqualTo(TextName.Cbr)
        // Asked before the name was known from the asking: the offer says it.
        assertThat(situation(offered = Comic, book = downloaded(complete(Text))).textName).isEqualTo(TextName.Cbz)
        assertThat(situation().textName).isEqualTo(TextName.Text)
    }

    @Test
    fun `stopped by the network rule, it waits for Wi-Fi and says what is left`() {
        val waiting = FormatState.Waiting(WaitReason.UnmeteredOnly, receivedBytes = 124 * Mb, totalBytes = 412 * Mb)
        val book = downloaded(record(Audiobook, waiting))

        assertThat(situation(book = book, network = Mobile).tile())
            .isEqualTo(TileState.WaitingForWifi(percent = 30, leftBytes = 288 * Mb))
    }

    @Test
    fun `queued while the queue waits for Wi-Fi, it waits for Wi-Fi too`() {
        val book = downloaded(record(Audiobook, FormatState.Queued))
        val queue = EmptyQueue.copy(waitReason = WaitReason.UnmeteredOnly)

        assertThat(situation(book = book, queue = queue, network = Mobile).tile())
            .isEqualTo(TileState.WaitingForWifi(percent = 0, leftBytes = 412 * Mb))
    }

    @Test
    fun `stopped for lack of any network, it does not offer to download anyway`() {
        val waiting = FormatState.Waiting(WaitReason.NoNetwork, receivedBytes = 124 * Mb, totalBytes = 412 * Mb)
        val book = downloaded(record(Audiobook, waiting))

        assertThat(situation(book = book, network = NoNetwork).tile())
            .isEqualTo(TileState.Queued(percent = 30, noNetwork = true, leftBytes = 288 * Mb))
    }

    @Test
    fun `what is left is that of the sizes known, when one is not`() {
        val waiting = FormatState.Waiting(WaitReason.UnmeteredOnly, receivedBytes = 124 * Mb, totalBytes = 412 * Mb)
        val book = downloaded(record(Text, FormatState.Queued), record(Audiobook, waiting))

        assertThat(situation(offered = ThreeFormats.take(1), book = book, network = Mobile).tile())
            .isEqualTo(TileState.WaitingForWifi(percent = null, leftBytes = 288 * Mb))
    }

    @Test
    fun `nothing is said to be left while no size is known`() {
        val book = downloaded(record(Text, FormatState.Queued))

        assertThat(situation(offered = ThreeFormats, book = book, network = Mobile, queue = wifiQueue).tile())
            .isEqualTo(TileState.WaitingForWifi(percent = null, leftBytes = null))
        assertThat(situation(offered = ThreeFormats, book = book, network = NoNetwork).tile())
            .isEqualTo(TileState.Queued(percent = null, noNetwork = true, leftBytes = null))
    }

    @Test
    fun `a device without a network waits for it whatever the queue last said`() {
        val waiting = FormatState.Waiting(WaitReason.UnmeteredOnly, receivedBytes = 0, totalBytes = 412 * Mb)
        val book = downloaded(record(Audiobook, waiting))

        assertThat(situation(book = book, network = NoNetwork).tile())
            .isEqualTo(TileState.Queued(percent = 0, noNetwork = true, leftBytes = 412 * Mb))
    }

    @Test
    fun `behind another book, it is in the queue`() {
        val book = downloaded(record(Audiobook, FormatState.Queued))

        assertThat(situation(book = book).tile())
            .isEqualTo(TileState.Queued(percent = 0, noNetwork = false, leftBytes = 412 * Mb))
    }

    @Test
    fun `a failure is said with its reason, whichever it is`() {
        listOf(
            DownloadFailure.NoSpace(missingBytes = 200 * Mb),
            DownloadFailure.Server(ApiError.ServerFailed),
            DownloadFailure.NotAnEpub,
            DownloadFailure.Damaged,
            DownloadFailure.NotAllowed,
        ).forEach { reason ->
            val book = downloaded(complete(Text), record(Audiobook, FormatState.Failed(reason, receivedBytes = 0)))

            assertThat(situation(book = book).tile()).isEqualTo(TileState.Failed(reason))
        }
    }

    @Test
    fun `a failure waits its turn behind what is still being fetched`() {
        val book = downloaded(
            record(Text, FormatState.Failed(DownloadFailure.NotAnEpub, receivedBytes = 0)),
            record(Audiobook, FormatState.Downloading(receivedBytes = 0, totalBytes = 412 * Mb)),
        )

        assertThat(situation(book = book).tile()).isInstanceOf(TileState.Downloading::class.java)
    }

    @Test
    fun `everything asked and received is downloaded`() {
        val book = downloaded(complete(Text), complete(Audiobook, 412 * Mb))

        assertThat(situation(book = book).tile()).isEqualTo(TileState.Downloaded)
    }

    @Test
    fun `an account that may not download is told so`() {
        assertThat(situation(canDownload = false).tile()).isEqualTo(TileState.NotAllowed)
    }

    @Test
    fun `an account that may not download keeps what it has, to remove it`() {
        val book = downloaded(complete(Text))

        assertThat(situation(book = book, canDownload = false).tile()).isEqualTo(TileState.Downloaded)
    }

    @Test
    fun `what was only queued is nothing for an account that may not download`() {
        val book = downloaded(record(Audiobook, FormatState.Queued))

        assertThat(situation(book = book, canDownload = false).tile()).isEqualTo(TileState.NotAllowed)
    }
}
