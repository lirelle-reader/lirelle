package app.tfdn.lirelle.feature.book.download

import app.tfdn.lirelle.core.downloads.model.DownloadFailure
import app.tfdn.lirelle.core.downloads.model.FormatRecord
import app.tfdn.lirelle.core.downloads.model.FormatState
import app.tfdn.lirelle.core.downloads.model.FormatUpdate
import app.tfdn.lirelle.core.downloads.model.PageCount
import app.tfdn.lirelle.core.downloads.model.WaitReason

private const val FullPercent = 100

/**
 * Where the update of a downloaded book is: what the line under the tiles says while the tile stays
 * "Downloaded", the kept copy opening all along. A percentage is of every update asked for the book.
 */
sealed interface UpdateState {
    /** The server has a newer version, which waits for the user. @property bytes what it would fetch. */
    data class Available(val bytes: Long) : UpdateState

    /**
     * @property receivedBytes of the format being fetched, with [totalBytes] what its update fetches.
     * @property pages the pages fetched and those to fetch, when the update is made of pages: said in place
     *   of its bytes.
     */
    data class Updating(
        val percent: Int,
        val receivedBytes: Long,
        val totalBytes: Long,
        val pages: PageCount? = null,
    ) : UpdateState

    /**
     * Stopped by the network rule: the user may let it go on.
     *
     * @property pages the pages reached, when all that waits is made of pages: said in place of the bytes left.
     */
    data class WaitingForWifi(val percent: Int, val leftBytes: Long, val pages: PageCount? = null) : UpdateState

    /**
     * Waiting for its turn, or, with [noNetwork], for any network.
     *
     * @property pages the pages reached, when all that waits is made of pages: said in place of the bytes left.
     */
    data class Queued(
        val percent: Int,
        val noNetwork: Boolean,
        val leftBytes: Long,
        val pages: PageCount? = null,
    ) : UpdateState

    data class Failed(val reason: DownloadFailure) : UpdateState

    /** Fetched while the book is open in the reader or the player: applied when it closes. */
    data object Ready : UpdateState
}

/** How far the update is, out of 100, for the states that have a bar on the tile. */
internal val UpdateState.barPercent: Int?
    get() = when (this) {
        is UpdateState.Updating -> percent
        is UpdateState.WaitingForWifi -> percent
        is UpdateState.Queued -> percent
        else -> null
    }

/** An update of a kept format, with where it is: [state] is null while it waits for the user. */
private class Pending(val update: FormatUpdate, val state: FormatState?) {
    val receivedBytes: Long
        get() = when (state) {
            is FormatState.Downloading -> state.receivedBytes
            is FormatState.Waiting -> state.receivedBytes
            is FormatState.Failed -> state.receivedBytes
            is FormatState.Complete -> state.bytes
            FormatState.Queued, null -> 0
        }

    /** What it fetches: what its download says, else what the check found. */
    val totalBytes: Long
        get() = when (state) {
            is FormatState.Downloading -> state.totalBytes
            is FormatState.Waiting -> state.totalBytes
            is FormatState.Complete -> state.bytes
            else -> null
        } ?: update.bytes

    val leftBytes: Long get() = (totalBytes - receivedBytes).coerceAtLeast(0)

    /** What it has of [totalBytes]: an update made of pages counts for its pages, not for its bytes. */
    val doneBytes: Long get() = update.pages?.let { totalBytes * it.kept / it.total } ?: receivedBytes
}

private fun FormatRecord.pending(): Pending? = update?.takeIf { isComplete }?.let { Pending(it, it.state) }

/**
 * The update of the book, null when it has none. An ordinary download wins over it: nothing is said of an
 * update while a format of the book is still asked, nothing of a book to delete when it closes, which is not
 * updated, and nothing to an account that may not download. Else what
 * is being fetched comes before what waits, which comes before what failed, then what the user can start, and
 * last what only waits for the book to close.
 */
fun DownloadSituation.update(): UpdateState? {
    if (!canDownload || book?.deleteAtClose == true || tile() != TileState.Downloaded) return null
    val updates = records.mapNotNull { it.pending() }
    val asked = updates.filter { it.state != null && it.state !is FormatState.Failed }
    val moving = asked.filterNot { it.state is FormatState.Complete }
    val fetched = moving.firstOrNull { it.state is FormatState.Downloading }
    val failed = updates.firstNotNullOfOrNull { it.state as? FormatState.Failed }
    val available = updates.filter { it.state == null }
    return when {
        fetched != null ->
            UpdateState.Updating(percent(asked), fetched.receivedBytes, fetched.totalBytes, fetched.update.pages)
        moving.isNotEmpty() -> waiting(moving, percent(asked))
        failed != null -> UpdateState.Failed(failed.reason)
        available.isNotEmpty() -> UpdateState.Available(available.sumOf { it.update.bytes })
        asked.isNotEmpty() -> UpdateState.Ready
        else -> null
    }
}

/** How far the updates asked are, out of 100: one made of pages, alone, is as far as its pages. */
private fun percent(asked: List<Pending>): Int {
    asked.singleOrNull()?.update?.pages?.let { return it.kept * FullPercent / it.total }
    val total = asked.sumOf { it.totalBytes }
    if (total <= 0) return 0
    return (asked.sumOf { it.doneBytes } * FullPercent / total).toInt().coerceIn(0, FullPercent)
}

/**
 * What [moving], queued or stopped, waits for: the update's own reason, else the queue's. It says the pages
 * reached when all that waits is made of pages.
 */
private fun DownloadSituation.waiting(moving: List<Pending>, percent: Int): UpdateState {
    val reason = moving.firstNotNullOfOrNull { (it.state as? FormatState.Waiting)?.reason } ?: queue.waitReason
    val left = moving.sumOf { it.leftBytes }
    val pages = moving.singleOrNull()?.update?.pages
    return when {
        reason == WaitReason.NoNetwork || !network.online ->
            UpdateState.Queued(percent, noNetwork = true, left, pages)
        reason == WaitReason.UnmeteredOnly -> UpdateState.WaitingForWifi(percent, left, pages)
        else -> UpdateState.Queued(percent, noNetwork = false, left, pages)
    }
}
