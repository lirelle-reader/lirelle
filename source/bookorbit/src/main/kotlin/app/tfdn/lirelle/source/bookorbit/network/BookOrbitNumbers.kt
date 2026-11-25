package app.tfdn.lirelle.source.bookorbit.network

import app.tfdn.lirelle.core.ids.AuthorId
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.ids.LibraryId
import app.tfdn.lirelle.core.ids.MarkId
import app.tfdn.lirelle.core.ids.ReadId
import app.tfdn.lirelle.core.ids.SeriesId
import app.tfdn.lirelle.core.ids.ShelfId

// The BookOrbit side's way back from the app's ids to the server's numbers, for its requests (the way in is in
// BookOrbitIds.kt). An id whose text is not the one a BookOrbit number gives was never BookOrbit's: a bug, thrown,
// never sent to the server.

/** The number BookOrbit gives this book. */
internal fun BookId.bookOrbit(): Long = bookOrbitNumber(value, "BookId")

/** The number BookOrbit gives this file. */
internal fun FileId.bookOrbit(): Long = bookOrbitNumber(value, "FileId")

/** The number BookOrbit gives this series. */
internal fun SeriesId.bookOrbit(): Long = bookOrbitNumber(value, "SeriesId")

/** The number BookOrbit gives this author. */
internal fun AuthorId.bookOrbit(): Long = bookOrbitNumber(value, "AuthorId")

/** The number BookOrbit gives this shelf. */
internal fun ShelfId.bookOrbit(): Long = bookOrbitNumber(value, "ShelfId")

/** The number BookOrbit gives this annotation. */
internal fun MarkId.bookOrbit(): Long = bookOrbitNumber(value, "MarkId")

/** The number BookOrbit gives this finished reading. */
internal fun ReadId.bookOrbit(): Long = bookOrbitNumber(value, "ReadId")

/** The number BookOrbit gives this library. */
internal fun LibraryId.bookOrbit(): Long = bookOrbitNumber(value, "LibraryId")

/**
 * The revision of the listening state whose version is [version] (made by [versionOf]); none, when the device knows
 * no state, is 0: the write creates it. A version whose text is not the one a revision gives was never BookOrbit's:
 * a bug, thrown, like an id's.
 */
internal fun revisionOf(version: String?): Int {
    if (version == null) return 0
    val revision = version.toIntOrNull()
    check(revision != null && revision.toString() == version) { "A version that is not a BookOrbit revision" }
    return revision
}

/**
 * The number whose decimal text is exactly [text], or a failure naming [type]: the text a number gives, so no
 * plus sign, no leading zero, no space. A minus sign is accepted, as the way in writes it for a negative number:
 * BookOrbit gives none, but whatever the way in made goes back unchanged.
 */
private fun bookOrbitNumber(text: String, type: String): Long {
    val number = text.toLongOrNull()
    check(number != null && number.toString() == text) { "A $type that is not a BookOrbit number" }
    return number
}
