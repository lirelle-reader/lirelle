package app.tfdn.lirelle.feature.book

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
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
import app.tfdn.lirelle.core.ids.AuthorId
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.ids.SeriesId
import app.tfdn.lirelle.core.library.model.BookFile
import app.tfdn.lirelle.core.library.model.BookFileKind
import app.tfdn.lirelle.core.library.model.BookReading
import app.tfdn.lirelle.core.library.model.CoverShape
import app.tfdn.lirelle.core.library.model.MediaProfile
import app.tfdn.lirelle.core.library.model.PlaybackState
import app.tfdn.lirelle.core.library.model.ReadState
import app.tfdn.lirelle.core.library.model.RelatedBook
import app.tfdn.lirelle.core.library.model.RelatedBooks
import app.tfdn.lirelle.core.library.model.SeriesRef
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.core.testing.sampleBookDetail
import app.tfdn.lirelle.core.ui.FormatBadgeTag
import app.tfdn.lirelle.core.ui.Loadable
import app.tfdn.lirelle.core.ui.LocalSignInAgain
import app.tfdn.lirelle.core.ui.LocalSignInNeeded
import app.tfdn.lirelle.core.ui.PagingErrorBarTag
import app.tfdn.lirelle.core.ui.SignInNeeded
import app.tfdn.lirelle.core.ui.SignInNeededAgainTag
import app.tfdn.lirelle.core.ui.SignInNeededBarTag
import app.tfdn.lirelle.core.ui.SignInNeededStateTag
import app.tfdn.lirelle.feature.book.download.DownloadTileTag
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class BookScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val openedBooks = mutableListOf<BookId>()
    private val openedAuthors = mutableListOf<AuthorId>()
    private val openedGenres = mutableListOf<String>()
    private val openedSeries = mutableListOf<SeriesId>()
    private val read = mutableListOf<Pair<BookId, Boolean>>()
    private val listened = mutableListOf<Pair<BookId, Boolean>>()
    private val narrated = mutableListOf<BookId>()
    private var retries = 0
    private var refreshes = 0

    private val reading = BookReading(42, Instant.parse("2026-03-01T20:00:00Z"), totalSeconds = 7_200, sessionCount = 5)
    private val related = RelatedBooks(
        inSeries = listOf(RelatedBook(BookId("13"), "Next volume", listOf("Ursula Writer"), null, CoverShape.Portrait)),
        sameAuthor =
            listOf(RelatedBook(BookId("20"), "Another book", listOf("Ursula Writer"), null, CoverShape.Portrait)),
        similar = listOf(RelatedBook(BookId("30"), "A similar book", listOf("Someone"), null, CoverShape.Square)),
    )
    private val complete =
        BookUiState(Loadable.Ready(sampleBookDetail()), reading, related, listening = Loadable.Ready(null))

    /** Nothing but a title, a file and its library: far from filling a screen. */
    private val shortBook = sampleBookDetail(media = MediaProfile.TextOnly, series = null).copy(
        subtitle = null, description = null, genres = emptyList(), rating = null, pageCount = null,
        publishedYear = null, publisher = null, language = null, isbn = null, narrators = emptyList(),
        collections = emptyList(), readState = null, startedAt = null,
    )

    private fun text(id: Int, vararg args: Any) = context.getString(id, *args)

    private fun spoken(name: Int, status: Int? = null, vararg args: Any) = context.spokenAction(name, status, *args)

    private val signedInAgain = mutableListOf<String>()

    /** @param signIn the profile that waits for a sign-in, as the signed-in level provides it; null when none. */
    private fun show(
        state: BookUiState,
        width: WindowWidthClass = WindowWidthClass.Compact,
        signIn: SignInNeeded? = null,
    ) {
        compose.setContent {
            LirelleTheme {
                CompositionLocalProvider(
                    LocalWindowWidthClass provides width,
                    LocalSignInNeeded provides signIn,
                    LocalSignInAgain provides { signedInAgain += it },
                ) {
                    BookContent(
                        state = state,
                        onBack = {},
                        onRetry = { retries++ },
                        onRefresh = { refreshes++ },
                        onOpenBook = { openedBooks += it },
                        onOpenAuthor = { openedAuthors += it },
                        onOpenGenre = { openedGenres += it },
                        onRead = { id, fromStart -> read += id to fromStart },
                        onListen = { id, fromStart -> listened += id to fromStart },
                        onNarrate = { narrated += it },
                        onOpenSeries = { openedSeries += it },
                    )
                }
            }
        }
    }

    private fun assertShown(vararg texts: String) = texts.forEach {
        compose.onNodeWithText(it, substring = true).performScrollTo().assertIsDisplayed()
    }

    /**
     * `performScrollTo` moves only the closest scrolling parent, which for a related book is the
     * row it is in: the block is brought into view first.
     */
    private fun assertRelatedShown(blockTag: String, heading: String, title: String) {
        compose.onNodeWithTag(blockTag).performScrollTo()
        compose.onNodeWithText(heading).assertIsDisplayed()
        compose.onNodeWithText(title, substring = true).assertIsDisplayed()
    }

    private fun assertAbsent(vararg texts: String) = texts.forEach {
        compose.onAllNodesWithText(it, substring = true).assertCountEquals(0)
    }

    @Test
    fun `a complete book shows every block`() {
        show(complete)

        assertShown(
            "A Complete Book",
            "With a subtitle",
            "Ursula Writer",
            text(R.string.book_series_line, "A Saga", "2"),
            text(R.string.section_reading),
            text(R.string.read_state_reading),
            text(R.string.section_about),
            "The summary of the book.",
            "Fantasy",
            text(R.string.section_files),
            text(R.string.file_read_along, "EPUB"),
            text(R.string.file_primary),
            text(R.string.file_audio, "M4B"),
            context.resources.getQuantityString(R.plurals.file_chapters, 24, 24),
            text(R.string.section_details),
            "A Narrator",
            "9780000000002",
            "Main library",
            "Favourites",
        )
        // The main action says the progress too: this is the one of the reading block.
        val progress = hasText(text(R.string.reading_progress_value, 42))
        compose.onNode(progress and hasAnyAncestor(hasTestTag("book-reading")))
            .performScrollTo()
            .assertIsDisplayed()
        assertRelatedShown("book-in-series", text(R.string.section_in_series), "Next volume")
        assertRelatedShown("book-same-author", text(R.string.section_same_author), "Another book")
        assertRelatedShown("book-similar", text(R.string.section_similar), "A similar book")
        compose.onNodeWithTag(FormatBadgeTag).performScrollTo().assertIsDisplayed()
        // The publisher is both among the facts, after the year, and in the details.
        val publisher = compose.onAllNodesWithText("A Publisher", substring = true)
        publisher.assertCountEquals(2)
        publisher[0].performScrollTo().assertIsDisplayed()
        publisher[1].performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the facts show the rating, the length, the year and the language`() {
        show(complete)

        assertShown(
            text(R.string.fact_rating, "4.5"),
            context.resources.getQuantityString(R.plurals.fact_pages, 412, 412),
            "2019",
            "English",
        )
    }

    @Test
    fun `a book never opened and without extras hides the blocks that have no data`() {
        val bare = sampleBookDetail(media = MediaProfile.TextOnly, series = null).copy(
            subtitle = null, description = null, genres = emptyList(), rating = null, pageCount = null,
            publishedYear = null, publisher = null, language = null, isbn = null, narrators = emptyList(),
            collections = emptyList(), readState = null, startedAt = null,
        )
        show(BookUiState(Loadable.Ready(bare)))

        assertShown("A Complete Book", text(R.string.section_files), text(R.string.section_details), "Main library")
        assertAbsent(
            text(R.string.section_reading),
            text(R.string.section_about),
            text(R.string.detail_narrators),
            text(R.string.detail_isbn),
            text(R.string.detail_collections),
            text(R.string.section_in_series),
            text(R.string.section_same_author),
            text(R.string.section_similar),
        )
        compose.onAllNodesWithTag(FormatBadgeTag).assertCountEquals(0)
    }

    @Test
    fun `the action that is neither reading nor listening is shown disabled, and there is no other one`() {
        show(complete)

        compose.onNodeWithText(text(R.string.action_download)).performScrollTo().assertIsNotEnabled()
        // Reading and listening together is not an action of the page: the reader starts its narration.
        compose.assertActionButtons(MainActionTag, ListenActionTag, DownloadTileTag)
    }

    @Test
    fun `the main action follows the book`() {
        show(BookUiState(Loadable.Ready(sampleBookDetail(media = MediaProfile.TextOnly))))

        compose.onNodeWithTag(MainActionTag)
            .assertContentDescriptionEquals(spoken(R.string.action_read, R.string.status_start_spoken))
        compose.onNodeWithTag(ListenActionTag).assertDoesNotExist()
        compose.assertActionButtons(MainActionTag, DownloadTileTag)
        compose.assertReadIconNarrated(false)
    }

    @Test
    fun `a book never opened that the reader can open starts being read`() {
        show(BookUiState(Loadable.Ready(sampleBookDetail(media = MediaProfile.TextOnly))))

        compose.onNodeWithTag(MainActionTag)
            .assertContentDescriptionEquals(spoken(R.string.action_read, R.string.status_start_spoken))
            .assertIsEnabled()
            .performClick()

        assertThat(read).containsExactly(BookId("12") to false)
    }

    @Test
    fun `a book being read goes on, and says how far the user is`() {
        show(BookUiState(Loadable.Ready(sampleBookDetail(media = MediaProfile.TextOnly)), reading))

        compose.onNodeWithTag(MainActionTag)
            .assertContentDescriptionEquals(spoken(R.string.action_read, R.string.status_resume_percent_spoken, 42))
            .assertIsEnabled()
            .performClick()

        assertThat(read).containsExactly(BookId("12") to false)
    }

    private fun textBook(readState: ReadState?) =
        sampleBookDetail(media = MediaProfile.TextOnly).copy(readState = readState)

    @Test
    fun `a book marked as read is read again, from its beginning`() {
        show(BookUiState(Loadable.Ready(textBook(ReadState.Read)), reading))

        compose.onNodeWithTag(MainActionTag)
            .assertContentDescriptionEquals(spoken(R.string.action_read, R.string.status_read_again_spoken))
            .assertIsEnabled()
            .performClick()

        assertThat(read).containsExactly(BookId("12") to true)
    }

    @Test
    fun `a book marked as read and never opened here is read again too`() {
        show(BookUiState(Loadable.Ready(textBook(ReadState.Read))))

        compose.onNodeWithTag(MainActionTag)
            .assertContentDescriptionEquals(spoken(R.string.action_read, R.string.status_read_again_spoken))
            .performClick()

        assertThat(read).containsExactly(BookId("12") to true)
    }

    @Test
    fun `a book at 98 percent the server does not say is read goes on where it is`() {
        assertGoesOnReadingAt(percent = 98)
    }

    @Test
    fun `a book at 99 percent the server does not say is read goes on where it is`() {
        assertGoesOnReadingAt(percent = 99)
    }

    @Test
    fun `a book at its end the server does not say is read goes on where it is`() {
        assertGoesOnReadingAt(percent = 100)
    }

    /** Only the server says a book is read: until then its action goes on, and never starts it over. */
    private fun assertGoesOnReadingAt(percent: Int) {
        show(BookUiState(Loadable.Ready(textBook(ReadState.Reading)), reading.copy(progressPercent = percent)))

        compose.onNodeWithTag(MainActionTag)
            .assertContentDescriptionEquals(
                spoken(R.string.action_read, R.string.status_resume_percent_spoken, percent),
            )
            .performClick()

        assertThat(read).containsExactly(BookId("12") to false)
    }

    @Test
    fun `a book the server says is read is read again from its beginning, wherever it stopped`() {
        show(BookUiState(Loadable.Ready(textBook(ReadState.Read)), reading.copy(progressPercent = 98)))

        compose.onNodeWithTag(MainActionTag)
            .assertContentDescriptionEquals(spoken(R.string.action_read, R.string.status_read_again_spoken))
            .performClick()

        assertThat(read).containsExactly(BookId("12") to true)
    }

    @Test
    fun `a book nearly at its end goes on where it is`() {
        show(BookUiState(Loadable.Ready(textBook(ReadState.Reading)), reading.copy(progressPercent = 97)))

        compose.onNodeWithTag(MainActionTag)
            .assertContentDescriptionEquals(spoken(R.string.action_read, R.string.status_resume_percent_spoken, 97))
            .performClick()

        assertThat(read).containsExactly(BookId("12") to false)
    }

    @Test
    fun `an audiobook marked as read and never listened to here starts being listened to`() {
        val book = sampleBookDetail(media = MediaProfile.AudioOnly).copy(readState = ReadState.Read)
        show(BookUiState(Loadable.Ready(book), listening = Loadable.Ready(null)))

        // It starts, its ring empty: nothing on the page offers to do the book again.
        compose.onNodeWithTag(ListenActionTag)
            .assertContentDescriptionEquals(spoken(R.string.action_listen, R.string.status_start_spoken))
            .assert(SemanticsMatcher.expectValue(ActionRingFractionKey, 0f))
        compose.assertActionButtons(ListenActionTag, DownloadTileTag)
        assertAbsent("again")
    }

    @Test
    fun `a book without a file the reader opens cannot be read`() {
        val plainText = BookFile(FileId("1"), BookFileKind.Text, "TXT", 2_000_000, isPrimary = true, null, null)
        val book = sampleBookDetail(media = MediaProfile.TextOnly).copy(files = listOf(plainText))
        show(BookUiState(Loadable.Ready(book)))

        compose.onNodeWithTag(MainActionTag)
            .assertContentDescriptionEquals(spoken(R.string.action_read, R.string.status_start_spoken))
            .assertIsNotEnabled()
            .performClick()

        assertThat(read).isEmpty()
    }

    @Test
    fun `a KEPUB is read as an EPUB is`() {
        val kepub = BookFile(FileId("1"), BookFileKind.Text, "kepub", 2_000_000, isPrimary = true, null, null)
        val book = sampleBookDetail(media = MediaProfile.TextOnly).copy(files = listOf(kepub))
        show(BookUiState(Loadable.Ready(book)))

        compose.onNodeWithTag(MainActionTag).assertIsEnabled()
    }

    @Test
    fun `an audiobook is listened to, not read`() {
        val audiobook = sampleBookDetail(media = MediaProfile.AudioOnly)
        show(BookUiState(Loadable.Ready(audiobook), listening = Loadable.Ready(null)))

        compose.onNodeWithTag(ListenActionTag)
            .assertContentDescriptionEquals(spoken(R.string.action_listen, R.string.status_start_spoken))
            .assertIsEnabled()
            .performClick()

        assertThat(listened).containsExactly(BookId("12") to false)
        assertThat(narrated).isEmpty()
        compose.onNodeWithTag(MainActionTag).assertDoesNotExist()
        compose.assertActionButtons(ListenActionTag, DownloadTileTag)
    }

    private fun listening(percentage: Double, completed: Boolean = false) = PlaybackState(
        assetId = "aud_1",
        positionMs = 60_000,
        percentage = percentage,
        completed = completed,
        capturedAt = Instant.parse("2026-03-02T08:00:00Z"),
        version = "v3",
        contentVersion = "rev",
    )

    private fun showAudiobook(listening: Loadable<PlaybackState?>) =
        show(BookUiState(Loadable.Ready(sampleBookDetail(media = MediaProfile.AudioOnly)), listening = listening))

    @Test
    fun `while where the listening is is not known, the action says listen and waits`() {
        showAudiobook(Loadable.Loading)

        compose.onNodeWithTag(ListenActionTag)
            .assertContentDescriptionEquals(spoken(R.string.action_listen))
            .assertIsNotEnabled()
            .performClick()

        assertThat(listened).isEmpty()
    }

    @Test
    fun `when where the listening is cannot be had, the action says listen and goes on`() {
        showAudiobook(Loadable.Failed(ApiError.ServerUnreachable))

        compose.onNodeWithTag(ListenActionTag)
            .assertContentDescriptionEquals(spoken(R.string.action_listen))
            .assertIsEnabled()
            .performClick()

        assertThat(listened).containsExactly(BookId("12") to false)
    }

    @Test
    fun `an audiobook being listened to goes on, and says how far the user is`() {
        showAudiobook(Loadable.Ready(listening(42.7)))

        compose.onNodeWithTag(ListenActionTag)
            .assertContentDescriptionEquals(spoken(R.string.action_listen, R.string.status_resume_percent_spoken, 42))
            .performClick()

        assertThat(listened).containsExactly(BookId("12") to false)
    }

    @Test
    fun `an audiobook barely started goes on, without a percentage of nought`() {
        showAudiobook(Loadable.Ready(listening(0.3)))

        compose.onNodeWithTag(ListenActionTag)
            .assertContentDescriptionEquals(spoken(R.string.action_listen, R.string.status_resume_spoken))
            .performClick()

        assertThat(listened).containsExactly(BookId("12") to false)
    }

    @Test
    fun `an audiobook at 98 percent the server does not say is completed goes on where it is`() {
        assertGoesOnListeningAt(percentage = 98.0, shown = 98)
    }

    @Test
    fun `an audiobook at 99 percent the server does not say is completed goes on where it is`() {
        assertGoesOnListeningAt(percentage = 99.6, shown = 99)
    }

    @Test
    fun `an audiobook at its end the server does not say is completed goes on where it is`() {
        assertGoesOnListeningAt(percentage = 100.0, shown = 100)
    }

    /** Only the server says a listening is completed: until then its action goes on, and never starts it over. */
    private fun assertGoesOnListeningAt(percentage: Double, shown: Int) {
        showAudiobook(Loadable.Ready(listening(percentage)))

        compose.onNodeWithTag(ListenActionTag)
            .assertContentDescriptionEquals(
                spoken(R.string.action_listen, R.string.status_resume_percent_spoken, shown),
            )
            .performClick()

        assertThat(listened).containsExactly(BookId("12") to false)
    }

    @Test
    fun `an audiobook the server says is completed is listened to again, wherever it stopped`() {
        showAudiobook(Loadable.Ready(listening(60.0, completed = true)))

        compose.onNodeWithTag(ListenActionTag)
            .assertContentDescriptionEquals(spoken(R.string.action_listen, R.string.status_listen_again_spoken))
            .performClick()

        assertThat(listened).containsExactly(BookId("12") to true)
    }

    @Test
    fun `a book with a text and audio can be read and listened to, each where it is`() {
        val book = sampleBookDetail(media = MediaProfile.TextAndAudio)
        show(BookUiState(Loadable.Ready(book), reading, listening = Loadable.Ready(listening(12.0))))

        compose.onNodeWithTag(MainActionTag)
            .assertContentDescriptionEquals(spoken(R.string.action_read, R.string.status_resume_percent_spoken, 42))
            .performClick()
        compose.onNodeWithTag(ListenActionTag)
            .assertContentDescriptionEquals(spoken(R.string.action_listen, R.string.status_resume_percent_spoken, 12))
            .assertIsEnabled()
            .performClick()

        assertThat(read).containsExactly(BookId("12") to false)
        assertThat(listened).containsExactly(BookId("12") to false)
        assertThat(narrated).isEmpty()
        // The reader cannot narrate such a book: the icon of Read is a plain book.
        compose.assertActionButtons(MainActionTag, ListenActionTag, DownloadTileTag)
        compose.assertReadIconNarrated(false)
    }

    @Test
    fun `a read-along book without an audio file is listened to in the reader, its narration read along`() {
        val book = sampleBookDetail(media = MediaProfile.ReadAlong)
        val readAlongOnly = book.copy(files = book.files.filter { it.kind != BookFileKind.Audio })
        // No playback state is asked for such a book: its listening stays loading.
        show(BookUiState(Loadable.Ready(readAlongOnly), reading))

        compose.onNodeWithTag(ListenActionTag)
            .performScrollTo()
            .assertContentDescriptionEquals(spoken(R.string.action_listen))
            .assertIsEnabled()
            .performClick()

        assertThat(narrated).containsExactly(BookId("12"))
        assertThat(listened).isEmpty()
        // Read opens the reader silently; its icon and what it says tell the reader can narrate the book.
        compose.onNodeWithTag(MainActionTag)
            .assertIsEnabled()
            .assertContentDescriptionEquals(
                context.spokenAction(R.string.action_read, R.string.status_resume_percent_spoken, 42, narrated = true),
            )
        compose.assertReadIconNarrated(true)
        compose.assertActionButtons(MainActionTag, ListenActionTag, DownloadTileTag)
    }

    @Test
    fun `a read-along book without an audio file nor a file the reader opens cannot be listened to`() {
        val book = sampleBookDetail(media = MediaProfile.ReadAlong)
        val plainText = book.files.filter { it.kind != BookFileKind.Audio }.map { it.copy(format = "TXT") }
        val unreadable = book.copy(files = plainText)
        show(BookUiState(Loadable.Ready(unreadable), reading))

        compose.onNodeWithTag(ListenActionTag).performScrollTo().assertIsNotEnabled().performClick()

        assertThat(narrated).isEmpty()
    }

    @Test
    fun `a book with a text only has no listening at all`() {
        val book = sampleBookDetail(media = MediaProfile.TextOnly)
        show(BookUiState(Loadable.Ready(book.copy(files = book.files.filter { it.kind != BookFileKind.Audio }))))

        compose.onNodeWithTag(ListenActionTag).assertDoesNotExist()
    }

    @Test
    fun `a read-along book keeps reading and listening apart, the icon of Read saying the reader narrates it`() {
        show(complete)

        compose.onNodeWithTag(MainActionTag).assertIsEnabled().performClick()
        compose.onNodeWithTag(ListenActionTag).performScrollTo().assertIsEnabled().performClick()
        compose.assertReadIconNarrated(true)
        compose.assertActionButtons(MainActionTag, ListenActionTag, DownloadTileTag)

        // Read opens the reader, which does not start narrating.
        assertThat(read).containsExactly(BookId("12") to false)
        // Its audio file is played in the player, as an audiobook's.
        assertThat(listened).containsExactly(BookId("12") to false)
        assertThat(narrated).isEmpty()
    }

    @Test
    fun `the audio files of a book in several parts are one row`() {
        val epub = BookFile(FileId("1"), BookFileKind.Text, "EPUB", 2_000_000, isPrimary = true, null, null)
        val tracks = (11..13).map { BookFile(FileId("$it"), BookFileKind.Audio, "MP3", 20_000_000, false, 3_600, null) }
        val book = sampleBookDetail(media = MediaProfile.TextAndAudio).copy(files = listOf(epub) + tracks)
        show(BookUiState(Loadable.Ready(book)))

        compose.onNodeWithTag("book-files").performScrollTo()
        compose.onAllNodesWithText(text(R.string.file_audio, "MP3")).assertCountEquals(1)
        assertShown(
            context.resources.getQuantityString(R.plurals.file_count, 3, 3),
            text(R.string.file_narration, text(R.string.duration_hours_minutes, 3, 0)),
        )
        compose.onAllNodesWithText(text(R.string.file_primary), substring = true).assertCountEquals(1)
    }

    @Test
    fun `a single audio file keeps its own row`() {
        show(complete)

        assertAbsent(context.resources.getQuantityString(R.plurals.file_count, 1, 1))
    }

    @Test
    fun `the author and the related books can be opened`() {
        show(complete)

        compose.onNodeWithTag("author-9").performScrollTo().performClick()
        // The page is scrolled to the block: scrolling to a book only moves the row it is in.
        compose.onNodeWithTag("book-in-series").performScrollTo()
        compose.onNodeWithTag("related-13").performClick()
        compose.onNodeWithTag("book-similar").performScrollTo()
        compose.onNodeWithTag("related-30").performClick()

        assertThat(openedAuthors).containsExactly(AuthorId("9"))
        assertThat(openedBooks).containsExactly(BookId("13"), BookId("30")).inOrder()
    }

    @Test
    fun `the heading of the row of the series opens the series of the book`() {
        show(complete)

        compose.onNodeWithTag("book-in-series-open").performScrollTo().performClick()

        assertThat(openedSeries).containsExactly(SeriesId("4"))
        assertThat(openedBooks).isEmpty()
        // The other rows lead nowhere: their headings stay plain.
        compose.onAllNodesWithTag("book-same-author-open").assertCountEquals(0)
        compose.onAllNodesWithTag("book-similar-open").assertCountEquals(0)
    }

    @Test
    fun `a series the server gives without an id keeps a plain heading`() {
        val book = sampleBookDetail(series = SeriesRef(id = null, name = "A Saga", index = "2"))
        show(complete.copy(book = Loadable.Ready(book)))

        compose.onNodeWithTag("book-in-series").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.section_in_series)).assertIsDisplayed().assertHasNoClickAction()
        compose.onAllNodesWithTag("book-in-series-open").assertCountEquals(0)
    }

    @Test
    fun `a genre opens its list`() {
        show(complete)

        compose.onNodeWithTag("genre-Fantasy").performScrollTo().performClick()

        assertThat(openedGenres).containsExactly("Fantasy")
    }

    @Test
    fun `a genre says where it leads and is large enough to touch`() {
        show(complete)

        val genre = compose.onNodeWithTag("genre-Fantasy").performScrollTo()

        genre.assertContentDescriptionEquals(text(R.string.genre_open_description, "Fantasy"))
        // What answers a touch, which reaches past the outline of a genre with a short name.
        val touch = genre.fetchSemanticsNode().touchBoundsInRoot
        val minimum = 48 * context.resources.displayMetrics.density
        assertThat(touch.height).isAtLeast(minimum)
        assertThat(touch.width).isAtLeast(minimum)
    }

    @Test
    fun `a failed refresh is said under the page, which stays`() {
        show(complete.copy(refreshError = ApiError.ServerUnreachable))

        compose.onNodeWithTag(PagingErrorBarTag).assertIsDisplayed()
        compose.onNodeWithText("A Complete Book").assertIsDisplayed()

        compose.onNodeWithText(text(app.tfdn.lirelle.core.ui.R.string.state_retry)).performClick()
        assertThat(refreshes).isEqualTo(1)
    }

    private val camille = SignInNeeded("p0", "camille", ServerKind.BookOrbit)

    @Test
    fun `a refresh that failed while the profile waits for a sign-in says so under the page, and signs in again`() {
        show(complete.copy(refreshError = ApiError.ServerUnreachable), signIn = camille)

        compose.onNodeWithTag(SignInNeededBarTag).assertIsDisplayed()
        compose.onNodeWithText("A Complete Book").assertIsDisplayed()
        compose.onNodeWithText("The session of camille · BookOrbit has expired.").assertIsDisplayed()
        compose.onNodeWithTag(PagingErrorBarTag).assertDoesNotExist()
        compose.onNodeWithText(text(app.tfdn.lirelle.core.ui.R.string.state_retry)).assertDoesNotExist()
        compose.onNodeWithText(text(app.tfdn.lirelle.core.ui.R.string.sign_in_again)).performClick()

        assertThat(signedInAgain).containsExactly("p0")
        assertThat(refreshes).isEqualTo(0)
    }

    @Test
    fun `a page that cannot be loaded while the profile waits for a sign-in says so, and signs in again`() {
        show(BookUiState(Loadable.Failed(ApiError.ServerUnreachable)), signIn = camille)

        compose.onNodeWithTag(SignInNeededStateTag).assertIsDisplayed()
        compose.onNodeWithText(text(app.tfdn.lirelle.core.ui.R.string.no_network_title)).assertDoesNotExist()
        compose.onNodeWithTag(SignInNeededAgainTag).performScrollTo().performClick()

        assertThat(signedInAgain).containsExactly("p0")
        assertThat(retries).isEqualTo(0)
    }

    /** Drags down from the empty lower part of the page, at [xFraction] of its width. */
    private fun pullDown(xFraction: Float = 0.5f) {
        compose.onRoot().performTouchInput {
            swipe(
                start = Offset(width * xFraction, height * PullStartFraction),
                end = Offset(width * xFraction, height - 1f),
            )
        }
        compose.waitForIdle()
    }

    @Test
    fun `pulling a page shorter than the screen down refreshes it`() {
        show(BookUiState(Loadable.Ready(shortBook)))

        pullDown()

        assertThat(refreshes).isEqualTo(1)
    }

    @Test
    @Config(qualifiers = "w1100dp-h800dp")
    fun `on a wide screen either column can be pulled down`() {
        show(BookUiState(Loadable.Ready(shortBook)), width = WindowWidthClass.Expanded)

        pullDown(xFraction = 0.1f)
        assertThat(refreshes).isEqualTo(1)

        pullDown(xFraction = 0.9f)
        assertThat(refreshes).isEqualTo(2)
    }

    @Test
    fun `a book that is gone says so and offers no retry`() {
        show(BookUiState(Loadable.Failed(ApiError.NotFound)))

        compose.onNodeWithText(text(app.tfdn.lirelle.core.ui.R.string.library_error_not_found)).assertIsDisplayed()
        assertAbsent(text(app.tfdn.lirelle.core.ui.R.string.state_retry))
    }

    @Test
    fun `an unreachable server offers a retry`() {
        show(BookUiState(Loadable.Failed(ApiError.ServerUnreachable)))

        compose.onNodeWithText(text(app.tfdn.lirelle.core.ui.R.string.state_retry)).performClick()

        assertThat(retries).isEqualTo(1)
    }

    @Test
    @Config(qualifiers = "w1100dp-h800dp")
    fun `on a wide screen the cover and the blocks are side by side`() {
        show(complete, width = WindowWidthClass.Expanded)

        val header = compose.onNodeWithTag("book-header").getUnclippedBoundsInRoot()
        val about = compose.onNodeWithTag("book-about").getUnclippedBoundsInRoot()
        assertThat(about.left).isAtLeast(header.right)
    }

    @Test
    fun `on a phone the blocks follow the header`() {
        show(complete)

        val header = compose.onNodeWithTag("book-header").getUnclippedBoundsInRoot()
        val actions = compose.onNodeWithTag("book-actions").getUnclippedBoundsInRoot()
        assertThat(actions.top).isAtLeast(header.bottom)
        assertThat(actions.left).isEqualTo(header.left)
    }

    @Test
    fun `a row of related books left empty is not shown`() {
        show(complete.copy(related = related.copy(sameAuthor = emptyList())))

        compose.onNodeWithTag("book-in-series").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithTag("book-same-author").assertCountEquals(0)
        assertAbsent(text(R.string.section_same_author))
    }

    @Test
    fun `the primary file is the first row and carries its mark, once`() {
        val audio = BookFile(FileId("1"), BookFileKind.Audio, "M4B", 310_000_000, isPrimary = false, 36_500, 24)
        val epub = BookFile(FileId("2"), BookFileKind.Text, "EPUB", 2_000_000, isPrimary = true, null, null)
        val book = sampleBookDetail(media = MediaProfile.TextAndAudio).copy(files = listOf(audio, epub))
        show(BookUiState(Loadable.Ready(book)))
        compose.onNodeWithTag("book-files").performScrollTo()

        val firstRow = hasAnyAncestor(hasTestTag("book-file-0"))
        compose.onNode(hasText("EPUB") and firstRow, useUnmergedTree = true).assertIsDisplayed()
        compose.onNode(hasText(text(R.string.file_audio, "M4B")) and hasAnyAncestor(hasTestTag("book-file-1")), true)
            .assertIsDisplayed()
        // A label of its own, in words: not the last item of the facts of the file.
        compose.onAllNodesWithText(text(R.string.file_primary), substring = true, useUnmergedTree = true)
            .assertCountEquals(1)
        compose.onNode(hasText(text(R.string.file_primary)) and firstRow, useUnmergedTree = true).assertIsDisplayed()
        compose.onAllNodesWithTag(PrimaryFileTag, useUnmergedTree = true).assertCountEquals(1)
        compose.onNode(hasTestTag(PrimaryFileTag) and firstRow, useUnmergedTree = true).assertIsDisplayed()
        val first = compose.onNodeWithTag("book-file-0").getUnclippedBoundsInRoot()
        val second = compose.onNodeWithTag("book-file-1").getUnclippedBoundsInRoot()
        assertThat(second.top).isAtLeast(first.bottom)
    }

    private companion object {
        /** Where a pull starts, as a part of the height of the screen: under what a short page shows. */
        const val PullStartFraction = 0.6f
    }
}
