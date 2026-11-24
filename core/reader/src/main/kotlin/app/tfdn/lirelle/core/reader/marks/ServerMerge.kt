package app.tfdn.lirelle.core.reader.marks

import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.ServerBookmark
import app.tfdn.lirelle.core.library.model.ServerHighlight

/** How many deleted items of each kind a book remembers for a restore once nothing waits for them. */
private const val KeptDeletions = 20

/**
 * The server's lists, except for the items in [keep] (those with a change waiting), which stay as
 * they are here, shown, changed or deleted. Items are matched by server id, so that one bound here
 * keeps its id. [fileId], the file they were read for, replaces the book's unless null.
 */
internal fun StoredBook.fromServer(
    bookmarks: List<ServerBookmark>,
    highlights: List<ServerHighlight>,
    keep: Set<String>,
    fileId: FileId? = null,
) = StoredBook(
    bookId = bookId,
    fileId = fileId ?: this.fileId,
    bookmarks = this.bookmarks.merged(bookmarks, ServerBookmark::id, ServerBookmark::stored, keep, MarkId::ofBookmark),
    highlights = this.highlights.merged(
        highlights, ServerHighlight::id, ServerHighlight::stored, keep, MarkId::ofHighlight,
    ),
)

/** [idOf]: the id of an item first seen on the server. */
private fun <S, T : StoredMark> List<T>.merged(
    server: List<S>,
    serverId: (S) -> ServerMarkId,
    stored: S.(String) -> T,
    keep: Set<String>,
    idOf: (ServerMarkId) -> MarkId,
): List<T> {
    val known = filter { it.serverId != null }.associateBy { it.serverId }
    val fresh = server.map { item ->
        val local = known[serverId(item)]
        if (local != null && local.id in keep) local else item.stored(local?.id ?: idOf(serverId(item)).value)
    }.distinctBy { it.id }
    val onServer = fresh.map { it.id }.toSet()
    val keptHere = filter { it.id !in onServer && (it.id in keep || it.deleted) }
    val forgotten = keptHere.filter { it.deleted && it.id !in keep }.dropLast(KeptDeletions).map { it.id }.toSet()
    return fresh + keptHere.filterNot { it.id in forgotten }
}
