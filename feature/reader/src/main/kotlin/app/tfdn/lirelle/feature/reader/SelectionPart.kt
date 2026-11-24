package app.tfdn.lirelle.feature.reader

import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.NewHighlight
import app.tfdn.lirelle.core.reader.marks.Highlight
import app.tfdn.lirelle.core.reader.marks.HighlightColour
import app.tfdn.lirelle.core.reader.marks.MarkId
import app.tfdn.lirelle.feature.reader.bridge.HostMessage
import app.tfdn.lirelle.feature.reader.bridge.PageMessage
import app.tfdn.lirelle.feature.reader.text.TextAction
import app.tfdn.lirelle.feature.reader.text.TextActions

/** The longest place of a highlight the server takes. */
private const val MaxHighlightCfiLength = 2_000

/**
 * The page's live selection and the bar above it. Most actions end the selection, on the page
 * too; a highlight made from it is drawn once the marks have it.
 *
 * @param search starts a search for the text given.
 */
internal class SelectionPart(
    private val core: ReaderCore,
    private val writes: MarkWrites,
    private val textActions: TextActions,
    private val appearance: Appearance,
    private val panel: HighlightPanelPart,
    private val search: (String) -> Unit,
) : SelectionActions {

    /** What the device offers is asked when a selection starts, not while its handles move. */
    fun onSelection(message: PageMessage.Selection) {
        val offered = if (core.current.selection == null) textActions.available() else core.current.textActions
        val selection = SelectionUi(
            cfi = message.cfi,
            text = message.text,
            sectionIndex = message.sectionIndex,
            bounds = message.bounds,
            exactId = message.exactId?.let(::MarkId),
        )
        // The controls' bars would cover the page where the selection's bar goes.
        core.update { it.copy(selection = selection, textActions = offered, controlsShown = false) }
    }

    fun onSelectionCleared() = core.update { it.copy(selection = null) }

    override fun highlightSelection(color: String) {
        val fileId = core.fileId ?: return
        val selection = take() ?: return
        if (selection.exactId == null && !selection.fitsTheServer()) return
        val colour = HighlightColour.display(color)
        appearance.update { it.copy(lastHighlightColour = colour) }
        val exactId = selection.exactId
        writes.launch { bookId ->
            if (exactId != null) {
                recolour(bookId, exactId, colour)
            } else {
                addHighlight(bookId, selection.with(fileId, colour))
            }
        }
    }

    override fun noteOnSelection() {
        val fileId = core.fileId ?: return
        val selection = take() ?: return
        if (selection.exactId != null) {
            exactOf(selection)?.let { panel.open(it.toPanel(focusNote = true)) }
            return
        }
        if (!selection.fitsTheServer()) return
        val colour = HighlightColour.display(core.current.settings.lastHighlightColour)
        writes.launch { bookId ->
            val id = addHighlight(bookId, selection.with(fileId, colour))
            panel.open(HighlightPanelUi(id, selection.text, colour, "", "", focusNote = true, confirmDiscard = false))
        }
    }

    /**
     * Copy and Share are done with the text; after Translate, Define, a web search or another app the
     * reader comes back to the same selection, to act on it again.
     */
    override fun selectionAction(action: TextAction) {
        val text = core.current.selection?.text ?: core.current.highlightPanel?.text ?: return
        textActions.perform(action, text)
        if (action.endsSelection()) take()
    }

    override fun searchSelection() {
        val selection = take() ?: return
        search(selection.text)
    }

    /** The system's back ends the selection. False when there was none. */
    fun onBack(): Boolean = take() != null

    /** Ends the selection, here and on the page; the one there was, if any. */
    private fun take(): SelectionUi? {
        val selection = core.current.selection ?: return null
        core.update { it.copy(selection = null) }
        core.driver.send(HostMessage.ClearSelection)
        return selection
    }

    /**
     * The highlight with exactly the selection's range, while the marks still hold it. One they no
     * longer hold was deleted since the page drew it, and may come back with "Undo": it is never
     * made again from the selection.
     */
    private fun exactOf(selection: SelectionUi): Highlight? =
        selection.exactId?.let { id -> core.current.marks.highlights.firstOrNull { it.id == id } }

    /** The chapter is the contents entry the selection's section belongs to. */
    private fun SelectionUi.with(fileId: FileId, colour: String) = NewHighlight(
        cfi = cfi,
        fileId = fileId,
        text = text,
        color = colour,
        note = null,
        chapterTitle = core.current.sectionLabels.getOrNull(sectionIndex),
    )
}

/** A highlight of a place longer than the server takes would be refused, and vanish: it is never made. */
private fun SelectionUi.fitsTheServer() = cfi.length <= MaxHighlightCfiLength

/** The actions after which the selection has served its purpose. */
private fun TextAction.endsSelection() = this == TextAction.Copy || this == TextAction.Share
