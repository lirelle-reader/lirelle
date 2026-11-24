package app.tfdn.lirelle.core.reader.marks

import app.tfdn.lirelle.core.library.model.HighlightStyle
import app.tfdn.lirelle.core.library.model.MarkOrigin
import java.time.Instant

/** The book with a change made here shown. */
internal fun StoredBook.applied(change: MarkChange, now: Instant): StoredBook {
    val id = change.id.value
    return when (change) {
        is MarkChange.AddHighlight ->
            copy(fileId = change.fileId, highlights = highlights.put(change.stored(highlights.find(id), now)))
        is MarkChange.ChangeHighlight ->
            copy(highlights = highlights.map { if (it.id == id && !it.deleted) it.changed(change) else it })
        is MarkChange.DeleteHighlight -> copy(highlights = highlights.deleting(id) { copy(deleted = true) })
        is MarkChange.RestoreHighlight ->
            copy(highlights = highlights.map { if (it.id == id) it.copy(deleted = false) else it })
        is MarkChange.AddBookmark ->
            copy(fileId = change.fileId, bookmarks = bookmarks.put(change.stored(bookmarks.find(id), now)))
        is MarkChange.DeleteBookmark -> copy(bookmarks = bookmarks.deleting(id) { copy(deleted = true) })
    }
}

/** [id] known to the server as [serverId]; a copy of it a refresh already brought, not in [keep], goes. */
internal fun StoredBook.bound(id: String, serverId: ServerMarkId, keep: Set<String>): StoredBook = copy(
    bookmarks = bookmarks.bound(id, serverId, keep) { copy(serverId = serverId) },
    highlights = highlights.bound(id, serverId, keep) { copy(serverId = serverId) },
)

private fun <T : StoredMark> List<T>.find(id: String): T? = firstOrNull { it.id == id }

private fun <T : StoredMark> List<T>.put(item: T): List<T> = filterNot { it.id == item.id } + item

/** The last deleted are the last in the list: the ones remembered longest. */
private fun <T : StoredMark> List<T>.deleting(id: String, delete: T.() -> T): List<T> {
    val item = firstOrNull { it.id == id && !it.deleted } ?: return this
    return filterNot { it.id == id } + item.delete()
}

private fun <T : StoredMark> List<T>.bound(
    id: String,
    serverId: ServerMarkId,
    keep: Set<String>,
    bind: T.() -> T,
): List<T> {
    if (none { it.id == id }) return this
    return filterNot { it.id != id && it.serverId == serverId && it.id !in keep }
        .map { if (it.id == id) it.bind() else it }
}

private fun StoredHighlight.changed(change: MarkChange.ChangeHighlight) = copy(
    color = change.color ?: color,
    note = when (val edit = change.note) {
        is NoteEdit.Set -> edit.text
        NoteEdit.Clear -> null
        null -> note
    },
)

private fun MarkChange.AddHighlight.stored(existing: StoredHighlight?, now: Instant) = StoredHighlight(
    id = id.value,
    serverId = existing?.serverId,
    cfi = highlight.cfi,
    text = highlight.text,
    color = highlight.color,
    style = HighlightStyle.Highlight,
    note = highlight.note,
    chapterTitle = highlight.chapterTitle,
    chapterIndex = spineIndexOf(highlight.cfi),
    origin = MarkOrigin.Web,
    createdAt = existing?.createdAt ?: now,
)

private fun MarkChange.AddBookmark.stored(existing: StoredBookmark?, now: Instant) = StoredBookmark(
    id = id.value,
    serverId = existing?.serverId,
    cfi = cfi,
    title = title,
    origin = MarkOrigin.Web,
    createdAt = existing?.createdAt ?: now,
)
