package app.tfdn.lirelle.feature.player.bookmarks

import app.tfdn.lirelle.core.audio.bookmarks.AddOutcome
import app.tfdn.lirelle.core.library.model.AudioBookmark
import app.tfdn.lirelle.feature.player.clockTime
import app.tfdn.lirelle.feature.player.controller.ChapterRow

/** The audio bookmarks of the audiobook in the player (spec 9). */
interface BookmarkActions {
    /**
     * A bookmark at the place playing, titled "chapter · time in the chapter"; what became of it, or null
     * when none was added: no audiobook is loaded, or the add failed (the screen says it could not be added).
     * It is made even if the screen goes meanwhile.
     */
    suspend fun addBookmark(): AddOutcome?

    /** A blank [title] keeps the one shown; a blank [note] clears it. */
    fun editBookmark(bookmark: AudioBookmark, title: String, note: String?)

    /** The player goes to [bookmark]; "Go back" returns to where it was, as after a chapter chosen. */
    fun goToBookmark(bookmark: AudioBookmark)

    /** [bookmark] leaves the list at once; it is deleted by [confirmDelete], or comes back by [undoDelete]. */
    fun deleteBookmark(bookmark: AudioBookmark)

    /** "Undo": the bookmark deleted comes back, and nothing is sent. */
    fun undoDelete(bookmark: AudioBookmark)

    /** The "Undo" snackbar went without "Undo": the deletion is made. */
    fun confirmDelete(bookmark: AudioBookmark)

    /** The list is read again from the server: the bookmarks tab was opened. */
    fun refreshBookmarks()
}

/** Where a bookmark is: its chapter's title (null without one) and how far into it, or into the book. */
internal data class BookmarkPlace(val chapterTitle: String?, val elapsedMs: Long) {
    /** "Chapter · 12:04", "Part 3 · 1:02:03"; the time in the book alone without a chapter title. */
    val text: String
        get() = chapterTitle?.let { "$it$Separator${clockTime(elapsedMs)}" } ?: clockTime(elapsedMs)

    companion object {
        private const val Separator = " · "

        /** [positionMs] on the book's timeline, in [chapter]; a chapter without a title is no chapter. */
        fun at(chapter: ChapterRow?, positionMs: Long): BookmarkPlace =
            chapter?.takeUnless { it.title.isBlank() }
                ?.let { BookmarkPlace(it.title, (positionMs - it.startMs).coerceAtLeast(0)) }
                ?: BookmarkPlace(null, positionMs)

        /** The place of [positionMs] among [chapters]: in the last one that starts at or before it. */
        fun of(positionMs: Long, chapters: List<ChapterRow>): BookmarkPlace =
            at(chapters.lastOrNull { it.startMs <= positionMs }, positionMs)
    }
}
