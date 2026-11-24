package app.tfdn.lirelle.feature.downloads

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.designsystem.LocalWindowWidthClass
import app.tfdn.lirelle.core.designsystem.WindowWidthClass
import app.tfdn.lirelle.core.downloads.model.DownloadFailure
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.TextName
import app.tfdn.lirelle.core.downloads.nameRes
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.model.PagedKind
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.ui.fileSize
import app.tfdn.lirelle.core.ui.glyph
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import app.tfdn.lirelle.core.downloads.R as DownloadsR
import app.tfdn.lirelle.core.ui.R as UiR

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class DownloadsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val calls = mutableListOf<String>()
    private val actions = DownloadsActions(
        onCancel = { calls += "cancel $it" },
        onRetry = { calls += "retry $it" },
        onDownloadNow = { calls += "now" },
        onAlwaysAllowMobile = { calls += "always" },
        onAskDelete = { calls += "delete $it" },
        onSnackbarDone = { snackbar, undo -> calls += "snackbar ${snackbar.kind} $undo" },
    )

    private val dune =
        DownloadedRow(BookId("1"), "Dune", "Frank Writer", null, listOf(DownloadFormat.Text), 4 * Mb, percent = 11)
    private val redMars = DownloadedRow(
        bookId = BookId("2"),
        title = "Red Mars",
        authors = "Kim Author",
        cover = null,
        formats = listOf(DownloadFormat.Text, DownloadFormat.Audiobook, DownloadFormat.Narration),
        bytes = 1_926 * Mb,
        percent = null,
    )
    private val listed = DownloadsUiState(
        storage = StorageUi(1_930 * Mb, bookCount = 2, freeBytes = 41 * Gb, totalBytes = 128 * Gb),
        books = listOf(dune, redMars),
        loaded = true,
    )

    private fun show(
        state: DownloadsUiState,
        width: WindowWidthClass = WindowWidthClass.Compact,
        onOpenBook: (BookId) -> Unit = {},
        onBrowseLibrary: () -> Unit = {},
    ) {
        compose.setContent {
            LirelleTheme {
                CompositionLocalProvider(LocalWindowWidthClass provides width) {
                    DownloadsScreen(state, actions, onOpenBook, onBrowseLibrary)
                }
            }
        }
    }

    private fun string(id: Int, vararg args: Any) = context.getString(id, *args)

    @Test
    fun `the storage says what the downloads take, how many books and what is free`() {
        show(listed)

        compose.onNodeWithText(string(R.string.downloads_storage_used, context.fileSize(1_930 * Mb)))
            .assertIsDisplayed()
        compose.onNodeWithText(context.resources.getQuantityString(R.plurals.downloads_book_count, 2, 2))
            .assertIsDisplayed()
        compose.onNodeWithText(string(R.string.downloads_storage_free, context.fileSize(41 * Gb))).assertIsDisplayed()
        compose.onNodeWithTag(StorageBarTag).assert(
            hasContentDescription(
                string(
                    R.string.downloads_storage_bar,
                    context.fileSize(1_930 * Mb),
                    context.fileSize(41 * Gb),
                    context.fileSize(128 * Gb),
                ),
            ),
        )
    }

    @Test
    fun `a downloaded book shows its title, its author, its formats and its size`() {
        show(listed)

        val row = hasAnyAncestor(hasTestTag(downloadedRowTag(BookId("2"))))
        compose.onNode(hasText("Red Mars") and row, useUnmergedTree = true).assertIsDisplayed()
        compose.onNode(hasText("Kim Author") and row, useUnmergedTree = true).assertIsDisplayed()
        compose.onNode(hasText(context.fileSize(1_926 * Mb)) and row, useUnmergedTree = true).assertIsDisplayed()
        DownloadFormat.entries
            .map { hasContentDescription(string(nameRes(it, TextName.Text))) and row }
            .forEach { compose.onNode(it, useUnmergedTree = true).assertExists() }
    }

    @Test
    fun `a kept text read by pages has its own glyph, named by its file for a screen reader`() {
        val names = listOf(TextName.Pdf, TextName.Cbz, TextName.Cbr, TextName.Cb7, TextName.Djvu)
        val said = listOf("PDF", "CBZ", "CBR", "CB7", "DjVu")
        val formats = listOf(DownloadFormat.Text, DownloadFormat.Audiobook)
        val rows = names.mapIndexed { index, name ->
            DownloadedRow(paged(index), "Book $index", "", null, formats, 4 * Mb, percent = null, textName = name)
        }
        show(listed.copy(books = rows + dune))

        said.forEachIndexed { index, word ->
            val row = hasAnyAncestor(hasTestTag(downloadedRowTag(paged(index))))
            compose.onNode(hasContentDescription(word) and row, useUnmergedTree = true).assertExists()
            compose.onNode(hasContentDescription("Audiobook") and row, useUnmergedTree = true).assertExists()
            compose.onNode(hasContentDescription("Text") and row, useUnmergedTree = true).assertDoesNotExist()
        }
        val reflowable = hasAnyAncestor(hasTestTag(downloadedRowTag(BookId("1"))))
        compose.onNode(hasContentDescription("Text") and reflowable, useUnmergedTree = true).assertExists()
    }

    private fun paged(index: Int) = BookId("paged-$index")

    @Test
    fun `the glyph of a text read by pages is the one of the page reader's chip, any other text's the book`() {
        fun glyph(name: TextName) = formatIcon(DownloadFormat.Text, name)

        assertThat(glyph(TextName.Text)).isEqualTo(Icons.AutoMirrored.Outlined.MenuBook)
        assertThat(glyph(TextName.Pdf)).isEqualTo(PagedKind.Pdf.glyph())
        assertThat(glyph(TextName.Djvu)).isEqualTo(PagedKind.Djvu.glyph())
        listOf(TextName.Cbz, TextName.Cbr, TextName.Cb7)
            .forEach { assertThat(glyph(it)).isEqualTo(PagedKind.Comic.glyph()) }
        assertThat(setOf(PagedKind.Pdf.glyph(), PagedKind.Djvu.glyph(), PagedKind.Comic.glyph(), glyph(TextName.Text)))
            .hasSize(4)
        // The other formats are what they were, whatever the text is.
        assertThat(formatIcon(DownloadFormat.Audiobook, TextName.Cbz)).isEqualTo(Icons.Outlined.Headphones)
        assertThat(formatIcon(DownloadFormat.Narration, TextName.Pdf)).isEqualTo(Icons.Outlined.GraphicEq)
    }

    @Test
    fun `only a book with a progress shows it`() {
        show(listed)

        compose.onNode(
            hasText(string(UiR.string.progress_percent, 11)) and
                hasAnyAncestor(hasTestTag(downloadedRowTag(BookId("1")))),
            useUnmergedTree = true,
        ).assertIsDisplayed()
        compose.onNode(hasTestTag(RowProgressTag) and hasAnyAncestor(hasTestTag(downloadedRowTag(BookId("2")))), true)
            .assertDoesNotExist()
        compose.onNodeWithTag(InProgressHeadingTag).assertDoesNotExist()
    }

    @Test
    fun `a tap on a book opens its page`() {
        val opened = mutableListOf<BookId>()
        show(listed, onOpenBook = { opened += it })

        compose.onNodeWithTag(downloadedRowTag(BookId("2"))).performClick()

        assertThat(opened).containsExactly(BookId("2"))
    }

    @Test
    fun `the menu button of a book offers to delete it from the device`() {
        show(listed)

        compose.onNodeWithContentDescription(string(R.string.downloads_row_menu, "Dune")).performClick()
        compose.onNodeWithText(string(R.string.downloads_delete)).performClick()

        assertThat(calls).containsExactly("delete 1")
        compose.onNodeWithText(string(R.string.downloads_delete)).assertDoesNotExist()
    }

    @Test
    fun `a long press on a book offers the same`() {
        val opened = mutableListOf<BookId>()
        show(listed, onOpenBook = { opened += it })

        compose.onNodeWithTag(downloadedRowTag(BookId("2"))).performTouchInput { longClick() }
        compose.onNodeWithText(string(R.string.downloads_delete)).performClick()

        assertThat(calls).containsExactly("delete 2")
        assertThat(opened).isEmpty()
    }

    private val inProgress = listed.copy(
        inProgress = listOf(
            ProgressRow(
                bookId = BookId("3"),
                title = "Slow Horses",
                cover = null,
                formats = listOf(DownloadFormat.Audiobook),
                status = ProgressStatus.Active(124 * Mb, 412 * Mb),
            ),
            ProgressRow(
                bookId = BookId("4"),
                title = "Project Rocket",
                cover = null,
                formats = listOf(DownloadFormat.Text, DownloadFormat.Narration),
                status = ProgressStatus.Queued(378 * Mb),
            ),
            ProgressRow(
                bookId = BookId("5"),
                title = "Fire and Ink",
                cover = null,
                formats = listOf(DownloadFormat.Text),
                status = ProgressStatus.Failed(DownloadFailure.Server(ApiError.ServerUnreachable)),
            ),
        ),
        wifi = WifiBannerUi(bookCount = 2, leftBytes = 666 * Mb),
    )

    @Test
    fun `the download being fetched says its formats and how far it is`() {
        show(inProgress)

        val audiobook = string(DownloadsR.string.downloads_format_audiobook)
        compose.onNodeWithText("Slow Horses").assertIsDisplayed()
        compose.onNodeWithText(
            string(R.string.downloads_progress_of, audiobook, context.fileSize(124 * Mb), context.fileSize(412 * Mb)),
        ).assertIsDisplayed()
        compose.onNode(hasTestTag(RowProgressTag) and hasAnyAncestor(hasTestTag(progressRowTag(BookId("3")))))
            .assertExists()
    }

    @Test
    fun `a queued download says so, without a bar`() {
        show(inProgress)

        val formats = string(DownloadsR.string.downloads_formats_text_narration, "Text")
        compose.onNodeWithText(string(R.string.downloads_progress_queued_size, formats, context.fileSize(378 * Mb)))
            .assertIsDisplayed()
        compose.onNode(hasTestTag(RowProgressTag) and hasAnyAncestor(hasTestTag(progressRowTag(BookId("4")))))
            .assertDoesNotExist()
    }

    @Test
    fun `a failed download says why and can be tried again`() {
        show(inProgress)

        compose.onNodeWithText(string(UiR.string.library_error_unreachable)).assertIsDisplayed()
        compose.onNodeWithTag(retryTag(BookId("5"))).performClick()

        assertThat(calls).containsExactly("retry 5")
    }

    @Test
    fun `each download in progress can be cancelled, by a button that names its book`() {
        show(inProgress)

        compose.onNodeWithContentDescription(string(R.string.downloads_cancel, "Project Rocket")).performClick()
        compose.onNodeWithContentDescription(string(R.string.downloads_cancel, "Fire and Ink")).performClick()

        assertThat(calls).containsExactly("cancel 4", "cancel 5").inOrder()
    }

    @Test
    fun `the Wi-Fi banner says what waits and offers to go on`() {
        show(inProgress)

        compose.onNodeWithText(string(R.string.downloads_wifi_title)).assertIsDisplayed()
        compose.onNodeWithText(
            context.resources.getQuantityString(R.plurals.downloads_wifi_left, 2, 2, context.fileSize(666 * Mb)),
        ).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.downloads_wifi_now)).performClick()
        compose.onNodeWithText(string(R.string.downloads_wifi_always)).performClick()

        assertThat(calls).containsExactly("now", "always").inOrder()
    }

    @Test
    fun `a queue waiting for the network says so, without the banner`() {
        show(inProgress.copy(wifi = null, waitingForNetwork = true))

        compose.onNodeWithText(string(R.string.downloads_waiting_network)).assertIsDisplayed()
        compose.onNodeWithTag(WifiBannerTag).assertDoesNotExist()
    }

    @Test
    fun `nothing downloaded explains how to keep a book and leads to the library`() {
        var browsed = 0
        val nothing = DownloadsUiState(storage = StorageUi(0, 0, 44 * Gb, 128 * Gb), loaded = true)
        show(nothing, onBrowseLibrary = { browsed++ })

        compose.onNodeWithText(string(R.string.downloads_storage_none)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.downloads_storage_free, context.fileSize(44 * Gb))).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.downloads_empty_title)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.downloads_empty_message)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.downloads_browse)).performClick()

        assertThat(browsed).isEqualTo(1)
    }

    @Test
    fun `nothing says empty before the device said what it keeps`() {
        show(DownloadsUiState())

        compose.onNodeWithTag(DownloadsEmptyTag).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.downloads_storage_none)).assertDoesNotExist()
    }

    @Test
    fun `a phone shows one column`() {
        show(listed)

        compose.onNodeWithTag(DownloadsListTag).assertIsDisplayed()
        compose.onNodeWithTag(DownloadsGridTag).assertDoesNotExist()
        val first = compose.onNodeWithTag(downloadedRowTag(BookId("1"))).fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithTag(downloadedRowTag(BookId("2"))).fetchSemanticsNode().boundsInRoot
        assertThat(second.top).isAtLeast(first.bottom)
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun `a wide window shows the books side by side on a grid`() {
        show(inProgress, width = WindowWidthClass.Expanded)

        compose.onNodeWithTag(DownloadsGridTag).assertIsDisplayed()
        compose.onNodeWithTag(DownloadsListTag).assertDoesNotExist()
        val first = compose.onNodeWithTag(downloadedRowTag(BookId("1"))).fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithTag(downloadedRowTag(BookId("2"))).fetchSemanticsNode().boundsInRoot
        assertThat(second.top).isEqualTo(first.top)
        assertThat(second.left).isAtLeast(first.right)
        // The storage is beside the title, not under it.
        val title = compose.onNodeWithText(string(R.string.downloads_title)).fetchSemanticsNode().boundsInRoot
        val storage = compose.onNodeWithTag(StorageHeaderTag).fetchSemanticsNode().boundsInRoot
        assertThat(storage.left).isAtLeast(title.right)
        assertThat(storage.top).isLessThan(title.bottom)
    }

    @Test
    fun `a deletion is said, and can be undone`() {
        val deleted = DownloadsSnackbar(DownloadsSnackbar.Kind.Deleted, serial = 1, bookId = BookId("1"))
        show(listed.copy(snackbar = deleted))

        compose.onNodeWithText(string(R.string.downloads_deleted)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.downloads_undo)).performClick()
        compose.waitForIdle()

        assertThat(calls).containsExactly("snackbar Deleted true")
    }

    @Test
    fun `the mobile network allowed is said, and can be undone`() {
        show(listed.copy(snackbar = DownloadsSnackbar(DownloadsSnackbar.Kind.MobileAllowed, serial = 1)))

        compose.onNodeWithText(string(R.string.downloads_mobile_allowed)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.downloads_undo)).performClick()
        compose.waitForIdle()

        assertThat(calls).containsExactly("snackbar MobileAllowed true")
    }

    @Test
    fun `a book in use is said, with nothing to undo`() {
        show(listed.copy(snackbar = DownloadsSnackbar(DownloadsSnackbar.Kind.InUse, serial = 1)))

        compose.onNodeWithText(string(R.string.downloads_in_use)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.downloads_undo)).assertDoesNotExist()
    }
}
