package app.tfdn.lirelle.feature.home

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.designsystem.LocalWindowWidthClass
import app.tfdn.lirelle.core.designsystem.WindowWidthClass
import app.tfdn.lirelle.core.library.model.BookSummary
import app.tfdn.lirelle.core.library.model.HomeRow
import app.tfdn.lirelle.core.library.model.HomeRowKind
import app.tfdn.lirelle.core.library.model.MediaProfile
import app.tfdn.lirelle.core.library.model.PagedKind
import app.tfdn.lirelle.core.library.model.SeriesRef
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.SeriesId
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.testing.sampleBook
import app.tfdn.lirelle.core.ui.BookMedium
import app.tfdn.lirelle.core.ui.BookPlaceholderTag
import app.tfdn.lirelle.core.ui.Loadable
import app.tfdn.lirelle.core.ui.LocalOpenDownloads
import app.tfdn.lirelle.core.ui.NoNetworkDownloadsTag
import app.tfdn.lirelle.core.ui.NoNetworkRetryTag
import app.tfdn.lirelle.core.ui.NoNetworkStateTag
import app.tfdn.lirelle.core.ui.PagingErrorBarTag
import app.tfdn.lirelle.core.ui.R as CoreR
import app.tfdn.lirelle.core.ui.SeeAllTag
import app.tfdn.lirelle.core.ui.mediumButtonTag
import app.tfdn.lirelle.core.ui.relativeTime
import com.google.common.truth.Truth.assertThat
import java.time.Duration
import java.time.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private val Now = Instant.parse("2026-11-13T12:00:00Z")

private fun ready(kind: HomeRowKind, hasMore: Boolean = false, vararg books: BookSummary) =
    kind to Loadable.Ready(HomeRow(kind, books.toList(), hasMore))

