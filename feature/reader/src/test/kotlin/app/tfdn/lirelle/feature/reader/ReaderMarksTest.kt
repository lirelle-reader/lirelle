package app.tfdn.lirelle.feature.reader

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.HighlightStyle
import app.tfdn.lirelle.core.library.model.NewHighlight
import app.tfdn.lirelle.core.reader.marks.BookMarks
import app.tfdn.lirelle.core.reader.marks.HighlightColour
import app.tfdn.lirelle.core.reader.marks.MarkId
import app.tfdn.lirelle.core.testing.MainDispatcherRule
import app.tfdn.lirelle.core.testing.sampleBookmark
import app.tfdn.lirelle.core.testing.sampleHighlight
import app.tfdn.lirelle.core.testing.set
import app.tfdn.lirelle.feature.reader.bridge.FakeReaderEngine
import app.tfdn.lirelle.feature.reader.bridge.HostMessage
import app.tfdn.lirelle.feature.reader.bridge.PageBookmark
import app.tfdn.lirelle.feature.reader.bridge.PageBounds
import app.tfdn.lirelle.feature.reader.bridge.PageDirection
import app.tfdn.lirelle.feature.reader.bridge.PageHighlight
import app.tfdn.lirelle.feature.reader.bridge.PageHighlightStyle
import app.tfdn.lirelle.feature.reader.bridge.PageMessage
import app.tfdn.lirelle.feature.reader.text.TextAction
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private const val RangeCfi = "epubcfi(/6/4!/4/2,/1:0,/1:15)"
private const val OtherRangeCfi = "epubcfi(/6/4!/4/10,/1:3,/1:20)"
private const val FarRangeCfi = "epubcfi(/6/8!/4/2,/1:0,/1:9)"
private const val Text = "A fine sentence"
private val Bounds = PageBounds(left = 0.1, top = 0.2, right = 0.6, bottom = 0.3)
private val Labels = listOf(null, "Chapter one", "Chapter two")

