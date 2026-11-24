package app.tfdn.lirelle.feature.reader.narration

import app.tfdn.lirelle.core.audio.narration.SkippedNotice
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.BookFileKind
import app.tfdn.lirelle.core.library.model.NarrationPlace
import app.tfdn.lirelle.core.reader.sync.LocalNarration
import app.tfdn.lirelle.core.testing.MainDispatcherRule
import app.tfdn.lirelle.feature.player.controller.NarrationFailure
import app.tfdn.lirelle.feature.player.service.NarrationStart
import app.tfdn.lirelle.feature.reader.ReaderHarness
import app.tfdn.lirelle.feature.reader.ReaderViewModel
import app.tfdn.lirelle.feature.reader.SampleToc
import app.tfdn.lirelle.feature.reader.SecondCfi
import app.tfdn.lirelle.feature.reader.ThirdCfi
import app.tfdn.lirelle.feature.reader.bridge.HostMessage
import app.tfdn.lirelle.feature.reader.bridge.NarrationTarget
import app.tfdn.lirelle.feature.reader.bridge.PageBounds
import app.tfdn.lirelle.feature.reader.bridge.PageDirection
import app.tfdn.lirelle.feature.reader.bridge.PageMessage
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The reader during a narration of its book: the underline, following, the strip, the starting points. */
class NarrationPartTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    @get:Rule
    val folder = TemporaryFolder()

    private fun TestScope.harness() = ReaderHarness(this, folder.root)

    private fun ReaderHarness.narrationSent(): List<HostMessage> =
        engine.sent.filter { it is HostMessage.NarrationMark || it is HostMessage.NarrationClear }

    private fun ReaderHarness.finds() = engine.sent.filterIsInstance<HostMessage.NarrationFind>()

    private val ReaderViewModel.narration get() = state.value.narration

    private fun mark(section: Int, id: String, paused: Boolean = false, follow: Boolean = true) =
        HostMessage.NarrationMark(section, id, paused, follow)

    // ---------------------------------------------------------------- the underline

    @Test
    fun `the sentence heard is underlined, dotted while paused`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        harness.reading()

        harness.player.hear(0, "a1")
        harness.player.hear(0, "a2")
        harness.player.hear(0, "a2", playing = false)

        assertThat(harness.narrationSent())
            .containsExactly(mark(0, "a1"), mark(0, "a2"), mark(0, "a2", paused = true))
            .inOrder()
    }

    @Test
    fun `a sentence heard before the page shows the book is underlined once it does`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            harness.player.hear(2, "c1", playing = false)

            harness.reading()

            assertThat(harness.narrationSent()).containsExactly(mark(2, "c1", paused = true))
        }

    @Test
    fun `the underline goes when the narration stops, or another book's is heard`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        harness.reading()
        harness.player.hear(0, "a1")

        harness.player.heard.value = null
        harness.player.hear(0, "a2")
        harness.player.hear(0, "x1", bookId = BookId("99"))
        harness.player.hear(0, "x2", fileId = FileId("2"))

        assertThat(harness.narrationSent())
            .containsExactly(mark(0, "a1"), HostMessage.NarrationClear, mark(0, "a2"), HostMessage.NarrationClear)
            .inOrder()
    }

    @Test
    fun `the page opened again is told the sentence again`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        harness.reading()
        harness.player.hear(0, "a1")

        harness.engine.emit(PageMessage.Opened(SampleToc, PageDirection.Ltr, fixedLayout = false, title = null))

        assertThat(harness.narrationSent()).containsExactly(mark(0, "a1"), mark(0, "a1")).inOrder()
    }

    // ---------------------------------------------------------------- following

    @Test
    fun `a page turned by the user stops following, and the strip offers the sentence back`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            val viewModel = harness.reading()
            harness.player.hear(0, "a1")

            harness.engine.emit(PageMessage.Turning)
            harness.player.hear(0, "a2")

            assertThat(viewModel.narration.following).isEqualTo(Following.Off)
            assertThat(viewModel.narration.strip).isEqualTo(StripUi.BackOnly)
            assertThat(harness.narrationSent().last()).isEqualTo(mark(0, "a2", follow = false))
        }

    @Test
    fun `a move the user made stops following, one the narration made does not`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()
        harness.player.hear(0, "a1")

        harness.engine.emit(pageAt(cfi = SecondCfi, byNarration = true))

        assertThat(viewModel.narration.following).isEqualTo(Following.On)

        harness.engine.emit(pageAt(cfi = ThirdCfi))

        assertThat(viewModel.narration.following).isEqualTo(Following.Off)
    }

    @Test
    fun `a place the user did not move to, as when the bar comes under the page, does not stop following`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            val viewModel = harness.reading()
            harness.engine.emit(PageMessage.Turning)
            harness.engine.emit(pageAt(cfi = SecondCfi))
            viewModel.startHere()
            harness.engine.emit(PageMessage.NarrationFound(harness.finds().single().requestId, 0, "a2"))
            harness.player.hear(0, "a2")

            // The page shrinks for the narration's bar and lays itself out again, elsewhere.
            harness.engine.emit(pageAt(cfi = ThirdCfi).copy(userMove = false))

            assertThat(viewModel.narration.following).isEqualTo(Following.On)
            assertThat(viewModel.narration.strip).isNull()
        }

    @Test
    fun `the contents, the slider, a bookmark, a page button and a footnote stop following`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            val viewModel = harness.reading()
            harness.player.hear(0, "a1")
            val navigations = listOf<() -> Unit>(
                { viewModel.goToToc(SampleToc[1]) },
                { viewModel.seekTo(0.5) },
                { viewModel.goToMark(ThirdCfi) },
                { viewModel.nextPage() },
                { harness.engine.emit(PageMessage.Link("two.xhtml#note", external = false)) },
            )

            navigations.forEachIndexed { index, navigate ->
                viewModel.backToSentence()
                navigate()

                assertWithMessage("navigation $index").that(viewModel.narration.following).isEqualTo(Following.Off)
            }
        }

    @Test
    fun `back in front while following, the page is told again to go to the sentence heard`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            val viewModel = harness.reading()
            harness.player.hear(0, "a1")
            viewModel.onPaused()

            viewModel.onResumed()

            assertThat(harness.narrationSent()).containsExactly(mark(0, "a1"), mark(0, "a1")).inOrder()
        }

    @Test
    fun `back in front while not following, nothing is told again`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()
        harness.player.hear(0, "a1")
        harness.engine.emit(PageMessage.Turning)
        val before = harness.narrationSent().size

        viewModel.onResumed()

        assertThat(harness.narrationSent()).hasSize(before)
    }

    @Test
    fun `a move the narration made neither hides the controls nor ends going back`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()
        harness.player.hear(0, "a1")
        viewModel.toggleControls()

        harness.engine.emit(pageAt(cfi = SecondCfi, byNarration = true))

        assertThat(viewModel.state.value.controlsShown).isTrue()
    }

    @Test
    fun `back to the sentence follows again, and the page is told to go there`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()
        harness.player.hear(0, "a1")
        harness.engine.emit(PageMessage.Turning)

        viewModel.backToSentence()

        assertThat(viewModel.narration.following).isEqualTo(Following.On)
        assertThat(viewModel.narration.strip).isNull()
        assertThat(harness.narrationSent().last()).isEqualTo(mark(0, "a1"))
    }

    @Test
    fun `the sentence back on the page shown follows again by itself`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()
        harness.player.hear(0, "a1")
        harness.engine.emit(PageMessage.Turning)

        harness.engine.emit(PageMessage.NarrationVisible(true))

        assertThat(viewModel.narration.following).isEqualTo(Following.On)
        assertThat(viewModel.narration.sentenceOnPage).isTrue()
    }

    @Test
    fun `paused with the sentence off the page, the strip also offers to read this page`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            val viewModel = harness.reading()
            harness.player.hear(0, "a1", playing = false)

            harness.engine.emit(PageMessage.NarrationVisible(false))

            assertThat(viewModel.narration.sentenceOnPage).isFalse()
            assertThat(viewModel.narration.strip).isEqualTo(StripUi.BackAndReadPage)

            harness.engine.emit(PageMessage.NarrationVisible(true))

            assertThat(viewModel.narration.strip).isNull()
        }

    @Test
    fun `once the narration ended no strip shows, and a narration started again has its ways back`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            val viewModel = harness.reading()
            harness.player.hear(2, "c2", playing = false)
            harness.player.narration.value = narrationUi(playing = false, finished = true)

            harness.engine.emit(PageMessage.Turning)
            harness.engine.emit(PageMessage.NarrationVisible(false))

            assertThat(viewModel.narration.ended).isTrue()
            assertThat(viewModel.narration.strip).isNull()

            harness.player.narration.value = narrationUi(playing = false)
            harness.player.hear(0, "a1", playing = false)
            harness.engine.emit(PageMessage.NarrationVisible(false))

            assertThat(viewModel.narration.strip).isEqualTo(StripUi.BackAndReadPage)
        }

    @Test
    fun `play after a pause follows again`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()
        harness.player.hear(0, "a1", playing = false)
        harness.engine.emit(PageMessage.Turning)

        harness.player.hear(0, "a1")

        assertThat(viewModel.narration.following).isEqualTo(Following.On)
        assertThat(harness.narrationSent().last()).isEqualTo(mark(0, "a1"))
    }

    @Test
    fun `nothing is shown without a narration of this book`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()
        harness.engine.emit(PageMessage.Turning)

        assertThat(viewModel.narration.strip).isNull()
        assertThat(harness.narrationSent()).isEmpty()
    }

    // ---------------------------------------------------------------- the narration shown

    @Test
    fun `this book's narration is active, and ended once listened to its end`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()

        harness.player.narration.value = narrationUi(playing = false)

        assertThat(viewModel.narration.available).isTrue()
        assertThat(viewModel.narration.active).isEqualTo(narrationUi(playing = false))
        assertThat(viewModel.narration.ended).isFalse()

        harness.player.narration.value = narrationUi(playing = false, finished = true)

        assertThat(viewModel.narration.ended).isTrue()

        harness.player.narration.value = narrationUi(bookId = BookId("99"))

        assertThat(viewModel.narration.active).isNull()
        assertThat(viewModel.narration.ended).isFalse()
    }

    @Test
    fun `a file without narration has no wave button`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        harness.detail = harness.detail.copy(files = harness.detail.files.map { it.copy(kind = BookFileKind.Text) })

        val viewModel = harness.reading()

        assertThat(viewModel.narration.available).isFalse()
    }

    @Test
    fun `finish the book opens the panel at the end of the book`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()
        harness.player.narration.value = narrationUi(playing = false, finished = true)

        viewModel.finishBook()

        assertThat(viewModel.state.value.endPanel).isNotNull()
    }

    // ---------------------------------------------------------------- stopping from the reader

    @Test
    fun `a paused narration is stopped from the reader, and undo loads it again where it was, paused`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            val viewModel = harness.reading()
            harness.player.narration.value = narrationUi(playing = false)

            viewModel.stopNarration()
            viewModel.undoStopNarration()
            viewModel.undoStopNarration()

            assertThat(harness.player.actions.calls).containsExactly("stop")
            assertThat(harness.player.resumed).containsExactly(false)
        }

    @Test
    fun `an ended narration is stopped from the reader too`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()
        harness.player.narration.value = narrationUi(playing = false, finished = true)

        viewModel.stopNarration()

        assertThat(harness.player.actions.calls).containsExactly("stop")
    }

    @Test
    fun `a narration that plays, or another book's, is not stopped from the reader`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()

        harness.player.narration.value = narrationUi(playing = true)
        viewModel.stopNarration()
        harness.player.narration.value = narrationUi(playing = false, bookId = BookId("99"))
        viewModel.stopNarration()
        viewModel.undoStopNarration()

        assertThat(harness.player.actions.calls).isEmpty()
        assertThat(harness.player.resumed).isEmpty()
    }

    @Test
    fun `a skipped section of this book is told, and read pauses and opens it`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()
        harness.player.hear(2, "c1")
        val notice = SkippedNotice(NarratedBook, sectionIndex = 1, title = "Two")

        harness.player.skipped.emit(SkippedNotice(BookId("99"), sectionIndex = 1, title = "Elsewhere"))
        assertThat(viewModel.narration.notice).isNull()
        harness.player.skipped.emit(notice)
        assertThat(viewModel.narration.notice).isEqualTo(notice)

        viewModel.readSkipped(notice)

        assertThat(harness.player.pauses).isEqualTo(1)
        assertThat(viewModel.narration.notice).isNull()
        assertThat(viewModel.narration.following).isEqualTo(Following.Off)
        assertThat(harness.engine.sent).contains(HostMessage.GoTo.Cfi("epubcfi(/6/4!/4)"))
        assertThat(viewModel.state.value.goBackShown).isTrue()
    }

    @Test
    fun `a skipped section without a title is named by the contents entry it starts`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading(sectionLabels = listOf("One", "Two", "Three", "Three"))
        harness.player.hear(2, "c1")

        harness.player.skipped.emit(SkippedNotice(NarratedBook, sectionIndex = 1, title = null))
        assertThat(viewModel.narration.notice).isEqualTo(SkippedNotice(NarratedBook, sectionIndex = 1, title = "Two"))

        // The second section of an entry is not that entry: it stays without a name.
        harness.player.skipped.emit(SkippedNotice(NarratedBook, sectionIndex = 3, title = null))
        assertThat(viewModel.narration.notice).isEqualTo(SkippedNotice(NarratedBook, sectionIndex = 3, title = null))
    }

    @Test
    fun `a notice dismissed goes, a newer one stays`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()
        harness.player.hear(2, "c1")
        val first = SkippedNotice(NarratedBook, sectionIndex = 1, title = "Two")
        val second = SkippedNotice(NarratedBook, sectionIndex = 3, title = null)
        harness.player.skipped.emit(first)
        harness.player.skipped.emit(second)

        viewModel.dismissNotice(first)

        assertThat(viewModel.narration.notice).isEqualTo(second)

        viewModel.dismissNotice(second)

        assertThat(viewModel.narration.notice).isNull()
    }

    // ---------------------------------------------------------------- starting points

    @Test
    fun `the wave button asks the page for the first narrated sentence shown and narrates from it`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            val viewModel = harness.reading()

            viewModel.startHere()

            val find = harness.finds().single()
            assertThat(find.sectionIndex).isEqualTo(0)
            assertThat(find.ids).containsExactly("a1", "a2", "a3").inOrder()
            assertThat(find.what).isEqualTo(NarrationTarget.Page)
            assertThat(harness.maps.asked).containsExactly(Triple(NarratedBook, TextFile, null))

            harness.engine.emit(PageMessage.NarrationFound(find.requestId, 0, "a2"))

            assertThat(harness.player.narrated).containsExactly(NarrationStart.AtSentence(0, "a2"))
            assertThat(viewModel.narration.following).isEqualTo(Following.On)
        }

    @Test
    fun `the wave button asks the page to prefer where the narration was last heard, in that section only`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            val viewModel = harness.reading()
            val heard = NarrationPlace(12.0, "OEBPS/a.xhtml#a3", 0)
            harness.places.put(LocalNarration(TextFile, heard, 10.0, Instant.EPOCH))

            viewModel.startHere()

            assertThat(harness.finds().single().prefer).isEqualTo("a3")

            val elsewhere = NarrationPlace(99.0, "OEBPS/c.xhtml#c1", 2)
            harness.places.put(LocalNarration(TextFile, elsewhere, 50.0, Instant.EPOCH))
            viewModel.startHere()

            assertThat(harness.finds().last().prefer).isNull()
        }

    @Test
    fun `no narrated sentence on or after the page in its section starts at the next narrated section`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            val viewModel = harness.reading()
            viewModel.readThisPage()

            harness.engine.emit(PageMessage.NarrationFound(harness.finds().single().requestId, 0, null))

            assertThat(harness.player.narrated).containsExactly(NarrationStart.AtSentence(2, "c1"))
        }

    @Test
    fun `a section without narration starts at the next narrated one without asking the page`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            val viewModel = harness.reading()
            harness.engine.emit(pageAt(cfi = SecondCfi, sectionIndex = 1))

            viewModel.startHere()

            assertThat(harness.finds()).isEmpty()
            assertThat(harness.player.narrated).containsExactly(NarrationStart.AtSentence(2, "c1"))
        }

    @Test
    fun `an answer to no question starts nothing`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        harness.reading()

        harness.engine.emit(PageMessage.NarrationFound(7, 0, "a1"))

        assertThat(harness.player.narrated).isEmpty()
    }

    @Test
    fun `read from here asks for the selection's sentence and ends the selection`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()
        harness.engine.emit(
            PageMessage.Selection(
                cfi = "epubcfi(/6/6!/4/2,/1:0,/1:4)",
                text = "Some",
                truncated = false,
                sectionIndex = 2,
                bounds = PageBounds(0.1, 0.1, 0.5, 0.2),
                highlightIds = emptyList(),
                exactId = null,
            ),
        )

        viewModel.readFromSelection()

        val find = harness.finds().single()
        assertThat(find.sectionIndex).isEqualTo(2)
        assertThat(find.ids).containsExactly("c1", "c2").inOrder()
        assertThat(find.what).isEqualTo(NarrationTarget.Selection)
        assertThat(harness.engine.sent.last()).isEqualTo(HostMessage.ClearSelection)
        assertThat(viewModel.state.value.selection).isNull()

        harness.engine.emit(PageMessage.NarrationFound(find.requestId, 2, "c2"))

        assertThat(harness.player.narrated).containsExactly(NarrationStart.AtSentence(2, "c2"))
    }

    @Test
    fun `a map that cannot be had makes the narration unavailable, and try again asks again`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            val viewModel = harness.reading()
            harness.maps.map = null

            viewModel.startHere()

            assertThat(viewModel.narration.unavailable).isTrue()
            assertThat(harness.finds()).isEmpty()

            harness.maps.map = sampleMap()
            viewModel.retryNarration()

            assertThat(viewModel.narration.unavailable).isFalse()
            assertThat(harness.finds()).hasSize(1)
        }

    @Test
    fun `a start elsewhere does not send the page back to the sentence heard before it`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            val viewModel = harness.reading()
            harness.player.hear(2, "c2", playing = false)
            harness.engine.emit(PageMessage.Turning)
            val before = harness.narrationSent().size

            viewModel.startHere()
            harness.engine.emit(PageMessage.NarrationFound(harness.finds().single().requestId, 0, "a2"))

            assertThat(harness.narrationSent().drop(before).filter { (it as HostMessage.NarrationMark).follow })
                .isEmpty()

            harness.player.hear(0, "a2")

            assertThat(harness.narrationSent().last()).isEqualTo(mark(0, "a2"))
        }

    @Test
    fun `an answer to a question asked again starts nothing, the latest one does`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()
        viewModel.startHere()
        viewModel.startHere()
        val (first, second) = harness.finds()

        harness.engine.emit(PageMessage.NarrationFound(first.requestId, 0, "a1"))

        assertThat(harness.player.narrated).isEmpty()

        harness.engine.emit(PageMessage.NarrationFound(second.requestId, 0, "a2"))

        assertThat(harness.player.narrated).containsExactly(NarrationStart.AtSentence(0, "a2"))
    }

    @Test
    fun `a question the user moved away from, or asked of a page opened since, starts nothing`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            val viewModel = harness.reading()
            viewModel.startHere()
            harness.engine.emit(PageMessage.Turning)
            harness.engine.emit(PageMessage.NarrationFound(harness.finds().last().requestId, 0, "a1"))

            viewModel.startHere()
            harness.engine.emit(PageMessage.Opened(SampleToc, PageDirection.Ltr, fixedLayout = false, title = null))
            harness.engine.emit(PageMessage.NarrationFound(harness.finds().last().requestId, 0, "a1"))

            assertThat(harness.finds()).hasSize(2)
            assertThat(harness.player.narrated).isEmpty()
        }

    @Test
    fun `two starts at once fetch the map once, and only the latest asks the page`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()
        val gate = CompletableDeferred<Unit>()
        harness.maps.gate = gate

        viewModel.startHere()
        viewModel.startHere()
        gate.complete(Unit)

        assertThat(harness.maps.asked).hasSize(1)
        assertThat(harness.finds()).hasSize(1)
    }

    @Test
    fun `a start landing on another sentence than asked is underlined`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()
        harness.player.hear(2, "c1", playing = false)
        viewModel.startHere()
        harness.engine.emit(PageMessage.NarrationFound(harness.finds().single().requestId, 0, "a2"))

        harness.player.hear(0, "a3")

        assertThat(harness.narrationSent().last()).isEqualTo(mark(0, "a3"))
    }

    @Test
    fun `the narration heard before a start that did not load is underlined again as it goes on`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            val viewModel = harness.reading()
            harness.player.hear(2, "c1")
            viewModel.startHere()
            harness.engine.emit(PageMessage.NarrationFound(harness.finds().single().requestId, 0, "a2"))

            harness.player.hear(2, "c2")

            assertThat(harness.narrationSent().last()).isEqualTo(mark(2, "c2"))
        }

    @Test
    fun `the sections skipped by another file's narration of the book are not told`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()
        harness.player.hear(0, "x1", fileId = FileId("2"))

        harness.player.skipped.emit(SkippedNotice(NarratedBook, sectionIndex = 1, title = "Two"))

        assertThat(viewModel.narration.notice).isNull()
    }

    // ---------------------------------------------------------------- opening

    @Test
    fun `opened during this book's narration, the page goes to the sentence heard and follows it`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            harness.player.hear(2, "c2", playing = false)
            harness.player.narration.value = narrationUi(playing = false)

            val viewModel = harness.narrating()

            assertThat(viewModel.narration.following).isEqualTo(Following.On)
            assertThat(harness.narrationSent()).containsExactly(mark(2, "c2", paused = true))
            assertThat(harness.finds()).isEmpty()
            assertThat(harness.player.narrated).isEmpty()
        }

    @Test
    fun `opened to narrate with nothing loaded, the narration starts from the page shown`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()

            harness.narrating()

            val find = harness.finds().single()
            assertThat(find.sectionIndex).isEqualTo(0)
            assertThat(find.what).isEqualTo(NarrationTarget.Page)
        }

    @Test
    fun `opened to narrate while another file of the book is narrated, the narration starts from the page`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            harness.player.hear(0, "x1", fileId = FileId("2"))

            harness.narrating()

            assertThat(harness.finds()).hasSize(1)
        }

    @Test
    fun `opened to narrate while this book's narration is paused, it plays where it is`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            harness.player.hear(2, "c1", playing = false)

            harness.narrating()

            assertThat(harness.player.plays).isEqualTo(1)
            assertThat(harness.finds()).isEmpty()
        }

    @Test
    fun `opened to narrate a narration of this book that ended, it starts from the page shown`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            harness.player.hear(2, "c2", playing = false)
            harness.player.narration.value = narrationUi(playing = false, finished = true)

            harness.narrating()

            assertThat(harness.player.plays).isEqualTo(0)
            assertThat(harness.finds().single().what).isEqualTo(NarrationTarget.Page)
        }

    @Test
    fun `opened to narrate while this book's narration plays, nothing changes`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        harness.player.hear(2, "c1")

        harness.narrating()

        assertThat(harness.player.plays).isEqualTo(0)
        assertThat(harness.finds()).isEmpty()
    }

    @Test
    fun `opened without being asked to narrate, nothing starts`() = runTest(mainRule.dispatcher) {
        val harness = harness()

        harness.reading()

        assertThat(harness.finds()).isEmpty()
    }

    // ---------------------------------------------------------------- errors

    @Test
    fun `this book's narration that failed while another book's is held shows its failure, not the other one`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            val viewModel = harness.reading()
            harness.player.narration.value = narrationUi(bookId = BookId("99"))
            harness.player.hear(0, "x1", bookId = BookId("99"))

            harness.player.failure.value = NarrationFailure(NarratedBook, TextFile)

            assertThat(viewModel.narration.active).isNull()
            assertThat(viewModel.narration.loadFailed).isTrue()
            viewModel.retryNarration()
            assertThat(harness.player.retries).isEqualTo(1)

            harness.player.failure.value = NarrationFailure(BookId("99"), FileId("3"))
            assertThat(viewModel.narration.loadFailed).isFalse()
        }

    @Test
    fun `another book's failure leaves this book's narration as it is`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()
        harness.player.narration.value = narrationUi()

        harness.player.failure.value = NarrationFailure(BookId("99"), FileId("3"))

        assertThat(viewModel.narration.active).isEqualTo(narrationUi())
        assertThat(viewModel.narration.loadFailed).isFalse()
    }

    @Test
    fun `try again after the narration failed to load asks the player, not the map`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()
        harness.player.narration.value = narrationUi(playing = false).copy(loadFailed = true)

        viewModel.retryNarration()

        assertThat(harness.player.retries).isEqualTo(1)
        assertThat(harness.maps.asked).isEmpty()
    }
}
