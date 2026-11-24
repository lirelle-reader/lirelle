package app.tfdn.lirelle.feature.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.reader.marks.BookMarks
import app.tfdn.lirelle.core.reader.marks.HighlightColour
import app.tfdn.lirelle.core.reader.marks.MarkId
import app.tfdn.lirelle.core.reader.settings.ReaderFlow
import app.tfdn.lirelle.core.testing.MainDispatcherRule
import app.tfdn.lirelle.core.testing.sampleBookmark
import app.tfdn.lirelle.core.testing.sampleHighlight
import app.tfdn.lirelle.core.testing.set
import app.tfdn.lirelle.feature.reader.bridge.HostMessage
import app.tfdn.lirelle.feature.reader.bridge.PageBookmark
import app.tfdn.lirelle.feature.reader.bridge.PageBounds
import app.tfdn.lirelle.feature.reader.bridge.PageDirection
import app.tfdn.lirelle.feature.reader.bridge.PageMessage
import com.google.common.truth.Truth.assertThat
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private const val RangeCfi = "epubcfi(/6/4!/4/2,/1:0,/1:15)"
private const val Text = "A fine sentence"
private val Bounds = PageBounds(left = 0.1, top = 0.2, right = 0.6, bottom = 0.3)
private val Labels = listOf(null, "Chapter one", "Chapter two")

