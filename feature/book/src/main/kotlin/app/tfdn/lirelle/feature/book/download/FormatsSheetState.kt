package app.tfdn.lirelle.feature.book.download

import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.FormatRecord
import app.tfdn.lirelle.core.downloads.model.FormatState
import app.tfdn.lirelle.core.downloads.model.TextName

private const val FullPercent = 100

/**
 * One format of the formats sheet.
 *
 * @property bytes its size on the device once downloaded, the size the book announces before; null when unknown.
 * @property locked true for the text while the narration, which needs it, is checked.
 * @property downloaded the device has it whole.
 * @property chapterCount of the audiobook, when the server says.
 * @property inNarration true for a text that comes inside the narration's file.
 * @property fetching asked and not whole yet.
 * @property estimated [bytes] is about what the format will take, not a size the server gave for it.
 * @property progress how much of it is received, out of 100; null when it is not being fetched or its size
 *   is not known.
 * @property updateAvailable the device has it whole and the server has a newer version, which waits for the
 *   user: it is said, and started from the line under the tiles, not from the sheet.
 * @property textName what the text is named by, for the text's row: "CBZ", "PDF".
 */
data class SheetRow(
    val format: DownloadFormat,
    val bytes: Long?,
    val checked: Boolean,
    val locked: Boolean,
    val downloaded: Boolean,
    val chapterCount: Int?,
    val inNarration: Boolean = false,
    val fetching: Boolean = false,
    val progress: Int? = null,
    val estimated: Boolean = false,
    val updateAvailable: Boolean = false,
    val textName: TextName = TextName.Text,
)

/**
 * What confirming the sheet does.
 *
 * @property downloadBytes the sizes known of what is to download; null when none is.
 * @property deleteBytes the space freed.
 */
data class SheetPlan(
    val toDownload: Set<DownloadFormat> = emptySet(),
    val downloadBytes: Long? = null,
    val toDelete: Set<DownloadFormat> = emptySet(),
    val deleteBytes: Long = 0,
) {
    val isEmpty: Boolean get() = toDownload.isEmpty() && toDelete.isEmpty()
}

/** The formats asked or kept: what the sheet shows checked when it opens. */
fun DownloadSituation.kept(): Set<DownloadFormat> = records.mapTo(mutableSetOf()) { it.format }

/**
 * The rows for the formats [chosen]: those the book offers and those the device keeps, in the order of
 * [DownloadFormat]. An account that may not download only sees what it keeps.
 */
fun DownloadSituation.sheetRows(chosen: Set<DownloadFormat>): List<SheetRow> {
    val narrationFile = offered.firstOrNull { it.format == DownloadFormat.Narration }?.fileId
    return DownloadFormat.entries.mapNotNull { format ->
        val offer = offered.firstOrNull { it.format == format }
        val record = record(format)
        if (record == null && (offer == null || !canDownload)) return@mapNotNull null
        val state = record?.state
        val size = record?.let { expectedBytes(it) }
        SheetRow(
            format = format,
            bytes = (state as? FormatState.Complete)?.bytes ?: offer?.estimatedBytes ?: size,
            checked = format in chosen,
            locked = format == DownloadFormat.Text && DownloadFormat.Narration in chosen,
            downloaded = state is FormatState.Complete,
            chapterCount = offer?.chapterCount,
            inNarration = format == DownloadFormat.Text && offer?.fileId != null && offer.fileId == narrationFile,
            fetching = record != null && !record.isComplete,
            progress = record?.takeUnless { it.isComplete }?.let { progressOf(it, size) },
            estimated = state !is FormatState.Complete && offer?.estimated == true,
            updateAvailable = record?.awaitsUpdate == true,
            textName = if (format == DownloadFormat.Text) textName else TextName.Text,
        )
    }
}

/** The device has it whole, and a newer version of it waits for the user. */
private val FormatRecord.awaitsUpdate: Boolean get() = isComplete && update?.let { it.state == null } == true

/** How far [record] is, out of 100, of its [size]: by its pages when it is made of pages. */
private fun progressOf(record: FormatRecord, size: Long?): Int? =
    record.pages?.let { it.kept * FullPercent / it.total } ?: percentOf(record.receivedBytes, size)

private fun percentOf(received: Long, total: Long?): Int? =
    total?.takeIf { it > 0 }?.let { (received * FullPercent / it).toInt().coerceIn(0, FullPercent) }

/** What going from what is kept to [chosen] takes: nothing is downloaded for an account that may not. */
fun DownloadSituation.plan(chosen: Set<DownloadFormat>): SheetPlan {
    val kept = kept()
    val toDownload = if (canDownload) chosen - kept else emptySet()
    val toDelete = kept - chosen
    val sizes = offered.filter { it.format in toDownload }.mapNotNull { it.estimatedBytes }
    return SheetPlan(
        toDownload = toDownload,
        downloadBytes = sizes.takeIf { it.isNotEmpty() }?.sum(),
        toDelete = toDelete,
        deleteBytes = records.filter { it.format in toDelete }.sumOf { it.receivedBytes },
    )
}
