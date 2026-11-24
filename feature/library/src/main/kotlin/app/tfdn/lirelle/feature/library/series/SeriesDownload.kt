package app.tfdn.lirelle.feature.library.series

import app.tfdn.lirelle.core.downloads.engine.OfferedFormat
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.TextName
import app.tfdn.lirelle.core.downloads.model.toggled
import app.tfdn.lirelle.core.ids.BookId

/**
 * One book of the series as the sheet needs it.
 *
 * @property finished the read state "read", as the heading of the series counts it.
 * @property kept the formats the device keeps of it or is fetching.
 */
data class SeriesBookOffer(
    val bookId: BookId,
    val finished: Boolean,
    val offered: List<OfferedFormat>,
    val kept: Set<DownloadFormat>,
)

/**
 * One format of the sheet of a series.
 *
 * @property books the candidates that offer it and do not keep it.
 * @property of the candidates.
 * @property bytes what those [books] take, of the sizes known.
 * @property estimated a size of the sum is about what its book will take, or is not known.
 * @property locked true for the text while the narration, which needs it, is checked.
 * @property textName what the text's row is named by: the name the candidates' texts share, "CBZ" for a
 *   series of such comics, and the text's own when they differ, the row being for them all.
 */
data class SeriesFormatRow(
    val format: DownloadFormat,
    val books: Int,
    val of: Int,
    val bytes: Long,
    val estimated: Boolean,
    val checked: Boolean,
    val locked: Boolean = false,
    val textName: TextName = TextName.Text,
)

/**
 * What the sheet of a series shows and what confirming it does.
 *
 * @property finishedSkipped the finished books left out.
 * @property books those that will receive at least one format.
 * @property bytes what they take, each file counted once.
 * @property asks what is asked of the queue, book by book in the order of the series.
 */
data class SeriesPlan(
    val rows: List<SeriesFormatRow>,
    val finishedSkipped: Int,
    val books: Int,
    val bytes: Long,
    val asks: List<Pair<BookId, Set<DownloadFormat>>>,
)

/**
 * The plan for the formats [checked]. A candidate is a book that is not finished, or any book when
 * [skipFinished] is false. There is a row for each format a candidate offers; the narration, when it has a row,
 * checks the text with it.
 */
fun seriesPlan(offers: List<SeriesBookOffer>, checked: Set<DownloadFormat>, skipFinished: Boolean): SeriesPlan {
    val candidates = offers.filterNot { skipFinished && it.finished }
    val narration = DownloadFormat.Narration in checked && candidates.any { it.offer(DownloadFormat.Narration) != null }
    val chosen = if (narration) checked + DownloadFormat.Text else checked
    val asked = candidates
        .map { book -> book.bookId to book.offered.filter { it.format in chosen && it.format !in book.kept } }
        .filter { (_, formats) -> formats.isNotEmpty() }
    return SeriesPlan(
        rows = DownloadFormat.entries
            .filter { format -> candidates.any { it.offer(format) != null } }
            .map { format ->
                row(format, candidates).copy(
                    checked = format in chosen,
                    locked = narration && format == DownloadFormat.Text,
                )
            },
        finishedSkipped = offers.size - candidates.size,
        books = asked.size,
        bytes = asked.sumOf { (_, formats) -> formats.bytesOnce() },
        asks = asked.map { (bookId, formats) -> bookId to formats.mapTo(mutableSetOf()) { it.format } },
    )
}

/**
 * The choice after a tap on [format]. The text follows the narration only while it is [locked] by it: a narration
 * chosen for another series, which this one does not offer, does not hold it.
 */
fun Set<DownloadFormat>.seriesToggled(format: DownloadFormat, locked: Boolean): Set<DownloadFormat> = when {
    format != DownloadFormat.Text || locked -> toggled(format)
    format in this -> this - format
    else -> this + format
}

private fun SeriesBookOffer.offer(format: DownloadFormat): OfferedFormat? = offered.firstOrNull { it.format == format }

private fun row(format: DownloadFormat, candidates: List<SeriesBookOffer>): SeriesFormatRow {
    val missing = candidates.mapNotNull { book -> book.offer(format)?.takeIf { format !in book.kept } }
    return SeriesFormatRow(
        format = format,
        books = missing.size,
        of = candidates.size,
        bytes = missing.sumOf { it.estimatedBytes ?: 0 },
        estimated = missing.any { it.estimated || it.estimatedBytes == null },
        checked = false,
        textName = sharedName(format, candidates),
    )
}

/**
 * What the text of every candidate that offers one is named by, when they all say the same: the row is then
 * named so. The text's own name otherwise, and for the other formats.
 */
private fun sharedName(format: DownloadFormat, candidates: List<SeriesBookOffer>): TextName {
    if (format != DownloadFormat.Text) return TextName.Text
    return candidates.mapNotNull { it.offer(format)?.textName }.distinct().singleOrNull() ?: TextName.Text
}

/** What the formats of one book take together: a text that comes inside the narration's file is not counted twice. */
private fun List<OfferedFormat>.bytesOnce(): Long {
    val narrationFile = firstOrNull { it.format == DownloadFormat.Narration }?.fileId
    return filterNot { it.format == DownloadFormat.Text && it.fileId != null && it.fileId == narrationFile }
        .sumOf { it.estimatedBytes ?: 0 }
}
