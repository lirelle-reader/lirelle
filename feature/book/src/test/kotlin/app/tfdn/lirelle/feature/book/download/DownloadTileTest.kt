package app.tfdn.lirelle.feature.book.download

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.downloads.model.DownloadFailure
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.PageCount
import app.tfdn.lirelle.core.downloads.model.TextName
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.testing.sampleBookDetail
import app.tfdn.lirelle.core.ui.Loadable
import app.tfdn.lirelle.core.ui.R as UiR
import app.tfdn.lirelle.core.ui.fileSize
import app.tfdn.lirelle.feature.book.BookContent
import app.tfdn.lirelle.feature.book.BookUiState
import app.tfdn.lirelle.feature.book.R
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The download tile of the book page and the line under the tiles, in each state. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class DownloadTileTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var download by mutableStateOf(BookDownloadUiState())
    private var tileTaps = 0
    private var lineActions = 0
    private var inUseShown = 0

    private fun text(id: Int, vararg args: Any) = context.getString(id, *args)

    private fun size(bytes: Long) = context.fileSize(bytes)

    private fun show(update: UpdateState) {
        show(TileState.Downloaded)
        download = download.copy(update = update)
    }

    private fun show(tile: TileState, allowed: Boolean = true, enabled: Boolean = true) {
        download = BookDownloadUiState(tile, allowed, enabled)
        compose.setContent {
            LirelleTheme {
                BookContent(
                    state = BookUiState(Loadable.Ready(sampleBookDetail()), listening = Loadable.Ready(null)),
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
                    downloadActions = DownloadActions(
                        onTile = { tileTaps++ },
                        onLineAction = { lineActions++ },
                        onInUseShown = { inUseShown++ },
                    ),
                )
            }
        }
    }

    private fun tile() = compose.onNodeWithTag(DownloadTileTag).performScrollTo()

    private fun assertLine(text: String, action: Int?) {
        compose.onNodeWithTag(DownloadLineTag, useUnmergedTree = true).performScrollTo().assertTextEquals(text)
        if (action == null) {
            compose.onAllNodesWithTag(DownloadLineActionTag).assertCountEquals(0)
        } else {
            compose.onNodeWithTag(DownloadLineActionTag)
                .performScrollTo()
                .assertContentDescriptionEquals(text(action))
                .performClick()
            assertThat(lineActions).isEqualTo(1)
        }
    }

    private fun assertNoLine() =
        compose.onAllNodesWithTag(DownloadLineTag, useUnmergedTree = true).assertCountEquals(0)

    @Test
    fun `not downloaded, the tile offers to and has no line`() {
        show(TileState.NotDownloaded)

        tile().assertIsEnabled().performClick()

        compose.onNodeWithText(text(R.string.action_download)).assertExists()
        assertThat(tileTaps).isEqualTo(1)
        assertNoLine()
    }

    @Test
    fun `a book with nothing to download has its tile disabled`() {
        show(TileState.NotDownloaded, enabled = false)

        tile().assertIsNotEnabled()
        assertNoLine()
    }

    @Test
    fun `downloading, the tile says how far and the line what, with a way to cancel`() {
        show(TileState.Downloading(30, DownloadFormat.Audiobook, receivedBytes = 124 * Mb, totalBytes = 412 * Mb))

        tile().assertIsEnabled()
        compose.onNodeWithText(text(R.string.download_tile_percent, 30)).assertExists()
        val audiobook = text(R.string.download_format_audiobook)
        assertThat(size(124 * Mb)).isEqualTo("124 MB")
        assertLine("$audiobook · 124 MB of 412 MB", R.string.download_action_cancel)
    }

    @Test
    fun `while a size is not known the tile names the format, without a percentage`() {
        show(TileState.Downloading(null, DownloadFormat.Text, receivedBytes = Mb, totalBytes = null))

        tile().assertTextEquals(text(R.string.download_format_text))
        assertLine(text(R.string.download_format_text), R.string.download_action_cancel)
    }

    private val reached = PageCount(12, 48)

    private fun bar(tag: String, fraction: Float) = compose.onNodeWithTag(tag, useUnmergedTree = true)
        .assert(hasProgressBarRangeInfo(ProgressBarRangeInfo(fraction, 0f..1f)))

    @Test
    fun `an album being fetched says its pages on the line, the bar of the tile following them`() {
        show(TileState.Downloading(25, DownloadFormat.Text, 8 * Mb, 9 * Mb, TextName.Cbz, reached))

        compose.onNodeWithText(text(R.string.download_tile_percent, 25)).assertExists()
        bar(DownloadBarTag, 0.25f)
        assertLine("CBZ · 12 pages of 48", R.string.download_action_cancel)
    }

    @Test
    fun `a text read by pages is named by its file on the tile and on the line`() {
        val names = mapOf(TextName.Pdf to "PDF", TextName.Cbr to "CBR", TextName.Cb7 to "CB7", TextName.Djvu to "DjVu")
        show(TileState.NotDownloaded)

        names.forEach { (name, word) ->
            val unknown = TileState.Downloading(null, DownloadFormat.Text, Mb, totalBytes = null, textName = name)
            download = BookDownloadUiState(unknown, enabled = true)
            tile().assertTextEquals(word)
            compose.onNodeWithTag(DownloadLineTag, useUnmergedTree = true).assertTextEquals(word)

            download = BookDownloadUiState(unknown.copy(percent = 11, totalBytes = 9 * Mb), enabled = true)
            compose.onNodeWithTag(DownloadLineTag, useUnmergedTree = true)
                .assertTextEquals("$word · ${size(Mb)} of ${size(9 * Mb)}")
        }
    }

    @Test
    fun `an album waiting for Wi-Fi says the pages it reached and offers to download anyway`() {
        show(TileState.WaitingForWifi(percent = 25, leftBytes = Mb, pages = reached))

        tile().assertTextEquals(text(R.string.download_tile_waiting))
        bar(DownloadBarTag, 0.25f)
        assertLine("Waiting for an unmetered network · 12 pages of 48", R.string.download_action_anyway)
    }

    @Test
    fun `an album waiting for any network says the pages it reached, and only offers to cancel`() {
        show(TileState.Queued(percent = 25, noNetwork = true, leftBytes = Mb, pages = reached))

        assertLine("Waiting for the network · 12 pages of 48", R.string.download_action_cancel)
    }

    @Test
    fun `an album behind another book is in the queue, as any download`() {
        show(TileState.Queued(percent = 25, noNetwork = false, leftBytes = Mb, pages = reached))

        assertLine(text(R.string.download_line_queued), R.string.download_action_cancel)
    }

    @Test
    @Config(qualifiers = "fr-w411dp-h891dp")
    fun `the pages of an album, in French`() {
        show(TileState.Downloading(25, DownloadFormat.Text, 8 * Mb, 9 * Mb, TextName.Cbz, reached))
        compose.onNodeWithTag(DownloadLineTag, useUnmergedTree = true).assertTextEquals("CBZ · 12 pages sur 48")

        download = BookDownloadUiState(TileState.WaitingForWifi(25, Mb, reached), enabled = true)
        compose.onNodeWithTag(DownloadLineTag, useUnmergedTree = true)
            .assertTextEquals("En attente d'un réseau non limité · 12 pages sur 48")

        download = BookDownloadUiState(TileState.Queued(25, noNetwork = true, Mb, reached), enabled = true)
        compose.onNodeWithTag(DownloadLineTag, useUnmergedTree = true)
            .assertTextEquals("En attente du réseau · 12 pages sur 48")
    }

    @Test
    fun `the update of an album says its pages, fetched or waiting`() {
        show(UpdateState.Updating(percent = 25, receivedBytes = 8 * Mb, totalBytes = 9 * Mb, pages = reached))
        assertDownloadedTile(bar = true)
        bar(UpdateBarTag, 0.25f)
        assertLine("Update · 12 pages of 48", R.string.download_action_cancel)

        download = download.copy(update = UpdateState.WaitingForWifi(25, Mb, reached))
        compose.onNodeWithTag(DownloadLineTag, useUnmergedTree = true)
            .assertTextEquals("Update waiting for an unmetered network · 12 pages of 48")

        download = download.copy(update = UpdateState.Queued(25, noNetwork = true, Mb, reached))
        compose.onNodeWithTag(DownloadLineTag, useUnmergedTree = true)
            .assertTextEquals("Update waiting for the network · 12 pages of 48")

        download = download.copy(update = UpdateState.Queued(25, noNetwork = false, Mb, reached))
        compose.onNodeWithTag(DownloadLineTag, useUnmergedTree = true)
            .assertTextEquals(text(R.string.download_update_queued))
    }

    @Test
    @Config(qualifiers = "fr-w411dp-h891dp")
    fun `the update of an album, in French`() {
        show(UpdateState.Updating(percent = 25, receivedBytes = 8 * Mb, totalBytes = 9 * Mb, pages = reached))
        compose.onNodeWithTag(DownloadLineTag, useUnmergedTree = true)
            .assertTextEquals("Mise à jour · 12 pages sur 48")

        download = download.copy(update = UpdateState.WaitingForWifi(25, Mb, reached))
        compose.onNodeWithTag(DownloadLineTag, useUnmergedTree = true)
            .assertTextEquals("Mise à jour en attente d'un réseau non limité · 12 pages sur 48")

        download = download.copy(update = UpdateState.Queued(25, noNetwork = true, Mb, reached))
        compose.onNodeWithTag(DownloadLineTag, useUnmergedTree = true)
            .assertTextEquals("Mise à jour en attente du réseau · 12 pages sur 48")
    }

    @Test
    fun `the bar of a download made of one file is at its percentage, as it was`() {
        show(TileState.Downloading(30, DownloadFormat.Audiobook, receivedBytes = 124 * Mb, totalBytes = 412 * Mb))

        bar(DownloadBarTag, 0.3f)
    }

    @Test
    fun `waiting for Wi-Fi, the line says what is left and offers to download anyway`() {
        show(TileState.WaitingForWifi(percent = 30, leftBytes = 288 * Mb))

        tile().assertTextEquals(text(R.string.download_tile_waiting))
        assertLine(text(R.string.download_line_wifi_left, "288 MB"), R.string.download_action_anyway)
    }

    @Test
    fun `waiting for Wi-Fi with a size not known says so alone`() {
        show(TileState.WaitingForWifi(percent = null, leftBytes = null))

        assertLine(text(R.string.download_line_wifi), R.string.download_action_anyway)
    }

    @Test
    fun `without any network the line says so, and only offers to cancel`() {
        show(TileState.Queued(percent = 30, noNetwork = true))

        tile().assertTextEquals(text(R.string.download_tile_waiting))
        assertLine(text(R.string.download_line_network), R.string.download_action_cancel)
    }

    @Test
    fun `without any network the line says what is left, when it is known`() {
        show(TileState.Queued(percent = 30, noNetwork = true, leftBytes = 288 * Mb))

        assertLine(text(R.string.download_line_network_left, "288 MB"), R.string.download_action_cancel)
    }

    @Test
    fun `behind another book it is in the queue`() {
        show(TileState.Queued(percent = 0, noNetwork = false))

        assertLine(text(R.string.download_line_queued), R.string.download_action_cancel)
    }

    @Test
    fun `failed for lack of space, the line says how much is missing and offers to retry`() {
        show(TileState.Failed(DownloadFailure.NoSpace(missingBytes = 200 * Mb)))

        tile().assertTextEquals(text(R.string.download_tile_failed))
        assertLine(text(R.string.download_failure_no_space, "200 MB"), R.string.download_action_retry)
    }

    @Test
    fun `failed by the server, the line says its sentence`() {
        show(TileState.Failed(DownloadFailure.Server(ApiError.ServerFailed)))

        assertLine(text(UiR.string.library_error_server_failed), R.string.download_action_retry)
    }

    @Test
    fun `failed on a file that is not a book, the line says so`() {
        show(TileState.Failed(DownloadFailure.NotAnEpub))

        assertLine(text(R.string.download_failure_not_epub), R.string.download_action_retry)
    }

    @Test
    fun `refused by the server for the account's rights, the line says downloads are not allowed`() {
        show(TileState.Failed(DownloadFailure.NotAllowed))

        assertLine(text(R.string.download_line_not_allowed), R.string.download_action_retry)
    }

    @Test
    fun `a kept file missing is to download again`() {
        show(TileState.Failed(DownloadFailure.Damaged))

        assertLine(text(R.string.download_failure_damaged), R.string.download_action_retry)
    }

    @Test
    fun `downloaded, the tile says so and opens the sheet`() {
        show(TileState.Downloaded)

        tile().assertTextEquals(text(R.string.download_tile_downloaded)).performClick()

        assertThat(tileTaps).isEqualTo(1)
        assertNoLine()
    }

    @Test
    fun `an account that may not download has the tile disabled and is told why`() {
        show(TileState.NotAllowed, allowed = false, enabled = false)

        tile().assertIsNotEnabled().assertTextEquals(text(R.string.action_download))
        assertLine(text(R.string.download_line_not_allowed), action = null)
    }

    @Test
    fun `an account that may not download can still open what it keeps`() {
        show(TileState.Downloaded, allowed = false)

        tile().assertIsEnabled().performClick()

        assertThat(tileTaps).isEqualTo(1)
        assertLine(text(R.string.download_line_not_allowed), action = null)
    }

    @Test
    fun `the tile follows the download`() {
        show(TileState.NotDownloaded)

        download = BookDownloadUiState(TileState.Downloaded, enabled = true)

        tile().assertTextEquals(text(R.string.download_tile_downloaded))
    }

    @Test
    fun `a deletion refused because the book is in use is said`() {
        show(TileState.Downloaded)

        download = download.copy(inUse = true)

        compose.onNodeWithText(text(R.string.download_in_use)).assertExists()
    }

    @Test
    fun `the formats sheet opens over the page`() {
        show(TileState.NotDownloaded)

        download = download.copy(sheet = FormatsSheetState(situation(offered = ThreeFormats), 41 * Gb, false))

        compose.onNodeWithTag(FormatsSheetTag).assertExists()
        compose.onNodeWithText(text(R.string.download_sheet_narration)).assertExists()
    }

    private fun assertDownloadedTile(bar: Boolean) {
        tile().assertIsEnabled().assertTextEquals(text(R.string.download_tile_downloaded))
        compose.onAllNodesWithTag(UpdateBarTag, useUnmergedTree = true).assertCountEquals(if (bar) 1 else 0)
    }

    @Test
    fun `an update that is available is offered on the line, the tile staying downloaded`() {
        show(UpdateState.Available(bytes = 1_080 * Mb))

        assertDownloadedTile(bar = false)
        assertLine(text(R.string.download_update_available, size(1_080 * Mb)), R.string.download_action_update)
    }

    @Test
    fun `updating, the line says how far, the tile has its thin bar, and it can be cancelled`() {
        show(UpdateState.Updating(percent = 30, receivedBytes = 324 * Mb, totalBytes = 1_080 * Mb))

        assertDownloadedTile(bar = true)
        assertLine("Update · 324 MB of 1.08 GB", R.string.download_action_cancel)
    }

    @Test
    fun `an update waiting for Wi-Fi says what is left and offers to download anyway`() {
        show(UpdateState.WaitingForWifi(percent = 30, leftBytes = 756 * Mb))

        assertDownloadedTile(bar = true)
        assertLine("Update waiting for an unmetered network · 756 MB left", R.string.download_action_anyway)
    }

    @Test
    fun `an update waiting for a network says what is left and can be cancelled`() {
        show(UpdateState.Queued(percent = 30, noNetwork = true, leftBytes = 756 * Mb))

        assertDownloadedTile(bar = true)
        assertLine(text(R.string.download_update_network_left, "756 MB"), R.string.download_action_cancel)
    }

    @Test
    fun `an update in the queue says so and can be cancelled`() {
        show(UpdateState.Queued(percent = 0, noNetwork = false, leftBytes = 756 * Mb))

        assertLine(text(R.string.download_update_queued), R.string.download_action_cancel)
    }

    @Test
    fun `an update that failed says why and offers to retry`() {
        show(UpdateState.Failed(DownloadFailure.NotAnEpub))

        assertDownloadedTile(bar = false)
        assertLine("Update failed: the file received is not a readable book", R.string.download_action_retry)
    }

    @Test
    fun `an update that is ready says when it is applied, without an action`() {
        show(UpdateState.Ready)

        assertDownloadedTile(bar = false)
        assertLine(text(R.string.download_update_ready), action = null)
    }

    @Test
    fun `an ordinary download or a refusal is said rather than an update`() {
        show(TileState.Queued(percent = 30, noNetwork = false))
        download = download.copy(update = UpdateState.Available(bytes = Mb))
        compose.onNodeWithTag(DownloadLineTag, useUnmergedTree = true)
            .assertTextEquals(text(R.string.download_line_queued))

        download = BookDownloadUiState(TileState.Downloaded, allowed = false, update = UpdateState.Available(Mb))
        compose.onNodeWithTag(DownloadLineTag, useUnmergedTree = true)
            .assertTextEquals(text(R.string.download_line_not_allowed))
    }
}
