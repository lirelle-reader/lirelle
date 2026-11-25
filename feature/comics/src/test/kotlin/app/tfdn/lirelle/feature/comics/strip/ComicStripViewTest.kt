package app.tfdn.lirelle.feature.comics.strip

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import app.tfdn.lirelle.core.comics.PageState
import app.tfdn.lirelle.core.comics.layout.spreads
import app.tfdn.lirelle.core.comics.settings.ComicSettings
import app.tfdn.lirelle.core.comics.settings.Direction
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.feature.comics.ComicEndPanel
import app.tfdn.lirelle.feature.comics.ComicNavigation
import app.tfdn.lirelle.feature.comics.ComicPhase
import app.tfdn.lirelle.feature.comics.ComicReaderUiState
import app.tfdn.lirelle.feature.comics.Landing
import app.tfdn.lirelle.feature.comics.StripNavigation
import app.tfdn.lirelle.feature.comics.page.ComicPageLoadingTag
import app.tfdn.lirelle.feature.comics.page.ComicPageRetryTag
import app.tfdn.lirelle.feature.comics.page.PageImages
import app.tfdn.lirelle.feature.comics.page.RecordingNavigation
import app.tfdn.lirelle.feature.comics.page.drawn
import app.tfdn.lirelle.feature.comics.page.pageImage
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val Pages = 12

/** How long a slice may take to come from the thread it is decoded on. */
private const val SliceWait = 10_000L

/** Remembers what the strip asked for and told, in order. */
private class RecordingStrip(private val navigation: RecordingNavigation = RecordingNavigation()) :
    ComicNavigation by navigation,
    StripNavigation {
    val calls: MutableList<String> get() = navigation.calls

    /** The pages the strip said it shows, in order. */
    val shows: List<String> get() = calls.filter { it.startsWith("stripShows") }

    /** As "stripShows 3 4", with "end" at the bottom of the strip and "scrolled" once the user moved it. */
    override fun stripShows(first: Int, last: Int, atEnd: Boolean, scrolled: Boolean) {
        calls += "stripShows $first $last" + (if (atEnd) " end" else "") + (if (scrolled) " scrolled" else "")
    }

    override fun stripRested() {
        calls += "stripRested"
    }

    override fun stripPastEnd() {
        calls += "stripPastEnd"
    }
}

