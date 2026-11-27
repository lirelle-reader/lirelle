package app.tfdn.lirelle.source.kavita.library

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.LibraryId
import app.tfdn.lirelle.core.ids.ReadId
import app.tfdn.lirelle.core.ids.SeriesId
import app.tfdn.lirelle.core.ids.ShelfId

// The Kavita side's id conversions, in one place: Kavita numbers its series, its libraries and its reading lists,
// and the app's ids for them are those numbers written out. Everywhere else an id is text that means nothing but
// itself. A book's id, `v12` or `c34`, is made and read in `KavitaBooks.kt`; an author's is its name.

/** The id of the Kavita series of this number. */
internal fun Int.asSeriesId(): SeriesId = SeriesId(this.toString())

/** The id of the Kavita reading list of this number, a shelf. */
internal fun Int.asShelfId(): ShelfId = ShelfId(this.toString())

/** The id of the Kavita library of this number. */
internal fun Int.asLibraryId(): LibraryId = LibraryId(this.toString())

/** The number of the Kavita series of this id; null for an id that is none of Kavita's. */
internal fun SeriesId.kavitaNumber(): Int? = value.kavitaNumber()

/** The number of the Kavita reading list of this id; null for an id that is none of Kavita's. */
internal fun ShelfId.kavitaNumber(): Int? = value.kavitaNumber()

/** The number this text writes, digits only and above 0, as Kavita numbers its items; null for any other text. */
internal fun String.kavitaNumber(): Int? =
    takeIf { it.isNotEmpty() && it.all { digit -> digit in '0'..'9' } }?.toIntOrNull()?.takeIf { it > 0 }

/**
 * The id of the [readNumber]th read of the Kavita book of this id, from 1: `c44#2`. Kavita keeps no read of its own,
 * only sessions: the History counts a book's reads.
 */
internal fun BookId.kavitaReadId(readNumber: Int): ReadId = ReadId("$value#$readNumber")