/** Selection, highlights, notes and bookmarks, as the view model keeps and sends them. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderMarksTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    @get:Rule
    val folder = TemporaryFolder()

    private fun TestScope.harness() = ReaderHarness(this, folder.root)

    private fun selection(exactId: String? = null, text: String = Text, cfi: String = RangeCfi) = PageMessage.Selection(
        cfi = cfi,
        text = text,
        truncated = false,
        sectionIndex = 1,
        bounds = Bounds,
        highlightIds = listOfNotNull(exactId),
        exactId = exactId,
    )

    private val liked = sampleHighlight(id = "h1", cfi = RangeCfi, text = Text, note = "Saved note")

    /** A reader with [liked] among the book's marks, whose page shows the book. */
    private fun ReaderHarness.readingWithHighlight(): ReaderViewModel {
        marks.set(BookId("12"), BookMarks(highlights = listOf(liked)))
        return reading(sectionLabels = Labels)
    }

    // ---------------------------------------------------------------- sending the marks

    @Test
    fun `once the page shows the book, the marks are sent as it draws them`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        harness.marks.set(
            BookId("12"),
            BookMarks(
                bookmarks = listOf(sampleBookmark("b1", StartCfi), sampleBookmark("b2", cfi = null)),
                highlights = listOf(
                    sampleHighlight("h1", RangeCfi, color = "yellow", note = "A note")
                        .copy(style = HighlightStyle.Invert),
                    sampleHighlight("h2", cfi = null),
                    sampleHighlight("h3", OtherRangeCfi, color = "#4ade80").copy(style = HighlightStyle.Underline),
                ),
            ),
        )

        val viewModel = harness.reading()

        assertThat(harness.engine.sent).containsAtLeast(
            HostMessage.SetHighlights(
                listOf(
                    PageHighlight("h1", RangeCfi, "#FACC15", PageHighlightStyle.Highlight, note = true),
                    PageHighlight("h3", OtherRangeCfi, "#4ADE80", PageHighlightStyle.Underline, note = false),
                ),
            ),
            HostMessage.SetBookmarks(listOf(PageBookmark("b1", StartCfi))),
        )
        assertThat(viewModel.state.value.marks.highlights.map { it.id.value }).containsExactly("h1", "h2", "h3")
    }

    @Test
    fun `the marks are sent again when they change, and only what changed`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        harness.reading()
        harness.engine.sent.clear()

        harness.marks.set(BookId("12"), BookMarks(bookmarks = listOf(sampleBookmark("b1", SecondCfi))))

        assertThat(harness.engine.sent).containsExactly(HostMessage.SetBookmarks(listOf(PageBookmark("b1", SecondCfi))))
    }

    @Test
    fun `nothing is sent before the page shows the book, everything once it does`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.viewModel()
        harness.marks.set(BookId("12"), BookMarks(bookmarks = listOf(sampleBookmark("b1", SecondCfi))))
        viewModel.onEngine(harness.engine)
        harness.engine.emit(PageMessage.Ready)
        assertThat(harness.engine.sent.filterIsInstance<HostMessage.SetBookmarks>()).isEmpty()

        harness.engine.emit(PageMessage.Opened(SampleToc, PageDirection.Ltr, fixedLayout = false, title = null))

        assertThat(harness.engine.sent).contains(HostMessage.SetBookmarks(listOf(PageBookmark("b1", SecondCfi))))
    }

    @Test
    fun `a page opened again is sent the marks again`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        harness.marks.set(BookId("12"), BookMarks(bookmarks = listOf(sampleBookmark("b1", SecondCfi))))
        harness.reading()
        harness.engine.sent.clear()

        harness.engine.emit(PageMessage.Opened(SampleToc, PageDirection.Ltr, fixedLayout = false, title = null))

        assertThat(harness.engine.sent).contains(HostMessage.SetBookmarks(listOf(PageBookmark("b1", SecondCfi))))
    }

    @Test
    fun `a new web view is given the marks once it opened the book`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        harness.marks.set(BookId("12"), BookMarks(bookmarks = listOf(sampleBookmark("b1", SecondCfi))))
        val viewModel = harness.reading()
        val second = FakeReaderEngine(bookBase = "/book/second/")

        viewModel.onEngine(second)
        second.emit(PageMessage.Ready)
        assertThat(second.sent.filterIsInstance<HostMessage.SetBookmarks>()).isEmpty()
        second.emit(PageMessage.Opened(SampleToc, PageDirection.Ltr, fixedLayout = false, title = null))

        assertThat(second.sent).contains(HostMessage.SetBookmarks(listOf(PageBookmark("b1", SecondCfi))))
    }

    @Test
    fun `the marks are read from the server when the book opens, and again later when it is cheap`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            val viewModel = harness.reading()
            assertThat(harness.marks.calls).containsExactly("refresh 12 1 force=true")

            harness.engine.emit(PageMessage.Opened(SampleToc, PageDirection.Ltr, fixedLayout = false, title = null))
            viewModel.onResumed()
            viewModel.showSheet(ReaderSheet.Contents)
            viewModel.showSheet(ReaderSheet.Settings)

            assertThat(harness.marks.calls).containsExactly(
                "refresh 12 1 force=true",
                "refresh 12 1 force=false",
                "refresh 12 1 force=false",
            ).inOrder()
        }

    // ---------------------------------------------------------------- selection

    @Test
    fun `a selection is shown with what can be done with it, until it is cleared`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.readingWithHighlight()

        harness.engine.emit(selection(exactId = "h1"))

        assertThat(viewModel.state.value.selection)
            .isEqualTo(SelectionUi(RangeCfi, Text, sectionIndex = 1, bounds = Bounds, exactId = MarkId("h1")))
        assertThat(viewModel.state.value.textActions).isEqualTo(harness.textActions.offered)

        harness.engine.emit(PageMessage.SelectionCleared)

        assertThat(viewModel.state.value.selection).isNull()
    }

    @Test
    fun `a colour dot highlights the selection, then clears it and keeps the colour`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading(sectionLabels = Labels)
        harness.engine.emit(selection())

        viewModel.highlightSelection(HighlightColour.Green.hex)

        assertThat(harness.marks.calls).contains(
            "addHighlight 12 " +
                NewHighlight(RangeCfi, FileId("1"), Text, "#4ADE80", note = null, chapterTitle = "Chapter one"),
        )
        assertThat(harness.engine.sent).contains(HostMessage.ClearSelection)
        assertThat(viewModel.state.value.selection).isNull()
        assertThat(harness.settings.current.lastHighlightColour).isEqualTo("#4ADE80")
        assertThat(harness.engine.sent.filterIsInstance<HostMessage.SetHighlights>().last().items.map { it.cfi })
            .containsExactly(RangeCfi)
    }

    @Test
    fun `a colour dot on an exact highlight recolours it`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.readingWithHighlight()
        harness.engine.emit(selection(exactId = "h1"))

        viewModel.highlightSelection(HighlightColour.Blue.hex)

        assertThat(harness.marks.calls.filterNot { it.startsWith("refresh") }).containsExactly("recolour 12 h1 #38BDF8")
        assertThat(harness.engine.sent).contains(HostMessage.ClearSelection)
        assertThat(viewModel.state.value.selection).isNull()
    }

    @Test
    fun `note highlights in the last colour used and opens its panel on the note`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        harness.settings.update { it.copy(lastHighlightColour = HighlightColour.Pink.hex) }
        val viewModel = harness.reading(sectionLabels = Labels)
        harness.engine.emit(selection())

        viewModel.noteOnSelection()

        assertThat(harness.marks.calls).contains(
            "addHighlight 12 " +
                NewHighlight(RangeCfi, FileId("1"), Text, "#F472B6", note = null, chapterTitle = "Chapter one"),
        )
        assertThat(viewModel.state.value.highlightPanel).isEqualTo(
            HighlightPanelUi(MarkId("local-1"), Text, "#F472B6", "", "", focusNote = true, confirmDiscard = false),
        )
        assertThat(viewModel.state.value.selection).isNull()
        assertThat(harness.engine.sent).contains(HostMessage.ClearSelection)
    }

    @Test
    fun `note on an exact highlight opens that one's panel`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.readingWithHighlight()
        harness.engine.emit(selection(exactId = "h1"))

        viewModel.noteOnSelection()

        assertThat(harness.marks.calls.filterNot { it.startsWith("refresh") }).isEmpty()
        assertThat(viewModel.state.value.highlightPanel).isEqualTo(
            HighlightPanelUi(
                MarkId("h1"),
                Text,
                "#FACC15",
                "Saved note",
                "Saved note",
                focusNote = true,
                confirmDiscard = false,
            ),
        )
    }

    @Test
    fun `an exact highlight the marks no longer hold is never made twice`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading(sectionLabels = Labels)

        harness.engine.emit(selection(exactId = "h9"))
        viewModel.highlightSelection(HighlightColour.Blue.hex)
        harness.engine.emit(selection(exactId = "h9"))
        viewModel.noteOnSelection()

        assertThat(harness.marks.calls.filterNot { it.startsWith("refresh") }).containsExactly("recolour 12 h9 #38BDF8")
        assertThat(viewModel.state.value.highlightPanel).isNull()
        assertThat(viewModel.state.value.selection).isNull()
    }

    @Test
    fun `a selection whose place is too long for the server is never highlighted`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading(sectionLabels = Labels)
        val long = "epubcfi(/6/4!/4/2,/1:0,/1:15)".padEnd(2_001, '0')

        harness.engine.emit(selection(cfi = long))
        viewModel.highlightSelection(HighlightColour.Green.hex)
        harness.engine.emit(selection(cfi = long))
        viewModel.noteOnSelection()

        assertThat(harness.marks.calls.filterNot { it.startsWith("refresh") }).isEmpty()
        assertThat(viewModel.state.value.selection).isNull()
        assertThat(viewModel.state.value.highlightPanel).isNull()
        assertThat(harness.engine.sent.count { it == HostMessage.ClearSelection }).isEqualTo(2)
    }

    @Test
    fun `a selection whose place is at the server's limit is highlighted`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading(sectionLabels = Labels)
        val long = "epubcfi(/6/4!/4/2,/1:0,/1:15)".padEnd(2_000, '0')

        harness.engine.emit(selection(cfi = long))
        viewModel.highlightSelection(HighlightColour.Green.hex)

        assertThat(harness.marks.calls.filter { it.startsWith("addHighlight") }).hasSize(1)
    }

    @Test
    fun `what the device offers is asked when a selection starts, not while it is extended`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            harness.reading()

            harness.engine.emit(selection())
            harness.engine.emit(selection(text = "A fine sentence, longer"))
            assertThat(harness.textActions.asked).isEqualTo(1)

            harness.engine.emit(PageMessage.SelectionCleared)
            harness.engine.emit(selection())
            assertThat(harness.textActions.asked).isEqualTo(2)
        }

    @Test
    fun `copy and share hand the selected text over and clear the selection`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()

        harness.engine.emit(selection())
        viewModel.selectionAction(TextAction.Share)
        harness.engine.emit(selection(text = "Another"))
        viewModel.selectionAction(TextAction.Copy)

        assertThat(harness.textActions.performed)
            .containsExactly(TextAction.Share to Text, TextAction.Copy to "Another").inOrder()
        assertThat(harness.engine.sent.count { it == HostMessage.ClearSelection }).isEqualTo(2)
        assertThat(viewModel.state.value.selection).isNull()
    }

    @Test
    fun `translate, define, a web search and other apps keep the selection and its bar`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            val viewModel = harness.reading()
            val dictionary = TextAction.App("Dictionary", "org.example.dict", "org.example.dict.Look")
            harness.engine.emit(selection())
            harness.engine.sent.clear()

            viewModel.selectionAction(TextAction.Translate)
            viewModel.selectionAction(TextAction.Define)
            viewModel.selectionAction(TextAction.SearchWeb)
            viewModel.selectionAction(dictionary)

            assertThat(harness.textActions.performed.map { it.first })
                .containsExactly(TextAction.Translate, TextAction.Define, TextAction.SearchWeb, dictionary)
                .inOrder()
            assertThat(harness.textActions.performed.map { it.second }.distinct()).containsExactly(Text)
            assertThat(harness.engine.sent).doesNotContain(HostMessage.ClearSelection)
            assertThat(viewModel.state.value.selection?.text).isEqualTo(Text)
        }

    @Test
    fun `search clears the selection`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()
        harness.engine.emit(selection())

        viewModel.searchSelection()

        assertThat(viewModel.state.value.selection).isNull()
        assertThat(harness.engine.sent).contains(HostMessage.ClearSelection)
    }

    @Test
    fun `without a selection the bar's actions do nothing`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()
        harness.engine.sent.clear()

        viewModel.highlightSelection(HighlightColour.Green.hex)
        viewModel.noteOnSelection()
        viewModel.selectionAction(TextAction.Share)

        assertThat(harness.marks.calls.filterNot { it.startsWith("refresh") }).isEmpty()
        assertThat(harness.textActions.performed).isEmpty()
        assertThat(harness.engine.sent).isEmpty()
        assertThat(viewModel.state.value.highlightPanel).isNull()
    }

    // ---------------------------------------------------------------- the highlight's panel

    @Test
    fun `a tap on a highlight opens its panel`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.readingWithHighlight()

        harness.engine.emit(PageMessage.HighlightTap("h1", Bounds))

        assertThat(viewModel.state.value.highlightPanel).isEqualTo(
            HighlightPanelUi(
                MarkId("h1"),
                Text,
                "#FACC15",
                "Saved note",
                "Saved note",
                focusNote = false,
                confirmDiscard = false,
            ),
        )

        harness.engine.emit(PageMessage.HighlightTap("unknown", Bounds))
        assertThat(viewModel.state.value.highlightPanel?.id).isEqualTo(MarkId("h1"))
    }

    @Test
    fun `a colour chosen in the panel is applied at once`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.readingWithHighlight()
        harness.engine.emit(PageMessage.HighlightTap("h1", Bounds))

        viewModel.setHighlightColour(HighlightColour.Pink.hex)

        assertThat(harness.marks.calls).contains("recolour 12 h1 #F472B6")
        assertThat(viewModel.state.value.highlightPanel?.color).isEqualTo("#F472B6")
        assertThat(harness.settings.current.lastHighlightColour).isEqualTo("#F472B6")
    }

    @Test
    fun `save keeps the note and closes the panel`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.readingWithHighlight()
        harness.engine.emit(PageMessage.HighlightTap("h1", Bounds))

        viewModel.editNote("A better note ")
        viewModel.saveNote()

        assertThat(harness.marks.calls).contains("setNote 12 h1 A better note")
        assertThat(viewModel.state.value.highlightPanel).isNull()
    }

    @Test
    fun `a note emptied and saved is removed`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.readingWithHighlight()
        harness.engine.emit(PageMessage.HighlightTap("h1", Bounds))

        viewModel.editNote("  ")
        viewModel.saveNote()

        assertThat(harness.marks.calls).contains("setNote 12 h1 null")
    }

    @Test
    fun `a note is at most 4000 characters, an edit past them is refused whole`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.readingWithHighlight()
        harness.engine.emit(PageMessage.HighlightTap("h1", Bounds))

        viewModel.editNote("x".repeat(4000))
        assertThat(viewModel.state.value.highlightPanel?.draft).hasLength(4000)

        viewModel.editNote("x".repeat(3999) + "\uD83D\uDE00")
        assertThat(viewModel.state.value.highlightPanel?.draft).isEqualTo("x".repeat(4000))
    }

    @Test
    fun `a selection hides the controls, so no bar covers the page under it`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.readingWithHighlight()
        viewModel.toggleControls()
        assertThat(viewModel.state.value.controlsShown).isTrue()

        harness.engine.emit(selection())

        assertThat(viewModel.state.value.controlsShown).isFalse()
    }

    @Test
    fun `volume keys are left alone while the highlight's panel is open`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.readingWithHighlight()
        viewModel.updateSettings { it.copy(volumeKeys = true) }
        assertThat(viewModel.onVolumeKey(volumeDown = true)).isTrue()

        harness.engine.emit(PageMessage.HighlightTap("h1", Bounds))

        assertThat(viewModel.onVolumeKey(volumeDown = true)).isFalse()
    }

    @Test
    fun `closing with a changed note asks first, and discard closes without saving`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.readingWithHighlight()
        harness.engine.emit(PageMessage.HighlightTap("h1", Bounds))
        viewModel.editNote("Unsaved")

        viewModel.closeHighlightPanel()
        assertThat(viewModel.state.value.highlightPanel?.confirmDiscard).isTrue()

        viewModel.keepEditing()
        assertThat(viewModel.state.value.highlightPanel?.confirmDiscard).isFalse()
        assertThat(viewModel.state.value.highlightPanel?.draft).isEqualTo("Unsaved")

        viewModel.closeHighlightPanel()
        viewModel.discardNote()

        assertThat(viewModel.state.value.highlightPanel).isNull()
        assertThat(harness.marks.calls.none { it.startsWith("setNote") }).isTrue()
    }

    @Test
    fun `delete closes the panel and offers an undo, which brings the highlight back`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            val viewModel = harness.readingWithHighlight()
            harness.engine.emit(PageMessage.HighlightTap("h1", Bounds))

            viewModel.deleteHighlight()

            assertThat(viewModel.state.value.highlightPanel).isNull()
            assertThat(harness.marks.calls).contains("deleteHighlight 12 h1")
            assertThat(viewModel.state.value.undo).isEqualTo(UndoUi.Highlight(MarkId("h1")))
            assertThat(harness.engine.sent.filterIsInstance<HostMessage.SetHighlights>().last().items).isEmpty()

            viewModel.undo()

            assertThat(harness.marks.calls.last()).isEqualTo("undoDeleteHighlight 12 h1")
            assertThat(viewModel.state.value.undo).isNull()
            assertThat(harness.engine.sent.filterIsInstance<HostMessage.SetHighlights>().last().items.map { it.id })
                .containsExactly("h1")
        }

    @Test
    fun `the panel's copy hands the highlighted text over`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.readingWithHighlight()
        harness.engine.emit(PageMessage.HighlightTap("h1", Bounds))

        viewModel.selectionAction(TextAction.Copy)

        assertThat(harness.textActions.performed).containsExactly(TextAction.Copy to Text)
        assertThat(viewModel.state.value.highlightPanel).isNotNull()
    }

    // ---------------------------------------------------------------- back, the panel's tabs, jumps

    @Test
    fun `back closes the highlight's panel, asking about a changed note, then the selection`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            val viewModel = harness.readingWithHighlight()
            viewModel.toggleControls()
            harness.engine.emit(PageMessage.HighlightTap("h1", Bounds))
            viewModel.editNote("Unsaved")

            assertThat(viewModel.onBack()).isTrue()
            assertThat(viewModel.state.value.highlightPanel?.confirmDiscard).isTrue()
            assertThat(viewModel.onBack()).isTrue()
            assertThat(viewModel.state.value.highlightPanel?.confirmDiscard).isFalse()
            viewModel.editNote("Saved note")
            assertThat(viewModel.onBack()).isTrue()
            assertThat(viewModel.state.value.highlightPanel).isNull()
            assertThat(viewModel.state.value.controlsShown).isTrue()

            harness.engine.emit(selection())
            assertThat(viewModel.onBack()).isTrue()
            assertThat(viewModel.state.value.selection).isNull()
            assertThat(harness.engine.sent.last()).isEqualTo(HostMessage.ClearSelection)

            // The selection hid the controls: nothing is left to close.
            assertThat(viewModel.state.value.controlsShown).isFalse()
            assertThat(viewModel.onBack()).isFalse()
        }

    @Test
    fun `the panel opens on the tab last used while the book is open`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()
        assertThat(viewModel.state.value.panelTab).isEqualTo(PanelTab.Contents)

        viewModel.showSheet(ReaderSheet.Contents)
        viewModel.showPanelTab(PanelTab.Notes)
        viewModel.dismissSheet()
        viewModel.showSheet(ReaderSheet.Contents)

        assertThat(viewModel.state.value.panelTab).isEqualTo(PanelTab.Notes)
    }

    @Test
    fun `a mark's row jumps there, offers go back and closes the sheet`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()
        viewModel.showSheet(ReaderSheet.Contents)

        viewModel.goToMark(FarRangeCfi)

        assertThat(harness.engine.sent.last()).isEqualTo(HostMessage.GoTo.Cfi(FarRangeCfi))
        assertThat(viewModel.state.value.goBackShown).isTrue()
        assertThat(viewModel.state.value.openSheet).isNull()
    }

    @Test
    fun `a bookmark of the visible page is not jumped to, and the next page turned still counts`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            harness.marks.set(BookId("12"), BookMarks(bookmarks = listOf(sampleBookmark("b1", SecondCfi))))
            val viewModel = harness.reading()
            harness.engine.emit(relocated(cfi = SecondCfi).copy(bookmarkIds = listOf("b1")))
            viewModel.showSheet(ReaderSheet.Contents)
            harness.engine.sent.clear()

            viewModel.goToMark(SecondCfi)

            assertThat(harness.engine.sent.filterIsInstance<HostMessage.GoTo>()).isEmpty()
            assertThat(viewModel.state.value.openSheet).isNull()
            assertThat(viewModel.state.value.goBackShown).isFalse()
            viewModel.toggleControls()
            harness.engine.emit(relocated(cfi = ThirdCfi, fraction = 0.3))
            assertThat(viewModel.state.value.controlsShown).isFalse()
        }

    @Test
    fun `a mark that may be on the visible page is jumped to without go back`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()
        harness.engine.emit(relocated(cfi = SecondCfi, fraction = 0.2))

        // Later in the same section: on this page or a following one.
        viewModel.goToMark("epubcfi(/6/4!/4/12,/1:0,/1:4)")
        assertThat(harness.engine.sent.last()).isEqualTo(HostMessage.GoTo.Cfi("epubcfi(/6/4!/4/12,/1:0,/1:4)"))
        assertThat(viewModel.state.value.goBackShown).isFalse()

        // Earlier in the section: certainly elsewhere.
        viewModel.goToMark(RangeCfi)
        assertThat(viewModel.state.value.goBackShown).isTrue()
    }

    @Test
    fun `a highlight that cannot be placed goes to the start of its chapter`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()
        viewModel.showSheet(ReaderSheet.Contents)

        viewModel.goToChapter(-1)
        assertThat(viewModel.state.value.goBackShown).isFalse()

        viewModel.goToChapter(2)

        assertThat(harness.engine.sent.last()).isEqualTo(HostMessage.GoTo.Cfi("epubcfi(/6/6!/4)"))
        assertThat(viewModel.state.value.goBackShown).isTrue()
        assertThat(viewModel.state.value.openSheet).isNull()
    }

    @Test
    fun `a page opened again has no selection`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()
        harness.engine.emit(selection())

        harness.engine.emit(PageMessage.Opened(SampleToc, PageDirection.Ltr, fixedLayout = false, title = null))

        assertThat(viewModel.state.value.selection).isNull()
    }
}