/**
 * The strip of an album read vertically, in a window of 400 by 800 pixels: a page of 400 by 600 is drawn as it
 * is, and the window shows one and a third.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w400dp-h800dp")
class ComicStripViewTest {

    @get:Rule(order = 0)
    val images = PageImages()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    @get:Rule
    val folder = TemporaryFolder()

    private val reader = RecordingStrip()
    private var toggles = 0
    private var hides = 0
    private var state by mutableStateOf(ComicReaderUiState())

    /** The pages [indexes] on the device, each 400 by 600. */
    private fun ready(indexes: Iterable<Int>): Map<Int, PageState> =
        indexes.associateWith { pageImage(folder.root, it, width = 400, height = 600) }

    /** The pages [indexes] on the device, each 400 by 250: more than three of them in the window. */
    private fun short(indexes: Iterable<Int>): Map<Int, PageState> =
        indexes.associateWith { pageImage(folder.root, it, width = 400, height = 250) }

    /** An album read vertically, open on [page]. */
    private fun strip(
        page: Int = 0,
        pageCount: Int = Pages,
        pages: Map<Int, PageState> = ready(0 until pageCount),
    ) = ComicReaderUiState(
        phase = ComicPhase.Ready,
        pageCount = pageCount,
        spreads = spreads(pageCount, wide = emptySet(), twoPages = false),
        spreadIndex = page,
        settings = ComicSettings(direction = Direction.Vertical),
        pages = pages,
    )

    private fun show(shown: ComicReaderUiState) {
        state = shown
        compose.setContent {
            images.Provide {
                LirelleTheme {
                    ComicStripView(state, reader, reader, onToggleBars = { toggles++ }, onHideBars = { hides++ })
                }
            }
        }
        compose.waitForIdle()
    }

    private fun page(index: Int): SemanticsNodeInteraction = compose.onNodeWithTag(comicStripPageTag(index))

    private fun list(): SemanticsNodeInteraction = compose.onNodeWithTag(ComicStripTag)

    /** A tap at [y] of the height of the window (0 its top, 1 its bottom), at mid-width, and what it started. */
    private fun tapAt(y: Float, settle: Boolean = true) {
        list().performTouchInput { click(Offset(width / 2f, height * y)) }
        if (settle) compose.waitForIdle()
    }

    /** The page at the top of the window, as the strip last told it. */
    private fun firstShown(): Int = reader.shows.last().split(" ")[1].toInt()

    /** Moves the strip by [pixels], forward when positive, as an accessibility service would. */
    private fun scrollBy(pixels: Float) {
        list().performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, pixels) }
        compose.waitForIdle()
    }

    @Test
    fun `the pages follow one another edge to edge`() {
        show(strip())

        page(0).assertIsDisplayed()
        page(1).assertIsDisplayed()
        assertThat(page(0).drawn().top).isEqualTo(0f)
        assertThat(page(0).drawn().height).isEqualTo(600f)
        assertThat(page(1).drawn().top).isEqualTo(page(0).drawn().bottom)
    }

    @Test
    fun `the strip tells the pages it shows and the one at the top`() {
        show(strip())
        assertThat(reader.shows).containsExactly("stripShows 0 1")

        list().performScrollToIndex(3)
        compose.waitForIdle()

        assertThat(reader.shows.last()).isEqualTo("stripShows 3 4 scrolled")
    }

    @Test
    fun `the strip tells its pages once for each change, not as it moves under them`() {
        show(strip())

        // Still the pages 0 and 1, further down the first.
        scrollBy(100f)
        scrollBy(100f)

        // Once more for the first move: the user scrolled the strip.
        assertThat(reader.shows).containsExactly("stripShows 0 1", "stripShows 0 1 scrolled").inOrder()
    }

    @Test
    fun `at rest the strip says so, and an album just opened says nothing`() {
        show(strip())
        assertThat(reader.calls).doesNotContain("stripRested")

        list().performTouchInput { swipeUp() }
        compose.waitForIdle()

        assertThat(reader.calls).contains("stripRested")
        // What it shows is told before it is said at rest: the reader writes the page it was last told.
        assertThat(reader.calls.last()).isEqualTo("stripRested")
        assertThat(reader.shows.size).isAtLeast(2)
    }

    @Test
    fun `it opens on the page read`() {
        show(strip(page = 5))

        page(5).assertIsDisplayed()
        assertThat(page(5).drawn().top).isEqualTo(0f)
        page(0).assertDoesNotExist()
        assertThat(reader.shows).containsExactly("stripShows 5 6")
    }

    @Test
    fun `a landing scrolls to its page, and nothing is told of the place left meanwhile`() {
        show(strip())

        state = state.copy(landing = Landing(page = 7, serial = 1))
        compose.waitForIdle()

        page(7).assertIsDisplayed()
        assertThat(page(7).drawn().top).isEqualTo(0f)
        assertThat(reader.shows).containsExactly("stripShows 0 1", "stripShows 7 8").inOrder()
    }

    @Test
    fun `the same page landed on twice is scrolled to twice`() {
        show(strip())
        state = state.copy(landing = Landing(page = 7, serial = 1))
        compose.waitForIdle()
        scrollBy(300f)

        state = state.copy(landing = Landing(page = 7, serial = 2))
        compose.waitForIdle()

        assertThat(page(7).drawn().top).isEqualTo(0f)
    }

    @Test
    fun `a tap in the bottom quarter moves forward and hides the bars, in the top quarter back, between the bars`() {
        show(strip())

        tapAt(0.9f)
        assertThat(hides).isEqualTo(1)
        // Nine tenths of the window: the bottom of the page 1 was at 1200.
        assertThat(page(1).drawn().top).isWithin(1f).of(600f - 720f)

        tapAt(0.5f)
        assertThat(toggles).isEqualTo(1)
        assertThat(hides).isEqualTo(1)
        assertThat(page(1).drawn().top).isWithin(1f).of(600f - 720f)

        tapAt(0.1f)
        assertThat(hides).isEqualTo(2)
        assertThat(toggles).isEqualTo(1)
        assertThat(page(0).drawn().top).isWithin(1f).of(0f)
        assertThat(reader.calls).doesNotContain("stripPastEnd")
    }

    @Test
    fun `two quick taps in the bottom quarter move the strip by two steps`() {
        show(strip())
        compose.mainClock.autoAdvance = false

        tapAt(0.9f, settle = false)
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        val midway = page(0).drawn().top
        tapAt(0.9f, settle = false)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()

        // The second tap came while the strip was on its way.
        assertThat(midway).isLessThan(0f)
        assertThat(midway).isGreaterThan(-720f)
        // Twice nine tenths of the window, from where the first tap led to.
        assertThat(page(2).drawn().top).isWithin(1f).of(1200f - 1440f)
        assertThat(hides).isEqualTo(2)
        assertThat(toggles).isEqualTo(0)
    }

    @Test
    fun `a quick tap back after a tap forward brings the strip back where it was`() {
        show(strip())
        compose.mainClock.autoAdvance = false

        tapAt(0.9f, settle = false)
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        tapAt(0.1f, settle = false)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()

        assertThat(page(0).drawn().top).isWithin(1f).of(0f)
    }

    @Test
    fun `a tap between the edges while the strip steps shows the bars and lets the step end`() {
        show(strip())
        compose.mainClock.autoAdvance = false

        tapAt(0.9f, settle = false)
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        tapAt(0.5f, settle = false)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()

        assertThat(toggles).isEqualTo(1)
        assertThat(page(1).drawn().top).isWithin(1f).of(600f - 720f)
    }

    @Test
    fun `a finger that drags the strip while it steps takes it from the step`() {
        show(strip())
        compose.mainClock.autoAdvance = false
        tapAt(0.9f, settle = false)
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        val midway = page(0).drawn().top

        // Back down by a quarter of the window at once, then held still: no fling follows.
        list().performTouchInput {
            down(center)
            moveBy(Offset(0f, height * 0.25f))
            advanceEventTime(1_000)
            move()
            up()
        }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()

        // Where the finger left it, back from where it took it: the step would have ended 720 further.
        assertThat(midway).isLessThan(0f)
        assertThat(page(0).drawn().top).isAtLeast(midway)
    }

    @Test
    fun `the step of a tap is the user's move, and its end a rest`() {
        show(strip())

        tapAt(0.9f)

        assertThat(reader.shows.last()).isEqualTo("stripShows 1 2 scrolled")
        assertThat(reader.calls.last()).isEqualTo("stripRested")
    }

    @Test
    fun `a finger put down on a strip that was flung stops it, and is not a tap`() {
        show(strip())
        compose.mainClock.autoAdvance = false
        list().performTouchInput { swipeUp(durationMillis = 100) }
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        val flung = page(firstShown()).drawn().top

        tapAt(0.5f, settle = false)
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        val stopped = firstShown() to page(firstShown()).drawn().top
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()

        assertThat(flung).isLessThan(0f)
        assertThat(toggles).isEqualTo(0)
        assertThat(firstShown() to page(firstShown()).drawn().top).isEqualTo(stopped)
    }

    @Test
    fun `a tap forward at the bottom of the last page asks for the end`() {
        show(strip(pageCount = 2))
        scrollBy(400f)
        assertThat(page(1).drawn().bottom).isEqualTo(800f)
        // At the very bottom the strip says so: the last page is the one read, though the one before it is at the top.
        assertThat(reader.shows.last()).isEqualTo("stripShows 0 1 end scrolled")

        tapAt(0.9f)

        assertThat(reader.calls.last()).isEqualTo("stripPastEnd")
        assertThat(hides).isEqualTo(1)
    }

    @Test
    fun `at the bottom of the strip the pages on screen are still told, with the end`() {
        show(strip(pageCount = 6, pages = short(0 until 6)))
        assertThat(reader.shows).containsExactly("stripShows 0 3")

        // Six pages of 250 in a window of 800: its bottom is 700 down.
        scrollBy(700f)

        assertThat(page(5).drawn().bottom).isEqualTo(800f)
        assertThat(reader.shows.last()).isEqualTo("stripShows 2 5 end scrolled")
    }

    @Test
    fun `opened on a page the bottom of the strip holds lower, it is not said scrolled until the user moves it`() {
        show(strip(page = 4, pageCount = 6, pages = short(0 until 6)))

        // Held at its bottom: the page opened on is not at the top.
        assertThat(page(5).drawn().bottom).isEqualTo(800f)
        assertThat(reader.shows).containsExactly("stripShows 2 5 end")
        assertThat(reader.calls).doesNotContain("stripRested")

        scrollBy(-300f)
        scrollBy(300f)

        assertThat(reader.shows.last()).isEqualTo("stripShows 2 5 end scrolled")
    }

    @Test
    fun `a landing is not the user's move, and what the user scrolled before no longer counts`() {
        show(strip(pageCount = 6, pages = short(0 until 6)))
        scrollBy(100f)
        assertThat(reader.shows.last()).isEqualTo("stripShows 0 3 scrolled")

        state = state.copy(landing = Landing(page = 4, serial = 1))
        compose.waitForIdle()

        assertThat(reader.shows.last()).isEqualTo("stripShows 2 5 end")
    }

    @Test
    fun `a pull on past the bottom of the last page asks for the end, once`() {
        show(strip(pageCount = 2))
        scrollBy(400f)

        list().performTouchInput { swipeUp() }
        compose.waitForIdle()

        assertThat(reader.calls.count { it == "stripPastEnd" }).isEqualTo(1)
        // The finger that moves the strip hides the bars.
        assertThat(hides).isEqualTo(1)
    }

    @Test
    fun `reaching the bottom of the last page does not ask for the end`() {
        show(strip(pageCount = 2))

        scrollBy(400f)

        assertThat(reader.calls).doesNotContain("stripPastEnd")
    }

    @Test
    fun `nothing is told of the pages while the end of the album is shown`() {
        show(strip().copy(endPanel = ComicEndPanel()))
        scrollBy(700f)
        assertThat(reader.shows).isEmpty()

        state = state.copy(endPanel = null)
        compose.waitForIdle()

        assertThat(reader.shows).containsExactly("stripShows 1 2 scrolled")
    }

    @Test
    fun `a page not there yet keeps its room and shows the loading mark`() {
        show(strip(pages = ready(listOf(0, 2, 3))))

        list().performScrollToIndex(1)
        compose.waitForIdle()

        compose.onNodeWithTag(ComicPageLoadingTag).assertExists()
        // The shape of its neighbour, meanwhile.
        assertThat(page(1).drawn().height).isEqualTo(600f)
        assertThat(page(2).drawn().top).isEqualTo(page(1).drawn().bottom)
    }

    @Test
    fun `a page that failed offers to try again`() {
        show(strip(pages = ready(listOf(0, 2, 3)) + (1 to PageState.Failed(ApiError.ServerUnreachable))))
        list().performScrollToIndex(1)
        compose.waitForIdle()

        compose.onNodeWithTag(ComicPageRetryTag).performClick()

        assertThat(reader.calls).contains("retry 1")
        // In the room its neighbour's shape gives it: the next page starts 600 under its top.
        assertThat(page(2).drawn().top).isEqualTo(600f)
        // The button took the tap: the strip did not.
        assertThat(toggles).isEqualTo(0)
    }

    @Test
    fun `a page the reader no longer watches keeps its height`() {
        val tall = pageImage(folder.root, index = 0, width = 400, height = 700)
        show(strip(pages = ready(1 until Pages) + (0 to tall)))
        assertThat(page(0).drawn().height).isEqualTo(700f)

        state = state.copy(pages = state.pages - 0)
        compose.waitForIdle()

        assertThat(page(0).drawn().height).isEqualTo(700f)
        compose.onNodeWithTag(ComicPageLoadingTag).assertExists()
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun `on a wide window the strip is a centred column, and taps are taken beside it`() {
        show(strip())

        val column = list().drawn()
        assertThat(column.width).isEqualTo(600f)
        assertThat(column.left).isEqualTo(340f)
        assertThat(page(0).drawn().height).isEqualTo(900f)

        // Beside the column, in the bottom quarter of the window.
        compose.onRoot().performTouchInput { click(Offset(100f, height * 0.9f)) }
        compose.waitForIdle()

        assertThat(hides).isEqualTo(1)
        assertThat(page(0).drawn().top).isWithin(1f).of(-720f)
    }

    @Test
    fun `a page above that arrives does not move what is read by more than its own change`() {
        show(strip(page = 3, pages = ready(3 until Pages)))
        scrollBy(-100f)
        assertThat(page(2).drawn().top).isEqualTo(-500f)
        assertThat(page(3).drawn().top).isEqualTo(100f)

        // Higher than the room it was given: 800 instead of 600.
        state = state.copy(pages = state.pages + (2 to pageImage(folder.root, index = 2, width = 400, height = 800)))
        compose.waitForIdle()

        // The strip stays where it was in the page at the top: what follows moved by what that page grew.
        assertThat(page(2).drawn().top).isEqualTo(-500f)
        page(3).assertIsDisplayed()
        assertThat(page(3).drawn().top).isEqualTo(300f)
    }

    @Test
    fun `a page that arrives above the screen leaves what is read where it is`() {
        show(strip(page = 3, pages = ready(3 until Pages)))

        state = state.copy(pages = state.pages + (1 to pageImage(folder.root, index = 1, width = 400, height = 800)))
        compose.waitForIdle()

        assertThat(page(3).drawn().top).isEqualTo(0f)
        assertThat(reader.shows).containsExactly("stripShows 3 4")
    }

    @Test
    fun `a page too tall for one picture is drawn in slices that follow one another`() {
        val tall = pageImage(folder.root, index = 0, width = 400, height = 6000)
        show(strip(pageCount = 2, pages = ready(listOf(1)) + (0 to tall)))

        compose.waitUntil(SliceWait) { compose.onAllNodesWithTag(StripSliceTag).fetchSemanticsNodes().isNotEmpty() }

        // Its first slice: the rows a slice may have at most, not the whole page.
        assertThat(page(0).drawn().height).isEqualTo(2048f)
        list().performScrollToIndex(3)
        compose.waitForIdle()
        // Three slices, then the next page, right under the 6000 rows of the first.
        assertThat(page(1).drawn().height).isEqualTo(600f)
        assertThat(reader.shows.last()).isEqualTo("stripShows 0 1 end scrolled")
        scrollBy(-10f)
        assertThat(reader.shows.last()).isEqualTo("stripShows 0 1 scrolled")
    }

    @Test
    fun `only the slices of a tall page near the screen are kept`() {
        // Half as wide again as the strip is not: 15,000 rows, eight slices of 2,048 on screen but the last.
        val tall = pageImage(folder.root, index = 0, width = 400, height = 15_000)
        show(strip(pageCount = 2, pages = ready(listOf(1)) + (0 to tall)))
        compose.waitUntil(SliceWait) { compose.onAllNodesWithTag(StripSliceTag).fetchSemanticsNodes().isNotEmpty() }

        // The window is 800 high: the first slice fills it, and those far below are not there.
        compose.onAllNodesWithTag(StripSliceTag).assertCountEquals(1)
        assertThat(page(0).drawn().height).isEqualTo(2048f)

        // On its last slice, 14,000 rows down: the first ones are let go of.
        list().performScrollToIndex(7)
        compose.waitUntil(SliceWait) { compose.onAllNodesWithTag(StripSliceTag).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()

        assertThat(compose.onAllNodesWithTag(StripSliceTag).fetchSemanticsNodes().size).isAtMost(2)
        page(0).assertDoesNotExist()
    }

    @Test
    fun `a tall page that cannot be read in parts is drawn whole`() {
        val unreadable = folder.newFile("no-picture").apply { writeText("not a picture") }
        val pages = ready(listOf(1)) + (0 to PageState.Ready(unreadable, width = 400, height = 6000))
        show(strip(pageCount = 2, pages = pages))

        compose.waitUntil(SliceWait) { page(0).drawn().height == 6000f }

        compose.onAllNodesWithTag(StripSliceTag).assertCountEquals(0)
    }
}
