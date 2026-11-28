package app.tfdn.lirelle.source.audiobookshelf.library

import app.tfdn.lirelle.core.library.model.BookSort
import app.tfdn.lirelle.core.library.model.SortDirection
import app.tfdn.lirelle.source.audiobookshelf.library.dto.AbsItemDto
import java.text.Collator

/**
 * The orders of a library's books, as the server is asked them for its pages and as the device sorts a whole list
 * the same way: by date added, by title, by author (last name first) and by date published. Audiobookshelf sorts by
 * series only within one series, so a list asked by series is in the order its books were added; a sort is always
 * named, as the server ignores one it does not know without a word.
 */
internal object AbsBookSorts {

    /** The field the server sorts a library's items by for [sort]. */
    fun serverField(sort: BookSort): String = when (sort) {
        BookSort.Added, BookSort.Series -> "addedAt"
        BookSort.Title -> "media.metadata.title"
        BookSort.Author -> "media.metadata.authorNameLF"
        BookSort.Published -> "media.metadata.publishedYear"
    }

    /** How the server is told [direction]: 1 for descending. */
    fun serverDesc(direction: SortDirection): Int = if (direction == SortDirection.Descending) 1 else 0

    /** The order of a group's items ([AbsLibraryApi.groupItems]) for [sort] and [direction]. */
    fun serverOrder(sort: BookSort, direction: SortDirection): Map<String, String> =
        mapOf("sort" to serverField(sort), "desc" to serverDesc(direction).toString())

    /**
     * [items] in [sort] and [direction], sorted on the device: titles and names as a reader sorts them, case and
     * accents aside; a book without a year last whichever way the dates go. Books equal by the sort keep their order.
     */
    fun <T> sorted(items: List<T>, sort: BookSort, direction: SortDirection, itemOf: (T) -> AbsItemDto): List<T> {
        val collator = Collator.getInstance().apply { strength = Collator.PRIMARY }
        val byTitle = compareBy<AbsItemDto, String>(collator) { it.media.metadata.title.orEmpty() }
        val ordered: Comparator<AbsItemDto> = when (sort) {
            BookSort.Added, BookSort.Series -> compareBy { it.addedAt ?: 0L }
            BookSort.Title -> byTitle
            BookSort.Author -> compareBy<AbsItemDto, String>(collator) { it.media.metadata.authorNameLF.orEmpty() }
                .then(byTitle)
            BookSort.Published -> return items.sortedWith(
                compareBy<T, String?>(nullsLast(directed(naturalOrder(), direction))) { itemOf(it).published }
                    .thenBy(collator) { itemOf(it).media.metadata.title.orEmpty() },
            )
        }
        return items.sortedWith(compareBy(directed(ordered, direction), itemOf))
    }

    /** [items] themselves, sorted as [sorted] sorts. */
    fun sorted(items: List<AbsItemDto>, sort: BookSort, direction: SortDirection): List<AbsItemDto> =
        sorted(items, sort, direction) { it }
}

/** [comparator] the way [direction] goes. */
private fun <T> directed(comparator: Comparator<T>, direction: SortDirection): Comparator<T> =
    if (direction == SortDirection.Descending) comparator.reversed() else comparator

/** When the book was published, as the server keeps it ("1979-10-12", "1992"); null when it does not say. */
private val AbsItemDto.published: String?
    get() = media.metadata.publishedYear?.trim()?.takeIf { it.isNotEmpty() }
