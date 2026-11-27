package app.tfdn.lirelle.source.kavita.reading

import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.ReadingPosition
import app.tfdn.lirelle.core.reader.marks.spineIndexOf
import app.tfdn.lirelle.source.kavita.library.utcInstant

// Kavita's progress of a chapter and the app's place, one into the other.

private const val Percent = 100.0

/** How a CFI starts: what tells the app's place from the XPath Kavita's web reader writes. */
private const val CfiPrefix = "epubcfi("

/** The package's spine is its third child: spine item `N` is at step `/6/(2N + 2)`. */
private const val StepsPerItem = 2

/**
 * The place Kavita keeps of a chapter of [pages] pages (spine items for an [epub]). A comic's or a PDF's page counts
 * from 0 on Kavita, from 1 in the app; the page count means read, at the last page. An EPUB's place is the app's CFI
 * when the app wrote it in `bookScrollId`; otherwise (Kavita's web reader writes an XPath there) the start of its
 * spine item, the last one for a book read to the end. Never opened: 0, no place, no date.
 */
internal fun positionOf(fileId: FileId, dto: ProgressDto, pages: Int, epub: Boolean): ReadingPosition {
    val updatedAt = utcInstant(dto.lastModifiedUtc)
    if (updatedAt == null && dto.pageNum <= 0) {
        return ReadingPosition(fileId, cfi = null, percent = 0.0, updatedAt = null)
    }
    val percent = if (pages <= 0) 0.0 else (dto.pageNum * Percent / pages).coerceIn(0.0, Percent)
    val last = (pages - 1).coerceAtLeast(0)
    if (epub) {
        val cfi = dto.bookScrollId?.takeIf { it.startsWith(CfiPrefix) } ?: spineStart(dto.pageNum.coerceAtMost(last))
        return ReadingPosition(fileId, cfi = cfi, percent = percent, updatedAt = updatedAt)
    }
    val page = (dto.pageNum + 1).coerceIn(1, last + 1)
    return ReadingPosition(fileId, cfi = null, percent = percent, updatedAt = updatedAt, page = page)
}

/** Kavita's page of the app's [page] (from 1): one less, the end ([percent] 100) the page count. */
internal fun pageNumOf(page: Int, percent: Double, pages: Int): Int =
    if (percent >= Percent) pages else (page - 1).coerceIn(0, (pages - 1).coerceAtLeast(0))

/** Kavita's page of an EPUB place: the spine item its [cfi] points into, the end ([percent] 100) the spine count. */
internal fun spineNumOf(cfi: String?, percent: Double, pages: Int): Int =
    if (percent >= Percent) pages else cfi?.let(::spineIndexOf)?.coerceIn(0, (pages - 1).coerceAtLeast(0)) ?: 0

/** The CFI of the start of spine item [index]. */
private fun spineStart(index: Int): String = "epubcfi(/6/${(index.coerceAtLeast(0) + 1) * StepsPerItem})"
