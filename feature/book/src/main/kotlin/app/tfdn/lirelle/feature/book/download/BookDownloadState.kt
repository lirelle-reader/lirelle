package app.tfdn.lirelle.feature.book.download

import app.tfdn.lirelle.core.downloads.QueueState
import app.tfdn.lirelle.core.downloads.engine.OfferedFormat
import app.tfdn.lirelle.core.downloads.engine.textName
import app.tfdn.lirelle.core.downloads.model.DownloadFailure
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.DownloadedBook
import app.tfdn.lirelle.core.downloads.model.FormatRecord
import app.tfdn.lirelle.core.downloads.model.FormatState
import app.tfdn.lirelle.core.downloads.model.PageCount
import app.tfdn.lirelle.core.downloads.model.TextName
import app.tfdn.lirelle.core.downloads.model.WaitReason
import app.tfdn.lirelle.core.library.net.NetworkState

private const val FullPercent = 100

/**
 * What the download tile of the book page says. A percentage is of everything asked for the book, and null
 * while a size is not known: none is shown rather than a wrong one.
 */
sealed interface TileState {
    data object NotDownloaded : TileState

    /**
     * @property format the one being fetched, with what it received and its size.
     * @property textName what the text is named by, when it is the one being fetched.
     * @property pages the pages it has and those it is to have, when it is made of pages: it then says how far
     *   it is in pages, its bytes being counted against an estimate.
     */
    data class Downloading(
        val percent: Int?,
        val format: DownloadFormat,
        val receivedBytes: Long,
        val totalBytes: Long?,
        val textName: TextName = TextName.Text,
        val pages: PageCount? = null,
    ) : TileState

    /**
     * Stopped by the network rule: the user may let it go on.
     *
     * @property leftBytes what is left to fetch of the sizes known; null when none is.
     * @property pages the pages reached, when all that waits is made of pages: said in place of the bytes left.
     */
    data class WaitingForWifi(val percent: Int?, val leftBytes: Long?, val pages: PageCount? = null) : TileState

    /**
     * Waiting for its turn, or, with [noNetwork], for any network.
     *
     * @property leftBytes what is left to fetch of the sizes known; null when none is.
     * @property pages the pages reached, when all that waits is made of pages: said in place of the bytes left.
     */
    data class Queued(
        val percent: Int?,
        val noNetwork: Boolean,
        val leftBytes: Long? = null,
        val pages: PageCount? = null,
    ) : TileState

    data class Failed(val reason: DownloadFailure) : TileState

    data object Downloaded : TileState

    /** The account may not download, and the device keeps nothing of the book. */
    data object NotAllowed : TileState
}

/**
 * What the book page knows of the downloads of its book.
 *
 * @property offered the formats the book has.
 * @property book what the device keeps or is fetching of it.
 * @property canDownload whether the server lets the account download.
 */
data class DownloadSituation(
    val offered: List<OfferedFormat> = emptyList(),
    val book: DownloadedBook? = null,
    val queue: QueueState = QueueState(active = null, waiting = emptyList(), waitReason = null, leftBytes = null),
    val network: NetworkState = NetworkState(online = true, unmetered = true),
    val canDownload: Boolean = true,
) {
    /** The formats asked or kept, without those being deleted. */
    val records: List<FormatRecord> get() = book?.formats.orEmpty().filterNot { it.removing }

    fun record(format: DownloadFormat): FormatRecord? = records.firstOrNull { it.format == format }

    /**
     * What the book's text is named by: as its record says, which knows it from its asking or its plan, else
     * as the book offers it.
     */
    val textName: TextName
        get() = record(DownloadFormat.Text)?.textName?.takeIf { it != TextName.Text } ?: offered.textName()
}

internal val FormatRecord.isComplete: Boolean get() = state is FormatState.Complete

/** What the format has on the device. */
internal val FormatRecord.receivedBytes: Long
    get() = when (val state = state) {
        FormatState.Queued -> 0
        is FormatState.Downloading -> state.receivedBytes
        is FormatState.Waiting -> state.receivedBytes
        is FormatState.Failed -> state.receivedBytes
        is FormatState.Complete -> state.bytes
    }

