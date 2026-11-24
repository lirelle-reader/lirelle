package app.tfdn.lirelle.feature.account

import android.content.Context
import android.content.res.Resources
import app.tfdn.lirelle.core.ui.fileSize

/**
 * What removing a profile takes from the device, said before it is done.
 */
sealed interface RemovalCost {
    /**
     * Of the current profile, which is open and can be counted.
     *
     * @property books the downloaded books, which the removal deletes from the device.
     * @property bytes what they hold there.
     * @property lost what has not reached the server and cannot before the removal: empty when it can.
     */
    data class Counted(val books: Int = 0, val bytes: Long = 0, val lost: PendingCounts = PendingCounts()) :
        RemovalCost

    /** Of a profile that sleeps: it is not opened to be counted, so the same is said without numbers. */
    data object NotCounted : RemovalCost
}

/**
 * The lines of the removal's confirmation under its opening sentence.
 *
 * @property kept what goes with the profile: its downloads, counted, or for a profile that sleeps its
 *   downloads and whatever was not sent, uncounted. Null when the current profile has no download.
 * @property atRisk what would be lost for good, counted; null when nothing would, or nothing was counted.
 */
internal data class RemovalLines(val kept: String?, val atRisk: String?)

/** The lines for [cost], sizes written as the platform writes them. */
internal fun removalLines(context: Context, cost: RemovalCost): RemovalLines = when (cost) {
    is RemovalCost.Counted -> RemovalLines(
        kept = if (cost.books > 0) {
            downloadsSentence(context.resources, cost.books, context.fileSize(cost.bytes))
        } else {
            null
        },
        atRisk = pendingWarning(context.resources, cost.lost),
    )
    RemovalCost.NotCounted -> RemovalLines(context.getString(R.string.profiles_remove_sleeping), atRisk = null)
}

/** "5 downloaded books (3.4 GB) will be deleted from this device."; [size] as the platform writes it. */
internal fun downloadsSentence(resources: Resources, books: Int, size: String): String =
    resources.getQuantityString(R.plurals.profiles_remove_downloads, books, books, size)

/**
 * "2 reading positions and 1 bookmark have not been sent to the server yet: they will be lost.", from the
 * kinds that have something waiting, always in the same order. Null when nothing waits.
 */
internal fun pendingWarning(resources: Resources, counts: PendingCounts): String? {
    val kinds = listOf(
        R.plurals.account_pending_positions to counts.positions,
        R.plurals.account_pending_sessions to counts.sessions,
        R.plurals.account_pending_bookmarks to counts.bookmarks,
        R.plurals.account_pending_highlights to counts.highlights,
    )
        .filter { (_, count) -> count > 0 }
        .map { (plural, count) -> resources.getQuantityString(plural, count, count) }
    if (kinds.isEmpty()) return null
    val list = if (kinds.size == 1) {
        kinds.single()
    } else {
        val first = kinds.dropLast(1)
            .reduce { all, kind -> resources.getString(R.string.account_pending_join, all, kind) }
        resources.getString(R.string.account_pending_join_last, first, kinds.last())
    }
    return resources.getQuantityString(R.plurals.profiles_remove_pending, counts.total, list)
}
