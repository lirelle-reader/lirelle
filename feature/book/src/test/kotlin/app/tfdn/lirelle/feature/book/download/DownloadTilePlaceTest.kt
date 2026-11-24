package app.tfdn.lirelle.feature.book.download

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.downloads.model.DownloadFailure
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.testing.DeviceRule
import app.tfdn.lirelle.core.testing.sampleBookDetail
import app.tfdn.lirelle.core.ui.Loadable
import app.tfdn.lirelle.feature.book.BookContent
import app.tfdn.lirelle.feature.book.BookUiState
import app.tfdn.lirelle.feature.book.ListenActionTag
import app.tfdn.lirelle.feature.book.MainActionTag
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.annotation.GraphicsMode

/** The download tile keeps its place and its width on its own line, whatever the download does. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Real font metrics: the default mode counts one pixel per character.
class DownloadTilePlaceTest(language: String) {

    @get:Rule(order = 0)
    val device = DeviceRule(qualifiers = "$language-w411dp-h891dp", fontScale = 1f)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var download by mutableStateOf(BookDownloadUiState(TileState.NotDownloaded, enabled = true))

    private val busy = listOf(
        TileState.Downloading(30, DownloadFormat.Audiobook, receivedBytes = 124 * Mb, totalBytes = 412 * Mb),
        TileState.Downloading(100, DownloadFormat.Audiobook, receivedBytes = 412 * Mb, totalBytes = 412 * Mb),
        TileState.Downloading(null, DownloadFormat.Audiobook, receivedBytes = Mb, totalBytes = null),
        TileState.WaitingForWifi(percent = 30, leftBytes = 288 * Mb),
        TileState.Queued(percent = 30, noNetwork = true),
        TileState.Queued(percent = null, noNetwork = false),
        TileState.Failed(DownloadFailure.Damaged),
        TileState.NotAllowed,
    )

    @Test
    fun `the tile has the same place and the same size in every state of a download`() {
        show()
        val idle = tile()
        assertOwnLine(idle, TileState.NotDownloaded)

        busy.forEach { state ->
            download = BookDownloadUiState(state, enabled = true)

            val shown = tile()
            assertWithMessage("left of the tile, $state").that(shown.left).isEqualTo(idle.left)
            assertWithMessage("top of the tile, $state").that(shown.top).isEqualTo(idle.top)
            assertWithMessage("width of the tile, $state").that(shown.width).isEqualTo(idle.width)
            assertWithMessage("height of the tile, $state").that(shown.height).isEqualTo(idle.height)
            assertOwnLine(shown, state)
        }
    }

    @Test
    fun `downloaded, the tile is still on its own line, in the middle`() {
        show()

        download = BookDownloadUiState(TileState.Downloaded, enabled = true)

        assertOwnLine(tile(), TileState.Downloaded)
    }

    private fun tile(): DpRect = compose.onNodeWithTag(DownloadTileTag).getUnclippedBoundsInRoot()

    /** The tile is alone on its line: under the two reading actions, in the middle of the block. */
    private fun assertOwnLine(tile: DpRect, state: TileState) {
        val block = compose.onNodeWithTag("book-actions").getUnclippedBoundsInRoot()
        listOf(MainActionTag, ListenActionTag).forEach { action ->
            val above = compose.onNodeWithTag(action).getUnclippedBoundsInRoot()
            assertWithMessage("the tile is under $action, $state").that(tile.top).isAtLeast(above.bottom)
        }
        assertWithMessage("the middle of the tile, $state")
            .that((tile.left + tile.right).value / 2)
            .isWithin(1f)
            .of((block.left + block.right).value / 2)
    }

    private fun show() = compose.setContent {
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
            )
        }
    }

    companion object {
        @JvmStatic
        @Parameters(name = "{0}")
        fun cases(): List<Array<Any>> = listOf(arrayOf<Any>("en"), arrayOf<Any>("fr"))
    }
}
