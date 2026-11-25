package app.tfdn.lirelle.core.ids

import kotlinx.serialization.Serializable

// The app's own names for what a server serves. Each wraps the server's id as text, so that a second kind of server,
// whose ids are not numbers, fits the same types, and so that the compiler stops a file's id being passed where a
// book's is wanted. The text means nothing outside the source it came from: only that source may parse it, nothing
// compares it as a number or builds it from one. Stored and serialised as the plain text.

/** A book. */
@JvmInline
@Serializable
value class BookId(val value: String) {
    override fun toString(): String = value
}

/** One of a book's files: a format such as an EPUB, an audiobook, a comic archive or a PDF. */
@JvmInline
@Serializable
value class FileId(val value: String) {
    override fun toString(): String = value
}

/** A series. */
@JvmInline
@Serializable
value class SeriesId(val value: String) {
    override fun toString(): String = value
}

/** An author. */
@JvmInline
@Serializable
value class AuthorId(val value: String) {
    override fun toString(): String = value
}

/** A shelf: a collection or a smart list. */
@JvmInline
@Serializable
value class ShelfId(val value: String) {
    override fun toString(): String = value
}

/** An annotation as the server knows it: a highlight, a note or an EPUB bookmark. */
@JvmInline
@Serializable
value class MarkId(val value: String) {
    override fun toString(): String = value
}

/** A finished reading in the history. */
@JvmInline
@Serializable
value class ReadId(val value: String) {
    override fun toString(): String = value
}

/** One library of a server: a part of what it serves, with its own books, series and authors. */
@JvmInline
@Serializable
value class LibraryId(val value: String) {
    override fun toString(): String = value
}
