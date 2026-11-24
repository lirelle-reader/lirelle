package app.tfdn.lirelle.feature.reader

import app.tfdn.lirelle.core.reader.marks.HighlightColour

/** The longest note the panel takes. */
internal const val NoteMaxLength = 4_000

/** The panel of one highlight: its colour, changed at once, and its note, kept by Save, which closes it. */
internal class HighlightPanelPart(
    private val core: ReaderCore,
    private val writes: MarkWrites,
    private val appearance: Appearance,
) : HighlightPanelActions {

    fun open(panel: HighlightPanelUi) = core.update { it.copy(highlightPanel = panel) }

    override fun setHighlightColour(color: String) {
        val panel = core.current.highlightPanel ?: return
        val colour = HighlightColour.display(color)
        change { it.copy(color = colour) }
        appearance.update { it.copy(lastHighlightColour = colour) }
        writes.launch { recolour(it, panel.id, colour) }
    }

    /** A draft past the limit is refused whole: nothing is cut, no character split in two. */
    override fun editNote(draft: String) {
        if (draft.length > NoteMaxLength) return
        change { it.copy(draft = draft, confirmDiscard = false) }
    }

    /** Keeps the note and closes the panel; a note left blank is removed. */
    override fun saveNote() {
        val panel = core.current.highlightPanel ?: return
        val note = panel.draft.trim()
        core.update { it.copy(highlightPanel = null) }
        writes.launch { setNote(it, panel.id, note.ifEmpty { null }) }
    }

    override fun deleteHighlight() {
        val panel = core.current.highlightPanel ?: return
        core.update { it.copy(highlightPanel = null, undo = UndoUi.Highlight(panel.id)) }
        writes.launch { deleteHighlight(it, panel.id) }
    }

    override fun closeHighlightPanel() {
        val panel = core.current.highlightPanel ?: return
        if (panel.draft.trim() != panel.note.trim()) change { it.copy(confirmDiscard = true) } else discardNote()
    }

    override fun discardNote() = core.update { it.copy(highlightPanel = null) }

    override fun keepEditing() = change { it.copy(confirmDiscard = false) }

    /** The system's back: closes the panel, or answers "keep editing" to its question. False when it is not open. */
    fun onBack(): Boolean {
        val panel = core.current.highlightPanel ?: return false
        if (panel.confirmDiscard) keepEditing() else closeHighlightPanel()
        return true
    }

    private fun change(transform: (HighlightPanelUi) -> HighlightPanelUi) =
        core.update { it.copy(highlightPanel = it.highlightPanel?.let(transform)) }
}
