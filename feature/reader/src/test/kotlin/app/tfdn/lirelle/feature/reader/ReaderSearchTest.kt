package app.tfdn.lirelle.feature.reader

import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.reader.marks.MarkId
import app.tfdn.lirelle.core.testing.MainDispatcherRule
import app.tfdn.lirelle.feature.reader.bridge.HostMessage
import app.tfdn.lirelle.feature.reader.bridge.PageBounds
import app.tfdn.lirelle.feature.reader.bridge.PageDirection
import app.tfdn.lirelle.feature.reader.bridge.PageMessage
import app.tfdn.lirelle.feature.reader.bridge.SearchGroup
import app.tfdn.lirelle.feature.reader.bridge.SearchHit
import com.google.common.truth.Truth.assertThat
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** One character to the page, two units of UTF-16. */
private const val Emoji = "\uD83D\uDE00"

private fun hit(cfi: String) = SearchHit(cfi = cfi, pre = "before ", match = "word", post = " after")

private val HitA = hit("epubcfi(/6/8!/4/2,/1:0,/1:4)")
private val HitB = hit("epubcfi(/6/8!/4/6,/1:0,/1:4)")
private val HitC = hit("epubcfi(/6/12!/4/2,/1:0,/1:4)")
private val HitD = hit("epubcfi(/6/12!/4/8,/1:0,/1:4)")
private val HitE = hit("epubcfi(/6/16!/4/2,/1:0,/1:4)")

private val EmptySearch = SearchUi(
    query = "",
    searchId = null,
    running = false,
    fraction = 0.0,
    groups = emptyList(),
    total = 0,
    truncated = false,
    current = null,
    onPage = false,
)

