package app.tfdn.lirelle.feature.book

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.designsystem.LocalWindowWidthClass
import app.tfdn.lirelle.core.designsystem.WindowWidthClass
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.model.BookDetail
import app.tfdn.lirelle.core.library.model.BookEdition
import app.tfdn.lirelle.core.library.model.BookFileKind
import app.tfdn.lirelle.core.library.model.BookReading
import app.tfdn.lirelle.core.library.model.PlaybackState
import app.tfdn.lirelle.core.library.model.CoverShape
import app.tfdn.lirelle.core.library.model.EditionMedia
import app.tfdn.lirelle.core.library.model.EditionProgress
import app.tfdn.lirelle.core.library.model.RelatedBook
import app.tfdn.lirelle.core.library.model.RelatedBooks
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.testing.sampleBookDetail
import app.tfdn.lirelle.core.testing.samplePagedDetail
import app.tfdn.lirelle.core.ui.Loadable
import app.tfdn.lirelle.core.ui.NoNetworkDownloadsTag
import app.tfdn.lirelle.core.ui.NoNetworkRetryTag
import app.tfdn.lirelle.core.ui.NoNetworkStateTag
import app.tfdn.lirelle.core.ui.PagingErrorBarTag
import app.tfdn.lirelle.core.ui.R as UiR
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The page of a book without a network: built from what the device keeps, or the "No network" state. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class BookOfflineScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val read = mutableListOf<BookId>()
    private val listened = mutableListOf<BookId>()
    private val narrated = mutableListOf<BookId>()
    private var retries = 0
    private var refreshes = 0
    private var downloadsOpened = 0

    /** A read-along EPUB and an audiobook. */
    private val book = sampleBookDetail()

    /** A read-along EPUB alone: listening is the reader narrating. */
    private val readAlong = book.copy(files = book.files.filter { it.kind != BookFileKind.Audio })

    /** The same book, which its server knows in two editions. */
    private val withEditions = book.copy(
        editions = listOf(
            edition("12", "Unabridged", EditionMedia.Text, isOpen = true),
            edition("40", "Full cast", EditionMedia.Audio, isOpen = false),
        ),
    )

    private fun edition(id: String, label: String, media: EditionMedia, isOpen: Boolean) =
        BookEdition(BookId(id), isOpen, label, book.title, coverUrl = null, media, EditionProgress.Finished)

    private fun text(id: Int, vararg args: Any) = context.getString(id, *args)

    private fun spoken(name: Int, status: Int? = null, vararg args: Any) = context.spokenAction(name, status, *args)

    private fun offline(
        kept: Set<DownloadFormat>,
        detail: BookDetail = book,
        percent: Int? = 11,
        noNetwork: Boolean = true,
    ) = BookUiState(
        book = Loadable.Ready(detail),
        reading = percent?.let { BookReading(it, lastReadAt = null, totalSeconds = 0, sessionCount = 0) },
        listening = Loadable.Failed(ApiError.ServerUnreachable),
        offline = true,
        noNetwork = noNetwork,
        keptFormats = kept,
    )

    /** @param width the width class of the window; the phone's by default. */
    private fun show(state: BookUiState, width: WindowWidthClass = WindowWidthClass.Compact) {
        compose.setContent {
            LirelleTheme {
                CompositionLocalProvider(LocalWindowWidthClass provides width) { Page(state) }
            }
        }
    }

    @Composable
    private fun Page(state: BookUiState) {
        BookContent(
            state = state,
            onBack = {},
            onRetry = { retries++ },
            onRefresh = { refreshes++ },
            onOpenBook = {},
            onOpenAuthor = {},
            onOpenGenre = {},
            onRead = { id, _ -> read += id },
            onListen = { id, _ -> listened += id },
            onNarrate = { narrated += it },
            onOpenDownloads = { downloadsOpened++ },
        )
    }

    private fun assertAbsent(vararg texts: String) =
        texts.forEach { compose.onAllNodesWithText(it, substring = true).assertCountEquals(0) }

    @Test
    fun `the formats the device has whole open, the others wait for the network and say so`() {
        show(offline(kept = setOf(DownloadFormat.Text)))

        compose.onNodeWithTag(OfflineChipTag).assertExists()
        compose.onNodeWithTag(MainActionTag).performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag(ListenActionTag).performScrollTo().assertIsNotEnabled().performClick()
        assertThat(read).containsExactly(BookId("12"))
        assertThat(listened).isEmpty()
        compose.onNodeWithText(text(R.string.book_offline_missing_audiobook)).assertExists()
        compose.onNodeWithText(text(R.string.book_offline_missing_narration)).assertExists()
        assertAbsent(text(R.string.book_offline_missing_text))
    }

    @Test
    fun `a downloaded audiobook plays while the text that is not waits`() {
        show(offline(kept = setOf(DownloadFormat.Audiobook)))

        compose.onNodeWithTag(MainActionTag).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag(ListenActionTag).performScrollTo().assertIsEnabled().performClick()
        assertThat(listened).containsExactly(BookId("12"))
        compose.onNodeWithText(text(R.string.book_offline_missing_text)).assertExists()
    }

    @Test
    fun `the listening the device knows is on its action, and goes on`() {
        val listening = PlaybackState("aud_one", 14_000, 1.4, completed = false, null, version = "", null)
        show(offline(kept = setOf(DownloadFormat.Audiobook)).copy(listening = Loadable.Ready(listening)))

        compose.onNodeWithTag(ListenActionTag)
            .performScrollTo()
            .assertContentDescriptionEquals(spoken(R.string.action_listen, R.string.status_resume_percent_spoken, 1))
            .assertIsEnabled()
            .performClick()
        assertThat(listened).containsExactly(BookId("12"))
    }

    @Test
    fun `everything downloaded, nothing is said to be missing`() {
        show(offline(kept = DownloadFormat.entries.toSet()))

        compose.onNodeWithTag(MainActionTag).performScrollTo().assertIsEnabled()
        compose.onNodeWithTag(ListenActionTag).performScrollTo().assertIsEnabled()
        assertAbsent(
            text(R.string.book_offline_missing_text),
            text(R.string.book_offline_missing_audiobook),
            text(R.string.book_offline_missing_narration),
        )
    }

    @Test
    fun `the reader narrates only a narration that is downloaded`() {
        show(offline(kept = setOf(DownloadFormat.Text), detail = readAlong))

        compose.onNodeWithTag(MainActionTag).performScrollTo().assertIsEnabled()
        compose.onNodeWithTag(ListenActionTag).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.book_offline_missing_narration)).assertExists()
        // The Read icon promises no narration the reader cannot play now.
        compose.assertReadIconNarrated(false)
    }

    @Test
    fun `a downloaded narration is listened to in the reader`() {
        show(offline(kept = setOf(DownloadFormat.Text, DownloadFormat.Narration), detail = readAlong))

        compose.assertReadIconNarrated(true)
        compose.onNodeWithTag(ListenActionTag).performScrollTo().assertIsEnabled().performClick()

        assertThat(narrated).containsExactly(BookId("12"))
    }

    @Test
    fun `my reading is the device's position, without what only the server knows`() {
        show(offline(kept = setOf(DownloadFormat.Text)))

        // The Read button says the progress too: this is the one of the reading block.
        val progress = hasText(text(R.string.reading_progress_value, 11))
        compose.onNode(progress and hasAnyAncestor(hasTestTag("book-reading"))).performScrollTo().assertExists()
        compose.onNodeWithText(text(R.string.reading_offline_note)).assertExists()
        assertAbsent(
            text(R.string.reading_last_read),
            text(R.string.reading_time),
            text(R.string.reading_sessions),
        )
    }

    @Test
    fun `the rows that need the server and the refresh error are not shown`() {
        val related = RelatedBooks(
            inSeries = listOf(
                RelatedBook(BookId("13"), "Next volume", listOf("Ursula Writer"), null, CoverShape.Portrait),
            ),
        )
        show(offline(kept = setOf(DownloadFormat.Text)).copy(related = related))

        assertAbsent(text(R.string.section_in_series), "Next volume")
        compose.onAllNodesWithTag(PagingErrorBarTag).assertCountEquals(0)
    }

    @Test
    fun `the editions, which only the server knows, are not shown`() {
        show(offline(kept = setOf(DownloadFormat.Text), detail = withEditions))

        compose.onAllNodesWithTag(EditionsTag).assertCountEquals(0)
        assertAbsent(text(R.string.section_editions), "Full cast")
    }

    @Test
    @Config(qualifiers = "w1100dp-h800dp")
    fun `on a wide screen the editions are not shown either`() {
        show(offline(kept = setOf(DownloadFormat.Text), detail = withEditions), WindowWidthClass.Expanded)

        compose.onNodeWithTag(OfflineChipTag).assertExists()
        compose.onAllNodesWithTag(EditionsTag).assertCountEquals(0)
        assertAbsent(text(R.string.section_editions), "Full cast")
    }

    @Test
    fun `gone from the server, the editions are not shown either`() {
        show(offline(kept = setOf(DownloadFormat.Text), detail = withEditions, noNetwork = false))

        compose.onAllNodesWithTag(EditionsTag).assertCountEquals(0)
    }

    @Test
    fun `a kept book gone from the server says so, and promises nothing for when the network is back`() {
        show(offline(kept = setOf(DownloadFormat.Text), noNetwork = false))

        compose.onAllNodesWithTag(OfflineChipTag).assertCountEquals(0)
        compose.onNodeWithTag(GoneChipTag).assertExists()
        compose.onNodeWithText(text(R.string.book_gone_chip)).assertExists()
        compose.onNodeWithTag(MainActionTag).performScrollTo().assertIsEnabled()
        compose.onNodeWithTag(ListenActionTag).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.book_gone_missing_audiobook)).assertExists()
        compose.onNodeWithText(text(R.string.book_gone_missing_narration)).assertExists()
        compose.onNodeWithText(text(R.string.reading_device_note)).assertExists()
        assertAbsent(
            text(R.string.book_gone_missing_text),
            text(R.string.book_offline_missing_audiobook),
            text(R.string.book_offline_missing_narration),
            text(R.string.reading_offline_note),
            "network",
        )
    }

    /** Drags the page down from its middle. */
    private fun pullDown() {
        compose.onRoot().performTouchInput {
            swipe(start = Offset(width / 2f, height * 0.6f), end = Offset(width / 2f, height - 1f))
        }
        compose.waitForIdle()
    }

    private fun assertNothingToRefresh() {
        compose.onAllNodesWithTag(RefreshIndicatorTag).assertCountEquals(0)
        pullDown()
        assertThat(refreshes).isEqualTo(0)
        compose.onAllNodesWithTag(RefreshIndicatorTag).assertCountEquals(0)
    }

    @Test
    fun `the text of a book read by pages that is not downloaded is named by its file`() {
        show(offline(kept = emptySet(), detail = samplePagedDetail("CBZ")))

        compose.onNodeWithText("The CBZ is not downloaded: it will be available when the network is back.")
            .assertExists()
        assertAbsent(text(R.string.book_offline_missing_text))
    }

    @Test
    fun `gone from the server, the text of a book read by pages is named by its file too`() {
        show(offline(kept = emptySet(), detail = samplePagedDetail("PDF"), noNetwork = false))

        compose.onNodeWithText("The PDF is not downloaded.").assertExists()
        assertAbsent(text(R.string.book_gone_missing_text), "network")
    }

    @Test
    @Config(qualifiers = "fr-w411dp-h891dp")
    fun `the text of a book read by pages that is not downloaded, in French`() {
        show(offline(kept = emptySet(), detail = samplePagedDetail("DJVU")))

        compose.onNodeWithText("Le DjVu n'est pas téléchargé : il sera disponible au retour du réseau.")
            .assertExists()
        assertAbsent(text(R.string.book_offline_missing_text))
    }

    @Test
    @Config(qualifiers = "fr-w411dp-h891dp")
    fun `gone from the server, in French`() {
        show(offline(kept = emptySet(), detail = samplePagedDetail("CBR"), noNetwork = false))

        compose.onNodeWithText("Le CBR n'est pas téléchargé.").assertExists()
        assertAbsent(text(R.string.book_gone_missing_text))
    }

    @Test
    fun `a page kept without a network has nothing to refresh, and draws no refresh indicator`() {
        show(offline(kept = setOf(DownloadFormat.Text)))

        assertNothingToRefresh()
    }

    @Test
    fun `the page of a book gone from the server has nothing to refresh, and draws no refresh indicator`() {
        show(offline(kept = setOf(DownloadFormat.Text), noNetwork = false))

        assertNothingToRefresh()
    }

    @Test
    fun `a page with a network is still pulled down to refresh`() {
        show(BookUiState(Loadable.Ready(book), listening = Loadable.Ready(null)))

        compose.onNodeWithTag(RefreshIndicatorTag).assertExists()
        pullDown()

        assertThat(refreshes).isEqualTo(1)
    }

    @Test
    fun `without a network the page does not say the book is gone`() {
        show(offline(kept = setOf(DownloadFormat.Text)))

        compose.onAllNodesWithTag(GoneChipTag).assertCountEquals(0)
    }

    @Test
    fun `a page with a network has neither the chip nor the device's note`() {
        val reading = BookReading(42, lastReadAt = null, totalSeconds = 0, sessionCount = 0)
        show(BookUiState(Loadable.Ready(book), reading, listening = Loadable.Ready(null)))

        compose.onAllNodesWithTag(OfflineChipTag).assertCountEquals(0)
        compose.onAllNodesWithTag(GoneChipTag).assertCountEquals(0)
        assertAbsent(text(R.string.reading_offline_note), text(R.string.reading_device_note))
    }

    @Test
    fun `a book that is not downloaded shows the no-network state, which leads to the downloads`() {
        show(BookUiState(Loadable.Failed(ApiError.ServerUnreachable)))

        compose.onNodeWithTag(NoNetworkStateTag).assertExists()
        compose.onNodeWithTag(NoNetworkDownloadsTag).performClick()
        compose.onNodeWithTag(NoNetworkRetryTag).performClick()

        assertThat(downloadsOpened).isEqualTo(1)
        assertThat(retries).isEqualTo(1)
    }

    @Test
    fun `another failure is an error of the server`() {
        show(BookUiState(Loadable.Failed(ApiError.ServerFailed)))

        compose.onAllNodesWithTag(NoNetworkStateTag).assertCountEquals(0)
        compose.onNodeWithText(text(UiR.string.library_error_server_failed)).assertExists()
    }
}
