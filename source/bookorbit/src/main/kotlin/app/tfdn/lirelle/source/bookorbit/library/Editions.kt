@file:Suppress("TooManyFunctions") // Label, order, media and progress: one small rule each.

package app.tfdn.lirelle.source.bookorbit.library

import app.tfdn.lirelle.core.library.model.BookEdition
import app.tfdn.lirelle.core.library.model.EditionMedia
import app.tfdn.lirelle.core.library.model.EditionProgress
import app.tfdn.lirelle.core.library.model.isAudioFormat
import app.tfdn.lirelle.source.bookorbit.library.dto.BookDetailDto
import app.tfdn.lirelle.source.bookorbit.library.dto.EditionProgressDto
import app.tfdn.lirelle.source.bookorbit.library.dto.LinkedBookDto
import app.tfdn.lirelle.source.bookorbit.network.bookIdOf
import java.time.Instant
import java.time.format.DateTimeParseException
import kotlin.math.roundToInt

private const val Present = "present"
private const val LabelSeparator = " · "
private const val LowestInProgress = 1
private const val HighestInProgress = 99

/** Archives of pages: an edition made of them only is a comic. */
private val ComicFormats = setOf("cbz", "cbr", "cb7")

/**
 * Every edition of the work of [dto], the open book included, named and ordered by the server's own rules
 * (`editionLabels` and `sortEditions` in its types), so that the app and the web list them alike. Empty when the
 * book has no other edition the reader can open.
 *
 * @param groupsEditions the server announces `book-editions`. Without it the answer has neither the edition fields
 *   nor the progress: no label is made, the progress is unknown, and only the main book and the ids order the list.
 * @param thumbnailUrl the small cover of a book, from its number and what tells a changed cover.
 */
internal fun editionsOf(
    dto: BookDetailDto,
    groupsEditions: Boolean,
    thumbnailUrl: (Long, String?) -> String,
): List<BookEdition> {
    val others = dto.linkedBooks.filter { it.status == Present }
    if (others.isEmpty()) return emptyList()
    val rich = groupsEditions && dto.linkSelf != null
    val self = dto.linkSelf ?: dto.asEdition()
    val all = (listOf(self) + others).sortedWith(if (rich) RichOrder else ReducedOrder)
    val labels = if (rich) labelsOf(all) else emptyMap()
    return all.map { edition ->
        BookEdition(
            bookId = bookIdOf(edition.bookId),
            isOpen = edition === self,
            label = labels[edition.bookId]?.takeIf { it.isNotBlank() },
            title = edition.title.orEmpty(),
            coverUrl = thumbnailUrl(edition.bookId, edition.coverUpdatedAt),
            media = mediaOf(edition.formats),
            progress = if (rich) progressOf(edition.progress) else EditionProgress.Unknown,
        )
    }
}

/** The open book as one of its linked books, from its own fields: what a server before 3.10.0 leaves to do. */
private fun BookDetailDto.asEdition() = LinkedBookDto(
    bookId = id,
    libraryName = libraryName,
    title = title,
    formats = files.filter { isContentRole(it.role) }.mapNotNull { it.format },
    isLead = linkIsLead,
)

// ---------- order

private val ReducedOrder = compareByDescending<LinkedBookDto> { it.isLead }.thenBy { it.bookId }

/** The main edition, those read (the latest first), those finished without a date, text before audio, the newest. */
private val RichOrder = compareByDescending<LinkedBookDto> { it.isLead }
    .thenBy { readRank(it) }
    .thenByDescending { readAt(it) }
    .thenBy { if (isAudioOnly(it.formats)) 1 else 0 }
    .thenBy(nullsLast(reverseOrder())) { it.publishedYear }
    .thenBy { it.bookId }

private fun readRank(edition: LinkedBookDto): Int = when {
    edition.lastReadAt != null -> 0
    edition.progress?.finished == true -> 1
    else -> 2
}

/** Only compared between editions that were read: the others share the lowest value. */
private fun readAt(edition: LinkedBookDto): Long = try {
    edition.lastReadAt?.let { Instant.parse(it).toEpochMilli() } ?: Long.MIN_VALUE
} catch (_: DateTimeParseException) {
    Long.MIN_VALUE
}

// ---------- label

private fun labelsOf(editions: List<LinkedBookDto>): Map<Long, String> {
    val bases = editions.associate { it.bookId to baseLabel(it) }
    val counts = bases.values.groupingBy { it.lowercase() }.eachCount()
    return editions.associate { edition ->
        val base = bases.getValue(edition.bookId)
        val shared = counts.getValue(base.lowercase()) > 1 && base != edition.libraryName
        edition.bookId to if (shared) "$base$LabelSeparator${edition.libraryName}" else base
    }
}

/** Its edition name, else the narrator of an audio-only edition, else publisher and year, else its library. */
private fun baseLabel(edition: LinkedBookDto): String =
    edition.editionName.named() ?: narratorOf(edition) ?: publicationOf(edition) ?: edition.libraryName

private fun String?.named(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

private fun narratorOf(edition: LinkedBookDto): String? =
    edition.narrators.firstNotNullOfOrNull { it.named() }?.takeIf { isAudioOnly(edition.formats) }

private fun publicationOf(edition: LinkedBookDto): String? {
    val publisher = edition.publisher.named()
    val year = edition.publishedYear?.toString()
    return if (publisher != null && year != null) "$publisher$LabelSeparator$year" else publisher ?: year
}

// ---------- media and progress

private fun isAudioOnly(formats: List<String>): Boolean = formats.isNotEmpty() && formats.all(::isAudioFormat)

private fun mediaOf(formats: List<String>): EditionMedia {
    val others = formats.filterNot(::isAudioFormat)
    return when {
        others.isEmpty() && formats.isNotEmpty() -> EditionMedia.Audio
        others.size < formats.size -> EditionMedia.TextAndAudio
        others.isNotEmpty() && others.all { it.lowercase() in ComicFormats } -> EditionMedia.Comic
        else -> EditionMedia.Text
    }
}

private fun progressOf(progress: EditionProgressDto?): EditionProgress = when {
    progress == null -> EditionProgress.NotStarted
    progress.finished -> EditionProgress.Finished
    else -> EditionProgress.InProgress(progress.percentage.roundToInt().coerceIn(LowestInProgress, HighestInProgress))
}
