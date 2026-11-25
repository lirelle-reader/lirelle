package app.tfdn.lirelle.source.bookorbit.network

import app.tfdn.lirelle.core.ids.AuthorId
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.ids.LibraryId
import app.tfdn.lirelle.core.ids.MarkId
import app.tfdn.lirelle.core.ids.ReadId
import app.tfdn.lirelle.core.ids.SeriesId
import app.tfdn.lirelle.core.ids.ShelfId

// The BookOrbit side's way from the server's numbers to the app's ids. Only BookOrbit's repositories and mappers use
// these: everywhere else an id is opaque. The way back is in BookOrbitNumbers.kt.

/** The id of the book BookOrbit numbers [number]. */
internal fun bookIdOf(number: Long): BookId = BookId(number.toString())

/** The id of the file BookOrbit numbers [number]. */
internal fun fileIdOf(number: Long): FileId = FileId(number.toString())

/** The id of the series BookOrbit numbers [number]. */
internal fun seriesIdOf(number: Long): SeriesId = SeriesId(number.toString())

/** The id of the author BookOrbit numbers [number]. */
internal fun authorIdOf(number: Long): AuthorId = AuthorId(number.toString())

/** The id of the shelf BookOrbit numbers [number]. */
internal fun shelfIdOf(number: Long): ShelfId = ShelfId(number.toString())

/** The id of the annotation BookOrbit numbers [number]. */
internal fun markIdOf(number: Long): MarkId = MarkId(number.toString())

/** The id of the finished reading BookOrbit numbers [number]. */
internal fun readIdOf(number: Long): ReadId = ReadId(number.toString())

/** The id of the library BookOrbit numbers [number]. */
internal fun libraryIdOf(number: Long): LibraryId = LibraryId(number.toString())

/** The version of a listening state BookOrbit gave [revision]: opaque to the app, which only compares it. */
internal fun versionOf(revision: Int): String = revision.toString()