/** The size of the format once whole: what its download says, else what was planned, else what the page announced. */
internal fun DownloadSituation.expectedBytes(record: FormatRecord): Long? {
    val said = when (val state = record.state) {
        is FormatState.Complete -> state.bytes
        is FormatState.Downloading -> state.totalBytes
        is FormatState.Waiting -> state.totalBytes
        else -> null
    }
    val planned = record.files.map { it.expectedBytes }.takeIf { it.isNotEmpty() && null !in it }?.sumOf { it ?: 0 }
    return said ?: planned ?: offered.firstOrNull { it.format == record.format }?.estimatedBytes
}

/**
 * What is left to fetch of the book, of the formats whose size is known: a format without one, such as the text
 * of a read-along file, does not hide what the others say. Null when no size is known.
 */
private fun DownloadSituation.leftBytes(): Long? = records
    .mapNotNull { record -> expectedBytes(record)?.let { (it - record.receivedBytes).coerceAtLeast(0) } }
    .takeIf { it.isNotEmpty() }
    ?.sum()

/**
 * How far everything asked for the book is, out of 100. A format made of pages counts for its pages, not for
 * its bytes, which are counted against an estimate: alone, the percentage is that of its pages, whether its
 * size is known or not.
 */
private fun DownloadSituation.percent(): Int? {
    records.singleOrNull()?.pagesOnTheWay?.let { return it.kept * FullPercent / it.total }
    val total = records.sumOf { expectedBytes(it) ?: return null }
    if (total <= 0) return null
    val done = records.sumOf { record ->
        val pages = record.pagesOnTheWay
        if (pages == null) record.receivedBytes else (expectedBytes(record) ?: 0) * pages.kept / pages.total
    }
    return (done * FullPercent / total).toInt().coerceIn(0, FullPercent)
}

/** The pages of a format made of pages that is not whole yet; null for any other. */
internal val FormatRecord.pagesOnTheWay: PageCount? get() = pages.takeUnless { isComplete }

/**
 * The tile: what is being fetched comes before what waits, which comes before what failed. An account that may
 * not download sees what it still keeps, to remove it, and nothing of what was only asked.
 */
fun DownloadSituation.tile(): TileState {
    val pending = records.filterNot { it.isComplete }
    val fetched = pending.firstOrNull { it.state is FormatState.Downloading }
    val state = fetched?.state as? FormatState.Downloading
    val moving = pending.filterNot { it.state is FormatState.Failed }
    return when {
        !canDownload -> if (records.any { it.isComplete }) TileState.Downloaded else TileState.NotAllowed
        pending.isEmpty() -> if (records.isEmpty()) TileState.NotDownloaded else TileState.Downloaded
        fetched != null && state != null -> TileState.Downloading(
            percent = percent(),
            format = fetched.format,
            receivedBytes = state.receivedBytes,
            totalBytes = state.totalBytes,
            textName = if (fetched.format == DownloadFormat.Text) textName else TextName.Text,
            pages = fetched.pages,
        )
        moving.isEmpty() -> TileState.Failed((pending.first().state as FormatState.Failed).reason)
        else -> waiting(moving)
    }
}

/**
 * What [moving], queued or stopped, waits for: the format's own reason, else the queue's. It says the pages
 * reached when all that waits is made of pages.
 */
private fun DownloadSituation.waiting(moving: List<FormatRecord>): TileState {
    val reason = moving.firstNotNullOfOrNull { (it.state as? FormatState.Waiting)?.reason } ?: queue.waitReason
    val pages = moving.singleOrNull()?.pages
    return when {
        reason == WaitReason.NoNetwork || !network.online ->
            TileState.Queued(percent(), noNetwork = true, leftBytes(), pages)
        reason == WaitReason.UnmeteredOnly -> TileState.WaitingForWifi(percent(), leftBytes(), pages)
        else -> TileState.Queued(percent(), noNetwork = false, leftBytes(), pages)
    }
}
