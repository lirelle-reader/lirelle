package app.tfdn.lirelle.feature.downloads

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.designsystem.LocalWindowWidthClass
import app.tfdn.lirelle.core.designsystem.WindowWidthClass
import app.tfdn.lirelle.core.downloads.model.DownloadFailure
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.PageCount
import app.tfdn.lirelle.core.downloads.model.TextName
import app.tfdn.lirelle.core.downloads.model.WaitReason
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.testing.DeviceRule
import app.tfdn.lirelle.core.testing.LaidOutText
import app.tfdn.lirelle.core.testing.assertAllTextFits
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.annotation.GraphicsMode

/**
 * No text of the Downloads destination overflows or is clipped, at any width, language or text size. A title and
 * the authors under it are one line with an ellipsis; everything else is whole.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Real font metrics: the default mode counts one pixel per character.
class DownloadsTextFitTest(private val screen: String, language: String, private val fontScale: Float) {

    @get:Rule(order = 0)
    val device = DeviceRule(qualifiers = "$language-$screen", fontScale = fontScale)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val wide = screen == Wide
    private val titles = setOf(LongTitle, OtherLongTitle, LongAuthors)

    /** The labels of the buttons that grow with them. */
    private val wrapping by lazy {
        setOf(
            R.string.downloads_wifi_now,
            R.string.downloads_wifi_always,
            R.string.downloads_browse,
            R.string.downloads_update,
            R.string.downloads_update_all,
        ).map(::string).toSet()
    }

    private fun string(id: Int) = compose.activity.getString(id)

    private val books = listOf(
        DownloadedRow(
            bookId = BookId("1"),
            title = LongTitle,
            authors = LongAuthors,
            cover = null,
            formats = listOf(DownloadFormat.Text, DownloadFormat.Audiobook, DownloadFormat.Narration),
            bytes = 23_400 * Mb,
            percent = 100,
        ),
        DownloadedRow(
            BookId("2"),
            OtherLongTitle,
            "Ursula Writer",
            null,
            listOf(DownloadFormat.Audiobook),
            1_900 * Mb,
            null,
        ),
        DownloadedRow(BookId("3"), "Short", "", null, listOf(DownloadFormat.Text), 4 * Mb, percent = 7),
    )
    private val listed = DownloadsUiState(
        storage = StorageUi(125_300 * Mb, bookCount = 123, freeBytes = 187_600 * Mb, totalBytes = 512 * Gb),
        books = books,
        loaded = true,
    )
    private val allFormats = listOf(DownloadFormat.Text, DownloadFormat.Audiobook, DownloadFormat.Narration)
    private val rows = listOf(
        ProgressRow(BookId("4"), LongTitle, null, allFormats, ProgressStatus.Active(1_240 * Mb, 4_120 * Mb)),
        ProgressRow(
            BookId("5"),
            OtherLongTitle,
            null,
            listOf(DownloadFormat.Audiobook),
            ProgressStatus.Active(124 * Mb, null),
        ),
        ProgressRow(BookId("6"), LongTitle, null, allFormats, ProgressStatus.Queued(3_780 * Mb)),
        ProgressRow(BookId("7"), "Short", null, allFormats - DownloadFormat.Audiobook, ProgressStatus.Queued(null)),
    )
    private val waitingRows = listOf(
        ProgressStatus.Waiting(WaitReason.UnmeteredOnly, 1_240 * Mb, 4_120 * Mb),
        ProgressStatus.Waiting(WaitReason.NoNetwork, 1_240 * Mb, 4_120 * Mb),
        ProgressStatus.Waiting(WaitReason.UnmeteredOnly, 0, 4_120 * Mb),
        ProgressStatus.Waiting(WaitReason.NoNetwork, 0, null),
    ).mapIndexed { index, status -> ProgressRow(BookId("waiting-$index"), LongTitle, null, allFormats, status) }
    private val failures = listOf(
        DownloadFailure.NoSpace(3_200 * Mb),
        DownloadFailure.Server(ApiError.ServerUnreachable),
        DownloadFailure.Server(ApiError.Unexpected),
        DownloadFailure.NotAnEpub,
        DownloadFailure.Damaged,
        DownloadFailure.NotAllowed,
    ).mapIndexed { index, failure ->
        val id = BookId("failed-$index")
        ProgressRow(id, LongTitle, null, listOf(DownloadFormat.Text), ProgressStatus.Failed(failure))
    }

    @Test
    fun `the storage and the downloaded books`() {
        val texts = showLists(listed)

        assertThat(texts.count { it.text in titles }).isAtLeast(1)
    }

    @Test
    fun `the downloads in progress`() {
        showLists(listed.copy(inProgress = rows, books = emptyList()))
    }

    @Test
    fun `the downloads stopped by a wait`() {
        showLists(listed.copy(inProgress = waitingRows, books = emptyList()))
    }

    @Test
    fun `the downloads made of pages, fetched, stopped by a wait, queued and updated`() {
        val pages = PageCount(1_234, 4_321)
        val statuses = listOf(rows[0].status, waitingRows[0].status, waitingRows[1].status, rows[2].status)
        val albums = statuses.mapIndexed { index, status ->
            val id = BookId("album-$index")
            ProgressRow(id, LongTitle, null, allFormats, status, textName = TextName.Djvu, pages = pages)
        }
        val updated = albums.take(3).map { it.copy(bookId = BookId("${it.bookId}-update"), update = true) }

        showLists(listed.copy(inProgress = albums + updated, books = emptyList()))
    }

    @Test
    fun `the downloaded books whose text is read by pages`() {
        val paged = listOf(TextName.Pdf, TextName.Cbz, TextName.Djvu)
            .mapIndexed { index, name -> books[0].copy(bookId = BookId("paged-$index"), textName = name) }

        showLists(listed.copy(books = paged))
    }

    @Test
    fun `the downloads that failed`() {
        showLists(listed.copy(inProgress = failures, books = emptyList()))
    }

    @Test
    fun `the updates, with the downloaded books that have a note`() {
        val updates =
            books.map { UpdateRow(it.copy(bookId = BookId("${it.bookId}-update")), updateBytes = 23_400 * Mb) }
        val noted = listOf(books[0].copy(note = RowNote.Gone), books[1].copy(note = RowNote.UpdateReady))

        val texts = showLists(listed.copy(updates = updates, books = noted))

        val labels = setOf(string(R.string.downloads_update), string(R.string.downloads_update_all))
        assertThat(texts.map { it.text }).containsAtLeastElementsIn(labels)
        texts.filter { it.text in labels }
            .forEach { assertWithMessage("$it is cut short").that(it.isEllipsized).isFalse() }
    }

    @Test
    fun `the updates in progress`() {
        val statuses = listOf(rows[0].status, waitingRows[0].status, waitingRows[2].status, rows[2].status) +
            failures.map { it.status }
        val updating = statuses.mapIndexed { index, status ->
            ProgressRow(BookId("updating-$index"), LongTitle, null, listOf(DownloadFormat.Text), status, update = true)
        }

        showLists(listed.copy(inProgress = updating, books = emptyList()))
    }

    @Test
    fun `the banner of a queue waiting for Wi-Fi`() {
        val waiting = listed.copy(inProgress = rows.take(1), books = emptyList())

        showLists(waiting.copy(wifi = WifiBannerUi(bookCount = 12, leftBytes = 16_660 * Mb)))
    }

    @Test
    fun `the banner without a size, and the wait for the network`() {
        val waiting = listed.copy(inProgress = rows.take(1), books = emptyList())

        showLists(waiting.copy(wifi = WifiBannerUi(bookCount = 1, leftBytes = null), waitingForNetwork = true))
    }

    @Test
    fun `the labels of the banner are whole, on two lines when one is too short`() {
        val waiting = listed.copy(inProgress = rows.take(1), wifi = WifiBannerUi(bookCount = 2, leftBytes = 666 * Mb))

        show(waiting)

        // Asked while they are on screen: a text scrolled away no longer knows where it was.
        val labels = compose.assertAllTextFits(titles, wrapping).filter { it.isLabel }

        assertThat(labels.map { it.text })
            .containsExactly(string(R.string.downloads_wifi_now), string(R.string.downloads_wifi_always))
        labels.forEach { assertWithMessage("$it is cut short").that(it.isEllipsized).isFalse() }
    }

    @Test
    fun `nothing downloaded`() {
        val texts = showLists(DownloadsUiState(storage = StorageUi(0, 0, 44_600 * Mb, 128 * Gb), loaded = true))

        val browse = texts.filter { it.text == string(R.string.downloads_browse) }
        assertThat(browse).isNotEmpty()
        browse.forEach { assertWithMessage("$it is cut short").that(it.isEllipsized).isFalse() }
    }

    @Test
    fun `nothing downloaded yet, with a download in progress`() {
        val storage = StorageUi(124 * Mb, 0, 44_600 * Mb, 128 * Gb, queued = true)

        showLists(DownloadsUiState(storage = storage, inProgress = rows.take(1), loaded = true))
    }

    @Test
    fun `the menu of a book`() {
        show(listed)

        compose.onNodeWithTag(downloadedRowTag(BookId("1"))).performTouchInput { longClick() }

        compose.assertAllTextFits(titles, wrapping)
        compose.onNodeWithTag(DeleteMenuItemTag).assertExists()
    }

    @Test
    fun `the message of a deletion, with its undo`() {
        val deleted = DownloadsSnackbar(DownloadsSnackbar.Kind.Deleted, serial = 1, bookId = BookId("1"))
        show(listed.copy(snackbar = deleted))

        val texts = compose.assertAllTextFits(titles, wrapping)

        assertThat(texts.map { it.text }).contains(compose.activity.getString(R.string.downloads_deleted))
    }

    @Test
    fun `the message of the metered networks allowed, with its undo`() {
        show(listed.copy(snackbar = DownloadsSnackbar(DownloadsSnackbar.Kind.MobileAllowed, serial = 1)))

        val texts = compose.assertAllTextFits(titles, wrapping)

        assertThat(texts.map { it.text }).contains(compose.activity.getString(R.string.downloads_mobile_allowed))
    }

    @Test
    fun `the sheet that asks which formats to delete`() {
        val sheet = DeleteSheetUi(
            bookId = BookId("1"),
            title = LongTitle,
            bytes = 23_400 * Mb,
            options = listOf(
                DeleteOption(DownloadFormat.Text, 3_200_000),
                DeleteOption(DownloadFormat.Audiobook, 12_300 * Mb),
                DeleteOption(DownloadFormat.Narration, 11_100 * Mb),
            ),
        )
        show(DownloadsUiState(loaded = true, deleteSheet = sheet))

        compose.assertAllTextFits(wrappingLabels = wrapping)
        // With something checked, the button says the space freed.
        compose.onNodeWithTag(deleteOptionTag(DownloadFormat.Audiobook)).performScrollTo().performClick()
        compose.onNodeWithTag(deleteOptionTag(DownloadFormat.Narration)).performScrollTo().performClick()

        val texts = compose.assertAllTextFits(wrappingLabels = wrapping)
        assertThat(texts.map { it.text }).contains(compose.activity.getString(R.string.downloads_sheet_note))
    }

    @Test
    fun `the sheet that asks which formats to delete of a book read by pages`() {
        val sheet = DeleteSheetUi(
            bookId = BookId("1"),
            title = LongTitle,
            bytes = 23_400 * Mb,
            options = listOf(
                DeleteOption(DownloadFormat.Text, 3_200_000, TextName.Djvu),
                DeleteOption(DownloadFormat.Audiobook, 12_300 * Mb),
            ),
        )
        show(DownloadsUiState(loaded = true, deleteSheet = sheet))

        val texts = compose.assertAllTextFits(wrappingLabels = wrapping)
        assertThat(texts.map { it.text }).contains("DjVu")
    }

    private fun show(state: DownloadsUiState) {
        compose.setContent {
            LirelleTheme {
                val width = if (wide) WindowWidthClass.Expanded else WindowWidthClass.Compact
                CompositionLocalProvider(LocalWindowWidthClass provides width) {
                    DownloadsScreen(state, DownloadsActions(), onOpenBook = {}, onBrowseLibrary = {})
                }
            }
        }
    }

    /** Checks what is on screen, then each item of the lists brought to the top: what a tall list hides too. */
    private fun showLists(state: DownloadsUiState): List<LaidOutText> {
        show(state)
        val seen = compose.assertAllTextFits(titles, wrapping).toMutableList()
        val list = compose.onNodeWithTag(if (wide) DownloadsGridTag else DownloadsListTag)
        repeat(state.itemCount()) { index ->
            list.performScrollToIndex(index)
            seen += compose.assertAllTextFits(titles, wrapping)
        }
        seen.filter { it.text in titles }.forEach {
            assertWithMessage("lines of $it").that(it.lineCount).isEqualTo(1)
        }
        return seen
    }

    /** The items of the lists: the heading, the banner and the line of a wait, the section titles, the rows. */
    private fun DownloadsUiState.itemCount(): Int {
        val waits = listOf(wifi != null, waitingForNetwork).count { it }
        val sections = listOf(inProgress.isNotEmpty(), updates.isNotEmpty(), downloadedHeading).count { it }
        return 1 + waits + sections + inProgress.size + updates.size + books.size + if (isEmpty) 1 else 0
    }

    companion object {
        private const val Narrow = "w320dp-h640dp"
        private const val Phone = "w411dp-h891dp"
        private const val Wide = "w1280dp-h800dp"
        private const val LongTitle = "A title long enough to need several lines on a narrow phone at a large text size"
        private const val LongAuthors = "Marie-Antoinette Writer, Bartholomew Other, Wilhelmina Third"
        private const val OtherLongTitle = "Another title that goes on and on, far past the end of any row or card"

        @JvmStatic
        @Parameters(name = "{0} {1} x{2}")
        fun cases(): List<Array<Any>> = listOf(Narrow, Phone, Wide).flatMap { screen ->
            listOf("en", "fr").flatMap { language ->
                listOf(1.0f, 2.0f).map { scale -> arrayOf<Any>(screen, language, scale) }
            }
        }
    }
}