private fun state(vararg rows: Pair<HomeRowKind, Loadable<HomeRow>>) =
    HomeUiState(HomeRowKind.entries.associateWith { Loadable.Ready(HomeRow(it, emptyList(), false)) } + rows)

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class HomeScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val opened = mutableListOf<BookId>()
    private val read = mutableListOf<BookId>()
    private val readComics = mutableListOf<BookId>()
    private val listened = mutableListOf<BookId>()
    private val narrated = mutableListOf<BookId>()
    private val seenAll = mutableListOf<HomeRowKind>()
    private var retries = 0
    private var refreshes = 0

    private fun text(id: Int) = context.getString(id)

    private var downloadsOpened = 0

    /** @param inShell the downloaded books can be reached, as in the shell. */
    private fun show(
        state: HomeUiState,
        width: WindowWidthClass = WindowWidthClass.Compact,
        inShell: Boolean = false,
    ) {
        compose.setContent {
            LirelleTheme {
                CompositionLocalProvider(
                    LocalWindowWidthClass provides width,
                    LocalOpenDownloads provides if (inShell) ({ downloadsOpened++ }) else null,
                ) {
                    HomeContent(
                        state = state.copy(now = Now),
                        onRetry = { retries++ },
                        onRefresh = { refreshes++ },
                        onOpenBook = { opened += it },
                        onRead = { read += it },
                        onListen = { listened += it },
                        onNarrate = { narrated += it },
                        onSeeAll = { seenAll += it },
                        onSignInAgain = {},
                        onOpenDownloads = {},
                        onReadComic = { readComics += it },
                    )
                }
            }
        }
    }

    // From under the last row, where a home shorter than the screen has nothing.
    private fun pullDown() {
        compose.onRoot().performTouchInput { swipeDown(startY = height * PullFrom, endY = height * PullTo) }
        compose.waitForIdle()
    }

    @Test
    fun `rows are shown in order, and an empty row is not shown at all`() {
        show(
            state(
                ready(HomeRowKind.Continue, false, sampleBook("1", "Reading", progressPercent = 11)),
                ready(
                    HomeRowKind.UpNext,
                    false,
                    sampleBook("2", "Next", series = SeriesRef(SeriesId("4"), "A Saga", "2")),
                ),
            ),
        )

        compose.onNodeWithText(text(R.string.home_row_continue)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.home_row_up_next)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.home_row_want_to_read)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.home_row_recently_added)).assertDoesNotExist()
        compose.onNodeWithTag("continue-1").assertIsDisplayed()
        compose.onNodeWithText("A Saga", useUnmergedTree = true).assertIsDisplayed()
        val continueTop = compose.onNodeWithText(text(R.string.home_row_continue)).fetchSemanticsNode().positionInRoot.y
        val upNextTop = compose.onNodeWithText(text(R.string.home_row_up_next)).fetchSemanticsNode().positionInRoot.y
        assertThat(continueTop).isLessThan(upNextTop)
    }

    @Test
    fun `the last reading is counted back from the moment the state holds`() {
        val book =
            sampleBook("1", "Reading", progressPercent = 11).copy(lastActivityAt = Now.minus(Duration.ofHours(2)))

        show(state(ready(HomeRowKind.Continue, false, book)))

        val expected = relativeTime(Now.minus(Duration.ofHours(2)), Now)
        compose.onNodeWithTag("continue-open-1").assert(hasContentDescription(expected, substring = true))
    }

    @Test
    fun `the title and the row titles are headings`() {
        show(state(ready(HomeRowKind.RecentlyAdded, false, sampleBook("2"))))

        val heading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
        compose.onNodeWithText(text(R.string.home_title)).assert(heading)
        compose.onNodeWithText(text(R.string.home_row_recently_added)).assert(heading)
    }

    @Test
    fun `see all is offered only for a row that has more`() {
        show(
            state(
                ready(HomeRowKind.WantToRead, true, sampleBook("1")),
                ready(HomeRowKind.RecentlyAdded, false, sampleBook("2")),
            ),
        )

        compose.onAllNodesWithTag(SeeAllTag).assertCountEquals(1)
        compose.onNodeWithTag(SeeAllTag).performClick()
        assertThat(seenAll).containsExactly(HomeRowKind.WantToRead)
    }

    @Test
    fun `see all of the continue row opens the continue list`() {
        show(state(ready(HomeRowKind.Continue, true, sampleBook("1", progressPercent = 5))))

        compose.onNodeWithTag(SeeAllTag).performClick()

        assertThat(seenAll).containsExactly(HomeRowKind.Continue)
    }

    @Test
    fun `a cover opens the page of its book`() {
        show(state(ready(HomeRowKind.RecentlyAdded, false, sampleBook("2"))))

        compose.onNodeWithTag("book-2").performClick()

        assertThat(opened).containsExactly(BookId("2"))
        assertThat(read).isEmpty()
    }

    private fun readButton(id: String) = compose.onNodeWithTag(mediumButtonTag(BookMedium.Read, BookId(id)))

    private fun listenButton(id: String) = compose.onNodeWithTag(mediumButtonTag(BookMedium.Listen, BookId(id)))

    @Test
    fun `the cover of a book of the continue row opens its page`() {
        val audio = sampleBook("2", progressPercent = 5, media = MediaProfile.AudioOnly)
        show(state(ready(HomeRowKind.Continue, false, sampleBook("1", progressPercent = 5), audio)))

        compose.onNodeWithTag("continue-open-1").performClick()
        compose.onNodeWithTag("continue-open-2").performClick()

        assertThat(opened).containsExactly(BookId("1"), BookId("2")).inOrder()
        assertThat(read).isEmpty()
        assertThat(listened).isEmpty()
    }

    @Test
    fun `read on a comic goes on in the comics reader`() {
        val comic = sampleBook("5", progressPercent = 25, pagedKind = PagedKind.Comic)
        show(state(ready(HomeRowKind.Continue, false, comic)))

        readButton("5").performClick()

        assertThat(readComics).containsExactly(BookId("5"))
        assertThat(read).isEmpty()
        assertThat(opened).isEmpty()
    }

    @Test
    fun `read on a PDF and on a DjVu goes on in the page reader, as on a comic`() {
        val pdf = sampleBook("6", progressPercent = 25, pagedKind = PagedKind.Pdf)
        val djvu = sampleBook("7", progressPercent = 60, pagedKind = PagedKind.Djvu)
        show(state(ready(HomeRowKind.Continue, false, pdf, djvu)))

        readButton("6").performClick()
        readButton("7").performClick()

        assertThat(readComics).containsExactly(BookId("6"), BookId("7")).inOrder()
        assertThat(read).isEmpty()
        assertThat(opened).isEmpty()
    }

    @Test
    fun `read goes on reading, listen goes on listening`() {
        val both = sampleBook("3", progressPercent = 5, media = MediaProfile.TextAndAudio)
        show(state(ready(HomeRowKind.Continue, false, both)))

        readButton("3").performClick()
        listenButton("3").performClick()

        assertThat(read).containsExactly(BookId("3"))
        assertThat(readComics).isEmpty()
        assertThat(listened).containsExactly(BookId("3"))
        assertThat(narrated).isEmpty()
        assertThat(opened).isEmpty()
    }

    @Test
    fun `listen to a read-along book with an audio file plays that file`() {
        val both = sampleBook("3", progressPercent = 5, media = MediaProfile.ReadAlong).copy(hasAudioFiles = true)
        show(state(ready(HomeRowKind.Continue, false, both)))

        listenButton("3").performClick()

        assertThat(listened).containsExactly(BookId("3"))
        assertThat(narrated).isEmpty()
    }

    @Test
    fun `listen to a read-along book without an audio file narrates it in the reader`() {
        val readAlongOnly = sampleBook("4", progressPercent = 5, media = MediaProfile.ReadAlong)
        show(state(ready(HomeRowKind.Continue, false, readAlongOnly)))

        listenButton("4").performClick()

        assertThat(narrated).containsExactly(BookId("4"))
        assertThat(listened).isEmpty()
        assertThat(read).isEmpty()
    }

    @Test
    fun `each book has a button per medium it has`() {
        val text = sampleBook("1", progressPercent = 5)
        val audio = sampleBook("2", media = MediaProfile.AudioOnly)
        val both = sampleBook("3", progressPercent = 5, media = MediaProfile.ReadAlong).copy(hasAudioFiles = true)
        // The narration of its EPUB alone: it is listened to in the reader.
        val readAlongOnly = sampleBook("4", progressPercent = 5, media = MediaProfile.ReadAlong)
        show(state(ready(HomeRowKind.Continue, false, text, audio, both, readAlongOnly)))

        readButton("1").assertExists()
        listenButton("1").assertDoesNotExist()
        readButton("2").assertDoesNotExist()
        listenButton("2").assertExists()
        readButton("3").assertExists()
        listenButton("3").assertExists()
        readButton("4").assertExists()
        listenButton("4").assertExists()
    }

    @Test
    fun `the listening time left shows once it is known`() {
        val audio = sampleBook("2", title = "Heard", media = MediaProfile.AudioOnly)
        val known = state(ready(HomeRowKind.Continue, false, audio))
            .copy(
                listening = mapOf(
                    BookId("2") to BookListening(timeLeftSeconds = 2 * 3600L + 40 * 60, listenedAt = Now),
                ),
            )
        show(known)

        compose.onNodeWithText(context.getString(CoreR.string.continue_time_left_hours, 2, 40), useUnmergedTree = true)
            .assertIsDisplayed()
        listenButton("2").assertContentDescriptionEquals(context.getString(CoreR.string.continue_listen_label, "Heard"))
    }

    @Test
    fun `nothing anywhere says the library is empty`() {
        show(state())

        compose.onNodeWithText(text(R.string.home_empty)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.home_title)).assertIsDisplayed()
    }

    @Test
    fun `pulling down refreshes a home shorter than the screen`() {
        show(state(ready(HomeRowKind.RecentlyAdded, false, sampleBook("2"))))
        pullDown()
        assertThat(refreshes).isEqualTo(1)
    }

    @Test
    fun `pulling down refreshes an empty library`() {
        show(state())
        pullDown()
        assertThat(refreshes).isEqualTo(1)
    }

    @Test
    fun `loading shows placeholders under the row titles`() {
        show(HomeUiState())

        compose.onNodeWithText(text(R.string.home_row_up_next)).assertIsDisplayed()
        assertThat(compose.onAllNodesWithTag(BookPlaceholderTag).fetchSemanticsNodes()).isNotEmpty()
        compose.onNodeWithText(text(R.string.home_empty)).assertDoesNotExist()
    }

    @Test
    fun `a failed row leaves the others and offers to retry`() {
        show(
            state(ready(HomeRowKind.Continue, false, sampleBook("1", progressPercent = 5))).let {
                it.copy(rows = it.rows + (HomeRowKind.UpNext to Loadable.Failed(ApiError.ServerUnreachable)))
            },
        )

        compose.onNodeWithTag("continue-1").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.home_row_up_next)).assertDoesNotExist()
        compose.onNodeWithTag(PagingErrorBarTag).assertIsDisplayed()
        compose.onNodeWithText(text(CoreR.string.state_retry)).performClick()
        assertThat(retries).isEqualTo(1)
    }

    @Test
    fun `a refresh that failed keeps the rows and offers to refresh again`() {
        val rows = state(ready(HomeRowKind.RecentlyAdded, false, sampleBook("2")))
        show(rows.copy(refreshError = ApiError.ServerUnreachable))

        compose.onNodeWithTag("book-2").assertIsDisplayed()
        compose.onNodeWithTag(PagingErrorBarTag).assertIsDisplayed()
        compose.onNodeWithText(text(CoreR.string.state_retry)).performClick()
        assertThat(refreshes).isEqualTo(1)
        assertThat(retries).isEqualTo(0)
    }

    @Test
    fun `everything failing is the error in place of the rows, under the title`() {
        show(HomeUiState(HomeRowKind.entries.associateWith { Loadable.Failed(ApiError.ServerUnreachable) }))

        compose.onNodeWithTag(PagingErrorBarTag).assertDoesNotExist()
        compose.onNodeWithText(text(CoreR.string.state_retry)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.home_title)).assertIsDisplayed()
        compose.onNodeWithText(text(CoreR.string.state_retry)).performClick()
        assertThat(retries).isEqualTo(1)
    }

    @Test
    fun `everything failing for lack of network leads to the downloads or tries again`() {
        val rows = HomeRowKind.entries.associateWith { Loadable.Failed(ApiError.ServerUnreachable) }
        show(HomeUiState(rows), inShell = true)

        compose.onNodeWithTag(NoNetworkStateTag).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.home_title)).assertIsDisplayed()
        compose.onNodeWithTag(NoNetworkDownloadsTag).performClick()
        compose.onNodeWithTag(NoNetworkRetryTag).performClick()

        assertThat(downloadsOpened).isEqualTo(1)
        assertThat(retries).isEqualTo(1)
    }

    @Test
    fun `everything failing for another reason is the error, in the shell too`() {
        show(HomeUiState(HomeRowKind.entries.associateWith { Loadable.Failed(ApiError.ServerFailed) }), inShell = true)

        compose.onNodeWithTag(NoNetworkStateTag).assertDoesNotExist()
        compose.onNodeWithText(text(CoreR.string.library_error_server_failed)).assertIsDisplayed()
    }

    @Test
    fun `outside the shell a lack of network is the error`() {
        show(HomeUiState(HomeRowKind.entries.associateWith { Loadable.Failed(ApiError.ServerUnreachable) }))

        compose.onNodeWithTag(NoNetworkStateTag).assertDoesNotExist()
        compose.onNodeWithText(text(CoreR.string.library_error_unreachable)).assertIsDisplayed()
    }

    @Test
    fun `a row failing for lack of network keeps the others on screen, with the bar`() {
        val failed = HomeRowKind.UpNext to Loadable.Failed(ApiError.ServerUnreachable)
        show(state(ready(HomeRowKind.Continue, false, sampleBook("1")), failed), inShell = true)

        compose.onNodeWithTag(NoNetworkStateTag).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.home_title)).assertIsDisplayed()
    }

    @Test
    fun `the see-all list shows the books of the row and goes back`() {
        val row = HomeRow(HomeRowKind.UpNext, listOf(sampleBook("4"), sampleBook("5")), hasMore = false)
        compose.setContent {
            LirelleTheme {
                HomeRowContent(
                    kind = HomeRowKind.UpNext,
                    state = Loadable.Ready(row),
                    onBack = {},
                    onRetry = {},
                    onOpenBook = { opened += it },
                )
            }
        }

        compose.onNodeWithText(text(R.string.home_row_up_next)).assertIsDisplayed()
        compose.onNodeWithTag("book-5").performClick()
        compose.onNodeWithTag("book-4").assertIsDisplayed()
        assertThat(opened).containsExactly(BookId("5"))
    }

    private companion object {
        const val PullFrom = 0.6f
        const val PullTo = 0.9f
    }
}
