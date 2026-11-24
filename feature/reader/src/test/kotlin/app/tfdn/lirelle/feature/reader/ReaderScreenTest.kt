package app.tfdn.lirelle.feature.reader

import androidx.compose.ui.unit.height
import androidx.compose.ui.test.onRoot
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.library.model.DownloadProgress
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.reader.marks.BookMarks
import app.tfdn.lirelle.core.reader.marks.HighlightColour
import app.tfdn.lirelle.core.reader.marks.MarkId
import app.tfdn.lirelle.core.reader.settings.ReaderSettings
import app.tfdn.lirelle.core.reader.settings.RunningLine
import app.tfdn.lirelle.core.reader.settings.RunningSlot
import app.tfdn.lirelle.core.testing.sampleBookmark
import app.tfdn.lirelle.core.testing.sampleHighlight
import app.tfdn.lirelle.feature.reader.bridge.PageBounds
import app.tfdn.lirelle.feature.reader.web.testBook
import com.google.common.truth.Truth.assertThat
import java.time.Duration
import java.time.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import app.tfdn.lirelle.core.ui.R as UiR

/** The header's line plus the band the page keeps clear of text at its top. */
private val BandAboveText = 48.dp

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class ReaderScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val folder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val actions = RecordingActions()

    private val reading by lazy {
        ReaderUiState(
            title = "A book",
            phase = ReaderPhase.Reading(testBook(folder.newFile("book.epub"))),
            opened = true,
            toc = SampleToc,
            location = location(),
        )
    }

    /** Without header nor footer, which would say the chapter and the percentage a second time. */
    private val bare by lazy {
        val nothing = RunningLine(RunningSlot.Nothing, RunningSlot.Nothing)
        reading.copy(settings = ReaderSettings(header = nothing, footer = nothing))
    }

    private fun text(id: Int, vararg args: Any) = context.getString(id, *args)

    private fun show(
        state: ReaderUiState,
        device: DeviceReadings = DeviceReadings(),
        sheets: @Composable () -> Unit = { Box(Modifier.testTag(SheetsTag)) },
    ) {
        compose.setContent {
            LirelleTheme {
                ReaderContent(
                    state = state,
                    actions = actions,
                    engineSlot = { Box(it.testTag(EngineTag)) },
                    sheets = sheets,
                    endPanel = { Box(Modifier.testTag(EndPanelTag)) },
                    device = device,
                )
            }
        }
    }

    private fun failed(reason: ReaderFailure) = ReaderUiState(title = "A book", phase = ReaderPhase.Failed(reason))

    // Opening

    @Test
    fun `a download shows the title, how far it is and Cancel`() {
        val progress = DownloadProgress(bytesRead = 1_000_000, totalBytes = 4_000_000)
        show(ReaderUiState(title = "A book", phase = ReaderPhase.Downloading(progress)))

        compose.onNodeWithText("A book").assertIsDisplayed()
        val bar = compose.onNodeWithTag(DownloadProgressTag).fetchSemanticsNode()
        assertThat(bar.config[SemanticsProperties.ProgressBarRangeInfo].current).isWithin(0.001f).of(0.25f)
        compose.onNodeWithText("4", substring = true).assertIsDisplayed()
        // The web view is there already, under the download, and its page gets ready meanwhile.
        compose.onNodeWithTag(EngineTag).assertExists()
        compose.onNodeWithTag(ReaderPageTag).assertDoesNotExist()

        compose.onNodeWithText(text(R.string.reader_cancel)).performClick()
        assertThat(actions.calls).containsExactly("leave")
    }

    @Test
    fun `a download that has not said its size shows a bar that does not fill`() {
        show(ReaderUiState(title = "A book", phase = ReaderPhase.Downloading(null)))

        val bar = compose.onNodeWithTag(DownloadProgressTag).fetchSemanticsNode()
        assertThat(bar.config[SemanticsProperties.ProgressBarRangeInfo]).isEqualTo(ProgressBarRangeInfo.Indeterminate)
        compose.onNodeWithText(text(R.string.reader_downloading)).assertIsDisplayed()
    }

    @Test
    fun `a failed download says why, and can be tried again`() {
        show(failed(ReaderFailure.Download(ApiError.ServerUnreachable)))

        compose.onNodeWithText(text(UiR.string.library_error_unreachable)).assertIsDisplayed()
        compose.onNodeWithText(text(UiR.string.state_retry)).performClick()
        compose.onNodeWithText(text(UiR.string.navigate_back)).performClick()
        assertThat(actions.calls).containsExactly("retry", "leave").inOrder()
    }

    @Test
    fun `a book that is gone cannot be tried again`() {
        show(failed(ReaderFailure.Download(ApiError.NotFound)))

        compose.onNodeWithText(text(UiR.string.library_error_not_found)).assertIsDisplayed()
        compose.onNodeWithText(text(UiR.string.state_retry)).assertDoesNotExist()
        compose.onNodeWithText(text(UiR.string.navigate_back)).assertIsDisplayed()
    }

    @Test
    fun `a page that could not show the book can be tried again`() {
        show(failed(ReaderFailure.Engine))

        compose.onNodeWithText(text(R.string.reader_failure_engine)).assertIsDisplayed()
        compose.onNodeWithText(text(UiR.string.state_retry)).performClick()
        assertThat(actions.calls).containsExactly("retry")
    }

    @Test
    fun `a file that cannot be read only offers to go back`() {
        val messages = mapOf(
            ReaderFailure.NotAnEpub to R.string.reader_failure_not_an_epub,
            ReaderFailure.Protected to R.string.reader_failure_protected,
            ReaderFailure.NoEpub to R.string.reader_failure_no_epub,
            ReaderFailure.OldWebView to R.string.reader_failure_old_web_view,
            ReaderFailure.DownloadNotAllowed to R.string.reader_failure_download_not_allowed,
        )
        var state by mutableStateOf(failed(ReaderFailure.NotAnEpub))
        compose.setContent { LirelleTheme { ReaderContent(state, actions, engineSlot = {}) } }

        messages.forEach { (reason, message) ->
            state = failed(reason)
            compose.onNodeWithText(text(message)).assertIsDisplayed()
            compose.onNodeWithText(text(UiR.string.state_retry)).assertDoesNotExist()
            compose.onNodeWithText(text(UiR.string.navigate_back)).assertIsDisplayed()
        }
        assertThat(text(R.string.reader_failure_old_web_view)).contains("Android System WebView")
    }

    @Test
    fun `a book still opening shows a spinner over the page`() {
        show(reading.copy(opened = false))

        compose.onNodeWithTag(EngineTag).assertExists()
        compose.onNodeWithTag(ReaderOpeningTag).assertIsDisplayed()
    }

    @Test
    fun `an opened book shows the page alone`() {
        show(reading)

        compose.onNodeWithTag(EngineTag).assertIsDisplayed()
        compose.onNodeWithTag(ReaderOpeningTag).assertDoesNotExist()
        compose.onNodeWithTag(ReaderTopBarTag).assertDoesNotExist()
        compose.onNodeWithTag(ReaderBottomBarTag).assertDoesNotExist()
        compose.onNodeWithTag(GoBackTag).assertDoesNotExist()
        compose.onNodeWithTag(PositionBannerTag).assertDoesNotExist()
        compose.onNodeWithTag(SheetsTag).assertExists()
        compose.onNodeWithTag(EndPanelTag).assertExists()
    }

    // Controls

    @Test
    fun `the top bar has back, the title, search, bookmark, the panel and settings, each with a spoken label`() {
        show(bare.copy(controlsShown = true))

        compose.onNodeWithText("A book").assertIsDisplayed()
        listOf(
            text(UiR.string.navigate_back),
            text(R.string.reader_search_book),
            text(R.string.reader_bookmark_add),
            text(R.string.reader_panel),
            text(R.string.reader_settings),
        ).forEach { label ->
            compose.onNodeWithContentDescription(label)
                .assertWidthIsAtLeast(48.dp)
                .assertHeightIsAtLeast(48.dp)
                .performClick()
        }
        assertThat(actions.calls).containsExactly(
            "leave",
            "openSearch",
            "toggleBookmark",
            "showSheet(Contents)",
            "showSheet(Settings)",
        ).inOrder()
    }

    @Test
    fun `the top bar's buttons come in their order after the title`() {
        show(bare.copy(controlsShown = true))

        val lefts = listOf(
            text(UiR.string.navigate_back),
            text(R.string.reader_search_book),
            text(R.string.reader_bookmark_add),
            text(R.string.reader_panel),
            text(R.string.reader_settings),
        ).map { compose.onNodeWithContentDescription(it).getUnclippedBoundsInRoot().left.value }
        assertThat(lefts).isInStrictOrder()
        val title = compose.onNodeWithText("A book").getUnclippedBoundsInRoot()
        assertThat(title.left.value).isGreaterThan(lefts[0])
        assertThat(title.right.value).isAtMost(lefts[1])
    }

    @Test
    fun `on a page holding a bookmark the button removes it`() {
        show(bare.copy(controlsShown = true, pageBookmarks = listOf(MarkId("b1"))))

        compose.onNodeWithContentDescription(text(R.string.reader_bookmark_add)).assertDoesNotExist()
        compose.onNodeWithContentDescription(text(R.string.reader_bookmark_remove)).performClick()
        assertThat(actions.calls).containsExactly("toggleBookmark")
    }

    @Test
    fun `while a page turns the button shows no bookmark`() {
        show(bare.copy(controlsShown = true, pageBookmarks = listOf(MarkId("b1")), ribbonHidden = true))

        compose.onNodeWithContentDescription(text(R.string.reader_bookmark_remove)).assertDoesNotExist()
        compose.onNodeWithContentDescription(text(R.string.reader_bookmark_add)).performClick()
        assertThat(actions.calls).containsExactly("toggleBookmark")
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp", fontScale = 2f)
    fun `on a narrow screen with large text the title gives way and the buttons keep their size`() {
        show(bare.copy(controlsShown = true, title = LongTitle))

        listOf(
            text(UiR.string.navigate_back),
            text(R.string.reader_search_book),
            text(R.string.reader_bookmark_add),
            text(R.string.reader_panel),
            text(R.string.reader_settings),
        ).forEach { label ->
            compose.onNodeWithContentDescription(label).assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        }
        val bar = compose.onNodeWithTag(ReaderTopBarTag).getUnclippedBoundsInRoot()
        val settings = compose.onNodeWithContentDescription(text(R.string.reader_settings)).getUnclippedBoundsInRoot()
        assertThat(settings.right.value).isAtMost(bar.right.value)
    }

    @Test
    fun `the bottom bar shows the chapter and the percentage, and moves by chapter`() {
        show(bare.copy(controlsShown = true))

        compose.onNodeWithText("Two").assertIsDisplayed()
        compose.onNodeWithText(text(UiR.string.progress_percent, 42)).assertIsDisplayed()
        listOf(R.string.reader_previous_chapter, R.string.reader_next_chapter).forEach {
            compose.onNodeWithText(text(it)).assertHeightIsAtLeast(48.dp).performClick()
        }
        assertThat(actions.calls).containsExactly("previousChapter", "nextChapter").inOrder()
    }

    @Test
    fun `the slider says the percentage and seeks when it is released, not before`() {
        show(bare.copy(controlsShown = true))
        val slider = compose.onNodeWithTag(ReaderSliderTag)

        val config = slider.fetchSemanticsNode().config
        assertThat(config.getOrNull(SemanticsProperties.StateDescription))
            .isEqualTo(text(UiR.string.progress_percent, 42))
        assertThat(config.getOrNull(SemanticsProperties.ContentDescription))
            .containsExactly(text(R.string.reader_position_slider))

        slider.performTouchInput {
            down(center)
            moveTo(centerRight)
        }
        assertThat(actions.calls).isEmpty()
        // While the finger slides, the percentage follows it.
        compose.onNodeWithText(text(UiR.string.progress_percent, 42)).assertDoesNotExist()

        slider.performTouchInput { up() }
        val sought = actions.calls.single().removeSurrounding("seekTo(", ")").toDouble()
        assertThat(sought).isGreaterThan(0.9)
    }

    @Test
    fun `a slider released where the book already is goes back to the book's place`() {
        show(bare.copy(controlsShown = true))
        val slider = compose.onNodeWithTag(ReaderSliderTag)

        slider.performTouchInput {
            down(center)
            moveTo(centerRight)
            up()
        }

        // The page has not moved, and says nothing: the thumb must not stay where the finger left it.
        compose.onNodeWithText(text(UiR.string.progress_percent, 42)).assertIsDisplayed()
        val range = slider.fetchSemanticsNode().config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)
        assertThat(range?.current).isWithin(0.001f).of(0.426f)
    }

    @Test
    fun `the banner offers the newer position, to go there or to ignore it`() {
        show(reading.copy(newerPosition = OfferedPosition(percent = 41.7, updatedAt = null)))

        compose.onNodeWithText(text(R.string.reader_newer_position, 41)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.reader_newer_position_ignore)).performClick()
        compose.onNodeWithText(text(R.string.reader_newer_position_go)).performClick()
        assertThat(actions.calls).containsExactly("ignoreNewerPosition", "acceptNewerPosition").inOrder()
    }

    @Test
    fun `the banner says how long ago the newer position was written`() {
        val now = Instant.parse("2026-11-14T10:00:00Z")
        val offered = OfferedPosition(percent = 41.7, updatedAt = now.minus(Duration.ofHours(2)))
        show(reading.copy(newerPosition = offered), DeviceReadings(now = now))

        compose.onNodeWithText("Newer position: 41%, 2 hours ago").assertIsDisplayed()
    }

    @Test
    fun `a newer position written seconds ago, or a little ahead of the device, was written just now`() {
        val now = Instant.parse("2026-11-14T10:00:00Z")
        var offered by mutableStateOf(OfferedPosition(percent = 41.7, updatedAt = now.minusSeconds(20)))
        compose.setContent {
            LirelleTheme {
                ReaderContent(
                    reading.copy(newerPosition = offered),
                    actions,
                    engineSlot = {},
                    device = DeviceReadings(now = now),
                )
            }
        }

        compose.onNodeWithText("Newer position: 41%, just now").assertIsDisplayed()
        offered = offered.copy(updatedAt = now.plusSeconds(30))
        compose.onNodeWithText("Newer position: 41%, just now").assertIsDisplayed()
    }

    @Test
    fun `the banner says no time when it is not known, on either side`() {
        val now = Instant.parse("2026-11-14T10:00:00Z")
        var offered by mutableStateOf(OfferedPosition(percent = 41.7, updatedAt = null))
        var device by mutableStateOf(DeviceReadings(now = now))
        compose.setContent {
            LirelleTheme {
                ReaderContent(reading.copy(newerPosition = offered), actions, engineSlot = {}, device = device)
            }
        }
        compose.onNodeWithText(text(R.string.reader_newer_position, 41)).assertIsDisplayed()

        offered = OfferedPosition(percent = 41.7, updatedAt = now.minus(Duration.ofHours(2)))
        device = DeviceReadings()

        compose.onNodeWithText(text(R.string.reader_newer_position, 41)).assertIsDisplayed()
    }

    @Test
    fun `a newer position stamped ahead of this device's clock is of now, not of the future`() {
        val now = Instant.parse("2026-11-14T10:00:00Z")
        val offered = OfferedPosition(percent = 41.7, updatedAt = now.plus(Duration.ofMinutes(5)))
        show(reading.copy(newerPosition = offered), DeviceReadings(now = now))

        compose.onNodeWithText("Newer position: 41%, just now").assertIsDisplayed()
    }

    @Test
    fun `a link to the web asks first, showing the address`() {
        show(reading.copy(externalLink = LongUrl))

        compose.onNodeWithText(LongUrl).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.reader_link_open)).performClick()
        compose.onNodeWithText(text(R.string.reader_cancel)).performClick()
        assertThat(actions.calls).containsExactly("confirmExternalLink", "dismissExternalLink").inOrder()
    }

    @Test
    fun `the panel opens from the state, on its tab`() {
        show(reading.copy(openSheet = ReaderSheet.Contents))

        compose.onNodeWithText(text(R.string.reader_tab_contents)).assertIsSelected()
        compose.onNodeWithText("Three").performClick()
        assertThat(actions.calls).containsExactly("goToToc(2)")
    }

    @Test
    fun `the panel tells the dates of the marks from the moment given`() {
        val marks = BookMarks(bookmarks = listOf(sampleBookmark("b1", "epubcfi(/6/4!/4/2/1:0)", createdAt = Now)))
        val state = reading.copy(openSheet = ReaderSheet.Contents, panelTab = PanelTab.Bookmarks, marks = marks)
        show(state, device = DeviceReadings(now = Now.plus(Duration.ofDays(1))))

        compose.onNodeWithText(text(UiR.string.date_yesterday), substring = true).assertIsDisplayed()
    }

    // Header and footer

    @Test
    fun `the header and the footer show what their slots say`() {
        val settings = ReaderSettings(
            header = RunningLine(RunningSlot.BookTitle, RunningSlot.Time),
            footer = RunningLine(RunningSlot.ChapterTitle, RunningSlot.Battery),
        )
        show(reading.copy(settings = settings), DeviceReadings(time = "21:07", batteryPercent = 85))

        compose.onNodeWithText("A book").assertIsDisplayed()
        compose.onNodeWithText("21:07").assertIsDisplayed()
        compose.onNodeWithText("Two").assertIsDisplayed()
        compose.onNodeWithText(text(UiR.string.progress_percent, 85)).assertIsDisplayed()
        val header = compose.onNodeWithTag(RunningHeaderTag).getUnclippedBoundsInRoot()
        val page = compose.onNodeWithTag(EngineTag).getUnclippedBoundsInRoot()
        val footer = compose.onNodeWithTag(RunningFooterTag).getUnclippedBoundsInRoot()
        assertThat(header.bottom.value).isAtMost(page.top.value)
        assertThat(footer.top.value).isAtLeast(page.bottom.value)
    }

    @Test
    fun `the default lines show the chapter, the pages left and the percentage, and no time unless given`() {
        show(reading)

        compose.onNodeWithText("Two").assertIsDisplayed()
        val pagesLeft = context.resources.getQuantityString(R.plurals.reader_pages_left, 7, 7)
        compose.onNodeWithText(pagesLeft).assertIsDisplayed()
        compose.onNodeWithText(text(UiR.string.progress_percent, 42)).assertIsDisplayed()
    }

    @Test
    fun `the pages left are not shown when the page does not count them`() {
        val settings = ReaderSettings(footer = RunningLine(RunningSlot.PagesLeftInChapter, RunningSlot.Nothing))
        show(reading.copy(settings = settings, location = location(page = null, pages = null)))

        compose.onNodeWithTag(RunningFooterTag).assertDoesNotExist()
    }

    @Test
    fun `the percentage of the chapter is shown beside that of the book, without a prefix`() {
        val settings = ReaderSettings(
            header = RunningLine(RunningSlot.Nothing, RunningSlot.Nothing),
            footer = RunningLine(RunningSlot.ChapterPercent, RunningSlot.BookPercent),
        )
        show(reading.copy(settings = settings, location = location(page = 2, pages = 9)))

        compose.onNodeWithText(text(UiR.string.progress_percent, 22)).assertIsDisplayed()
        compose.onNodeWithText(text(UiR.string.progress_percent, 42)).assertIsDisplayed()
    }

    @Test
    fun `the percentage of the chapter reaches 100 on its last page`() {
        val settings = ReaderSettings(footer = RunningLine(RunningSlot.ChapterPercent, RunningSlot.Nothing))
        show(reading.copy(settings = settings, location = location(page = 9, pages = 9)))

        compose.onNodeWithText(text(UiR.string.progress_percent, 100)).assertIsDisplayed()
    }

    @Test
    fun `the percentage of the chapter is not shown when the page does not count its pages`() {
        val settings = ReaderSettings(footer = RunningLine(RunningSlot.ChapterPercent, RunningSlot.Nothing))
        show(reading.copy(settings = settings, location = location(page = null, pages = null)))

        compose.onNodeWithTag(RunningFooterTag).assertDoesNotExist()
    }

    @Test
    fun `a line with nothing in its two slots takes no room`() {
        show(bare)

        compose.onNodeWithTag(RunningHeaderTag).assertDoesNotExist()
        compose.onNodeWithTag(RunningFooterTag).assertDoesNotExist()
        val page = compose.onNodeWithTag(EngineTag).getUnclippedBoundsInRoot()
        assertThat(page.top.value).isEqualTo(0f)
        assertThat(page.bottom.value).isEqualTo(891f)
    }

    @Test
    fun `the search's bar has its own room between the page and the footer, go back in it`() {
        val search = searchOf("Chapter one" to 3, current = 0, onPage = true)
        show(reading.copy(search = search, goBackShown = true))

        val page = compose.onNodeWithTag(EngineTag).getUnclippedBoundsInRoot()
        val bar = compose.onNodeWithTag(SearchBarTag).getUnclippedBoundsInRoot()
        val footer = compose.onNodeWithTag(RunningFooterTag).getUnclippedBoundsInRoot()
        assertThat(page.bottom.value).isAtMost(bar.top.value)
        assertThat(bar.bottom.value).isAtMost(footer.top.value)
        compose.onNodeWithTag(GoBackTag).assertDoesNotExist()
        val inBar = hasAnyAncestor(hasTestTag(SearchBarTag))
        compose.onNode(hasContentDescription(text(R.string.reader_go_back)) and inBar)
            .assertIsDisplayed()
    }

    // Screen readers

    @Test
    fun `the page offers the controls and the page turns to a screen reader`() {
        show(reading)

        val page = compose.onNodeWithTag(ReaderPageTag).fetchSemanticsNode().config
        val offered = page[SemanticsActions.CustomActions]
        assertThat(offered.map { it.label }).containsExactly(
            text(R.string.reader_show_controls),
            text(R.string.reader_next_page_right),
            text(R.string.reader_previous_page_left),
        ).inOrder()

        offered.forEach { assertThat(it.action()).isTrue() }
        assertThat(actions.calls).containsExactly("toggleControls", "nextPage", "previousPage").inOrder()
    }

    @Test
    fun `in a right-to-left book the spoken hints name the other edges`() {
        show(reading.copy(rtl = true, controlsShown = true))

        val page = compose.onNodeWithTag(ReaderPageTag).fetchSemanticsNode().config
        assertThat(page[SemanticsActions.CustomActions].map { it.label }).containsExactly(
            text(R.string.reader_hide_controls),
            text(R.string.reader_next_page_left),
            text(R.string.reader_previous_page_right),
        ).inOrder()
    }

    @Test
    fun `the brightness is shown while a finger changes it, and only then`() {
        show(bare.copy(adjustingBrightness = true, settings = bare.settings.copy(brightness = 0.4f)))

        compose.onNodeWithTag(BrightnessIndicatorTag).assertIsDisplayed()
        compose.onNodeWithText(text(UiR.string.progress_percent, 40), useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `the brightness is shown above the text, not over it`() {
        show(bare.copy(adjustingBrightness = true, settings = bare.settings.copy(brightness = 0.4f)))

        val indicator = compose.onNodeWithTag(BrightnessIndicatorTag).getUnclippedBoundsInRoot()
        val root = compose.onRoot().getUnclippedBoundsInRoot()
        assertThat(indicator.top).isEqualTo(root.top)
        assertThat(indicator.height).isAtMost(BandAboveText)
    }

    @Test
    fun `no brightness is shown when no finger changes it`() {
        show(bare.copy(settings = bare.settings.copy(brightness = 0.4f)))

        compose.onNodeWithTag(BrightnessIndicatorTag).assertDoesNotExist()
    }

    // Selection, highlights and undo

    private val selection = SelectionUi(
        cfi = "epubcfi(/6/2!/4,/1:0,/1:4)",
        text = "word",
        sectionIndex = 0,
        bounds = PageBounds(left = 0.1, top = 0.5, right = 0.9, bottom = 0.55),
        exactId = null,
    )

    @Test
    fun `no bar without a selection`() {
        show(reading)

        compose.onNodeWithTag(SelectionBarTag).assertDoesNotExist()
    }

    @Test
    fun `a selection has its bar over the page, laid out in the page's space`() {
        show(reading.copy(selection = selection))

        val page = compose.onNodeWithTag(EngineTag).getUnclippedBoundsInRoot()
        val bar = compose.onNodeWithTag(SelectionBarTag).getUnclippedBoundsInRoot()
        assertThat(bar.bottom.value).isAtMost((page.top + page.height * 0.5f).value)
        assertThat(bar.top.value).isAtLeast(page.top.value)
    }

    @Test
    fun `without an exact highlight the bar marks no colour, not even the last one used`() {
        val settings = reading.settings.copy(lastHighlightColour = HighlightColour.Pink.hex)
        show(reading.copy(selection = selection, settings = settings))

        compose.onNodeWithContentDescription(text(R.string.reader_highlight_pink)).assertIsNotSelected()
    }

    @Test
    fun `no bar of the controls covers the page while there is a selection`() {
        show(reading.copy(selection = selection, controlsShown = true))

        compose.onNodeWithTag(ReaderTopBarTag).assertDoesNotExist()
        compose.onNodeWithTag(ReaderBottomBarTag).assertDoesNotExist()
        compose.onNodeWithTag(SelectionBarTag).assertIsDisplayed()
    }

    @Test
    fun `the bar marks the colour of the exact highlight under the selection`() {
        val highlight = sampleHighlight("h1", selection.cfi, color = "#4ade80")
        show(
            reading.copy(
                selection = selection.copy(exactId = highlight.id),
                settings = reading.settings.copy(lastHighlightColour = HighlightColour.Pink.hex),
                marks = BookMarks(highlights = listOf(highlight)),
            ),
        )

        compose.onNodeWithContentDescription(text(R.string.reader_highlight_green)).assertIsSelected()
    }

    @Test
    fun `the highlight's panel is shown over the page`() {
        val panel = HighlightPanelUi(MarkId("h1"), "a passage", "#FACC15", "", "", focusNote = false, false)
        show(reading.copy(highlightPanel = panel))

        compose.onNodeWithTag(HighlightPanelTag).assertIsDisplayed()
        // A screen reader stays in the panel: the page and its bars behind it are not offered.
        compose.onNodeWithTag(ReaderPageTag).assertDoesNotExist()
    }

    @Test
    fun `a deletion offers undo at the bottom of the page`() {
        show(reading.copy(undo = UndoUi.Highlight(MarkId("h1"))))

        compose.onNodeWithText(text(R.string.reader_highlight_deleted)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.reader_undo)).performClick()
        assertThat(actions.calls).containsExactly("undo")
    }

    @Test
    fun `no undo is drawn over the panel of a footnote`() {
        show(reading.copy(undo = UndoUi.Highlight(MarkId("h1")), noteOpen = true))

        compose.onNodeWithTag(UndoBarTag).assertDoesNotExist()
    }

    @Test
    fun `undo is offered above an open sheet`() {
        show(reading.copy(undo = UndoUi.Highlight(MarkId("h1")))) {
            Box(Modifier.fillMaxSize().testTag(SheetsTag).clickable { actions.leave() })
        }

        compose.onNodeWithText(text(R.string.reader_undo)).performClick()

        assertThat(actions.calls).containsExactly("undo")
    }

    @Test
    fun `with the panel open, undo is offered once, inside the panel, whose window covers the page's`() {
        val marks = BookMarks(bookmarks = listOf(sampleBookmark("b2", "epubcfi(/6/4!/4/2/1:0)")))
        show(
            reading.copy(
                openSheet = ReaderSheet.Contents,
                panelTab = PanelTab.Bookmarks,
                marks = marks,
                undo = UndoUi.Bookmarks(listOf(MarkId("b1"))),
            ),
        )

        assertThat(compose.onAllNodesWithTag(UndoBarTag).fetchSemanticsNodes()).hasSize(1)
        compose.onNode(hasTestTag(UndoBarTag) and hasAnyAncestor(hasTestTag(ReaderPanelTag))).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.reader_undo)).performClick()
        assertThat(actions.calls).containsExactly("undo")
    }

    private companion object {
        const val EngineTag = "engine"
        const val SheetsTag = "sheets"
        const val EndPanelTag = "end-panel"
        val Now: Instant = Instant.parse("2026-11-14T10:00:00Z")
    }
}
