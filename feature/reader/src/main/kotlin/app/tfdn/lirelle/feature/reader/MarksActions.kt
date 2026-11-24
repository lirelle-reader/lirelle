package app.tfdn.lirelle.feature.reader

import app.tfdn.lirelle.core.reader.marks.MarkId
import app.tfdn.lirelle.feature.reader.text.TextAction
import java.util.Locale

/** The selection bar. Each action ends the selection. */
interface SelectionActions {
    /** A colour dot: a highlight in [color], or the exact highlight under the selection recoloured. */
    fun highlightSelection(color: String)

    /** "Note": a highlight in the last colour used, its panel open on the note; on an exact highlight, its panel. */
    fun noteOnSelection()

    /**
     * Translate, Define, Copy, Share, SearchWeb or another app, with the selected text. With the
     * highlight's panel open and no selection, with the highlighted text (the panel's Copy).
     */
    fun selectionAction(action: TextAction)

    /** "Search": the search starts with the selected text. */
    fun searchSelection()
}

/** The highlight's panel. */
interface HighlightPanelActions {
    /** At once, without Save. */
    fun setHighlightColour(color: String)

    /** The text in the note field, up to 4 000 characters. */
    fun editNote(draft: String)

    fun saveNote()

    /** Closes the panel and offers "Undo". */
    fun deleteHighlight()

    /** Asks whether to discard the note first when it was changed and not saved. */
    fun closeHighlightPanel()

    /** The changed note is dropped and the panel closed. */
    fun discardNote()

    /** The question is answered "no": the panel stays, the note as it was typed. */
    fun keepEditing()
}

/** Bookmarks, the panel's tabs and its rows, and the undo of a deletion. */
interface MarkListActions {
    /** Adds a bookmark at the start of the visible page, or removes every bookmark it holds ("Undo" offered). */
    fun toggleBookmark()

    /** "Undo" offered. */
    fun deleteBookmark(id: MarkId)

    /** Brings back what the last deletion took. */
    fun undo()

    /**
     * The offer of an undo timed out; a newer deletion also replaces it. Nothing happens when [offer]
     * is no longer the one shown: a newer offer is not taken away by an older one's time.
     */
    fun dismissUndo(offer: UndoUi)

    fun showPanelTab(tab: PanelTab)

    /** A jump, "Go back" offered; closes the sheet. */
    fun goToMark(cfi: String)

    /** To the start of the spine item [chapterIndex], for a highlight that cannot be placed. As [goToMark]. */
    fun goToChapter(chapterIndex: Int)
}

/** Selection, highlights, notes and bookmarks. */
interface MarksActions : SelectionActions, HighlightPanelActions, MarkListActions

/**
 * The texts the reader makes up for marks, in the language of the app.
 *
 * @property bookmarkAt the title of a bookmark on a page outside any chapter: a pattern with one
 *   `%d`, the percentage of the book, and `%%` for the sign.
 */
data class MarkLabels(val bookmarkAt: String) {
    fun bookmarkTitle(percent: Int): String =
        runCatching { String.format(Locale.ROOT, bookmarkAt, percent) }.getOrElse { "$percent %" }
}

internal val EnglishMarkLabels = MarkLabels(bookmarkAt = "Bookmark at %d %%")
