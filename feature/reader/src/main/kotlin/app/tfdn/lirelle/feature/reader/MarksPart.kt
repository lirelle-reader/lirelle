package app.tfdn.lirelle.feature.reader

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.HighlightStyle
import app.tfdn.lirelle.core.reader.marks.Bookmark
import app.tfdn.lirelle.core.reader.marks.Highlight
import app.tfdn.lirelle.core.reader.marks.HighlightColour
import app.tfdn.lirelle.core.reader.marks.Marks
import app.tfdn.lirelle.feature.reader.bridge.HostMessage
import app.tfdn.lirelle.feature.reader.bridge.PageBookmark
import app.tfdn.lirelle.feature.reader.bridge.PageHighlight
import app.tfdn.lirelle.feature.reader.bridge.PageHighlightStyle
import app.tfdn.lirelle.feature.reader.bridge.PageMessage
import app.tfdn.lirelle.feature.reader.text.TextActions
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The book's bookmarks and highlights: kept in the state, drawn by the page, read again from the
 * server now and then. What the user does with them is in [selection], [panel] and [bookmarks].
 */
internal class MarksPart(
    private val core: ReaderCore,
    private val marks: Marks,
    textActions: TextActions,
    appearance: Appearance,
    lasting: CoroutineScope,
    search: (String) -> Unit,
) {
    private val writes = MarkWrites(lasting, marks, core.bookId)
    val panel = HighlightPanelPart(core, writes, appearance)
    val selection = SelectionPart(core, writes, textActions, appearance, panel, search)
    val bookmarks = BookmarkPart(core, writes)

    /** What the page holds of ours; null until it opened a book. */
    private var sentHighlights: List<PageHighlight>? = null
    private var sentBookmarks: List<PageBookmark>? = null

    /** The file whose marks were read from the server, whatever the quiet period, since the reader opened. */
    private var refreshedFile: FileId? = null

    fun start() {
        core.scope.launch {
            marks.of(core.bookId).collect { held ->
                core.update { it.copy(marks = held) }
                send()
            }
        }
    }

    /** The page opened the book, without any mark or selection: it is given the marks, which are read again. */
    fun onOpened(sectionLabels: List<String?>) {
        core.update {
            it.copy(sectionLabels = sectionLabels, selection = null, pageBookmarks = emptyList(), ribbonHidden = false)
        }
        sentHighlights = emptyList()
        sentBookmarks = emptyList()
        send()
        val fileId = core.fileId ?: return
        if (refreshedFile == fileId) return
        refreshedFile = fileId
        refresh(force = true)
    }

    fun onMessage(message: PageMessage.Marking) = when (message) {
        is PageMessage.Selection -> selection.onSelection(message)
        PageMessage.SelectionCleared -> selection.onSelectionCleared()
        is PageMessage.HighlightTap -> onHighlightTap(message)
    }

    /** A tap on a highlight the page draws. */
    private fun onHighlightTap(message: PageMessage.HighlightTap) {
        val highlight = core.current.marks.highlights.firstOrNull { it.id.value == message.id } ?: return
        panel.open(highlight.toPanel(focusNote = false))
    }

    fun onResumed() = refresh(force = false)

    /** The panel of contents, bookmarks and notes was opened. */
    fun onPanelOpened() = refresh(force = false)

    /** The system's back: the highlight's panel, then the selection. False when neither is there. */
    fun onBack(): Boolean = panel.onBack() || selection.onBack()

    /** Never without annotations (spec 2c): the source holds no marks to read. */
    private fun refresh(force: Boolean) {
        if (!core.capabilities.annotations) return
        val fileId = core.fileId ?: return
        core.scope.launch { marks.refresh(core.bookId, fileId, force) }
    }

    /** What changed since the page was last given the marks, once it shows the book. */
    private fun send() {
        if (sentHighlights == null) return
        val held = core.current.marks
        val highlights = held.highlights.mapNotNull { it.toPage() }
        if (highlights != sentHighlights && core.driver.send(HostMessage.SetHighlights(highlights))) {
            sentHighlights = highlights
        }
        val bookmarks = held.bookmarks.mapNotNull { it.toPage() }
        if (bookmarks != sentBookmarks && core.driver.send(HostMessage.SetBookmarks(bookmarks))) {
            sentBookmarks = bookmarks
        }
    }
}

/**
 * The writes to the marks of book [bookId], one after the other in the order they were asked: an
 * undo never overtakes the deletion it undoes. They run in [scope], which outlives the reader: a
 * write asked as it closes is made all the same.
 */
internal class MarkWrites(private val scope: CoroutineScope, private val marks: Marks, private val bookId: BookId) {
    private val order = Mutex()

    fun launch(write: suspend Marks.(bookId: BookId) -> Unit) {
        scope.launch {
            order.withLock {
                // A write that fails leaves the marks as they were; the reader goes on, and so do the next writes.
                runCatching { marks.write(bookId) }.onFailure { if (it is CancellationException) throw it }
            }
        }
    }
}

/** The panel of a highlight as it is held. */
internal fun Highlight.toPanel(focusNote: Boolean) = HighlightPanelUi(
    id = id,
    text = text,
    color = HighlightColour.display(color),
    note = note.orEmpty(),
    draft = note.orEmpty(),
    focusNote = focusNote,
    confirmDiscard = false,
)

/** Null for a highlight the page cannot place. "Invert" is drawn as a highlight. */
private fun Highlight.toPage(): PageHighlight? {
    val placed = cfi ?: return null
    val style = when (style) {
        HighlightStyle.Highlight, HighlightStyle.Invert -> PageHighlightStyle.Highlight
        HighlightStyle.Underline -> PageHighlightStyle.Underline
        HighlightStyle.Strikethrough -> PageHighlightStyle.Strikethrough
        HighlightStyle.Squiggly -> PageHighlightStyle.Squiggly
    }
    return PageHighlight(id.value, placed, HighlightColour.display(color), style, note = !note.isNullOrBlank())
}

private fun Bookmark.toPage(): PageBookmark? = cfi?.let { PageBookmark(id.value, it) }
