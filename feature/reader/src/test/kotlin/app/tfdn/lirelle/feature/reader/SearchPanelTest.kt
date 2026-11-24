package app.tfdn.lirelle.feature.reader

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasImeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.reader.marks.MarkId
import app.tfdn.lirelle.core.reader.settings.ReaderSettings
import app.tfdn.lirelle.core.reader.settings.RunningLine
import app.tfdn.lirelle.core.reader.settings.RunningSlot
import app.tfdn.lirelle.feature.reader.bridge.PageBounds
import app.tfdn.lirelle.feature.reader.bridge.SearchHit
import app.tfdn.lirelle.feature.reader.web.testBook
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A search for "carnet": for each section, its label and how many results it has. */
internal fun searchOf(
    vararg rows: Pair<String?, Int>,
    running: Boolean = false,
    current: Int? = null,
    onPage: Boolean = false,
    truncated: Boolean = false,
): SearchUi {
    val groups = rows.mapIndexed { index, (label, hits) ->
        SearchRow(
            sectionIndex = index + 2,
            label = label,
            hits = List(hits) {
                SearchHit("epubcfi(/6/${2 * index + 4}!/4/$it)", "before $index.$it ", "carnet", " after")
            },
        )
    }
    return SearchUi(
        query = "carnet",
        searchId = 7,
        running = running,
        fraction = if (running) 0.37 else 1.0,
        groups = groups,
        total = groups.sumOf { it.hits.size },
        truncated = truncated,
        current = current,
        onPage = onPage,
    )
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class SearchPanelTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val folder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val actions = RecordingActions()

    private fun text(id: Int, vararg args: Any) = context.getString(id, *args)

    private var search by mutableStateOf(searchOf())

    private fun showPanel(shown: SearchUi) {
        search = shown
        compose.setContent { LirelleTheme { SearchPanel(search, actions) } }
    }

    private fun field() = compose.onNode(hasSetTextAction())

    // The panel

    @Test
    fun `an empty search gives the field the focus, with its hint`() {
        showPanel(SearchUi("", null, false, 0.0, emptyList(), 0, false, null, false))

        field().assertIsFocused()
        compose.onNodeWithText(text(R.string.reader_search_book)).assertIsDisplayed()
    }

    @Test
    fun `a search with a query shows it, and leaves the focus alone`() {
        showPanel(searchOf("Chapter one" to 1))

        field().assertIsNotFocused()
        assertThat(field().fetchSemanticsNode().config[SemanticsProperties.EditableText].text).isEqualTo("carnet")
    }

    @Test
    fun `typing is handed over, and the keyboard's search action searches`() {
        showPanel(SearchUi("", null, false, 0.0, emptyList(), 0, false, null, false))

        field().assert(hasImeAction(ImeAction.Search))
        field().performTextReplacement("carnet")
        compose.waitForIdle()
        field().performImeAction()

        assertThat(actions.calls).containsExactly("editQuery(carnet)", "submitQuery").inOrder()
    }

    @Test
    fun `a running search says how far it has got`() {
        showPanel(searchOf("Chapter one" to 2, running = true))

        compose.onNodeWithText("Searching… 37\u00A0%").assertIsDisplayed()
        val bar = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
            .fetchSemanticsNode()
        assertThat(bar.config[SemanticsProperties.ProgressBarRangeInfo].current).isWithin(0.001f).of(0.37f)
    }

    @Test
    @Config(qualifiers = "fr-w411dp-h891dp")
    fun `in French, how far the search has got`() {
        showPanel(searchOf("Chapitre un" to 2, running = true))

        compose.onNodeWithText("Recherche… 37\u00A0%").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "fr-w411dp-h891dp")
    fun `in French, the results and the chapters are counted`() {
        showPanel(searchOf("Un" to 5, "Deux" to 5, "Trois" to 5, "Quatre" to 5, "Cinq" to 5, "Six" to 2))

        compose.onNodeWithText("27 résultats dans 6 chapitres").assertIsDisplayed()
    }

    @Test
    fun `chapter headings are headings to a screen reader`() {
        showPanel(searchOf("Chapter one" to 1, null to 1))

        compose.onNodeWithText("Chapter one").assert(isHeading())
        compose.onNodeWithText(text(R.string.reader_search_untitled)).assert(isHeading())
    }

    @Test
    fun `a chapter over several files has one heading, and counts once`() {
        showPanel(searchOf("Chapter one" to 2, "Chapter one" to 1, null to 1, null to 1))

        assertThat(compose.onAllNodesWithText("Chapter one").fetchSemanticsNodes()).hasSize(1)
        assertThat(compose.onAllNodesWithText(text(R.string.reader_search_untitled)).fetchSemanticsNodes()).hasSize(1)
        compose.onNodeWithText("5 results in 2 chapters").assertIsDisplayed()
    }

    @Test
    fun `a chapter met again after another one has its heading again`() {
        showPanel(searchOf("Chapter one" to 1, "Chapter two" to 1, "Chapter one" to 1))

        assertThat(compose.onAllNodesWithText("Chapter one").fetchSemanticsNodes()).hasSize(2)
        compose.onNodeWithText("3 results in 3 chapters").assertIsDisplayed()
    }

    @Test
    fun `the summary counts the results held, as the bar does`() {
        showPanel(searchOf("Chapter one" to 2, "Chapter two" to 1).copy(total = 30))

        compose.onNodeWithText("3 results in 2 chapters").assertIsDisplayed()
    }

    @Test
    fun `a result says where a tap leads`() {
        showPanel(searchOf("Chapter one" to 1))

        val row = compose.onNodeWithText("before 0.0 carnet after").fetchSemanticsNode()
        assertThat(row.config[SemanticsActions.OnClick].label).isEqualTo(text(R.string.reader_search_go_to_result))
    }

    @Test
    fun `results are grouped under their chapter, untitled ones under a label of their own`() {
        showPanel(searchOf("Chapter one" to 1, null to 1))

        compose.onNodeWithText("Chapter one").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.reader_search_untitled)).assertIsDisplayed()
        compose.onNodeWithText("before 0.0 carnet after").assertIsDisplayed()
        compose.onNodeWithText("before 1.0 carnet after").assertIsDisplayed()
    }

    @Test
    fun `the match is in bold, and only the match`() {
        showPanel(searchOf("Chapter one" to 1))

        val row = compose.onNodeWithText("before 0.0 carnet after").fetchSemanticsNode()
        val shown = row.config[SemanticsProperties.Text].single()
        val bold = shown.spanStyles.filter { it.item.fontWeight == FontWeight.Bold }
        assertThat(bold).hasSize(1)
        assertThat(shown.text.substring(bold[0].start, bold[0].end)).isEqualTo("carnet")
    }

    @Test
    fun `a tap on a result shows it, counted across the chapters`() {
        showPanel(searchOf("Chapter one" to 2, "Chapter two" to 2))

        compose.onNodeWithText("before 1.1 carnet after").performScrollTo().performClick()

        assertThat(actions.calls).containsExactly("showResult(7, 3)")
    }

    @Test
    fun `at the end the results and the chapters are counted`() {
        showPanel(searchOf("Chapter one" to 2, "Chapter two" to 1))

        val results = context.resources.getQuantityString(R.plurals.reader_search_results, 3, 3)
        val chapters = context.resources.getQuantityString(R.plurals.reader_search_chapters, 2, 2)
        compose.onNodeWithText(text(R.string.reader_search_summary, results, chapters)).assertIsDisplayed()
        compose.onNodeWithText("3 results in 2 chapters").assertIsDisplayed()
    }

    @Test
    fun `one result in one chapter`() {
        showPanel(searchOf("Chapter one" to 1))

        compose.onNodeWithText("1 result in 1 chapter").assertIsDisplayed()
    }

    @Test
    fun `a search stopped at its limit says so`() {
        showPanel(searchOf("Chapter one" to 2, truncated = true))

        compose.onNodeWithText(text(R.string.reader_search_truncated)).assertIsDisplayed()
    }

    @Test
    fun `a search that found nothing says so`() {
        showPanel(searchOf())

        compose.onNodeWithText(text(R.string.reader_search_none)).assertIsDisplayed()
    }

    @Test
    fun `nothing is said before anything is searched`() {
        showPanel(SearchUi("c", null, false, 0.0, emptyList(), 0, false, null, false))

        compose.onNodeWithText(text(R.string.reader_search_none)).assertDoesNotExist()
    }

    @Test
    fun `the panel's close button closes the search`() {
        showPanel(searchOf("Chapter one" to 1))

        compose.onNodeWithContentDescription(text(R.string.reader_search_close)).performClick()

        assertThat(actions.calls).containsExactly("closeSearch")
    }

    // The bar on the page

    private val reading by lazy {
        ReaderUiState(
            title = "A book",
            phase = ReaderPhase.Reading(testBook(folder.newFile("book.epub"))),
            opened = true,
            toc = SampleToc,
            location = location(),
        )
    }

    private var page by mutableStateOf(ReaderUiState())
    private var composed = false

    private fun showPage(state: ReaderUiState) {
        page = state
        if (composed) return
        composed = true
        compose.setContent {
            LirelleTheme {
                ReaderContent(
                    state = page,
                    actions = actions,
                    engineSlot = { Box(it.testTag(EngineTag).clickable { actions.toggleControls() }) },
                )
            }
        }
    }

    private val onPage = searchOf("Chapter one" to 20, "Chapter two" to 7, current = 2, onPage = true)

    @Test
    fun `the bar says which result is shown, out of how many`() {
        showPage(reading.copy(search = onPage))

        compose.onNodeWithTag(SearchBarTag).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.reader_search_counter, 3, 27)).assertIsDisplayed()
        compose.onNodeWithText("3 / 27").assertIsDisplayed()
        compose.onNodeWithContentDescription("Result 3 of 27").assertExists()
    }

    @Test
    fun `the counter is said again when another result is shown, not while the search runs or before a result`() {
        showPage(reading.copy(search = onPage))
        assertThat(counterLiveRegion()).isEqualTo(LiveRegionMode.Polite)

        showPage(reading.copy(search = onPage.copy(running = true)))
        assertThat(counterLiveRegion()).isNull()

        showPage(reading.copy(search = onPage.copy(current = null)))
        assertThat(counterLiveRegion()).isNull()
    }

    private fun counterLiveRegion() =
        compose.onNode(hasText("/", substring = true) and hasAnyAncestor(hasTestTag(SearchBarTag)))
            .fetchSemanticsNode().config.getOrNull(SemanticsProperties.LiveRegion)

    @Test
    fun `the next arrow stays in place whatever the counter says`() {
        showPage(reading.copy(search = onPage.copy(current = 0)))
        val before = compose.onNodeWithContentDescription(text(R.string.reader_search_next)).getUnclippedBoundsInRoot()

        showPage(reading.copy(search = searchOf("Chapter one" to 1_000, current = 999, onPage = true)))
        val after = compose.onNodeWithContentDescription(text(R.string.reader_search_next)).getUnclippedBoundsInRoot()

        assertThat(after.left.value).isWithin(0.5f).of(before.left.value)
    }

    @Test
    fun `before any result is shown the bar counts them only`() {
        showPage(reading.copy(search = onPage.copy(current = null)))

        compose.onNodeWithText(text(R.string.reader_search_counter_none, 27)).assertIsDisplayed()
        compose.onNodeWithContentDescription("27 results").assertExists()
    }

    private val stopped = searchOf("Chapter one" to 1_000, current = 2, onPage = true, truncated = true)

    @Test
    fun `a search stopped at its limit, the bar says there are more`() {
        showPage(reading.copy(search = stopped))
        compose.onNodeWithText("3\u00A0/\u00A01000+").assertIsDisplayed()
        compose.onNodeWithContentDescription("Result 3 of more than 1,000").assertExists()

        showPage(reading.copy(search = stopped.copy(current = null)))
        compose.onNodeWithText("–\u00A0/\u00A01000+").assertIsDisplayed()
        compose.onNodeWithContentDescription("More than 1,000 results").assertExists()
    }

    @Test
    @Config(qualifiers = "fr-w411dp-h891dp")
    fun `in French, a search stopped at its limit, the bar says there are more`() {
        showPage(reading.copy(search = stopped))
        compose.onNodeWithText("3\u00A0/\u00A01000+").assertIsDisplayed()
        compose.onNodeWithContentDescription("Résultat 3 sur plus de 1\u202F000").assertExists()

        showPage(reading.copy(search = stopped.copy(current = null)))
        compose.onNodeWithContentDescription("Plus de 1\u202F000 résultats").assertExists()
    }

    @Test
    fun `the next arrow stays in place when the counter says there are more`() {
        showPage(reading.copy(search = onPage.copy(current = 0)))
        val before = compose.onNodeWithContentDescription(text(R.string.reader_search_next)).getUnclippedBoundsInRoot()

        showPage(reading.copy(search = stopped.copy(current = 999)))
        val after = compose.onNodeWithContentDescription(text(R.string.reader_search_next)).getUnclippedBoundsInRoot()

        assertThat(after.left.value).isWithin(0.5f).of(before.left.value)
    }

    @Test
    fun `the bar's buttons, each with a spoken label`() {
        showPage(reading.copy(search = onPage))

        listOf(
            R.string.reader_search_previous,
            R.string.reader_search_next,
            R.string.reader_search_back_to_results,
            R.string.reader_search_close,
        ).forEach {
            compose.onNodeWithContentDescription(text(it)).assertHeightIsAtLeast(48.dp).performClick()
        }

        assertThat(actions.calls)
            .containsExactly("previousResult", "nextResult", "backToResults", "closeSearch")
            .inOrder()
    }

    @Test
    fun `a tap on the bar does not reach the page, which would open the controls`() {
        showPage(reading.copy(search = onPage))

        compose.onNodeWithText(text(R.string.reader_search_counter, 3, 27)).performClick()
        compose.onNodeWithContentDescription(text(R.string.reader_search_next)).performClick()

        assertThat(actions.calls).containsExactly("nextResult")
    }

    @Test
    fun `no bar while the search is only in its panel, nor without a search`() {
        showPage(reading.copy(search = onPage.copy(onPage = false)))
        compose.onNodeWithTag(SearchBarTag).assertDoesNotExist()

        showPage(reading)
        compose.onNodeWithTag(SearchBarTag).assertDoesNotExist()
    }

    @Test
    fun `no bar while a footnote is open, a selection is made, or the results are shown again`() {
        showPage(reading.copy(search = onPage, noteOpen = true))
        compose.onNodeWithTag(SearchBarTag).assertDoesNotExist()

        val selection = SelectionUi(
            "epubcfi(/6/2!/4,/1:0,/1:4)",
            "word",
            0,
            PageBounds(0.1, 0.8, 0.9, 0.85),
            null,
        )
        showPage(reading.copy(search = onPage, selection = selection))
        compose.onNodeWithTag(SearchBarTag).assertDoesNotExist()

        showPage(reading.copy(search = onPage, openSheet = ReaderSheet.Search))
        compose.onNodeWithTag(SearchBarTag).assertDoesNotExist()
    }

    private fun bounds(tag: String) = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()

    @Test
    fun `the bar takes its own room under the text, above the footer, and the text is whole again once it goes`() {
        showPage(reading)
        val whole = bounds(EngineTag)

        showPage(reading.copy(search = onPage))
        compose.waitForIdle()
        val page = bounds(EngineTag)
        val bar = bounds(SearchBarTag)
        assertThat(page.bottom.value).isAtMost(bar.top.value)
        assertThat(page.bottom.value).isLessThan(whole.bottom.value)
        assertThat(bar.bottom.value).isAtMost(bounds(RunningFooterTag).top.value)

        showPage(reading.copy(search = onPage.copy(onPage = false)))
        compose.waitForIdle()
        assertThat(bounds(EngineTag)).isEqualTo(whole)
    }

    @Test
    fun `with the controls the bar sits just above their bar, and the text keeps its room`() {
        showPage(reading.copy(search = onPage, goBackShown = true))
        val page = bounds(EngineTag)

        showPage(reading.copy(search = onPage, goBackShown = true, controlsShown = true))
        compose.waitForIdle()
        val bar = bounds(SearchBarTag)
        val controls = bounds(ReaderBottomBarTag)
        assertThat(bar.bottom.value).isAtMost(controls.top.value)
        assertThat(bar.bottom.value).isGreaterThan(controls.top.value - 24f)
        assertThat(bounds(EngineTag)).isEqualTo(page)
        compose.onNodeWithTag(GoBackTag).assertDoesNotExist()
    }

    @Test
    fun `with the controls a tap on the bar reaches its buttons, not the page`() {
        showPage(reading.copy(search = onPage, controlsShown = true))
        compose.waitForIdle()

        compose.onNodeWithContentDescription(text(R.string.reader_search_next)).performClick()

        assertThat(actions.calls).containsExactly("nextResult")
    }

    @Test
    fun `while the bar is shown go back is one of its buttons, not over the page`() {
        showPage(reading.copy(search = onPage, goBackShown = true))

        compose.onNodeWithTag(GoBackTag).assertDoesNotExist()
        val inBar = hasAnyAncestor(hasTestTag(SearchBarTag))
        compose.onNode(hasContentDescription(text(R.string.reader_go_back)) and inBar)
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        assertThat(actions.calls).containsExactly("goBack")
    }

    @Test
    fun `the bar offers go back only after a jump, and go back floats again once the bar is gone`() {
        showPage(reading.copy(search = onPage))
        compose.onNodeWithContentDescription(text(R.string.reader_go_back)).assertDoesNotExist()

        showPage(reading.copy(search = onPage.copy(onPage = false), goBackShown = true))
        compose.onNodeWithTag(GoBackTag).assertIsDisplayed()
        compose.onNodeWithTag(SearchBarTag).assertDoesNotExist()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `on a phone the bar holds on one line, go back and the widest counter included`() {
        showPage(reading.copy(search = stopped.copy(current = 999), goBackShown = true))

        assertThat(bounds(SearchBarTag).height.value).isAtMost(64f)
    }

    @Test
    fun `the text keeps its room while results arrive and go back comes or goes`() = assertRoomIsStable()

    @Test
    @Config(qualifiers = "fr-w320dp-h640dp", fontScale = 2f)
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `on a narrow screen in French with large text, the text keeps its room as the bar changes`() =
        assertRoomIsStable()

    @Test
    @Config(qualifiers = "w360dp-h780dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `on a screen where go back takes the bar to two lines, the text keeps its room as it comes or goes`() =
        assertRoomIsStable()

    private fun assertRoomIsStable() {
        showPage(reading.copy(search = searchOf(running = true, onPage = true)))
        compose.waitForIdle()
        val page = bounds(EngineTag)
        listOf(
            reading.copy(search = searchOf("Chapter one" to 3, running = true, current = 0, onPage = true)),
            reading.copy(search = onPage, goBackShown = true),
            reading.copy(search = onPage),
            reading.copy(search = stopped, goBackShown = true),
            reading.copy(search = stopped.copy(current = null)),
        ).forEach {
            showPage(it)
            compose.waitForIdle()
            assertThat(bounds(EngineTag)).isEqualTo(page)
        }
    }

    @Test
    fun `without the controls the bar is at the bottom of the screen, just above the footer`() {
        showPage(reading.copy(search = onPage))
        val footer = compose.onNodeWithTag(RunningFooterTag).getUnclippedBoundsInRoot()
        val bar = compose.onNodeWithTag(SearchBarTag).getUnclippedBoundsInRoot()
        assertThat(bar.bottom.value).isGreaterThan(footer.top.value - 24f)

        val nothing = RunningLine(RunningSlot.Nothing, RunningSlot.Nothing)
        showPage(reading.copy(search = onPage, settings = ReaderSettings(footer = nothing)))
        compose.waitForIdle()
        val lowered = compose.onNodeWithTag(SearchBarTag).getUnclippedBoundsInRoot()
        assertThat(lowered.bottom.value).isGreaterThan(891f - 24f)
    }

    @Test
    fun `the bar and undo stack above the running footer, clear of the header`() {
        showPage(reading.copy(search = onPage, goBackShown = true, undo = UndoUi.Highlight(MarkId("h1"))))
        compose.waitForIdle()

        val header = bounds(RunningHeaderTag)
        val footer = bounds(RunningFooterTag)
        val bar = bounds(SearchBarTag)
        val undo = bounds(UndoBarTag)
        assertThat(bar.bottom.value).isAtMost(footer.top.value)
        assertThat(undo.bottom.value).isAtMost(bar.top.value)
        assertThat(undo.top.value).isAtLeast(header.bottom.value)
    }

    @Test
    fun `go back is hidden while a footnote is open, and comes back when it closes`() {
        showPage(reading.copy(goBackShown = true, noteOpen = true))
        compose.onNodeWithTag(GoBackTag).assertDoesNotExist()

        showPage(reading.copy(goBackShown = true, noteOpen = false))
        compose.onNodeWithTag(GoBackTag).assertIsDisplayed()
    }

    @Test
    fun `alone, go back is in the running footer and undo stays above it`() {
        showPage(reading.copy(goBackShown = true))
        val footer = compose.onNodeWithTag(RunningFooterTag).getUnclippedBoundsInRoot()
        val goBack = compose.onNodeWithTag(GoBackTag).getUnclippedBoundsInRoot()
        // It may rise above the footer's line into the empty band at the page's bottom, no further.
        assertThat(goBack.top.value).isAtLeast((footer.top - PageBottomBand).value - 0.5f)
        assertThat(goBack.bottom.value).isAtMost(footer.bottom.value + 0.5f)

        showPage(reading.copy(undo = UndoUi.Highlight(MarkId("h1"))))
        compose.waitForIdle()
        assertThat(compose.onNodeWithTag(UndoBarTag).getUnclippedBoundsInRoot().bottom.value)
            .isAtMost(footer.top.value)
    }

    @Test
    fun `undo is offered above the bar, with or without the controls`() {
        val undone = reading.copy(search = onPage, goBackShown = true, undo = UndoUi.Highlight(MarkId("h1")))
        showPage(undone)
        compose.waitForIdle()
        assertThat(bounds(UndoBarTag).bottom.value).isAtMost(bounds(SearchBarTag).top.value)

        showPage(undone.copy(controlsShown = true))
        compose.waitForIdle()
        val bar = bounds(SearchBarTag)
        assertThat(bounds(UndoBarTag).bottom.value).isAtMost(bar.top.value)
        assertThat(bar.bottom.value).isAtMost(bounds(ReaderBottomBarTag).top.value)
    }

    @Test
    fun `the search's panel opens from the state`() {
        showPage(reading.copy(search = onPage.copy(onPage = false), openSheet = ReaderSheet.Search))

        compose.onNodeWithTag(SearchPanelTag).assertIsDisplayed()
    }

    @Test
    fun `with the search's panel open, undo is offered once, inside the panel, whose window covers the page's`() {
        showPage(
            reading.copy(
                search = onPage.copy(onPage = false),
                openSheet = ReaderSheet.Search,
                undo = UndoUi.Highlight(MarkId("h1")),
            ),
        )

        assertThat(compose.onAllNodesWithTag(UndoBarTag).fetchSemanticsNodes()).hasSize(1)
        compose.onNode(hasTestTag(UndoBarTag) and hasAnyAncestor(hasTestTag(SearchPanelTag))).assertIsDisplayed()
    }

    private companion object {
        const val EngineTag = "engine"
    }
}
