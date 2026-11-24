package app.tfdn.lirelle.feature.reader

import app.tfdn.lirelle.core.reader.marks.CfiOrder
import app.tfdn.lirelle.core.reader.marks.MarkId
import app.tfdn.lirelle.core.reader.marks.spineIndexOf
import app.tfdn.lirelle.feature.reader.bridge.HostMessage
import kotlinx.coroutines.CompletableDeferred

/** The package's spine is its third child: spine item `N` is at step `/6/(2N + 2)`. */
private const val StepsPerItem = 2

/** The bookmarks of the visible page, the undo of a deletion, the panel's tabs and its rows. */
internal class BookmarkPart(private val core: ReaderCore, private val writes: MarkWrites) : MarkListActions {

    /** The bookmark being added, until it is made: a toggle meanwhile removes it once it is. */
    private var adding: CompletableDeferred<MarkId>? = null

    override fun toggleBookmark() {
        if (!core.driver.opened) return
        val pending = adding
        val here = core.current.pageBookmarks
        when {
            pending != null -> removeOnceMade(pending)
            here.isEmpty() -> add()
            else -> remove(here)
        }
    }

    override fun deleteBookmark(id: MarkId) = remove(listOf(id))

    override fun undo() {
        val undo = core.current.undo ?: return
        core.update { it.copy(undo = null) }
        writes.launch { bookId ->
            when (undo) {
                is UndoUi.Highlight -> undoDeleteHighlight(bookId, undo.id)
                is UndoUi.Bookmarks -> undoDeleteBookmarks(bookId, undo.ids)
            }
        }
    }

    override fun dismissUndo(offer: UndoUi) = core.update { if (it.undo == offer) it.copy(undo = null) else it }

    override fun showPanelTab(tab: PanelTab) = core.update { it.copy(panelTab = tab) }

    /**
     * A bookmark of the visible page is not jumped to: the page would not move, and the jump would
     * stay waited for. A place that may be on the visible page is jumped to without "Go back".
     */
    override fun goToMark(cfi: String) {
        if (cfi.isBlank()) return
        core.update { it.copy(openSheet = null) }
        val state = core.current
        val onPage = cfi == core.lastCfi ||
            state.marks.bookmarks.any { it.cfi == cfi && it.id in state.pageBookmarks }
        if (onPage) return
        core.jump(HostMessage.GoTo.Cfi(cfi), remember = !mayBeOnPage(cfi, core.lastCfi))
    }

    override fun goToChapter(chapterIndex: Int) {
        if (chapterIndex < 0) return
        goToMark(sectionStartCfi(chapterIndex))
    }

    /** At the start of the visible page, titled with its chapter, or with how far into the book it is. */
    private fun add() {
        val cfi = core.lastCfi ?: return
        val fileId = core.fileId ?: return
        val location = core.current.location
        val title = location?.chapter?.takeIf { it.isNotBlank() }
            ?: core.markLabels.bookmarkTitle(location?.percent ?: 0)
        val made = CompletableDeferred<MarkId>()
        adding = made
        writes.launch { bookId ->
            val result = runCatching { addBookmark(bookId, fileId, cfi, title) }
            val ours = adding === made
            if (ours) adding = null
            val id = result.onFailure { made.completeExceptionally(it) }.getOrThrow()
            made.complete(id)
            if (!ours) return@launch
            // The page says which bookmarks it holds; until it has, the one just added is there.
            if (core.lastCfi == cfi) core.update { it.copy(pageBookmarks = listOf(id)) }
        }
    }

    /** The bookmark still being added is removed as soon as it is made, with "Undo". */
    private fun removeOnceMade(pending: CompletableDeferred<MarkId>) {
        adding = null
        writes.launch { bookId ->
            val id = pending.await()
            deleteBookmarks(bookId, listOf(id))
            core.update { it.copy(pageBookmarks = it.pageBookmarks - id, undo = UndoUi.Bookmarks(listOf(id))) }
        }
    }

    private fun remove(ids: List<MarkId>) {
        core.update { it.copy(pageBookmarks = it.pageBookmarks - ids.toSet(), undo = UndoUi.Bookmarks(ids)) }
        writes.launch { deleteBookmarks(it, ids) }
    }
}

/** The start of the section at [index] of the spine. */
internal fun sectionStartCfi(index: Int): String = "epubcfi(/6/${StepsPerItem * (index + 1)}!/4)"

/**
 * Whether [cfi] may be on the page that starts at [pageStart]: later in the same section, it is
 * there or on a following page, which only the page could tell. Earlier, or in another section,
 * it is elsewhere.
 */
private fun mayBeOnPage(cfi: String, pageStart: String?): Boolean {
    if (pageStart == null) return false
    val section = spineIndexOf(cfi) ?: return false
    return section == spineIndexOf(pageStart) && CfiOrder.compare(cfi, pageStart) >= 0
}
