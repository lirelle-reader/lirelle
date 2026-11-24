package app.tfdn.lirelle.core.library.model

import app.tfdn.lirelle.core.ids.AuthorId

data class Author(val id: AuthorId, val name: String, val bookCount: Int, val portraitUrl: String?)

data class AuthorDetail(val author: Author, val biography: String?)

/** The orders the user can choose for authors; the source says each in its own query. The names are stored. */
enum class AuthorSort {
    /** By sort name ("Adams, Douglas"), the order books sorted by author follow; not by display name. */
    Name,

    /** By number of books. */
    BookCount,
}
