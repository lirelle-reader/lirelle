package app.tfdn.lirelle.core.reader.marks

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.NewHighlight
import app.tfdn.lirelle.core.reader.sync.InstantAsText
import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal sealed class StoredChange {
    abstract val bookId: BookId
    abstract val id: String
}

@Serializable
@SerialName("addHighlight")
internal data class StoredAddHighlight(
    override val bookId: BookId,
    override val id: String,
    val fileId: FileId,
    val cfi: String,
    val highlightFileId: FileId,
    val text: String,
    val color: String,
    val note: String? = null,
    val chapterTitle: String? = null,
    @Serializable(InstantAsText::class) val attemptedAt: Instant? = null,
) : StoredChange()

/** [clearNote] with no [note]: the note is cleared; neither: it is left as it is. */
@Serializable
@SerialName("changeHighlight")
internal data class StoredChangeHighlight(
    override val bookId: BookId,
    override val id: String,
    val color: String? = null,
    val note: String? = null,
    val clearNote: Boolean = false,
) : StoredChange()

@Serializable
@SerialName("deleteHighlight")
internal data class StoredDeleteHighlight(override val bookId: BookId, override val id: String) : StoredChange()

@Serializable
@SerialName("restoreHighlight")
internal data class StoredRestoreHighlight(override val bookId: BookId, override val id: String) : StoredChange()

@Serializable
@SerialName("addBookmark")
internal data class StoredAddBookmark(
    override val bookId: BookId,
    override val id: String,
    val fileId: FileId,
    val cfi: String,
    val title: String,
) : StoredChange()

@Serializable
@SerialName("deleteBookmark")
internal data class StoredDeleteBookmark(override val bookId: BookId, override val id: String) : StoredChange()

internal fun MarkChange.stored(): StoredChange = when (this) {
    is MarkChange.AddHighlight -> StoredAddHighlight(
        bookId, id.value, fileId, highlight.cfi, highlight.fileId, highlight.text, highlight.color, highlight.note,
        highlight.chapterTitle, attemptedAt,
    )
    is MarkChange.ChangeHighlight ->
        StoredChangeHighlight(bookId, id.value, color, (note as? NoteEdit.Set)?.text, note == NoteEdit.Clear)
    is MarkChange.DeleteHighlight -> StoredDeleteHighlight(bookId, id.value)
    is MarkChange.RestoreHighlight -> StoredRestoreHighlight(bookId, id.value)
    is MarkChange.AddBookmark -> StoredAddBookmark(bookId, id.value, fileId, cfi, title)
    is MarkChange.DeleteBookmark -> StoredDeleteBookmark(bookId, id.value)
}

internal fun StoredChange.change(): MarkChange = when (this) {
    is StoredAddHighlight -> MarkChange.AddHighlight(
        bookId, MarkId(id), fileId, NewHighlight(cfi, highlightFileId, text, color, note, chapterTitle), attemptedAt,
    )
    is StoredChangeHighlight -> MarkChange.ChangeHighlight(bookId, MarkId(id), color, noteEdit())
    is StoredDeleteHighlight -> MarkChange.DeleteHighlight(bookId, MarkId(id))
    is StoredRestoreHighlight -> MarkChange.RestoreHighlight(bookId, MarkId(id))
    is StoredAddBookmark -> MarkChange.AddBookmark(bookId, MarkId(id), fileId, cfi, title)
    is StoredDeleteBookmark -> MarkChange.DeleteBookmark(bookId, MarkId(id))
}

private fun StoredChangeHighlight.noteEdit(): NoteEdit? = when {
    note != null -> NoteEdit.Set(note)
    clearNote -> NoteEdit.Clear
    else -> null
}
