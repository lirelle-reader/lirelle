package app.tfdn.lirelle.feature.reader

import app.tfdn.lirelle.core.audio.narration.SkippedNotice
import app.tfdn.lirelle.core.reader.marks.MarkId
import app.tfdn.lirelle.core.reader.settings.ReaderSettings
import app.tfdn.lirelle.feature.reader.bridge.TocEntry
import app.tfdn.lirelle.feature.reader.text.TextAction

/** Notes what the screen asks for, as `name` or `name(argument)`, oldest first. */
class RecordingActions : ReaderActions {
    val calls = mutableListOf<String>()
    var settings = ReaderSettings()

    override fun nextChapter() = note("nextChapter")
    override fun previousChapter() = note("previousChapter")
    override fun nextPage() = note("nextPage")
    override fun previousPage() = note("previousPage")
    override fun goToToc(entry: TocEntry) = note("goToToc(${entry.id})")
    override fun seekTo(fraction: Double) = note("seekTo($fraction)")
    override fun goBack() = note("goBack")
    override fun toggleControls() = note("toggleControls")
    override fun showSheet(sheet: ReaderSheet) = note("showSheet($sheet)")
    override fun dismissSheet() = note("dismissSheet")
    override fun updateSettings(transform: (ReaderSettings) -> ReaderSettings) {
        settings = transform(settings)
        note("updateSettings")
    }
    override fun setBrightness(level: Float) = note("setBrightness($level)")
    override fun keepBrightness() = note("keepBrightness")
    override fun retry() = note("retry")
    override fun leave() = note("leave")
    override fun acceptNewerPosition() = note("acceptNewerPosition")
    override fun ignoreNewerPosition() = note("ignoreNewerPosition")
    override fun confirmExternalLink() = note("confirmExternalLink")
    override fun dismissExternalLink() = note("dismissExternalLink")
    override fun setRating(stars: Int?) = note("setRating($stars)")
    override fun closeEndPanel() = note("closeEndPanel")
    override fun dismissEndPanel() = note("dismissEndPanel")
    override fun openNextVolume() = note("openNextVolume")
    override fun deleteFromDevice() = note("deleteFromDevice")
    override fun keepOnDevice() = note("keepOnDevice")
    override fun highlightSelection(color: String) = note("highlightSelection($color)")
    override fun noteOnSelection() = note("noteOnSelection")
    override fun selectionAction(action: TextAction) = note("selectionAction($action)")
    override fun searchSelection() = note("searchSelection")
    override fun setHighlightColour(color: String) = note("setHighlightColour($color)")
    override fun editNote(draft: String) = note("editNote($draft)")
    override fun saveNote() = note("saveNote")
    override fun deleteHighlight() = note("deleteHighlight")
    override fun closeHighlightPanel() = note("closeHighlightPanel")
    override fun discardNote() = note("discardNote")
    override fun keepEditing() = note("keepEditing")
    override fun toggleBookmark() = note("toggleBookmark")
    override fun deleteBookmark(id: MarkId) = note("deleteBookmark(${id.value})")
    override fun undo() = note("undo")
    override fun dismissUndo(offer: UndoUi) = note("dismissUndo($offer)")
    override fun showPanelTab(tab: PanelTab) = note("showPanelTab($tab)")
    override fun goToMark(cfi: String) = note("goToMark($cfi)")
    override fun goToChapter(chapterIndex: Int) = note("goToChapter($chapterIndex)")
    override fun openSearch() = note("openSearch")
    override fun editQuery(query: String) = note("editQuery($query)")
    override fun submitQuery() = note("submitQuery")
    override fun showResult(searchId: Int, index: Int) = note("showResult($searchId, $index)")
    override fun nextResult() = note("nextResult")
    override fun previousResult() = note("previousResult")
    override fun backToResults() = note("backToResults")
    override fun closeSearch() = note("closeSearch")
    override fun startHere() = note("startHere")
    override fun readFromSelection() = note("readFromSelection")
    override fun readThisPage() = note("readThisPage")
    override fun backToSentence() = note("backToSentence")
    override fun finishBook() = note("finishBook")
    override fun readSkipped(notice: SkippedNotice) = note("readSkipped(${notice.sectionIndex})")
    override fun dismissNotice(notice: SkippedNotice) = note("dismissNotice(${notice.sectionIndex})")
    override fun retryNarration() = note("retryNarration")
    override fun stopNarration() = note("stopNarration")
    override fun undoStopNarration() = note("undoStopNarration")

    private fun note(call: String) {
        calls += call
    }
}

fun location(
    chapter: String? = "Two",
    tocId: Int? = 1,
    fraction: Double = 0.426,
    page: Int? = 2,
    pages: Int? = 9,
) = Location(chapter, tocId, fraction, page, pages, atEnd = false)

const val LongTitle = "The remarkably long title of a book that no top bar could ever show whole on a phone"
const val LongChapter = "Chapter twenty-seven, in which a great many things happen one after the other"
const val LongUrl =
    "https://www.example.org/a/very/long/path/that/goes/on/and/on/and/on/index.html?with=parameters&more=1"

/** Six levels deep, with labels that need several lines. */
val DeepToc: List<TocEntry> = (0..11).map { index ->
    TocEntry(
        id = index,
        label = "Part $index, with a label long enough to need more than one line in a narrow sheet",
        href = "part$index.xhtml",
        depth = index % 6,
    )
}