/** Search in the book: the query, the results as they come, stepping through them on the page. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderSearchTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    @get:Rule
    val folder = TemporaryFolder()

    private fun TestScope.harness() = ReaderHarness(this, folder.root)

    private val ReaderHarness.searches get() = engine.sent.filterIsInstance<HostMessage.Search>()

    /** A tap on the result at [index] of the search the sheet shows. */
    private fun ReaderViewModel.show(index: Int) = showResult(state.value.search!!.searchId!!, index)

    /** A reader whose search for "word" found [HitA] to [HitE] in three sections and is done. */
    private fun searched(harness: ReaderHarness): ReaderViewModel {
        val viewModel = harness.reading()
        viewModel.openSearch()
        viewModel.editQuery("word")
        viewModel.submitQuery()
        val id = harness.searches.last().searchId
        harness.engine.emit(
            PageMessage.SearchResults(id, listOf(SearchGroup(3, "Three", listOf(HitA, HitB)))),
        )
        harness.engine.emit(
            PageMessage.SearchResults(
                id,
                listOf(SearchGroup(5, "Five", listOf(HitC, HitD)), SearchGroup(7, null, listOf(HitE))),
            ),
        )
        harness.engine.emit(PageMessage.SearchDone(id, total = 5, truncated = false))
        return viewModel
    }

    // ---------------------------------------------------------------- the query

    @Test
    fun `the search opens empty in its sheet`() = runTest(mainRule.dispatcher) {
        val viewModel = harness().reading()

        viewModel.openSearch()

        assertThat(viewModel.state.value.openSheet).isEqualTo(ReaderSheet.Search)
        assertThat(viewModel.state.value.search).isEqualTo(EmptySearch)
    }

    @Test
    fun `typing starts a search after 400 ms without typing, from two characters`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()
        viewModel.openSearch()

        viewModel.editQuery("w")
        advanceTimeBy(1.seconds)
        assertThat(harness.searches).isEmpty()
        assertThat(viewModel.state.value.search?.query).isEqualTo("w")

        viewModel.editQuery("wo")
        advanceTimeBy(399.milliseconds)
        viewModel.editQuery("wor")
        advanceTimeBy(399.milliseconds)
        assertThat(harness.searches).isEmpty()

        advanceTimeBy(2.milliseconds)
        assertThat(harness.searches).hasSize(1)
        val search = harness.searches.single()
        assertThat(search.query).isEqualTo("wor")
        assertThat(viewModel.state.value.search).isEqualTo(
            EmptySearch.copy(query = "wor", searchId = search.searchId, running = true),
        )
    }

    @Test
    fun `confirming starts the search at once, and only once`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()
        viewModel.openSearch()

        viewModel.editQuery("word")
        viewModel.submitQuery()
        assertThat(harness.searches.map { it.query }).containsExactly("word")

        advanceTimeBy(1.seconds)
        viewModel.submitQuery()
        assertThat(harness.searches.map { it.query }).containsExactly("word")
    }

    @Test
    fun `under two characters nothing is searched, even when confirmed`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()
        viewModel.openSearch()

        viewModel.editQuery(" a ")
        viewModel.submitQuery()
        advanceTimeBy(1.seconds)

        assertThat(harness.searches).isEmpty()
    }

    @Test
    fun `a query is at most 200 characters`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()
        viewModel.openSearch()

        viewModel.editQuery("x".repeat(250))
        viewModel.submitQuery()

        assertThat(viewModel.state.value.search?.query).hasLength(200)
        assertThat(harness.searches.single().query).isEqualTo("x".repeat(200))
    }

    @Test
    fun `characters are counted as the page counts them, an emoji being one`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()
        viewModel.openSearch()

        viewModel.editQuery(Emoji)
        viewModel.submitQuery()
        advanceTimeBy(1.seconds)
        assertThat(harness.searches).isEmpty()

        viewModel.editQuery(Emoji + Emoji)
        advanceTimeBy(1.seconds)
        assertThat(harness.searches.map { it.query }).containsExactly(Emoji + Emoji)
    }

    @Test
    fun `a query of emoji is cut at 200 of them, never inside one`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()
        viewModel.openSearch()

        viewModel.editQuery("x" + Emoji.repeat(250))
        viewModel.submitQuery()

        assertThat(viewModel.state.value.search?.query).isEqualTo("x" + Emoji.repeat(199))
        assertThat(harness.searches.single().query).isEqualTo("x" + Emoji.repeat(199))
    }

    @Test
    fun `a new query has a new id, and the old one's results are ignored`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()
        viewModel.openSearch()
        viewModel.editQuery("word")
        viewModel.submitQuery()
        val first = harness.searches.last().searchId

        viewModel.editQuery("other")
        advanceTimeBy(401.milliseconds)
        val second = harness.searches.last().searchId
        assertThat(second).isNotEqualTo(first)

        harness.engine.emit(PageMessage.SearchResults(first, listOf(SearchGroup(3, "Three", listOf(HitA)))))
        harness.engine.emit(PageMessage.SearchProgress(first, 0.9))
        harness.engine.emit(PageMessage.SearchDone(first, total = 1, truncated = false))
        assertThat(viewModel.state.value.search).isEqualTo(
            EmptySearch.copy(query = "other", searchId = second, running = true),
        )

        harness.engine.emit(PageMessage.SearchResults(second, listOf(SearchGroup(5, "Five", listOf(HitC)))))
        assertThat(viewModel.state.value.search?.groups).containsExactly(SearchRow(5, "Five", listOf(HitC)))
    }

    // ---------------------------------------------------------------- the results

    @Test
    fun `results are grouped by section in arrival order, a section going on in the next message`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            val viewModel = harness.reading()
            viewModel.openSearch()
            viewModel.editQuery("word")
            viewModel.submitQuery()
            val id = harness.searches.last().searchId

            harness.engine.emit(
                PageMessage.SearchResults(
                    id,
                    listOf(SearchGroup(3, "Three", listOf(HitA)), SearchGroup(5, "Five", listOf(HitB))),
                    fraction = 0.25,
                ),
            )
            harness.engine.emit(
                PageMessage.SearchResults(
                    id,
                    listOf(SearchGroup(5, "Five", listOf(HitC, HitD)), SearchGroup(7, null, listOf(HitE))),
                ),
            )

            val search = viewModel.state.value.search!!
            assertThat(search.groups).containsExactly(
                SearchRow(3, "Three", listOf(HitA)),
                SearchRow(5, "Five", listOf(HitB, HitC, HitD)),
                SearchRow(7, null, listOf(HitE)),
            ).inOrder()
            assertThat(search.total).isEqualTo(5)
            assertThat(search.fraction).isEqualTo(0.25)
            assertThat(search.running).isTrue()
        }

    @Test
    fun `progress is shown, and the end gives the total and whether it was cut`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()
        viewModel.openSearch()
        viewModel.editQuery("word")
        viewModel.submitQuery()
        val id = harness.searches.last().searchId

        harness.engine.emit(PageMessage.SearchProgress(id, 0.4))
        assertThat(viewModel.state.value.search?.fraction).isEqualTo(0.4)

        harness.engine.emit(PageMessage.SearchDone(id, total = 1000, truncated = true))
        val search = viewModel.state.value.search!!
        assertThat(search.running).isFalse()
        assertThat(search.total).isEqualTo(1000)
        assertThat(search.truncated).isTrue()
        assertThat(search.fraction).isEqualTo(1.0)
    }

    @Test
    fun `a refused query ends with no result`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()
        viewModel.openSearch()
        viewModel.editQuery("word")
        viewModel.submitQuery()

        harness.engine.emit(PageMessage.SearchDone(harness.searches.last().searchId, total = 0, truncated = false))

        assertThat(viewModel.state.value.search?.running).isFalse()
        assertThat(viewModel.state.value.search?.total).isEqualTo(0)
    }

    // ---------------------------------------------------------------- on the page

    @Test
    fun `a result shown closes the sheet and goes there, offering to go back`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = searched(harness)
        viewModel.toggleControls()

        viewModel.show(3)

        assertThat(harness.engine.sent.last()).isEqualTo(HostMessage.ShowSearchResult(HitD.cfi))
        val state = viewModel.state.value
        assertThat(state.openSheet).isNull()
        assertThat(state.search?.current).isEqualTo(3)
        assertThat(state.search?.onPage).isTrue()
        assertThat(state.goBackShown).isTrue()
        // Moving between results does not show the controls.
        assertThat(state.controlsShown).isFalse()
    }

    @Test
    fun `an index outside the results does nothing`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = searched(harness)
        val sent = harness.engine.sent.size

        viewModel.show(5)
        viewModel.show(-1)

        assertThat(harness.engine.sent).hasSize(sent)
        assertThat(viewModel.state.value.search?.current).isNull()
    }

    @Test
    fun `next and previous step through the results, wrapping around`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = searched(harness)
        viewModel.show(4)

        viewModel.nextResult()
        assertThat(viewModel.state.value.search?.current).isEqualTo(0)
        assertThat(harness.engine.sent.last()).isEqualTo(HostMessage.ShowSearchResult(HitA.cfi))

        viewModel.previousResult()
        assertThat(viewModel.state.value.search?.current).isEqualTo(4)
        assertThat(harness.engine.sent.last()).isEqualTo(HostMessage.ShowSearchResult(HitE.cfi))

        viewModel.previousResult()
        assertThat(viewModel.state.value.search?.current).isEqualTo(3)
        assertThat(harness.engine.sent.last()).isEqualTo(HostMessage.ShowSearchResult(HitD.cfi))
    }

    @Test
    fun `go back returns to where the search started, however many results were shown`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            val viewModel = searched(harness)

            viewModel.show(0)
            harness.engine.emit(relocated(cfi = HitA.cfi, fraction = 0.3).copy(byHost = true))
            viewModel.nextResult()
            harness.engine.emit(relocated(cfi = HitA.cfi, fraction = 0.3).copy(byHost = true))
            viewModel.nextResult()
            harness.engine.emit(relocated(cfi = HitC.cfi, fraction = 0.5).copy(byHost = true))

            viewModel.goBack()

            assertThat(harness.engine.sent.last()).isEqualTo(HostMessage.GoTo.Cfi(StartCfi))
            assertThat(viewModel.state.value.goBackShown).isFalse()
        }

    @Test
    fun `back to the results reopens the sheet as it was`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = searched(harness)
        viewModel.show(2)
        val search = viewModel.state.value.search

        viewModel.backToResults()

        assertThat(viewModel.state.value.openSheet).isEqualTo(ReaderSheet.Search)
        assertThat(viewModel.state.value.search).isEqualTo(search)
    }

    @Test
    fun `the search opened again shows the same query and results`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = searched(harness)
        val search = viewModel.state.value.search
        viewModel.dismissSheet()

        viewModel.openSearch()

        assertThat(viewModel.state.value.openSheet).isEqualTo(ReaderSheet.Search)
        // Closed with results, the sheet had left the search's bar on the page.
        assertThat(viewModel.state.value.search).isEqualTo(search?.copy(onPage = true))
        assertThat(harness.searches).hasSize(1)
    }

    @Test
    fun `closing the search removes the outlines and forgets it`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = searched(harness)
        viewModel.show(1)
        viewModel.backToResults()

        viewModel.closeSearch()

        assertThat(harness.engine.sent.last()).isEqualTo(HostMessage.ClearSearch)
        assertThat(viewModel.state.value.search).isNull()
        assertThat(viewModel.state.value.openSheet).isNull()

        viewModel.openSearch()
        assertThat(viewModel.state.value.search).isEqualTo(EmptySearch)
    }

    @Test
    fun `a search closed before its pause is over starts nothing`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()
        viewModel.openSearch()

        viewModel.editQuery("word")
        viewModel.closeSearch()
        advanceTimeBy(1.seconds)

        assertThat(harness.searches).isEmpty()
        assertThat(viewModel.state.value.search).isNull()
    }

    @Test
    fun `search on a selection opens the search with its text and starts it`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()
        val text = "  " + "y".repeat(250)
        harness.engine.emit(
            PageMessage.Selection(
                cfi = "epubcfi(/6/4!/4/2,/1:0,/1:15)",
                text = text,
                truncated = false,
                sectionIndex = 1,
                bounds = PageBounds(0.1, 0.2, 0.6, 0.3),
                highlightIds = emptyList(),
                exactId = null,
            ),
        )

        viewModel.searchSelection()

        assertThat(viewModel.state.value.openSheet).isEqualTo(ReaderSheet.Search)
        assertThat(viewModel.state.value.search?.query).isEqualTo("y".repeat(200))
        assertThat(harness.searches.single().query).isEqualTo("y".repeat(200))
        assertThat(viewModel.state.value.search?.running).isTrue()
    }

    @Test
    fun `back closes the sheet, then the search on the page, then goes back`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = searched(harness)
        viewModel.show(2)
        viewModel.backToResults()

        assertThat(viewModel.onBack()).isTrue()
        assertThat(viewModel.state.value.openSheet).isNull()
        assertThat(viewModel.state.value.search?.onPage).isTrue()

        assertThat(viewModel.onBack()).isTrue()
        assertThat(harness.engine.sent.last()).isEqualTo(HostMessage.ClearSearch)
        assertThat(viewModel.state.value.search).isNull()
        assertThat(viewModel.state.value.goBackShown).isTrue()

        assertThat(viewModel.onBack()).isTrue()
        assertThat(harness.engine.sent.last()).isEqualTo(HostMessage.GoTo.Cfi(StartCfi))
    }

    @Test
    fun `a page opened again searches again for the same query`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = searched(harness)
        viewModel.show(1)
        val first = harness.searches.last().searchId

        harness.engine.emit(PageMessage.Opened(SampleToc, PageDirection.Ltr, fixedLayout = false, title = null))

        val again = harness.searches.last()
        assertThat(again.query).isEqualTo("word")
        assertThat(again.searchId).isNotEqualTo(first)
        assertThat(viewModel.state.value.search).isEqualTo(
            EmptySearch.copy(query = "word", searchId = again.searchId, running = true),
        )
    }

    // ---------------------------------------------------------------- the page answers a move

    @Test
    fun `a result on the visible page is answered, and the next page turned counts`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = searched(harness)
        harness.engine.emit(relocated(cfi = SecondCfi, fraction = 0.2))
        viewModel.show(0)
        harness.engine.emit(relocated(cfi = SecondCfi, fraction = 0.2).copy(byHost = true))

        viewModel.nextResult()
        // The page already shows the next result: it answers with exactly the same relocation.
        harness.engine.emit(relocated(cfi = SecondCfi, fraction = 0.2).copy(byHost = true))
        viewModel.toggleControls()
        harness.engine.emit(relocated(cfi = ThirdCfi, fraction = 0.3))

        assertThat(viewModel.state.value.controlsShown).isFalse()
        assertThat(viewModel.state.value.goBackShown).isTrue()
        harness.engine.emit(relocated(cfi = "epubcfi(/6/6!/4/8)", fraction = 0.31))
        assertThat(viewModel.state.value.goBackShown).isFalse()
    }

    @Test
    fun `a page that opens the book again owes no answer to the moves asked before`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            val viewModel = harness.reading()
            harness.engine.emit(relocated(cfi = SecondCfi, fraction = 0.2))
            // Two moves the page never answers: it opens the book again first.
            viewModel.seekTo(0.5)
            viewModel.seekTo(0.6)
            harness.engine.emit(PageMessage.Opened(SampleToc, PageDirection.Ltr, fixedLayout = false, title = null))

            viewModel.seekTo(0.5)
            harness.engine.emit(relocated(cfi = ThirdCfi, fraction = 0.5).copy(byHost = true))
            viewModel.toggleControls()
            harness.engine.emit(relocated(cfi = "epubcfi(/6/6!/4/8)", fraction = 0.51))

            // The answer ended the wait: the page turned next is the user's.
            assertThat(viewModel.state.value.location?.fraction).isEqualTo(0.51)
            assertThat(viewModel.state.value.controlsShown).isFalse()
        }

    @Test
    fun `a relocation the host asked for is never a page turned`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()
        viewModel.seekTo(0.5)
        harness.engine.emit(relocated(cfi = ThirdCfi, fraction = 0.5))
        viewModel.toggleControls()

        harness.engine.emit(relocated(cfi = "epubcfi(/6/6!/4/8)", fraction = 0.51).copy(byHost = true))
        harness.engine.emit(relocated(cfi = "epubcfi(/6/6!/4/14)", fraction = 0.52).copy(byHost = true))

        assertThat(viewModel.state.value.controlsShown).isTrue()
        assertThat(viewModel.state.value.goBackShown).isTrue()
        assertThat(viewModel.state.value.location?.fraction).isEqualTo(0.52)
    }

    @Test
    fun `the answer to go back on the same place brings the user back`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()
        harness.engine.emit(relocated(cfi = SecondCfi, fraction = 0.2))
        viewModel.goToToc(SampleToc[1])
        harness.engine.emit(relocated(cfi = ThirdCfi, fraction = 0.3, tocId = 1))

        viewModel.goBack()
        harness.engine.emit(relocated(cfi = SecondCfi, fraction = 0.2).copy(byHost = true))
        advanceTimeBy(5.seconds)
        viewModel.onLeave()

        assertThat(harness.positions.positions[FileId("1")]?.cfi).isEqualTo(SecondCfi)
        assertThat(harness.repository.savedPositions.map { it.cfi }).doesNotContain(ThirdCfi)
    }

    // ---------------------------------------------------------------- review

    @Test
    fun `the sheet closed with results leaves the bar on the page, without a current result`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            val viewModel = searched(harness)

            viewModel.dismissSheet()

            assertThat(viewModel.state.value.search?.onPage).isTrue()
            assertThat(viewModel.state.value.search?.current).isNull()
            viewModel.nextResult()
            assertThat(viewModel.state.value.search?.current).isEqualTo(0)
            assertThat(harness.engine.sent.last()).isEqualTo(HostMessage.ShowSearchResult(HitA.cfi))
        }

    @Test
    fun `the sheet closed by back while results may still come leaves the bar too`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()
        viewModel.openSearch()
        viewModel.editQuery("word")
        viewModel.submitQuery()

        assertThat(viewModel.onBack()).isTrue()

        assertThat(viewModel.state.value.openSheet).isNull()
        assertThat(viewModel.state.value.search?.onPage).isTrue()
    }

    @Test
    fun `the sheet closed with nothing found, or nothing searched, leaves no bar`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()
        viewModel.openSearch()
        viewModel.dismissSheet()
        assertThat(viewModel.state.value.search?.onPage).isFalse()

        viewModel.openSearch()
        viewModel.editQuery("word")
        viewModel.submitQuery()
        harness.engine.emit(PageMessage.SearchDone(harness.searches.last().searchId, total = 0, truncated = false))
        viewModel.dismissSheet()

        assertThat(viewModel.state.value.search?.onPage).isFalse()
    }

    @Test
    fun `a result shown, or the list again, before typing pauses starts no search`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = searched(harness)

        viewModel.editQuery("words")
        viewModel.show(1)
        advanceTimeBy(1.seconds)
        viewModel.editQuery("wordy")
        viewModel.backToResults()
        advanceTimeBy(1.seconds)

        assertThat(harness.searches).hasSize(1)
    }

    @Test
    fun `a query edited below two characters clears the search`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = searched(harness)
        val old = harness.searches.last().searchId

        viewModel.editQuery("w")

        assertThat(harness.engine.sent.last()).isEqualTo(HostMessage.ClearSearch)
        assertThat(viewModel.state.value.search).isEqualTo(EmptySearch.copy(query = "w"))
        harness.engine.emit(PageMessage.SearchResults(old, listOf(SearchGroup(9, "Nine", listOf(HitE)))))
        assertThat(viewModel.state.value.search?.groups).isEmpty()

        // The same query as before is searched again.
        viewModel.editQuery("word")
        advanceTimeBy(401.milliseconds)
        assertThat(harness.searches.map { it.query }).containsExactly("word", "word")
    }

    @Test
    fun `a short query with nothing searched says nothing to the page`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()
        viewModel.openSearch()
        val sent = harness.engine.sent.size

        viewModel.editQuery("w")
        viewModel.editQuery("")

        assertThat(harness.engine.sent).hasSize(sent)
    }

    @Test
    fun `a result of an older search is not shown`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = searched(harness)
        val old = viewModel.state.value.search!!.searchId!!
        viewModel.editQuery("other")
        viewModel.submitQuery()
        harness.engine.emit(
            PageMessage.SearchResults(harness.searches.last().searchId, listOf(SearchGroup(5, "Five", listOf(HitC)))),
        )
        val sent = harness.engine.sent.size

        viewModel.showResult(old, 0)

        assertThat(harness.engine.sent).hasSize(sent)
        assertThat(viewModel.state.value.search?.current).isNull()
        assertThat(viewModel.state.value.openSheet).isEqualTo(ReaderSheet.Search)
    }

    @Test
    fun `results arriving after the search was closed are dropped, also once it is opened again`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            val viewModel = harness.reading()
            viewModel.openSearch()
            viewModel.editQuery("word")
            viewModel.submitQuery()
            val id = harness.searches.last().searchId

            viewModel.closeSearch()
            harness.engine.emit(PageMessage.SearchResults(id, listOf(SearchGroup(3, "Three", listOf(HitA)))))
            assertThat(viewModel.state.value.search).isNull()

            viewModel.openSearch()
            harness.engine.emit(PageMessage.SearchResults(id, listOf(SearchGroup(3, "Three", listOf(HitB)))))
            harness.engine.emit(PageMessage.SearchProgress(id, 0.5))
            harness.engine.emit(PageMessage.SearchDone(id, total = 2, truncated = false))
            assertThat(viewModel.state.value.search).isEqualTo(EmptySearch)
        }

    @Test
    fun `previous without a current result starts from the last`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            val viewModel = searched(harness)

            viewModel.previousResult()
            assertThat(viewModel.state.value.search?.current).isEqualTo(4)
            assertThat(harness.engine.sent.last()).isEqualTo(HostMessage.ShowSearchResult(HitE.cfi))
        }

    @Test
    fun `next and previous with no result do nothing`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()
        viewModel.openSearch()
        val sent = harness.engine.sent.size

        viewModel.nextResult()
        viewModel.previousResult()

        assertThat(harness.engine.sent).hasSize(sent)
    }

    @Test
    fun `go back asked before the jump was answered is credited to its own answer`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()
        harness.engine.emit(relocated(cfi = SecondCfi, fraction = 0.2))
        advanceTimeBy(5.seconds)

        viewModel.goToToc(SampleToc[1])
        viewModel.goBack()
        harness.engine.emit(relocated(cfi = ThirdCfi, fraction = 0.3, tocId = 1).copy(byHost = true))
        advanceTimeBy(5.seconds)
        harness.engine.emit(relocated(cfi = SecondCfi, fraction = 0.2).copy(byHost = true))
        advanceTimeBy(5.seconds)
        viewModel.onLeave()

        // Back where the position already was: nothing more is written.
        assertThat(harness.repository.savedPositions.map { it.cfi }).containsExactly(SecondCfi)
        assertThat(harness.positions.positions[FileId("1")]?.cfi).isEqualTo(SecondCfi)
        assertThat(viewModel.state.value.goBackShown).isFalse()
    }

    @Test
    fun `bookmarks resent after an answer to a move are only bookmarks`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()
        harness.engine.emit(relocated(cfi = SecondCfi, fraction = 0.2))
        advanceTimeBy(5.seconds)

        viewModel.goToToc(SampleToc[1])
        viewModel.goBack()
        harness.engine.emit(relocated(cfi = ThirdCfi, fraction = 0.3, tocId = 1).copy(byHost = true))
        harness.engine.emit(relocated(cfi = ThirdCfi, fraction = 0.3, tocId = 1).copy(bookmarkIds = listOf("b1")))
        assertThat(viewModel.state.value.pageBookmarks).containsExactly(MarkId("b1"))
        advanceTimeBy(5.seconds)
        harness.engine.emit(relocated(cfi = SecondCfi, fraction = 0.2).copy(byHost = true))
        advanceTimeBy(5.seconds)
        viewModel.onLeave()

        assertThat(harness.positions.positions[FileId("1")]?.cfi).isEqualTo(SecondCfi)
        assertThat(harness.repository.savedPositions.map { it.cfi }).containsExactly(SecondCfi)
    }
}
