package app.tfdn.lirelle.feature.book.download

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.designsystem.LocalWindowWidthClass
import app.tfdn.lirelle.core.designsystem.WindowWidthClass
import app.tfdn.lirelle.core.downloads.model.DownloadFailure
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.FormatState
import app.tfdn.lirelle.core.downloads.model.PageCount
import app.tfdn.lirelle.core.downloads.model.TextName
import app.tfdn.lirelle.core.library.model.BookReading
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.testing.DeviceRule
import app.tfdn.lirelle.core.testing.LaidOutText
import app.tfdn.lirelle.core.testing.assertAllTextFits
import app.tfdn.lirelle.core.testing.sampleBookDetail
import app.tfdn.lirelle.core.testing.samplePagedDetail
import app.tfdn.lirelle.core.ui.Loadable
import app.tfdn.lirelle.core.ui.R as UiR
import app.tfdn.lirelle.feature.book.BookContent
import app.tfdn.lirelle.feature.book.BookUiState
import app.tfdn.lirelle.feature.book.R
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.annotation.GraphicsMode

/** No text of the downloads of the book page overflows or is clipped, at any width, language or text size. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Real font metrics: the default mode counts one pixel per character.
class DownloadTextFitTest(private val screen: String, language: String, fontScale: Float) {

    @get:Rule(order = 0)
    val device = DeviceRule(qualifiers = "$language-$screen", fontScale = fontScale)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val book = sampleBookDetail()
    private var page by mutableStateOf(BookUiState(Loadable.Ready(book), listening = Loadable.Ready(null)))
    private var download by mutableStateOf(BookDownloadUiState())

    /** As many pages as a count can say in four digits, most of them there. */
    private val manyPages = PageCount(1_234, 4_321)

    private val tiles = listOf(
        TileState.NotDownloaded,
        TileState.Downloading(100, DownloadFormat.Audiobook, receivedBytes = 1_070 * Mb, totalBytes = 1_080 * Mb),
        TileState.Downloading(null, DownloadFormat.Audiobook, receivedBytes = 124 * Mb, totalBytes = null),
        TileState.WaitingForWifi(percent = 30, leftBytes = 1_080 * Mb),
        TileState.WaitingForWifi(percent = null, leftBytes = null),
        TileState.Queued(percent = 30, noNetwork = true),
        TileState.Queued(percent = 30, noNetwork = true, leftBytes = 1_080 * Mb),
        TileState.Queued(percent = 30, noNetwork = false),
        TileState.Downloading(100, DownloadFormat.Text, 1_070 * Mb, 1_080 * Mb, TextName.Djvu, manyPages),
        TileState.Downloading(null, DownloadFormat.Text, 124 * Mb, totalBytes = null, textName = TextName.Djvu),
        TileState.Downloading(100, DownloadFormat.Text, 1_070 * Mb, 1_080 * Mb, textName = TextName.Djvu),
        TileState.WaitingForWifi(percent = 30, leftBytes = 1_080 * Mb, pages = manyPages),
        TileState.Queued(percent = 30, noNetwork = true, leftBytes = 1_080 * Mb, pages = manyPages),
        TileState.Failed(DownloadFailure.NoSpace(missingBytes = 1_080 * Mb)),
        TileState.Failed(DownloadFailure.Server(ApiError.ServerUnreachable)),
        TileState.Failed(DownloadFailure.Server(ApiError.ServerFailed)),
        TileState.Failed(DownloadFailure.NotAnEpub),
        TileState.Failed(DownloadFailure.Damaged),
        TileState.Failed(DownloadFailure.NotAllowed),
        TileState.Downloaded,
    )

    @Test
    fun `the tile and its line in every state`() {
        showPage()

        tiles.forEach { tile ->
            download = BookDownloadUiState(tile, enabled = true)
            assertTileWhole(compose.assertAllTextFits(), tile)
        }
    }

    private val updates = listOf(
        UpdateState.Available(bytes = 1_080 * Mb),
        UpdateState.Updating(percent = 30, receivedBytes = 324 * Mb, totalBytes = 1_080 * Mb),
        UpdateState.WaitingForWifi(percent = 30, leftBytes = 756 * Mb),
        UpdateState.Queued(percent = 30, noNetwork = true, leftBytes = 756 * Mb),
        UpdateState.Queued(percent = 0, noNetwork = false, leftBytes = 1_080 * Mb),
        UpdateState.Updating(percent = 30, receivedBytes = 324 * Mb, totalBytes = 1_080 * Mb, pages = manyPages),
        UpdateState.WaitingForWifi(percent = 30, leftBytes = 756 * Mb, pages = manyPages),
        UpdateState.Queued(percent = 30, noNetwork = true, leftBytes = 756 * Mb, pages = manyPages),
        UpdateState.Failed(DownloadFailure.NoSpace(missingBytes = 1_080 * Mb)),
        UpdateState.Failed(DownloadFailure.Server(ApiError.ServerUnreachable)),
        UpdateState.Failed(DownloadFailure.NotAnEpub),
        UpdateState.Failed(DownloadFailure.NotAllowed),
        UpdateState.Ready,
    )

    @Test
    fun `the downloaded tile and its line in every state of an update`() {
        showPage()

        updates.forEach { update ->
            download = BookDownloadUiState(TileState.Downloaded, enabled = true, update = update)
            val texts = compose.assertAllTextFits()
            assertTileWhole(texts, TileState.Downloaded)
            if (update == UpdateState.Ready) {
                val line = texts.single { it.text == compose.activity.getString(R.string.download_update_ready) }
                assertWithMessage("$line is cut short").that(line.isEllipsized).isFalse()
            }
        }
    }

    @Test
    fun `the formats sheet of a book with updates`() {
        val kept = downloaded(
            updatable(DownloadFormat.Text),
            updatable(DownloadFormat.Audiobook),
            updatable(DownloadFormat.Narration),
        )
        showSheet(situation(offered = ThreeFormats, book = kept))

        compose.assertAllTextFits()
    }

    @Test
    fun `the tile and its line for an account that may not download`() {
        showPage()

        listOf(TileState.NotAllowed, TileState.Downloaded).forEach { tile ->
            download = BookDownloadUiState(tile, allowed = false, enabled = tile == TileState.Downloaded)
            val texts = compose.assertAllTextFits()
            val line = texts.single { it.text == compose.activity.getString(R.string.download_line_not_allowed) }
            assertWithMessage("$line is cut short").that(line.isEllipsized).isFalse()
        }
    }

    /** The label of the tile is one line; the short ones are whole on a phone of an ordinary width. */
    private fun assertTileWhole(texts: List<LaidOutText>, tile: TileState) {
        val label = when (tile) {
            TileState.Downloaded -> compose.activity.getString(R.string.download_tile_downloaded)
            is TileState.Failed -> compose.activity.getString(R.string.download_tile_failed)
            else -> return
        }
        val shown = texts.filter { it.text == label && it.isLabel }
        assertWithMessage("\"$label\" of the tile").that(shown).hasSize(1)
        assertWithMessage("lines of \"$label\"").that(shown.single().lineCount).isEqualTo(1)
    }

    @Test
    fun `the page of a downloaded book without a network`() {
        page = BookUiState(
            book = Loadable.Ready(book),
            reading = BookReading(11, lastReadAt = null, totalSeconds = 0, sessionCount = 0),
            listening = Loadable.Failed(ApiError.ServerUnreachable),
            offline = true,
            noNetwork = true,
            keptFormats = emptySet(),
        )
        download = BookDownloadUiState(TileState.Downloaded, enabled = true)
        showPage()

        val texts = compose.assertAllTextFits()

        listOf(
            R.string.book_offline_chip,
            R.string.book_offline_missing_text,
            R.string.book_offline_missing_audiobook,
            R.string.book_offline_missing_narration,
            R.string.reading_offline_note,
        ).forEach { id ->
            val text = texts.single { it.text == compose.activity.getString(id) }
            assertWithMessage("$text is cut short").that(text.isEllipsized).isFalse()
        }
    }

    @Test
    fun `the page of a downloaded book that is gone from the server`() {
        page = BookUiState(
            book = Loadable.Ready(book),
            reading = BookReading(11, lastReadAt = null, totalSeconds = 0, sessionCount = 0),
            listening = Loadable.Failed(ApiError.NotFound),
            offline = true,
            noNetwork = false,
            keptFormats = emptySet(),
        )
        download = BookDownloadUiState(TileState.Downloaded, enabled = true)
        showPage()

        val texts = compose.assertAllTextFits()

        listOf(
            R.string.book_gone_chip,
            R.string.book_gone_missing_text,
            R.string.book_gone_missing_audiobook,
            R.string.book_gone_missing_narration,
            R.string.reading_device_note,
        ).forEach { id ->
            val text = texts.single { it.text == compose.activity.getString(id) }
            assertWithMessage("$text is cut short").that(text.isEllipsized).isFalse()
        }
    }

    @Test
    fun `the page of a book read by pages whose file is not downloaded, without a network and gone`() {
        val paged = samplePagedDetail("DJVU")
        val lines = mapOf(true to R.string.book_offline_missing_file, false to R.string.book_gone_missing_file)
        download = BookDownloadUiState(TileState.Downloaded, enabled = true)
        showPage()
        lines.forEach { (noNetwork, line) ->
            page = BookUiState(
                book = Loadable.Ready(paged),
                reading = BookReading(11, lastReadAt = null, totalSeconds = 0, sessionCount = 0),
                listening = Loadable.Ready(null),
                offline = true,
                noNetwork = noNetwork,
                keptFormats = emptySet(),
            )

            val texts = compose.assertAllTextFits()

            // The longest of the names a file has.
            val text = texts.single { it.text == compose.activity.getString(line, "DjVu") }
            assertWithMessage("$text is cut short").that(text.isEllipsized).isFalse()
        }
    }

    @Test
    fun `a book that is not downloaded, without a network`() {
        page = BookUiState(Loadable.Failed(ApiError.ServerUnreachable))
        showPage()

        // The button to the downloads grows with its label.
        val downloads = compose.activity.getString(UiR.string.no_network_open_downloads)
        compose.assertAllTextFits(wrappingLabels = setOf(downloads))
    }

    private val threeFormatsKept = downloaded(
        complete(DownloadFormat.Text, 2_800_000),
        record(DownloadFormat.Audiobook, FormatState.Downloading(receivedBytes = 535 * Mb, totalBytes = 1_070 * Mb)),
        complete(DownloadFormat.Narration, 1_080 * Mb),
    )

    @Test
    fun `the formats sheet with three formats in gigabytes, on a metered network`() {
        showSheet(situation(offered = ThreeFormats, network = Mobile), waitsForWifi = true)
        compose.assertAllTextFits()

        // Checked: the text locked, the button with a size.
        compose.onNodeWithTag(formatRowTag(DownloadFormat.Narration)).performScrollTo().performClick()
        compose.onNodeWithTag(formatRowTag(DownloadFormat.Audiobook)).performScrollTo().performClick()
        compose.assertAllTextFits()
    }

    @Test
    fun `the formats sheet with an estimated size`() {
        showSheet(situation(offered = listOf(EstimatedText) + ThreeFormats.drop(1)))

        compose.assertAllTextFits()
    }

    @Test
    fun `the formats sheet of a book kept and being fetched`() {
        showSheet(situation(offered = ThreeFormats, book = threeFormatsKept))

        compose.assertAllTextFits()
    }

    @Test
    fun `the formats sheet of a book read by pages, offered, fetched, and its deletion confirmed`() {
        val offered = listOf(PdfAndAudiobook[0].copy(textName = TextName.Djvu, estimated = true), PdfAndAudiobook[1])
        showSheet(situation(offered = offered))
        compose.assertAllTextFits()
    }

    @Test
    fun `the formats sheet of an album being fetched`() {
        val fetched = album(FormatState.Downloading(8 * Mb, 9 * Mb), kept = 12, name = TextName.Djvu)
        showSheet(situation(offered = Comic, book = downloaded(fetched)))

        compose.assertAllTextFits()
    }

    @Test
    fun `the confirmation of the deletion of a document and its audiobook`() {
        val kept = downloaded(
            complete(DownloadFormat.Text, 9 * Mb).copy(textName = TextName.Djvu),
            complete(DownloadFormat.Audiobook, 1_070 * Mb),
        )
        showSheet(situation(offered = PdfAndAudiobook, book = kept))
        compose.onNodeWithTag(formatRowTag(DownloadFormat.Text)).performScrollTo().performClick()
        compose.onNodeWithTag(formatRowTag(DownloadFormat.Audiobook)).performScrollTo().performClick()

        compose.onNodeWithTag(FormatsApplyTag).performScrollTo().performClick()

        compose.assertAllTextFits()
    }

    @Test
    fun `the formats sheet that only removes`() {
        showSheet(situation(offered = ThreeFormats, book = threeFormatsKept, canDownload = false))

        compose.assertAllTextFits()
    }

    @Test
    fun `the confirmation of a deletion`() {
        showSheet(situation(offered = ThreeFormats, book = threeFormatsKept))
        compose.onNodeWithTag(formatRowTag(DownloadFormat.Narration)).performScrollTo().performClick()
        compose.onNodeWithTag(formatRowTag(DownloadFormat.Audiobook)).performScrollTo().performClick()

        compose.onNodeWithTag(FormatsApplyTag).performScrollTo().performClick()

        val texts = compose.assertAllTextFits()
        val note = texts.single { it.text == compose.activity.getString(R.string.download_delete_note) }
        assertWithMessage("$note is cut short").that(note.isEllipsized).isFalse()
    }

    private fun showSheet(situation: DownloadSituation, waitsForWifi: Boolean = false) = show {
        FormatsSheetContent(
            title = "A title long enough to need several lines on a narrow phone at a large text size",
            sheet = FormatsSheetState(situation, freeBytes = 41_200 * Mb, waitsForWifi = waitsForWifi),
            onCancel = {},
            onApply = {},
        )
    }

    private fun showPage() = show {
        BookContent(
            state = page,
            onBack = {},
            onRetry = {},
            onRefresh = {},
            onOpenBook = {},
            onOpenAuthor = {},
            onOpenGenre = {},
            onRead = { _, _ -> },
            onListen = { _, _ -> },
            onNarrate = {},
            download = download,
        )
    }

    private fun show(content: @Composable () -> Unit) {
        val width = if (screen == Wide) WindowWidthClass.Expanded else WindowWidthClass.Compact
        compose.setContent {
            LirelleTheme {
                CompositionLocalProvider(LocalWindowWidthClass provides width, content = content)
            }
        }
    }

    companion object {
        private const val Narrow = "w320dp-h640dp"
        private const val Phone = "w411dp-h891dp"
        private const val Wide = "w1280dp-h800dp"

        @JvmStatic
        @Parameters(name = "{0} {1} x{2}")
        fun cases(): List<Array<Any>> = listOf(Narrow, Phone, Wide).flatMap { screen ->
            listOf("en", "fr").flatMap { language ->
                listOf(1.0f, 2.0f).map { scale -> arrayOf<Any>(screen, language, scale) }
            }
        }
    }
}
