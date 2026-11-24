package app.tfdn.lirelle.core.reader.marks

/** The same item: the same id in the same book. */
internal fun MarkChange.sameItem(other: MarkChange) = bookId == other.bookId && id == other.id

/** The same kind of change of the same item. */
internal fun MarkChange.sameKind(other: MarkChange) = sameItem(other) && this::class == other::class

/** What brings the server back to where it was before this change, if anything can. */
internal fun MarkChange.undo(): MarkChange? = when (this) {
    is MarkChange.AddHighlight -> MarkChange.DeleteHighlight(bookId, id)
    is MarkChange.DeleteHighlight -> MarkChange.RestoreHighlight(bookId, id)
    is MarkChange.RestoreHighlight -> MarkChange.DeleteHighlight(bookId, id)
    is MarkChange.AddBookmark -> MarkChange.DeleteBookmark(bookId, id)
    is MarkChange.ChangeHighlight, is MarkChange.DeleteBookmark -> null
}

/** The add with [change]'s fields as its values. */
internal fun MarkChange.AddHighlight.with(change: MarkChange.ChangeHighlight) = copy(
    highlight = highlight.copy(
        color = change.color ?: highlight.color,
        note = when (val edit = change.note) {
            is NoteEdit.Set -> edit.text
            NoteEdit.Clear -> null
            null -> highlight.note
        },
    ),
)

/** One change with the fields of both, [later]'s winning. */
internal fun MarkChange.ChangeHighlight.then(later: MarkChange.ChangeHighlight) =
    copy(color = later.color ?: color, note = later.note ?: note)