/** Bookmarks: the corner, the button, undo, writes held or failed, and the page resending its place. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderBookmarksTest {

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

    // ---------------------------------------------------------------- bookmarks

    @Test
    fun `the bookmarks of the visible page are known from the page`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()

        harness.engine.emit(relocated(cfi = SecondCfi).copy(bookmarkIds = listOf("b1", "b2")))

        assertThat(viewModel.state.value.pageBookmarks).containsExactly(MarkId("b1"), MarkId("b2")).inOrder()
    }

    @Test
    fun `a bookmark is added at the start of the page, titled with its chapter`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()

        viewModel.toggleBookmark()

        assertThat(harness.marks.calls).contains("addBookmark 12 1 $StartCfi One")
        assertThat(viewModel.state.value.pageBookmarks).containsExactly(MarkId("local-1"))
        assertThat(harness.engine.sent.last())
            .isEqualTo(HostMessage.SetBookmarks(listOf(PageBookmark("local-1", StartCfi))))
    }

    @Test
    fun `outside any chapter a bookmark is titled with the percentage, in the app's language`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            val viewModel = harness.reading()
            harness.engine.emit(relocated(cfi = SecondCfi, fraction = 0.376, tocId = null))

            viewModel.toggleBookmark()
            viewModel.onMarkLabels(MarkLabels(bookmarkAt = "Marque-page à %d %%"))
            harness.engine.emit(relocated(cfi = ThirdCfi, fraction = 0.5, tocId = null))
            viewModel.toggleBookmark()

            assertThat(harness.marks.calls.filter { it.startsWith("addBookmark") }).containsExactly(
                "addBookmark 12 1 $SecondCfi Bookmark at 37 %",
                "addBookmark 12 1 $ThirdCfi Marque-page à 50 %",
            ).inOrder()
        }

    @Test
    fun `on a page with bookmarks, every one is removed, with an undo`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val both = listOf(sampleBookmark("b1", SecondCfi), sampleBookmark("b2", SecondCfi))
        harness.marks.set(BookId("12"), BookMarks(bookmarks = both))
        val viewModel = harness.reading()
        harness.engine.emit(relocated(cfi = SecondCfi).copy(bookmarkIds = listOf("b1", "b2")))

        viewModel.toggleBookmark()

        assertThat(harness.marks.calls.last()).isEqualTo("deleteBookmarks 12 [b1, b2]")
        assertThat(viewModel.state.value.undo).isEqualTo(UndoUi.Bookmarks(listOf(MarkId("b1"), MarkId("b2"))))
        assertThat(viewModel.state.value.pageBookmarks).isEmpty()

        viewModel.undo()

        assertThat(harness.marks.calls.last()).isEqualTo("undoDeleteBookmarks 12 [b1, b2]")
        assertThat(viewModel.state.value.undo).isNull()
    }

    @Test
    fun `a second tap before the page answers removes the bookmark just added`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()

        viewModel.toggleBookmark()
        viewModel.toggleBookmark()

        assertThat(harness.marks.calls.filterNot { it.startsWith("refresh") })
            .containsExactly("addBookmark 12 1 $StartCfi One", "deleteBookmarks 12 [local-1]").inOrder()
    }

    @Test
    fun `a second tap while the bookmark is being added removes it once it is made`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            val viewModel = harness.reading()
            val gate = CompletableDeferred<Unit>()
            harness.marks.gate = gate

            viewModel.toggleBookmark()
            viewModel.toggleBookmark()
            assertThat(harness.marks.calls.filterNot { it.startsWith("refresh") })
                .containsExactly("addBookmark 12 1 $StartCfi One")

            gate.complete(Unit)

            assertThat(harness.marks.calls.filterNot { it.startsWith("refresh") })
                .containsExactly("addBookmark 12 1 $StartCfi One", "deleteBookmarks 12 [local-1]").inOrder()
            assertThat(harness.marks.books.value[BookId("12")]?.bookmarks).isEmpty()
            assertThat(viewModel.state.value.pageBookmarks).isEmpty()
            assertThat(viewModel.state.value.undo).isEqualTo(UndoUi.Bookmarks(listOf(MarkId("local-1"))))
        }

    @Test
    fun `a third tap while the first two are written adds the bookmark again`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()
        val gate = CompletableDeferred<Unit>()
        harness.marks.gate = gate

        repeat(3) { viewModel.toggleBookmark() }
        gate.complete(Unit)

        assertThat(harness.marks.calls.filterNot { it.startsWith("refresh") }).containsExactly(
            "addBookmark 12 1 $StartCfi One",
            "deleteBookmarks 12 [local-1]",
            "addBookmark 12 1 $StartCfi One",
        ).inOrder()
        assertThat(harness.marks.books.value[BookId("12")]?.bookmarks?.map { it.id.value }).containsExactly("local-2")
        assertThat(viewModel.state.value.pageBookmarks).containsExactly(MarkId("local-2"))
    }

    @Test
    fun `a write that fails is dropped, and the writes after it are made`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading(sectionLabels = Labels)
        harness.marks.failure = IllegalStateException("disk full")
        harness.engine.emit(selection())

        viewModel.highlightSelection(HighlightColour.Green.hex)
        viewModel.toggleBookmark()
        harness.marks.failure = null
        viewModel.toggleBookmark()

        assertThat(harness.marks.books.value[BookId("12")]?.bookmarks?.map { it.cfi }).containsExactly(StartCfi)
    }

    @Test
    fun `a mark written as the reader closes is written all the same`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val store = ViewModelStore()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = harness.reading() as T
        }
        val viewModel = ViewModelProvider(store, factory)[ReaderViewModel::class.java]
        val gate = CompletableDeferred<Unit>()
        harness.marks.gate = gate

        viewModel.toggleBookmark()
        store.clear()
        gate.complete(Unit)

        assertThat(harness.marks.books.value[BookId("12")]?.bookmarks?.map { it.cfi }).containsExactly(StartCfi)
    }

    @Test
    fun `a bookmark deleted from its row offers an undo, and a newer undo replaces an older one`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            harness.marks.set(BookId("12"), BookMarks(bookmarks = listOf(sampleBookmark("b1", SecondCfi))))
            val viewModel = harness.readingWithHighlight()
            harness.engine.emit(PageMessage.HighlightTap("h1", Bounds))
            viewModel.deleteHighlight()

            viewModel.deleteBookmark(MarkId("b1"))

            assertThat(harness.marks.calls.last()).isEqualTo("deleteBookmarks 12 [b1]")
            assertThat(viewModel.state.value.undo).isEqualTo(UndoUi.Bookmarks(listOf(MarkId("b1"))))

            // The older offer timing out late does not take the newer one away.
            viewModel.dismissUndo(UndoUi.Highlight(MarkId("h1")))
            assertThat(viewModel.state.value.undo).isEqualTo(UndoUi.Bookmarks(listOf(MarkId("b1"))))

            viewModel.dismissUndo(UndoUi.Bookmarks(listOf(MarkId("b1"))))
            assertThat(viewModel.state.value.undo).isNull()
            viewModel.undo()
            assertThat(harness.marks.calls.last()).isEqualTo("deleteBookmarks 12 [b1]")
        }

    @Test
    fun `a tap in the top-right corner toggles the bookmark`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()

        harness.engine.emit(PageMessage.Tap(x = 0.9, y = 0.05))

        assertThat(harness.marks.calls).contains("addBookmark 12 1 $StartCfi One")
        assertThat(harness.engine.sent).doesNotContain(HostMessage.Next)
        assertThat(viewModel.state.value.controlsShown).isFalse()
    }

    @Test
    fun `below the corner, or with the corner off, the right edge turns the page`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        harness.reading()

        harness.engine.emit(PageMessage.Tap(x = 0.9, y = 0.2))
        assertThat(harness.engine.sent.last()).isEqualTo(HostMessage.Next)

        harness.settings.update { it.copy(cornerBookmark = false) }
        harness.engine.emit(PageMessage.Tap(x = 0.9, y = 0.05))

        assertThat(harness.engine.sent.count { it == HostMessage.Next }).isEqualTo(2)
        assertThat(harness.marks.calls.none { it.startsWith("addBookmark") }).isTrue()
    }

    @Test
    fun `in a right-to-left book the corner stays top-right`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        harness.reading(PageDirection.Rtl)

        harness.engine.emit(PageMessage.Tap(x = 0.05, y = 0.05))
        assertThat(harness.engine.sent.last()).isEqualTo(HostMessage.Next)
        assertThat(harness.marks.calls.none { it.startsWith("addBookmark") }).isTrue()

        harness.engine.emit(PageMessage.Tap(x = 0.95, y = 0.05))
        assertThat(harness.marks.calls).contains("addBookmark 12 1 $StartCfi One")
    }

    // ---------------------------------------------------------------- the page resends its place

    @Test
    fun `bookmarks resent with the same place change nothing but the bookmarks`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()
        viewModel.toggleControls()
        viewModel.seekTo(0.5)

        harness.engine.emit(relocated(cfi = StartCfi, fraction = 0.1, moved = false).copy(bookmarkIds = listOf("b1")))
        harness.engine.emit(relocated(cfi = ThirdCfi, fraction = 0.5))

        // The jump's relocation is still known as one: the controls stay.
        assertThat(viewModel.state.value.controlsShown).isTrue()
        assertThat(viewModel.state.value.goBackShown).isTrue()
        assertThat(viewModel.state.value.pageBookmarks).isEmpty()
    }

    @Test
    fun `bookmarks resent while going back do not make the place looked at the position`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            val viewModel = harness.reading()
            harness.engine.emit(relocated(cfi = SecondCfi, fraction = 0.2))
            viewModel.goToToc(SampleToc[1])
            harness.engine.emit(relocated(cfi = ThirdCfi, fraction = 0.3, tocId = 1))

            viewModel.toggleControls()

            viewModel.goBack()
            harness.engine.emit(relocated(cfi = ThirdCfi, fraction = 0.3, tocId = 1).copy(bookmarkIds = listOf("b1")))
            assertThat(viewModel.state.value.pageBookmarks).containsExactly(MarkId("b1"))
            advanceTimeBy(5.seconds)
            harness.engine.emit(relocated(cfi = SecondCfi, fraction = 0.2))
            viewModel.onLeave()

            assertThat(harness.positions.positions[FileId("1")]?.cfi).isEqualTo(SecondCfi)
            assertThat(harness.repository.savedPositions.map { it.cfi }).doesNotContain(ThirdCfi)
            assertThat(viewModel.state.value.goBackShown).isFalse()
            // Coming back is not a page turned.
            assertThat(viewModel.state.value.controlsShown).isTrue()
        }

    @Test
    fun `bookmarks resent before a link's relocation leave it a jump`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()
        harness.engine.emit(relocated(cfi = SecondCfi, fraction = 0.2))
        viewModel.toggleControls()

        harness.engine.emit(PageMessage.Link(href = "two.xhtml#note", external = false))
        harness.engine.emit(relocated(cfi = SecondCfi, fraction = 0.2).copy(bookmarkIds = listOf("b1")))
        harness.engine.emit(relocated(cfi = ThirdCfi, fraction = 0.3))

        assertThat(viewModel.state.value.controlsShown).isTrue()
        assertThat(viewModel.state.value.pageBookmarks).isEmpty()
        // One page turned after the link still offers "Go back": the link was not counted as a turn.
        harness.engine.emit(relocated(cfi = "epubcfi(/6/6!/4/4)", fraction = 0.31))
        assertThat(viewModel.state.value.goBackShown).isTrue()
    }

    // ---------------------------------------------------------------- the ribbon while a page turns

    private val ReaderViewModel.ribbon get() = state.value.ribbonShown

    @Test
    fun `the ribbon goes as soon as the page says a turn is coming, and the next place brings it back`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            val viewModel = harness.reading()
            harness.engine.emit(relocated(cfi = SecondCfi).copy(bookmarkIds = listOf("b1")))

            harness.engine.emit(PageMessage.Turning)
            assertThat(viewModel.ribbon).isFalse()
            // Hidden only: the page's bookmarks are still known, for the button to remove them.
            assertThat(viewModel.state.value.pageBookmarks).containsExactly(MarkId("b1"))

            harness.engine.emit(relocated(cfi = ThirdCfi, fraction = 0.3).copy(bookmarkIds = listOf("b2")))
            assertThat(viewModel.ribbon).isTrue()
            assertThat(viewModel.state.value.pageBookmarks).containsExactly(MarkId("b2"))
        }

    @Test
    fun `a turn asked by the reader leaves the ribbon to the page, which says when it turns`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            val viewModel = harness.reading()
            harness.engine.emit(relocated(cfi = SecondCfi).copy(bookmarkIds = listOf("b1")))

            viewModel.nextPage()
            harness.engine.emit(PageMessage.Tap(x = 0.05, y = 0.5))
            viewModel.updateSettings { it.copy(volumeKeys = true) }
            viewModel.onVolumeKey(volumeDown = true)
            assertThat(viewModel.ribbon).isTrue()
            assertThat(viewModel.state.value.pageBookmarks).containsExactly(MarkId("b1"))

            harness.engine.emit(PageMessage.Turning)
            assertThat(viewModel.ribbon).isFalse()
        }

    @Test
    fun `in scrolled layout, next at the end keeps the ribbon, the page sending no turn nor place`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            val viewModel = harness.reading()
            viewModel.updateSettings { it.copy(flow = ReaderFlow.Scrolled) }
            val last = relocated(cfi = ThirdCfi, fraction = 1.0, atEnd = true)
            harness.engine.emit(last.copy(bookmarkIds = listOf("b1")))

            viewModel.nextPage()
            harness.engine.emit(PageMessage.PastEnd)

            assertThat(viewModel.ribbon).isTrue()
            assertThat(viewModel.state.value.pageBookmarks).containsExactly(MarkId("b1"))
        }

    @Test
    fun `the button during a turn on its way removes the page's bookmark, it does not add another`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            harness.marks.set(BookId("12"), BookMarks(bookmarks = listOf(sampleBookmark("b1", SecondCfi))))
            val viewModel = harness.reading()
            harness.engine.emit(relocated(cfi = SecondCfi).copy(bookmarkIds = listOf("b1")))

            viewModel.nextPage()
            harness.engine.emit(PageMessage.Turning)
            viewModel.toggleBookmark()

            assertThat(harness.marks.calls.filter { it.startsWith("addBookmark") }).isEmpty()
            assertThat(harness.marks.calls.last()).isEqualTo("deleteBookmarks 12 [b1]")
        }

    @Test
    fun `with the corner off, a tap there turns the page, whose announcement hides the ribbon`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            val viewModel = harness.reading()
            viewModel.updateSettings { it.copy(cornerBookmark = false) }
            harness.engine.emit(relocated(cfi = SecondCfi).copy(bookmarkIds = listOf("b1")))

            harness.engine.emit(PageMessage.Tap(x = 0.95, y = 0.05))

            assertThat(harness.engine.sent.last()).isEqualTo(HostMessage.Next)
            assertThat(viewModel.state.value.pageBookmarks).containsExactly(MarkId("b1"))
        }

    @Test
    fun `a turn that could not happen brings the ribbon back with the same place sent again`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            val viewModel = harness.reading()
            val last = relocated(cfi = ThirdCfi, fraction = 1.0, atEnd = true).copy(bookmarkIds = listOf("b1"))
            harness.engine.emit(last)

            harness.engine.emit(PageMessage.Turning)
            harness.engine.emit(PageMessage.PastEnd)
            assertThat(viewModel.ribbon).isFalse()
            harness.engine.emit(last)
            assertThat(viewModel.ribbon).isTrue()
            assertThat(viewModel.state.value.pageBookmarks).containsExactly(MarkId("b1"))
        }
}
