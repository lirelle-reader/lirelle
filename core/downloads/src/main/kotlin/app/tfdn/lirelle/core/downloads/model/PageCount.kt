package app.tfdn.lirelle.core.downloads.model

/**
 * The pages of a format made of pages.
 *
 * @property kept those the device has.
 * @property total those it is to have: what the server counted when the format was planned.
 */
data class PageCount(val kept: Int, val total: Int)

/**
 * The count of the pages among [files], null when none of them is a page. A format that is [whole] has them
 * all; until then it has [kept] of them, as its download said when the last one arrived: the record says it,
 * so that nobody counts files on the device to show it.
 */
internal fun pageCount(files: List<PlannedFile>, kept: Int, whole: Boolean): PageCount? {
    val total = files.count { it.kind == FileKind.Page }
    return if (total == 0) null else PageCount(if (whole) total else kept.coerceIn(0, total), total)
}
