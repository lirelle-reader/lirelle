package app.tfdn.lirelle.feature.book

import android.content.Context
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.model.BookFileKind
import app.tfdn.lirelle.core.library.model.BookReading
import app.tfdn.lirelle.core.library.model.MediaProfile
import app.tfdn.lirelle.core.library.model.ReadState
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.testing.sampleBookDetail
import app.tfdn.lirelle.core.testing.sampleComicDetail
import app.tfdn.lirelle.core.testing.samplePagedDetail
import app.tfdn.lirelle.core.ui.Loadable
import app.tfdn.lirelle.core.ui.PagedLabelTag
import app.tfdn.lirelle.core.ui.R as UiR
import app.tfdn.lirelle.feature.book.download.DownloadTileTag
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private val Album = BookId("14")

/** The page of a comic: the actions of a text, which open the comics reader, its chip, and the page it is at. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class BookComicTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val read = mutableListOf<Pair<BookId, Boolean>>()
    private val readComics = mutableListOf<Pair<BookId, Boolean>>()
    private val listened = mutableListOf<BookId>()

    private val comic = sampleComicDetail().copy(readState = null)
    private val atPage12 = BookReading(25, lastReadAt = null, totalSeconds = 600, sessionCount = 1, page = 12)

    private fun text(id: Int, vararg args: Any) = context.getString(id, *args)

    private fun spoken(name: Int, status: Int? = null, vararg args: Any) = context.spokenAction(name, status, *args)

    private fun show(state: BookUiState) {
        compose.setContent {
            LirelleTheme {
                BookContent(
                    state = state,
                    onBack = {},
                    onRetry = {},
                    onRefresh = {},
                    onOpenBook = {},
                    onOpenAuthor = {},
                    onOpenGenre = {},
                    onRead = { id, fromStart -> read += id to fromStart },
                    onListen = { id, _ -> listened += id },
                    onNarrate = {},
                    onReadComic = { id, fromStart -> readComics += id to fromStart },
                )
            }
        }
    }

    private fun mainAction() = compose.onNodeWithTag(MainActionTag).performScrollTo()

    @Test
    fun `a comic never opened starts being read, in the comics reader`() {
        show(BookUiState(Loadable.Ready(comic)))

        mainAction()
            .assertContentDescriptionEquals(spoken(R.string.action_read, R.string.status_start_spoken))
            .assertIsEnabled()
            .performClick()

        assertThat(readComics).containsExactly(Album to false)
        assertThat(read).isEmpty()
    }

    @Test
    fun `a comic being read goes on, and says how far the user is`() {
        show(BookUiState(Loadable.Ready(comic.copy(readState = ReadState.Reading)), atPage12))

        mainAction()
            .assertContentDescriptionEquals(spoken(R.string.action_read, R.string.status_resume_percent_spoken, 25))
            .assertIsEnabled()
            .performClick()

        assertThat(readComics).containsExactly(Album to false)
    }

    @Test
    fun `a comic marked as read is read again, from its first page`() {
        show(BookUiState(Loadable.Ready(comic.copy(readState = ReadState.Read)), atPage12))

        mainAction()
            .assertContentDescriptionEquals(spoken(R.string.action_read, R.string.status_read_again_spoken))
            .assertIsEnabled()
            .performClick()

        assertThat(readComics).containsExactly(Album to true)
        assertThat(read).isEmpty()
    }

    @Test
    fun `a comic has no listening, and its download is not offered`() {
        show(BookUiState(Loadable.Ready(comic)))

        compose.onNodeWithTag(ListenActionTag).assertDoesNotExist()
        compose.assertActionButtons(MainActionTag, DownloadTileTag)
        compose.assertReadIconNarrated(false)
        compose.onNodeWithText(text(R.string.action_download)).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `a comic that has an audiobook too is read in the comics reader and listened to in the player`() {
        val audio = sampleBookDetail().files.single { it.kind == BookFileKind.Audio }
        val both = comic.copy(media = MediaProfile.TextAndAudio, files = comic.files + audio)
        show(BookUiState(Loadable.Ready(both), listening = Loadable.Ready(null)))

        mainAction().performClick()
        compose.onNodeWithTag(ListenActionTag).performScrollTo().assertIsEnabled().performClick()

        assertThat(readComics).containsExactly(Album to false)
        assertThat(listened).containsExactly(Album)
    }

    @Test
    fun `on a page built without the server a comic cannot be read`() {
        val offline = BookUiState(
            book = Loadable.Ready(comic),
            reading = atPage12,
            listening = Loadable.Failed(ApiError.ServerUnreachable),
            offline = true,
            noNetwork = true,
            keptFormats = emptySet(),
        )
        show(offline)

        mainAction().assertIsNotEnabled().performClick()

        assertThat(readComics).isEmpty()
        assertThat(read).isEmpty()
    }

    @Test
    fun `on a page built without the server a kept PDF is read, in the page reader`() {
        val offline = BookUiState(
            book = Loadable.Ready(samplePagedDetail("PDF").copy(readState = null)),
            listening = Loadable.Failed(ApiError.ServerUnreachable),
            offline = true,
            noNetwork = true,
            keptFormats = setOf(DownloadFormat.Text),
        )
        show(offline)

        mainAction().assertIsEnabled().performClick()

        assertThat(readComics).containsExactly(Album to false)
        assertThat(read).isEmpty()
    }

    @Test
    fun `a comic says so under its title`() {
        show(BookUiState(Loadable.Ready(comic)))

        compose.onNodeWithTag(PagedLabelTag).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(UiR.string.media_comic)).assertIsDisplayed()
    }

    @Test
    fun `a book that is not a comic does not say it is one, and is read in the reader`() {
        show(BookUiState(Loadable.Ready(sampleBookDetail(media = MediaProfile.TextOnly))))

        compose.onAllNodesWithTag(PagedLabelTag).assertCountEquals(0)
        mainAction().performClick()

        assertThat(read).containsExactly(BookId("12") to false)
        assertThat(readComics).isEmpty()
    }

    @Test
    fun `the page a comic is at is under the bar of its reading`() {
        show(BookUiState(Loadable.Ready(comic), atPage12))

        compose.onNodeWithTag(ReadingPageTag).performScrollTo().assertIsDisplayed()
            .assertTextEquals(text(R.string.reading_page, 12))
    }

    @Test
    fun `a reading without a page has no page line`() {
        show(BookUiState(Loadable.Ready(sampleBookDetail()), atPage12.copy(page = null)))

        compose.onAllNodesWithTag(ReadingPageTag).assertCountEquals(0)
    }
}
