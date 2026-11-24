package app.tfdn.lirelle.core.reader.marks

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.HighlightStyle
import app.tfdn.lirelle.core.library.model.MarkOrigin
import app.tfdn.lirelle.core.library.model.NewHighlight
import app.tfdn.lirelle.core.library.model.PositionStatus
import app.tfdn.lirelle.core.library.model.ServerBookmark
import app.tfdn.lirelle.core.library.model.ServerHighlight
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** Invented marks for the tests of the store and the outbox. */
internal object MarkSamples {
    val At: Instant = Instant.parse("2026-11-14T10:00:00.5Z")
    val Wall: Clock = Clock.fixed(At, ZoneOffset.UTC)
    val Book = BookId("42")

    /** Another book than [Book]. */
    val OtherBook = BookId("43")
    val ReadFile = FileId("420")

    val Local = MarkId("8f0c4b8e-2a65-4c1e-9d55-0d1f0a7e3b21")
    val LocalBookmark = MarkId("0b7e7c1a-4f3d-4d8f-8a5e-6c2b9e1d4f70")

    val NewOne = NewHighlight(
        cfi = "epubcfi(/6/4!/4/2,/1:0,/1:12)",
        fileId = ReadFile,
        text = "a quiet lake",
        color = "#FACC15",
        note = null,
        chapterTitle = "Chapter one",
    )

    fun addHighlight(id: MarkId = Local, bookId: BookId = Book, highlight: NewHighlight = NewOne) =
        MarkChange.AddHighlight(bookId, id, ReadFile, highlight)

    fun change(id: MarkId = Local, color: String? = null, note: NoteEdit? = null, bookId: BookId = Book) =
        MarkChange.ChangeHighlight(bookId, id, color, note)

    fun delete(id: MarkId = Local, bookId: BookId = Book) = MarkChange.DeleteHighlight(bookId, id)

    fun restore(id: MarkId = Local, bookId: BookId = Book) = MarkChange.RestoreHighlight(bookId, id)

    fun addBookmark(id: MarkId = LocalBookmark, bookId: BookId = Book) =
        MarkChange.AddBookmark(bookId, id, ReadFile, "epubcfi(/6/8!/4/2/1:0)", "Chapter two")

    fun deleteBookmark(id: MarkId = LocalBookmark, bookId: BookId = Book) = MarkChange.DeleteBookmark(bookId, id)

    /** The server's id of an item, its text being [n]'s: the samples derive places and dates from [n]. */
    fun sid(n: Long) = ServerMarkId("$n")

    fun serverHighlight(id: Long, text: String = "the far shore", color: String = "#4ADE80", note: String? = null) =
        ServerHighlight(
            id = sid(id),
            cfi = "epubcfi(/6/${2 * id + 2}!/4/2,/1:0,/1:9)",
            text = text,
            color = color,
            style = HighlightStyle.Underline,
            note = note,
            chapterTitle = "Chapter $id",
            chapterIndex = id.toInt(),
            origin = MarkOrigin.KoReader,
            positionStatus = PositionStatus.Repaired,
            createdAt = At.minusSeconds(id),
            updatedAt = null,
        )

    fun serverBookmark(id: Long, title: String = "Bookmark $id") = ServerBookmark(
        id = sid(id),
        cfi = "epubcfi(/6/${2 * id + 2}!/4/2/1:0)",
        title = title,
        note = null,
        origin = MarkOrigin.Web,
        createdAt = At.minusSeconds(id),
    )
}
